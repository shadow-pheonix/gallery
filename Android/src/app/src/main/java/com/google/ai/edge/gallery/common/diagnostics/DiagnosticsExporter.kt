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
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.MediaStore
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exports an app-scoped report to public Downloads without a storage permission. */
object DiagnosticsExporter {
  suspend fun export(
    context: Context,
    modelStatuses: Map<String, ModelDownloadStatus> = emptyMap(),
    secrets: List<String> = emptyList(),
  ): String = withContext(Dispatchers.IO) {
    val now = Instant.now()
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
    val filename = "edge-gallery-diagnostics-${timestamp.format(now)}-${UUID.randomUUID().toString().take(8)}.txt"
    val report = buildString {
      appendLine("AI Edge Gallery diagnostics")
      appendLine("Generated (UTC): $now")
      appendLine("App: ${BuildConfig.APPLICATION_ID}")
      appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
      appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
      appendLine()
      appendLine("Device")
      appendLine("Manufacturer: ${Build.MANUFACTURER}")
      appendLine("Model: ${Build.MODEL}")
      appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
      appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
      appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
      val memory = ActivityManager.MemoryInfo()
      context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
      appendLine("RAM bytes: total=${memory.totalMem}, available=${memory.availMem}, low=${memory.lowMemory}")
      val storage = StatFs(context.filesDir.absolutePath)
      appendLine("App storage bytes: total=${storage.totalBytes}, available=${storage.availableBytes}")
      val runtime = Runtime.getRuntime()
      appendLine("Java heap bytes: max=${runtime.maxMemory()}, used=${runtime.totalMemory() - runtime.freeMemory()}")
      appendLine()
      appendLine("Model download status")
      if (modelStatuses.isEmpty()) appendLine("No model status available.")
      modelStatuses.toSortedMap().forEach { (name, status) ->
        appendLine("$name: ${status.status}, bytes=${status.receivedBytes}/${status.totalBytes}")
        status.failureReason?.let { appendLine("  Failure: $it") }
        status.unavailability?.let { appendLine("  Unavailable: ${it.reason}") }
        if (status.errorMessage.isNotBlank()) appendLine("  Error: ${status.errorMessage}")
      }
      appendLine()
      appendLine("Recent app logs (current process, up to 2000 lines)")
      appendLine(readAppLogs(context))
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, filename)
      put(MediaStore.Downloads.MIME_TYPE, "text/plain")
      put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: throw IOException("Could not create the diagnostics file in Downloads")
    try {
      val output = resolver.openOutputStream(uri)
        ?: throw IOException("Could not open the diagnostics file")
      output.bufferedWriter(Charsets.UTF_8).use { it.write(redactDiagnostics(report, secrets)) }
      val published = resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
        null,
        null,
      )
      if (published != 1) throw IOException("Could not publish the diagnostics file")
    } catch (error: Exception) {
      // An unsuccessful write must not leave a partial/pending file in Downloads.
      runCatching { resolver.delete(uri, null, null) }
      throw error
    }
    filename
  }

  private fun readAppLogs(context: Context): String {
    val logFile = File.createTempFile("diagnostics-", ".log", context.cacheDir)
    var process: java.lang.Process? = null
    return try {
      process = ProcessBuilder(
        "logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", "2000",
      ).redirectErrorStream(true).redirectOutput(logFile).start()
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        "App log capture timed out."
      } else if (process.exitValue() != 0) {
        "App logs are unavailable on this device."
      } else {
        // Bound the report size even if a log line is unusually large.
        logFile.inputStream().bufferedReader().use { reader ->
          val buffer = CharArray(512 * 1024)
          var count = 0
          while (count < buffer.size) {
            val read = reader.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
          }
          if (count <= 0) "No recent app logs available." else String(buffer, 0, count)
        }
      }
    } catch (_: IOException) {
      "App logs are unavailable on this device."
    } catch (_: SecurityException) {
      "App log access is restricted on this device."
    } finally {
      process?.destroyForcibly()
      logFile.delete()
    }
  }
}
