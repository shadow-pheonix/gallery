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

/** Best-effort removal of credentials from diagnostic text, including URLs and log messages. */
internal fun redactDiagnostics(text: String, secrets: List<String> = emptyList()): String {
  var result = text
  for (secret in secrets.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }) {
    result = result.replace(secret, "[REDACTED]")
  }
  result = result.replace(Regex("hf_[A-Za-z0-9]+"), "[REDACTED]")
  result = result.replace(Regex("(?:github_pat_|gh[pousr]_)[A-Za-z0-9_]+"), "[REDACTED]")
  result = result.replace(Regex("(?i)(?:Bearer|Basic)\\s+[^\\s\"',;]+"), "[REDACTED]")
  return result.replace(
    Regex(
      "(?i)((?:access_token|refresh_token|id_token|api_key|authorization|client_secret)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"'&,;}]+"
    )
  ) { match ->
    "${match.groupValues[1]}[REDACTED]"
  }
}
