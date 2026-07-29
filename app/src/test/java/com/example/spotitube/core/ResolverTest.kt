package com.example.spotitube.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverTest {

  private class FakeSpotify(
    private val pages: Map<String, String> = emptyMap(),
    private val shortLinkTarget: String? = null,
  ) : SpotifyMetadataSource {
    var trackFetches = 0

    override suspend fun expandShortLink(link: SpotifyLink): SpotifyLink? =
      shortLinkTarget?.let { SpotifyLinkParser.parse(it) }

    override suspend fun fetchTrack(link: SpotifyLink): SpotifyTrackMeta? {
      trackFetches++
      return pages[link.canonicalUrl]?.let { SpotifyMetaParser.parse(it) }
    }
  }

  private class FakeYouTube(private val json: String? = null, private val boom: Boolean = false) : YouTubeMusicSearch {
    var queries = mutableListOf<String>()

    override suspend fun searchSongs(query: String): List<YouTubeSong> {
      queries += query
      if (boom) throw java.io.IOException("network down")
      return InnerTubeParser.parseSongs(json)
    }
  }

  private val rickUrl = "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8"

  /** Throws [CancellationException] from whichever dependency the test names. */
  private class CancellingSpotify(
    private val onExpand: Boolean = false,
    private val onFetch: Boolean = false,
    private val pages: Map<String, String> = emptyMap(),
  ) : SpotifyMetadataSource {
    override suspend fun expandShortLink(link: SpotifyLink): SpotifyLink? {
      if (onExpand) throw CancellationException("superseded")
      return null
    }

    override suspend fun fetchTrack(link: SpotifyLink): SpotifyTrackMeta? {
      if (onFetch) throw CancellationException("superseded")
      return pages[link.canonicalUrl]?.let { SpotifyMetaParser.parse(it) }
    }
  }

  private class CancellingYouTube : YouTubeMusicSearch {
    override suspend fun searchSongs(query: String): List<YouTubeSong> =
      throw CancellationException("superseded")
  }

  private fun rickSpotify() = FakeSpotify(mapOf(rickUrl to Fixtures.read(Fixtures.RICK_ASTLEY_HTML)))

  private fun rickYouTube() = FakeYouTube(Fixtures.read(Fixtures.RICK_ASTLEY_SEARCH_JSON))

  @Test
  fun `track link resolves end to end to the right video`() = runTest {
    val youTube = rickYouTube()
    val outcome = SpotitubeResolver(rickSpotify(), youTube).resolve(rickUrl)
    val play = outcome as ResolveOutcome.PlayOnYouTubeMusic
    assertEquals("lYBUbBu4W08", play.videoId)
    assertEquals("https://music.youtube.com/watch?v=lYBUbBu4W08", play.url)
    assertEquals(listOf("Rick Astley Never Gonna Give You Up"), youTube.queries)
    // Confidence is decided on CORE, so that is what must clear the bar. `rank` is reported
    // alongside it and may legitimately sit above or below — it carries presentation bonuses and
    // an explicit-mismatch penalty — which is exactly why one field called `score` could not stand
    // in for both.
    assertTrue(play.diagnostics.format(), play.core >= MatchScorer.CONFIDENCE_THRESHOLD)
    assertEquals(MatchScorer.CONFIDENCE_THRESHOLD, play.diagnostics.threshold, 1e-9)
  }

  @Test
  fun `track link buried in shared text still resolves`() = runTest {
    val text = "yo check this \uD83C\uDFB5 $rickUrl?si=6f2a1c9d4b8e4f01 lol"
    val outcome = SpotitubeResolver(rickSpotify(), rickYouTube()).resolve(text)
    assertEquals("lYBUbBu4W08", (outcome as ResolveOutcome.PlayOnYouTubeMusic).videoId)
  }

  @Test
  fun `spotify uri in shared text still resolves`() = runTest {
    val outcome =
      SpotitubeResolver(rickSpotify(), rickYouTube()).resolve("spotify:track:4PTG3Z6ehGkBFwjybzWkR8")
    assertEquals("lYBUbBu4W08", (outcome as ResolveOutcome.PlayOnYouTubeMusic).videoId)
  }

  @Test
  fun `non track entities bounce back to spotify without touching youtube`() = runTest {
    val cases =
      mapOf(
        "https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6" to SpotifyEntityType.ALBUM,
        "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M" to SpotifyEntityType.PLAYLIST,
        "https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt" to SpotifyEntityType.ARTIST,
        "https://open.spotify.com/show/4rOoJ6Egrf8K2IrywzwOMk" to SpotifyEntityType.SHOW,
        "https://open.spotify.com/episode/512ojhOuo1ktJprKbVcKyQ" to SpotifyEntityType.EPISODE,
      )
    for ((url, type) in cases) {
      val spotify = FakeSpotify()
      val youTube = FakeYouTube()
      val outcome = SpotitubeResolver(spotify, youTube).resolve(url)
      val bounce = outcome as? ResolveOutcome.BounceToSpotify ?: error("expected bounce for $url, got $outcome")
      assertEquals(url, type, bounce.type)
      assertEquals(url, url, bounce.url)
      assertEquals("must not search YouTube for $url", emptyList<String>(), youTube.queries)
      assertEquals("must not fetch metadata for $url", 0, spotify.trackFetches)
    }
  }

  @Test
  fun `locale prefixed album bounces to the canonical spotify url`() = runTest {
    val outcome =
      SpotitubeResolver(FakeSpotify(), FakeYouTube())
        .resolve("https://open.spotify.com/intl-de/album/6eUW0wxWtzkFdaEFsTJto6?si=xyz")
    val bounce = outcome as ResolveOutcome.BounceToSpotify
    assertEquals("https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6", bounce.url)
  }

  @Test
  fun `short link is expanded before deciding`() = runTest {
    val spotify =
      FakeSpotify(pages = mapOf(rickUrl to Fixtures.read(Fixtures.RICK_ASTLEY_HTML)), shortLinkTarget = rickUrl)
    val outcome = SpotitubeResolver(spotify, rickYouTube()).resolve("https://spotify.link/aBcD1234efGh")
    assertEquals("lYBUbBu4W08", (outcome as ResolveOutcome.PlayOnYouTubeMusic).videoId)
  }

  @Test
  fun `short link that expands to an album bounces`() = runTest {
    val album = "https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6"
    val spotify = FakeSpotify(shortLinkTarget = album)
    val youTube = FakeYouTube()
    val outcome = SpotitubeResolver(spotify, youTube).resolve("https://spotify.app.link/aBcD1234efGh")
    assertEquals(album, (outcome as ResolveOutcome.BounceToSpotify).url)
    assertEquals(emptyList<String>(), youTube.queries)
  }

  @Test
  fun `unresolvable short link falls back to spotify rather than guessing`() = runTest {
    val short = "https://spotify.link/aBcD1234efGh"
    val outcome = SpotitubeResolver(FakeSpotify(shortLinkTarget = null), FakeYouTube()).resolve(short)
    val bounce = outcome as ResolveOutcome.BounceToSpotify
    assertEquals(short, bounce.url)
    assertEquals(SpotifyEntityType.SHORT_LINK, bounce.type)
  }

  @Test
  fun `unreadable spotify page bounces instead of searching for nothing`() = runTest {
    val youTube = FakeYouTube()
    val outcome = SpotitubeResolver(FakeSpotify(), youTube).resolve(rickUrl)
    assertEquals(rickUrl, (outcome as ResolveOutcome.BounceToSpotify).url)
    assertEquals(emptyList<String>(), youTube.queries)
  }

  @Test
  fun `no search results opens the youtube music search page`() = runTest {
    val outcome = SpotitubeResolver(rickSpotify(), FakeYouTube(json = "{}")).resolve(rickUrl)
    val search = outcome as ResolveOutcome.SearchOnYouTubeMusic
    assertEquals("Rick Astley Never Gonna Give You Up", search.query)
    assertEquals(
      "https://music.youtube.com/search?q=Rick%20Astley%20Never%20Gonna%20Give%20You%20Up",
      search.url,
    )
  }

  @Test
  fun `search failure degrades to the search page instead of crashing`() = runTest {
    val outcome = SpotitubeResolver(rickSpotify(), FakeYouTube(boom = true)).resolve(rickUrl)
    assertTrue(outcome is ResolveOutcome.SearchOnYouTubeMusic)
  }

  @Test
  fun `only covers in the results means no auto play`() = runTest {
    val covers =
      listOf(
        YouTubeSong("aaa", "Never Gonna Give You Up", listOf("Midnight Arena"), null, 263),
        YouTubeSong("bbb", "Never Gonna Give You Up (Karaoke Version)", listOf("Urock Karaoke"), null, 214),
      )
    val youTube =
      object : YouTubeMusicSearch {
        override suspend fun searchSongs(query: String) = covers
      }
    val outcome = SpotitubeResolver(rickSpotify(), youTube).resolve(rickUrl)
    val search = outcome as ResolveOutcome.SearchOnYouTubeMusic
    assertTrue(search.reason, search.reason.contains("vetoed"))
  }

  @Test
  fun `a search explains which candidate lost and on which sub-score`() = runTest {
    // The defect this closes is a reporting one. A device report could say "best 0.55" but not that
    // the album term was the entire cause, so the reader had to re-derive it from the weights by
    // hand. A bare score cannot tell "we found the wrong song" from "we found the right song and
    // YouTube named the album differently", and those need opposite fixes.
    val covers =
      listOf(
        YouTubeSong("aaa", "Never Gonna Give You Up", listOf("Midnight Arena"), null, 263),
        YouTubeSong("bbb", "Never Gonna Give You Up (Karaoke Version)", listOf("Urock Karaoke"), null, 214),
      )
    val youTube =
      object : YouTubeMusicSearch {
        override suspend fun searchSongs(query: String) = covers
      }
    val search =
      SpotitubeResolver(rickSpotify(), youTube).resolve(rickUrl) as ResolveOutcome.SearchOnYouTubeMusic
    val diagnostic = search.diagnostic ?: error("a losing candidate must be reported")

    // Which candidate lost — by ID, never by name. A losing candidate is by construction a close
    // miss of the user's own query, so logging its title would leak what was shared under the guise
    // of logging YouTube's data.
    assertTrue(diagnostic, diagnostic.contains("bestVideoId="))
    assertFalse("no candidate text may appear: $diagnostic", diagnostic.contains("Never Gonna"))
    assertFalse("no candidate artist may appear: $diagnostic", diagnostic.contains("Midnight Arena"))
    assertFalse("no candidate artist may appear: $diagnostic", diagnostic.contains("Urock"))
    // ...on which sub-score — the part that makes the line diagnostic rather than merely negative...
    for (subScore in listOf("t=", "a=", "al=")) {
      assertTrue("missing $subScore in: $diagnostic", diagnostic.contains(subScore))
    }
    // ...and the bar it had to clear, so the reader need not know the constant.
    assertTrue(diagnostic, diagnostic.contains("threshold=0.70"))
    assertTrue("the veto is the cause here: $diagnostic", diagnostic.contains("VETO["))
  }

  @Test
  fun `a cancelled resolve produces NO outcome, from any dependency`() = runTest {
    // A correctness property of THIS layer, deliberately not delegated to the coordinator.
    //
    // The coordinator discards a stale ticket, so today a misclassified cancellation is invisible.
    // That is defence in depth, not permission to misclassify: it is one reordering of the
    // generation check away from surfacing, and a cancelled coroutine that returns an Outcome has
    // also failed to propagate cancellation, so it keeps doing work nobody wants.
    //
    // The concrete symptom this guards: `runCatching { youTube.searchSongs(query) }` catches
    // CancellationException, turning a superseded request into an empty candidate list and then
    // into SearchOnYouTubeMusic(reason = "no candidates from search") — the exact string measured
    // on device for a track that had scored 0.810 seconds earlier.
    //
    // Asserted for EVERY dependency, not just the one that had the bug, because the next one will
    // be somewhere else.
    val cases: List<Pair<String, SpotitubeResolver>> =
      listOf(
        "expandShortLink" to
          SpotitubeResolver(CancellingSpotify(onExpand = true), rickYouTube()),
        "fetchTrack" to SpotitubeResolver(CancellingSpotify(onFetch = true), rickYouTube()),
        "searchSongs" to
          SpotitubeResolver(
            CancellingSpotify(pages = mapOf(rickUrl to Fixtures.read(Fixtures.RICK_ASTLEY_HTML))),
            CancellingYouTube(),
          ),
      )

    for ((dependency, resolver) in cases) {
      val url = if (dependency == "expandShortLink") "https://spotify.link/abc123XYZ" else rickUrl
      var outcome: ResolveOutcome? = null
      var cancelled = false
      try {
        outcome = resolver.resolve(url)
      } catch (_: CancellationException) {
        cancelled = true
      }
      assertTrue(
        "$dependency: cancellation must propagate, but resolve() returned $outcome — a superseded " +
          "request must produce no outcome at all",
        cancelled,
      )
      assertNull("$dependency: no outcome may be produced", outcome)
    }
  }

  @Test
  fun `a genuine search failure still degrades to the search page`() = runTest {
    // The other side of the same boundary: a REAL failure must still fall back, or the fix above
    // would have turned every network error into a crash.
    val outcome = SpotitubeResolver(rickSpotify(), FakeYouTube(boom = true)).resolve(rickUrl)
    val search = outcome as ResolveOutcome.SearchOnYouTubeMusic
    assertTrue(search.reason, search.reason.contains("no candidates"))
  }

  @Test
  fun `a search with nothing ranked reports no diagnostic rather than inventing one`() = runTest {
    val search =
      SpotitubeResolver(rickSpotify(), FakeYouTube(json = "{}")).resolve(rickUrl)
        as ResolveOutcome.SearchOnYouTubeMusic
    assertNull("nothing ranked, so there is nothing to explain", search.diagnostic)
  }

  @Test
  fun `title only spotify metadata opens search instead of auto playing`() = runTest {
    // Simulates Spotify serving the JavaScript shell: the oEmbed fallback gives a title only.
    val spotify =
      object : SpotifyMetadataSource {
        override suspend fun expandShortLink(link: SpotifyLink): SpotifyLink? = null

        override suspend fun fetchTrack(link: SpotifyLink) =
          SpotifyTrackMeta(title = "Never Gonna Give You Up", artists = emptyList())
      }
    val outcome = SpotitubeResolver(spotify, rickYouTube()).resolve(rickUrl)
    val search = outcome as? ResolveOutcome.SearchOnYouTubeMusic ?: error("expected search, got $outcome")
    assertTrue(search.reason, search.reason.contains("no artists"))
    assertEquals("Never Gonna Give You Up", search.query)
  }

  @Test
  fun `two releases of the same recording play rather than opening search`() = runTest {
    // Spotify page degraded to no album; YouTube offers the soundtrack upload and the Hollywood's
    // Bleeding upload. Same title, same artists — the same recording on two releases — so play it.
    val spotify =
      object : SpotifyMetadataSource {
        override suspend fun expandShortLink(link: SpotifyLink): SpotifyLink? = null

        override suspend fun fetchTrack(link: SpotifyLink) =
          SpotifyTrackMeta(
            title = "Sunflower - Spider-Man: Into the Spider-Verse",
            artists = listOf("Post Malone", "Swae Lee"),
            durationSeconds = 158,
          )
      }
    val youTube =
      object : YouTubeMusicSearch {
        override suspend fun searchSongs(query: String) =
          InnerTubeParser.parseSongs(Fixtures.read(Fixtures.SUNFLOWER_SEARCH_JSON))
      }
    val outcome = SpotitubeResolver(spotify, youTube).resolve("https://open.spotify.com/track/3KkXRkHbMCARz0aVfEt68P")
    val play = outcome as? ResolveOutcome.PlayOnYouTubeMusic ?: error("expected play, got $outcome")
    assertEquals("r7Rn4ryE_w8", play.videoId)
  }

  @Test
  fun `garbage input is unsupported`() = runTest {
    val resolver = SpotitubeResolver(FakeSpotify(), FakeYouTube())
    for (input in listOf(null, "", "just some words", "https://youtube.com/watch?v=abc")) {
      assertTrue("$input", resolver.resolve(input) is ResolveOutcome.Unsupported)
    }
  }

  @Test
  fun `search urls percent encode spaces and specials`() {
    assertEquals(
      "https://music.youtube.com/search?q=Post%20Malone%2C%20Swae%20Lee%20Sunflower",
      SpotitubeResolver.youTubeMusicSearchUrl("Post Malone, Swae Lee Sunflower"),
    )
    assertFalse(SpotitubeResolver.youTubeMusicSearchUrl("a b").contains(' '))
  }
}
