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

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val diagnosticsJson =
  GsonBuilder().serializeSpecialFloatingPointValues().serializeNulls().setPrettyPrinting().create()

/** Redact values before serialization so quotes and escapes cannot corrupt the JSON document. */
internal fun diagnosticJson(value: Any?, secrets: List<String> = emptyList()): String {
  fun redact(element: JsonElement): JsonElement =
    when {
      element.isJsonObject ->
        element.asJsonObject.apply {
          entrySet().toList().forEach { (key, value) -> add(key, redact(value)) }
        }
      element.isJsonArray ->
        element.asJsonArray.apply { for (i in 0 until size()) set(i, redact(get(i))) }
      element.isJsonPrimitive && element.asJsonPrimitive.isNumber && !element.asDouble.isFinite() ->
        JsonNull.INSTANCE
      element.isJsonPrimitive && element.asJsonPrimitive.isString ->
        JsonPrimitive(redactDiagnostics(element.asString, secrets))
      else -> element
    }
  return diagnosticsJson.toJson(redact(diagnosticsJson.toJsonTree(value)))
}

/** Bounded, atomic, app-private history. All calls are made by the recorder's single IO worker. */
internal class DiagnosticsHistory(
  private val file: File,
  private val maxEvents: Int = 120,
  private val maxBytes: Int = 2 * 1024 * 1024,
) {
  private val events = linkedMapOf<String, JsonElement>()
  var evictedEvents = 0L
    private set

  var storageError: String? = null
    private set

  init {
    require(maxEvents > 0 && maxBytes > 0)
    try {
      if (file.exists()) {
        require(file.length() <= maxBytes) { "History exceeded its size limit" }
        val saved = JsonParser.parseString(file.readText()).asJsonObject
        evictedEvents = saved.get("evictedEvents")?.asLong ?: 0
        saved.getAsJsonArray("events").forEach { event ->
          events[event.asJsonObject.get("id").asString] = event
        }
        trim()
      }
    } catch (error: Exception) {
      events.clear()
      storageError = "History could not be loaded: ${error.javaClass.simpleName}"
    }
  }

  fun put(id: String, event: Map<String, Any?>) {
    events[id] = JsonParser.parseString(diagnosticJson(event))
    trim()
    persist()
  }

  fun snapshot(): Map<String, Any?> =
    mapOf(
      "schemaVersion" to 1,
      "events" to events.values.map { it.deepCopy() },
      "evictedEvents" to evictedEvents,
      "eventLimit" to maxEvents,
      "byteLimit" to maxBytes,
      "storageError" to storageError,
    )

  private fun trim() {
    while (
      events.isNotEmpty() &&
        (events.size > maxEvents ||
          diagnosticsJson.toJson(snapshot()).toByteArray().size > maxBytes)
    ) {
      events.remove(events.keys.first())
      evictedEvents++
    }
  }

  private fun persist() {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    try {
      file.parentFile?.mkdirs()
      temporary.outputStream().use { stream ->
        stream.write(diagnosticsJson.toJson(snapshot()).toByteArray(Charsets.UTF_8))
        stream.fd.sync()
      }
      Files.move(
        temporary.toPath(),
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (error: Exception) {
      storageError = "History write failed: ${error.javaClass.simpleName}"
    } finally {
      temporary.delete()
    }
  }
}

/** Counts callbacks and characters only; callback chunks are never labelled as model tokens. */
internal class StreamStatistics(private val startedNs: Long) {
  private var callbacks = 0L
  private var nonEmptyCallbacks = 0L
  private var answerCharacters = 0L
  private var thinkingCharacters = 0L
  private var firstNs: Long? = null
  private var previousNs: Long? = null
  private var gapTotalNs = 0L
  private var gapMaxNs = 0L

  @Synchronized
  fun add(nowNs: Long, answerLength: Int, thinkingLength: Int): Boolean {
    callbacks++
    answerCharacters += answerLength
    thinkingCharacters += thinkingLength
    if (answerLength == 0 && thinkingLength == 0) return false
    val first = firstNs == null
    if (first) firstNs = nowNs
    previousNs?.let { gap ->
      val elapsed = (nowNs - gap).coerceAtLeast(0)
      gapTotalNs += elapsed
      gapMaxNs = maxOf(gapMaxNs, elapsed)
    }
    previousNs = nowNs
    nonEmptyCallbacks++
    return first
  }

  @Synchronized
  fun snapshot(endedNs: Long): Map<String, Any?> =
    mapOf(
      "callbackCount" to callbacks,
      "nonEmptyCallbackCount" to nonEmptyCallbacks,
      "answerCharacterCount" to answerCharacters,
      "thinkingCharacterCount" to thinkingCharacters,
      "appTimeToFirstContentMs" to firstNs?.let { (it - startedNs) / 1e6 },
      "appDecodeAfterFirstContentMs" to firstNs?.let { (endedNs - it) / 1e6 },
      "meanContentCallbackGapMs" to
        if (nonEmptyCallbacks > 1) gapTotalNs / (nonEmptyCallbacks - 1) / 1e6 else null,
      "maxContentCallbackGapMs" to if (nonEmptyCallbacks > 1) gapMaxNs / 1e6 else null,
      "countsAreTokens" to false,
    )
}

internal class FrameStatistics {
  private var count = 0L
  private var late = 0L
  private var missing = 0L
  private var totalNs = 0.0
  private var maxNs = 0L

  @Synchronized
  fun add(durationNs: Long, deadlineNs: Long, droppedReports: Int) {
    missing += droppedReports
    if (durationNs < 0) return
    count++
    totalNs += durationNs
    maxNs = maxOf(maxNs, durationNs)
    if (deadlineNs > 0 && durationNs > deadlineNs) late++
  }

  @Synchronized
  fun snapshot(): Map<String, Any?> =
    mapOf(
      "frameCount" to count,
      "framesOverOwnDeadline" to late,
      "droppedFrameMetricReports" to missing,
      "meanFrameMs" to if (count > 0) totalNs / count / 1e6 else null,
      "maxFrameMs" to if (count > 0) maxNs / 1e6 else null,
      "scope" to "Activity window frames during the operation; no frames when not rendering",
    )
}

/**
 * Keep a bounded raw series, but aggregate every sample, including those discarded from the series.
 */
internal class ResourceSeries(private val limit: Int = 64) {
  init {
    require(limit > 0)
  }

  private val samples = mutableListOf<Map<String, Number?>>()
  private val peaks = mutableMapOf<String, Double>()
  private val sums = mutableMapOf<String, Double>()
  private val counts = mutableMapOf<String, Int>()
  private var count = 0

  @Synchronized
  fun add(sample: Map<String, Number?>) {
    count++
    sample.forEach { (key, value) ->
      value
        ?.toDouble()
        ?.takeIf { it.isFinite() }
        ?.let {
          peaks[key] = maxOf(peaks[key] ?: it, it)
          sums[key] = (sums[key] ?: 0.0) + it
          counts[key] = (counts[key] ?: 0) + 1
        }
    }
    if (samples.size == limit) samples.removeAt(if (limit > 1) 1 else 0)
    samples.add(sample.mapValues { (_, value) -> value?.takeIf { it.toDouble().isFinite() } })
  }

  @Synchronized
  fun snapshot(): Map<String, Any?> =
    mapOf(
      "totalSamples" to count,
      "omittedRawSamples" to count - samples.size,
      "samples" to samples.toList(),
      "peaks" to peaks.toMap(),
      "means" to sums.mapValues { (key, value) -> value / counts.getValue(key) },
      "availableSampleCounts" to counts.toMap(),
    )
}
