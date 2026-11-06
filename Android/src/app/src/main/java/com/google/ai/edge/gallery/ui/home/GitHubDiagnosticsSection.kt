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

package com.google.ai.edge.gallery.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.diagnostics.DEFAULT_DIAGNOSTICS_REPOSITORY
import com.google.ai.edge.gallery.common.diagnostics.DiagnosticReport
import com.google.ai.edge.gallery.common.diagnostics.DiagnosticsRepository
import com.google.ai.edge.gallery.common.diagnostics.GitHubDiagnosticsClient
import com.google.ai.edge.gallery.common.diagnostics.GitHubDiagnosticsConnection
import com.google.ai.edge.gallery.common.diagnostics.GitHubDiagnosticsException
import com.google.ai.edge.gallery.common.diagnostics.GitHubDiagnosticsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Explicit, one-shot upload. No startup/background network calls or analytics for private links.
 */
@Composable
internal fun GitHubDiagnosticsSection(captureReport: suspend (List<String>) -> DiagnosticReport) {
  val context = LocalContext.current
  val appContext = context.applicationContext
  val scope = rememberCoroutineScope()
  val store = remember { GitHubDiagnosticsStore(appContext) }
  val client = remember { GitHubDiagnosticsClient() }
  var connection by remember { mutableStateOf<GitHubDiagnosticsConnection?>(null) }
  var loaded by remember { mutableStateOf(false) }
  var pushing by remember { mutableStateOf(false) }
  var showSetup by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<Int?>(null) }
  var uploadedUrl by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(store) {
    try {
      connection = store.load()
      uploadedUrl = connection?.latestUploadUrl
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      error = R.string.diagnostics_github_storage_failed
    } finally {
      loaded = true
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      stringResource(R.string.diagnostics_github_description),
      style = MaterialTheme.typography.bodySmall,
    )
    connection?.let {
      Text(
        stringResource(R.string.diagnostics_github_destination, it.repository.fullName),
        style = MaterialTheme.typography.bodySmall,
      )
    }
    OutlinedButton(
      enabled = loaded && !pushing,
      onClick = {
        val target = connection
        if (target == null) {
          showSetup = true
          return@OutlinedButton
        }
        pushing = true
        error = null
        uploadedUrl = null
        scope.launch {
          try {
            // Fail early on an invalid/public destination, before the potentially expensive
            // capture.
            client.verify(target)
            val report = captureReport(listOf(target.token))
            // push() rechecks privacy immediately before uploading.
            val upload = client.push(target, report)
            uploadedUrl = upload.url
            val updated = GitHubDiagnosticsConnection(target.repository, target.token, upload.url)
            connection = updated
            try {
              store.save(updated)
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Exception) {
              error = R.string.diagnostics_github_receipt_not_saved
            }
            Toast.makeText(appContext, R.string.diagnostics_github_uploaded, Toast.LENGTH_LONG)
              .show()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (problem: GitHubDiagnosticsException) {
            error = githubProblemText(problem)
          } catch (_: Exception) {
            error = R.string.diagnostics_github_push_failed
          } finally {
            pushing = false
          }
        }
      },
    ) {
      Text(
        stringResource(
          if (pushing) R.string.diagnostics_github_pushing else R.string.diagnostics_github_push
        )
      )
    }
    TextButton(enabled = loaded && !pushing, onClick = { showSetup = true }) {
      Text(stringResource(R.string.diagnostics_github_setup))
    }
    uploadedUrl?.let { url ->
      TextButton(onClick = { openDiagnosticsLink(context, url) }) {
        Text(stringResource(R.string.diagnostics_github_open_latest))
      }
    }
    error?.let {
      Text(
        stringResource(it),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
  if (showSetup) {
    GitHubDiagnosticsSetup(
      connection,
      store,
      client,
      onSaved = {
        connection = it
        uploadedUrl = it.latestUploadUrl
        error = null
        showSetup = false
      },
      onDisconnected = {
        connection = null
        uploadedUrl = null
        error = null
        showSetup = false
      },
      onDismiss = { showSetup = false },
    )
  }
}

@Composable
private fun GitHubDiagnosticsSetup(
  existing: GitHubDiagnosticsConnection?,
  store: GitHubDiagnosticsStore,
  client: GitHubDiagnosticsClient,
  onSaved: (GitHubDiagnosticsConnection) -> Unit,
  onDisconnected: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // Credentials intentionally use remember, never rememberSaveable or a prefilled password field.
  var repository by remember {
    mutableStateOf(existing?.repository?.fullName ?: DEFAULT_DIAGNOSTICS_REPOSITORY)
  }
  var token by remember { mutableStateOf("") }
  var saving by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<Int?>(null) }

  AlertDialog(
    onDismissRequest = {
      if (!saving) {
        token = ""
        onDismiss()
      }
    },
    title = { Text(stringResource(R.string.diagnostics_github_setup)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(stringResource(R.string.diagnostics_github_setup_instructions))
        OutlinedTextField(
          value = repository,
          onValueChange = { repository = it },
          enabled = !saving,
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.diagnostics_github_repository)) },
        )
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          enabled = !saving,
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          label = { Text(stringResource(R.string.diagnostics_github_token)) },
          supportingText = {
            Text(
              stringResource(
                if (existing == null) R.string.diagnostics_github_token_hint
                else R.string.diagnostics_github_token_keep
              )
            )
          },
        )
        TextButton(
          enabled = !saving,
          onClick = {
            openDiagnosticsLink(context, "https://github.com/settings/personal-access-tokens/new")
          },
        ) {
          Text(stringResource(R.string.diagnostics_github_create_token))
        }
        if (existing != null) {
          TextButton(
            enabled = !saving,
            onClick = {
              saving = true
              error = null
              scope.launch {
                try {
                  store.clear()
                  token = ""
                  onDisconnected()
                } catch (cancelled: CancellationException) {
                  throw cancelled
                } catch (_: Exception) {
                  error = R.string.diagnostics_github_storage_failed
                } finally {
                  saving = false
                }
              }
            },
          ) {
            Text(stringResource(R.string.diagnostics_github_disconnect))
          }
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      Button(
        enabled = !saving && repository.isNotBlank() && (token.isNotBlank() || existing != null),
        onClick = {
          val destination =
            try {
              DiagnosticsRepository.parse(repository)
            } catch (_: IllegalArgumentException) {
              error = R.string.diagnostics_github_bad_repository
              return@Button
            }
          val candidate =
            try {
              GitHubDiagnosticsConnection(
                destination,
                token.trim().ifBlank { existing?.token.orEmpty() },
              )
            } catch (_: IllegalArgumentException) {
              error = R.string.diagnostics_github_bad_token
              return@Button
            }
          saving = true
          error = null
          scope.launch {
            try {
              client.verify(candidate)
              val saved =
                GitHubDiagnosticsConnection(
                  destination,
                  candidate.token,
                  existing?.latestUploadUrl?.takeIf { existing.repository == destination },
                )
              store.save(saved)
              token = ""
              onSaved(saved)
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (problem: GitHubDiagnosticsException) {
              error = githubProblemText(problem)
            } catch (_: Exception) {
              error = R.string.diagnostics_github_storage_failed
            } finally {
              saving = false
            }
          }
        },
      ) {
        Text(
          stringResource(
            if (saving) R.string.diagnostics_github_checking else R.string.diagnostics_github_save
          )
        )
      }
    },
    dismissButton = {
      TextButton(
        enabled = !saving,
        onClick = {
          token = ""
          onDismiss()
        },
      ) {
        Text(stringResource(R.string.diagnostics_github_cancel))
      }
    },
  )
}

@StringRes
private fun githubProblemText(error: GitHubDiagnosticsException): Int =
  when (error.problem) {
    GitHubDiagnosticsException.Problem.AUTHENTICATION -> R.string.diagnostics_github_auth_failed
    GitHubDiagnosticsException.Problem.PERMISSION -> R.string.diagnostics_github_permission_failed
    GitHubDiagnosticsException.Problem.NOT_FOUND -> R.string.diagnostics_github_not_found
    GitHubDiagnosticsException.Problem.PUBLIC_REPOSITORY -> R.string.diagnostics_github_public
    GitHubDiagnosticsException.Problem.ARCHIVED -> R.string.diagnostics_github_archived
    GitHubDiagnosticsException.Problem.RATE_LIMIT -> R.string.diagnostics_github_rate_limit
    GitHubDiagnosticsException.Problem.CONFLICT -> R.string.diagnostics_github_conflict
    GitHubDiagnosticsException.Problem.NETWORK -> R.string.diagnostics_github_network
    GitHubDiagnosticsException.Problem.REPORT_TOO_LARGE -> R.string.diagnostics_github_too_large
    else -> R.string.diagnostics_github_push_failed
  }

private fun openDiagnosticsLink(context: Context, url: String) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
  } catch (_: Exception) {
    Toast.makeText(context, R.string.diagnostics_github_browser_failed, Toast.LENGTH_LONG).show()
  }
}
