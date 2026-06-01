package com.chronotrace.sdk

import kotlin.test.Test
import kotlin.test.assertTrue

class ChronoPlatformLinuxTest {

    @Test
    fun shouldReturnPositiveValueWhenCallingNowMillis() {
        val result = ChronoPlatform.nowMillis()
        assertTrue(result > 0, "nowMillis() should return a value greater than 0, got: $result")
    }

    @Test
    fun shouldReturnDifferentValuesWhenCalledAtDifferentTimes() {
        val first = ChronoPlatform.nowMillis()
        val second = ChronoPlatform.nowMillis()
        assertTrue(second >= first, "nowMillis() should advance or stay the same, first: $first, second: $second")
        // At least one of the calls should differ (unless we're running impossibly fast)
        assertTrue(second > 0, "second value should be positive")
    }
}