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

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsDataTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun retainedHistorySurvivesRestartAndUpdatesAnExistingRun() {
    val file = File(temporary.root, "nested/history.json")
    val history = DiagnosticsHistory(file)
    history.put("run", mapOf("id" to "run", "status" to "RUNNING"))
    history.put("run", mapOf("id" to "run", "status" to "CANCELLED", "ttftMs" to null))
    val saved =
      JsonParser.parseString(diagnosticJson(DiagnosticsHistory(file).snapshot())).asJsonObject
    val events = saved.getAsJsonArray("events")
    assertEquals(1, events.size())
    assertEquals("CANCELLED", events[0].asJsonObject["status"].asString)
    assertTrue(events[0].asJsonObject["ttftMs"].isJsonNull)
    assertFalse(File(file.parentFile, "history.json.tmp").exists())
  }

  @Test
  fun historyRetainsNewestRunsAndReportsEvictions() {
    val file = File(temporary.root, "history.json")
    val history = DiagnosticsHistory(file, maxEvents = 2)
    for (i in 1..3) history.put("$i", mapOf("id" to "$i"))
    val saved =
      JsonParser.parseString(diagnosticJson(DiagnosticsHistory(file, maxEvents = 2).snapshot()))
        .asJsonObject
    assertEquals(1L, saved["evictedEvents"].asLong)
    assertEquals(
      listOf("2", "3"),
      saved.getAsJsonArray("events").map { it.asJsonObject["id"].asString },
    )
  }

  @Test
  fun historyBoundsDiskUsageAndRejectsAnOversizedEvent() {
    val file = File(temporary.root, "history.json")
    val history = DiagnosticsHistory(file, maxBytes = 1024)
    history.put("large", mapOf("id" to "large", "error" to "x".repeat(2048)))
    assertTrue(file.length() <= 1024)
    assertEquals(1L, history.snapshot()["evictedEvents"])
  }

  @Test
  fun corruptHistoryIsReportedAndNewRunsCanStillBeSaved() {
    val file = temporary.newFile("history.json").apply { writeText("{broken") }
    val history = DiagnosticsHistory(file)
    assertNotNull(history.storageError)
    history.put("next", mapOf("id" to "next", "status" to "SUCCESS"))
    assertEquals(
      1,
      JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("events").size(),
    )
    assertNotNull(history.storageError)
  }

  @Test
  fun storageFailureKeepsTheRunAvailableForExport() {
    val parent = temporary.newFile("not-a-directory")
    val history = DiagnosticsHistory(File(parent, "history.json"))
    history.put("run", mapOf("id" to "run"))
    assertNotNull(history.storageError)
    val snapshot = JsonParser.parseString(diagnosticJson(history.snapshot())).asJsonObject
    assertEquals(1, snapshot.getAsJsonArray("events").size())
  }

  @Test
  fun basicAuthorizationIsRedacted() {
    val output = redactDiagnostics("Authorization: Basic c2VjcmV0OnBhc3N3b3Jk")
    assertFalse(output.contains("c2VjcmV0OnBhc3N3b3Jk"))
  }

  @Test
  fun historyRedactsCredentialsBeforeWritingToDisk() {
    val file = File(temporary.root, "history.json")
    DiagnosticsHistory(file)
      .put("run", mapOf("id" to "run", "error" to "Bearer topsecret hf_ABC123"))
    assertFalse(file.readText().contains("topsecret"))
    assertFalse(file.readText().contains("hf_ABC123"))
  }

  @Test
  fun redactionPreservesJsonStructureAndMissingAndZeroAreDistinct() {
    val secret = "a secret with \"quotes\" and\nlines"
    val text =
      diagnosticJson(
        mapOf(
          "nested" to listOf(mapOf("log" to "failure: $secret")),
          "missing" to null,
          "zero" to 0,
          "infinite" to Double.POSITIVE_INFINITY,
          "nan" to Double.NaN,
        ),
        listOf(secret),
      )
    val json = JsonParser.parseString(text).asJsonObject
    assertFalse(text.contains("secret with"))
    assertTrue(json["missing"].isJsonNull)
    assertEquals(0, json["zero"].asInt)
    assertTrue(json["infinite"].isJsonNull)
    assertTrue(json["nan"].isJsonNull)
    assertTrue(json.getAsJsonArray("nested")[0].asJsonObject["log"].asString.contains("[REDACTED]"))
  }

  @Test
  fun streamCountsChunksNotTokensAndTimesFirstThinkingOutput() {
    val stream = StreamStatistics(1_000_000)
    assertFalse(stream.add(2_000_000, 0, 0))
    assertTrue(stream.add(4_000_000, 0, 8))
    assertFalse(stream.add(9_000_000, 12, 0))
    val snapshot = stream.snapshot(14_000_000)
    assertEquals(3L, snapshot["callbackCount"])
    assertEquals(2L, snapshot["nonEmptyCallbackCount"])
    assertEquals(3.0, snapshot["appTimeToFirstContentMs"])
    assertEquals(10.0, snapshot["appDecodeAfterFirstContentMs"])
    assertEquals(5.0, snapshot["meanContentCallbackGapMs"])
    assertEquals(12L, snapshot["answerCharacterCount"])
    assertEquals(8L, snapshot["thinkingCharacterCount"])
    assertEquals(false, snapshot["countsAreTokens"])
    assertFalse(snapshot.keys.any { it.contains("tokenCount", ignoreCase = true) })
  }

  @Test
  fun noOutputOrSingleCallbackDoesNotInventTimingOrTokens() {
    val stream = StreamStatistics(0)
    assertNull(stream.snapshot(10)["appTimeToFirstContentMs"])
    stream.add(10, 5, 0)
    assertNull(stream.snapshot(20)["meanContentCallbackGapMs"])
  }

  @Test
  fun frameJankUsesEachFramesDeadlineAtHighRefreshRates() {
    val frames = FrameStatistics()
    frames.add(10_000_000, 8_333_333, 2)
    frames.add(10_000_000, 16_666_667, 0)
    val result = frames.snapshot()
    assertEquals(2L, result["frameCount"])
    assertEquals(1L, result["framesOverOwnDeadline"])
    assertEquals(2L, result["droppedFrameMetricReports"])
    assertEquals(10.0, result["meanFrameMs"])
  }

  @Test
  fun boundedSamplesKeepStartAndTailButPeakIncludesDiscardedSamples() {
    val series = ResourceSeries(limit = 3)
    for (value in listOf(1, 100, 3, 4)) series.add(
      mapOf("memoryBytes" to value, "unsupported" to null)
    )
    val snapshot = JsonParser.parseString(diagnosticJson(series.snapshot())).asJsonObject
    assertEquals(4, snapshot["totalSamples"].asInt)
    assertEquals(1, snapshot["omittedRawSamples"].asInt)
    assertEquals(
      listOf(1, 3, 4),
      snapshot.getAsJsonArray("samples").map { it.asJsonObject["memoryBytes"].asInt },
    )
    assertEquals(100.0, snapshot.getAsJsonObject("peaks")["memoryBytes"].asDouble, 0.0)
    assertFalse(snapshot.getAsJsonObject("peaks").has("unsupported"))
  }

  @Test
  fun concurrentCallbacksAreCountedWithoutLosingUpdates() {
    val stream = StreamStatistics(0)
    val threads = (1..8).map { Thread { repeat(100) { stream.add(1000, 1, 0) } }.apply { start() } }
    threads.forEach { it.join() }
    assertEquals(800L, stream.snapshot(2000)["callbackCount"])
    assertEquals(800L, stream.snapshot(2000)["answerCharacterCount"])
  }
}
