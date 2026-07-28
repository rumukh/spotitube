package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyEmbedParserTest {

  @Test
  fun `single artist embed payload`() {
    val meta = SpotifyEmbedParser.parse(Fixtures.read(Fixtures.RICK_ASTLEY_EMBED_HTML))!!
    assertEquals("Never Gonna Give You Up", meta.title)
    assertEquals(listOf("Rick Astley"), meta.artists)
    assertEquals(213573, meta.durationMillis)
    assertEquals(214, meta.durationSeconds)
    assertEquals(1987, meta.releaseYear)
    assertEquals(false, meta.isExplicit)
    // The embed payload carries no album; that is what the Open Graph merge is for.
    assertNull(meta.album)
  }

  @Test
  fun `multi artist embed payload gives a clean array with no separator guessing`() {
    val meta = SpotifyEmbedParser.parse(Fixtures.read(Fixtures.SUNFLOWER_EMBED_HTML))!!
    assertEquals("Sunflower - Spider-Man: Into the Spider-Verse", meta.title)
    assertEquals(listOf("Post Malone", "Swae Lee"), meta.artists)
    assertEquals(158040, meta.durationMillis)
    assertEquals(158, meta.durationSeconds)
    assertEquals(2018, meta.releaseYear)
    assertEquals(false, meta.isExplicit)
  }

  @Test
  fun `merging embed with open graph fills in the album`() {
    val embed = SpotifyEmbedParser.parse(Fixtures.read(Fixtures.SUNFLOWER_EMBED_HTML))!!
    val openGraph = SpotifyMetaParser.parse(Fixtures.read(Fixtures.SUNFLOWER_HTML))!!
    val merged = embed.mergedWith(openGraph)

    // Structured fields still come from the embed…
    assertEquals(listOf("Post Malone", "Swae Lee"), merged.artists)
    assertEquals(158040, merged.durationMillis)
    assertEquals(false, merged.isExplicit)
    // …and the album, which only the canonical page has, is filled in.
    assertTrue(merged.album!!.contains("Into the Spider-Verse"))
  }

  @Test
  fun `merging tolerates either side being absent`() {
    val embed = SpotifyEmbedParser.parse(Fixtures.read(Fixtures.RICK_ASTLEY_EMBED_HTML))!!
    assertEquals(embed, embed.mergedWith(null))

    val openGraph = SpotifyMetaParser.parse(Fixtures.read(Fixtures.RICK_ASTLEY_HTML))!!
    val merged = embed.mergedWith(openGraph)
    assertEquals("Whenever You Need Somebody", merged.album)
    assertEquals(213573, merged.durationMillis)
  }

  @Test
  fun `malformed and missing payloads degrade to null`() {
    val inputs =
      listOf(
        null,
        "",
        "   ",
        "<html><body>no script here</body></html>",
        """<script id="__NEXT_DATA__" type="application/json">not json</script>""",
        """<script id="__NEXT_DATA__" type="application/json">{}</script>""",
        """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{}}}</script>""",
      )
    for (input in inputs) {
      assertNull("expected null for: ${input?.take(50)}", SpotifyEmbedParser.parse(input))
    }
  }

  @Test
  fun `a moved entity is still found by the recursive fallback`() {
    val json =
      """
      <script id="__NEXT_DATA__" type="application/json">
      {"props":{"somethingNew":{"deeper":[{"entity":
        {"type":"track","name":"Moved Song","duration":180000,"isExplicit":true,
         "artists":[{"name":"Someone"}]}}]}}}
      </script>
      """.trimIndent()
    val meta = SpotifyEmbedParser.parse(json)
    assertNotNull(meta)
    assertEquals("Moved Song", meta!!.title)
    assertEquals(listOf("Someone"), meta.artists)
    assertEquals(180, meta.durationSeconds)
    assertEquals(true, meta.isExplicit)
  }
}
