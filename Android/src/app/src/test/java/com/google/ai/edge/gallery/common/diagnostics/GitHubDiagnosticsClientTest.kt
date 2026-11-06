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
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GitHubDiagnosticsClientTest {
  private val token = "github_pat_" + "a".repeat(70)
  private val connection =
    GitHubDiagnosticsConnection(DiagnosticsRepository.parse("user/private-logs"), token)
  private val filename = "edge-gallery-diagnostics-20261106-063033-545-f37bf34d.json"
  private val report =
    DiagnosticReport(filename, "{\"schemaVersion\":2,\"nativeTps\":25,\"missing\":null}")
  private val success = GitHubResponse(201, "{\"commit\":{\"sha\":\"${"a".repeat(40)}\"}}")

  @Test
  fun pushChecksPrivacyThenCreatesADatedUniqueReportAndReturnsAnImmutableLink() =
    runBlocking<Unit> {
      val methods = mutableListOf<String>()
      val client = GitHubDiagnosticsClient { method, path, credential, body ->
        methods.add(method)
        assertEquals(token, credential)
        if (method == "GET") {
          assertEquals("/repos/user/private-logs", path)
          assertNull(body)
          GitHubResponse(200, "{\"private\":true,\"archived\":false}")
        } else {
          assertEquals("PUT", method)
          assertEquals("/repos/user/private-logs/contents/diagnostics/2026/11/06/$filename", path)
          val json = JsonParser.parseString(body).asJsonObject
          assertTrue(json["message"].asString.contains(filename))
          val uploaded =
            String(Base64.getDecoder().decode(json["content"].asString), Charsets.UTF_8)
          val parsed = JsonParser.parseString(uploaded).asJsonObject
          assertEquals(25, parsed["nativeTps"].asInt)
          assertTrue(parsed["missing"].isJsonNull)
          success
        }
      }
      val result = client.push(connection, report)
      assertEquals(listOf("GET", "PUT"), methods)
      assertEquals(
        "https://github.com/user/private-logs/blob/${"a".repeat(40)}/diagnostics/2026/11/06/$filename",
        result.url,
      )
    }

  @Test
  fun publicMissingOrStringVisibilityNeverUploads() =
    runBlocking<Unit> {
      for (body in listOf("{\"private\":false}", "{}", "{\"private\":\"true\"}")) {
        var requests = 0
        val client = GitHubDiagnosticsClient { method, _, _, _ ->
          requests++
          assertEquals("GET", method)
          GitHubResponse(200, body)
        }
        expect(GitHubDiagnosticsException.Problem.PUBLIC_REPOSITORY) {
          client.push(connection, report)
        }
        assertEquals(1, requests)
      }
    }

  @Test
  fun archivedRepositoryNeverUploads() =
    runBlocking<Unit> {
      val client = GitHubDiagnosticsClient { method, _, _, _ ->
        assertEquals("GET", method)
        GitHubResponse(200, "{\"private\":true,\"archived\":true}")
      }
      expect(GitHubDiagnosticsException.Problem.ARCHIVED) { client.push(connection, report) }
    }

  @Test
  fun errorsAreTypedAndDoNotExposeResponseBodiesOrCredentials() =
    runBlocking<Unit> {
      val responses =
        listOf(
          GitHubResponse(401, token) to GitHubDiagnosticsException.Problem.AUTHENTICATION,
          GitHubResponse(403, token) to GitHubDiagnosticsException.Problem.PERMISSION,
          GitHubResponse(404, token) to GitHubDiagnosticsException.Problem.NOT_FOUND,
          GitHubResponse(403, token, mapOf("x-ratelimit-remaining" to "0")) to
            GitHubDiagnosticsException.Problem.RATE_LIMIT,
          GitHubResponse(429, token) to GitHubDiagnosticsException.Problem.RATE_LIMIT,
          GitHubResponse(409, token) to GitHubDiagnosticsException.Problem.CONFLICT,
          GitHubResponse(422, token) to GitHubDiagnosticsException.Problem.CONFLICT,
          GitHubResponse(500, token) to GitHubDiagnosticsException.Problem.SERVER,
          GitHubResponse(301, token) to GitHubDiagnosticsException.Problem.INVALID_RESPONSE,
        )
      for ((response, problem) in responses) {
        val client = GitHubDiagnosticsClient { _, _, _, _ -> response }
        expect(problem) { client.verify(connection) }
      }
    }

  @Test
  fun boundaryRedactionRemovesTheExactTokenAndOtherCredentialPatterns() =
    runBlocking<Unit> {
      val secretReport =
        DiagnosticReport(
          filename,
          diagnosticsJson.toJson(
            mapOf(
              "log" to "$token Authorization: Bearer hidden-value hf_othersecret ghp_othersecret"
            )
          ),
        )
      val client = GitHubDiagnosticsClient { method, _, _, body ->
        if (method == "GET") GitHubResponse(200, "{\"private\":true}")
        else {
          val bytes =
            Base64.getDecoder()
              .decode(JsonParser.parseString(body).asJsonObject["content"].asString)
          val uploaded = String(bytes, Charsets.UTF_8)
          for (secret in
            listOf(token, "hidden-value", "hf_othersecret", "ghp_othersecret")) assertFalse(
            uploaded.contains(secret)
          )
          assertTrue(uploaded.contains("[REDACTED]"))
          success
        }
      }
      client.push(connection, secretReport)
    }

  @Test
  fun malformedMetadataAndCommitResponseAreRejected() =
    runBlocking<Unit> {
      val metadata = GitHubDiagnosticsClient { _, _, _, _ -> GitHubResponse(200, "not JSON") }
      expect(GitHubDiagnosticsException.Problem.INVALID_RESPONSE) { metadata.verify(connection) }
      for (body in listOf("{}", "{\"commit\":7}", "{\"commit\":{\"sha\":\"not-a-sha\"}}")) {
        val client = GitHubDiagnosticsClient { method, _, _, _ ->
          if (method == "GET") GitHubResponse(200, "{\"private\":true}")
          else GitHubResponse(201, body)
        }
        expect(GitHubDiagnosticsException.Problem.INVALID_RESPONSE) {
          client.push(connection, report)
        }
      }
    }

  @Test
  fun connectionFailureDoesNotRetryTheWriteOrExposeItsExceptionText() =
    runBlocking<Unit> {
      var writes = 0
      val client = GitHubDiagnosticsClient { method, _, _, _ ->
        if (method == "GET") GitHubResponse(200, "{\"private\":true}")
        else {
          writes++
          throw IOException("request body and secret $token")
        }
      }
      expect(GitHubDiagnosticsException.Problem.NETWORK) { client.push(connection, report) }
      assertEquals(1, writes)
    }

  @Test
  fun oversizedReportIsRejectedBeforeThePutRequest() =
    runBlocking<Unit> {
      val client = GitHubDiagnosticsClient { method, _, _, _ ->
        assertEquals("GET", method)
        GitHubResponse(200, "{\"private\":true}")
      }
      expect(GitHubDiagnosticsException.Problem.REPORT_TOO_LARGE) {
        client.push(connection, DiagnosticReport(filename, " ".repeat(10 * 1024 * 1024 + 1)))
      }
    }

  @Test
  fun repositoryNamesCannotInjectUrlsPathsOrQueries() {
    for (input in
      listOf(
        "https://github.com/user/repo",
        "user/repo/extra",
        "user/..",
        "user/repo?private=true",
        "user%2Fevil/repo",
        "user/repo\r\nHost: evil",
      )) {
      try {
        DiagnosticsRepository.parse(input)
        fail("Accepted an invalid repository")
      } catch (_: IllegalArgumentException) {}
    }
    assertEquals("user/private-logs", DiagnosticsRepository.parse(" user/private-logs ").fullName)
  }

  @Test
  fun connectionDoesNotExposeItsTokenInToStringAndRejectsHeaderInjection() {
    assertFalse(connection.toString().contains(token))
    try {
      GitHubDiagnosticsConnection(connection.repository, token + "\r\nHost: evil")
      fail("Accepted header injection")
    } catch (_: IllegalArgumentException) {}
  }

  private suspend fun expect(
    problem: GitHubDiagnosticsException.Problem,
    block: suspend () -> Any?,
  ) {
    try {
      block()
      fail("Expected $problem")
    } catch (error: GitHubDiagnosticsException) {
      assertEquals(problem, error.problem)
      assertFalse(error.toString().contains(token))
    }
  }
}
