package com.coach.domain.grade

import java.text.Normalizer

internal actual fun nfkc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
