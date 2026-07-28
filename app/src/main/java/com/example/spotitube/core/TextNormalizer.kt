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

  private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
  private val NON_ALNUM = Regex("[^a-z0-9]+")

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

  /** Lower-cases, removes diacritics, expands `&`, and reduces everything else to single spaces. */
  fun normalize(raw: String): String {
    val folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
    var s = DIACRITICS.replace(folded, "").lowercase(Locale.ROOT)
    s = s.replace("&", " and ").replace("'", "").replace("\u2019", "")
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
