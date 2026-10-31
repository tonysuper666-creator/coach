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
    @Test fun chineseZerosLargeSectionsAndNonFiniteInputs() {
        for((chinese,arabic) in listOf("一百零二" to "102", "一千零三十" to "1030", "二〇二六" to "2026",
            "一亿二千万" to "120000000", "负0.25" to "-0.25", "负百分之二十五" to "-0.25", "负三点零五" to "-3.05", "十一分之负三" to "-3/11")) {
            assertTrue(NumericValue.matches(chinese,arabic), chinese)
        }
        for(value in listOf("NaN","Infinity","1e309","零分之一","十百","一百百"))
            assertFalse(NumericValue.matches(value,"1"),value)
        assertFalse(NumericValue.close(Double.POSITIVE_INFINITY,1.0))
        assertFalse(NumericValue.close(1.0,Double.POSITIVE_INFINITY))
        assertFalse(NumericValue.close(1.0,1.0,relTolerance=-1.0))
    }

}
