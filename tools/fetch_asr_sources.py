"""Prepare the pinned whisper.cpp CPU source tree for the native ASR bridge.

Source archives, revisions, hashes and licenses are fixed in
asr_sources.lock.json. Run this explicitly before building; CMake does not
clone, update or download anything at configure time. Only the official
source archive is fetched here, never model weights.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name('asr_sources.lock.json')
DEST = ROOT / 'core/translation/src/main/cpp/third_party'
NOTICES = ROOT / 'core/translation/src/main/assets/translation/licenses'


def retain_license(target):
    NOTICES.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(target / 'LICENSE', NOTICES / 'whisper.cpp-MIT.txt')


def archive_root(entry):
    repo = entry['repo'].rsplit('/', 1)[-1]
    return repo + '-' + entry['revision']


def checked_destination(target, member_name, root_name):
    if '\x00' in member_name or '\\' in member_name:
        raise ValueError('Archive entry has an unsafe name')
    path = PurePosixPath(member_name)
    if path.is_absolute() or '..' in path.parts:
        raise ValueError('Archive path escapes source folder')
    if not path.parts or path.parts[0] != root_name:
        raise ValueError('Archive entry is outside the pinned source root')
    parts = path.parts[1:]
    if not parts:
        return None, None
    if any(part in ('', '.', '..') or ':' in part for part in parts):
        raise ValueError('Archive path escapes source folder')
    destination = target.joinpath(*parts).resolve()
    if target.resolve() not in destination.parents:
        raise ValueError('Archive path escapes source folder')
    return path, destination


def prepare(entry):
    target = DEST / entry['path']
    marker = target / '.lmreader-source.json'
    if marker.exists():
        try:
            current = json.loads(marker.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            current = None
        if current == entry:
            retain_license(target)
            return entry['path'] + ' ready'
        shutil.rmtree(target)
    elif target.exists():
        raise ValueError('Refusing to replace unmarked source directory: ' + entry['path'])

    request = urllib.request.Request(
        entry['url'], headers={'User-Agent': 'lmreader-source-prep'})
    data = urllib.request.urlopen(request, timeout=120).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != entry['sha256']:
        raise ValueError('Source archive checksum mismatch: ' + entry['path'])

    root_name = archive_root(entry)
    target.mkdir(parents=True, exist_ok=False)
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            for member in archive:
                path, destination = checked_destination(target, member.name, root_name)
                if path is None:
                    continue
                # Never create filesystem links from an archive. The pinned
                # whisper.cpp archive contains only directories and files.
                if member.issym() or member.islnk():
                    raise ValueError('Archive links are not allowed')
                if member.isdir():
                    destination.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    source = archive.extractfile(member)
                    if source is None:
                        raise ValueError('Archive file is unreadable')
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(source.read())
                else:
                    raise ValueError('Unsupported archive entry: ' + member.name)
        marker.write_text(json.dumps(entry, indent=2) + '\n', encoding='utf-8')
        retain_license(target)
    except BaseException:
        shutil.rmtree(target, ignore_errors=True)
        raise
    return entry['path'] + ' prepared'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify', action='store_true',
                        help='Check prepared source markers without downloading')
    args = parser.parse_args()
    entries = json.loads(LOCK.read_text(encoding='utf-8'))
    if args.verify:
        for entry in entries:
            marker = DEST / entry['path'] / '.lmreader-source.json'
            if not marker.exists() or json.loads(marker.read_text()) != entry:
                raise SystemExit('Missing source: ' + entry['path'])
        print('All pinned ASR sources are prepared')
    else:
        for entry in entries:
            print(prepare(entry), flush=True)
