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

import android.content.Context
import com.google.ai.edge.gallery.common.diagnostics.PerformanceDiagnostics
import com.google.ai.edge.gallery.common.diagnostics.modelConfiguration
import com.google.ai.edge.gallery.data.Model
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Local recording without the legacy MetricsLogger's Firebase/Logcat transport. */
internal class LocalDiagnosticsMetricsTracker(
  private val context: Context,
  private val model: Model,
  private val taskId: String,
  private val runtime: Map<String, Any?>,
  recordInitialization: Boolean,
) : MetricsTracker {
  override val liveMetrics: StateFlow<InferenceMetrics?> = MutableStateFlow(null)
  private val engineId = runtime["engineId"] ?: UUID.randomUUID().toString()
  private var sessionId = UUID.randomUUID().toString()
  private var turnIndex = 0
  private var operation: PerformanceDiagnostics.Operation? = null
  private var session: ConversationSession? = null
  private var firstBenchmark: InferenceBenchmark? = null
  private var contextBefore: Int? = null
  private var hasContent = false
  private var request: Map<String, Any?> = emptyMap()
  private var conversationConfiguration = modelConfiguration(model)
  private var initialization =
    if (recordInitialization)
      PerformanceDiagnostics.begin(context, "model_initialization", metadata())
    else null

  private fun metadata(): Map<String, Any?> =
    mapOf(
      "modelName" to model.name,
      "taskId" to taskId,
      "engineId" to engineId,
      "sessionId" to sessionId,
      "runtimeAppliedAtInitialization" to runtime,
      "conversationConfigurationApplied" to conversationConfiguration,
    )

  @Synchronized
  override fun onModelInitialized() {
    val status = model.initStatusFlow.value
    initialization?.finish(
      if (status is Model.InitializationStatus.Failed) "ERROR" else "SUCCESS",
      mapOf("modelInitializationDurationMs" to model.initDuration?.inWholeMilliseconds),
      (status as? Model.InitializationStatus.Failed)?.error?.message,
    )
    initialization = null
  }

  @Synchronized
  override fun updateRequestDiagnostics(values: Map<String, Any?>) {
    request = values.toMap()
  }

  @Synchronized
  override fun markPhase(name: String) {
    (operation ?: initialization)?.markPhase(name)
  }

  @Synchronized
  override fun startTurn(session: ConversationSession) {
    if (operation != null || !session.isAlive) return
    operation =
      PerformanceDiagnostics.begin(
        context,
        "inference",
        metadata() + mapOf("turnIndex" to turnIndex++, "request" to request),
      )
    this.session = session
    contextBefore = runCatching { session.getTokenCount() }.getOrNull()
    firstBenchmark = null
    hasContent = false
  }

  @Synchronized
  override fun onNewToken(tokenText: String, thinkingText: String?) {
    if (operation?.onContent(tokenText, thinkingText) == true) {
      hasContent = true
      operation?.markPhase("firstContent")
      // Capture prefill now: final channel stripping may overwrite its native counters.
      firstBenchmark = runCatching { session?.getBenchmark() }.getOrNull()
    }
  }

  @Synchronized
  override fun endTurn(statusCode: InferenceStatus.Code, errorMessage: String?): InferenceMetrics? {
    val capture = operation ?: return null
    // Native error callbacks can still hold the executor mutex. Never query it on errors, or a
    // cancellation before output. Completion callbacks and cancellation after output are safe.
    val safeToQuery =
      statusCode == InferenceStatus.Code.SUCCESS ||
        (statusCode == InferenceStatus.Code.CANCELLED && hasContent)
    val finalBenchmark =
      if (safeToQuery) runCatching { session?.getBenchmark() }.getOrNull() else null
    val contextAfter =
      if (safeToQuery) runCatching { session?.getTokenCount() }.getOrNull() else null
    capture.finish(
      statusCode.name,
      mapOf(
        "nativePrefillAtFirstContent" to firstBenchmark?.asDiagnosticValues(includeDecode = false),
        "nativeAtCompletion" to finalBenchmark?.asDiagnosticValues(includeDecode = true),
        "contextTokensBefore" to contextBefore,
        "contextTokensAfter" to contextAfter,
        "nativeMetricsAvailability" to
          when {
            !safeToQuery ->
              "Not queried: failed or cancelled before output; avoids native lock contention/stale data"
            finalBenchmark == null ->
              "Unavailable: runtime returned no benchmark (or querying failed)"
            else ->
              "Available fields reported by LiteRT-LM; null means not exposed or not finalized"
          },
        "prefillCaveat" to
          "Prefer nativePrefillAtFirstContent; completion prefill may reflect channel stripping. Token counts cover runtime-processed text/media/history, not callback chunks.",
      ),
      errorMessage,
    )
    operation = null
    session = null
    return null
  }

  override fun cancelTurn(reason: CancellationReason, customMessage: String?): InferenceMetrics? =
    endTurn(InferenceStatus.Code.CANCELLED, customMessage ?: reason.name)

  @Synchronized
  override fun resetSession() {
    // The old conversation may already be closed: finish without querying native state.
    operation?.finish("CANCELLED", mapOf("reason" to "CONVERSATION_RESET"))
    operation = null
    session = null
    sessionId = UUID.randomUUID().toString()
    turnIndex = 0
    conversationConfiguration = modelConfiguration(model)
  }

  @Synchronized
  override fun close() {
    operation?.finish("CANCELLED", mapOf("reason" to "MODEL_UNLOADED"))
    initialization?.finish("CANCELLED", mapOf("reason" to "INITIALIZATION_INTERRUPTED"))
    operation = null
    initialization = null
    session = null
  }
}

internal fun InferenceBenchmark.asDiagnosticValues(includeDecode: Boolean): Map<String, Any?> =
  mapOf(
    "prefillTokenCount" to prefillTokenCount,
    "prefillTokensPerSecond" to prefillSpeedTps,
    "decodeTokenCount" to if (includeDecode) decodeTokenCount else null,
    "decodeTokensPerSecond" to if (includeDecode) decodeSpeedTps else null,
    "initTimeMs" to initTimeMs,
    "nativeTimeToFirstTokenMs" to if (includeDecode) nativeTimeToFirstTokenMs else null,
  )
