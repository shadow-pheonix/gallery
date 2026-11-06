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

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsCredentialCipherTest {
  private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  @Test
  fun ciphertextContainsNoPlaintextAndDecryptsAcrossNewCipherInstances() {
    val key = newKey()
    val cipher = DiagnosticsCredentialCipher(key)
    val plaintext =
      "{\"token\":\"github_pat_fake_secret_for_test_only\",\"repository\":\"user/private-logs\"}"
    val encrypted = cipher.encrypt(plaintext)
    assertFalse(encrypted.contains("github_pat_fake_secret"))
    assertFalse(encrypted.contains("user/private-logs"))
    assertEquals(plaintext, DiagnosticsCredentialCipher(key).decrypt(encrypted))
  }

  @Test
  fun everyEncryptionUsesADifferentNonce() {
    val cipher = DiagnosticsCredentialCipher(newKey())
    assertNotEquals(cipher.encrypt("same credential"), cipher.encrypt("same credential"))
  }

  @Test
  fun tamperedOrWrongKeyCiphertextIsRejected() {
    val key = newKey()
    val encrypted = DiagnosticsCredentialCipher(key).encrypt("secret")
    try {
      DiagnosticsCredentialCipher(newKey()).decrypt(encrypted)
      fail("Accepted wrong key")
    } catch (_: AEADBadTagException) {}
    val bytes = java.util.Base64.getDecoder().decode(encrypted.removePrefix("v1:"))
    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
    try {
      DiagnosticsCredentialCipher(key)
        .decrypt("v1:" + java.util.Base64.getEncoder().encodeToString(bytes))
      fail("Accepted tampering")
    } catch (_: AEADBadTagException) {}
  }

  @Test
  fun invalidOrOversizedCredentialFormatIsRejected() {
    val cipher = DiagnosticsCredentialCipher(newKey())
    for (value in listOf("v0:garbage", "v1:AAAA", "v1:" + "A".repeat(16 * 1024))) {
      try {
        cipher.decrypt(value)
        fail("Accepted invalid credential format")
      } catch (_: IllegalArgumentException) {}
    }
  }
}
