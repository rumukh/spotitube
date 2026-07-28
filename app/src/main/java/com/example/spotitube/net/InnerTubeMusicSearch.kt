package com.example.spotitube.net

import android.util.Log
import com.example.spotitube.LinkHandlerActivity
import com.example.spotitube.core.InnerTubeParser
import com.example.spotitube.core.ShelfStrategy
import com.example.spotitube.core.YouTubeMusic
import com.example.spotitube.core.YouTubeMusicSearch
import com.example.spotitube.core.YouTubeSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
      // Blocking IO is not interrupted by cancellation. Checking here means a superseded request
      // does not spend the user's data on a search whose answer can no longer be used.
      currentCoroutineContext().ensureActive()
      val response = runCatching { Http.postJson(ENDPOINT, requestBody(query), HEADERS, YOUTUBE_HOSTS) }.getOrNull()
        ?: return@withContext emptyList()
      if (!response.isSuccessful) return@withContext emptyList()
      val parse = InnerTubeParser.parseSongsWithStrategy(response.body)
      if (parse.strategy == ShelfStrategy.RECOVERED_ENVELOPE) {
        // Silent degradation: results still look normal, so without this line nobody would ever
        // learn that YouTube changed the documented shape.
        Log.w(LinkHandlerActivity.TAG, "SHELF strategy=RECOVERED_ENVELOPE rows=${parse.songs.size}")
      }
      parse.songs
    }

  /** Built by hand rather than with a serializer so the client payload stays byte-for-byte obvious. */
  private fun requestBody(query: String): String {
    val escaped = JsonPrimitive(query).toString()
    return """{"context":{"client":{"clientName":"$CLIENT_NAME","clientVersion":"$CLIENT_VERSION",""" +
      """"hl":"en","gl":"US"}},"query":$escaped,"params":"$SONGS_FILTER_PARAMS"}"""
  }

  companion object {
    const val ENDPOINT = "${YouTubeMusic.ORIGIN}/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20241202.01.00"

    /** Opaque InnerTube filter meaning "Songs shelf only". */
    private const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ=="

    private val YOUTUBE_HOSTS = Http.HostAllowList("youtube.com", "google.com", "googleapis.com")

    private val HEADERS =
      mapOf(
        // Derived from the single source of truth: InnerTube rejects a mismatched Origin, so these
        // must never drift from the host we actually call.
        "Origin" to YouTubeMusic.ORIGIN,
        "Referer" to "${YouTubeMusic.ORIGIN}/",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        // A desktop browser UA here is deliberate and unrelated to the short-link UA policy: this
        // is the web client InnerTube expects, and it is a POST to an API, not a Branch redirect.
        "User-Agent" to
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
          "Chrome/131.0.0.0 Safari/537.36",
        "X-YouTube-Client-Name" to "67",
        "X-YouTube-Client-Version" to CLIENT_VERSION,
      )
  }
}
