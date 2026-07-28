package com.example.spotitube.net

import com.example.spotitube.core.MetadataSource
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

  /**
   * Follows a `spotify.link` short URL to a canonical one — which, measured, it cannot actually do.
   *
   * **Expansion is structurally unsupported by this resolver, not merely unverified.** [Http] only
   * follows HTTP `3xx` with a `Location` header; it never reads the body. Branch never offers that:
   *
   * * `Spotitube/1.0 (+Android)` → `200`, no `Location`. The body is JavaScript whose only action is
   *   `window.top.location.replace("market://details?id=com.spotify.music")`.
   * * `facebookexternalhit/1.1` → `200`, no `Location`. A Branch landing page carrying
   *   `al:android:url = spotify://open?…`, with no canonical URL anywhere in it.
   * * a mobile-browser UA → `307`, and the only `3xx` on offer is
   *   `intent://…;package=com.spotify.music;S.browser_fallback_url=market://…`, a non-HTTP scheme we
   *   are right to refuse.
   *
   * So the hop is JavaScript or an Android intent, never a redirect we can follow. **Adding another
   * User-Agent cannot help** — that has been tried and measured across all three shapes above. The
   * only mechanism that could work is parsing the body, which is deliberately out of scope.
   *
   * The attempt is kept because it costs one request on a path that is already failing, and because
   * it is what routes an unresolvable short link into the safe degradation rather than to a browser.
   * Note what Branch wants for a non-browser client: `market://details?id=com.spotify.music`, and
   * `browser_fallback_url` pointing at the Play Store too. Branch is actively trying to send the
   * user to install Spotify, which is exactly why an unresolved short link is never handed to a
   * browser.
   *
   * Honest limit: every measurement used an **invalid** code, because no valid one is obtainable —
   * current Spotify emits canonical `open.spotify.com/…?si=…` from both desktop and mobile. A valid
   * code might behave differently, but the mechanism is clearly JS/intent-based either way.
   */
  override suspend fun expandShortLink(link: SpotifyLink): SpotifyLink? =
    withContext(Dispatchers.IO) {
      for (agent in SHORT_LINK_USER_AGENTS) {
        val finalUrl =
          runCatching {
            Http.resolveFinalUrl(
              link.canonicalUrl,
              headersFor(agent),
              SPOTIFY_HOSTS,
              maxRedirects = SHORT_LINK_MAX_HOPS,
            )
          }
            .getOrNull()
            ?: continue
        // The parser drops every query parameter, so `si`, `_branch_*` and UTM values from the
        // Branch hop never make it into anything we request or hand on.
        val parsed = SpotifyLinkParser.parse(finalUrl)?.takeIf { it.type != SpotifyEntityType.SHORT_LINK }
        if (parsed != null) return@withContext parsed
      }
      null
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
        if (merged != null && merged.title.isNotBlank()) {
          val source =
            when {
              embed != null && openGraph != null -> MetadataSource.EMBED_AND_OPEN_GRAPH
              embed != null -> MetadataSource.EMBED
              else -> MetadataSource.OPEN_GRAPH
            }
          return@coroutineScope merged.copy(source = source)
        }

        // Degraded last resort: oEmbed always answers but carries the title only, no artist and no
        // duration. MatchScorer refuses to auto-play on that, so this can only ever open search.
        oEmbedTitle(link)?.let {
          SpotifyTrackMeta(title = it, artists = emptyList(), source = MetadataSource.OEMBED_TITLE_ONLY)
        }
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
     * Truthful, and the only UA observed to resolve `spotify.link` all the way to a canonical URL.
     */
    const val APP_USER_AGENT = "Spotitube/1.0 (+Android)"

    /** `spotify.link` -> `spotify.app.link` -> `open.spotify.com` is three hops; allow one spare. */
    private const val SHORT_LINK_MAX_HOPS = 4

    /**
     * Agents tried when expanding a short link — now exactly one.
     *
     * `facebookexternalhit/1.1` was here as a second attempt and has been **deleted after
     * measurement**, not on suspicion: it returns `200` with no `Location`, identically to
     * [APP_USER_AGENT], so it retried a mechanism that cannot work rather than adding redundancy.
     * See [expandShortLink] for the full measurement across all three UA shapes.
     *
     * Browser UAs are excluded and must stay excluded: they are the one shape that *does* answer
     * `3xx`, but with an `intent://` URL we cannot follow. `ShortLinkUserAgentTest` guards that.
     *
     * **Do not add another agent here.** Expansion is not UA-limited; it is limited by Branch
     * hopping via JavaScript or an Android intent rather than an HTTP redirect.
     */
    internal val SHORT_LINK_USER_AGENTS = listOf(APP_USER_AGENT)

    /**
     * A `spotify.link` short URL redirects wherever Spotify points it; refuse to follow it off
     * Spotify's own domains so a crafted link cannot aim this client at an arbitrary host.
     */
    private val SPOTIFY_HOSTS = Http.HostAllowList("spotify.com", "spotify.link", "spotify.app.link", "spoti.fi")

    val DEFAULT_USER_AGENTS =
      listOf(
        // Truthful and currently served rich data by both endpoints.
        APP_USER_AGENT,
        // Unfurler UA: ~28 KB canonical response, all og:/music: tags present.
        "facebookexternalhit/1.1",
        // Mobile Chrome: ~139 KB but also fully server-rendered — independent fallback.
        "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
          "Chrome/131.0.0.0 Mobile Safari/537.36",
      )
  }
}
