package com.coach.server.reader.v2

import java.nio.file.Files
import kotlin.test.*

/** Synthetic fixture only; mirrors tools/reader-v2/layout page JSON schema_version 1. */
class LayoutCacheTest {
    private fun fixture(): java.nio.file.Path {
        val root = Files.createTempDirectory("layout")
        val v = root.resolve("doc").resolve("abc-p1-docling2")
        Files.createDirectories(v.resolve("pages"))
        Files.writeString(root.resolve("doc/latest.json"), """{"version":"abc-p1-docling2"}""")
        Files.writeString(v.resolve("manifest.json"), "{}")
        Files.writeString(v.resolve("pages/00001.json"), """{"schema_version":1,"status":"ok","columns":2,
          "reading_order_status":"ok","rotation":0,"size":[595.28,841.89],"blocks":[
          {"id":"p1b0","kind":"text","bbox":[60,90,270,180],"reading_order":0,"column":1,"status":"ok","text":"synthetic"},
          {"id":"p1b1","kind":"table","bbox":[60,200,270,300],"reading_order":1,"column":0,"status":"unknown","in_reading_flow":false,"fallback":"original_page_region"}]}""")
        Files.writeString(v.resolve("pages/00002.json"), """{"schema_version":1,"status":"ok","columns":2,
          "reading_order_status":"conflict","fallback":"original_page","rotation":0,"size":null,"blocks":[]}""")
        Files.writeString(v.resolve("pages/00004.json"), """{"schema_version":1,"status":"ok","columns":1,
          "reading_order_status":"single_column","rotation":0,"size":[595,842],"blocks":[
          {"id":"a","kind":"text","bbox":[72,300,500,350],"reading_order":0,"column":0,"status":"ok","text":"x"},
          {"id":"b","kind":"text","bbox":[72,100,500,150],"reading_order":1,"column":0,"status":"ok","text":"y"}]}""")
        Files.writeString(v.resolve("pages/00005.json"), """{"schema_version":1,"status":"ok","columns":1,
          "reading_order_status":"single_column","rotation":0,"size":[595,842],"blocks":[
          {"id":"a","kind":"table","raw_label":"document_index","bbox":[60,100,280,700],"reading_order":0,"column":0,"status":"ok"},
          {"id":"b","kind":"table","raw_label":"document_index","bbox":[320,100,540,700],"reading_order":1,"column":0,"status":"ok"}]}""")
        Files.writeString(v.resolve("pages/00006.json"), """{"schema_version":1,"status":"ok","columns":1,
          "reading_order_status":"single_column","rotation":0,"size":[595,842],"blocks":[
          {"id":"a","kind":"text","bbox":[60,100,280,300],"reading_order":0,"column":0,"status":"ok","text":"x"},
          {"id":"b","kind":"text","bbox":[320,110,540,300],"reading_order":1,"column":0,"status":"ok","text":"y"}]}""")
        Files.writeString(v.resolve("pages/00003.json"), """{"schema_version":2,"status":"ok","blocks":[]}""")
        return root
    }

    @Test fun readsPageAndTrustsOrderOnlyWhenSafe() {
        val c = LayoutCache(fixture())
        val p = assertNotNull(c.page("doc", 1))
        assertEquals(2, p.columns); assertTrue(p.orderTrusted)
        assertFalse(p.blocks[1].inReadingFlow); assertEquals("original_page_region", p.blocks[1].fallback)
        assertFalse(assertNotNull(c.page("doc", 2)).orderTrusted)
    }

    @Test fun upsideDownOrderIsNotTrustedEvenIfProducerSaysSingleColumn() {
        val p = assertNotNull(LayoutCache(fixture()).page("doc", 4))
        assertEquals("single_column", p.readingOrderStatus); assertFalse(p.orderTrusted)
    }

    @Test fun indexTablesAndSideBySideBlocksAreNotTrustedEvenIfProducerSaysOk() {
        val c = LayoutCache(fixture())
        for (n in listOf(5, 6)) {
            val p = assertNotNull(c.page("doc", n))
            assertEquals("single_column", p.readingOrderStatus)
            assertFalse(p.orderTrusted, "page $n")
        }
    }

    @Test fun missingPageUnknownDocAndFutureSchemaAreNull() {
        val c = LayoutCache(fixture())
        assertNull(c.page("doc", 9)); assertNull(c.page("nope", 1)); assertNull(c.page("doc", 3))
        assertFailsWith<IllegalArgumentException> { c.page("../x", 1) }
    }
}
