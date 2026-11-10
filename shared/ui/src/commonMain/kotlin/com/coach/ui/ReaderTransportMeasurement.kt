package com.coach.ui

import com.coach.client.ReaderHttp
import kotlinx.serialization.json.*

/** Transport diagnostic only: no decode, draw, hit or presentation claims. */
suspend fun measureSyntheticReaderTransport(reader: ReaderHttp): JsonObject {
    val rows = (1..12).map { page ->
        val events = mutableListOf<JsonObject>()
        val start = ReaderPageTrace.nowNs()
        val request = ReaderPageTrace.request(0, page, 0)
        val bytes = reader.image("synthetic-baseline", page, "v1") { kind ->
            events += buildJsonObject { put("kind", kind); put("ns", ReaderPageTrace.nowNs()) }
            ReaderPageTrace.mark(kind, 0, page, 0, request)
        }
        check(bytes != null && bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte())
        buildJsonObject {
            put("page", page); put("startNs", start); put("endNs", ReaderPageTrace.nowNs())
            put("bytes", bytes.size); put("stages", JsonArray(events))
        }
    }
    return buildJsonObject { put("scope", "transport only; no UI timing"); put("samples", JsonArray(rows)) }
}
