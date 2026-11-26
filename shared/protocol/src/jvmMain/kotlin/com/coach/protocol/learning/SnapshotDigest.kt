package com.coach.protocol.learning

import java.security.MessageDigest
import java.util.HexFormat

/** A page boundary or a restart does not affect the digest of this ordered sequence of JSON records. */
fun snapshotDigest(previous: String, item: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    hash.update(HexFormat.of().parseHex(previous))
    return HexFormat.of().formatHex(hash.digest(item.encodeToByteArray()))
}
