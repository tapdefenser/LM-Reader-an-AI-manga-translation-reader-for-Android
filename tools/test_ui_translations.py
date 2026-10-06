import json
import re
import unittest
import xml.etree.ElementTree as ET
import generate_ui_translations as translations


class UiTranslationTests(unittest.TestCase):
    def test_android_english_resources_cover_every_localizable_string(self):
        resources = translations.ROOT / "app/src/main/res"
        def strings(folder):
            return {element.attrib["name"]: "".join(element.itertext())
                    for file in folder.glob("*.xml") for element in ET.parse(file).getroot()
                    if element.tag == "string" and element.attrib.get("translatable") != "false"}
        source, english = strings(resources / "values"), strings(resources / "values-en")
        self.assertEqual([], sorted(source.keys() - english.keys()))
        for key in source:
            self.assertEqual(set(re.findall(r"%\d*\$?[sd]", source[key])), set(re.findall(r"%\d*\$?[sd]", english[key])), key)

    def test_nested_interpolation_does_not_split_a_kotlin_string(self):
        source = 'Text("版本 ${version} · ${if (expanded) "展开" else "折叠"} · 行数 ${rows.size}")'
        values = translations.literals(source)
        self.assertEqual(1, len(values))
        self.assertEqual("版本 __VAR0__ · __VAR1__ · 行数 __VAR2__", translations.template(values[0])[0])

    def test_escaped_dollar_is_literal_and_escaped_text_is_decoded(self):
        self.assertEqual(('提示词 ${语言}\n"内容"', '提示词 ${语言}\n"内容"'),
                         translations.template(r'提示词 \${语言}\n\"内容\"'))

    def test_catalog_has_all_current_localizable_literals(self):
        catalog = json.loads(translations.OUTPUT.read_text(encoding="utf-8"))
        missing = set()
        for root in translations.SOURCES:
            for file in root.rglob("*.kt"):
                if "src/main" not in file.as_posix() or file.parts[-2] == "i18n":
                    continue
                for value in translations.literals(file.read_text(encoding="utf-8")):
                    key = translations.template(value)[0]
                    if translations.HAN.search(key) and key.strip() and len(key) <= 700 and not key.startswith(("UPDATE ", "SELECT ", "INSERT ")) and key not in catalog:
                        missing.add(key)
        self.assertEqual([], sorted(missing))

    def test_placeholder_sets_are_preserved_and_old_parser_fragments_are_absent(self):
        catalog = json.loads(translations.OUTPUT.read_text(encoding="utf-8"))
        for key, english in catalog.items():
            self.assertEqual(set(re.findall(r"__VAR\d+__", key)), set(re.findall(r"__VAR\d+__", english)), key)
            self.assertNotIn('${if(', key)
            self.assertNotIn("I don't know", english)


if __name__ == "__main__":
    unittest.main()
