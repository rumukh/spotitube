package com.example.spotitube.core

import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a successful play is allowed to say about itself, and in which words.
 *
 * The defect these pin is a reporting one, and it was invisible because both halves looked
 * reasonable in isolation. `ScoredMatch.score` was the RANK; the play outcome carried it and the
 * MATCH and RESULT lines printed it as `score`; the threshold was applied to CORE; and the search
 * path's reason text used that same word for the core. So one label meant two quantities depending
 * on which outcome produced it, neither line named the bar, and a device regression baseline that
 * read "the score" off each run was comparing numbers that are not comparable.
 *
 * `the two quantities straddle the bar on a real candidate` below is the concrete demonstration:
 * core 0.680 opens search while rank 0.700 clears the 0.70 threshold, on one candidate.
 */
class MatchDiagnosticsTest {

  private val spotify =
    SpotifyTrackMeta(
      title = "Never Gonna Give You Up",
      artists = listOf("Rick Astley"),
      album = "Whenever You Need Somebody",
      durationSeconds = 214,
      isExplicit = false,
      isPlayable = true,
      source = MetadataSource.EMBED_AND_OPEN_GRAPH,
    )

  /** A clean winner that also earns presentation bonuses, so its core and rank differ. */
  private val winner =
    YouTubeSong(
      videoId = "lYBUbBu4W08",
      title = "Never Gonna Give You Up",
      artists = listOf("Rick Astley"),
      album = "Whenever You Need Somebody",
      durationSeconds = 214,
      hasAlbumLink = true,
      hasArtistChannel = true,
    )

  /** Every piece of display text, from both sides of the match, that must never reach a log line. */
  private val displayText = listOf("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody")

  private object NoSpotifyPage : SpotifyMetadataSource {
    override suspend fun expandShortLink(link: SpotifyLink): SpotifyLink? = null

    override suspend fun fetchTrack(link: SpotifyLink): SpotifyTrackMeta? = null
  }

  private fun resolverOver(vararg songs: YouTubeSong) =
    SpotitubeResolver(
      NoSpotifyPage,
      object : YouTubeMusicSearch {
        override suspend fun searchSongs(query: String): List<YouTubeSong> = songs.toList()
      },
    )

  private suspend fun playFor(meta: SpotifyTrackMeta, candidate: YouTubeSong): ResolveOutcome.PlayOnYouTubeMusic {
    val outcome = resolverOver(candidate).resolveTrack(meta)
    return outcome as? ResolveOutcome.PlayOnYouTubeMusic ?: error("expected a play outcome, got $outcome")
  }

  private suspend fun play(): ResolveOutcome.PlayOnYouTubeMusic = playFor(spotify, winner)

  // --- the outcome carries both quantities ------------------------------------------------------

  @Test
  fun `a play reports the winner's core and rank, and they are not the same number`() = runTest {
    val expected = MatchScorer.score(spotify, winner)
    // The fixture IS the test here. With core == rank every assertion below would pass just as
    // happily against an implementation that reported one of them twice, or swapped them — which
    // is the confusion being fixed, so it must not be possible to pass by accident.
    assertNotEquals(
      "the fixture is worthless unless core and rank actually differ: ${expected.explain()}",
      expected.core,
      expected.rank,
      1e-6,
    )

    val play = play()
    assertEquals("core must be the evidence score", expected.core, play.core, 1e-9)
    assertEquals("rank must be the ordering score", expected.rank, play.rank, 1e-9)
    assertEquals(expected.titleScore, play.diagnostics.titleScore, 1e-9)
    assertEquals(expected.artistScore, play.diagnostics.artistScore, 1e-9)
    assertEquals(expected.albumScore, play.diagnostics.albumScore, 1e-9)
    // The carried threshold must BE the constant. Carrying it keeps the renderer pure and lets a
    // caller judging against a different bar report the bar it used; this assertion is what stops
    // that flexibility becoming a second source of truth.
    assertEquals(
      "the reported bar must be the bar MatchScorer actually applies",
      MatchScorer.CONFIDENCE_THRESHOLD,
      play.diagnostics.threshold,
      1e-9,
    )
    assertEquals(expected.vetoes, play.diagnostics.vetoes)
    assertEquals(expected.notes, play.diagnostics.notes)
  }

  @Test
  fun `the two quantities straddle the bar on a real candidate`() = runTest {
    // Not a description of the defect — a candidate where reading one quantity as the other
    // inverts the decision. Title similarity 0.400 (two of five tokens shared, both ways), artist
    // exact, no album known:
    //   core = (0.40*0.400 + 0.35*1.000) / 0.75 = 0.680  -> below the 0.70 bar, so SEARCH
    //   rank = core + 0.02 position prior            = 0.700  -> at the bar
    // Nothing is vetoed, so this is decided purely on the threshold.
    val match = MatchScorer.score(spotify, NEAR_MISS)
    assertFalse("nothing may be vetoed or the threshold is not what decides: ${match.explain()}", match.vetoed)
    assertTrue(
      "the scenario has to actually occur: core must be BELOW the bar — ${match.explain()}",
      match.core < MatchScorer.CONFIDENCE_THRESHOLD,
    )
    assertTrue(
      "...and rank ABOVE it, or this candidate demonstrates nothing — ${match.explain()}",
      match.rank >= MatchScorer.CONFIDENCE_THRESHOLD,
    )

    val outcome = resolverOver(NEAR_MISS).resolveTrack(spotify)
    val search = outcome as? ResolveOutcome.SearchOnYouTubeMusic ?: error("expected search, got $outcome")
    assertTrue("the reason must name the quantity it judged: ${search.reason}", search.reason.contains("best core"))
    assertNoAmbiguousLabel(search.reason)
    assertReportsCoreAndRank(match.diagnostics(), search.diagnostic.orEmpty())
  }

  // --- exactly what the renderer emits ----------------------------------------------------------

  @Test
  fun `the renderer emits exactly this, character for character`() = runTest {
    assertEquals(
      "core=1.000 rank=1.310 (t=1.00 a=1.00 al=1.00) threshold=0.70 " +
        "notes=album-match,album-link,artist-channel",
      play().diagnostics.format(),
    )
  }

  @Test
  fun `the rendering has one grammar, with or without vetoes and notes`() {
    // Pins the optional groups and their order, which a `contains` assertion cannot: a VETO group
    // emitted after the threshold, or a stray extra field, would still contain everything sought.
    val grammar =
      Regex(
        """^core=\d\.\d{3} rank=\d\.\d{3} \(t=\d\.\d{2} a=\d\.\d{2} al=\d\.\d{2}\)""" +
          """( VETO\[[^\]]+\])? threshold=\d\.\d{2}( notes=\S+)?$"""
      )
    val karaoke =
      MatchScorer.score(
        spotify,
        winner.copy(videoId = "bbbbbbbbbbb", title = "Never Gonna Give You Up (Karaoke Version)"),
      )
    assertTrue("the vetoed fixture must actually be vetoed: ${karaoke.explain()}", karaoke.vetoed)
    for (rendered in listOf(MatchScorer.score(spotify, winner).explain(), karaoke.explain())) {
      assertTrue("does not match the pinned grammar: $rendered", grammar.matches(rendered))
    }
  }

  @Test
  fun `the log fields do not depend on the device locale`() = runTest {
    // Non-vacuous first: prove this locale really does change how %.3f renders, or the comparison
    // below is comparing two identical strings for reasons unrelated to the fix.
    assertEquals(
      "the harness is worthless unless this locale actually renders a comma decimal",
      "1,000",
      withLocale(Locale.GERMANY) { "%.3f".format(1.0) },
    )

    val play = play()
    val germanLine = withLocale(Locale.GERMANY) { OutcomeLog.matchLine(play) }
    assertEquals(
      "a device report must not change shape with the phone's language",
      withLocale(Locale.ROOT) { OutcomeLog.matchLine(play) },
      germanLine,
    )
    assertTrue(germanLine, germanLine.contains("core=1.000"))

    // The search reason is formatted at resolve time, so it has to be resolved under the locale.
    val germanReason =
      withLocale(Locale.GERMANY) {
        (resolverOver(NEAR_MISS).resolveTrack(spotify) as ResolveOutcome.SearchOnYouTubeMusic).reason
      }
    assertEquals("best core 0.68 below threshold 0.70", germanReason)
  }

  // --- what the line says -----------------------------------------------------------------------

  @Test
  fun `the play log line names core, rank, the sub-scores and the threshold`() = runTest {
    // Judged against the independently scored match, not against the outcome's own report of
    // itself: a line that agrees with a mis-wired outcome is exactly the failure being guarded.
    val expected = MatchScorer.score(spotify, winner).diagnostics()
    val line = OutcomeLog.playFields(play())
    assertTrue(line, line.contains("videoId=lYBUbBu4W08"))
    assertReportsCoreAndRank(expected, line)
    for (subScore in listOf("t=", "a=", "al=")) {
      assertTrue("missing $subScore in: $line", line.contains(subScore))
    }
  }

  @Test
  fun `each quantity appears exactly once on each play line`() = runTest {
    val play = play()
    for (line in listOf(OutcomeLog.matchLine(play), OutcomeLog.playResultFields(play))) {
      for (label in listOf("core=", "rank=", "threshold=", "videoId=")) {
        assertEquals(
          "$label must appear exactly once, or a reader cannot tell which occurrence decided: $line",
          1,
          Regex(Regex.escape(label)).findAll(line).count(),
        )
      }
      assertNoAmbiguousLabel(line)
    }
  }

  @Test
  fun `no text from either side of the match can reach a play line`() = runTest {
    // Poison sentinels through every string-shaped field the outcome touches, including the
    // upstream `playabilityReason` — which used to be logged verbatim and is whatever Spotify's
    // embed payload happens to contain, not a category this codebase owns.
    val poisoned =
      SpotifyTrackMeta(
        title = "$POISON-TITLE",
        artists = listOf("$POISON-ARTIST"),
        album = "$POISON-ALBUM",
        durationSeconds = 214,
        isExplicit = false,
        isPlayable = false,
        playabilityReason = "$POISON-PLAYABILITY",
        source = MetadataSource.EMBED_AND_OPEN_GRAPH,
      )
    val candidate =
      YouTubeSong(
        videoId = "lYBUbBu4W08",
        title = "$POISON-TITLE",
        artists = listOf("$POISON-ARTIST"),
        album = "$POISON-ALBUM",
        durationSeconds = 214,
      )
    val play = playFor(poisoned, candidate)

    // The scenario has to have happened: the poison must really be reachable from the outcome, or
    // this asserts the absence of something that was never there.
    assertTrue("the outcome must actually carry the poison", play.description.contains(POISON))
    assertEquals("$POISON-PLAYABILITY", play.spotify.playabilityReason)

    val matchLine = OutcomeLog.matchLine(play)
    for (line in listOf(matchLine, OutcomeLog.playResultFields(play))) {
      assertFalse("upstream or user text reached a log line: $line", line.contains(POISON))
    }
    // The signal survives as a boolean — dropping the field entirely would have lost it.
    assertTrue(matchLine, matchLine.contains("hasPlayabilityReason=true"))
    assertTrue(matchLine, matchLine.contains("metaSource=EMBED_AND_OPEN_GRAPH"))
  }

  @Test
  fun `the play log line carries no ambiguous label and no display text`() = runTest {
    val play = play()
    val line = OutcomeLog.playFields(play)
    assertNoAmbiguousLabel(line)
    for (text in displayText) {
      assertFalse("no title, artist or album may reach logcat: $line", line.contains(text))
    }
    // The only words on the line beyond the numbers are category names MatchScorer itself owns.
    // Asserted exactly: a new one appearing here should force a look at what it discloses.
    assertEquals(listOf("album-match", "album-link", "artist-channel"), play.diagnostics.notes)
  }

  @Test
  fun `a play and a search disclose exactly the same fields`() = runTest {
    // The property the device session needs: one report, read the same way whichever outcome it
    // was. Built from the SAME candidate, the two renderings may differ only in how the video id is
    // labelled — the video we launched, versus the one that lost.
    val match = MatchScorer.score(spotify, winner)
    assertEquals(
      OutcomeLog.searchDiagnostic(match).replaceFirst("bestVideoId=", "videoId="),
      OutcomeLog.playFields(play()),
    )
  }

  // --- and the checks above can actually fail ---------------------------------------------------

  @Test
  fun `a swapped or truncated report is detected, and a bare label is rejected`() = runTest {
    // Reproducing the defect with the fix removed. A green assertion about a log line is worth
    // nothing until it has been watched reject the wrong line.
    val correct = play().diagnostics
    val swapped = correct.copy(core = correct.rank, rank = correct.core)
    assertNotEquals("the swap must be visible at all", correct.format(), swapped.format())

    assertThrows("a swapped report must not pass", AssertionError::class.java) {
      assertReportsCoreAndRank(correct, swapped.format())
    }
    assertThrows("a report with no threshold must not pass", AssertionError::class.java) {
      assertReportsCoreAndRank(correct, correct.format().replace(Regex(""" threshold=[0-9.,]+"""), ""))
    }
    assertThrows("the old label must not pass", AssertionError::class.java) {
      assertNoAmbiguousLabel("videoId=lYBUbBu4W08 " + "score=%.3f".format(correct.rank))
    }
  }

  // --- the one part no pure test can reach ------------------------------------------------------

  @Test
  fun `the android log sites cannot reintroduce the ambiguous label or the raw reason`() {
    // Crude, and precedented in OwnerGenerationTest for the same reason: the property is about
    // WHERE something is written, in Android classes this project deliberately carries no
    // instrumentation for. The tests above pin what OutcomeLog renders; this pins the only
    // remaining way to get it wrong, which is to stop using it and interpolate the fields inline
    // again — which is precisely how the two paths drifted apart the first time.
    val handler = source("LinkHandlerActivity.kt")
    val main = source("MainActivity.kt")

    for (builder in listOf("OutcomeLog.matchLine(", "OutcomeLog.playResultFields(")) {
      assertTrue(
        "the play log lines must be built by $builder, so they keep sharing a renderer with the " +
          "search path and stay assertable without instrumentation",
        handler.contains(builder),
      )
    }
    assertFalse(
      "LinkHandlerActivity logs Spotify's raw playability reason again. That string is read " +
        "straight out of the embed payload and is not a category this codebase owns; report " +
        "hasPlayabilityReason instead.",
      handler.contains("playabilityReason="),
    )
    for ((name, text) in listOf("LinkHandlerActivity.kt" to handler, "MainActivity.kt" to main)) {
      assertFalse(
        "$name writes a bare score field again. It meant RANK on the play path while the " +
          "threshold was applied to CORE, and the search reason used the same word for CORE, so " +
          "one label named two quantities. Name the quantity instead. To mention the old label " +
          "in a comment, write it without the equals sign.",
        text.contains("score="),
      )
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  private fun assertReportsCoreAndRank(expected: MatchDiagnostics, rendered: String) {
    assertTrue("must name the evidence score: $rendered", rendered.contains("core=%.3f".format(expected.core)))
    assertTrue("must name the ordering score: $rendered", rendered.contains("rank=%.3f".format(expected.rank)))
    assertTrue(
      "must name the bar core had to clear: $rendered",
      rendered.contains("threshold=%.2f".format(expected.threshold)),
    )
  }

  private fun assertNoAmbiguousLabel(rendered: String) {
    assertFalse("a bare score field names two different quantities here: $rendered", rendered.contains("score="))
  }

  /** Inline so the body may suspend: some of these have to *resolve* under the locale. */
  private inline fun <T> withLocale(locale: Locale, body: () -> T): T {
    val original = Locale.getDefault()
    return try {
      Locale.setDefault(locale)
      body()
    } finally {
      Locale.setDefault(original)
    }
  }

  /** Gradle runs unit tests from the module directory; the fallback covers a repo-root run. */
  private fun source(name: String): String =
    java.io.File("src/main/java/com/example/spotitube/$name")
      .takeIf { it.exists() }
      ?.readText()
      ?: java.io.File("app/src/main/java/com/example/spotitube/$name").readText()

  private companion object {
    const val POISON = "SPOTITUBE-POISON"

    /** Title similarity 0.400 against the fixture: two of five tokens shared, both directions. */
    val NEAR_MISS =
      YouTubeSong(
        videoId = "aaaaaaaaaaa",
        title = "Never Gonna Dance Tonight Instead",
        artists = listOf("Rick Astley"),
        durationSeconds = 214,
      )
  }
}
