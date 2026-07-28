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
   * Strips YouTube Music's romanisation suffix: `夜の踊り子 - Yoru No Odoriko` → `夜の踊り子`.
   *
   * YouTube Music appends a Latin transliteration to non-Latin titles; Spotify does not. Left in
   * place this is not cosmetic — a CJK title contains no spaces, so it canonicalises to a **single
   * token**, and the appended romanisation collapses token-set recall to `1/n`. Measured: the
   * correct recording of 夜の踊り子, by the right artist at exactly the right duration, scored a
   * title similarity of 0.40 and fell under the confidence threshold. Whether a track played came
   * down to how many words its romanisation happened to add.
   *
   * Deliberately narrow. It fires only when the head contains a non-Latin letter and the tail is
   * purely Latin, so Latin-script titles — `Sunflower - Spider-Man: Into the Spider-Verse` — are
   * untouched and cannot regress. It also cannot manufacture a false match between two *different*
   * non-Latin songs: stripping leaves the CJK head, which is the discriminating part, so
   * `新宝島 - Shin Takara Jima` still shares nothing with `夜の踊り子`.
   *
   * Variant vetoes are unaffected: [MatchScorer.variantMarkers] reads [normalize] on the raw title,
   * not [canonical], so a stripped `… (agraph Remix) - … (agraph Remix)` is still rejected.
   */
  fun stripRomanisation(raw: String): String {
    val match = DASH_SEPARATOR.findAll(raw).lastOrNull() ?: return raw
    val head = raw.substring(0, match.range.first).trim()
    val tail = raw.substring(match.range.last + 1).trim()
    if (head.isEmpty() || tail.isEmpty()) return raw

    val headIsNonLatin = codePoints(head).any { Character.isLetter(it) && !isLatinScript(it) }
    if (!headIsNonLatin) return raw

    // The tail must be a transliteration: Latin letters only, and at least one of them. Requiring a
    // letter stops a bare numeric or symbolic suffix being mistaken for a romanisation.
    var sawLatinLetter = false
    for (cp in codePoints(tail)) {
      if (!Character.isLetter(cp)) continue
      if (!isLatinScript(cp)) return raw
      sawLatinLetter = true
    }
    return if (sawLatinLetter) head else raw
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
    // First: YouTube Music's romanisation suffix, so everything below operates on the real title.
    var s = stripRomanisation(raw)
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
    val ca = canonical(a)
    val cb = canonical(b)
    if (ca.isEmpty() || cb.isEmpty()) return 0.0
    if (ca == cb) return 1.0

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
