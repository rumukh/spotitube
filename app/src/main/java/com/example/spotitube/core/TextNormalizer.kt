package com.example.spotitube.core

import java.text.Normalizer
import java.util.Locale

/**
 * Title/artist normalisation shared by the matcher.
 *
 * The goal is to make `Sunflower - Spider-Man: Into the Spider-Verse` (Spotify) and
 * `Sunflower (Spider-Man: Into the Spider-Verse)` (YouTube Music) compare equal, while keeping
 * genuinely different songs apart.
 */
object TextNormalizer {

  /**
   * Latin/Greek/Cyrillic combining marks only (U+0300–U+036F). Deliberately *not* `\p{Mn}`: that
   * would also strip Arabic, Hebrew and Indic vowel marks, which carry meaning.
   */
  private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")

  /**
   * Separator class. Uses Unicode letter/number properties, **not** `[^a-z0-9]` — the ASCII form
   * silently normalises Cyrillic, Hangul, Han and Kana titles to the empty string, which made
   * every non-Latin track unmatchable.
   */
  private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

  /**
   * Suffixes/parentheticals that describe the *same* recording and should be ignored when
   * comparing titles. Matched case-insensitively against a bracketed group or a ` - ` suffix.
   */
  private val NOISE_PHRASES =
    listOf(
      "official video",
      "official music video",
      "official audio",
      "official lyric video",
      "official visualizer",
      "official",
      "music video",
      "lyric video",
      "lyrics",
      "audio",
      "visualizer",
      "hd",
      "hq",
      "4k",
      "explicit",
      "clean",
      "clean version",
      "bonus track",
      "bonus",
      "album version",
      "single version",
      "radio edit",
      "radio version",
      "original mix",
      "stereo",
      "mono",
      "digital remaster",
      "remaster",
      "remastered",
      "remastered version",
      "anniversary edition",
      "deluxe",
      "deluxe edition",
      "expanded edition",
      "from the original motion picture soundtrack",
      "original motion picture soundtrack",
      "motion picture soundtrack",
      "soundtrack version",
      "taylors version",
    )

  /** Matches `(feat. X)`, `[ft X]`, `- feat. X`, `with X` credits. */
  private val FEATURE = Regex("""[\(\[]\s*(feat|ft|featuring|with)[\.\s][^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
  private val FEATURE_SUFFIX = Regex("""\s[-–—]\s*(feat|ft|featuring)[\.\s].*$""", RegexOption.IGNORE_CASE)

  /** ` - `, ` – `, ` — ` used as a title/suffix separator. */
  private val DASH_SEPARATOR = Regex("""\s[-–—]\s""")

  /**
   * Splits a trailing ` - suffix` into head and suffix, or `null` when there is no separator.
   * Uses the *last* separator, so a head containing a dash stays intact.
   */
  private fun splitDashSuffix(raw: String): Pair<String, String>? {
    val match = DASH_SEPARATOR.findAll(raw).lastOrNull() ?: return null
    val head = raw.substring(0, match.range.first).trim()
    val suffix = raw.substring(match.range.last + 1).trim()
    return if (head.isEmpty() || suffix.isEmpty()) null else head to suffix
  }

  private fun codePoints(text: String): Sequence<Int> = sequence {
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      yield(cp)
      i += Character.charCount(cp)
    }
  }

  /** COMMON/INHERITED cover shared punctuation and marks, which say nothing about script. */
  private fun isLatinScript(codePoint: Int): Boolean =
    when (Character.UnicodeScript.of(codePoint)) {
      Character.UnicodeScript.LATIN,
      Character.UnicodeScript.COMMON,
      Character.UnicodeScript.INHERITED -> true
      else -> false
    }

  private fun hasNonLatinLetter(text: String): Boolean =
    codePoints(text).any { Character.isLetter(it) && !isLatinScript(it) }

  /**
   * Whether [suffix] looks like a transliteration rather than identity-bearing text.
   *
   * Latin letters (including macrons — Yūki, Tōkyō — since those are Latin script) plus a small
   * punctuation allowlist. **Digits are excluded deliberately**: `Part 1` and `Part 2` are the
   * difference between two tracks, not a romanisation of either.
   */
  private fun isRomanisationSuffix(suffix: String): Boolean {
    var sawLatinLetter = false
    for (cp in codePoints(suffix)) {
      when {
        Character.isDigit(cp) -> return false
        Character.isLetter(cp) -> {
          if (!isLatinScript(cp)) return false
          sawLatinLetter = true
        }
        Character.isWhitespace(cp) -> Unit
        cp.toChar() in ROMANISATION_PUNCTUATION -> Unit
        Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> Unit
        else -> return false
      }
    }
    return sawLatinLetter
  }

  /** Punctuation that legitimately appears inside a transliteration. */
  private const val ROMANISATION_PUNCTUATION = ".,'\u2019\u02BC-\u2010\u2013()[]&!?:;/"

  /**
   * The non-Latin head of a `<non-Latin head> - <Latin romanisation>` title, or `null`.
   *
   * Expects [stripped] to have already been through [stripNoise], so ordinary noise — `(feat. X)`,
   * `- Remastered`, `(Official Audio)` — is gone before the shape is judged. That ordering is what
   * makes the rule correct rather than merely usually-right: without it, a Spotify title of
   * `夜の踊り子 - Remastered` reads as *already romanised*, the "one side only" rule blocks the
   * special case, and the correct recording scores 0.400 and fails. Measured, not theorised.
   */
  private fun romanisedHead(stripped: String): String? {
    val (head, suffix) = splitDashSuffix(stripped) ?: return null
    if (!isRomanisationSuffix(suffix)) return null
    // A Latin head never qualifies, or the "Circles" / "Circles Around The Sun" false-positive
    // class comes straight back.
    if (!hasNonLatinLetter(head)) return null
    return head
  }

  /**
   * Whether the two titles are the same song written with and without YouTube Music's romanisation.
   *
   * Pair-conditioned on purpose. An earlier version stripped the suffix inside [stripNoise], which
   * is context-free — it sees one title at a time and cannot tell a transliteration from
   * identity-bearing text, so it permanently collapsed titles differing *only* by that suffix:
   * `同じ頭 - Part One` and `同じ頭 - Part Two` became indistinguishable, and adjacent album tracks
   * share artist, album and duration so nothing else would have caught it. It also had a far wider
   * blast radius than intended, because [canonical] feeds artist-set comparison, album similarity
   * and equivalence-cluster identity — not just title matching.
   *
   * Requires the shape on **exactly one** side. Two romanisation-shaped suffixes mean the titles are
   * being distinguished *by* those suffixes, which is precisely the identity case above.
   */
  private fun isRomanisationPair(strippedA: String, strippedB: String): Boolean {
    val headA = romanisedHead(strippedA)
    val headB = romanisedHead(strippedB)
    // Exactly one side, either side — the romanised title is not always the YouTube one.
    if ((headA == null) == (headB == null)) return false

    val head = headA ?: headB!!
    val other = if (headA != null) strippedB else strippedA
    val normalisedHead = normalize(head)
    return normalisedHead.isNotEmpty() && normalisedHead == normalize(other)
  }

  /** `(2022 Remaster)`, `- Remastered 2011`, `[2009 Digital Remaster]`, ... */
  private val YEARED_REMASTER =
    Regex("""[\(\[]?\s*(19|20)\d{2}\s*(digital\s+)?remaster(ed)?\s*[\)\]]?""", RegexOption.IGNORE_CASE)
  private val REMASTER_YEARED =
    Regex("""[\(\[]?\s*remaster(ed)?\s*(19|20)\d{2}\s*[\)\]]?""", RegexOption.IGNORE_CASE)

  /**
   * Removes release-variant noise while preserving the part of the title that identifies the song.
   * Never strips an arbitrary ` - suffix`, because for many tracks (`Sunflower - Spider-Man...`)
   * that suffix *is* the title.
   */
  fun stripNoise(raw: String): String {
    var s = raw
    s = FEATURE.replace(s, " ")
    s = FEATURE_SUFFIX.replace(s, " ")
    s = YEARED_REMASTER.replace(s, " ")
    s = REMASTER_YEARED.replace(s, " ")
    for (phrase in NOISE_PHRASES) {
      // Bracketed: "(Official Video)" / "[Remastered]"
      s = Regex("""[\(\[]\s*${Regex.escape(phrase)}\s*[\)\]]""", RegexOption.IGNORE_CASE).replace(s, " ")
      // Dash suffix at the end: "- Remastered"
      s = Regex("""\s[-–—]\s*${Regex.escape(phrase)}\s*$""", RegexOption.IGNORE_CASE).replace(s, " ")
    }
    return s.trim()
  }

  /** Lower-cases, removes Latin diacritics, expands `&`, and reduces separators to single spaces. */
  fun normalize(raw: String): String {
    // NFKC first, so full-width forms, ligatures and other compatibility variants fold together.
    var s = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    s = s.replace("&", " and ").replace("'", "").replace("\u2019", "")
    // Decompose to peel off accents, then recompose so scripts that decompose into non-mark
    // components (Hangul jamo) come back to their canonical form.
    s = Normalizer.normalize(s, Normalizer.Form.NFD)
    s = DIACRITICS.replace(s, "")
    s = Normalizer.normalize(s, Normalizer.Form.NFC)
    s = NON_ALNUM.replace(s, " ")
    return s.trim().replace(Regex("\\s+"), " ")
  }

  /** [stripNoise] then [normalize] — the canonical form used for comparisons. */
  fun canonical(raw: String): String = normalize(stripNoise(raw))

  fun tokens(raw: String): Set<String> = canonical(raw).split(' ').filter { it.isNotEmpty() }.toSet()

  /**
   * Symmetric token-set similarity in `0.0..1.0`. Exact string equality short-circuits to `1.0`;
   * otherwise it is the F1 of the two containments, which is forgiving of one side carrying an
   * extra descriptive word but not of two genuinely different titles.
   */
  fun similarity(a: String, b: String): Double {
    // Ordinary noise removal runs independently on both sides FIRST. Everything below judges the
    // stripped strings, which is what lets `夜の踊り子 - Remastered` be recognised as an ordinary
    // title rather than an already-romanised one.
    val strippedA = stripNoise(a)
    val strippedB = stripNoise(b)
    val ca = normalize(strippedA)
    val cb = normalize(strippedB)
    if (ca.isEmpty() || cb.isEmpty()) return 0.0
    if (ca == cb) return 1.0

    // YouTube Music appends a Latin transliteration to non-Latin titles and Spotify does not:
    // `夜の踊り子` vs `夜の踊り子 - Yoru No Odoriko`. That is not cosmetic — a CJK title has no
    // spaces, so it canonicalises to a SINGLE token, and the appended romanisation drives token-set
    // recall to 1/n. Measured: the correct recording, right artist, exact duration, scored 0.40 and
    // fell under the confidence threshold; whether a track played came down to how many words its
    // romanisation happened to add.
    if (isRomanisationPair(strippedA, strippedB)) return 1.0

    // No match on the special case: fall through to ordinary similarity on the same stripped
    // strings, so nothing is scored twice or scored against a half-processed title.
    val ta = ca.split(' ').filter { it.isNotEmpty() }.toSet()
    val tb = cb.split(' ').filter { it.isNotEmpty() }.toSet()
    if (ta.isEmpty() || tb.isEmpty()) return 0.0

    val shared = ta.intersect(tb).size.toDouble()
    if (shared == 0.0) return 0.0
    val precision = shared / ta.size
    val recall = shared / tb.size
    return 2 * precision * recall / (precision + recall)
  }

  /** Parses `m:ss` or `h:mm:ss` (YouTube Music's duration column) into seconds. */
  fun parseClockDuration(text: String?): Int? {
    val t = text?.trim() ?: return null
    if (!Regex("""^\d{1,3}(:\d{2}){1,2}$""").matches(t)) return null
    val parts = t.split(':').map { it.toIntOrNull() ?: return null }
    return when (parts.size) {
      2 -> parts[0] * 60 + parts[1]
      3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
      else -> null
    }
  }
}
