package com.example.spotitube.core

import kotlin.math.abs

/** A candidate together with why it scored the way it did. */
data class ScoredMatch(
  val song: YouTubeSong,
  /**
   * Evidence score: title, artist and album only. Confidence is judged on this and nothing else,
   * so no accumulation of small bonuses can lift a weak match over [MatchScorer.CONFIDENCE_THRESHOLD].
   */
  val core: Double,
  /** Ordering score: [core] plus presentation bonuses. Decides *which* candidate, never *whether*. */
  val score: Double,
  val titleScore: Double,
  val artistScore: Double,
  val albumScore: Double,
  val vetoes: List<String>,
  val notes: List<String>,
) {
  val vetoed: Boolean
    get() = vetoes.isNotEmpty()

  fun explain(): String =
    "core=%.3f rank=%.3f (t=%.2f a=%.2f al=%.2f)%s".format(
      core,
      score,
      titleScore,
      artistScore,
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
   * True when a candidate close enough to the winner to be a coin toss is *not* the same recording
   * — a different title, a different artist set, or a variant marker. Picking `result[0]` there
   * would be a coin toss dressed up as a decision, so the user gets the search results instead.
   *
   * A near-tie between two releases of the *same* recording is benign and does not set this.
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
   * Minimum *core* score for auto-play.
   *
   * Core is `title + artist + album` with weights summing to 1.0. Presentation bonuses (official
   * album link, artist channel, explicit agreement, YouTube's own ordering) are deliberately
   * excluded: they may reorder candidates but must never manufacture confidence in a weak one.
   *
   * Measured against the live probes the distribution is strongly bimodal — legitimate uploads sit
   * near 1.0 and every wrong candidate (covers, karaoke, instrumentals, live re-uploads,
   * compilation-farm re-posts) is vetoed to 0.0. So the vetoes, not this number, are what keep the
   * wrong song from playing; the threshold only guards the residual "nothing vetoed but the
   * evidence is thin" case.
   */
  const val CONFIDENCE_THRESHOLD = 0.70

  // Core weights. MUST sum to 1.0.
  private const val WEIGHT_TITLE = 0.40
  private const val WEIGHT_ARTIST = 0.35

  /**
   * Album is a full weighted term, not a bonus, because it is the *only* signal that can separate
   * two uploads of the same recording on different releases. For "Sunflower" the exact-duration
   * candidate (2:38) sits on Post Malone's own album while the +1 s candidate (2:39) sits on the
   * soundtrack album Spotify names — album evidence is what picks correctly there.
   */
  private const val WEIGHT_ALBUM = 0.25

  /**
   * Beyond this many seconds apart it is simply not the same recording.
   *
   * This is duration's *only* job. It is deliberately not a scoring term: measured against the live
   * "Sunflower" result set, duration proximity is anti-correlated with correctness — the exact
   * 158 s matches are a wrong-album release and a compilation-farm upload, while the correct
   * soundtrack release is 159 s. Rewarding proximity would promote exactly the wrong rows.
   */
  const val MAX_DURATION_DELTA_SECONDS = 12

  /** A whole artist name must match. Token overlap alone can never reach this. */
  private const val MIN_ARTIST_SCORE = 0.55

  private const val EXPLICIT_AGREE_BONUS = 0.02
  private const val EXPLICIT_DISAGREE_PENALTY = 0.06

  /**
   * Score gap below which two candidates are "equally strong", i.e. close enough that nothing but
   * presentation separates them. Within this band every candidate must be the *same recording* as
   * the winner or auto-play is refused.
   */
  private const val AMBIGUITY_MARGIN = 0.02

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
    val albumKnown = spotify.album != null && candidate.album != null
    val albumScore = if (albumKnown) TextNormalizer.similarity(spotify.album!!, candidate.album!!) else 0.0

    val spotifyDuration = spotify.durationSeconds
    val candidateDuration = candidate.durationSeconds
    val delta = if (spotifyDuration != null && candidateDuration != null) abs(spotifyDuration - candidateDuration) else null

    val vetoes = ArrayList<String>()
    val notes = ArrayList<String>()

    if (delta != null && delta > MAX_DURATION_DELTA_SECONDS) vetoes += "duration±${delta}s"
    // An unreadable candidate artist is not neutral: karaoke and third-party uploads are exactly
    // the rows that come back without an artist endpoint, and they otherwise look like a match.
    if (candidate.artists.isEmpty()) vetoes += "artist-unknown"
    if (artistScore < MIN_ARTIST_SCORE) vetoes += "artist"
    variantMarkers(spotify, candidate).forEach { vetoes += "variant:$it" }
    if (titleScore < 0.34) vetoes += "title"

    // Evidence only. Duration contributes nothing here — see MAX_DURATION_DELTA_SECONDS.
    //
    // When either side has no album, album is not evidence *against* the candidate: the weight is
    // renormalised across title and artist instead. Otherwise Spotify's page degrading to a shell
    // would cap every candidate at 0.75 and silently push good matches under the threshold.
    val core =
      if (albumKnown) {
        WEIGHT_TITLE * titleScore + WEIGHT_ARTIST * artistScore + WEIGHT_ALBUM * albumScore
      } else {
        (WEIGHT_TITLE * titleScore + WEIGHT_ARTIST * artistScore) / (WEIGHT_TITLE + WEIGHT_ARTIST)
      }
    if (albumKnown && albumScore > 0.8) notes += "album-match"

    // Ordering only, from here down. Nothing below may affect `core`, and therefore nothing below
    // can push a weak match over the confidence threshold.
    var rank = core
    if (candidate.hasAlbumLink) {
      rank += 0.02
      notes += "album-link"
    }
    if (candidate.hasArtistChannel) {
      rank += 0.02
      notes += "artist-channel"
    }
    // Clean and explicit masters are different recordings; rank the matching one first rather than
    // rejecting outright, because the two sides disagree often enough that a veto would misfire.
    val spotifyExplicit = spotify.isExplicit
    val candidateExplicit = candidate.isExplicit
    if (spotifyExplicit != null && candidateExplicit != null) {
      if (spotifyExplicit == candidateExplicit) {
        rank += EXPLICIT_AGREE_BONUS
        notes += "explicit-match"
      } else {
        rank -= EXPLICIT_DISAGREE_PENALTY
        notes += "explicit-mismatch"
      }
    }
    // Tiny prior on YouTube's own ranking, purely to break ties deterministically.
    rank += 0.02 * (1.0 - (candidate.position.coerceAtMost(20) / 20.0))

    val vetoed = vetoes.isNotEmpty()

    return ScoredMatch(
      song = candidate,
      core = if (vetoed) 0.0 else core.coerceAtLeast(0.0),
      score = if (vetoed) 0.0 else rank.coerceAtLeast(0.0),
      titleScore = titleScore,
      artistScore = artistScore,
      albumScore = albumScore,
      vetoes = vetoes,
      notes = notes,
    )
  }

  fun best(spotify: SpotifyTrackMeta, candidates: List<YouTubeSong>): MatchOutcome {
    // Spotify's page occasionally comes back as a JavaScript shell with no Open Graph tags, and the
    // oEmbed fallback carries a title and nothing else. Without artists the artist veto — the only
    // thing that rejects covers and karaoke — cannot run at all, so confidence is structurally
    // unavailable however well a candidate scores. Duration's absence is NOT disqualifying: it is
    // only an eligibility gate, so title + artist + album remain sufficient without it.
    val insufficientEvidence = spotify.artists.isEmpty()
    if (candidates.isEmpty()) {
      return MatchOutcome(null, confident = false, ranked = emptyList(), insufficientEvidence = insufficientEvidence)
    }
    val ranked =
      candidates.map { score(spotify, it) }.sortedWith(compareByDescending<ScoredMatch> { it.score }.thenBy { it.song.position })
    val top = ranked.firstOrNull()

    // Equivalence cluster. Everything close enough to the winner that only presentation separates
    // it must be the *same recording* — same normalised title, same artist set, no variant markers.
    // Two releases of one recording tying is benign and plays; a near-tie against something
    // genuinely different is a coin toss and opens search.
    //
    // The band is measured on CORE, never on rank. Rank carries album-link, artist-channel,
    // explicit and position adjustments, and a dangerous rival that simply lacks those structural
    // links could otherwise be pushed outside the band and silently excluded from the safety check.
    val topCore = ranked.filter { !it.vetoed }.maxOfOrNull { it.core } ?: 0.0
    val ambiguous =
      top != null &&
        !top.vetoed &&
        ranked.any { rival ->
          rival !== top && !rival.vetoed && rival.core >= topCore - AMBIGUITY_MARGIN && !sameRecording(top, rival)
        }

    val confident =
      top != null && !top.vetoed && top.core >= CONFIDENCE_THRESHOLD && !insufficientEvidence && !ambiguous
    return MatchOutcome(
      best = top,
      confident = confident,
      ranked = ranked,
      insufficientEvidence = insufficientEvidence,
      ambiguous = ambiguous,
    )
  }

  /**
   * Two candidates are the same recording when their titles normalise identically, their artist
   * sets match, and neither carries a variant marker. Different releases of one recording satisfy
   * this; a cover, a karaoke version or a different song sharing a title does not.
   */
  private fun sameRecording(a: ScoredMatch, b: ScoredMatch): Boolean {
    if (a.vetoes.isNotEmpty() || b.vetoes.isNotEmpty()) return false
    if (TextNormalizer.canonical(a.song.title) != TextNormalizer.canonical(b.song.title)) return false
    val artistsA = a.song.artists.map { TextNormalizer.canonical(it) }.filter { it.isNotEmpty() }.toSet()
    val artistsB = b.song.artists.map { TextNormalizer.canonical(it) }.filter { it.isNotEmpty() }.toSet()
    if (artistsA.isEmpty() || artistsB.isEmpty()) return false
    return artistsA == artistsB
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

    // No full-name match. Some uploads collapse a collaboration into one credit string, so split
    // each RAW candidate credit on collaboration delimiters, then canonicalise each segment.
    // Splitting must happen before canonicalisation, which strips the very punctuation we split on.
    //
    // A token sublist is not enough: it accepts "u2" inside "U2 Tribute Band", which is a different
    // act entirely. The variant veto does not save us there, because it inspects the candidate
    // title and album — never the artist credit. Splitting instead makes "Post Malone & Swae Lee"
    // two valid credits while "U2 Tribute Band" stays one credit that matches nothing.
    val candidateCredits =
      candidateArtists
        .flatMap { splitCredits(it) }
        .map { TextNormalizer.canonical(it) }
        .filter { it.isNotEmpty() }
        .toSet()
    if (a.any { it in candidateCredits }) return 0.55

    val ta = a.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.toSet()
    val tb = b.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.toSet()
    if (ta.isEmpty() || tb.isEmpty()) return 0.0
    val jaccard = ta.intersect(tb).size.toDouble() / ta.union(tb).size
    // Bare token overlap must stay strictly below MIN_ARTIST_SCORE: sharing a word ("Rick Roll" vs
    // "Rick Astley", "Post Lee" vs "Post Malone") is not evidence of being the same artist.
    return 0.5 * jaccard * MIN_ARTIST_SCORE
  }

  /**
   * Splits one candidate credit string into the individual artists it names.
   *
   * Only collaboration delimiters split — `&`, `,`, `and`, `feat`, `ft`, `featuring`, `with`, `x`.
   * Everything else stays whole, so "U2 Tribute Band" remains a single credit naming a band that
   * is not U2, while "Post Malone & Swae Lee" becomes two credits that each match exactly.
   */
  private fun splitCredits(credit: String): List<String> =
    credit
      .split(CREDIT_DELIMITERS)
      .map { it.trim() }
      .filter { it.isNotEmpty() }

  private val CREDIT_DELIMITERS =
    Regex("""\s*(?:&|,|\bx\b|\band\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)

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
