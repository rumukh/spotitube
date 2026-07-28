package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMetaParserTest {

  @Test
  fun `single artist track from the live page`() {
    val meta = SpotifyMetaParser.parse(Fixtures.read(Fixtures.RICK_ASTLEY_HTML))!!
    assertEquals("Never Gonna Give You Up", meta.title)
    assertEquals(listOf("Rick Astley"), meta.artists)
    assertEquals("Whenever You Need Somebody", meta.album)
    assertEquals(214, meta.durationSeconds)
    assertEquals(1987, meta.releaseYear)
    assertEquals("music.song", meta.ogType)
    assertEquals("Rick Astley Never Gonna Give You Up", meta.searchQuery)
  }

  @Test
  fun `multi artist track and html escaping from the live page`() {
    val meta = SpotifyMetaParser.parse(Fixtures.read(Fixtures.SUNFLOWER_HTML))!!
    assertEquals("Sunflower - Spider-Man: Into the Spider-Verse", meta.title)
    assertEquals(listOf("Post Malone", "Swae Lee"), meta.artists)
    assertEquals(158, meta.durationSeconds)
    assertEquals(2018, meta.releaseYear)
    // The raw HTML carries `Soundtrack From &amp; Inspired by`; the parser must unescape it.
    val album = meta.album!!
    assertTrue("album was: $album", album.contains("From & Inspired by"))
    assertTrue("album still contains an entity: $album", !album.contains("&amp;"))
  }

  @Test
  fun `artist separator other than a plain comma is handled`() {
    // Real capture in which Spotify returned U+060C ARABIC COMMA between the two artists.
    val html = Fixtures.read(Fixtures.SUNFLOWER_INTL_COMMA_HTML)
    assertTrue("fixture should contain U+060C", html.contains('\u060C'))
    val meta = SpotifyMetaParser.parse(html)!!
    assertEquals(listOf("Post Malone", "Swae Lee"), meta.artists)
  }

  @Test
  fun `album page is recognised as such`() {
    val meta = SpotifyMetaParser.parse(Fixtures.read(Fixtures.ALBUM_HTML))!!
    assertEquals("music.album", meta.ogType)
    assertNull("albums carry no music:duration", meta.durationSeconds)
  }

  @Test
  fun `attribute order does not matter`() {
    val html =
      """
      <meta content="Bohemian Rhapsody" property="og:title"/>
      <meta content='Queen · A Night at the Opera · Song · 1975' property='og:description'/>
      <meta content="354" name="music:duration"/>
      <meta name="music:musician_description" content="Queen"/>
      """
    val meta = SpotifyMetaParser.parse(html)!!
    assertEquals("Bohemian Rhapsody", meta.title)
    assertEquals(listOf("Queen"), meta.artists)
    assertEquals(354, meta.durationSeconds)
  }

  @Test
  fun `falls back to og description when musician description is missing`() {
    val html =
      """
      <meta property="og:title" content="Sound of Silence"/>
      <meta property="og:description" content="Simon &amp; Garfunkel · Sounds of Silence · Song · 1966"/>
      """
    val meta = SpotifyMetaParser.parse(html)!!
    // `&` must survive as one artist name, not be split into two.
    assertEquals(listOf("Simon & Garfunkel"), meta.artists)
    assertEquals("Sounds of Silence", meta.album)
    assertEquals(1966, meta.releaseYear)
    assertNull(meta.durationSeconds)
  }

  @Test
  fun `missing tags degrade instead of throwing`() {
    assertNull(SpotifyMetaParser.parse(null))
    assertNull(SpotifyMetaParser.parse(""))
    assertNull(SpotifyMetaParser.parse("<html><head><title>Spotify</title></head><body/></html>"))

    // Title only: still usable, just with no artist to search with.
    val titleOnly = SpotifyMetaParser.parse("""<meta property="og:title" content="Untitled"/>""")
    assertNotNull(titleOnly)
    assertEquals(emptyList<String>(), titleOnly!!.artists)
    assertEquals("Untitled", titleOnly.searchQuery)
  }

  @Test
  fun `html entities are unescaped`() {
    assertEquals("Rock & Roll", SpotifyMetaParser.unescapeHtml("Rock &amp; Roll"))
    assertEquals("\"Heroes\"", SpotifyMetaParser.unescapeHtml("&quot;Heroes&quot;"))
    assertEquals("Don't", SpotifyMetaParser.unescapeHtml("Don&#39;t"))
    assertEquals("Don't", SpotifyMetaParser.unescapeHtml("Don&#x27;t"))
    assertEquals("a & b & c", SpotifyMetaParser.unescapeHtml("a &amp; b &amp; c"))
    // Unknown or malformed entities are passed through untouched rather than eaten.
    assertEquals("100% & rising", SpotifyMetaParser.unescapeHtml("100% &amp; rising"))
    assertEquals("A&B", SpotifyMetaParser.unescapeHtml("A&B"))
    assertEquals("&notarealentity;", SpotifyMetaParser.unescapeHtml("&notarealentity;"))
  }
}
