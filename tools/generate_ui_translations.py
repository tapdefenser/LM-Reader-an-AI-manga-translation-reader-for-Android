"""Refresh the offline English catalog for legacy Kotlin UI strings.

Requires the optional argostranslate Python package and its zh -> en model.
Run from the repository root; review the generated translations before shipping.
"""

from __future__ import annotations

import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = (ROOT / "app/src/main/java/com/lmreader", ROOT / "core")
OUTPUT = ROOT / "app/src/main/assets/i18n/zh_en.json"
OVERRIDES = ROOT / "tools/ui_translation_overrides.json"
HAN = re.compile(r"[\u3400-\u9fff]")
INTERPOLATION = re.compile(r"\$\{[^{}]+\}|\$[A-Za-z_][A-Za-z_0-9]*")


def interpolation_end(source: str, position: int) -> int:
    """Skip a Kotlin interpolation, including nested strings and braces."""
    depth = 1
    position += 2
    while position < len(source) and depth:
        if source[position] in ('"', "'"):
            position = quoted_end(source, position)
        elif source[position] == "{":
            depth += 1
            position += 1
        elif source[position] == "}":
            depth -= 1
            position += 1
        else:
            position += 1
    return position


def quoted_end(source: str, position: int) -> int:
    quote = source[position]
    if source.startswith('"""', position):
        end = source.find('"""', position + 3)
        return len(source) if end < 0 else end + 3
    position += 1
    while position < len(source):
        if source[position] == "\\":
            position += 2
        elif quote == '"' and source.startswith("${", position):
            position = interpolation_end(source, position)
        elif source[position] == quote:
            return position + 1
        else:
            position += 1
    return position


def literals(source: str) -> list[str]:
    result: list[str] = []
    position = 0
    while position < len(source):
        if source.startswith("//", position):
            end = source.find("\n", position)
            position = len(source) if end < 0 else end + 1
            continue
        if source.startswith("/*", position):
            position += 2
            depth = 1
            while position < len(source) and depth:
                if source.startswith("/*", position):
                    depth += 1
                    position += 2
                elif source.startswith("*/", position):
                    depth -= 1
                    position += 2
                else:
                    position += 1
            continue
        if source.startswith('"""', position):
            end = source.find('"""', position + 3)
            if end < 0:
                break
            result.append(source[position + 3 : end])
            position = end + 3
            continue
        if source[position] == '"':
            start = position + 1
            end = quoted_end(source, position)
            result.append(source[start:end - 1])
            position = end
            continue
        if source[position] == "'":
            position += 1
            while position < len(source):
                if source[position] == "\\":
                    position += 2
                elif source[position] == "'":
                    position += 1
                    break
                else:
                    position += 1
            continue
        position += 1
    return result


def template(value: str) -> tuple[str, str]:
    tokens: list[str] = []
    chunks: list[str] = []
    position = 0
    while position < len(value):
        if value[position] == "\\" and position + 1 < len(value):
            escaped = value[position + 1]
            chunks.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "$": "$"}.get(escaped, "\\" + escaped))
            position += 2
            continue
        if value.startswith("${", position):
            end = interpolation_end(value, position)
        else:
            match = re.match(r"\$[A-Za-z_][A-Za-z_0-9]*", value[position:])
            end = position + len(match.group()) if match else position
        if end > position:
            token = f"<{len(tokens)}>"
            tokens.append(token)
            chunks.append(token)
            position = end
        else:
            chunks.append(value[position])
            position += 1
    marked = "".join(chunks)
    source = marked
    for index, token in enumerate(tokens):
        source = source.replace(token, f"__VAR{index}__")
    return source, marked


def main() -> None:
    current = json.loads(OUTPUT.read_text(encoding="utf-8")) if OUTPUT.exists() else {}
    candidates: dict[str, str] = {}
    files = (file for source_root in SOURCES for file in source_root.rglob("*.kt")
             if source_root.name != "core" or "src/main" in file.as_posix())
    for file in files:
        if file.parts[-2] == "i18n":
            continue
        for value in literals(file.read_text(encoding="utf-8")):
            if HAN.search(value):
                key, marked = template(value)
                if len(key) <= 700 and key.strip() and HAN.search(key) and not key.startswith(("UPDATE ", "SELECT ", "INSERT ")):
                    candidates.setdefault(key, marked)

    missing = [(key, marked) for key, marked in candidates.items() if key not in current]
    overrides = json.loads(OVERRIDES.read_text(encoding="utf-8"))
    missing = [(key, marked) for key, marked in missing if key not in overrides]
    print(f"Found {len(candidates)} Chinese literals, translating {len(missing)} new entries", flush=True)
    if missing:
        import argostranslate.translate
    for number, (key, marked) in enumerate(missing, 1):
        try:
            english = argostranslate.translate.translate(marked, "zh", "en").strip()
        except Exception as failure:
            print(f"Could not translate {key!r}: {failure}", flush=True)
            continue
        english = re.sub(r"<\s*(\d+)\s*>", lambda match: f"__VAR{match.group(1)}__", english)
        if "__VAR" in key and not all(token in english for token in re.findall(r"__VAR\d+__", key)):
            chunks = re.split(r"(<\d+>)", marked)
            english = "".join(
                f"__VAR{chunk[1:-1]}__" if re.fullmatch(r"<\d+>", chunk)
                else argostranslate.translate.translate(chunk, "zh", "en")
                for chunk in chunks
            ).strip()
        current[key] = english
        if number % 50 == 0:
            print(f"Translated {number}/{len(missing)}", flush=True)
            OUTPUT.write_text(json.dumps(current, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    current.update(overrides)
    for key in list(current):
        if any(fragment in key for fragment in ('${if(', '${input(', '${type.', '${backends.joinToString(')):
            del current[key]
    OUTPUT.write_text(json.dumps(current, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Catalog entries: {len(current)}", flush=True)


if __name__ == "__main__":
    main()
