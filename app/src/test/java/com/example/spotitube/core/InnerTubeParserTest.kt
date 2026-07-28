package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeParserTest {

  @Test
  fun `parses the live songs shelf`() {
    val songs = InnerTubeParser.parseSongs(Fixtures.read(Fixtures.RICK_ASTLEY_SEARCH_JSON))
    assertEquals(20, songs.size)

    val first = songs[0]
    assertEquals("lYBUbBu4W08", first.videoId)
    assertEquals("Never Gonna Give You Up", first.title)
    assertEquals(listOf("Rick Astley"), first.artists)
    assertEquals("Whenever You Need Somebody", first.album)
    assertEquals(214, first.durationSeconds)
    assertTrue(first.hasAlbumLink)
    assertTrue(first.hasArtistChannel)
    assertEquals(0, first.position)
    assertEquals("https://music.youtube.com/watch?v=lYBUbBu4W08", first.watchUrl)
  }

  @Test
  fun `a malformed videoId yields no watch url at all`() {
    // YouTube Music claims music.youtube.com with a path pattern of `.*`, so a bad watch URL opens
    // an indeterminate screen instead of throwing ActivityNotFoundException. The guard therefore has
    // to be here, and these assertions observe the ABSENCE of a URL rather than re-testing a regex.
    val bad =
      listOf(
        "",
        "short",
        "waytoolongvideoid",
        "eleven!chars",
        "lYBUbBu4W0",
        "аYBUbBu4W08", // leading Cyrillic а, same shape as Latin a
      )
    for (id in bad) {
      val song = YouTubeSong(videoId = id, title = "x", artists = listOf("y"))
      assertNull("expected no watch url for '$id'", song.watchUrl)
    }
  }

  @Test
  fun `real video ids still produce a watch url on the music host`() {
    // The inverse guard: over-strict validation would silently degrade every play to a search.
    for (id in listOf("lYBUbBu4W08", "r7Rn4ryE_w8", "dQw4w9WgXcQ", "_-aBcDeFgH1")) {
      val url = YouTubeSong(videoId = id, title = "x", artists = listOf("y")).watchUrl
      assertEquals("https://music.youtube.com/watch?v=$id", url)
    }
  }

  @Test
  fun `every launch url we can emit is on the music host`() {
    // www.youtube.com is NOT claimed by YouTube Music: measured on-device, an explicit intent for it
    // resolves to NO ACTIVITY, so a stray host would silently land in the YouTube app or a browser.
    val urls =
      listOf(
        YouTubeSong(videoId = "lYBUbBu4W08", title = "x", artists = listOf("y")).watchUrl!!,
        YouTubeMusic.searchUrl("rick astley never gonna give you up"),
        SpotitubeResolver.youTubeMusicSearchUrl("post malone sunflower"),
      )
    for (url in urls) {
      assertTrue("not on the music host: $url", url.startsWith("https://music.youtube.com/"))
    }
  }

  @Test
  fun `parses multi artist rows structurally rather than by splitting bullets`() {
    val songs = InnerTubeParser.parseSongs(Fixtures.read(Fixtures.SUNFLOWER_SEARCH_JSON))
    val first = songs[0]
    assertEquals("r7Rn4ryE_w8", first.videoId)
    assertEquals("Sunflower (Spider-Man: Into the Spider-Verse)", first.title)
    assertEquals(listOf("Post Malone", "Swae Lee"), first.artists)
    assertEquals(159, first.durationSeconds)

    // Row 2 has four artists joined with commas and an ampersand in the rendered text.
    val remix = songs.first { it.videoId == "58dyibIUscg" }
    assertEquals(listOf("Post Malone", "Swae Lee", "Nicky Jam", "Prince Royce"), remix.artists)
  }

  @Test
  fun `every row has a video id and a title`() {
    for (name in listOf(Fixtures.RICK_ASTLEY_SEARCH_JSON, Fixtures.SUNFLOWER_SEARCH_JSON)) {
      for (song in InnerTubeParser.parseSongs(Fixtures.read(name))) {
        assertTrue("$name: blank videoId", song.videoId.isNotBlank())
        assertTrue("$name: blank title", song.title.isNotBlank())
        assertNotNull("$name/${song.videoId}: no duration", song.durationSeconds)
      }
    }
  }

  @Test
  fun `positions follow the shelf order`() {
    val songs = InnerTubeParser.parseSongs(Fixtures.read(Fixtures.RICK_ASTLEY_SEARCH_JSON))
    assertEquals(songs.indices.toList(), songs.map { it.position })
  }

  @Test
  fun `truncated and malformed bodies degrade gracefully`() {
    val full = Fixtures.read(Fixtures.RICK_ASTLEY_SEARCH_JSON)
    val inputs =
      listOf(
        null,
        "",
        "   ",
        "not json at all",
        "{",
        "[]",
        "{}",
        """{"contents":null}""",
        """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[]}}}""",
        full.substring(0, full.length / 3),
        full.substring(0, 200),
      )
    for (input in inputs) {
      val result = InnerTubeParser.parseSongs(input)
      assertNotNull(result)
      assertTrue("must not invent rows for: ${input?.take(40)}", result.size <= 20)
    }
  }

  @Test
  fun `unexpected shape still finds items via the recursive fallback`() {
    // Same rows, but wrapped in an envelope the documented path cannot navigate.
    val full = Fixtures.read(Fixtures.RICK_ASTLEY_SEARCH_JSON)
    val reshaped = """{"someFutureEnvelope":{"nested":[$full]}}"""
    val songs = InnerTubeParser.parseSongs(reshaped)
    assertEquals(20, songs.size)
    assertTrue(songs.any { it.videoId == "lYBUbBu4W08" })
  }

  @Test
  fun `missing navigation endpoints fall back to splitting the detail line`() {
    // Realistic degradation: YouTube keeps the rows but drops the structured browse endpoints,
    // so artists/album/duration have to come from the rendered "A • B • 3:34" line instead.
    val stripped = Fixtures.read(Fixtures.RICK_ASTLEY_SEARCH_JSON).replace("\"navigationEndpoint\"", "\"navEndpointV2\"")
    val songs = InnerTubeParser.parseSongs(stripped)
    assertEquals(20, songs.size)

    val first = songs[0]
    assertEquals("lYBUbBu4W08", first.videoId)
    assertEquals("Never Gonna Give You Up", first.title)
    assertEquals(listOf("Rick Astley"), first.artists)
    assertEquals("Whenever You Need Somebody", first.album)
    assertEquals(214, first.durationSeconds)
    // The structural hints are gone, so the "official upload" bonuses must not be claimed.
    assertFalse(first.hasArtistChannel)
    assertFalse(first.hasAlbumLink)
  }

  @Test
  fun `ampersand joined artists in the rendered line are split`() {
    val json =
      shelf(
        """
        {"musicResponsiveListItemRenderer":{
          "playlistItemData":{"videoId":"abc1234567"},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Some Song"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
              {"text":"Artist One & Artist Two \u2022 Some Album \u2022 4:05"}]}}}
          ]}}
        """
      )
    val songs = InnerTubeParser.parseSongs(json)
    assertEquals(1, songs.size)
    assertEquals("abc1234567", songs[0].videoId)
    assertEquals("Some Song", songs[0].title)
    assertEquals(listOf("Artist One", "Artist Two"), songs[0].artists)
    assertEquals("Some Album", songs[0].album)
    assertEquals(245, songs[0].durationSeconds)
  }

  @Test
  fun `rows without a video id are skipped instead of crashing`() {
    val json =
      shelf(
        """
        {"musicResponsiveListItemRenderer":{"flexColumns":[]}},
        {"messageRenderer":{"text":"no results"}},
        {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"keepme1234"},
          "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Kept"}]}}}]}}
        """
      )
    val songs = InnerTubeParser.parseSongs(json)
    assertEquals(listOf("keepme1234"), songs.map { it.videoId })
  }

  /**
   * Wraps shelf rows in the documented envelope. A control assertion guarantees the envelope itself
   * is valid, so a malformed literal can never make a "degrades gracefully" test pass vacuously.
   */
  private fun shelf(items: String): String =
    """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":""" +
      """{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},""" +
      """"contents":[$items]}}]}}}}]}}}"""

  @Test
  fun `the shelf envelope used by these tests is itself valid`() {
    val control =
      shelf(
        """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"control123"},
           "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Control"}]}}}]}}"""
      )
    assertEquals(listOf("control123"), InnerTubeParser.parseSongs(control).map { it.videoId })
  }
}
