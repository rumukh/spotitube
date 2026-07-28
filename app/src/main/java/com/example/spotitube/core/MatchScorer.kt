package com.example.spotitube.core

import kotlin.math.abs

/** A candidate together with why it scored the way it did. */
data class ScoredMatch(
  val song: YouTubeSong,
  val score: Double,
  val titleScore: Double,
  val artistScore: Double,
  val durationScore: Double,
  val albumScore: Double,
  val vetoes: List<String>,
  val notes: List<String>,
) {
  val vetoed: Boolean
    get() = vetoes.isNotEmpty()

  fun explain(): String =
    "score=%.3f (t=%.2f a=%.2f d=%.2f al=%.2f)%s".format(
      score,
      titleScore,
      artistScore,
      durationScore,
      albumScore,
      if (vetoed) " VETO[${vetoes.joinToString("|")}]" else "",
    )
}

/** Outcome of ranking a search result set against the Spotify track. */
data class MatchOutcome(
  val best: ScoredMatch?,
  val confident: Boolean,
  val ranked: List<ScoredMatch>,
  /**
   * True when the Spotify side gave us nothing but a title. Title alone cannot distinguish an
   * original from a cover, so auto-play is refused however well a candidate scores.
   */
  val insufficientEvidence: Boolean = false,
  /**
   * True when the top two candidates are separated by nothing but YouTube's own ordering *and*
   * they come from clearly different releases, with no Spotify album to arbitrate. Picking
   * `result[0]` there would be a coin toss dressed up as a decision.
   */
  val ambiguous: Boolean = false,
)

/**
 * Decides whether a YouTube Music row is *the same recording* as the Spotify track.
 *
 * Weighted score plus hard vetoes. The vetoes matter more than the weights: the real search results
 * contain covers and karaoke versions whose titles match Spotify's *exactly* (a string quartet
 * cover of "Sunflower - Spider-Man: Into the Spider-Verse"), so title similarity alone is unsafe.
 */
object MatchScorer {

  /**
   * Minimum score for auto-play.
   *
   * Measured against both live probes, the score distribution is strongly bimodal: the legitimate
   * uploads land at 1.069–1.070 and *every* wrong candidate — covers, karaoke, instrumentals, live
   * re-uploads, compilation-farm re-posts — is vetoed to 0.0. So the vetoes, not this number, are
   * what keep the wrong song from playing.
   *
   * The threshold guards the residual case where nothing is vetoed but the evidence is thin: a
   * fuzzy title (~0.80), an artist that only matches as a substring (0.55) and an unreadable
   * duration column together score ≈0.70. Anything weaker than that opens search instead.
   *
   * Note the maximum is ≈1.07, not 1.0: the official-upload bonuses are added on top of the
   * weighted sum so that a perfect match still out-ranks a near-perfect one.
   */
  const val CONFIDENCE_THRESHOLD = 0.70

  private const val WEIGHT_TITLE = 0.40
  private const val WEIGHT_ARTIST = 0.35
  private const val WEIGHT_DURATION = 0.25

  /** Beyond this many seconds apart it is simply not the same recording. */
  const val MAX_DURATION_DELTA_SECONDS = 12

  /**
   * Durations inside this bucket are treated as equally good rather than ranked by raw delta.
   *
   * This matters: for "Sunflower" the exact-duration candidate (2:38) sits on *Post Malone's own
   * album* while the +1 s candidate (2:39) sits on the soundtrack album Spotify names. Ranking by
   * smallest delta picks the wrong release; album evidence has to be what separates them.
   */
  private const val PERFECT_DURATION_DELTA_SECONDS = 3
  private const val DURATION_ZERO_AT_SECONDS = 15
  private const val MIN_ARTIST_SCORE = 0.25

  /** Album agreement is corroboration, never a requirement — the same recording is often reissued. */
  private const val ALBUM_BONUS = 0.06

  private const val EXPLICIT_AGREE_BONUS = 0.02
  private const val EXPLICIT_DISAGREE_PENALTY = 0.06

  /**
   * Score gap below which two candidates are "equally strong". Equal to the rank prior, so it means
   * literally "nothing but YouTube's ordering separates these".
   */
  private const val AMBIGUITY_MARGIN = 0.02

  /** Unknown duration is neither evidence for nor against; slightly pessimistic. */
  private const val UNKNOWN_DURATION_SCORE = 0.45

  /** Same idea for a row whose artist column we could not read. */
  private const val UNKNOWN_ARTIST_SCORE = 0.45

  /**
   * Words that mean "this is a different performance". Vetoed only when the Spotify title does not
   * contain the same word — "Live and Let Die" must not be rejected for containing "live".
   */
  private val VARIANT_TOKENS =
    setOf(
      "cover",
      "covers",
      "karaoke",
      "instrumental",
      "instrumentals",
      "live",
      "remix",
      "remixed",
      "nightcore",
      "slowed",
      "reverb",
      "spedup",
      "acapella",
      "acappella",
      "tribute",
      "parody",
      "bardcore",
      "lofi",
      "mashup",
      "bootleg",
      "medley",
      "unplugged",
      "backing",
      "ringtone",
      "midi",
      "pianoforte",
      "quartet",
      "orchestra",
      "orchestral",
      "chiptune",
      "rendition",
      "8d",
      "16d",
    )

  /** Multi-word variant markers checked against the canonicalised title. */
  private val VARIANT_PHRASES =
    listOf(
      "sped up",
      "slowed down",
      "made famous by",
      "in the style of",
      "originally performed by",
      "as made popular by",
      "karaoke version",
      "8 bit",
      "string quartet",
      "piano version",
      "guitar version",
      "workout mix",
      "tribute to",
      "in old english",
    )

  /** These disqualify even when they only appear on the album/collection, not the title. */
  private val ALBUM_VARIANT_PHRASES = listOf("karaoke", "tribute", "made famous by", "in the style of")

  fun score(spotify: SpotifyTrackMeta, candidate: YouTubeSong): ScoredMatch {
    val titleScore = TextNormalizer.similarity(spotify.title, candidate.title)
    val artistScore = artistScore(spotify.artists, candidate.artists)
    val albumScore =
      if (spotify.album != null && candidate.album != null) {
        TextNormalizer.similarity(spotify.album, candidate.album)
      } else {
        0.0
      }

    val spotifyDuration = spotify.durationSeconds
    val candidateDuration = candidate.durationSeconds
    val delta = if (spotifyDuration != null && candidateDuration != null) abs(spotifyDuration - candidateDuration) else null
    val durationScore = durationScore(delta)

    val vetoes = ArrayList<String>()
    val notes = ArrayList<String>()

    if (delta != null && delta > MAX_DURATION_DELTA_SECONDS) vetoes += "duration±${delta}s"
    // An unreadable candidate artist is not neutral: karaoke and third-party uploads are exactly
    // the rows that come back without an artist endpoint, and they otherwise look like a match.
    if (candidate.artists.isEmpty()) vetoes += "artist-unknown"
    if (artistScore < MIN_ARTIST_SCORE) vetoes += "artist"
    variantMarkers(spotify, candidate).forEach { vetoes += "variant:$it" }
    if (titleScore < 0.34) vetoes += "title"

    var raw = WEIGHT_TITLE * titleScore + WEIGHT_ARTIST * artistScore + WEIGHT_DURATION * durationScore

    // Album agreement is the tie-breaker between two uploads of the same recording on different
    // releases, and it must outweigh YouTube's own ordering.
    if (albumScore > 0.0) {
      raw += ALBUM_BONUS * albumScore
      if (albumScore > 0.8) notes += "album-match"
    }
    if (candidate.hasAlbumLink) {
      raw += 0.02
      notes += "album-link"
    }
    if (candidate.hasArtistChannel) {
      raw += 0.02
      notes += "artist-channel"
    }
    // Clean and explicit masters are different recordings; rank the matching one first rather than
    // rejecting outright, because the two sides disagree often enough that a veto would misfire.
    val spotifyExplicit = spotify.isExplicit
    val candidateExplicit = candidate.isExplicit
    if (spotifyExplicit != null && candidateExplicit != null) {
      if (spotifyExplicit == candidateExplicit) {
        raw += EXPLICIT_AGREE_BONUS
        notes += "explicit-match"
      } else {
        raw -= EXPLICIT_DISAGREE_PENALTY
        notes += "explicit-mismatch"
      }
    }
    // Tiny prior on YouTube's own ranking, purely to break ties deterministically.
    raw += 0.02 * (1.0 - (candidate.position.coerceAtMost(20) / 20.0))

    val score = if (vetoes.isEmpty()) raw.coerceAtLeast(0.0) else 0.0

    return ScoredMatch(
      song = candidate,
      score = score,
      titleScore = titleScore,
      artistScore = artistScore,
      durationScore = durationScore,
      albumScore = albumScore,
      vetoes = vetoes,
      notes = notes,
    )
  }

  fun best(spotify: SpotifyTrackMeta, candidates: List<YouTubeSong>): MatchOutcome {
    // Spotify's page occasionally comes back as a JavaScript shell with no Open Graph tags, and the
    // oEmbed fallback carries a title and nothing else. A bare title is not enough to tell an
    // original from a cover, so never auto-play on it — open search and let the user choose.
    val insufficientEvidence = spotify.artists.isEmpty() && spotify.durationSeconds == null
    if (candidates.isEmpty()) {
      return MatchOutcome(null, confident = false, ranked = emptyList(), insufficientEvidence = insufficientEvidence)
    }
    val ranked =
      candidates.map { score(spotify, it) }.sortedWith(compareByDescending<ScoredMatch> { it.score }.thenBy { it.song.position })
    val top = ranked.firstOrNull()
    val runnerUp = ranked.getOrNull(1)?.takeIf { !it.vetoed }

    // Spotify's canonical page intermittently returns a JavaScript shell, which costs us the album.
    // Without it, two uploads of the same title and duration on *different* releases are separated
    // by nothing but YouTube's ranking prior — that is a coin toss, so hand the user the results.
    val ambiguous =
      top != null &&
        !top.vetoed &&
        runnerUp != null &&
        top.score - runnerUp.score <= AMBIGUITY_MARGIN &&
        top.albumScore == 0.0 &&
        runnerUp.albumScore == 0.0 &&
        albumsClearlyDiffer(top.song.album, runnerUp.song.album)

    val confident =
      top != null && !top.vetoed && top.score >= CONFIDENCE_THRESHOLD && !insufficientEvidence && !ambiguous
    return MatchOutcome(
      best = top,
      confident = confident,
      ranked = ranked,
      insufficientEvidence = insufficientEvidence,
      ambiguous = ambiguous,
    )
  }

  /** Only "different release", not "slightly different edition" — a deluxe reissue is not a conflict. */
  private fun albumsClearlyDiffer(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    return TextNormalizer.similarity(a, b) < 0.5
  }

  private fun durationScore(delta: Int?): Double =
    when {
      delta == null -> UNKNOWN_DURATION_SCORE
      delta <= PERFECT_DURATION_DELTA_SECONDS -> 1.0
      delta >= DURATION_ZERO_AT_SECONDS -> 0.0
      else ->
        1.0 -
          (delta - PERFECT_DURATION_DELTA_SECONDS).toDouble() /
          (DURATION_ZERO_AT_SECONDS - PERFECT_DURATION_DELTA_SECONDS)
    }

  /**
   * Requires at least one *whole* artist name to match. Partial token overlap alone is capped low,
   * because "Rick Roll" (a compilation-farm artist) shares a token with "Rick Astley" while being a
   * different uploader entirely.
   */
  internal fun artistScore(spotifyArtists: List<String>, candidateArtists: List<String>): Double {
    if (spotifyArtists.isEmpty() || candidateArtists.isEmpty()) return UNKNOWN_ARTIST_SCORE
    val a = spotifyArtists.map { TextNormalizer.canonical(it) }.filter { it.isNotEmpty() }.toSet()
    val b = candidateArtists.map { TextNormalizer.canonical(it) }.filter { it.isNotEmpty() }.toSet()
    if (a.isEmpty() || b.isEmpty()) return UNKNOWN_ARTIST_SCORE

    val exact = a.intersect(b).size
    if (exact > 0) return 0.60 + 0.40 * (exact.toDouble() / minOf(a.size, b.size))

    // No full-name match. Some uploads collapse "Post Malone & Swae Lee" into one string, so also
    // accept a full Spotify artist appearing as a substring of a candidate artist.
    val substringHit = a.any { s -> b.any { c -> c.contains(s) || s.contains(c) } }
    if (substringHit) return 0.55

    val ta = a.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.toSet()
    val tb = b.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.toSet()
    if (ta.isEmpty() || tb.isEmpty()) return 0.0
    val jaccard = ta.intersect(tb).size.toDouble() / ta.union(tb).size
    return 0.5 * jaccard
  }

  /** Variant markers present on the candidate but absent from the Spotify title. */
  internal fun variantMarkers(spotify: SpotifyTrackMeta, candidate: YouTubeSong): List<String> {
    val spotifyTitle = TextNormalizer.normalize(spotify.title)
    val spotifyTokens = spotifyTitle.split(' ').toSet()
    val candidateTitle = TextNormalizer.normalize(candidate.title)
    val candidateTokens = candidateTitle.split(' ').toSet()

    val hits = LinkedHashSet<String>()
    for (token in VARIANT_TOKENS) {
      if (token in candidateTokens && token !in spotifyTokens) hits += token
    }
    for (phrase in VARIANT_PHRASES) {
      if (candidateTitle.contains(phrase) && !spotifyTitle.contains(phrase)) hits += phrase
    }
    val album = candidate.album?.let { TextNormalizer.normalize(it) }
    if (album != null) {
      for (phrase in ALBUM_VARIANT_PHRASES) {
        if (album.contains(phrase) && !spotifyTitle.contains(phrase)) hits += phrase
      }
    }
    return hits.toList()
  }
}
