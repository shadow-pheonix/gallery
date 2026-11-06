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
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Debug
import android.os.StatFs
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.BenchmarkResult
import com.google.ai.edge.gallery.proto.ValueSeries
import java.io.File

/** Only whitelisted configuration values; never serialize Model (it can contain credentials). */
internal fun modelConfiguration(model: Model): Map<String, Any?> =
  mapOf(
    "selectedBackend" to model.currentAccelerator?.name,
    "selectedVisionBackend" to model.currentVisionAccelerator.name,
    "topK" to model.getIntConfigValue(ConfigKeys.TOPK, DEFAULT_TOPK),
    "topP" to model.getFloatConfigValue(ConfigKeys.TOPP, DEFAULT_TOPP),
    "temperature" to model.getFloatConfigValue(ConfigKeys.TEMPERATURE, DEFAULT_TEMPERATURE),
    "maxContextTokens" to model.getIntConfigValue(ConfigKeys.MAX_TOKENS, DEFAULT_MAX_TOKEN),
    "thinkingSetting" to model.getBooleanConfigValue(ConfigKeys.ENABLE_THINKING, false),
    "speculativeDecodingSetting" to
      model.getBooleanConfigValue(ConfigKeys.ENABLE_SPECULATIVE_DECODING, false),
  )

internal fun modelDiagnosticSnapshot(context: Context, model: Model): Map<String, Any?> {
  val file = File(model.getPath(context))
  return mapOf(
    "name" to model.name,
    "runtime" to model.backendSpec.runtimeType.name,
    "fileName" to file.name,
    "fileExists" to file.exists(),
    "fileBytes" to file.takeIf { it.exists() }?.length(),
    "fileModifiedAtMs" to file.takeIf { it.exists() }?.lastModified(),
    "initialized" to (model.instance != null),
    "initializationDurationMs" to model.initDuration?.inWholeMilliseconds,
    "currentSettingsAtExport" to modelConfiguration(model),
    "configurationNote" to
      "Current settings can differ from an existing engine. Use operation runtimeAppliedAtInitialization for applied backend/configuration. Filename is an identity hint, not verified quantization or KV-cache format.",
  )
}

internal fun storedBenchmarkSnapshot(result: BenchmarkResult): Map<String, Any?> {
  if (!result.hasLlmResult())
    return mapOf("available" to false, "reason" to "Unsupported benchmark type")
  val llm = result.llmResult
  val basic = llm.baiscInfo
  val stats = llm.stats
  fun series(values: ValueSeries, unit: String) =
    mapOf(
      "unit" to unit,
      "values" to values.valueList,
      "count" to values.valueCount,
      "min" to values.min.takeIf { values.valueCount > 0 },
      "max" to values.max.takeIf { values.valueCount > 0 },
      "mean" to values.avg.takeIf { values.valueCount > 0 },
      "median" to values.medium.takeIf { values.valueCount > 0 },
    )
  return mapOf(
    "startedAtEpochMs" to basic.startMs,
    "endedAtEpochMs" to basic.endMs,
    "modelName" to basic.modelName,
    "backend" to basic.accelerator,
    "requestedPrefillTokens" to basic.prefillTokens,
    "requestedDecodeTokens" to basic.decodeTokens,
    "requestedRuns" to basic.numberOfRuns,
    "appVersion" to basic.appVersion,
    "prefill" to series(stats.prefillSpeed, "tokens/second"),
    "decode" to series(stats.decodeSpeed, "tokens/second"),
    "timeToFirstToken" to series(stats.timeToFirstToken, "seconds"),
    "firstInitializationMs" to stats.firstInitTimeMs,
    "subsequentInitializations" to series(stats.nonFirstInitTimeMs, "milliseconds"),
    "historicalLimitations" to
      "Old results do not contain per-run resources or actual token counts. New benchmark_run events contain those. First run uses a fresh app cache when creation succeeds; later runs reuse it. OS page caches are not flushed.",
  )
}

internal fun deviceDiagnosticSnapshot(context: Context): Map<String, Any?> =
  mapOf(
    "manufacturer" to Build.MANUFACTURER,
    "model" to Build.MODEL,
    "socManufacturer" to Build.SOC_MANUFACTURER,
    "socModel" to Build.SOC_MODEL,
    "hardware" to Build.HARDWARE,
    "board" to Build.BOARD,
    "androidVersion" to Build.VERSION.RELEASE,
    "apiLevel" to Build.VERSION.SDK_INT,
    "securityPatch" to Build.VERSION.SECURITY_PATCH,
    "supportedAbis" to Build.SUPPORTED_ABIS.toList(),
    "availableProcessorCount" to Runtime.getRuntime().availableProcessors(),
    "storage" to
      diagnosticSection {
        val storage = StatFs(context.filesDir.absolutePath)
        mapOf("totalBytes" to storage.totalBytes, "availableBytes" to storage.availableBytes)
      },
    "resourcesAtExport" to diagnosticSection { DiagnosticsResourceProbe(context).sample() },
    "artRuntimeStats" to diagnosticSection { Debug.getRuntimeStats() },
    "graphicsIdentity" to diagnosticSection { graphicsIdentity() },
    "previousProcessExits" to
      diagnosticSection {
        context
          .getSystemService(ActivityManager::class.java)
          .getHistoricalProcessExitReasons(context.packageName, 0, 8)
          .map {
            mapOf(
              "timestampEpochMs" to it.timestamp,
              "reasonCode" to it.reason,
              "status" to it.status,
              "importance" to it.importance,
              "lastPssBytes" to it.pss * 1024,
              "lastRssBytes" to it.rss * 1024,
              "description" to it.description?.take(1024),
            )
          }
      },
  )

internal inline fun diagnosticSection(action: () -> Any?): Map<String, Any?> =
  try {
    mapOf("available" to true, "data" to action())
  } catch (error: Exception) {
    mapOf(
      "available" to false,
      "reason" to
        "${error.javaClass.simpleName}: ${redactDiagnostics(error.message.orEmpty()).take(512)}",
    )
  }

/** Export-only offscreen query. This is device GL identity, not proof of the inference delegate. */
private fun graphicsIdentity(): Map<String, Any?> {
  val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
  check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
  var eglContext = EGL14.EGL_NO_CONTEXT
  var surface = EGL14.EGL_NO_SURFACE
  try {
    check(EGL14.eglInitialize(display, null, 0, null, 0)) { "EGL initialization failed" }
    val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
    val count = IntArray(1)
    check(
      EGL14.eglChooseConfig(
        display,
        intArrayOf(
          EGL14.EGL_SURFACE_TYPE,
          EGL14.EGL_PBUFFER_BIT,
          EGL14.EGL_RENDERABLE_TYPE,
          EGL14.EGL_OPENGL_ES2_BIT,
          EGL14.EGL_NONE,
        ),
        0,
        configs,
        0,
        1,
        count,
        0,
      ) && count[0] > 0
    ) {
      "No offscreen EGL configuration"
    }
    eglContext =
      EGL14.eglCreateContext(
        display,
        configs[0],
        EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
        0,
      )
    check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
    surface =
      EGL14.eglCreatePbufferSurface(
        display,
        configs[0],
        intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
        0,
      )
    check(
      surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, eglContext)
    ) {
      "Offscreen EGL surface unavailable"
    }
    return mapOf(
      "vendor" to GLES20.glGetString(GLES20.GL_VENDOR),
      "renderer" to GLES20.glGetString(GLES20.GL_RENDERER),
      "version" to GLES20.glGetString(GLES20.GL_VERSION),
      "scope" to "Offscreen OpenGL ES identity; inference may use a different GPU API",
    )
  } finally {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
    EGL14.eglTerminate(display)
    EGL14.eglReleaseThread()
  }
}

internal fun appDiagnosticSnapshot(): Map<String, Any?> =
  mapOf(
    "applicationId" to BuildConfig.APPLICATION_ID,
    "versionName" to BuildConfig.VERSION_NAME,
    "versionCode" to BuildConfig.VERSION_CODE,
    "buildType" to BuildConfig.BUILD_TYPE,
    "sourceCommit" to BuildConfig.SOURCE_COMMIT,
    "liteRtLmVersion" to BuildConfig.LITERTLM_VERSION,
  )
