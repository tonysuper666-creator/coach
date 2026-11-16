Bundled Windows x64 OCR runtime

Tesseract 5.5.2 (Apache 2.0) and Leptonica 1.87.0 (BSD 2-Clause),
extracted unchanged from Tess4J 5.18.0 and Lept4J 1.23.0 Maven Central JARs:
https://repo.maven.apache.org/maven2/net/sourceforge/tess4j/tess4j/5.18.0/tess4j-5.18.0.jar
https://repo.maven.apache.org/maven2/net/sourceforge/lept4j/lept4j/1.23.0/lept4j-1.23.0.jar

Simplified Chinese and English integer LSTM models (Apache 2.0):
https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd

windows-x64.zip contains the two DLLs, chi_sim/eng traineddata and all three
component licenses. sha256.txt lists the exact uncompressed payload digests.
No full Tess4J/Lept4J Java wrapper or other platform binaries are shipped.
C API cancellation/deadline contract:
https://github.com/tesseract-ocr/tesseract/blob/5.5.2/include/tesseract/capi.h
