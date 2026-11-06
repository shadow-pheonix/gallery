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

import com.google.ai.edge.gallery.common.metrics.InferenceBenchmark
import com.google.ai.edge.gallery.common.metrics.asDiagnosticValues
import com.google.ai.edge.gallery.proto.BenchmarkResult
import com.google.ai.edge.gallery.proto.LlmBenchmarkBasicInfo
import com.google.ai.edge.gallery.proto.LlmBenchmarkResult
import com.google.ai.edge.gallery.proto.LlmBenchmarkStats
import com.google.ai.edge.gallery.proto.ValueSeries
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class BenchmarkDiagnosticsTest {
  @Test
  fun nativePrefillSnapshotNeverIncludesUnfinalizedDecodeData() {
    val native = InferenceBenchmark(100, 32, 500f, 28f, 1200.0, 250.0)
    val first = native.asDiagnosticValues(includeDecode = false)
    assertEquals(100, first["prefillTokenCount"])
    assertEquals(500f, first["prefillTokensPerSecond"])
    assertNull(first["decodeTokenCount"])
    assertNull(first["decodeTokensPerSecond"])
    assertNull(first["nativeTimeToFirstTokenMs"])
    val final = native.asDiagnosticValues(includeDecode = true)
    assertEquals(32, final["decodeTokenCount"])
    assertEquals(28f, final["decodeTokensPerSecond"])
    assertEquals(250.0, final["nativeTimeToFirstTokenMs"])
  }

  @Test
  fun unavailableNativeCountersStayNull() {
    val snapshot = InferenceBenchmark().asDiagnosticValues(includeDecode = true)
    assertTrue(snapshot.values.all { it == null })
    assertTrue(
      JsonParser.parseString(diagnosticJson(snapshot)).asJsonObject["decodeTokenCount"].isJsonNull
    )
  }

  @Test
  fun storedBenchmarkIncludesRawRunsUnitsAndNoFabricatedEmptySeriesAverage() {
    val result =
      BenchmarkResult.newBuilder()
        .setLlmResult(
          LlmBenchmarkResult.newBuilder()
            .setBaiscInfo(
              LlmBenchmarkBasicInfo.newBuilder()
                .setModelName("test-model")
                .setAccelerator("GPU")
                .setPrefillTokens(128)
                .setDecodeTokens(32)
                .setNumberOfRuns(2)
            )
            .setStats(
              LlmBenchmarkStats.newBuilder()
                .setTimeToFirstToken(
                  ValueSeries.newBuilder().addValue(0.25).addValue(0.5).setAvg(0.375)
                )
                .setDecodeSpeed(ValueSeries.newBuilder().addValue(20.0).addValue(30.0).setAvg(25.0))
            )
        )
        .build()
    val json = JsonParser.parseString(diagnosticJson(storedBenchmarkSnapshot(result))).asJsonObject
    assertEquals(128, json["requestedPrefillTokens"].asInt)
    val ttft = json.getAsJsonObject("timeToFirstToken")
    assertEquals("seconds", ttft["unit"].asString)
    assertEquals(0.25, ttft.getAsJsonArray("values")[0].asDouble, 0.0)
    assertEquals("tokens/second", json.getAsJsonObject("decode")["unit"].asString)
    assertTrue(json.getAsJsonObject("subsequentInitializations")["mean"].isJsonNull)
    assertEquals(0, json.getAsJsonObject("subsequentInitializations")["count"].asInt)
  }
}
