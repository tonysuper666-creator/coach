package com.coach.desktop.books

internal fun importedPdfVisuals(pdf: BookPdfDocument, pages: List<BookPage>, control: BookImportControl): List<ExtractedIllustration> {
    // Preserve canonical text and store supplemental enclosed regions through the
    // same content-addressed transaction as other page visuals. Display is separate.
    val visuals = mutableListOf<ExtractedIllustration>()
    var visualBytes = 0L
    for (page in pages) {
        control.report(page.number, pdf.numberOfPages)
        if (visuals.size >= FormatBookImages.MAX_IMAGES || visualBytes >= FormatBookImages.MAX_TOTAL) break
        val enclosed = PdfBoxedVisuals.extract(pdf, page, control)
        val tables = PdfPrintedTables.extract(pdf, page, control, enclosed)
        for (crop in PdfBookImages.extract(pdf, page, enclosed + tables, control)) {
            if (visuals.size >= FormatBookImages.MAX_IMAGES || visualBytes + crop.bytes.size > FormatBookImages.MAX_TOTAL) break
            visuals += crop
            visualBytes += crop.bytes.size
        }
    }
    return visuals
}
