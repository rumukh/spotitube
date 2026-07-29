package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-Latin titles, end to end, against **real captured responses**.
 *
 * These use live Spotify embed payloads and live InnerTube results because the bug they cover hid
 * behind hand-written fixtures: every CJK title we had invented ourselves, and none of them carried
 * YouTube Music's romanisation suffix. The titles here are exactly what YouTube returned.
 */
class JapaneseTrackMatchingTest {

  /** embed fixture, canonical-page fixture, InnerTube fixture, expected winning videoId. */
  private val FULL_CASES =
    listOf(
      Quad(Fixtures.YORU_NO_ODORIKO_EMBED, Fixtures.YORU_NO_ODORIKO_HTML, Fixtures.YORU_NO_ODORIKO_SEARCH, "F8e2ohz1Jwg"),
      Quad(Fixtures.TAKANE_EMBED, Fixtures.TAKANE_HTML, Fixtures.TAKANE_SEARCH, "uDsEVMjwNb0"),
      Quad(Fixtures.BLUE_AMBER_EMBED, Fixtures.BLUE_AMBER_HTML, Fixtures.BLUE_AMBER_SEARCH, "BZ8MAxVzdC0"),
      Quad(Fixtures.AO_TO_NATSU_EMBED, Fixtures.AO_TO_NATSU_HTML, Fixtures.AO_TO_NATSU_SEARCH, "-QxMzUEJH4Q"),
    )

  private data class Quad(val embed: String, val html: String, val search: String, val expectedId: String)

  private fun spotify(fixture: String) = SpotifyEmbedParser.parse(Fixtures.read(fixture))!!

  /**
   * What the **app** actually holds: the embed payload merged with the canonical page.
   *
   * Use this, not [spotify], for anything that asserts an outcome. The embed carries no album, so
   * [spotify] alone exercises [MatchScorer]'s album-absent branch — which renormalises the album
   * weight away and is therefore systematically *more* forgiving than the device.
   */
  private fun merged(embed: String, html: String) =
    SpotifyEmbedParser.parse(Fixtures.read(embed))!!.mergedWith(SpotifyMetaParser.parse(Fixtures.read(html)))

  private fun candidates(fixture: String) = InnerTubeParser.parseSongs(Fixtures.read(fixture))

  // --- the bug: a correct match that fell under the threshold ----------------------------------

  @Test
  fun `the exact recording of Yoru No Odoriko plays`() {
    // Right song, right artist, exact duration — and before the romanisation strip this scored a
    // title similarity of 0.40 and opened search instead of playing.
    val meta = spotify(Fixtures.YORU_NO_ODORIKO_EMBED)
    assertEquals("夜の踊り子", meta.title)
    assertEquals(listOf("sakanaction"), meta.artists)

    val outcome = MatchScorer.best(meta, candidates(Fixtures.YORU_NO_ODORIKO_SEARCH))
    assertTrue("expected confidence, got ${outcome.best?.explain()}", outcome.confident)
    assertEquals("F8e2ohz1Jwg", outcome.best!!.song.videoId)
  }

  @Test
  fun `the other three Japanese tracks also play`() {
    for ((embed, html, search, expectedId) in FULL_CASES.drop(1)) {
      val meta = merged(embed, html)
      val outcome = MatchScorer.best(meta, candidates(search))
      assertTrue("${meta.title}: not confident — ${outcome.best?.explain()}", outcome.confident)
      assertEquals(meta.title, expectedId, outcome.best!!.song.videoId)
    }
  }

  @Test
  fun `the album from the canonical page cannot sink a correct Japanese match`() {
    // The regression this pins is a *test* defect as much as a code one. These tracks were green
    // while the device fell to SEARCH, because the fixtures parsed only the embed payload — which
    // carries no album — and so exercised MatchScorer's album-absent branch, which renormalises.
    // The app merges the canonical page in, so on a device the album is present and disagrees:
    //   高嶺の花子さん  Spotify ラブストーリー   vs YouTube "Love Story"   — same album, two scripts
    //   ブルーアンバー   Spotify ブルーアンバー   vs YouTube "Blue Amber"   — same album, two scripts
    //   青と夏         Spotify Attitude      vs YouTube "Ao To Natsu"  — single vs parent album
    // None is a real disagreement. Under the old flat weighting each cost 0.25 and dropped a
    // perfect title+artist match to 0.750 — and any title short of perfect below the threshold.
    for ((embed, html, search, expectedId) in FULL_CASES) {
      val meta = merged(embed, html)
      assertNotNull("${meta.title}: the canonical page must supply an album", meta.album)
      val outcome = MatchScorer.best(meta, candidates(search))
      val best = outcome.best
      assertNotNull("${meta.title}: no candidate survived", best)
      assertEquals("${meta.title}: wrong winner — ${best!!.explain()}", expectedId, best.song.videoId)
      assertTrue("${meta.title}: not confident — ${best.explain()}", outcome.confident)
      assertEquals(
        "${meta.title}: merged metadata must score exactly as the embed-only path did, since a " +
          "disagreeing album is not evidence — ${best.explain()}",
        MatchScorer.best(spotify(embed), candidates(search)).best!!.core,
        best.core,
        1e-9,
      )
    }
  }

  @Test
  fun `the romanised title scores as an exact match`() {
    val meta = spotify(Fixtures.YORU_NO_ODORIKO_EMBED)
    val winner = candidates(Fixtures.YORU_NO_ODORIKO_SEARCH).first { it.videoId == "F8e2ohz1Jwg" }
    assertEquals("夜の踊り子 - Yoru No Odoriko", winner.title)
    assertEquals(1.0, TextNormalizer.similarity(meta.title, winner.title), 1e-9)
  }

  // --- the inverse: stripping must not manufacture matches --------------------------------------

  @Test
  fun `a different song by the same artist stays rejected`() {
    // 新宝島 is in the same result set. Stripping the romanisation leaves the CJK head, which is the
    // discriminating part, so this must share nothing with 夜の踊り子.
    val meta = spotify(Fixtures.YORU_NO_ODORIKO_EMBED)
    val ranked = MatchScorer.best(meta, candidates(Fixtures.YORU_NO_ODORIKO_SEARCH)).ranked
    val other = ranked.first { it.song.videoId == "0EuC4jUbITA" }
    assertEquals("新宝島 - Shin Takara Jima", other.song.title)
    assertTrue("must stay vetoed: ${other.explain()}", other.vetoed)
    assertEquals(0.0, other.core, 1e-9)
    assertEquals(0.0, other.rank, 1e-9)
  }

  @Test
  fun `a remix of the right song stays rejected`() {
    val meta = spotify(Fixtures.YORU_NO_ODORIKO_EMBED)
    val ranked = MatchScorer.best(meta, candidates(Fixtures.YORU_NO_ODORIKO_SEARCH)).ranked
    val remix = ranked.first { it.song.videoId == "VgI4-zjwCfk" }
    assertTrue(remix.song.title.contains("Remix"))
    assertTrue("remix must stay vetoed: ${remix.explain()}", remix.vetoed)
  }

  @Test
  fun `an instrumental of the right song stays rejected`() {
    val meta = spotify(Fixtures.TAKANE_EMBED)
    val ranked = MatchScorer.best(meta, candidates(Fixtures.TAKANE_SEARCH)).ranked
    val instrumental = ranked.first { it.song.videoId == "JVERIhW4z8k" }
    assertTrue("instrumental must stay vetoed: ${instrumental.explain()}", instrumental.vetoed)
  }

  // --- the strip itself, now pair-conditioned ----------------------------------------------------

  @Test
  fun `the romanisation rule only fires with real evidence`() {
    // Full-title equality via the pair rule…
    assertEquals(1.0, TextNormalizer.similarity("夜の踊り子", "夜の踊り子 - Yoru No Odoriko"), 1e-9)
    // …but never for a Latin head, and never when both sides carry a Latin suffix.
    assertTrue(TextNormalizer.similarity("Circles", "Circles - Around The Sun") < 1.0)
    assertTrue(TextNormalizer.similarity("同じ頭 - Part One", "同じ頭 - Part Two") < 1.0)
  }

  /** Not an assertion — prints the calibration these tests were tuned against. */
  @Test
  fun `print japanese calibration`() {
    for ((embed, html, search) in FULL_CASES) {
      val embedOnly = spotify(embed)
      val merged = merged(embed, html)
      val embedOutcome = MatchScorer.best(embedOnly, candidates(search))
      val outcome = MatchScorer.best(merged, candidates(search))
      println(
        "=== ${merged.title} — ${merged.artistLine} — ${merged.durationSeconds}s" +
          "  spotifyAlbum=${merged.album}" +
          "  embedOnly=${"%.3f".format(embedOutcome.best?.core ?: 0.0)}/${embedOutcome.confident}" +
          "  merged=${"%.3f".format(outcome.best?.core ?: 0.0)}/${outcome.confident}"
      )
      outcome.ranked.take(4).forEach {
        println(
          "   %-12s %-40s album=%-22s %s".format(
            it.song.videoId,
            it.song.title.take(38),
            (it.song.album ?: "-").take(20),
            it.explain(),
          )
        )
      }
    }
  }
}
