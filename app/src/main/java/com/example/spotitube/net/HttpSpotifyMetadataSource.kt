package com.example.spotitube.net

import com.example.spotitube.core.SpotifyEntityType
import com.example.spotitube.core.SpotifyLink
import com.example.spotitube.core.SpotifyLinkParser
import com.example.spotitube.core.SpotifyMetaParser
import com.example.spotitube.core.SpotifyMetadataSource
import com.example.spotitube.core.SpotifyTrackMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads Spotify track metadata from the public `open.spotify.com` page. No API key, no OAuth.
 *
 * User-Agent matters: verified on 2026-07-28 that a **desktop Chrome** UA gets a ~6 KB JavaScript
 * shell with no Open Graph tags at all, while link-unfurler and mobile UAs get the server-rendered
 * page. We therefore try a short list of UAs and stop at the first that yields a parsable title.
 */
class HttpSpotifyMetadataSource(
  private val userAgents: List<String> = DEFAULT_USER_AGENTS,
) : SpotifyMetadataSource {

  override suspend fun expandShortLink(link: SpotifyLink): SpotifyLink? =
    withContext(Dispatchers.IO) {
      val finalUrl =
        runCatching { Http.resolveFinalUrl(link.canonicalUrl, headersFor(userAgents.first())) }.getOrNull()
          ?: return@withContext null
      SpotifyLinkParser.parse(finalUrl)?.takeIf { it.type != SpotifyEntityType.SHORT_LINK }
    }

  override suspend fun fetchTrack(link: SpotifyLink): SpotifyTrackMeta? =
    withContext(Dispatchers.IO) {
      for (userAgent in userAgents) {
        val response = runCatching { Http.get(link.canonicalUrl, headersFor(userAgent)) }.getOrNull() ?: continue
        if (!response.isSuccessful) continue
        val meta = SpotifyMetaParser.parse(response.body)
        if (meta != null && meta.title.isNotBlank()) return@withContext meta
      }
      // Degraded last resort: oEmbed always answers but carries the title only, no artist.
      oEmbedTitle(link)?.let { return@withContext SpotifyTrackMeta(title = it, artists = emptyList()) }
      null
    }

  private fun oEmbedTitle(link: SpotifyLink): String? {
    val url = "https://open.spotify.com/oembed?url=" +
      java.net.URLEncoder.encode(link.canonicalUrl, "UTF-8")
    val response = runCatching { Http.get(url, headersFor(userAgents.first())) }.getOrNull() ?: return null
    if (!response.isSuccessful) return null
    val match = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(response.body) ?: return null
    return SpotifyMetaParser.unescapeHtml(match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\"))
  }

  private fun headersFor(userAgent: String) =
    mapOf(
      "User-Agent" to userAgent,
      "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      // Without this the artist separator has been observed to come back as U+060C ARABIC COMMA.
      "Accept-Language" to "en-US,en;q=0.9",
    )

  companion object {
    val DEFAULT_USER_AGENTS =
      listOf(
        // Unfurler UA: ~28 KB response, all og:/music: tags present.
        "facebookexternalhit/1.1",
        // Mobile Chrome: ~139 KB but also fully server-rendered — independent fallback.
        "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
          "Chrome/131.0.0.0 Mobile Safari/537.36",
      )
  }
}
