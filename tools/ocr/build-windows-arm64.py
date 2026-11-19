"""Rebuild the bundled Windows ARM64 engine. See src/windowsArm64/resources/ocr/NOTICE.md."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tarfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

import pefile


root = Path.cwd()
desktop = Path(__file__).resolve().parents[2] / "desktopApp"
toolchain = root / "llvm-mingw-20261117-ucrt-x86_64"
os.environ["PATH"] = str(toolchain / "bin") + os.pathsep + os.environ["PATH"]
os.environ["SOURCE_DATE_EPOCH"] = "1767225600"


def verify(file, digest):
    if hashlib.sha256(file.read_bytes()).hexdigest() != digest:
        raise ValueError(f"Unexpected SHA-256: {file}")


for name, digest in [
    ("lept.tgz", "fa2b40c5caea96d1bb93a97486262aed8731b69ce25a84a6bf5d25323e33f631"),
    ("tess.tgz", "6235ea0dae45ea137f59c09320406f5888383741924d98855bd2ce0d16b54f21"),
]:
    verify(root / name, digest)
    with tarfile.open(name) as archive:
        archive.extractall(root, filter="data")
verify(root / "mingw-runtime.txt", "1db8da07b436c68833c0673ffee3d9fcb2526047f3820b81661865dfedc79a1f")

prefix = root / "prefix"
configure = [
    "cmake", "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_SYSTEM_NAME=Windows",
    "-DCMAKE_SYSTEM_PROCESSOR=aarch64", "-DCMAKE_C_COMPILER=aarch64-w64-mingw32-clang",
    "-DCMAKE_CXX_COMPILER=aarch64-w64-mingw32-clang++", "-DCMAKE_RC_COMPILER=aarch64-w64-mingw32-windres",
    "-DSW_BUILD=OFF", "-DBUILD_SHARED_LIBS=ON", "-DCMAKE_SHARED_LINKER_FLAGS=-static -s -Wl,--no-insert-timestamp",
    f"-DCMAKE_INSTALL_PREFIX={prefix.as_posix()}",
]
codecs = ["ZLIB", "JPEG", "OPENJPEG", "PNG", "WEBP", "TIFF", "GIF"]
subprocess.run(configure + ["-S", "leptonica-1.87.0", "-B", "lb"] + [f"-DENABLE_{c}=OFF" for c in codecs], check=True)
subprocess.run(["cmake", "--build", "lb", "--target", "install", "-j", "4"], check=True)
subprocess.run(configure + [
    "-S", "tesseract-5.5.2", "-B", "tb", f"-DLeptonica_DIR={prefix.as_posix()}/lib/cmake/leptonica",
    f'-DCMAKE_CXX_FLAGS=-D_USE_MATH_DEFINES "-ffile-prefix-map={root.as_posix()}=."',
    "-DOPENMP_BUILD=OFF", "-DBUILD_TRAINING_TOOLS=OFF", "-DGRAPHICS_DISABLED=ON",
    "-DDISABLE_TIFF=ON", "-DLEPT_TIFF_RESULT=1", "-DDISABLE_CURL=ON", "-DDISABLE_ARCHIVE=ON", "-DENABLE_NATIVE=OFF",
], check=True)
subprocess.run(["cmake", "--build", "tb", "--target", "libtesseract", "-j", "4"], check=True)

lept = prefix / "bin/libleptonica-1.87.0.dll"
tess = root / "tb/bin/libtesseract55.dll"
bridge = desktop / "src/main/kotlin/com/coach/desktop/books/BookOcr.kt"
required = set(re.findall(r"fun (Tess\w+)\(", bridge.read_text(encoding="utf-8")))
for dll in (lept, tess):
    with pefile.PE(str(dll)) as pe:
        if pe.FILE_HEADER.Machine != 0xAA64:
            raise ValueError(f"Expected AA64: {dll.name}")
        imports = [entry.dll.decode().lower() for entry in
                   pe.DIRECTORY_ENTRY_IMPORT + getattr(pe, "DIRECTORY_ENTRY_DELAY_IMPORT", [])]
        for name in imports:
            if name in {"kernel32.dll", "gdi32.dll", "user32.dll"} or name.startswith("api-ms-win-crt-"):
                continue
            if dll != tess or name != lept.name:
                raise ValueError(f"Unbundled dependency: {dll.name} -> {name}")
        if dll == tess:
            exports = {s.name.decode() for s in pe.DIRECTORY_ENTRY_EXPORT.symbols if s.name}
            if required - exports:
                raise ValueError(f"Missing Tesseract exports: {required - exports}")
        print(f"{dll.name}: AA64 imports={','.join(imports)}")

common = desktop / "src/main/resources/ocr"
files = {dll.name: dll.read_bytes() for dll in (lept, tess)}
with ZipFile(common / "windows-x64.zip") as x64:
    for name in x64.namelist():
        if name.startswith(("licenses/", "tessdata/")):
            files[name] = x64.read(name)
files["licenses/llvm.txt"] = (toolchain / "LICENSE.TXT").read_bytes()
files["licenses/mingw-runtime.txt"] = (root / "mingw-runtime.txt").read_bytes()
out = desktop / "src/windowsArm64/resources/ocr"
out.mkdir(parents=True, exist_ok=True)
with ZipFile(out / "windows-arm64.zip", "w") as archive:
    for name in sorted(files):
        archive.writestr(ZipInfo(name), files[name], compress_type=ZIP_DEFLATED)
(out / "sha256.txt").write_text("".join(f"{hashlib.sha256(files[name]).hexdigest()}  {name}\n"
                                        for name in sorted(files)), encoding="ascii", newline="\n")
