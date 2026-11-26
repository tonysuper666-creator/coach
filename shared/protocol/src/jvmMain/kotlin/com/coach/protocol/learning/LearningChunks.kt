package com.coach.protocol.learning

import java.security.MessageDigest
import java.util.HexFormat

const val LEARNING_CHUNK_BYTES = 256 * 1024
const val LEARNING_UPLOAD_LIMIT = 64 * 1024 * 1024
const val LEARNING_CHUNK_TTL_MS = 86_400_000L

fun learningDigest(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
