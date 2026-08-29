package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ScheduleConfig
import com.example.model.TcpPacket
import com.example.model.TimeRange
import com.example.service.MeshStateManager
import com.example.service.ScheduleManager
import com.example.utils.NetworkUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read app name from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("WiFi TCP Mesh", appName)
  }

  @Test
  fun `test tcp packet serialization and deserialization`() {
    val packet = TcpPacket(TcpPacket.Type.DATA, "node_1", "node_2", "hello_mesh")
    val json = packet.toJson()
    assertNotNull(json)
    assertTrue(json.contains("hello_mesh"))

    val parsed = TcpPacket.fromJson(json)
    assertNotNull(parsed)
    assertEquals(TcpPacket.Type.DATA, parsed.type)
    assertEquals("node_1", parsed.senderId)
    assertEquals("hello_mesh", parsed.payload)
  }

  @Test
  fun `test mesh state manager singleton`() {
    val state = MeshStateManager.getInstance()
    assertNotNull(state)
    state.isServiceRunning = true
    assertTrue(state.isServiceRunning)
  }

  @Test
  fun `test time range evaluation`() {
    // 04:00 - 06:00 range
    val range = TimeRange(4, 0, 6, 0, "Test Range")
    assertTrue(range.isInside(4, 0))
    assertTrue(range.isInside(5, 30))
    assertTrue(range.isInside(6, 0))
    assertFalse(range.isInside(3, 59))
    assertFalse(range.isInside(6, 1))
    assertFalse(range.isInside(12, 0))
  }

  @Test
  fun `test schedule config inverted calculation`() {
    val config = ScheduleConfig()
    // User sets: Red TCP OFF / Wi-Fi ON from 04:00 to 05:30 (same-day window)
    // Red TCP ON Complement: [00:00 - 03:59] and [05:31 - 23:59]
    config.applyInvertedSchedule(4, 0, 5, 30)
    
    // Check Wi-Fi active hours (4:00 - 5:30)
    assertTrue(config.shouldWifiBeActive(4, 0))
    assertTrue(config.shouldWifiBeActive(4, 30))
    assertTrue(config.shouldWifiBeActive(5, 30))
    assertFalse(config.shouldWifiBeActive(3, 59))
    assertFalse(config.shouldWifiBeActive(5, 31))
    assertFalse(config.shouldWifiBeActive(12, 0))

    // Check Red TCP Complement active hours (00:00-03:59 and 05:31-23:59)
    assertTrue(config.shouldHotspotBeActive(0, 0))
    assertTrue(config.shouldHotspotBeActive(2, 0))
    assertTrue(config.shouldHotspotBeActive(3, 59))
    assertFalse(config.shouldHotspotBeActive(4, 0))
    assertFalse(config.shouldHotspotBeActive(5, 0))
    assertFalse(config.shouldHotspotBeActive(5, 30))
    assertTrue(config.shouldHotspotBeActive(5, 31))
    assertTrue(config.shouldHotspotBeActive(12, 0))
    assertTrue(config.shouldHotspotBeActive(23, 59))
  }

  @Test
  fun `test schedule manager initialization and update`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = ScheduleManager.getInstance()
    manager.init(context)

    val customRange = TimeRange(10, 0, 12, 0, "Almuerzo")
    manager.addWifiRange(customRange)
    
    val config = manager.config
    assertTrue(config.shouldWifiBeActive(10, 30))
  }
}

