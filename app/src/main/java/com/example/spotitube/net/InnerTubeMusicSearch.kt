package com.example.spotitube.net

import com.example.spotitube.core.InnerTubeParser
import com.example.spotitube.core.YouTubeMusicSearch
import com.example.spotitube.core.YouTubeSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keyless search against YouTube Music's internal InnerTube endpoint.
 *
 * No API key, no cookies, no login. `params` is the opaque filter that restricts results to the
 * "Songs" shelf, which is exactly the set of official track uploads we want to rank.
 */
class InnerTubeMusicSearch : YouTubeMusicSearch {

  override suspend fun searchSongs(query: String): List<YouTubeSong> =
    withContext(Dispatchers.IO) {
      if (query.isBlank()) return@withContext emptyList()
      val response = runCatching { Http.postJson(ENDPOINT, requestBody(query), HEADERS) }.getOrNull()
        ?: return@withContext emptyList()
      if (!response.isSuccessful) return@withContext emptyList()
      InnerTubeParser.parseSongs(response.body)
    }

  /** Built by hand rather than with a serializer so the client payload stays byte-for-byte obvious. */
  private fun requestBody(query: String): String {
    val escaped = JsonPrimitive(query).toString()
    return """{"context":{"client":{"clientName":"$CLIENT_NAME","clientVersion":"$CLIENT_VERSION",""" +
      """"hl":"en","gl":"US"}},"query":$escaped,"params":"$SONGS_FILTER_PARAMS"}"""
  }

  companion object {
    const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20241202.01.00"

    /** Opaque InnerTube filter meaning "Songs shelf only". */
    private const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ=="

    private val HEADERS =
      mapOf(
        "Origin" to "https://music.youtube.com",
        "Referer" to "https://music.youtube.com/",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "User-Agent" to
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
          "Chrome/131.0.0.0 Safari/537.36",
        "X-YouTube-Client-Name" to "67",
        "X-YouTube-Client-Version" to CLIENT_VERSION,
      )
  }
}
