package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testBatteryProfilesExist() {
    val profiles = com.example.data.model.BatteryProfile.values()
    assertTrue(profiles.isNotEmpty())
    val balanced = profiles.first { it.name == "BALANCED" }
    val perf = profiles.first { it.name == "PERFORMANCE" }
    val gaming = profiles.first { it.name == "GAMING" }

    assertEquals(40.0f, balanced.thermalLimitC, 0.01f)
    assertEquals(43.0f, perf.thermalLimitC, 0.01f)
    assertEquals(47.0f, gaming.thermalLimitC, 0.01f)
  }
}
