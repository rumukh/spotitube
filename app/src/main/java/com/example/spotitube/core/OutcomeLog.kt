package com.example.spotitube.core

/**
 * Every field the resolver's outcomes are allowed to put in logcat, assembled here rather than
 * inline in the Android layer.
 *
 * Two reasons, and the second is the one that has actually bitten this project. It is the only way
 * the string that really reaches logcat can be asserted from a JVM unit test — there is deliberately
 * no instrumentation for the Activity layer — and it stops the play and search paths hand-rolling
 * their own field lists, which is exactly how they drifted into disclosing different things under
 * the same names.
 *
 * The disclosure rule for everything below: **opaque identifiers, numbers, booleans, and fixed
 * category names this codebase owns.** No title, artist, album, query, or any other string that
 * originates outside our own source. That excludes upstream text that merely *looks* enumerated —
 * `playabilityReason` is whatever Spotify's JSON happens to contain, so only its presence is
 * reported.
 */
object OutcomeLog {

  /**
   * The scoring fields for a play: the opaque video id, then the shared [MatchDiagnostics]
   * rendering. Identical after the id label to what [searchDiagnostic] emits, so one report reads
   * the same whichever outcome produced it.
   */
  fun playFields(play: ResolveOutcome.PlayOnYouTubeMusic): String =
    "videoId=${play.videoId.ifBlank { "none" }} ${play.diagnostics.format()}"

  /**
   * Body of the `MATCH` line — the scoring fields plus what Spotify told us about the track.
   *
   * `hasPlayabilityReason` is a boolean on purpose. The reason string is read straight out of
   * Spotify's embed payload and is unconstrained upstream text, so logging it verbatim put a string
   * we do not control into logcat on every successful play. Its *presence* is the diagnostic signal
   * — "Spotify had something to say about playability" — and that survives as a boolean.
   */
  fun matchLine(play: ResolveOutcome.PlayOnYouTubeMusic): String {
    val meta = play.spotify
    return playFields(play) +
      " spotifyDuration=${meta.durationSeconds}" +
      " explicit=${meta.isExplicit}" +
      " playable=${meta.isPlayable}" +
      " hasPlayabilityReason=${meta.playabilityReason != null}" +
      " metaSource=${meta.source?.name ?: "unknown"}"
  }

  /** The play-specific tail of the single structured `RESULT` line. */
  fun playResultFields(play: ResolveOutcome.PlayOnYouTubeMusic): String =
    "strategy=${YouTubeMusic.WATCH_STRATEGY} ${playFields(play)}"

  /**
   * Why the best candidate lost: **identifiers and numbers only, never text.**
   *
   * `t`/`a`/`al` are what make this diagnostic rather than merely negative — a bare `best 0.55`
   * cannot distinguish "we found the wrong song" from "we found the right song and YouTube named
   * the album differently", and those need opposite fixes.
   *
   * The candidate's title and artist are deliberately **not** here. They would be a near-copy of
   * the user's own query on this path, since a losing candidate is by construction a close miss —
   * so logging them would leak what was shared under the guise of logging YouTube's data. The
   * `videoId` is sufficient for correlation and resolves to the same row via public oEmbed.
   * Veto and note names are fixed category strings, not user content.
   *
   * The id label differs from [playFields] on purpose: `videoId` is the video we launched, whereas
   * `bestVideoId` is the one that lost. Everything after it is byte-identical between the paths.
   */
  fun searchDiagnostic(best: ScoredMatch): String =
    "bestVideoId=${best.song.videoId.ifBlank { "none" }} ${best.diagnostics().format()}"
}
