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

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.proto.BenchmarkResult
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single captured report shared by Downloads and explicitly requested GitHub uploads. */
class DiagnosticReport(val filename: String, val json: String)

/** Captures diagnostics independently of their destination; neither capture nor export uploads. */
object DiagnosticsExporter {
  suspend fun capture(
    context: Context,
    modelStatuses: Map<String, ModelDownloadStatus> = emptyMap(),
    secrets: List<String> = emptyList(),
    models: List<Model> = emptyList(),
    benchmarkResults: () -> List<BenchmarkResult> = { emptyList() },
  ): DiagnosticReport =
    withContext(Dispatchers.IO) {
      val now = Instant.now()
      val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
      val filename =
        "edge-gallery-diagnostics-${timestamp.format(now)}-${UUID.randomUUID().toString().take(8)}.json"
      val report =
        diagnosticJson(
          mapOf(
            "schemaVersion" to 2,
            "generatedAtUtc" to now.toString(),
            "app" to appDiagnosticSnapshot(),
            "device" to deviceDiagnosticSnapshot(context),
            "performance" to diagnosticSection { PerformanceDiagnostics.snapshot() },
            "models" to
              models
                .distinctBy { it.name }
                .map { diagnosticSection { modelDiagnosticSnapshot(context, it) } },
            "downloads" to
              modelStatuses.toSortedMap().map { (name, status) ->
                mapOf(
                  "modelName" to name,
                  "status" to status.status.name,
                  "receivedBytes" to status.receivedBytes,
                  "totalBytes" to status.totalBytes,
                  "failureReason" to status.failureReason?.name,
                  "unavailabilityReason" to status.unavailability?.reason?.name,
                  "error" to status.errorMessage.take(2048),
                )
              },
            "storedBenchmarks" to
              diagnosticSection { benchmarkResults().map(::storedBenchmarkSnapshot) },
            "appLogs" to
              mapOf(
                "scope" to
                  "Current app process, main and crash buffers, last 2000 lines, at most 512 Ki characters",
                "text" to readAppLogs(context),
              ),
            "captureNotes" to
              listOf(
                "Run model initialization, normal chat, and controlled benchmarks before exporting. Old chat timings cannot be reconstructed.",
                "All timings use a monotonic clock; UTC timestamps correlate events. First content callback TTFT includes input preparation and scheduling, and is distinct from native TTFT.",
                "Context counts before the first turn may be zero when LiteRT-LM defers initial history prefill. Prefill counts include runtime-processed history/media and may differ from the new prompt token count.",
                "Null or absent counters mean unavailable, unsupported, or not finalized; never assume zero. Callback chunks and character counts are not tokens.",
                "CPU percent is relative to one core and can exceed 100. PSS/RSS/graphics accounting describe the app process, not model-exclusive memory or physical GPU VRAM.",
                "Thermal status uses Android PowerManager codes 0 (none) to 6 (shutdown). Headroom is cached for at least 10 seconds; 1.0 is the severe-throttling threshold, not remaining capacity.",
                "Battery current/temperature/charge are whole-device readings and depend on charging, other apps, and vendor support. They are not per-model power or energy measurements.",
                "Configured backends do not prove every operation's placement. LiteRT-LM does not expose per-operator placement/timing, fallback counts, KV-cache allocation bytes/precision, GPU utilization/VRAM/bandwidth, or NPU counters through this API. A native profiler/instrumented runtime is required for those.",
                "Sampling is asynchronous and active only during recorded operations, nominally 1 second; short spikes can be missed. Tail probes can occur after completion: sampleAfterOperationEndMs reports their delay. Probe duration and discarded raw sample counts are reported. Instrumentation and native benchmark mode have nonzero overhead.",
                "Structured coverage: LiteRT-LM initialization, streaming chat including ephemeral requests, synchronous helper calls, and benchmark runs. Synchronous calls have no app streaming TTFT; use the native TTFT when available. Other runtimes, ASR/TTS, and externally managed conversations do not provide equivalent native turn metrics.",
                "History is private to this app and bounded. No diagnostics are uploaded automatically. Prompt/response bodies and media are not deliberately recorded in structured metrics; errors and raw logs can contain user data. Review before sharing. Known auth tokens are redacted.",
              ),
          ),
          secrets,
        )
      DiagnosticReport(filename, report)
    }

  suspend fun saveToDownloads(context: Context, report: DiagnosticReport): String =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      val values =
        ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, report.filename)
          put(MediaStore.Downloads.MIME_TYPE, "application/json")
          put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
          put(MediaStore.Downloads.IS_PENDING, 1)
        }
      val uri =
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          ?: throw IOException("Could not create the diagnostics file in Downloads")
      try {
        val output =
          resolver.openOutputStream(uri) ?: throw IOException("Could not open the diagnostics file")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(report.json) }
        val published =
          resolver.update(
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
      report.filename
    }

  private fun readAppLogs(context: Context): String {
    val logFile = File.createTempFile("diagnostics-", ".log", context.cacheDir)
    var process: java.lang.Process? = null
    return try {
      process =
        ProcessBuilder(
            "logcat",
            "-b",
            "main",
            "-b",
            "crash",
            "-d",
            "-v",
            "threadtime",
            "--pid=${Process.myPid()}",
            "-t",
            "2000",
          )
          .redirectErrorStream(true)
          .redirectOutput(logFile)
          .start()
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
