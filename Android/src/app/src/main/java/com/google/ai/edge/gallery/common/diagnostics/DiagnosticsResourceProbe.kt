/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.common.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit

/** One shared probe, read on the recorder IO worker. Missing/restricted readings remain null. */
internal class DiagnosticsResourceProbe(private val context: Context) {
  private var previousWallMs: Long? = null
  private var previousCpuMs: Long? = null
  private val frequencies =
    (0 until Runtime.getRuntime().availableProcessors().coerceAtMost(32))
      .associateWith { File("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") }
      .filterValues { it.canRead() }

  fun sample(): Map<String, Number?> {
    val began = System.nanoTime()
    val result = linkedMapOf<String, Number?>()
    result["elapsedRealtimeMs"] = SystemClock.elapsedRealtime()
    val wall = SystemClock.elapsedRealtime()
    val cpu = Process.getElapsedCpuTime()
    result["processCpuTimeMs"] = cpu
    result["processCpuPercentOfOneCore"] =
      previousWallMs?.let { previous ->
        val interval = wall - previous
        if (interval > 0) (cpu - (previousCpuMs ?: cpu)) * 100.0 / interval else null
      }
    previousWallMs = wall
    previousCpuMs = cpu
    result["availableProcessorCount"] = Runtime.getRuntime().availableProcessors()
    val runtime = Runtime.getRuntime()
    result["javaHeapUsedBytes"] = runtime.totalMemory() - runtime.freeMemory()
    result["javaHeapLimitBytes"] = runtime.maxMemory()
    result["nativeHeapAllocatedBytes"] = Debug.getNativeHeapAllocatedSize()
    read {
      val memory = Debug.MemoryInfo()
      Debug.getMemoryInfo(memory)
      result["processPssBytes"] = memory.totalPss.toLong() * 1024
      result["processPrivateDirtyBytes"] = memory.totalPrivateDirty.toLong() * 1024
      result["attributedGraphicsBytes"] =
        memory.getMemoryStat("summary.graphics")?.toLongOrNull()?.times(1024)
    }
    read {
      val info = ActivityManager.MemoryInfo()
      context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
      result["deviceAvailableRamBytes"] = info.availMem
      result["deviceTotalRamBytes"] = info.totalMem
      result["deviceLowMemory"] = if (info.lowMemory) 1 else 0
    }
    read {
      File("/proc/self/status").useLines { lines ->
        lines.forEach { line ->
          val key =
            when (line.substringBefore(':')) {
              "VmRSS" -> "processRssBytes"
              "VmHWM" -> "processPeakRssBytes"
              "Threads" -> "processThreadCount"
              else -> null
            }
          if (key != null)
            result[key] =
              line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.let {
                if (key.endsWith("Bytes")) it * 1024 else it
              }
        }
      }
    }
    read {
      val power = context.getSystemService(PowerManager::class.java)
      result["thermalStatus"] = power.currentThermalStatus
      result["powerSaveMode"] = if (power.isPowerSaveMode) 1 else 0
      result["deviceIdleMode"] = if (power.isDeviceIdleMode) 1 else 0
      val headroom = headroom(power, began)
      result["thermalHeadroomForecast0s"] = headroom.first
      result["thermalHeadroomReadingAgeMs"] = headroom.second
    }
    read {
      val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      battery?.let {
        val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        result["batteryLevelPercent"] = if (level >= 0 && scale > 0) 100.0 * level / scale else null
        result["batteryTemperatureC"] =
          it
            .getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { value -> value != Int.MIN_VALUE }
            ?.div(10.0)
        result["batteryVoltageMv"] =
          it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { v -> v >= 0 }
        result["batteryStatus"] = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        result["batteryPluggedSource"] = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
      }
      val manager = context.getSystemService(BatteryManager::class.java)
      result["wholeDeviceBatteryCurrentUa"] =
        manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf {
          it != Int.MIN_VALUE
        }
      result["wholeDeviceBatteryChargeCounterUah"] =
        manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf {
          it != Int.MIN_VALUE
        }
    }
    read {
      Debug.getRuntimeStats().forEach { (key, value) ->
        if (
          key in
            setOf(
              "art.gc.gc-count",
              "art.gc.gc-time",
              "art.gc.blocking-gc-count",
              "art.gc.blocking-gc-time",
              "art.gc.bytes-allocated",
              "art.gc.bytes-freed",
            )
        ) {
          result[key] = value.toLongOrNull()
        }
      }
    }
    frequencies.forEach { (core, file) ->
      result["cpu${core}FrequencyKhz"] =
        runCatching { file.readText().trim().toLongOrNull() }.getOrNull()
    }
    result["probeDurationMs"] = (System.nanoTime() - began) / 1e6
    return result
  }

  companion object {
    private var lastHeadroomNs = 0L
    private var thermalHeadroom: Float? = null

    @Synchronized
    private fun headroom(power: PowerManager, now: Long): Pair<Float?, Double> {
      if (now - lastHeadroomNs >= TimeUnit.SECONDS.toNanos(10) || lastHeadroomNs == 0L) {
        thermalHeadroom =
          runCatching { power.getThermalHeadroom(0) }.getOrNull()?.takeIf { it.isFinite() }
        lastHeadroomNs = now
      }
      return thermalHeadroom to (now - lastHeadroomNs) / 1e6
    }
  }

  private inline fun read(action: () -> Unit) {
    try {
      action()
    } catch (_: Exception) {
      /* Unsupported counters are absent, never zero-filled. */
    }
  }
}
