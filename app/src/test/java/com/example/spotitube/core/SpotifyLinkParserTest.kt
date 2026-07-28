package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLinkParserTest {

  private val trackId = "4PTG3Z6ehGkBFwjybzWkR8"

  @Test
  fun `plain track url`() {
    val link = SpotifyLinkParser.parse("https://open.spotify.com/track/$trackId")!!
    assertEquals(SpotifyEntityType.TRACK, link.type)
    assertEquals(trackId, link.id)
    assertEquals("https://open.spotify.com/track/$trackId", link.canonicalUrl)
    assertTrue(link.isTrack)
  }

  @Test
  fun `si tracking parameter is dropped`() {
    val link = SpotifyLinkParser.parse("https://open.spotify.com/track/$trackId?si=8c1a3f0d2b4e4a7c&nd=1")!!
    assertEquals("https://open.spotify.com/track/$trackId", link.canonicalUrl)
  }

  @Test
  fun `locale prefixed url is canonicalised`() {
    val link = SpotifyLinkParser.parse("https://open.spotify.com/intl-de/track/$trackId?si=abc")!!
    assertEquals(SpotifyEntityType.TRACK, link.type)
    assertEquals("https://open.spotify.com/track/$trackId", link.canonicalUrl)
  }

  @Test
  fun `embed url is canonicalised`() {
    val link = SpotifyLinkParser.parse("https://open.spotify.com/embed/track/$trackId")!!
    assertEquals(SpotifyEntityType.TRACK, link.type)
    assertEquals("https://open.spotify.com/track/$trackId", link.canonicalUrl)
  }

  @Test
  fun `spotify uri scheme`() {
    val link = SpotifyLinkParser.parse("spotify:track:$trackId")!!
    assertEquals(SpotifyEntityType.TRACK, link.type)
    assertEquals("https://open.spotify.com/track/$trackId", link.canonicalUrl)
  }

  @Test
  fun `url embedded in shared text`() {
    val shared = "Rob: listen to this!! https://open.spotify.com/track/$trackId?si=xyz123456 \uD83D\uDD25 tell me what you think"
    val link = SpotifyLinkParser.findIn(shared)!!
    assertEquals(trackId, link.id)
    assertEquals(SpotifyEntityType.TRACK, link.type)
  }

  @Test
  fun `url with trailing sentence punctuation`() {
    val link = SpotifyLinkParser.findIn("have you heard https://open.spotify.com/track/$trackId).")!!
    assertEquals(trackId, link.id)
  }

  @Test
  fun `spotify uri embedded in shared text`() {
    val link = SpotifyLinkParser.findIn("Shared from Spotify: spotify:track:$trackId enjoy")!!
    assertEquals(trackId, link.id)
    assertEquals(SpotifyEntityType.TRACK, link.type)
  }

  @Test
  fun `non track entities keep their type`() {
    val cases =
      mapOf(
        "https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6" to SpotifyEntityType.ALBUM,
        "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M" to SpotifyEntityType.PLAYLIST,
        "https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt" to SpotifyEntityType.ARTIST,
        "https://open.spotify.com/show/4rOoJ6Egrf8K2IrywzwOMk" to SpotifyEntityType.SHOW,
        "https://open.spotify.com/episode/512ojhOuo1ktJprKbVcKyQ" to SpotifyEntityType.EPISODE,
      )
    for ((url, expected) in cases) {
      val link = SpotifyLinkParser.parse(url)
      assertEquals(url, expected, link?.type)
      assertTrue(url, link?.isTrack == false)
    }
  }

  @Test
  fun `legacy user playlist url`() {
    val link = SpotifyLinkParser.parse("https://open.spotify.com/user/spotify/playlist/37i9dQZF1DXcBWIGoYBM5M")!!
    assertEquals(SpotifyEntityType.PLAYLIST, link.type)
    assertEquals("37i9dQZF1DXcBWIGoYBM5M", link.id)
  }

  @Test
  fun `short links are flagged for redirect resolution`() {
    for (url in listOf("https://spotify.link/aBcD1234", "https://spotify.app.link/aBcD1234")) {
      val link = SpotifyLinkParser.parse(url)
      assertEquals(url, SpotifyEntityType.SHORT_LINK, link?.type)
      assertNull(url, link?.id)
      assertEquals(url, url, link?.canonicalUrl)
    }
  }

  @Test
  fun `garbage input is rejected`() {
    val junk =
      listOf(
        null,
        "",
        "   ",
        "hello world",
        "https://example.com/track/$trackId",
        "https://open.spotify.com/",
        "https://open.spotify.com/track/",
        "https://open.spotify.com/track/short",
        "https://open.spotify.com/wombat/$trackId",
        "spotify:track:",
        "spotify:wombat:$trackId",
        "ftp://open.spotify.com/track/$trackId",
        // Unicode "letters" are not base62 and must never be echoed back into an outbound URL.
        "https://open.spotify.com/track/ПТG3Z6ehGkBFwjybzWkR8",
        "spotify:track:４PTG3Z6ehGkBFwjybzWkR8x",
        "https://open.spotify.com/track/" + "a".repeat(200),
      )
    for (input in junk) {
      assertNull("expected null for: $input", SpotifyLinkParser.findIn(input))
    }
  }

  @Test
  fun `youtube link is not mistaken for spotify`() {
    assertNull(SpotifyLinkParser.findIn("https://music.youtube.com/watch?v=lYBUbBu4W08"))
  }

  @Test
  fun `http scheme and www prefix are accepted`() {
    assertNotNull(SpotifyLinkParser.parse("http://www.open.spotify.com/track/$trackId"))
  }
}
