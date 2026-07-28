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
      hasAlbumLink: Boolean = false,
      hasArtistChannel: Boolean = false,
    ) = YouTubeSong(
      videoId,
      title,
      artists,
      album,
    duration,
    hasAlbumLink = hasAlbumLink,
    hasArtistChannel = hasArtistChannel,
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
    val outcome = MatchScorer.best(sunflowerWithAlbum, sunflowerCandidates())
    assertTrue("expected confidence, got ${outcome.best?.explain()}", outcome.confident)
    assertEquals("r7Rn4ryE_w8", outcome.best!!.song.videoId)
  }

  @Test
  fun `two releases of the same recording are benign and still play`() {
    // Same live result set, but Spotify's canonical page failed so we have no album to arbitrate.
    // The top two are the soundtrack upload and the Hollywood's Bleeding upload — same title, same
    // artists, durations one second apart. They are the SAME RECORDING on two different releases,
    // so whichever we pick the user hears the song their friend sent. Sending them to a search page
    // over a 0.001 score difference would be caution theatre, not safety.
    val outcome = MatchScorer.best(sunflower, sunflowerCandidates())
    assertFalse("same-recording near-tie must not be ambiguous: ${outcome.best?.explain()}", outcome.ambiguous)
    assertTrue("expected confidence, got ${outcome.best?.explain()}", outcome.confident)
    assertEquals("r7Rn4ryE_w8", outcome.best!!.song.videoId)
  }

  @Test
  fun `a near tie against a different recording opens search`() {
    // The dangerous shape: something scoring within the margin of the winner that is NOT the same
    // recording. Here a same-duration, same-title upload by a different artist. One of them is
    // wrong and we cannot tell which, so hand the user the results instead of guessing.
    val meta =
      SpotifyTrackMeta(title = "Sunflower", artists = listOf("Post Malone", "Swae Lee"), durationSeconds = 158)
    val candidates =
      listOf(
        song(videoId = "aaaaaaaaaaa", title = "Sunflower", artists = listOf("Post Malone", "Swae Lee"), duration = 158),
        song(videoId = "bbbbbbbbbbb", title = "Sunflower", artists = listOf("Post Malone"), duration = 158, position = 1),
      )
    val outcome = MatchScorer.best(meta, candidates)
    assertTrue("expected ambiguity, got ${outcome.ranked.joinToString { it.explain() }}", outcome.ambiguous)
    assertFalse(outcome.confident)
  }

  @Test
  fun `ordering bonuses cannot push a dangerous rival out of the safety cluster`() {
    // The winner carries an album link and an artist channel; the rival carries neither, so its
    // RANK is well below the winner's while its CORE is identical. If the cluster band were
    // measured on rank the rival would fall outside it, the guard would not fire, and we would
    // auto-play one of two candidates that are not the same recording.
    val meta =
      SpotifyTrackMeta(title = "Sunflower", artists = listOf("Post Malone", "Swae Lee"), durationSeconds = 158)
    val candidates =
      listOf(
        song(
          videoId = "aaaaaaaaaaa",
          title = "Sunflower",
          artists = listOf("Post Malone", "Swae Lee"),
          duration = 158,
          hasAlbumLink = true,
          hasArtistChannel = true,
        ),
        song(
          videoId = "bbbbbbbbbbb",
          title = "Sunflower",
          artists = listOf("Swae Lee"),
          duration = 158,
          position = 1,
        ),
      )
    val outcome = MatchScorer.best(meta, candidates)
    val top = outcome.ranked[0]
    val rival = outcome.ranked[1]
    assertTrue("rank gap should exceed the margin: ${top.explain()} vs ${rival.explain()}", top.score - rival.score > 0.02)
    assertTrue("expected ambiguity despite the rank gap", outcome.ambiguous)
    assertFalse(outcome.confident)
  }

  @Test
  fun `a shortened artist name is not a whole artist match`() {
    // "Post" is CONTAINED BY "Post Malone" without being them. Accepting the reverse substring
    // scored it 0.55, which with an exact title reached ~0.79 and auto-played a different artist.
    // "GABBA" and "U2 Tribute Band" are the other direction: unbounded containment, not a name.
    for (impostor in listOf("Post", "Swae", "Malone Post", "Post Lee")) {
      val meta = SpotifyTrackMeta(title = "Sunflower", artists = listOf("Post Malone", "Swae Lee"), durationSeconds = 158)
      val outcome =
        MatchScorer.best(
          meta,
          listOf(song(videoId = "aaaaaaaaaaa", title = "Sunflower", artists = listOf(impostor), duration = 158)),
        )
      assertFalse("'$impostor' must not auto-play: ${outcome.ranked[0].explain()}", outcome.confident)
    }
  }

  @Test
  fun `artist containment is bounded to whole tokens`() {
    // ABBA must not match GABBA: containment has to stop at token boundaries, not any substring.
    val abba = SpotifyTrackMeta(title = "Dancing Queen", artists = listOf("ABBA"), durationSeconds = 230)
    val gabba =
      MatchScorer.best(
        abba,
        listOf(song(videoId = "aaaaaaaaaaa", title = "Dancing Queen", artists = listOf("GABBA"), duration = 230)),
      )
    assertFalse("GABBA is not ABBA: ${gabba.ranked[0].explain()}", gabba.confident)

    // A genuine collapsed credit still matches, which is the case the rule exists for.
    val real =
      MatchScorer.best(
        abba,
        listOf(song(videoId = "bbbbbbbbbbb", title = "Dancing Queen", artists = listOf("ABBA & Friends"), duration = 230)),
      )
    assertTrue("collapsed credit should match: ${real.ranked[0].explain()}", real.confident)
  }

  @Test
  fun `a collapsed artist string still matches`() {
    // The inverse guard: some uploads collapse the credit into one string, and a WHOLE Spotify
    // artist appearing inside it is a genuine match that must keep working.
    val meta = SpotifyTrackMeta(title = "Sunflower", artists = listOf("Post Malone"), durationSeconds = 158)
    val outcome =
      MatchScorer.best(
        meta,
        listOf(
          song(
            videoId = "aaaaaaaaaaa",
            title = "Sunflower",
            artists = listOf("Post Malone & Swae Lee"),
            duration = 158,
          )
        ),
      )
    assertTrue("collapsed credit should match: ${outcome.ranked[0].explain()}", outcome.confident)
  }

  @Test
  fun `a deluxe reissue of the same album is not treated as a different release`() {
    // Rick Astley's top two are the original and the 2022 remaster from the deluxe edition of the
    // same album. Even with no Spotify album that is not a conflict — both are the same song.
    val noAlbum = rickAstley.copy(album = null)
    val outcome = MatchScorer.best(noAlbum, rickCandidates())
    assertFalse("deluxe reissue must not read as ambiguous: ${outcome.best?.explain()}", outcome.ambiguous)
    assertTrue(outcome.confident)
    assertEquals("lYBUbBu4W08", outcome.best!!.song.videoId)
  }

  @Test
  fun `ambiguity does not fire when the album resolves it`() {
    val outcome = MatchScorer.best(sunflowerWithAlbum, sunflowerCandidates())
    assertFalse(outcome.ambiguous)
    assertTrue(outcome.confident)
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
  fun `artists are mandatory but a missing duration is not`() {
    // Artists are the load-bearing defence: the artist veto is what rejects covers and karaoke, so
    // without them confidence is structurally unavailable however well a candidate scores.
    val durationOnly =
      SpotifyTrackMeta(title = "Never Gonna Give You Up", artists = emptyList(), durationSeconds = 214)
    val noArtists = MatchScorer.best(durationOnly, rickCandidates())
    assertFalse("no artists must never auto-play: ${noArtists.best?.explain()}", noArtists.confident)
    assertTrue(noArtists.insufficientEvidence)

    // Duration is only an eligibility gate, so its absence is not disqualifying. Blocking here would
    // send legitimate links to a search page for no safety gain.
    val artistOnly = SpotifyTrackMeta(title = "Never Gonna Give You Up", artists = listOf("Rick Astley"))
    val outcome = MatchScorer.best(artistOnly, rickCandidates())
    assertTrue("artists without a duration should still play: ${outcome.best?.explain()}", outcome.confident)
    assertEquals("lYBUbBu4W08", outcome.best!!.song.videoId)
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
