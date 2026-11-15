package com.coach.desktop.books

import kotlin.test.*

/** A claimed complete set must never substitute for independently verified coverage. */
class IncompletePosedClaimAuditProbe {
    @Test fun declarationCannotEstablishAuthoritativePosedCoverage() {
        val f = SourceReceiptFixture()
        val knownPosed = f.input.usedQuestions.single()
        val truncated = f.input.copy(usedQuestions = emptySet())
        runCatching { f.library.importReviewVariantSource(f.review(), f.file(f.receipt(truncated))) }
        val ready = f.review().canStartVariant
        val offered = f.library.startReviewVariant(f.review())
        f.snapshot("incomplete-claim.sqlite")
        println("INCOMPLETE_CLAIM omitted=$knownPosed acceptedReady=$ready selected=${offered.variant?.allocation?.question} integrity=${f.data.integrity()}")
        assertFalse(ready, "A true declaration alone must not certify a truncated posed set; current importer trusts the owner")
    }
}
