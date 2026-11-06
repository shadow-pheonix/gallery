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

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class GitHubHttpTransportTest {
  @Test
  fun transportUsesExpectedAuthHeadersUtf8BodyAndRateLimitHeaders() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    var received = ""
    var auth = ""
    var version = ""
    var contentType = ""
    server.createContext("/report") { exchange ->
      received = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
      auth = exchange.requestHeaders.getFirst("Authorization")
      version = exchange.requestHeaders.getFirst("X-GitHub-Api-Version")
      contentType = exchange.requestHeaders.getFirst("Content-Type")
      exchange.responseHeaders.add("X-RateLimit-Remaining", "0")
      val bytes = "{\"ok\":true}".toByteArray()
      exchange.sendResponseHeaders(201, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
      exchange.close()
    }
    server.start()
    try {
      val transport = GitHubHttpTransport(URI("http://127.0.0.1:${server.address.port}"))
      val body = "{\"message\":\"Unicode λ\"}"
      val response = transport.request("PUT", "/report", "fake-token", body)
      assertEquals(201, response.status)
      assertEquals(body, received)
      assertEquals("Bearer fake-token", auth)
      assertEquals("2026-03-10", version)
      assertEquals("application/json; charset=utf-8", contentType)
      assertEquals("0", response.headers["x-ratelimit-remaining"])
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun redirectsAreNeverFollowedWithAnAuthorizationHeader() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    var redirectedRequests = 0
    server.createContext("/redirect") { exchange ->
      exchange.responseHeaders.add("Location", "/unexpected")
      exchange.sendResponseHeaders(302, -1)
      exchange.close()
    }
    server.createContext("/unexpected") { exchange ->
      redirectedRequests++
      exchange.sendResponseHeaders(200, -1)
      exchange.close()
    }
    server.start()
    try {
      val response =
        GitHubHttpTransport(URI("http://127.0.0.1:${server.address.port}"))
          .request("GET", "/redirect", "fake-token", null)
      assertEquals(302, response.status)
      assertEquals(0, redirectedRequests)
    } finally {
      server.stop(0)
    }
  }
}
