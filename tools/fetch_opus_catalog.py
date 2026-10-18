"""Build the pinned OPUS-MT ONNX CPU catalog from Hugging Face metadata.

This tool intentionally downloads no ONNX weights.  Weight sizes and SHA-256
values come from Hugging Face LFS metadata.  Only the small Marian config,
vocabulary and SentencePiece files are downloaded so their SHA-256 values can
be computed and their formats checked.  Those files are cached under
``.scratch/opus-mt/catalog-cache`` and never under a tracked asset directory.

Typical use::

    python tools/fetch_opus_catalog.py
    python tools/fetch_opus_catalog.py --check
    python tools/fetch_opus_catalog.py --refresh

All network requests have a finite timeout, a bounded response body, and at
most two retries.  At most four requests run concurrently.  No remote content
is executed.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = ROOT / "core/translation/src/main/assets/translation/opus_catalog.json"
DEFAULT_SCRATCH = ROOT / ".scratch/opus-mt"
DEFAULT_CACHE = DEFAULT_SCRATCH / "catalog-cache"
DEFAULT_SUMMARY = DEFAULT_SCRATCH / "catalog-summary.json"
DEFAULT_LOG = DEFAULT_SCRATCH / "catalog.log"

HF_BASE = "https://huggingface.co"
MODEL_LIST_URL = HF_BASE + "/api/models?author=Xenova&search=opus-mt-&limit=1000"
USER_AGENT = "LM-Reader-opus-catalog/1"
MAX_WORKERS = 4
HTTP_TIMEOUT = 30
HTTP_ATTEMPTS = 3
HTTP_DEADLINE = 120
MAX_METADATA_BYTES = 8 * 1024 * 1024
MAX_SMALL_BYTES = 16 * 1024 * 1024
MAX_PACKAGE_BYTES = 350_000_000
ALLOWED_LICENSES = ("apache-2.0", "cc-by-4.0")
STRICT_REPO_RE = re.compile(r"^Xenova/opus-mt-([a-z]{2})-([a-z]{2})$")
SHA256_RE = re.compile(r"^[a-f0-9]{64}$")
REVISION_RE = re.compile(r"^[a-f0-9]{40}$")
SAFE_NAME_RE = re.compile(r"^[A-Za-z0-9._-]+$")
VERSION_RE = re.compile(r"^hf-[a-f0-9]{12}$")
CONTROL_TOKEN_RE = re.compile(r"^>>[^<>]+<<$")
RETRY_HTTP_CODES = {408, 425, 429, 500, 502, 503, 504}

# role, flat stored name, original repository path, whether it is a weight.
ROLE_SPECS = (
    ("encoder", "encoder.onnx", "onnx/encoder_model_quantized.onnx", True),
    ("decoder", "decoder.onnx", "onnx/decoder_model_merged_quantized.onnx", True),
    ("srcVocab", "source.spm", "source.spm", False),
    ("trgVocab", "target.spm", "target.spm", False),
    ("vocab", "vocab.json", "vocab.json", False),
    ("config", "config.json", "config.json", False),
)
ROLE_ORDER = [spec[0] for spec in ROLE_SPECS]
ROLE_BY_NAME = {spec[0]: spec for spec in ROLE_SPECS}
SMALL_ROLES = tuple(spec for spec in ROLE_SPECS if not spec[3])
WEIGHT_ROLES = tuple(spec for spec in ROLE_SPECS if spec[3])

REQUIRED_DIRECTIONS = (
    ("ja-en", "ja", "en"),
    ("en-zh", "en", "zh-Hans"),
    ("zh-en", "zh-Hans", "en"),
    ("fr-de", "fr", "de"),
    ("fr-es", "fr", "es"),
    ("it-es", "it", "es"),
    ("ru-uk", "ru", "uk"),
    ("en-es", "en", "es"),
)


class FetchError(Exception):
    """A bounded network or cache operation failed."""


class SkipPackage(Exception):
    """A repository is real but cannot be part of the catalog."""

    def __init__(self, reason, stage="validation", extra=None):
        super().__init__(reason)
        self.reason = reason
        self.stage = stage
        self.extra = extra or {}


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


def language_tag(code):
    # The OPUS-MT zh models use a generic Chinese tag.  The app uses Hans.
    return "zh-Hans" if code == "zh" else code


def package_id(source, target):
    return "opus-{}-{}".format(source, target)


def upstream_repository(repo):
    return "Helsinki-NLP/" + repo.split("/", 1)[1]


def upstream_url(repo):
    return HF_BASE + "/" + upstream_repository(repo)


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_relative_path(value):
    if not isinstance(value, str) or not value:
        return False
    if value.startswith("/") or "\\" in value:
        return False
    if any(ch in value for ch in "%?#:"):
        return False
    parts = value.split("/")
    return all(part not in ("", ".", "..") for part in parts)


def safe_cache_component(value):
    return value.replace("/", "__").replace("\\", "_")


def atomic_write_bytes(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".tmp", dir=str(path.parent)
    )
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            try:
                os.unlink(temporary)
            except OSError:
                pass


def atomic_write_json(path, value):
    text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    atomic_write_bytes(path, text.encode("utf-8"))


def load_json_file(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


class RunLog:
    def __init__(self):
        self.lines = []

    def add(self, message, echo=False):
        line = "[{}] {}".format(utc_now(), message)
        self.lines.append(line)
        if echo:
            print(message, flush=True)

    def write(self, path):
        try:
            atomic_write_bytes(Path(path), ("\n".join(self.lines) + "\n").encode("utf-8"))
        except OSError as exc:
            print("warning: could not write log: {}".format(exc), file=sys.stderr)


def read_limited(response, limit, label):
    declared = response.headers.get("Content-Length")
    if declared is not None:
        try:
            if int(declared) > limit:
                raise FetchError(
                    "{}: response declares {} bytes (limit {})".format(label, declared, limit)
                )
        except ValueError:
            pass
    deadline = time.monotonic() + HTTP_DEADLINE
    chunks = []
    total = 0
    while True:
        if time.monotonic() > deadline:
            raise FetchError("{}: response exceeded the time budget".format(label))
        chunk = response.read(64 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > limit:
            raise FetchError("{}: response exceeded {} bytes".format(label, limit))
        chunks.append(chunk)
    return b"".join(chunks)


def retry_delay(attempt, response_headers=None):
    delay = min(1.0 * (2 ** attempt), 4.0)
    if response_headers is not None:
        retry_after = response_headers.get("Retry-After")
        if retry_after:
            try:
                delay = min(max(float(retry_after), 0.0), 10.0)
            except ValueError:
                pass
    return delay


def fetch_bytes(url, limit, label):
    last_error = None
    for attempt in range(HTTP_ATTEMPTS):
        request = urllib.request.Request(
            url,
            headers={"User-Agent": USER_AGENT, "Accept-Encoding": "identity"},
        )
        try:
            with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
                data = read_limited(response, limit, label)
                return data, response.headers
        except urllib.error.HTTPError as exc:
            last_error = "HTTP {}".format(exc.code)
            if exc.code not in RETRY_HTTP_CODES or attempt + 1 >= HTTP_ATTEMPTS:
                raise FetchError("{}: {}".format(label, last_error)) from exc
            time.sleep(retry_delay(attempt, exc.headers))
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = "{}: {}".format(type(exc).__name__, exc)
            if attempt + 1 >= HTTP_ATTEMPTS:
                raise FetchError("{}: {}".format(label, last_error)) from exc
            time.sleep(retry_delay(attempt))
    raise FetchError("{}: {}".format(label, last_error or "request failed"))


def fetch_json(url, limit, label):
    data, _ = fetch_bytes(url, limit, label)
    try:
        return json.loads(data.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise FetchError("{}: invalid JSON: {}".format(label, exc)) from exc


def normalize_license_values(raw):
    values = set()
    card = raw.get("cardData")
    if isinstance(card, dict):
        license_value = card.get("license")
        if isinstance(license_value, str):
            values.add(license_value.strip().lower())
        elif isinstance(license_value, list):
            for item in license_value:
                if isinstance(item, str):
                    values.add(item.strip().lower())
    tags = raw.get("tags")
    if isinstance(tags, list):
        for tag in tags:
            if isinstance(tag, str) and tag.lower().startswith("license:"):
                values.add(tag.split(":", 1)[1].strip().lower())
    return sorted(value for value in values if value)


def selected_license(values):
    for allowed in ALLOWED_LICENSES:
        if allowed in values:
            return allowed
    return None


def repositories_cache_path(cache_dir):
    return Path(cache_dir) / "repositories.json"


def metadata_cache_path(cache_dir, repo):
    return Path(cache_dir) / "metadata" / (safe_cache_component(repo) + ".json")


def license_cache_path(cache_dir, repo):
    return Path(cache_dir) / "licenses" / (safe_cache_component(repo) + ".json")


def small_file_cache_path(cache_dir, repo, revision, name):
    return Path(cache_dir) / "files" / safe_cache_component(repo) / revision / name


def small_meta_path(path):
    return Path(str(path) + ".meta.json")


def valid_revision(value):
    return isinstance(value, str) and bool(REVISION_RE.fullmatch(value))


def read_repo_list(cache_dir, refresh, log):
    path = repositories_cache_path(cache_dir)
    cached = None
    if path.exists():
        try:
            candidate = load_json_file(path)
            items = candidate.get("repositories")
            if (
                candidate.get("schema") == 1
                and isinstance(items, list)
                and items
                and all(isinstance(item, str) and "/" in item for item in items)
            ):
                cached = items
        except (OSError, ValueError, AttributeError):
            cached = None
    if cached is not None and not refresh:
        log.add("repositories: reused cached list ({} entries)".format(len(cached)))
        return cached, {"fetched": False, "stale": False}

    try:
        raw = fetch_json(MODEL_LIST_URL, MAX_METADATA_BYTES, "model list")
    except FetchError:
        if cached is not None:
            log.add("repositories: fetch failed, using stale cache")
            return cached, {"fetched": False, "stale": True}
        raise
    if not isinstance(raw, list):
        raise FetchError("model list: expected a JSON array")
    repositories = []
    for item in raw:
        if isinstance(item, dict) and isinstance(item.get("id"), str):
            repositories.append(item["id"])
    repositories = sorted(set(repositories))
    if not repositories:
        raise FetchError("model list: no repositories returned")
    atomic_write_json(
        path,
        {"schema": 1, "query": MODEL_LIST_URL, "repositories": repositories},
    )
    log.add("repositories: fetched list ({} entries)".format(len(repositories)))
    return repositories, {"fetched": True, "stale": False}


def get_model_detail(cache_dir, repo, refresh, log):
    path = metadata_cache_path(cache_dir, repo)
    cached = None
    if path.exists():
        try:
            candidate = load_json_file(path)
            if (
                candidate.get("schema") == 1
                and candidate.get("repository") == repo
                and valid_revision(candidate.get("revision"))
                and isinstance(candidate.get("files"), dict)
            ):
                cached = candidate
        except (OSError, ValueError, AttributeError):
            cached = None
    if cached is not None and not refresh:
        return cached, {"fetched": False, "stale": False}

    url = "{}/api/models/{}?blobs=true".format(HF_BASE, repo)
    try:
        raw = fetch_json(url, MAX_METADATA_BYTES, "metadata " + repo)
    except FetchError:
        if cached is not None:
            log.add("metadata {}: fetch failed, using stale cache".format(repo))
            return cached, {"fetched": False, "stale": True}
        raise
    if not isinstance(raw, dict):
        raise FetchError("metadata {}: expected an object".format(repo))
    revision = raw.get("sha")
    if not valid_revision(revision):
        raise FetchError("metadata {}: missing 40-character revision".format(repo))
    siblings = raw.get("siblings")
    if not isinstance(siblings, list):
        raise FetchError("metadata {}: missing siblings".format(repo))

    files = {}
    for sibling in siblings:
        if not isinstance(sibling, dict):
            continue
        original_path = sibling.get("rfilename")
        if not safe_relative_path(original_path):
            continue
        size = sibling.get("size")
        if not is_int(size):
            size = None
        lfs = sibling.get("lfs")
        lfs_sha = None
        lfs_size = None
        if isinstance(lfs, dict):
            candidate_sha = lfs.get("sha256")
            if isinstance(candidate_sha, str) and SHA256_RE.fullmatch(candidate_sha):
                lfs_sha = candidate_sha
            candidate_size = lfs.get("size")
            if is_int(candidate_size) and candidate_size > 0:
                lfs_size = candidate_size
        files[original_path] = {
            "size": size,
            "lfsSha256": lfs_sha,
            "lfsSize": lfs_size,
        }

    card = raw.get("cardData")
    base_model = card.get("base_model") if isinstance(card, dict) else None
    entry = {
        "schema": 1,
        "repository": repo,
        "revision": revision,
        "license": normalize_license_values(raw),
        "baseModel": base_model if isinstance(base_model, str) else None,
        "files": files,
    }
    atomic_write_json(path, entry)
    return entry, {"fetched": True, "stale": False}


def get_upstream_license(cache_dir, repo, refresh, log):
    upstream = upstream_repository(repo)
    path = license_cache_path(cache_dir, upstream)
    cached = None
    if path.exists():
        try:
            candidate = load_json_file(path)
            if (
                candidate.get("schema") == 1
                and candidate.get("repository") == upstream
                and isinstance(candidate.get("license"), list)
            ):
                cached = candidate
        except (OSError, ValueError, AttributeError):
            cached = None
    if cached is not None and not refresh:
        return cached, {"fetched": False, "stale": False}

    url = "{}/api/models/{}".format(HF_BASE, upstream)
    try:
        raw = fetch_json(url, MAX_METADATA_BYTES, "license " + upstream)
    except FetchError:
        if cached is not None:
            log.add("license {}: fetch failed, using stale cache".format(upstream))
            return cached, {"fetched": False, "stale": True}
        raise
    if not isinstance(raw, dict):
        raise FetchError("license {}: expected an object".format(upstream))
    entry = {
        "schema": 1,
        "repository": upstream,
        "license": normalize_license_values(raw),
    }
    atomic_write_json(path, entry)
    return entry, {"fetched": True, "stale": False}


def resolve_license(cache_dir, repo, detail, refresh, log):
    mirror_values = detail.get("license")
    if not isinstance(mirror_values, list):
        mirror_values = []
    mirror_values = sorted(set(str(value).lower() for value in mirror_values if value))
    if mirror_values:
        chosen = selected_license(mirror_values)
        if chosen is None:
            raise SkipPackage(
                "mirror license is not apache-2.0 or cc-by-4.0: {}".format(
                    ", ".join(mirror_values)
                ),
                stage="license",
            )
        return {
            "license": chosen,
            "licenseSource": repo,
            "sourceKind": "mirror",
        }, {"fetched": False, "stale": False}

    base_model = detail.get("baseModel")
    expected_upstream = upstream_repository(repo)
    if base_model is not None and base_model != expected_upstream:
        raise SkipPackage(
            "base_model is {}, expected {}".format(base_model, expected_upstream),
            stage="license",
        )
    entry, stats = get_upstream_license(cache_dir, repo, refresh, log)
    values = entry.get("license")
    if not isinstance(values, list) or not values:
        raise SkipPackage("license missing on mirror and upstream", stage="license")
    chosen = selected_license(values)
    if chosen is None:
        raise SkipPackage(
            "upstream license is not apache-2.0 or cc-by-4.0: {}".format(
                ", ".join(str(value) for value in values)
            ),
            stage="license",
        )
    return {
        "license": chosen,
        "licenseSource": expected_upstream,
        "sourceKind": "upstream",
    }, stats


def inspect_required_files(detail):
    files = detail.get("files")
    if not isinstance(files, dict):
        raise SkipPackage("metadata has no file table", stage="metadata")
    required = {}
    missing = []
    for role, name, original_path, is_weight in ROLE_SPECS:
        sibling = files.get(original_path)
        if not isinstance(sibling, dict):
            missing.append(original_path)
            continue
        if is_weight:
            size = sibling.get("lfsSize")
            sha256 = sibling.get("lfsSha256")
            if not is_int(size) or size <= 0:
                missing.append(original_path + " (no positive LFS size)")
                continue
            if not isinstance(sha256, str) or not SHA256_RE.fullmatch(sha256):
                missing.append(original_path + " (no verifiable LFS SHA-256)")
                continue
        else:
            size = sibling.get("size")
            sha256 = sibling.get("lfsSha256")
            if not is_int(size) or size <= 0:
                missing.append(original_path + " (no positive size)")
                continue
            if size > MAX_SMALL_BYTES:
                missing.append(
                    original_path + " ({} bytes exceeds small-file limit)".format(size)
                )
                continue
            if sha256 is not None and (
                not isinstance(sha256, str) or not SHA256_RE.fullmatch(sha256)
            ):
                missing.append(original_path + " (invalid LFS SHA-256)")
                continue
        required[role] = {
            "path": original_path,
            "name": name,
            "size": size,
            "lfsSha256": sha256,
            "isWeight": is_weight,
        }
    if missing:
        raise SkipPackage(
            "missing or unverifiable required file(s): " + "; ".join(missing),
            stage="files",
            extra={"missing": missing},
        )
    total = sum(item["size"] for item in required.values())
    weight = sum(item["size"] for role, item in required.items() if item["isWeight"])
    if total > MAX_PACKAGE_BYTES:
        raise SkipPackage(
            "package is {} bytes, over the {} byte limit".format(total, MAX_PACKAGE_BYTES),
            stage="size",
            extra={"totalBytes": total},
        )
    return required, total, weight


def small_cache_entry(cache_dir, repo, revision, role_spec):
    role, name, original_path = role_spec[0], role_spec[1], role_spec[2]
    path = small_file_cache_path(cache_dir, repo, revision, name)
    meta_path = small_meta_path(path)
    if not path.exists() or not meta_path.exists():
        return None
    try:
        meta = load_json_file(meta_path)
    except (OSError, ValueError):
        return None
    if (
        meta.get("repository") != repo
        or meta.get("revision") != revision
        or meta.get("path") != original_path
        or meta.get("role") != role
        or not is_int(meta.get("size"))
        or meta.get("size") != path.stat().st_size
        or not isinstance(meta.get("sha256"), str)
        or not SHA256_RE.fullmatch(meta.get("sha256"))
    ):
        return None
    actual = sha256_file(path)
    if actual != meta["sha256"]:
        return None
    return {
        "path": path,
        "size": meta["size"],
        "sha256": actual,
        "downloaded": False,
        "reused": True,
    }


def ensure_small_file(cache_dir, repo, revision, role_spec, refresh, log):
    role, name, original_path, _, expected_size, expected_lfs = role_spec
    cached = small_cache_entry(cache_dir, repo, revision, role_spec)
    if cached is not None:
        cache_matches = cached["size"] == expected_size
        if expected_lfs is not None:
            cache_matches = cache_matches and cached["sha256"] == expected_lfs
        if cache_matches:
            return cached

    url = "{}/{}/resolve/{}/{}".format(
        HF_BASE, repo, revision, urllib.parse.quote(original_path, safe="/")
    )
    label = "{} {}".format(repo, original_path)
    data, _ = fetch_bytes(url, MAX_SMALL_BYTES, label)
    digest = sha256_bytes(data)
    size = len(data)
    if size != expected_size:
        raise FetchError(
            label + ": size mismatch (got {}, expected {})".format(size, expected_size)
        )
    if expected_lfs is not None and digest != expected_lfs:
        raise FetchError(
            label + ": SHA-256 mismatch (got {}, expected {})".format(digest, expected_lfs)
        )

    path = small_file_cache_path(cache_dir, repo, revision, name)
    atomic_write_bytes(path, data)
    atomic_write_json(
        small_meta_path(path),
        {
            "repository": repo,
            "revision": revision,
            "path": original_path,
            "role": role,
            "size": size,
            "sha256": digest,
        },
    )
    log.add(
        "download {} {} {} bytes sha256={}".format(repo, original_path, size, digest)
    )
    return {
        "path": path,
        "size": size,
        "sha256": digest,
        "downloaded": True,
        "reused": False,
    }


def load_json_object(data, label):
    try:
        value = json.loads(data.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SkipPackage("{} is not valid UTF-8 JSON: {}".format(label, exc), stage="format")
    if not isinstance(value, dict):
        raise SkipPackage("{} is not a JSON object".format(label), stage="format")
    return value


def validate_marian_config(config):
    if str(config.get("model_type", "")).lower() != "marian":
        return ["config.json model_type is not marian"]
    errors = []
    for key in ("encoder_layers", "decoder_layers", "d_model"):
        value = config.get(key)
        if not is_int(value) or value <= 0:
            errors.append("config.json {} is not a positive integer".format(key))
    for key in ("pad_token_id", "eos_token_id", "decoder_start_token_id"):
        value = config.get(key)
        if not is_int(value) or value < 0:
            errors.append("config.json {} is missing or invalid".format(key))
    if "architectures" in config:
        architectures = config["architectures"]
        if not isinstance(architectures, list) or not all(
            isinstance(item, str) for item in architectures
        ):
            errors.append("config.json architectures is not a string array")
        elif "MarianMTModel" not in architectures:
            errors.append("config.json architectures does not contain MarianMTModel")
    return errors


def validate_vocab(vocab):
    if len(vocab) < 100:
        return ["vocab.json has fewer than 100 entries"], set()
    errors = []
    ids = set()
    for token, token_id in vocab.items():
        if not isinstance(token, str) or not token:
            errors.append("vocab.json has an empty or non-string token")
            break
        if not is_int(token_id) or token_id < 0:
            errors.append("vocab.json token {!r} has an invalid id".format(token))
            break
        ids.add(token_id)
    if len(ids) != len(vocab):
        errors.append("vocab.json token ids are not unique")
    return errors, ids


def protobuf_varint(data, offset):
    value = 0
    shift = 0
    while True:
        if offset >= len(data) or shift > 63:
            return None, offset
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return value, offset
        shift += 7


def validate_sentencepiece(data):
    if len(data) < 1024:
        return "SentencePiece file is too small"
    if data[:1] != b"\x0a":
        return "SentencePiece file does not start with a ModelProto field"
    if b"<unk>" not in data[:4096]:
        return "SentencePiece file has no <unk> trainer entry"
    if b"</s>" not in data:
        return "SentencePiece file has no </s> piece"
    offset = 0
    while offset < len(data):
        key, offset = protobuf_varint(data, offset)
        if key is None or key < 8:
            return "SentencePiece protobuf has an invalid field key"
        wire_type = key & 0x07
        field_number = key >> 3
        if field_number == 0:
            return "SentencePiece protobuf has field number zero"
        if wire_type == 0:
            value, offset = protobuf_varint(data, offset)
            if value is None:
                return "SentencePiece protobuf has a truncated varint"
        elif wire_type == 1:
            offset += 8
        elif wire_type == 2:
            length, offset = protobuf_varint(data, offset)
            if length is None or length < 0 or offset + length > len(data):
                return "SentencePiece protobuf has a truncated field"
            offset += length
        elif wire_type == 5:
            offset += 4
        else:
            return "SentencePiece protobuf uses an unsupported wire type"
        if offset > len(data):
            return "SentencePiece protobuf is truncated"
    if offset != len(data):
        return "SentencePiece protobuf did not consume the whole file"
    return None


def control_tokens(vocab):
    return sorted(token for token in vocab if CONTROL_TOKEN_RE.fullmatch(token))


def file_entry(repo, revision, role, name, original_path, size, sha256, git_blob_sha1=None):
    return {
        "role": role,
        "name": name,
        "size": size,
        "sha256": sha256,
        "officialPath": original_path,
        "mirrorPath": original_path,
        "downloadPath": original_path,
        "url": "{}/{}/resolve/{}/{}".format(HF_BASE, repo, revision, original_path),
        "compression": "NONE",
        "downloadSize": size,
        "downloadSha256": sha256,
        **({"gitBlobSha1": git_blob_sha1} if git_blob_sha1 else {}),
    }


def add_skip(skips, repo, source, target, exc):
    item = {
        "repository": repo,
        "package": package_id(source, target) if source and target else None,
        "stage": exc.stage,
        "reason": exc.reason,
    }
    item.update(exc.extra)
    skips.append(item)


def build_catalog(args, log):
    cache_dir = Path(args.cache_dir)
    catalog_path = Path(args.catalog)
    summary_path = Path(args.summary)

    repository_list, list_stats = read_repo_list(cache_dir, args.refresh, log)
    strict = []
    ignored = []
    for repo in repository_list:
        match = STRICT_REPO_RE.fullmatch(repo)
        if not match:
            ignored.append(
                {
                    "repository": repo,
                    "reason": "not a strict opus-mt-<2-letter>-<2-letter> bilingual model",
                }
            )
            continue
        source = language_tag(match.group(1))
        target = language_tag(match.group(2))
        strict.append((repo, source, target))
    log.add(
        "repositories: {} listed, {} strict bilingual, {} ignored".format(
            len(repository_list), len(strict), len(ignored)
        ),
        echo=True,
    )

    details = {}
    detail_stats = {"fetched": 0, "reused": 0, "stale": 0}
    errors = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(
                get_model_detail, cache_dir, repo, args.refresh, log
            ): repo
            for repo, _, _ in strict
        }
        for future in concurrent.futures.as_completed(futures):
            repo = futures[future]
            try:
                entry, stats = future.result()
                details[repo] = entry
                if stats["fetched"]:
                    detail_stats["fetched"] += 1
                else:
                    detail_stats["reused"] += 1
                if stats["stale"]:
                    detail_stats["stale"] += 1
            except Exception as exc:
                errors[repo] = exc
                log.add("metadata {}: {}".format(repo, exc))
    log.add(
        "metadata: {} fetched, {} reused, {} stale".format(
            detail_stats["fetched"], detail_stats["reused"], detail_stats["stale"]
        ),
        echo=True,
    )

    skips = []
    preliminary = []
    for repo, source, target in strict:
        if source == target:
            add_skip(
                skips,
                repo,
                source,
                target,
                SkipPackage("source and target languages are identical", stage="languages"),
            )
            continue
        if repo in errors:
            exc = errors[repo]
            add_skip(
                skips,
                repo,
                source,
                target,
                SkipPackage("metadata fetch failed: {}".format(exc), stage="metadata"),
            )
            continue
        detail = details[repo]
        try:
            required, total, weight = inspect_required_files(detail)
        except SkipPackage as exc:
            add_skip(skips, repo, source, target, exc)
            continue
        preliminary.append(
            {
                "repository": repo,
                "source": source,
                "target": target,
                "revision": detail["revision"],
                "detail": detail,
                "required": required,
                "totalBytes": total,
                "weightBytes": weight,
            }
        )

    license_stats = {"fetched": 0, "reused": 0, "stale": 0}
    licensed = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(
                resolve_license,
                cache_dir,
                item["repository"],
                item["detail"],
                args.refresh,
                log,
            ): item
            for item in preliminary
        }
        for future in concurrent.futures.as_completed(futures):
            item = futures[future]
            repo = item["repository"]
            try:
                info, stats = future.result()
                item["license"] = info
                if stats["fetched"]:
                    license_stats["fetched"] += 1
                else:
                    license_stats["reused"] += 1
                if stats["stale"]:
                    license_stats["stale"] += 1
                licensed.append(item)
            except SkipPackage as exc:
                add_skip(skips, repo, item["source"], item["target"], exc)
            except Exception as exc:
                add_skip(
                    skips,
                    repo,
                    item["source"],
                    item["target"],
                    SkipPackage(
                        "license lookup failed: {}".format(exc), stage="license"
                    ),
                )
    log.add(
        "licenses: {} fetched, {} reused, {} stale, {} eligible".format(
            license_stats["fetched"],
            license_stats["reused"],
            license_stats["stale"],
            len(licensed),
        ),
        echo=True,
    )

    file_results = {}
    file_errors = {}
    file_stats = {"downloaded": 0, "reused": 0}
    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {}
        for item in licensed:
            repo = item["repository"]
            revision = item["revision"]
            for role, name, original_path, is_weight in SMALL_ROLES:
                spec = (
                    role,
                    name,
                    original_path,
                    is_weight,
                    item["required"][role]["size"],
                    item["required"][role]["lfsSha256"],
                )
                future = executor.submit(
                    ensure_small_file,
                    cache_dir,
                    repo,
                    revision,
                    spec,
                    args.refresh,
                    log,
                )
                futures[future] = (repo, role)
        for future in concurrent.futures.as_completed(futures):
            repo, role = futures[future]
            try:
                result = future.result()
                file_results[(repo, role)] = result
                if result["downloaded"]:
                    file_stats["downloaded"] += 1
                else:
                    file_stats["reused"] += 1
            except Exception as exc:
                file_errors.setdefault(repo, []).append(
                    "{}: {}".format(role, exc)
                )
                log.add("file {} {}: {}".format(repo, role, exc))
    log.add(
        "small files: {} downloaded, {} reused, {} errors".format(
            file_stats["downloaded"],
            file_stats["reused"],
            sum(len(values) for values in file_errors.values()),
        ),
        echo=True,
    )

    packages = []
    provenances = {}
    license_source_counts = {"mirror": 0, "upstream": 0}
    token_observations = []
    token_exclusions = []
    for item in licensed:
        repo = item["repository"]
        source = item["source"]
        target = item["target"]
        revision = item["revision"]
        required = item["required"]
        if repo in file_errors:
            add_skip(
                skips,
                repo,
                source,
                target,
                SkipPackage(
                    "small file fetch failed: " + "; ".join(file_errors[repo]),
                    stage="files",
                ),
            )
            continue

        role_data = {}
        try:
            for role, name, original_path, is_weight in SMALL_ROLES:
                result = file_results[(repo, role)]
                data = result["path"].read_bytes()
                if len(data) != result["size"]:
                    raise SkipPackage(
                        "{} changed while being read".format(original_path), stage="format"
                    )
                role_data[role] = data
            config = load_json_object(role_data["config"], repo + "/config.json")
            config_errors = validate_marian_config(config)
            if config_errors:
                raise SkipPackage("; ".join(config_errors), stage="format")
            vocab = load_json_object(role_data["vocab"], repo + "/vocab.json")
            vocab_errors, vocab_ids = validate_vocab(vocab)
            if vocab_errors:
                raise SkipPackage("; ".join(vocab_errors), stage="format")
            for key in ("pad_token_id", "eos_token_id", "decoder_start_token_id"):
                if config[key] not in vocab_ids:
                    config_errors.append(
                        "config.json {}={} is absent from vocab.json".format(
                            key, config[key]
                        )
                    )
            if config_errors:
                raise SkipPackage("; ".join(config_errors), stage="format")
            for label, role in (("source.spm", "srcVocab"), ("target.spm", "trgVocab")):
                problem = validate_sentencepiece(role_data[role])
                if problem:
                    raise SkipPackage("{}: {}".format(label, problem), stage="format")
        except SkipPackage as exc:
            add_skip(skips, repo, source, target, exc)
            continue

        tokens = control_tokens(vocab)
        target_prefix = ""
        if tokens:
            observation = {
                "package": package_id(source, target),
                "repository": repo,
                "tokens": tokens,
            }
            if source == "en" and target == "zh-Hans":
                if ">>cmn_Hans<<" not in tokens:
                    observation["decision"] = (
                        "excluded: required >>cmn_Hans<< is absent"
                    )
                    token_exclusions.append(observation)
                    add_skip(
                        skips,
                        repo,
                        source,
                        target,
                        SkipPackage(
                            "en->zh-Hans requires >>cmn_Hans<<, found: {}".format(
                                ", ".join(tokens)
                            ),
                            stage="target-prefix",
                            extra={"tokens": tokens},
                        ),
                    )
                    continue
                target_prefix = ">>cmn_Hans<<"
                observation["decision"] = (
                    "included with explicit targetPrefix >>cmn_Hans<<"
                )
            else:
                observation["decision"] = (
                    "excluded: language control tokens present and no prefix inferred"
                )
                token_exclusions.append(observation)
                add_skip(
                    skips,
                    repo,
                    source,
                    target,
                    SkipPackage(
                        "language control tokens present; no prefix was inferred: {}".format(
                            ", ".join(tokens)
                        ),
                        stage="target-prefix",
                        extra={"tokens": tokens},
                    ),
                )
                continue
            token_observations.append(observation)

        files = []
        for role, name, original_path, is_weight in ROLE_SPECS:
            source_file = required[role]
            if is_weight:
                size = source_file["size"]
                sha256 = source_file["lfsSha256"]
            else:
                result = file_results[(repo, role)]
                size = result["size"]
                sha256 = result["sha256"]
                if size != source_file["size"]:
                    add_skip(
                        skips,
                        repo,
                        source,
                        target,
                        SkipPackage(
                            "{} size does not match metadata".format(original_path),
                            stage="files",
                        ),
                    )
                    files = []
                    break
                if source_file["lfsSha256"] is not None and sha256 != source_file["lfsSha256"]:
                    add_skip(
                        skips,
                        repo,
                        source,
                        target,
                        SkipPackage(
                            "{} SHA-256 does not match LFS metadata".format(original_path),
                            stage="files",
                        ),
                    )
                    files = []
                    break
            files.append(
                file_entry(
                    repo,
                    revision,
                    role,
                    name,
                    original_path,
                    size,
                    sha256,
                    hashlib.sha1(
                        ("blob {}\0".format(size)).encode("ascii") + file_results[(repo, role)]["path"].read_bytes()
                    ).hexdigest() if not is_weight and source_file["lfsSha256"] is None else None,
                )
            )
        if not files:
            continue

        pack = {
            "id": package_id(source, target),
            "version": "hf-" + revision[:12],
            "engine": "OPUS_MT",
            "source": source,
            "target": target,
            "targetPrefix": target_prefix,
            "license": item["license"]["license"],
            "repository": repo,
            "files": files,
        }
        packages.append(pack)
        provenance = {
            "repository": repo,
            "revision": revision,
            "license": item["license"]["license"],
            "licenseSource": item["license"]["licenseSource"],
            "upstreamUrl": upstream_url(repo),
            "weightBytes": item["weightBytes"],
            "totalBytes": item["totalBytes"],
        }
        provenances[pack["id"]] = provenance
        license_source_counts[item["license"]["sourceKind"]] += 1
        log.add(
            "include {} {}@{} total={} weight={} prefix={!r}".format(
                pack["id"],
                repo,
                revision[:12],
                item["totalBytes"],
                item["weightBytes"],
                target_prefix,
            )
        )
        # Release the small byte strings before the next package.
        role_data.clear()

    packages.sort(key=lambda pack: pack["id"])
    provenances = {key: provenances[key] for key in sorted(provenances)}
    skips.sort(key=lambda item: (item["repository"], item["stage"] or "", item["reason"]))
    ignored.sort(key=lambda item: item["repository"])

    catalog = {
        "schema": 3,
        "engine": "local-nmt-v1",
        "packages": packages,
        "provenance": provenances,
    }
    atomic_write_json(catalog_path, catalog)
    catalog_bytes = catalog_path.stat().st_size
    catalog_sha256 = sha256_file(catalog_path)

    package_sizes = [pack_total(pack) for pack in packages]
    weight_sizes = [
        sum(file["size"] for file in pack["files"] if file["role"] in ("encoder", "decoder"))
        for pack in packages
    ]
    direct_non_english = [
        {
            "package": pack["id"],
            "source": pack["source"],
            "target": pack["target"],
            "totalBytes": pack_total(pack),
        }
        for pack in packages
        if pack["source"] != "en" and pack["target"] != "en"
    ]
    route_map = {(pack["source"], pack["target"]): pack for pack in packages}
    skip_route_map = {}
    for skip in skips:
        if skip.get("package"):
            skip_route_map[skip["package"]] = skip
    required_directions = {}
    for label, source, target in REQUIRED_DIRECTIONS:
        pack = route_map.get((source, target))
        if pack is not None:
            required_directions[label] = {
                "status": "included",
                "package": pack["id"],
                "source": pack["source"],
                "target": pack["target"],
                "targetPrefix": pack["targetPrefix"],
            }
        else:
            candidate_id = package_id(source, target)
            skip = skip_route_map.get(candidate_id)
            required_directions[label] = {
                "status": "excluded" if skip else "missing",
                "package": candidate_id,
                "source": source,
                "target": target,
                "reason": skip["reason"] if skip else "not present in the Xenova repository list",
            }

    summary = {
        "schema": 1,
        "generatedAt": utc_now(),
        "generator": "tools/fetch_opus_catalog.py",
        "mode": "refresh" if args.refresh else "cache-first",
        "catalog": {
            "path": str(catalog_path.relative_to(ROOT)).replace("\\", "/")
            if is_relative_to(catalog_path, ROOT)
            else str(catalog_path),
            "bytes": catalog_bytes,
            "sha256": catalog_sha256,
        },
        "source": {
            "api": MODEL_LIST_URL,
            "author": "Xenova",
            "upstream": "Helsinki-NLP (same model name)",
            "weightsDownloaded": False,
        },
        "repositories": {
            "listed": len(repository_list),
            "strictBilingual": len(strict),
            "included": len(packages),
            "skipped": len(skips),
            "ignored": len(ignored),
        },
        "included": len(packages),
        "packageBytes": {
            "min": min(package_sizes) if package_sizes else None,
            "max": max(package_sizes) if package_sizes else None,
            "total": sum(package_sizes),
        },
        "weightBytes": {
            "min": min(weight_sizes) if weight_sizes else None,
            "max": max(weight_sizes) if weight_sizes else None,
            "total": sum(weight_sizes),
        },
        "directNonEnglishCount": len(direct_non_english),
        "directNonEnglish": direct_non_english,
        "requiredDirections": required_directions,
        "targetPrefixes": {
            pack["id"]: pack["targetPrefix"]
            for pack in packages
            if pack["targetPrefix"]
        },
        "controlTokens": {
            "selectedTargetPrefix": {
                observation["package"]: ">>cmn_Hans<<"
                for observation in token_observations
                if observation.get("decision")
            },
            "packagesWithTokens": token_observations,
            "excludedForTokens": token_exclusions,
        },
        "licenses": {
            "policy": (
                "Allowed: apache-2.0 or cc-by-4.0. Xenova mirror cards omit "
                "license metadata, so the effective license is inherited from "
                "the exact Helsinki-NLP base model recorded in provenance."
            ),
            "mirror": license_source_counts["mirror"],
            "upstreamFallback": license_source_counts["upstream"],
        },
        "skipped": skips,
        "ignoredRepositories": ignored,
        "cache": {
            "path": str(cache_dir.relative_to(ROOT)).replace("\\", "/")
            if is_relative_to(cache_dir, ROOT)
            else str(cache_dir),
            "repositories": list_stats,
            "metadata": detail_stats,
            "licenses": license_stats,
            "smallFiles": file_stats,
        },
        "repeatCommands": {
            "generate": "python tools/fetch_opus_catalog.py",
            "refreshMetadata": "python tools/fetch_opus_catalog.py --refresh",
            "check": "python tools/fetch_opus_catalog.py --check",
            "syntax": (
                "python -c \"import ast,pathlib; "
                "ast.parse(pathlib.Path('tools/fetch_opus_catalog.py')"
                ".read_text(encoding='utf-8'))\""
            ),
        },
        "notes": [
            "No ONNX weights were downloaded; their sizes and SHA-256 values come from HF LFS metadata.",
            "Only config.json, vocab.json, source.spm and target.spm were downloaded for hashing and format checks.",
            "Package size is the sum of all six uncompressed files and must not exceed 350,000,000 bytes.",
            "zh is normalized to zh-Hans. en->zh-Hans uses targetPrefix >>cmn_Hans<< only after verifying vocab.json.",
            "Per-pack license/repository duplicate the provenance values because the existing Android catalog parser reads them there.",
        ],
    }
    atomic_write_json(summary_path, summary)
    log.add(
        "catalog: {} packages, {} skipped, {} ignored; bytes {}..{}; sha256={}".format(
            len(packages),
            len(skips),
            len(ignored),
            min(package_sizes) if package_sizes else 0,
            max(package_sizes) if package_sizes else 0,
            catalog_sha256,
        ),
        echo=True,
    )
    log.add("wrote {}".format(catalog_path), echo=True)
    log.add("wrote {}".format(summary_path), echo=True)
    return 0


def is_relative_to(path, parent):
    try:
        Path(path).resolve().relative_to(Path(parent).resolve())
        return True
    except ValueError:
        return False


def pack_total(pack):
    return sum(file["size"] for file in pack["files"])


def validate_catalog(catalog_path, cache_dir):
    errors = []
    warnings = []

    path = Path(catalog_path)
    if not path.exists():
        return ["catalog does not exist: {}".format(path)], []
    try:
        catalog = load_json_file(path)
    except (OSError, ValueError) as exc:
        return ["catalog is not valid UTF-8 JSON: {}".format(exc)], []
    if not isinstance(catalog, dict):
        return ["catalog root is not an object"], []
    expected_root_keys = {"schema", "engine", "packages", "provenance"}
    if set(catalog.keys()) != expected_root_keys:
        errors.append(
            "catalog root keys are {}, expected {}".format(
                sorted(catalog.keys()), sorted(expected_root_keys)
            )
        )
    if catalog.get("schema") != 3:
        errors.append("catalog schema is not 3")
    if catalog.get("engine") != "local-nmt-v1":
        errors.append("catalog engine is not local-nmt-v1")
    packages = catalog.get("packages")
    provenance = catalog.get("provenance")
    if not isinstance(packages, list) or not packages:
        return errors + ["catalog packages is not a non-empty array"], warnings
    if not isinstance(provenance, dict):
        return errors + ["catalog provenance is not an object"], warnings
    if len(packages) > 512:
        errors.append("catalog has more than 512 packages")

    ids = set()
    routes = set()
    verified_cache = 0
    missing_cache = 0
    for index, pack in enumerate(packages):
        label = "packages[{}]".format(index)
        if not isinstance(pack, dict):
            errors.append(label + " is not an object")
            continue
        required_pack_keys = {
            "id",
            "version",
            "engine",
            "source",
            "target",
            "targetPrefix",
            "license",
            "repository",
            "files",
        }
        missing_keys = required_pack_keys - set(pack.keys())
        if missing_keys:
            errors.append(label + " is missing keys: " + ", ".join(sorted(missing_keys)))
            continue
        pack_id = pack["id"]
        if not isinstance(pack_id, str) or not SAFE_NAME_RE.fullmatch(pack_id):
            errors.append(label + " has an unsafe id")
            continue
        if pack_id in ids:
            errors.append("duplicate package id: " + pack_id)
        ids.add(pack_id)
        label = pack_id
        if pack.get("engine") != "OPUS_MT":
            errors.append(label + " engine is not OPUS_MT")
        source = pack.get("source")
        target = pack.get("target")
        language_re = re.compile(r"^[a-z]{2,3}(?:-[A-Za-z0-9]{1,8})*$")
        if not isinstance(source, str) or not language_re.fullmatch(source):
            errors.append(label + " has an invalid source language")
            continue
        if not isinstance(target, str) or not language_re.fullmatch(target):
            errors.append(label + " has an invalid target language")
            continue
        if source == target:
            errors.append(label + " has identical source and target")
        if (source, target) in routes:
            errors.append(label + " duplicates a language route")
        routes.add((source, target))
        if pack_id != package_id(source, target):
            errors.append(label + " id does not match source/target")
        version = pack.get("version")
        if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
            errors.append(label + " version is not hf-<12 hex>")
        target_prefix = pack.get("targetPrefix")
        if not isinstance(target_prefix, str):
            errors.append(label + " targetPrefix is not a string")
            target_prefix = ""
        if source == "en" and target == "zh-Hans":
            if target_prefix != ">>cmn_Hans<<":
                errors.append(label + " en->zh-Hans must use >>cmn_Hans<<")
        elif target_prefix:
            errors.append(label + " has an unverified targetPrefix")
        pack_license = pack.get("license")
        pack_repository = pack.get("repository")
        if pack_license not in ALLOWED_LICENSES:
            errors.append(label + " license is not allowed")
        if (
            not isinstance(pack_repository, str)
            or not re.fullmatch(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+", pack_repository)
        ):
            errors.append(label + " repository is invalid")

        files = pack.get("files")
        if not isinstance(files, list) or len(files) != len(ROLE_SPECS):
            errors.append(label + " must contain exactly six files")
            continue
        by_role = {}
        names = set()
        pack_repo = None
        pack_revision = None
        file_total = 0
        weight_total = 0
        cached_config = None
        cached_vocab = None
        for file_index, file in enumerate(files):
            file_label = "{}/files[{}]".format(label, file_index)
            if not isinstance(file, dict):
                errors.append(file_label + " is not an object")
                continue
            required_file_keys = {
                "role",
                "name",
                "size",
                "sha256",
                "officialPath",
                "mirrorPath",
                "downloadPath",
                "url",
                "compression",
                "downloadSize",
                "downloadSha256",
            }
            missing = required_file_keys - set(file.keys())
            if missing:
                errors.append(file_label + " is missing keys: " + ", ".join(sorted(missing)))
                continue
            role = file["role"]
            if role not in ROLE_BY_NAME:
                errors.append(file_label + " has an unknown role")
                continue
            if role in by_role:
                errors.append(file_label + " duplicates role " + role)
            by_role[role] = file
            _, expected_name, expected_path, is_weight = ROLE_BY_NAME[role]
            if file["name"] != expected_name:
                errors.append(file_label + " name does not match role")
            if not isinstance(file["name"], str) or not SAFE_NAME_RE.fullmatch(file["name"]):
                errors.append(file_label + " has an unsafe stored name")
            if file["name"] in names:
                errors.append(file_label + " duplicates a stored name")
            names.add(file["name"])
            for key in ("officialPath", "mirrorPath", "downloadPath"):
                if file[key] != expected_path or not safe_relative_path(file[key]):
                    errors.append(file_label + " {} is not the expected safe path".format(key))
            size = file["size"]
            digest = file["sha256"]
            if not is_int(size) or size <= 0 or size > MAX_PACKAGE_BYTES:
                errors.append(file_label + " size is invalid")
                continue
            if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
                errors.append(file_label + " sha256 is not lowercase 64-hex")
            if file["compression"] != "NONE":
                errors.append(file_label + " compression is not NONE")
            if file["downloadSize"] != size or file["downloadSha256"] != digest:
                errors.append(file_label + " download size/hash differs from size/hash")
            url_re = re.compile(
                r"^https://huggingface\.co/([^/]+/[^/]+)/resolve/([a-f0-9]{40})/(.+)$"
            )
            match = url_re.fullmatch(str(file["url"]))
            if not match:
                errors.append(file_label + " url is not a pinned HF resolve URL")
            else:
                repo_from_url, revision_from_url, path_from_url = match.groups()
                if path_from_url != expected_path:
                    errors.append(file_label + " URL path does not match the role")
                if pack_repo is None:
                    pack_repo, pack_revision = repo_from_url, revision_from_url
                elif (pack_repo, pack_revision) != (repo_from_url, revision_from_url):
                    errors.append(file_label + " URL repo/revision differs within the pack")
            file_total += size
            if is_weight:
                weight_total += size

            if not is_weight:
                cache_file = small_file_cache_path(cache_dir, pack_repo or "", pack_revision or "", expected_name)
                meta_file = small_meta_path(cache_file)
                if cache_file.exists():
                    if not meta_file.exists():
                        errors.append(file_label + " cache file has no metadata sidecar")
                    else:
                        try:
                            meta = load_json_file(meta_file)
                            if (
                                meta.get("revision") != pack_revision
                                or meta.get("path") != expected_path
                                or meta.get("size") != size
                                or meta.get("sha256") != digest
                            ):
                                errors.append(file_label + " cache metadata does not match catalog")
                            elif sha256_file(cache_file) != digest:
                                errors.append(file_label + " cache file SHA-256 mismatch")
                            elif file.get("gitBlobSha1") and hashlib.sha1(
                                ("blob {}\0".format(size)).encode("ascii") + cache_file.read_bytes()
                            ).hexdigest() != file["gitBlobSha1"]:
                                errors.append(file_label + " cache file Git blob SHA-1 mismatch")
                            else:
                                verified_cache += 1
                                if role == "config":
                                    data = cache_file.read_bytes()
                                    config = load_json_object(data, str(cache_file))
                                    cached_config = config
                                    config_errors = validate_marian_config(config)
                                    errors.extend(
                                        "{}/cache config: {}".format(label, item)
                                        for item in config_errors
                                    )
                                elif role == "vocab":
                                    data = cache_file.read_bytes()
                                    vocab = load_json_object(data, str(cache_file))
                                    cached_vocab = vocab
                                    vocab_errors, _ = validate_vocab(vocab)
                                    errors.extend(
                                        "{}/cache vocab: {}".format(label, item)
                                        for item in vocab_errors
                                    )
                                    tokens = control_tokens(vocab)
                                    if tokens and target_prefix != ">>cmn_Hans<<":
                                        errors.append(
                                            label
                                            + " cache vocab has language control tokens but no verified prefix"
                                        )
                                    if source == "en" and target == "zh-Hans" and ">>cmn_Hans<<" not in tokens:
                                        errors.append(
                                            label + " cache vocab lacks >>cmn_Hans<<"
                                        )
                                elif role in ("srcVocab", "trgVocab"):
                                    problem = validate_sentencepiece(cache_file.read_bytes())
                                    if problem:
                                        errors.append(
                                            "{}/cache {}: {}".format(label, role, problem)
                                        )
                        except (OSError, ValueError, SkipPackage) as exc:
                            errors.append(file_label + " cache validation failed: {}".format(exc))
                else:
                    missing_cache += 1

        if set(by_role.keys()) != set(ROLE_ORDER):
            errors.append(label + " does not contain the six required roles")
            continue
        if [file["role"] for file in files] != ROLE_ORDER:
            errors.append(label + " file order is not stable")
        if cached_config is not None and cached_vocab is not None:
            vocab_ids = set(cached_vocab.values())
            for key in ("pad_token_id", "eos_token_id", "decoder_start_token_id"):
                if cached_config.get(key) not in vocab_ids:
                    errors.append(
                        "{} cache config {} is absent from cached vocab".format(label, key)
                    )
        if file_total > MAX_PACKAGE_BYTES:
            errors.append(label + " total size exceeds 350,000,000 bytes")
        if pack_repo is None or pack_revision is None:
            errors.append(label + " has no parseable pinned repository revision")
            continue
        expected_upstream = upstream_url(pack_repo)
        expected_source = upstream_repository(pack_repo)
        if version != "hf-" + pack_revision[:12]:
            errors.append(label + " version does not match the pinned revision")
        entry = provenance.get(pack_id)
        if not isinstance(entry, dict):
            errors.append(label + " has no provenance entry")
        else:
            required_provenance = {
                "repository",
                "revision",
                "license",
                "licenseSource",
                "upstreamUrl",
                "weightBytes",
                "totalBytes",
            }
            missing = required_provenance - set(entry.keys())
            if missing:
                errors.append(label + " provenance is missing keys: " + ", ".join(sorted(missing)))
            if entry.get("repository") != pack_repo:
                errors.append(label + " provenance repository differs from file URLs")
            if pack_repository != pack_repo:
                errors.append(label + " pack repository differs from file URLs")
            if pack_license != entry.get("license"):
                errors.append(label + " pack license differs from provenance")
            if entry.get("revision") != pack_revision:
                errors.append(label + " provenance revision differs from file URLs")
            if entry.get("license") not in ALLOWED_LICENSES:
                errors.append(label + " provenance license is not allowed")
            if entry.get("licenseSource") not in (pack_repo, expected_source):
                errors.append(label + " provenance licenseSource is unexpected")
            if entry.get("upstreamUrl") != expected_upstream:
                errors.append(label + " provenance upstreamUrl is unexpected")
            if entry.get("weightBytes") != weight_total:
                errors.append(label + " provenance weightBytes does not match the ONNX files")
            if entry.get("totalBytes") != file_total:
                errors.append(label + " provenance totalBytes does not match all files")

    if set(provenance.keys()) != ids:
        errors.append("provenance keys do not exactly match package ids")

    for candidate in Path(cache_dir).rglob("*") if Path(cache_dir).exists() else []:
        if candidate.is_file() and candidate.suffix.lower() == ".onnx":
            errors.append("cache contains a forbidden ONNX file: {}".format(candidate))

    if errors:
        return errors, warnings
    warnings.append(
        "cache files verified: {}, cache files absent: {}".format(
            verified_cache, missing_cache
        )
    )
    return [], warnings


def check_command(args):
    errors, warnings = validate_catalog(Path(args.catalog), Path(args.cache_dir))
    if errors:
        print("FAILED: {} catalog error(s)".format(len(errors)), file=sys.stderr)
        for message in errors:
            print("  - " + message, file=sys.stderr)
        return 1
    catalog = load_json_file(args.catalog)
    sizes = [pack_total(pack) for pack in catalog["packages"]]
    direct = [
        pack
        for pack in catalog["packages"]
        if pack["source"] != "en" and pack["target"] != "en"
    ]
    print(
        "OK: {} packages, {} direct non-English, size range {}..{} bytes".format(
            len(catalog["packages"]), len(direct), min(sizes), max(sizes)
        )
    )
    for warning in warnings:
        print("  " + warning)
    return 0


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description=(
            "Generate or check the pinned OPUS-MT ONNX CPU catalog. "
            "ONNX weights are never downloaded."
        )
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate the existing catalog and cache without any network access",
    )
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="refresh cached HF metadata and license metadata (small files stay pinned by revision)",
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=DEFAULT_CATALOG,
        help="catalog JSON path (default: %(default)s)",
    )
    parser.add_argument(
        "--summary",
        type=Path,
        default=DEFAULT_SUMMARY,
        help="generation summary path (default: %(default)s)",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=DEFAULT_CACHE,
        help="metadata and small-file cache (default: %(default)s)",
    )
    parser.add_argument(
        "--log",
        type=Path,
        default=DEFAULT_LOG,
        help="run log path (default: %(default)s)",
    )
    args = parser.parse_args(argv)
    if args.check and args.refresh:
        parser.error("--check and --refresh cannot be combined")
    return args


def main(argv=None):
    args = parse_args(argv)
    if args.check:
        return check_command(args)
    log = RunLog()
    try:
        return build_catalog(args, log)
    except Exception as exc:
        log.add("fatal: {}: {}".format(type(exc).__name__, exc))
        print("fatal: {}: {}".format(type(exc).__name__, exc), file=sys.stderr)
        return 1
    finally:
        log.write(args.log)


if __name__ == "__main__":
    sys.exit(main())
