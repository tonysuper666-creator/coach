package com.coach.domain.scope

/**
 * The single answer to "which source pages may this AI retrieval / explanation / search touch".
 *
 * Narrow-only by construction: the constructor is private, no operation widens a scope, and the only way to combine two scopes is
 * [narrow] (intersection). The default for anything unspecified is [NONE]. Trusted server code obtains a ceiling from
 * [learner] or [tutorInternal] (derived from permissions and the learning record); callers then only narrow it.
 *
 * Unlearned chapters, quiz answers and held-out questions are expressed by the ceiling: a page whose document is not
 * learner-visible, or that lies outside a [pages] window, is simply not permitted.
 */
class VisibleScope private constructor(
    private val internalReference: Boolean,
    private val docs: Set<String>?,
    private val windows: Map<String, IntRange>,
) {
    /** Is this page inside the scope? [learnerVisible] is the store's verdict (document flag and answer/sealed name filter). */
    fun permits(docId: String, pdfPage: Int, learnerVisible: Boolean): Boolean {
        if (!learnerVisible && !internalReference) return false
        if (docs != null && docId !in docs) return false
        val window = windows[docId]
        return window == null || pdfPage in window
    }

    /** Pages that are not learner-visible may be read as private tutor reference only when the ceiling says so. */
    val allowsInternalReference: Boolean get() = internalReference

    /** Intersection: never larger than either operand, commutative, idempotent. */
    fun narrow(other: VisibleScope): VisibleScope {
        val mergedDocs = when {
            docs == null -> other.docs
            other.docs == null -> docs
            else -> docs intersect other.docs
        }
        val keys = windows.keys + other.windows.keys
        val merged = keys.associateWith { k ->
            val a = windows[k]; val b = other.windows[k]
            when {
                a == null -> b!!
                b == null -> a
                else -> maxOf(a.first, b.first)..minOf(a.last, b.last)
            }
        }
        return VisibleScope(internalReference && other.internalReference, mergedDocs, merged)
    }

    /** Narrow page windows without restricting which documents are listed; documents without a window are unaffected. */
    fun limitPages(windows: Map<String, IntRange>): VisibleScope = narrow(VisibleScope(true, null, windows))

    fun narrowToDocs(vararg ids: String): VisibleScope = narrow(VisibleScope(true, ids.toSet(), emptyMap()))
    /** Current passage / chapter / learned range of one document, as an inclusive pdf page window. */
    fun narrowToPages(doc: String, pages: IntRange): VisibleScope = narrow(VisibleScope(true, setOf(doc), mapOf(doc to pages)))

    override fun equals(other: Any?) = other is VisibleScope && internalReference == other.internalReference &&
        docs == other.docs && windows.filterValues { !it.isEmpty() } == other.windows.filterValues { !it.isEmpty() }
    override fun hashCode() = listOf(internalReference, docs, windows.filterValues { !it.isEmpty() }).hashCode()

    companion object {
        /**
         * "Already studied" as page windows, from the outline anchors of learned and not-yet-learned points. Within a document the
         * window ends right before the earliest not-yet-learned anchor; a document whose only anchors are unlearned gets an empty window.
         */
        fun learnedWindows(learned: List<Pair<String, Int>>, unlearned: List<Pair<String, Int>>): Map<String, IntRange> {
            val learnedDocs = learned.map { it.first }.toSet()
            val frontier = unlearned.groupBy({ it.first }, { it.second }).mapValues { it.value.min() }
            return (learnedDocs + frontier.keys).associateWith { doc ->
                if (doc in learnedDocs) 1..((frontier[doc] ?: Int.MAX_VALUE) - 1) else 1..0
            }
        }

        /** Nothing is visible. The safe default. */
        val NONE = VisibleScope(false, emptySet(), emptyMap())
        /** Ceiling for anything learner-facing: learner-visible documents only. */
        fun learner() = VisibleScope(false, null, emptyMap())
        /** Ceiling for tutor-side prompts that may carry private reference text (answer keys, sealed material) that is never quotable. */
        fun tutorInternal() = VisibleScope(true, null, emptyMap())
    }
}
