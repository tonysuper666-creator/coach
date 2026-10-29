package com.coach.domain.grade

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NumericValueTest {

    @Test
    fun arabicAndChineseNumeralsAgree() {
        assertTrue(NumericValue.matches("0.25", "0.25"))
        assertTrue(NumericValue.matches("二十五", "25"))
        assertTrue(NumericValue.matches("一百二十", "120"))
        assertTrue(NumericValue.matches("十一", "11"))
        assertTrue(NumericValue.matches("三点一四", "3.14"))
        assertTrue(NumericValue.matches("两", "2"))
    }

    @Test
    fun minusVariantsAndFractions() {
        assertTrue(NumericValue.matches("−3/11", "负十一分之三"))
        assertTrue(NumericValue.matches("负十一分之三", "-3/11"))
        assertTrue(NumericValue.matches("六分之一", "1/6"))
        assertTrue(NumericValue.matches("-0.5", "负二分之一"))
    }

    @Test
    fun percentages() {
        assertTrue(NumericValue.matches("20%", "百分之二十五").not()) // 20% != 25%
        assertTrue(NumericValue.matches("25%", "百分之二十五"))
        assertTrue(NumericValue.matches("25%", "0.25"))
        assertTrue(NumericValue.matches("百分之六", "0.06"))
    }

    @Test
    fun toleranceIsRelativeWithAbsoluteFloor() {
        assertTrue(NumericValue.matches("0.3333", "1/3", relTolerance = 2e-4))
        assertFalse(NumericValue.matches("0.3333", "1/3"))
        assertFalse(NumericValue.matches("19800", "23300"))
        assertTrue(NumericValue.matches("2.33万", "23300"))
        assertTrue(NumericValue.matches("0", "0.0000000001"))
    }

    @Test
    fun nonNumericTextIsNotComparable() {
        assertFalse(NumericValue.matches("大概是零点二五左右吧，我不确定", "0.25"))
        assertFalse(NumericValue.matches("", "0"))
        assertFalse(NumericValue.matches("无", "0"))
    }
}
