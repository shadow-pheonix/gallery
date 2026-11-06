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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Authenticated encryption; the production key stays non-exportable in Android Keystore. */
internal class DiagnosticsCredentialCipher(private val key: SecretKey) {
  fun encrypt(plaintext: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.updateAAD(AAD)
    check(cipher.iv.size == 12) { "Unexpected GCM nonce size" }
    return "v1:" +
      Base64.getEncoder()
        .encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
  }

  fun decrypt(encoded: String): String {
    require(encoded.startsWith("v1:")) { "Unsupported credential format" }
    require(encoded.length <= 16 * 1024) { "Credential file is too large" }
    val bytes = Base64.getDecoder().decode(encoded.removePrefix("v1:"))
    require(bytes.size >= 12 + 16) { "Incomplete credential file" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
    cipher.updateAAD(AAD)
    return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
  }

  companion object {
    private val AAD = "com.google.aiedge.gallery.github-diagnostics.v1".toByteArray(Charsets.UTF_8)
  }
}

/** All credential IO stays off main, outside Android backup, and behind one process-wide lock. */
internal class GitHubDiagnosticsStore(context: Context) {
  private val file =
    File(context.applicationContext.noBackupFilesDir, "diagnostics/github-connection.enc")

  suspend fun load(): GitHubDiagnosticsConnection? =
    withContext(Dispatchers.IO) {
      synchronized(lock) {
        if (!file.exists() && !File("${file.path}.bak").exists()) return@synchronized null
        check(file.length() <= 16 * 1024) { "Credential file is too large" }
        val encrypted = String(AtomicFile(file).readFully(), Charsets.UTF_8)
        val key =
          key(create = false) ?: throw IOException("Saved GitHub key is unavailable; reconnect")
        val json =
          JsonParser.parseString(DiagnosticsCredentialCipher(key).decrypt(encrypted)).asJsonObject
        GitHubDiagnosticsConnection(
          repository = DiagnosticsRepository.parse(json["repository"].asString),
          token = json["token"].asString,
          latestUploadUrl = json["latestUploadUrl"]?.takeUnless { it.isJsonNull }?.asString,
        )
      }
    }

  suspend fun save(connection: GitHubDiagnosticsConnection) =
    withContext(Dispatchers.IO) {
      synchronized(lock) {
        val secretKey = key(create = true) ?: throw IOException("Android Keystore is unavailable")
        // Credential serialization must not use diagnosticJson(), which intentionally redacts
        // tokens.
        val plaintext =
          diagnosticsJson.toJson(
            mapOf(
              "repository" to connection.repository.fullName,
              "token" to connection.token,
              "latestUploadUrl" to connection.latestUploadUrl,
            )
          )
        val encrypted = DiagnosticsCredentialCipher(secretKey).encrypt(plaintext)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
          output.write(encrypted.toByteArray(Charsets.UTF_8))
          atomic.finishWrite(output)
        } catch (error: Exception) {
          atomic.failWrite(output)
          throw error
        }
      }
    }

  suspend fun clear() =
    withContext(Dispatchers.IO) {
      synchronized(lock) {
        AtomicFile(file).delete()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
      }
    }

  private fun key(create: Boolean): SecretKey? {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null || !create) return existing
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      .apply {
        init(
          KeyGenParameterSpec.Builder(
              KEY_ALIAS,
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        )
      }
      .generateKey()
  }

  companion object {
    private const val KEY_ALIAS = "edge_gallery_github_diagnostics_v1"
    private val lock = Any()
  }
}
