package com.example.spotitube.core

/** What the app decided to do with a link. Rendered by the Android layer into an Intent. */
sealed interface ResolveOutcome {

  /** Confident match — open YouTube Music straight into the song. */
  data class PlayOnYouTubeMusic(
    val videoId: String,
    val url: String,
    val description: String,
    val score: Double,
    val spotify: SpotifyTrackMeta,
  ) : ResolveOutcome

  /** No confident match — hand the user YouTube Music's search results instead of guessing. */
  data class SearchOnYouTubeMusic(val query: String, val url: String, val reason: String) : ResolveOutcome

  /** Albums, playlists, artists, shows and episodes go back where they came from. */
  data class BounceToSpotify(val url: String, val type: SpotifyEntityType) : ResolveOutcome

  /** The input was not a Spotify link at all, or the network failed hard. */
  data class Unsupported(val reason: String) : ResolveOutcome
}

/** Fetches the metadata behind a Spotify link. Implemented over HTTP; faked in unit tests. */
interface SpotifyMetadataSource {
  /** Follows redirects for `spotify.link` short URLs and returns the canonical link, or `null`. */
  suspend fun expandShortLink(link: SpotifyLink): SpotifyLink?

  /** Loads and parses the track page. Returns `null` when the page yields no usable metadata. */
  suspend fun fetchTrack(link: SpotifyLink): SpotifyTrackMeta?
}

/** Searches YouTube Music's song shelf. Implemented over InnerTube; faked in unit tests. */
interface YouTubeMusicSearch {
  suspend fun searchSongs(query: String): List<YouTubeSong>
}

/**
 * The whole product decision, with no Android dependencies so it can be unit-tested end to end
 * against fixtures.
 */
class SpotitubeResolver(
  private val spotify: SpotifyMetadataSource,
  private val youTube: YouTubeMusicSearch,
) {

  /** Accepts a URL, a `spotify:` URI, or arbitrary shared text containing one. */
  suspend fun resolve(input: String?): ResolveOutcome {
    val parsed = SpotifyLinkParser.findIn(input) ?: return ResolveOutcome.Unsupported("no Spotify link in input")

    val link =
      if (parsed.type == SpotifyEntityType.SHORT_LINK) {
        spotify.expandShortLink(parsed)
          ?: return ResolveOutcome.BounceToSpotify(parsed.canonicalUrl, SpotifyEntityType.SHORT_LINK)
      } else {
        parsed
      }

    if (!link.isTrack) return ResolveOutcome.BounceToSpotify(link.canonicalUrl, link.type)

    val meta =
      spotify.fetchTrack(link)
        ?: return ResolveOutcome.BounceToSpotify(link.canonicalUrl, link.type)

    return resolveTrack(meta)
  }

  /** The half of [resolve] that runs once we have Spotify metadata. Exposed for focused tests. */
  suspend fun resolveTrack(meta: SpotifyTrackMeta): ResolveOutcome {
    val query = meta.searchQuery
    val searchUrl = youTubeMusicSearchUrl(query)

    val candidates = runCatching { youTube.searchSongs(query) }.getOrElse { emptyList() }
    if (candidates.isEmpty()) {
      return ResolveOutcome.SearchOnYouTubeMusic(query, searchUrl, "no candidates from search")
    }

    val outcome = MatchScorer.best(meta, candidates)
    val best = outcome.best
    // A malformed videoId cannot be launched: YouTube Music claims its whole host with a `.*` path
    // pattern, so a bad watch URL opens an indeterminate screen instead of throwing. Fall back to
    // search rather than sending the user somewhere arbitrary.
    val watchUrl = best?.song?.watchUrl
    if (!outcome.confident || best == null || watchUrl == null) {
      val why =
        when {
          outcome.insufficientEvidence -> "no artists in Spotify metadata: not enough to auto-play"
          outcome.ambiguous ->
            "a near-tied candidate is a different recording, so the winner is not clearly right"
          best == null -> "nothing ranked"
          best.vetoed -> "best candidate vetoed: ${best.vetoes.joinToString(",")}"
          watchUrl == null -> "malformed videoId"
          else -> "best score %.2f below threshold %.2f".format(best.core, MatchScorer.CONFIDENCE_THRESHOLD)
        }
      return ResolveOutcome.SearchOnYouTubeMusic(query, searchUrl, why)
    }

    return ResolveOutcome.PlayOnYouTubeMusic(
      videoId = best.song.videoId,
      url = watchUrl,
      description = "${best.song.artistLine} — ${best.song.title}",
      score = best.score,
      spotify = meta,
    )
  }

  companion object {
    fun youTubeMusicSearchUrl(query: String): String = YouTubeMusic.searchUrl(query)
  }
}
