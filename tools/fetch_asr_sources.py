"""Prepare the pinned native ASR source trees.

Source archives, revisions, hashes and licenses are fixed in
asr_sources.lock.json. Run this explicitly before building; CMake does not
clone, update or download anything at configure time. Only the official
source archives are fetched here, never model weights.

The Vulkan backend uses pinned Khronos Vulkan-Headers and SPIRV-Headers so the
build does not depend on a system Vulkan SDK. Whisper.cpp stays at the exact
upstream revision that already works with the downloaded GGML models.
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
LICENSES = ROOT / 'tools/third_party_licenses'


def retain_license(target, entry):
    license_entry = entry.get('license')
    if license_entry is None:
        # Historical whisper.cpp notice location used by the release packaging.
        NOTICES.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(target / 'LICENSE', NOTICES / 'whisper.cpp-MIT.txt')
        return
    source = target / license_entry['file']
    name = license_entry['target']
    if not source.is_file():
        raise ValueError('Pinned license file is missing: ' + license_entry['file'])
    if not name or '/' in name or '\\' in name or name in ('.', '..') or '\x00' in name:
        raise ValueError('Unsafe license target name')
    LICENSES.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, LICENSES / name)
    NOTICES.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, NOTICES / name)


def archive_root(entry):
    explicit = entry.get('archive_root')
    if explicit:
        return explicit
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
            retain_license(target, entry)
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
        retain_license(target, entry)
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
            license_entry = entry.get('license')
            if license_entry is not None:
                source = DEST / entry['path'] / license_entry['file']
                if not source.is_file():
                    raise SystemExit('Missing license in source: ' + entry['path'])
        print('All pinned ASR sources are prepared')
    else:
        for entry in entries:
            print(prepare(entry), flush=True)
