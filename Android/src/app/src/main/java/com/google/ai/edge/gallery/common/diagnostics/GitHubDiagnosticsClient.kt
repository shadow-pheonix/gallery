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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val DEFAULT_DIAGNOSTICS_REPOSITORY = "shadow-pheonix/gallery-diagnostics"

internal data class DiagnosticsRepository(val owner: String, val name: String) {
  val fullName: String
    get() = "$owner/$name"

  companion object {
    fun parse(value: String): DiagnosticsRepository {
      val parts = value.trim().split('/')
      require(
        parts.size == 2 &&
          parts[0].matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) &&
          parts[1].matches(Regex("[A-Za-z0-9_.-]{1,100}")) &&
          parts[1] !in setOf(".", "..")
      ) {
        "Use a repository in owner/name format"
      }
      return DiagnosticsRepository(parts[0], parts[1])
    }
  }
}

/** Deliberately no generated toString/copy/component functions that could expose a credential. */
internal class GitHubDiagnosticsConnection(
  val repository: DiagnosticsRepository,
  val token: String,
  val latestUploadUrl: String? = null,
) {
  init {
    require(token.matches(Regex("[A-Za-z0-9_]{20,255}"))) { "Enter a valid GitHub token" }
  }

  override fun toString(): String =
    "GitHubDiagnosticsConnection(${repository.fullName}, token=[REDACTED])"
}

internal data class GitHubDiagnosticUpload(val url: String, val path: String)

internal class GitHubDiagnosticsException(val problem: Problem) : IOException(problem.name) {
  enum class Problem {
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    PUBLIC_REPOSITORY,
    ARCHIVED,
    RATE_LIMIT,
    CONFLICT,
    NETWORK,
    SERVER,
    INVALID_RESPONSE,
    REPORT_TOO_LARGE,
  }
}

internal data class GitHubResponse(
  val status: Int,
  val body: String,
  val headers: Map<String, String> = emptyMap(),
)

internal fun interface GitHubTransport {
  fun request(method: String, path: String, token: String, body: String?): GitHubResponse
}

/** Fixed GitHub HTTPS origin in production; no redirects can carry the token to another host. */
internal class GitHubHttpTransport(private val origin: URI = URI("https://api.github.com")) :
  GitHubTransport {
  override fun request(method: String, path: String, token: String, body: String?): GitHubResponse {
    val connection = origin.resolve(path).toURL().openConnection() as HttpURLConnection
    try {
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 15_000
      connection.readTimeout = 20_000
      connection.requestMethod = method
      connection.setRequestProperty("Authorization", "Bearer $token")
      connection.setRequestProperty("Accept", "application/vnd.github+json")
      connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
      connection.setRequestProperty("User-Agent", "AI-Edge-Gallery-Diagnostics")
      connection.setRequestProperty("Cache-Control", "no-cache")
      connection.useCaches = false
      if (body != null) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
      }
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      val response =
        stream
          ?.bufferedReader(StandardCharsets.UTF_8)
          ?.use { reader ->
            // Repository metadata and commit responses are small. Never retain a large error body.
            val buffer = CharArray(128 * 1024)
            var size = 0
            while (size < buffer.size) {
              val count = reader.read(buffer, size, buffer.size - size)
              if (count < 0) break
              size += count
            }
            String(buffer, 0, size)
          }
          .orEmpty()
      val headers =
        listOf("X-RateLimit-Remaining", "Retry-After")
          .mapNotNull { key -> connection.getHeaderField(key)?.let { key.lowercase() to it } }
          .toMap()
      return GitHubResponse(status, response, headers)
    } finally {
      connection.disconnect()
    }
  }
}

internal class GitHubDiagnosticsClient(
  private val transport: GitHubTransport = GitHubHttpTransport()
) {
  suspend fun verify(connection: GitHubDiagnosticsConnection) =
    withContext(Dispatchers.IO) { verifyPrivateRepository(connection) }

  suspend fun push(
    connection: GitHubDiagnosticsConnection,
    report: DiagnosticReport,
  ): GitHubDiagnosticUpload =
    withContext(Dispatchers.IO) {
      currentCoroutineContext().ensureActive()
      verifyPrivateRepository(connection)
      currentCoroutineContext().ensureActive()
      val filename = report.filename
      require(
        filename.matches(Regex("edge-gallery-diagnostics-\\d{8}-\\d{6}-\\d{3}-[a-f0-9]{8}\\.json"))
      )
      val day = filename.removePrefix("edge-gallery-diagnostics-").take(8)
      val path =
        "diagnostics/${day.take(4)}/${day.substring(4, 6)}/${day.substring(6, 8)}/$filename"
      // Redact again at the transport boundary, including this exact credential, and verify JSON.
      if (report.json.length > 10 * 1024 * 1024)
        fail(GitHubDiagnosticsException.Problem.REPORT_TOO_LARGE)
      val redacted = diagnosticJson(JsonParser.parseString(report.json), listOf(connection.token))
      val bytes = redacted.toByteArray(StandardCharsets.UTF_8)
      if (bytes.size > 10 * 1024 * 1024) fail(GitHubDiagnosticsException.Problem.REPORT_TOO_LARGE)
      val body =
        diagnosticJson(
          mapOf(
            "message" to "Add Edge Gallery diagnostics $filename",
            "content" to Base64.getEncoder().encodeToString(bytes),
          )
        )
      currentCoroutineContext().ensureActive()
      val response =
        request(
          "PUT",
          "/repos/${connection.repository.fullName}/contents/$path",
          connection.token,
          body,
        )
      checkStatus(response, expected = 201)
      val result = objectResponse(response)
      val sha =
        result
          .get("commit")
          ?.takeIf { it.isJsonObject }
          ?.asJsonObject
          ?.get("sha")
          ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
          ?.asString
          ?.takeIf { it.matches(Regex("[a-fA-F0-9]{40,64}")) }
          ?: fail(GitHubDiagnosticsException.Problem.INVALID_RESPONSE)
      GitHubDiagnosticUpload(
        "https://github.com/${connection.repository.fullName}/blob/$sha/$path",
        path,
      )
    }

  private fun verifyPrivateRepository(connection: GitHubDiagnosticsConnection) {
    val response =
      request("GET", "/repos/${connection.repository.fullName}", connection.token, null)
    checkStatus(response, expected = 200)
    val repo = objectResponse(response)
    // Missing/malformed visibility is rejected. Do not infer private from a successful GET.
    if (
      repo
        .get("private")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean != true
    )
      fail(GitHubDiagnosticsException.Problem.PUBLIC_REPOSITORY)
    if (repo.get("archived")?.asBoolean == true) fail(GitHubDiagnosticsException.Problem.ARCHIVED)
  }

  private fun request(method: String, path: String, token: String, body: String?): GitHubResponse =
    try {
      transport.request(method, path, token, body)
    } catch (error: GitHubDiagnosticsException) {
      throw error
    } catch (_: IOException) {
      fail(GitHubDiagnosticsException.Problem.NETWORK)
    }

  private fun objectResponse(response: GitHubResponse): JsonObject =
    try {
      JsonParser.parseString(response.body).asJsonObject
    } catch (_: Exception) {
      fail(GitHubDiagnosticsException.Problem.INVALID_RESPONSE)
    }

  private fun checkStatus(response: GitHubResponse, expected: Int) {
    if (response.status == expected) return
    val problem =
      when {
        response.status == 401 -> GitHubDiagnosticsException.Problem.AUTHENTICATION
        response.status == 429 ||
          (response.status == 403 &&
            (response.headers["x-ratelimit-remaining"] == "0" ||
              response.headers.containsKey("retry-after"))) ->
          GitHubDiagnosticsException.Problem.RATE_LIMIT
        response.status == 403 -> GitHubDiagnosticsException.Problem.PERMISSION
        response.status == 404 -> GitHubDiagnosticsException.Problem.NOT_FOUND
        response.status == 409 || response.status == 422 ->
          GitHubDiagnosticsException.Problem.CONFLICT
        response.status in 500..599 -> GitHubDiagnosticsException.Problem.SERVER
        else -> GitHubDiagnosticsException.Problem.INVALID_RESPONSE
      }
    fail(problem)
  }

  private fun fail(problem: GitHubDiagnosticsException.Problem): Nothing =
    throw GitHubDiagnosticsException(problem)
}
