"""Check JNI constructors and ASR native declarations in the actual APK, including R8."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

# These are resolved by native code, which R8 cannot inspect. Missing them
# causes process aborts rather than a catchable Kotlin/Java exception.
REQUIRED = {
    "ai.onnxruntime.NodeInfo": ("java.lang.String", "ai.onnxruntime.ValueInfo"),
    "com.google.ai.edge.litert.LiteRtException": ("int", "java.lang.String"),
    "ca.mpreg.imagedecoder.ImageDecoder": ("long", "int", "int", "boolean", "java.lang.String"),
    "ca.mpreg.imagedecoder.ImageDecoder$DecodeResult": ("long", "java.nio.ByteBuffer", "int", "int", "int", "int", "int", "int", "int"),
    "ca.mpreg.imagedecoder.ImageDecoder$DecodeException": ("java.lang.String",),
}

ASR_CLASS = "com.lmreader.core.translation.asr.WhisperNative"
ASR_METHODS = {
    "capabilities": ("java.lang.String", ()),
    "create": ("long", ("java.lang.String", "java.lang.String")),
    "describe": ("java.lang.String", ("long",)),
    "transcribe": ("java.lang.String", ("long", "float[]", "java.lang.String", "int")),
    "abort": ("void", ("long",)),
    "release": ("void", ("long",)),
}


def verify(apk, dexdump):
    found = set()
    asr_found = set()
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="lmreader-jni-") as folder:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:\d+)?\.dex", name):
                continue
            dex = Path(folder) / name
            dex.write_bytes(archive.read(name))
            xml = Path(folder) / "api.xml"
            with xml.open("wb") as output:
                subprocess.run([str(dexdump), "-l", "xml", str(dex)], stdout=output,
                               stderr=subprocess.PIPE, check=True)
            package = ""
            for event, element in ET.iterparse(xml, events=("start", "end")):
                if event == "start" and element.tag == "package":
                    package = element.get("name", "")
                elif event == "end" and element.tag == "class":
                    class_name = package + "." + element.get("name", "")
                    if class_name in REQUIRED:
                        for constructor in element.findall("constructor"):
                            parameters = tuple(p.get("type") for p in constructor.findall("parameter"))
                            if constructor.get("static") == "false" and parameters == REQUIRED[class_name]:
                                found.add(class_name)
                    if class_name == ASR_CLASS:
                        for method in element.findall("method"):
                            method_name = method.get("name")
                            signature = (method.get("return"), tuple(p.get("type") for p in method.findall("parameter")))
                            if method.get("native") == "true" and ASR_METHODS.get(method_name) == signature:
                                asr_found.add(method_name)
                    element.clear()
    missing = sorted(set(REQUIRED) - found)
    if missing:
        raise RuntimeError("APK is missing JNI constructors; check engine R8 rules: " + ", ".join(missing))
    missing_asr = sorted(set(ASR_METHODS) - asr_found)
    if missing_asr:
        raise RuntimeError("APK is missing ASR JNI declarations; check bridge signatures/R8 rules: " + ", ".join(missing_asr))
    return sorted(found)


if __name__ == "__main__":
    from package_release import build_tools, tool
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    print("Verified engine JNI constructors: " + ", ".join(verify(args.apk, tool(build_tools(), "dexdump"))))
