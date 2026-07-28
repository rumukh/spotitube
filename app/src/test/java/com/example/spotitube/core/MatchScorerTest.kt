package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchScorerTest {

  private val rickAstley =
    SpotifyTrackMeta(
      title = "Never Gonna Give You Up",
      artists = listOf("Rick Astley"),
      album = "Whenever You Need Somebody",
      durationSeconds = 214,
      releaseYear = 1987,
    )

  private val sunflower =
    SpotifyTrackMeta(
      title = "Sunflower - Spider-Man: Into the Spider-Verse",
      artists = listOf("Post Malone", "Swae Lee"),
      durationSeconds = 158,
      releaseYear = 2018,
    )

  /** Same track, with the album the canonical page reports. */
  private val sunflowerWithAlbum =
    sunflower.copy(
      album = "Spider-Man: Into the Spider-Verse (Soundtrack From & Inspired by the Motion Picture)"
    )

  private fun rickCandidates() = InnerTubeParser.parseSongs(Fixtures.read(Fixtures.RICK_ASTLEY_SEARCH_JSON))

  private fun sunflowerCandidates() = InnerTubeParser.parseSongs(Fixtures.read(Fixtures.SUNFLOWER_SEARCH_JSON))

  private fun song(
    videoId: String = "vid",
    title: String,
    artists: List<String>,
    duration: Int?,
    album: String? = null,
    position: Int = 0,
    isExplicit: Boolean? = null,
  ) = YouTubeSong(
    videoId,
    title,
    artists,
    album,
    duration,
    hasAlbumLink = true,
    hasArtistChannel = true,
    isExplicit = isExplicit,
    position = position,
  )

  // --- album evidence -------------------------------------------------------------------------

  @Test
  fun `album agreement decides between two uploads of the same recording`() {
    // The real Sunflower result set contains both: a soundtrack upload at 2:39 (the album Spotify
    // names) and a Hollywood's Bleeding upload at 2:38 (an exact duration match). Ranking by
    // smallest duration delta picks the wrong release.
    val soundtrack =
      song(
        videoId = "r7Rn4ryE_w8",
        title = "Sunflower (Spider-Man: Into the Spider-Verse)",
        artists = listOf("Post Malone", "Swae Lee"),
        duration = 159,
        album = "Spider-Man: Into the Spider-Verse (Soundtrack From & Inspired by the Motion Picture)",
        position = 1,
      )
    val otherRelease =
      song(
        videoId = "z9VMaLxg9Ok",
        title = "Sunflower (Spider-Man: Into the Spider-Verse)",
        artists = listOf("Post Malone", "Swae Lee"),
        duration = 158,
        album = "Hollywood's Bleeding",
        position = 0,
      )

    // YouTube's own ordering is deliberately reversed here and the exact duration sits on the wrong
    // album, so only album evidence can produce the right answer.
    val outcome = MatchScorer.best(sunflowerWithAlbum, listOf(otherRelease, soundtrack))
    assertTrue(outcome.confident)
    assertEquals("r7Rn4ryE_w8", outcome.best!!.song.videoId)
  }

  @Test
  fun `a mismatched album never rejects an otherwise good match`() {
    // The same recording is legitimately reissued on compilations, so album disagreement is only an
    // absence of corroboration, never a veto.
    val compilation =
      song(
        title = "Never Gonna Give You Up",
        artists = listOf("Rick Astley"),
        duration = 214,
        album = "Greatest Hits of the 80s",
      )
    val match = MatchScorer.score(rickAstley, compilation)
    assertFalse(match.explain(), match.vetoed)
    assertTrue(match.score >= MatchScorer.CONFIDENCE_THRESHOLD)
  }

  // --- explicit / clean -----------------------------------------------------------------------

  @Test
  fun `explicit badges are read from the live search response`() {
    val songs = InnerTubeParser.parseSongs(Fixtures.read(Fixtures.EXPLICIT_SEARCH_JSON))
    assertEquals(true, songs.first { it.videoId == "AaxFIY-cWH0" }.isExplicit)
    // No badges array at all means "not stated", not "clean".
    assertEquals(null, songs.first { it.videoId == "Moye-xEc_x8" }.isExplicit)
  }

  @Test
  fun `the matching explicitness wins when two candidates are otherwise identical`() {
    val explicitTrack =
      SpotifyTrackMeta(
        title = "rockstar",
        artists = listOf("Post Malone"),
        durationSeconds = 218,
        isExplicit = true,
      )
    val cleanVersion =
      song(videoId = "clean", title = "rockstar", artists = listOf("Post Malone"), duration = 218, isExplicit = false)
    val explicitVersion =
      song(
        videoId = "explicit",
        title = "rockstar",
        artists = listOf("Post Malone"),
        duration = 218,
        position = 1,
        isExplicit = true,
      )
    val outcome = MatchScorer.best(explicitTrack, listOf(cleanVersion, explicitVersion))
    assertEquals("explicit", outcome.best!!.song.videoId)
    assertTrue(outcome.confident)
  }

  @Test
  fun `an explicitness mismatch alone does not block playback`() {
    val explicitTrack =
      SpotifyTrackMeta(title = "rockstar", artists = listOf("Post Malone"), durationSeconds = 218, isExplicit = true)
    val onlyClean = song(title = "rockstar", artists = listOf("Post Malone"), duration = 218, isExplicit = false)
    val outcome = MatchScorer.best(explicitTrack, listOf(onlyClean))
    val best = outcome.best!!
    assertFalse(best.explain(), best.vetoed)
    assertTrue("a clean master is still the right song: ${best.explain()}", outcome.confident)
  }

  // --- unreadable candidate artists -------------------------------------------------------------

  @Test
  fun `a candidate with no readable artist is never auto played`() {
    // Karaoke and third-party rows are exactly the ones that come back without an artist endpoint,
    // and they otherwise look like a perfect match on title and duration.
    val faceless =
      YouTubeSong(
        videoId = "nameless",
        title = "Never Gonna Give You Up",
        artists = emptyList(),
        album = null,
        durationSeconds = 214,
      )
    val match = MatchScorer.score(rickAstley, faceless)
    assertTrue(match.explain(), match.vetoed)
    assertTrue(match.vetoes.contains("artist-unknown"))
    assertEquals(0.0, match.score, 1e-9)
  }

  // --- end-to-end ranking against the real search results ------------------------------------

  @Test
  fun `official upload wins over 19 real distractors`() {
    val outcome = MatchScorer.best(rickAstley, rickCandidates())
    assertTrue("expected confidence, got ${outcome.best?.explain()}", outcome.confident)
    assertEquals("lYBUbBu4W08", outcome.best!!.song.videoId)
  }

  @Test
  fun `multi artist track picks the official soundtrack upload`() {
    val outcome = MatchScorer.best(sunflower, sunflowerCandidates())
    assertTrue("expected confidence, got ${outcome.best?.explain()}", outcome.confident)
    assertEquals("r7Rn4ryE_w8", outcome.best!!.song.videoId)
  }

  @Test
  fun `every distractor in the real result set is beaten by the official upload`() {
    val ranked = MatchScorer.best(rickAstley, rickCandidates()).ranked
    val winner = ranked.first()
    assertEquals("lYBUbBu4W08", winner.song.videoId)
    // The cover, the live re-upload, the instrumental and the compilation-farm uploads must all
    // be vetoed outright, not merely out-ranked.
    val mustBeVetoed = setOf("w9_RhI5xCXs", "_uK77EalGq8", "0k7BvzQRrOI", "4OyWl83yeqM", "131wf0e6ACk", "pDWrOMKsnyY")
    for (videoId in mustBeVetoed) {
      val entry = ranked.first { it.song.videoId == videoId }
      assertTrue("$videoId should be vetoed but scored ${entry.explain()}", entry.vetoed)
    }
  }

  @Test
  fun `karaoke and string quartet cover are vetoed even with a matching title`() {
    val ranked = MatchScorer.best(sunflower, sunflowerCandidates()).ranked
    val karaoke = ranked.first { it.song.videoId == "zV99uC2L0s8" }
    assertTrue("karaoke should be vetoed: ${karaoke.explain()}", karaoke.vetoed)

    // This one's title is character-for-character identical to Spotify's — only the artist and
    // duration give it away, which is exactly why title similarity alone is not enough.
    val quartet = ranked.first { it.song.videoId == "JopBvW4S5QY" }
    assertEquals(sunflower.title, quartet.song.title)
    assertTrue("string quartet cover should be vetoed: ${quartet.explain()}", quartet.vetoed)
  }

  // --- individual signals ---------------------------------------------------------------------

  @Test
  fun `exact match scores at the top of the range`() {
    val match =
      MatchScorer.score(
        rickAstley,
        song(title = "Never Gonna Give You Up", artists = listOf("Rick Astley"), duration = 214),
      )
    assertFalse(match.vetoed)
    assertTrue("score was ${match.score}", match.score > 0.95)
  }

  @Test
  fun `remaster is accepted`() {
    val match =
      MatchScorer.score(
        rickAstley,
        song(title = "Never Gonna Give You Up (2022 Remaster)", artists = listOf("Rick Astley"), duration = 214),
      )
    assertFalse("remaster must not be vetoed: ${match.explain()}", match.vetoed)
    assertTrue("score was ${match.score}", match.score >= MatchScorer.CONFIDENCE_THRESHOLD)
  }

  @Test
  fun `feat suffix and official video noise are accepted`() {
    for (title in
      listOf(
        "Never Gonna Give You Up (Official Video)",
        "Never Gonna Give You Up (feat. Nobody)",
        "Never Gonna Give You Up - Remastered",
        "Never Gonna Give You Up [Official Audio]",
      )) {
      val match = MatchScorer.score(rickAstley, song(title = title, artists = listOf("Rick Astley"), duration = 214))
      assertFalse("$title must not be vetoed: ${match.explain()}", match.vetoed)
      assertTrue("$title scored ${match.score}", match.score >= MatchScorer.CONFIDENCE_THRESHOLD)
    }
  }

  @Test
  fun `variant renditions are rejected`() {
    val variants =
      listOf(
        "Never Gonna Give You Up (Cover)",
        "Never Gonna Give You Up (Karaoke Version)",
        "Never Gonna Give You Up (Instrumental)",
        "Never Gonna Give You Up (Live at Wembley)",
        "Never Gonna Give You Up (Remix)",
        "Never Gonna Give You Up (Sped Up)",
        "Never Gonna Give You Up (Slowed + Reverb)",
        "Never Gonna Give You Up (Nightcore)",
        "Never Gonna Give You Up (8D Audio)",
        "Never Gonna Give You Up - Tribute to Rick Astley",
      )
    for (title in variants) {
      // Same artist, same duration: only the variant keyword may reject these.
      val match = MatchScorer.score(rickAstley, song(title = title, artists = listOf("Rick Astley"), duration = 214))
      assertTrue("$title should be vetoed but was ${match.explain()}", match.vetoed)
      assertEquals(0.0, match.score, 1e-9)
    }
  }

  @Test
  fun `a variant word that is part of the real title is not a veto`() {
    val liveAndLetDie =
      SpotifyTrackMeta(title = "Live and Let Die", artists = listOf("Wings"), durationSeconds = 191)
    val match =
      MatchScorer.score(liveAndLetDie, song(title = "Live and Let Die", artists = listOf("Wings"), duration = 191))
    assertFalse("must not veto on the word 'live' in the real title: ${match.explain()}", match.vetoed)
    assertTrue(match.score >= MatchScorer.CONFIDENCE_THRESHOLD)
  }

  @Test
  fun `wrong duration is rejected`() {
    val match =
      MatchScorer.score(
        rickAstley,
        song(title = "Never Gonna Give You Up", artists = listOf("Rick Astley"), duration = 263),
      )
    assertTrue(match.vetoed)
    assertTrue(match.vetoes.any { it.startsWith("duration") })
  }

  @Test
  fun `duration just inside the tolerance is still accepted`() {
    val match =
      MatchScorer.score(
        rickAstley,
        song(title = "Never Gonna Give You Up", artists = listOf("Rick Astley"), duration = 214 + 4),
      )
    assertFalse(match.explain(), match.vetoed)
    assertTrue(match.score >= MatchScorer.CONFIDENCE_THRESHOLD)
  }

  @Test
  fun `wrong artist is rejected`() {
    val match =
      MatchScorer.score(
        rickAstley,
        song(title = "Never Gonna Give You Up", artists = listOf("Midnight Arena"), duration = 214),
      )
    assertTrue(match.explain(), match.vetoed)
    assertTrue(match.vetoes.contains("artist"))
  }

  @Test
  fun `sharing one name token with the real artist is not enough`() {
    // "Rick Roll" is a real compilation-farm artist in the live results; it shares "Rick".
    val match =
      MatchScorer.score(
        rickAstley,
        song(title = "Never Gonna Give You Up", artists = listOf("Rick Roll"), duration = 217),
      )
    assertTrue(match.explain(), match.vetoed)
  }

  @Test
  fun `a different song by the right artist is rejected`() {
    val match =
      MatchScorer.score(
        rickAstley,
        song(title = "Together Forever", artists = listOf("Rick Astley"), duration = 206),
      )
    assertTrue(match.explain(), match.vetoed)
  }

  @Test
  fun `artist scoring requires a whole name match`() {
    assertTrue(MatchScorer.artistScore(listOf("Rick Astley"), listOf("Rick Astley")) > 0.95)
    assertTrue(MatchScorer.artistScore(listOf("Post Malone", "Swae Lee"), listOf("Post Malone")) >= 0.6)
    assertTrue(MatchScorer.artistScore(listOf("Rick Astley"), listOf("Rick Roll")) < 0.25)
    assertEquals(0.0, MatchScorer.artistScore(listOf("Rick Astley"), listOf("Midnight Arena")), 1e-9)
    // Diacritics and casing must not matter.
    assertTrue(MatchScorer.artistScore(listOf("Beyoncé"), listOf("BEYONCE")) > 0.95)
  }

  @Test
  fun `title only metadata never auto plays however well a candidate scores`() {
    // This is what the oEmbed fallback yields when Spotify serves the JavaScript shell with no
    // Open Graph tags: a title and nothing else. A title cannot tell an original from a cover.
    val titleOnly = SpotifyTrackMeta(title = "Never Gonna Give You Up", artists = emptyList())
    val outcome = MatchScorer.best(titleOnly, rickCandidates())

    assertTrue(outcome.insufficientEvidence)
    assertFalse("must not auto-play on a title alone: ${outcome.best?.explain()}", outcome.confident)
    // The ranking itself is still useful, it just is not trusted enough to act on.
    assertEquals("lYBUbBu4W08", outcome.best!!.song.videoId)
  }

  @Test
  fun `an artist alone or a duration alone is enough evidence`() {
    val artistOnly = SpotifyTrackMeta(title = "Never Gonna Give You Up", artists = listOf("Rick Astley"))
    assertTrue(MatchScorer.best(artistOnly, rickCandidates()).confident)

    val durationOnly =
      SpotifyTrackMeta(title = "Never Gonna Give You Up", artists = emptyList(), durationSeconds = 214)
    assertTrue(MatchScorer.best(durationOnly, rickCandidates()).confident)
  }

  @Test
  fun `non latin tracks can be matched`() {
    val japanese =
      SpotifyTrackMeta(title = "夜に駆ける", artists = listOf("YOASOBI"), durationSeconds = 261)
    val candidates =
      listOf(
        song(videoId = "good", title = "夜に駆ける", artists = listOf("YOASOBI"), duration = 261),
        song(videoId = "cover", title = "夜に駆ける (Cover)", artists = listOf("Someone Else"), duration = 258, position = 1),
      )
    val outcome = MatchScorer.best(japanese, candidates)
    assertTrue("non-Latin track should match: ${outcome.best?.explain()}", outcome.confident)
    assertEquals("good", outcome.best!!.song.videoId)
  }

  @Test
  fun `empty candidate list yields no match`() {
    val outcome = MatchScorer.best(rickAstley, emptyList())
    assertFalse(outcome.confident)
    assertEquals(null, outcome.best)
  }

  @Test
  fun `unknown durations do not crash and stay below auto-play confidence when weak`() {
    val match =
      MatchScorer.score(
        SpotifyTrackMeta(title = "Some Song", artists = listOf("Some Artist"), durationSeconds = null),
        song(title = "Some Song", artists = listOf("Some Artist"), duration = null),
      )
    assertFalse(match.vetoed)
    assertTrue(match.score > MatchScorer.CONFIDENCE_THRESHOLD)
  }

  /** Not an assertion — prints the calibration table that the threshold was chosen from. */
  @Test
  fun `print ranking for calibration`() {
    for ((label, meta, candidates) in
      listOf(
        Triple("rick astley", rickAstley, rickCandidates()),
        Triple("sunflower", sunflower, sunflowerCandidates()),
      )) {
      println("=== $label (threshold ${MatchScorer.CONFIDENCE_THRESHOLD}) ===")
      MatchScorer.best(meta, candidates).ranked.forEach {
        println("  %-12s %-58s %s".format(it.song.videoId, it.song.title.take(56), it.explain()))
      }
    }
  }
}
