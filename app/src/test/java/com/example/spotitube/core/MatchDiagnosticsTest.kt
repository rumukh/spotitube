package com.example.spotitube.core

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

  private suspend fun play(): ResolveOutcome.PlayOnYouTubeMusic {
    val outcome = resolverOver(winner).resolveTrack(spotify)
    return outcome as? ResolveOutcome.PlayOnYouTubeMusic ?: error("expected a play outcome, got $outcome")
  }

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
    assertEquals(
      "the bar must travel with the numbers, or a reader has to know the constant",
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
    val nearMiss =
      YouTubeSong(
        videoId = "aaaaaaaaaaa",
        title = "Never Gonna Dance Tonight Instead",
        artists = listOf("Rick Astley"),
        durationSeconds = 214,
      )
    val match = MatchScorer.score(spotify, nearMiss)
    assertFalse("nothing may be vetoed or the threshold is not what decides: ${match.explain()}", match.vetoed)
    assertTrue(
      "the scenario has to actually occur: core must be BELOW the bar — ${match.explain()}",
      match.core < MatchScorer.CONFIDENCE_THRESHOLD,
    )
    assertTrue(
      "...and rank ABOVE it, or this candidate demonstrates nothing — ${match.explain()}",
      match.rank >= MatchScorer.CONFIDENCE_THRESHOLD,
    )

    val outcome = resolverOver(nearMiss).resolveTrack(spotify)
    val search = outcome as? ResolveOutcome.SearchOnYouTubeMusic ?: error("expected search, got $outcome")
    assertTrue("the reason must name the quantity it judged: ${search.reason}", search.reason.contains("best core"))
    assertNoAmbiguousLabel(search.reason)
    assertReportsCoreAndRank(match.diagnostics(), search.diagnostic.orEmpty())
  }

  // --- what the line says -----------------------------------------------------------------------

  @Test
  fun `the play log line names core, rank, the sub-scores and the threshold`() = runTest {
    // Judged against the independently scored match, not against the outcome's own report of
    // itself: a line that agrees with a mis-wired outcome is exactly the failure being guarded.
    val expected = MatchScorer.score(spotify, winner).diagnostics()
    val line = SpotitubeResolver.describe(play())
    assertTrue(line, line.contains("videoId=lYBUbBu4W08"))
    assertReportsCoreAndRank(expected, line)
    for (subScore in listOf("t=", "a=", "al=")) {
      assertTrue("missing $subScore in: $line", line.contains(subScore))
    }
  }

  @Test
  fun `the play log line carries no ambiguous label and no display text`() = runTest {
    val play = play()
    val line = SpotitubeResolver.describe(play)
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
      SpotitubeResolver.diagnose(match).replaceFirst("bestVideoId=", "videoId="),
      SpotitubeResolver.describe(play()),
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
  fun `the android log sites cannot reintroduce the ambiguous label`() {
    // Crude, and precedented in OwnerGenerationTest for the same reason: the property is about
    // WHERE something is written, in Android classes this project deliberately carries no
    // instrumentation for. The tests above pin what SpotitubeResolver.describe renders; this pins
    // the only remaining way to get it wrong, which is to stop using it and hand-roll the fields
    // again — which is precisely how the two paths drifted apart the first time.
    val handler = source("LinkHandlerActivity.kt")
    val main = source("MainActivity.kt")

    assertTrue(
      "the play log line must be built by SpotitubeResolver.describe, so it keeps sharing a " +
        "renderer with the search path and stays assertable without instrumentation",
      handler.contains("SpotitubeResolver.describe("),
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

  /** Gradle runs unit tests from the module directory; the fallback covers a repo-root run. */
  private fun source(name: String): String =
    java.io.File("src/main/java/com/example/spotitube/$name")
      .takeIf { it.exists() }
      ?.readText()
      ?: java.io.File("app/src/main/java/com/example/spotitube/$name").readText()
}
