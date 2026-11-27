package com.coach.desktop.books

import java.util.IdentityHashMap

/** Resolve physical PDF bookmark destinations before any text extraction or pagination. */
internal fun extractBookPdfOutline(pdf: BookPdfDocument): List<BookOutlineItem> {
    val root = pdf.documentCatalog?.documentOutline ?: return emptyList()
    val pageIndices = IdentityHashMap<Any, Int>(pdf.numberOfPages)
    pdf.pages.forEachIndexed { index, page -> pageIndices[page.cosObject] = index + 1 }
    val seen = IdentityHashMap<Any, Boolean>()
    val result = mutableListOf<BookOutlineItem>()

    fun walk(first: BookPdfOutlineEntry?, level: Int) {
        var entry = first
        while (entry != null && seen.size < 10_000) {
            val item = entry
            if (seen.put(item.cosObject, true) != null) break
            val title = item.title?.trim().orEmpty()
            val page = runCatching { item.findDestinationPage(pdf) }.getOrNull()?.let { pageIndices[it.cosObject] }
            if (title.isNotEmpty() && page != null) result += BookOutlineItem(title, level, page, HeadingSource.PDF_OUTLINE)
            if (level < 32) walk(item.firstChild, level + 1)
            entry = item.nextSibling
        }
    }

    walk(root.firstChild, 1)
    // Journal PDFs often wrap every section in one bookmark named after the paper.
    val first = result.firstOrNull()
    if (first != null && first.page == 1 && first.level == 1 &&
        first.title.equals(pdf.documentInformation?.title?.trim(), ignoreCase = true) &&
        result.count { it.level == 1 } == 1 && result.count { it.level == 2 } >= 2) {
        return result.drop(1).map { it.copy(level = it.level - 1) }
    }
    return result
}
