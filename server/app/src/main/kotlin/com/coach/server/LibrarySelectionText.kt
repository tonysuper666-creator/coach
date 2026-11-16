package com.coach.server

/** Extraction comparison only for a bound local file; never fuzzy matching or joining pages/blocks. */
internal fun containsBoundBookSelection(text: String, quote: String): Boolean {
    fun plain(value: String): String = value
        // Paired emphasis around a word, never a variable's intraword underscore.
        .replace(Regex("(?<![\\p{L}\\p{N}_])_([\\p{L}]+)_(?![\\p{L}\\p{N}_])"), "$1")
        // Letter-to-letter hyphenation at an actual line break; keep ordinary hyphens and numbers.
        .replace(Regex("(?<=[\\p{L}])-[\\t ]*\\r?\\n[\\t ]*(?=[\\p{L}])"), "")
        .trim().replace(Regex("\\s+"), " ")
    val selected = plain(quote)
    return selected.isNotEmpty() && plain(text).contains(selected)
}
