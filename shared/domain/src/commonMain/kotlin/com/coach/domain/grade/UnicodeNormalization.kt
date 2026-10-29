package com.coach.domain.grade

/** NFKC 归一（ADR-0003：NFKC + 空白归一 + 减号统一）。两个目标端都有 `java.text.Normalizer`，按平台各自实现。 */
internal expect fun nfkc(value: String): String
