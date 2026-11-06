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

package com.google.ai.edge.gallery.common.metrics

import com.google.ai.edge.gallery.common.diagnostics.DiagnosticOperation
import com.google.ai.edge.gallery.common.diagnostics.diagnosticJson
import com.google.ai.edge.gallery.data.Model
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class LocalDiagnosticsMetricsTrackerTest {
  private class Capture(val metadata: Map<String, Any?>) : DiagnosticOperation {
    var status: String? = null
    var values: Map<String, Any?> = emptyMap()
    var finishes = 0
    private var first = true

    override fun markPhase(name: String) {}

    override fun onContent(answer: String, thinking: String?): Boolean {
      if (answer.isEmpty() && thinking.isNullOrEmpty()) return false
      return first.also { first = false }
    }

    override fun finish(status: String, values: Map<String, Any?>, error: String?) {
      this.status = status
      this.values = values
      finishes++
    }
  }

  private class Session : ConversationSession {
    override var isAlive = true
    var benchmarkCalls = 0
    var tokenCalls = 0
    var throwOnQuery = false
    var nativeBenchmark = InferenceBenchmark(128, 24, 500f, 30f, nativeTimeToFirstTokenMs = 280.0)

    override fun getTokenCount(): Int {
      tokenCalls++
      check(!throwOnQuery) { "Native executor locked" }
      return 10
    }

    override fun getBenchmark(): InferenceBenchmark {
      benchmarkCalls++
      check(!throwOnQuery) { "Native executor locked" }
      return nativeBenchmark
    }
  }

  private class Fixture {
    val captures = mutableListOf<Capture>()
    val session = Session()
    val tracker =
      LocalDiagnosticsMetricsTracker(
        Model(name = "test-model"),
        "test-task",
        mapOf("engineId" to "engine"),
        recordInitialization = false,
      ) { _, metadata ->
        Capture(metadata).also { captures.add(it) }
      }
  }

  @Test
  fun successKeepsOriginalPrefillWhenCompletionBenchmarkHasBeenOverwritten() {
    val f = Fixture()
    f.tracker.startTurn(f.session)
    f.tracker.onNewToken("first chunk", null)
    f.session.nativeBenchmark =
      InferenceBenchmark(2, 24, 9000f, 30f, nativeTimeToFirstTokenMs = 280.0)
    f.tracker.onNewToken("next chunk", null)
    f.tracker.endTurn(InferenceStatus.Code.SUCCESS, null)
    val capture = f.captures.single()
    val json = JsonParser.parseString(diagnosticJson(capture.values)).asJsonObject
    assertEquals("SUCCESS", capture.status)
    assertEquals(
      128,
      json.getAsJsonObject("nativePrefillAtFirstContent")["prefillTokenCount"].asInt,
    )
    assertEquals(2, json.getAsJsonObject("nativeAtCompletion")["prefillTokenCount"].asInt)
    assertEquals(24, json.getAsJsonObject("nativeAtCompletion")["decodeTokenCount"].asInt)
    assertEquals(2, f.session.benchmarkCalls)
    assertEquals(2, f.session.tokenCalls)
  }

  @Test
  fun failureDoesNotQueryLockedNativeStateOrInventFinalCounters() {
    val f = Fixture()
    f.tracker.startTurn(f.session)
    f.session.throwOnQuery = true
    f.tracker.endTurn(InferenceStatus.Code.ERROR, "native failure")
    assertEquals("ERROR", f.captures.single().status)
    assertEquals(0, f.session.benchmarkCalls)
    assertEquals(1, f.session.tokenCalls)
    assertNull(f.captures.single().values["nativeAtCompletion"])
  }

  @Test
  fun cancelBeforeOutputSkipsNativeQueriesEvenAfterAnEmptyCallback() {
    val f = Fixture()
    f.tracker.startTurn(f.session)
    f.tracker.onNewToken("", null)
    f.session.throwOnQuery = true
    f.tracker.cancelTurn(CancellationReason.USER_CANCELLED, null)
    assertEquals("CANCELLED", f.captures.single().status)
    assertEquals(0, f.session.benchmarkCalls)
    assertEquals(1, f.session.tokenCalls)
  }

  @Test
  fun cancelAfterOutputCanCaptureFinalizedPartialNativeResults() {
    val f = Fixture()
    f.tracker.startTurn(f.session)
    f.tracker.onNewToken("", "reasoning")
    f.tracker.cancelTurn(CancellationReason.USER_CANCELLED, null)
    assertEquals("CANCELLED", f.captures.single().status)
    assertNotNull(f.captures.single().values["nativeAtCompletion"])
    assertEquals(2, f.session.benchmarkCalls)
  }

  @Test
  fun resetRetainsCancellationAndStartsANewSessionWithoutReadingClosedConversation() {
    val f = Fixture()
    f.tracker.startTurn(f.session)
    f.session.throwOnQuery = true
    f.tracker.resetSession()
    assertEquals("CANCELLED", f.captures[0].status)
    assertEquals("CONVERSATION_RESET", f.captures[0].values["reason"])
    assertEquals(0, f.session.benchmarkCalls)
    f.session.throwOnQuery = false
    f.tracker.startTurn(f.session)
    assertNotEquals(f.captures[0].metadata["sessionId"], f.captures[1].metadata["sessionId"])
    assertEquals(0, f.captures[1].metadata["turnIndex"])
  }

  @Test
  fun duplicateCompletionAndCloseFinalizeEachTurnOnlyOnce() {
    val f = Fixture()
    f.tracker.startTurn(f.session)
    f.tracker.endTurn(InferenceStatus.Code.SUCCESS, null)
    f.tracker.endTurn(InferenceStatus.Code.ERROR, "late duplicate")
    f.tracker.close()
    assertEquals(1, f.captures.single().finishes)
    assertEquals("SUCCESS", f.captures.single().status)
  }

  @Test
  fun appliedConfigurationIsCapturedSeparatelyFromLaterSettings() {
    val f = Fixture()
    val applied = mutableMapOf<String, Any?>("topK" to 7, "toolProviderCount" to 3)
    f.tracker.updateConversationDiagnostics(applied)
    applied["topK"] = 99
    f.tracker.startTurn(f.session)
    val json = JsonParser.parseString(diagnosticJson(f.captures.single().metadata)).asJsonObject
    assertEquals(7, json.getAsJsonObject("conversationConfigurationApplied")["topK"].asInt)
    assertEquals(
      3,
      json.getAsJsonObject("conversationConfigurationApplied")["toolProviderCount"].asInt,
    )
  }

  @Test
  fun synchronousRequestGetsNativeResultsWithoutInventingStreamingTtft() {
    val f = Fixture()
    f.tracker.updateRequestDiagnostics(mapOf("mode" to "synchronous", "inputCharacterCount" to 25))
    f.tracker.startTurn(f.session)
    f.tracker.endTurn(InferenceStatus.Code.SUCCESS, null)
    assertNull(f.captures.single().values["nativePrefillAtFirstContent"])
    assertNotNull(f.captures.single().values["nativeAtCompletion"])
    assertEquals(1, f.session.benchmarkCalls)
  }
}
