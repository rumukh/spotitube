package com.example.spotitube.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal HTTP client built on [HttpURLConnection] — no third-party dependency.
 *
 * Redirects are followed manually so callers can see the final URL (needed to expand
 * `spotify.link` short links) and so http→https hops work, which the built-in follower refuses.
 */
internal object Http {

  private const val CONNECT_TIMEOUT_MS = 10_000
  private const val READ_TIMEOUT_MS = 15_000
  private const val MAX_REDIRECTS = 6
  private const val MAX_BODY_BYTES = 4 * 1024 * 1024

  data class Response(val code: Int, val finalUrl: String, val body: String) {
    val isSuccessful: Boolean
      get() = code in 200..299
  }

  fun get(url: String, headers: Map<String, String> = emptyMap()): Response = request("GET", url, headers, null)

  fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Response =
    request("POST", url, headers + ("Content-Type" to "application/json"), json.toByteArray(Charsets.UTF_8))

  /** Follows redirects without downloading bodies; returns the final URL. */
  fun resolveFinalUrl(url: String, headers: Map<String, String> = emptyMap()): String {
    var current = url
    for (hop in 0 until MAX_REDIRECTS) {
      val connection = open(current, "GET", headers)
      connection.instanceFollowRedirects = false
      try {
        val code = connection.responseCode
        if (code !in 300..399) return current
        val location = connection.getHeaderField("Location") ?: return current
        current = URL(URL(current), location).toString()
      } finally {
        connection.disconnect()
      }
    }
    return current
  }

  private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Response {
    var current = url
    var lastCode = -1
    for (hop in 0 until MAX_REDIRECTS) {
      val connection = open(current, method, headers)
      connection.instanceFollowRedirects = false
      try {
        if (body != null) {
          connection.doOutput = true
          connection.setFixedLengthStreamingMode(body.size)
          connection.outputStream.use { it.write(body) }
        }
        lastCode = connection.responseCode
        if (lastCode in 300..399) {
          val location = connection.getHeaderField("Location")
          if (location != null) {
            current = URL(URL(current), location).toString()
            continue
          }
        }
        val stream = if (lastCode in 200..299) connection.inputStream else connection.errorStream
        val text =
          stream?.let { raw ->
            val decoded =
              if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
            decoded.use { it.readBoundedText() }
          }.orEmpty()
        return Response(lastCode, current, text)
      } finally {
        connection.disconnect()
      }
    }
    throw IOException("Too many redirects for $url (last status $lastCode)")
  }

  private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.setRequestProperty("Accept-Encoding", "gzip")
    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
    return connection
  }

  private fun java.io.InputStream.readBoundedText(): String {
    val buffer = ByteArray(16 * 1024)
    val out = java.io.ByteArrayOutputStream()
    while (out.size() < MAX_BODY_BYTES) {
      val read = read(buffer)
      if (read <= 0) break
      out.write(buffer, 0, read)
    }
    return out.toString(Charsets.UTF_8.name())
  }
}
