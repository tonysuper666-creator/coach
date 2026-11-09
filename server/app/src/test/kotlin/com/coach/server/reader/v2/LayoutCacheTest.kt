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

    @Test fun missingPageUnknownDocAndFutureSchemaAreNull() {
        val c = LayoutCache(fixture())
        assertNull(c.page("doc", 9)); assertNull(c.page("nope", 1)); assertNull(c.page("doc", 3))
        assertFailsWith<IllegalArgumentException> { c.page("../x", 1) }
    }
}
