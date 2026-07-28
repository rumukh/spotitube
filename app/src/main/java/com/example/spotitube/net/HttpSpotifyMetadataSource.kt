package com.example.spotitube.net

import com.example.spotitube.core.SpotifyEmbedParser
import com.example.spotitube.core.SpotifyEntityType
import com.example.spotitube.core.SpotifyLink
import com.example.spotitube.core.SpotifyLinkParser
import com.example.spotitube.core.SpotifyMetaParser
import com.example.spotitube.core.SpotifyMetadataSource
import com.example.spotitube.core.SpotifyTrackMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Reads Spotify track metadata from public `open.spotify.com` pages. No API key, no OAuth.
 *
 * Two independent sources are fetched **concurrently** and merged, because neither is sufficient
 * on its own:
 *
 * * `/embed/track/{id}` serves a `__NEXT_DATA__` JSON island — structured artists (no separator
 *   guessing), millisecond duration, and an explicit flag. It has no album name.
 * * the canonical `/track/{id}` page's Open Graph tags are the only place the **album** appears,
 *   which is what disambiguates two uploads of the same recording on different releases.
 *
 * The canonical page is also UA- and CDN-variable: a desktop Chrome UA has been observed getting a
 * ~6 KB JavaScript shell with no tags at all, so a short list of user agents is tried in turn.
 * Both sources are undocumented, so either may return nothing and the merge simply degrades.
 */
class HttpSpotifyMetadataSource(
  private val userAgents: List<String> = DEFAULT_USER_AGENTS,
) : SpotifyMetadataSource {

  override suspend fun expandShortLink(link: SpotifyLink): SpotifyLink? =
    withContext(Dispatchers.IO) {
      val finalUrl =
        runCatching { Http.resolveFinalUrl(link.canonicalUrl, headersFor(userAgents.first()), SPOTIFY_HOSTS) }
          .getOrNull()
          ?: return@withContext null
      SpotifyLinkParser.parse(finalUrl)?.takeIf { it.type != SpotifyEntityType.SHORT_LINK }
    }

  override suspend fun fetchTrack(link: SpotifyLink): SpotifyTrackMeta? =
    withContext(Dispatchers.IO) {
      coroutineScope {
        val embedDeferred = async { fetchEmbed(link) }
        val openGraphDeferred = async { fetchOpenGraph(link) }
        val embed = embedDeferred.await()
        val openGraph = openGraphDeferred.await()

        // Prefer the structured payload, then fill the album (and anything missing) from the tags.
        val merged = embed?.mergedWith(openGraph) ?: openGraph
        if (merged != null && merged.title.isNotBlank()) return@coroutineScope merged

        // Degraded last resort: oEmbed always answers but carries the title only, no artist and no
        // duration. MatchScorer refuses to auto-play on that, so this can only ever open search.
        oEmbedTitle(link)?.let { SpotifyTrackMeta(title = it, artists = emptyList()) }
      }
    }

  private fun fetchEmbed(link: SpotifyLink): SpotifyTrackMeta? {
    val id = link.id ?: return null
    val url = "https://open.spotify.com/embed/track/$id"
    for (userAgent in userAgents) {
      val response = runCatching { Http.get(url, headersFor(userAgent), SPOTIFY_HOSTS) }.getOrNull() ?: continue
      if (!response.isSuccessful) continue
      SpotifyEmbedParser.parse(response.body)?.let { return it }
    }
    return null
  }

  private fun fetchOpenGraph(link: SpotifyLink): SpotifyTrackMeta? {
    for (userAgent in userAgents) {
      val response =
        runCatching { Http.get(link.canonicalUrl, headersFor(userAgent), SPOTIFY_HOSTS) }.getOrNull() ?: continue
      if (!response.isSuccessful) continue
      val meta = SpotifyMetaParser.parse(response.body)
      if (meta != null && meta.title.isNotBlank()) return meta
    }
    return null
  }

  private fun oEmbedTitle(link: SpotifyLink): String? {
    val url = "https://open.spotify.com/oembed?url=" +
      java.net.URLEncoder.encode(link.canonicalUrl, "UTF-8")
    val response = runCatching { Http.get(url, headersFor(userAgents.first()), SPOTIFY_HOSTS) }.getOrNull()
      ?: return null
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
    /**
     * A `spotify.link` short URL redirects wherever Spotify points it; refuse to follow it off
     * Spotify's own domains so a crafted link cannot aim this client at an arbitrary host.
     */
    private val SPOTIFY_HOSTS = Http.HostAllowList("spotify.com", "spotify.link", "spotify.app.link", "spoti.fi")

    val DEFAULT_USER_AGENTS =
      listOf(
        // Truthful and currently served rich data by both endpoints.
        "Spotitube/1.0 (+Android)",
        // Unfurler UA: ~28 KB canonical response, all og:/music: tags present.
        "facebookexternalhit/1.1",
        // Mobile Chrome: ~139 KB but also fully server-rendered — independent fallback.
        "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
          "Chrome/131.0.0.0 Mobile Safari/537.36",
      )
  }
}
