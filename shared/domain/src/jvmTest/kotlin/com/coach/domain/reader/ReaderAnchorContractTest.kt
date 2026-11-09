package com.coach.domain.reader

import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class ReaderAnchorContractTest {
    @Test fun publicSyntheticWireExamplesResolveAndRoundTripWithoutChangingSemantics() {
        val fixture = ReaderAnchorJson.parseToJsonElement(File(System.getProperty("coach.repo"),
            "docs/reader-anchors/contract-v1.json").readText(Charsets.UTF_8)).jsonObject
        for (item in fixture.getValue("cases").jsonArray) {
            val case = item.jsonObject
            val anchor = ReaderAnchorJson.decodeFromJsonElement<ReaderAnchor>(case.getValue("anchor"))
            val index = ReaderAnchorJson.decodeFromJsonElement<ReaderDocumentIndex>(case.getValue("index"))
            val resolution = ReaderAnchorResolver.resolve(anchor, index)
            assertEquals(case.getValue("expected"), ReaderAnchorJson.encodeToJsonElement(resolution), case.getValue("name").toString())
            assertEquals(case.getValue("anchor"), ReaderAnchorJson.encodeToJsonElement(anchor))
            assertEquals(case.getValue("index"), ReaderAnchorJson.encodeToJsonElement(index))
        }
        val labels = ReaderAnchorJson.decodeFromJsonElement<VerifiedPageLabels>(fixture.getValue("pageLabels"))
        assertEquals(PageLookupStatus.UNKNOWN, labels.forLabel("2").status)
        val entry = ReaderAnchorJson.decodeFromJsonElement<ReaderAnchorEntry>(fixture.getValue("entry"))
        assertEquals(fixture.getValue("entry"), ReaderAnchorJson.encodeToJsonElement(entry))
    }
}
