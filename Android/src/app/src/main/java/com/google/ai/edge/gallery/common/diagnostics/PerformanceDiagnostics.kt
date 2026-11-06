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

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import com.google.ai.edge.gallery.BuildConfig
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Local only. No prompt, generated content, tools, credentials, or analytics transport. */
object PerformanceDiagnostics {
  private val worker =
    Executors.newSingleThreadScheduledExecutor { task ->
      Thread(task, "GalleryDiagnostics").apply { isDaemon = true }
    }
  private val active = ConcurrentHashMap<String, Operation>()
  private val processId = UUID.randomUUID().toString()
  @Volatile private var initialized = false
  private lateinit var history: DiagnosticsHistory
  private lateinit var probe: DiagnosticsResourceProbe
  private var sampling: ScheduledFuture<*>? = null
  private var checkpointNs = 0L
  @Volatile private var recorderError: String? = null

  @Synchronized
  fun initialize(context: Context) {
    if (initialized) return
    val app = context.applicationContext
    // The history load and all probes stay off the application/main and inference threads.
    worker.execute {
      history = DiagnosticsHistory(File(app.noBackupFilesDir, "diagnostics/performance.json"))
      probe = DiagnosticsResourceProbe(app)
    }
    (app as? Application)?.registerActivityLifecycleCallbacks(FrameObserver())
    initialized = true
  }

  fun begin(context: Context, kind: String, metadata: Map<String, Any?>): Operation {
    initialize(context)
    val operation = Operation(kind, metadata.toMap())
    active[operation.id] = operation
    worker.execute {
      guarded {
        history.put(operation.id, operation.snapshot())
        sample()
        if (sampling == null) {
          sampling = worker.scheduleAtFixedRate({ guarded { sample() } }, 1, 1, TimeUnit.SECONDS)
        }
      }
    }
    return operation
  }

  /**
   * Call from the export IO dispatcher. A barrier includes writes already queued at export time.
   */
  internal fun snapshot(): Map<String, Any?> {
    if (!initialized) return mapOf("available" to false, "reason" to "Recorder has not initialized")
    return worker
      .submit<Map<String, Any?>> {
        mapOf(
          "available" to true,
          "processSessionId" to processId,
          "history" to history.snapshot(),
          "activeOperations" to active.values.map { it.snapshot() },
          "recorderError" to recorderError,
          "samplingIntervalMs" to 1000,
          "checkpointIntervalMs" to 10000,
          "recovery" to
            "An unfinished event from a different processSessionId was interrupted; its last checkpoint may precede exit by 10 seconds. Android exit reasons are exported separately.",
          "resourceScope" to
            "Whole app process and device, not exclusive GPU/model allocation. Overlapping operations share samples.",
        )
      }
      .get(15, TimeUnit.SECONDS)
  }

  private fun sample() {
    if (active.isEmpty()) {
      sampling?.cancel(false)
      sampling = null
      return
    }
    val values = probe.sample()
    active.values.forEach { it.addResources(values) }
    val now = System.nanoTime()
    if (now - checkpointNs >= TimeUnit.SECONDS.toNanos(10)) {
      active.values.forEach { history.put(it.id, it.snapshot()) }
      checkpointNs = now
    }
  }

  private inline fun guarded(action: () -> Unit) {
    try {
      action()
    } catch (error: Exception) {
      recorderError =
        "${error.javaClass.simpleName}: ${redactDiagnostics(error.message.orEmpty()).take(512)}"
    }
  }

  class Operation
  internal constructor(private val kind: String, private val metadata: Map<String, Any?>) {
    val id: String = UUID.randomUUID().toString()
    private val startedNs = System.nanoTime()
    private val startedAt = Instant.now().toString()
    private val stream = StreamStatistics(startedNs)
    internal val resources = ResourceSeries()
    internal val frames = FrameStatistics()
    @Volatile private var endedNs: Long? = null
    private var finalValues: Map<String, Any?> = emptyMap()
    private val phases = linkedMapOf<String, Double>()

    @Synchronized
    fun markPhase(name: String) {
      if (endedNs == null) phases[name] = (System.nanoTime() - startedNs) / 1e6
    }

    internal fun addResources(values: Map<String, Number?>) {
      val now = System.nanoTime()
      resources.add(
        values +
          mapOf(
            "sampleOffsetFromOperationStartMs" to (now - startedNs) / 1e6,
            "sampleAfterOperationEndMs" to endedNs?.let { (now - it) / 1e6 },
          )
      )
    }

    /** Retain lengths, never the strings. Returns true for the first content callback. */
    fun onContent(answer: String, thinking: String?): Boolean =
      if (endedNs == null) stream.add(System.nanoTime(), answer.length, thinking?.length ?: 0)
      else false

    @Synchronized
    fun finish(status: String, values: Map<String, Any?> = emptyMap(), error: String? = null) {
      if (endedNs != null) return
      finalValues =
        values +
          mapOf(
            "status" to status,
            "endedAtUtc" to Instant.now().toString(),
            "error" to error?.let { redactDiagnostics(it).take(2048) },
          )
      endedNs = System.nanoTime()
      active.remove(id)
      worker.execute {
        guarded {
          addResources(probe.sample())
          history.put(id, snapshot())
          if (active.isEmpty()) {
            sampling?.cancel(false)
            sampling = null
          }
        }
      }
    }

    @Synchronized
    internal fun snapshot(): Map<String, Any?> {
      val end = endedNs ?: System.nanoTime()
      return mapOf(
        "id" to id,
        "kind" to kind,
        "processSessionId" to processId,
        "appVersion" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE,
        "sourceCommit" to BuildConfig.SOURCE_COMMIT,
        "startedAtUtc" to startedAt,
        "status" to "RUNNING",
        "elapsedMs" to (end - startedNs) / 1e6,
        "metadata" to metadata,
        "stream" to stream.snapshot(end),
        "resources" to resources.snapshot(),
        "uiFrames" to frames.snapshot(),
        "phaseOffsetsMs" to phases.toMap(),
      ) + finalValues
    }
  }

  /**
   * Frame deadlines follow the actual display/window timing, including high refresh rate phones.
   */
  private class FrameObserver : Application.ActivityLifecycleCallbacks {
    private var thread: HandlerThread? = null
    private val listeners = mutableMapOf<Activity, Window.OnFrameMetricsAvailableListener>()

    override fun onActivityResumed(activity: Activity) {
      try {
        val handlerThread =
          thread
            ?: HandlerThread("GalleryDiagnosticFrames").also {
              it.start()
              thread = it
            }
        val listener =
          Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
            active.values.forEach { it.frames.add(duration, deadline, dropped) }
          }
        listeners.remove(activity)?.let {
          activity.window.removeOnFrameMetricsAvailableListener(it)
        }
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
        listeners[activity] = listener
      } catch (error: RuntimeException) {
        recorderError = "Frame metrics unavailable: ${error.javaClass.simpleName}"
      }
    }

    override fun onActivityPaused(activity: Activity) {
      listeners.remove(activity)?.let {
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(it) }
      }
    }

    override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)

    override fun onActivityCreated(activity: Activity, state: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
  }
}
