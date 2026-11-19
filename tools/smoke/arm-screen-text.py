"""Recognize controls in the smoke driver's enlarged 24-bit Windows bitmap."""
import ctypes as c
import csv
import io
import json
import os
from pathlib import Path
import struct
import sys
import zipfile


def engine(cache):
    with open(sys.executable, "rb") as exe:
        exe.seek(60)
        offset, = struct.unpack("<I", exe.read(4))
        exe.seek(offset + 4)
        machine, = struct.unpack("<H", exe.read(2))
    source, arch, dll = {
        0xaa64: ("windowsArm64", "arm64", "libtesseract55.dll"),
        0x8664: ("main", "x64", "libtesseract552.dll"),
    }[machine]
    if not (cache / dll).exists():
        root = Path(__file__).resolve().parents[2] / "desktopApp" / "src" / source
        with zipfile.ZipFile(root / f"resources/ocr/windows-{arch}.zip") as bundle:
            bundle.extractall(cache)
    os.environ["OMP_THREAD_LIMIT"] = "2"
    return c.CDLL(str(cache / dll))


def words(bmp, cache):
    data = bmp.read_bytes()
    pixels, = struct.unpack_from("<I", data, 10)
    width, height, _, bits, compression = struct.unpack_from("<iiHHI", data, 18)
    if bits != 24 or height <= 0 or compression != 0:
        raise ValueError("Expected a bottom-up 24-bit BMP")
    stride = (width * 3 + 3) & ~3
    raster = b"".join(data[pixels + i * stride:pixels + (i + 1) * stride] for i in reversed(range(height)))
    api = engine(cache)
    ptr, integer = c.c_void_p, c.c_int
    for name, args, ret in [
        ("TessBaseAPICreate", [], ptr),
        ("TessBaseAPIInit2", [ptr, c.c_char_p, c.c_char_p, integer], integer),
        ("TessBaseAPISetPageSegMode", [ptr, integer], None),
        ("TessBaseAPISetImage", [ptr, c.c_char_p, integer, integer, integer, integer], None),
        ("TessBaseAPIGetTsvText", [ptr, integer], ptr),
        ("TessDeleteText", [ptr], None),
        ("TessBaseAPIDelete", [ptr], None),
    ]:
        func = getattr(api, name)
        func.argtypes, func.restype = args, ret
    handle = api.TessBaseAPICreate()
    try:
        if api.TessBaseAPIInit2(handle, str(cache / "tessdata").encode(), b"chi_sim", 1):
            raise RuntimeError("Could not initialize screen OCR")
        api.TessBaseAPISetPageSegMode(handle, 11)  # sparse UI labels
        api.TessBaseAPISetImage(handle, raster, width, height, 3, stride)
        tsv = api.TessBaseAPIGetTsvText(handle, 0)
        if not tsv:
            raise RuntimeError("Missing TSV")
        try:
            rows = csv.reader(io.StringIO(c.string_at(tsv).decode()), delimiter="\t", quoting=csv.QUOTE_NONE)
            for row in rows:
                if len(row) == 12 and row[0] == "5" and row[11].strip():
                    yield [row[2:5], row[11], *map(int, row[6:10])]
        finally:
            api.TessDeleteText(tsv)
    finally:
        api.TessBaseAPIDelete(handle)


if __name__ == "__main__":
    print(json.dumps(list(words(*map(Path, sys.argv[1:])))))
