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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTextTest {
  @Test
  fun removesKnownCredentialsAndHuggingFaceTokens() {
    val output = redactDiagnostics(
      "download hf_ABC123 failed; custom-token; custom-token-long; refresh-value",
      listOf("custom-token", "custom-token-long", "refresh-value", "", " "),
    )
    assertEquals("download [REDACTED] failed; [REDACTED]; [REDACTED]; [REDACTED]", output)
  }

  @Test
  fun removesCredentialsInHeadersJsonAndUrlParameters() {
    val output = redactDiagnostics(
      "Authorization: Bearer secret123\n" +
        "{\"access_token\":\"access-secret\",\"refresh_token\":\"refresh-secret\"}\n" +
        "https://example.com?api_key=key-secret&model=gemma\n" +
        "client_secret=client-value; status=FAILED",
    )
    for (secret in listOf("secret123", "access-secret", "refresh-secret", "key-secret", "client-value")) {
      assertFalse("Credential remained: $secret", output.contains(secret))
    }
    assertTrue(output.contains("model=gemma"))
    assertTrue(output.contains("status=FAILED"))
  }

  @Test
  fun preservesUsefulDiagnostics() {
    val report = "Android: 16 (API 36)\nRAM bytes: total=123\ngemma: FAILED\nNo recent app logs available."
    assertEquals(report, redactDiagnostics(report))
  }
}
