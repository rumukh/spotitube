package com.example.spotitube.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
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

  /**
   * Registrable suffixes a request is allowed to reach.
   *
   * Short links redirect somewhere we do not control, so without this a crafted `spotify.link`
   * could aim our client at an arbitrary host. `null` means "no restriction".
   */
  class HostAllowList(private vararg val suffixes: String) {
    fun allows(host: String): Boolean {
      val h = host.lowercase()
      return suffixes.any { h == it || h.endsWith(".$it") }
    }

    override fun toString(): String = suffixes.joinToString(",")
  }

  data class Response(val code: Int, val finalUrl: String, val body: String) {
    val isSuccessful: Boolean
      get() = code in 200..299
  }

  fun get(url: String, headers: Map<String, String> = emptyMap(), allow: HostAllowList? = null): Response =
    request("GET", url, headers, null, allow)

  fun postJson(
    url: String,
    json: String,
    headers: Map<String, String> = emptyMap(),
    allow: HostAllowList? = null,
  ): Response =
    request(
      "POST",
      url,
      headers + ("Content-Type" to "application/json"),
      json.toByteArray(Charsets.UTF_8),
      allow,
    )

  /** Follows redirects without downloading bodies; returns the final URL. */
  fun resolveFinalUrl(
    url: String,
    headers: Map<String, String> = emptyMap(),
    allow: HostAllowList? = null,
    maxRedirects: Int = MAX_REDIRECTS,
  ): String {
    var current = checkHost(url, allow)
    for (hop in 0 until maxRedirects) {
      val connection = open(current, "GET", headers)
      connection.instanceFollowRedirects = false
      try {
        val code = connection.responseCode
        if (code !in 300..399) return current
        val location = connection.getHeaderField("Location") ?: return current
        current = nextRedirectUrl(current, location, allow)
      } finally {
        connection.disconnect()
      }
    }
    // Exhausted the budget while still being redirected. Returning `current` here would look
    // indistinguishable from "arrived", so say so instead and let the caller fall back.
    throw IOException("Still redirecting after $maxRedirects hops")
  }

  /**
   * Resolves a `Location` header against the current URL and vets it. Pure, so the hostile cases
   * can be unit-tested without a server.
   *
   * Rejects any scheme other than http/https. This is not theoretical: with an Android Chrome
   * User-Agent, `https://spotify.link/{code}` answers 307 with
   * `Location: intent://open?...#Intent;scheme=spotify;package=com.spotify.music;...`, an Android
   * intent URI. `java.net.URL` cannot even represent that, so without an explicit check the failure
   * surfaces as an opaque `MalformedURLException`.
   */
  internal fun nextRedirectUrl(currentUrl: String, location: String, allow: HostAllowList?): String {
    val next =
      try {
        URI(currentUrl).resolve(location.trim())
      } catch (e: IllegalArgumentException) {
        throw IOException("Unparsable redirect target (${e.javaClass.simpleName})")
      } catch (e: URISyntaxException) {
        throw IOException("Unparsable redirect target (${e.javaClass.simpleName})")
      }

    val scheme = next.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
      throw IOException("Refusing to follow non-HTTP redirect scheme '$scheme'")
    }
    return checkHost(next.toString(), allow)
  }

  private fun request(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: ByteArray?,
    allow: HostAllowList?,
  ): Response {
    var current = checkHost(url, allow)
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
            current = nextRedirectUrl(current, location, allow)
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
    throw IOException("Too many redirects (last status $lastCode)")
  }

  /** A redirect off the allow-list is a hard failure, never something we quietly follow. */
  private fun checkHost(url: String, allow: HostAllowList?): String {
    if (allow == null) return url
    val host = URL(url).host ?: throw IOException("No host in redirect target")
    if (!allow.allows(host)) throw IOException("Refusing to follow $host (allowed: $allow)")
    return url
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
