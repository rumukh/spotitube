package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  private fun spotify(fixture: String) = SpotifyEmbedParser.parse(Fixtures.read(fixture))!!

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
    val cases =
      listOf(
        Triple(Fixtures.TAKANE_EMBED, Fixtures.TAKANE_SEARCH, "uDsEVMjwNb0"),
        Triple(Fixtures.BLUE_AMBER_EMBED, Fixtures.BLUE_AMBER_SEARCH, "BZ8MAxVzdC0"),
        Triple(Fixtures.AO_TO_NATSU_EMBED, Fixtures.AO_TO_NATSU_SEARCH, "-QxMzUEJH4Q"),
      )
    for ((embed, search, expectedId) in cases) {
      val meta = spotify(embed)
      val outcome = MatchScorer.best(meta, candidates(search))
      assertTrue("${meta.title}: not confident — ${outcome.best?.explain()}", outcome.confident)
      assertEquals(meta.title, expectedId, outcome.best!!.song.videoId)
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
    assertEquals(0.0, other.score, 1e-9)
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

  // --- the strip itself --------------------------------------------------------------------------

  @Test
  fun `romanisation suffixes are stripped`() {
    val cases =
      mapOf(
        "夜の踊り子 - Yoru No Odoriko" to "夜の踊り子",
        "新宝島 - Shin Takara Jima" to "新宝島",
        "高嶺の花子さん - Takaneno Hanakosan" to "高嶺の花子さん",
        "ブルーアンバー - Blue Amber" to "ブルーアンバー",
        "点描の唄 - Tenbyouno Uta (feat. Sonoko Inoue)" to "点描の唄",
        "밤편지 - Through the Night" to "밤편지",
        "Кино - Gruppa Krovi" to "Кино",
      )
    for ((input, expected) in cases) {
      assertEquals(input, expected, TextNormalizer.stripRomanisation(input))
    }
  }

  @Test
  fun `latin titles are never touched`() {
    // The rule is gated on a non-Latin head precisely so nothing already working can regress.
    val untouched =
      listOf(
        "Sunflower - Spider-Man: Into the Spider-Verse",
        "Never Gonna Give You Up - Remastered",
        "Live and Let Die",
        "Circles",
        "rockstar (feat. 21 Savage)",
      )
    for (title in untouched) {
      assertEquals(title, title, TextNormalizer.stripRomanisation(title))
    }
  }

  @Test
  fun `a non latin tail is not a romanisation`() {
    // Both sides non-Latin means this is a real two-part title, not a transliteration.
    assertEquals("夜の踊り子 - 踊り子", TextNormalizer.stripRomanisation("夜の踊り子 - 踊り子"))
  }

  @Test
  fun `a tail with no letters is not a romanisation`() {
    assertEquals("夜の踊り子 - 2020", TextNormalizer.stripRomanisation("夜の踊り子 - 2020"))
  }

  @Test
  fun `stripping never empties a title`() {
    for (odd in listOf("夜の踊り子 - ", " - Yoru", "夜の踊り子", "", "   ")) {
      val result = TextNormalizer.stripRomanisation(odd)
      assertFalse("'$odd' -> '' would erase the title", odd.isNotBlank() && result.isBlank())
    }
  }

  /** Not an assertion — prints the calibration these tests were tuned against. */
  @Test
  fun `print japanese calibration`() {
    val cases =
      listOf(
        Fixtures.YORU_NO_ODORIKO_EMBED to Fixtures.YORU_NO_ODORIKO_SEARCH,
        Fixtures.TAKANE_EMBED to Fixtures.TAKANE_SEARCH,
        Fixtures.BLUE_AMBER_EMBED to Fixtures.BLUE_AMBER_SEARCH,
        Fixtures.AO_TO_NATSU_EMBED to Fixtures.AO_TO_NATSU_SEARCH,
      )
    for ((embed, search) in cases) {
      val meta = spotify(embed)
      val outcome = MatchScorer.best(meta, candidates(search))
      println("=== ${meta.title} — ${meta.artistLine} — ${meta.durationSeconds}s  confident=${outcome.confident}")
      outcome.ranked.take(4).forEach {
        println("   %-12s %-46s %s".format(it.song.videoId, it.song.title.take(44), it.explain()))
      }
    }
  }
}
