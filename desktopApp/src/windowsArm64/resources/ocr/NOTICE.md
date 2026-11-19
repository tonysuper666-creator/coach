# Bundled Windows ARM64 OCR

LearningCoach decodes scans to raw pixels with ImageIO and PDFBox. This build of Tesseract 5.5.2 and Leptonica 1.87.0 accepts those pixels through the Tesseract C API. It uses the same Simplified Chinese and English models as the x64 package. The archive contains the licenses for the engines, models and statically linked compiler runtimes. The adjacent manifest records each uncompressed file's SHA-256.

## Rebuilding the archive

Use an empty working directory on Windows with Python 3.12, pefile, CMake and Ninja on PATH. Download these inputs into that directory.

| Local name | Source |
| --- | --- |
| `tess.tgz` | [Tesseract 5.5.2](https://codeload.github.com/tesseract-ocr/tesseract/tar.gz/refs/tags/5.5.2) |
| `lept.tgz` | [Leptonica 1.87.0](https://codeload.github.com/DanBloomberg/leptonica/tar.gz/refs/tags/1.87.0) |
| `mingw-runtime.txt` | [MinGW runtime redistribution notices](https://raw.githubusercontent.com/mingw-w64/mingw-w64/41eb042358a52011b35350392130dcdc94a8ead8/COPYING.MinGW-w64-runtime/COPYING.MinGW-w64-runtime.txt) |
| `llvm-mingw-20261117-ucrt-x86_64.zip` | [LLVM-MinGW release archive](https://github.com/mstorsjo/llvm-mingw/releases/download/20261117/llvm-mingw-20261117-ucrt-x86_64.zip) |

Verify the toolchain archive SHA-256 is `4d002e330a0f703625aa5ccbc37e96f8f824413e163bfc7336774aabd2c3a412` and extract it here. From this directory run `python <checkout>/tools/ocr/build-windows-arm64.py`. The script verifies the source inputs, cross-compiles the libraries and checks both PE import tables and the C functions used by the JNA bridge before writing the ZIP and manifest.

The model revision is [tessdata_fast 87416418](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd).
