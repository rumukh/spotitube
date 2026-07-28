package com.example.spotitube.core

import kotlinx.coroutines.CancellationException

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

  /**
   * No candidate was confidently the same recording, so open search instead of guessing.
   *
   * [diagnostic] carries the losing candidate and its sub-scores. Without it a SEARCH line says only
   * *that* we scored below threshold, never *why* — a real report read "best 0.55" and the album
   * term being the entire cause had to be derived by hand from the weights. It is the same class of
   * disclosure as the `picked=`/`spotify=` fields already on the MATCH line.
   */
  data class SearchOnYouTubeMusic(
    val query: String,
    val url: String,
    val reason: String,
    val diagnostic: String? = null,
  ) : ResolveOutcome

  /** Albums, playlists, artists, shows and episodes go back where they came from. */
  data class BounceToSpotify(val url: String, val type: SpotifyEntityType, val schemeUri: String? = null) :
    ResolveOutcome

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

    if (!link.isTrack) return ResolveOutcome.BounceToSpotify(link.canonicalUrl, link.type, link.schemeUri)

    val meta =
      spotify.fetchTrack(link)
        ?: return ResolveOutcome.BounceToSpotify(link.canonicalUrl, link.type, link.schemeUri)

    return resolveTrack(meta)
  }

  /** The half of [resolve] that runs once we have Spotify metadata. Exposed for focused tests. */
  suspend fun resolveTrack(meta: SpotifyTrackMeta): ResolveOutcome {
    val query = meta.searchQuery
    val searchUrl = youTubeMusicSearchUrl(query)

    // Cancellation must pass straight through. A superseded request is cancelled, and converting
    // that into an empty candidate list produces a user-facing SEARCH with
    // reason="no candidates from search" -- a string measured verbatim on device for a track that
    // had in fact scored 0.810. Only a REAL search failure may fall back.
    val candidates =
      try {
        youTube.searchSongs(query)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Throwable) {
        emptyList()
      }
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
      return ResolveOutcome.SearchOnYouTubeMusic(query, searchUrl, why, diagnostic = best?.let(::diagnose))
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

    /**
     * Why the best candidate lost, in one line: the candidate, the sub-scores, the threshold it had
     * to clear, and any veto. `t`/`a`/`al` are what make this diagnostic rather than merely
     * negative — a bare `best 0.55` cannot distinguish "we found the wrong song" from "we found the
     * right song and YouTube named the album differently", and those need opposite fixes.
     */
    internal fun diagnose(best: ScoredMatch): String =
      "best=\"${best.song.artistLine} — ${best.song.title}\" ${best.explain()} " +
        "threshold=%.2f".format(MatchScorer.CONFIDENCE_THRESHOLD) +
        (if (best.notes.isNotEmpty()) " notes=${best.notes.joinToString(",")}" else "")
  }
}
