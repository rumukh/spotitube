package com.example.spotitube.core

/**
 * Which source(s) produced a [SpotifyTrackMeta].
 *
 * Recorded so a bad match in the field can be traced to the path that produced it — the sources
 * differ in what they carry (the embed has no album; oEmbed has only a title), so knowing which one
 * won explains most surprising results without reproducing them.
 */
enum class MetadataSource {
  /** Structured `__NEXT_DATA__` only — no album available. */
  EMBED,

  /** Open Graph tags only; the embed failed or was unparsable. */
  OPEN_GRAPH,

  /** Both, merged. The normal healthy path. */
  EMBED_AND_OPEN_GRAPH,

  /** Degraded last resort: title only, never enough to auto-play. */
  OEMBED_TITLE_ONLY,
}

/** The bits of a Spotify track page we actually use for matching. */
data class SpotifyTrackMeta(
  val title: String,
  val artists: List<String>,
  val album: String? = null,
  val durationSeconds: Int? = null,
  val releaseYear: Int? = null,
  /** Raw `og:type`, e.g. `music.song`. Used only as a sanity cross-check. */
  val ogType: String? = null,
  /** Millisecond duration, available only from the embed payload. Kept for fidelity in logs. */
  val durationMillis: Int? = null,
  /** `null` when the source did not tell us. */
  val isExplicit: Boolean? = null,
  /** Spotify's own playability for this market. Logged as a diagnostic; never gates playback. */
  val isPlayable: Boolean? = null,
  val playabilityReason: String? = null,
  /** Which endpoint(s) this came from. Diagnostic only; never affects matching. */
  val source: MetadataSource? = null,
) {
  val artistLine: String
    get() = artists.joinToString(", ")

  /**
   * The query we send to YouTube Music. Artists first, then the title — this is the form that was
   * verified to put the official recording in row 0 for both probe tracks.
   */
  val searchQuery: String
    get() = listOf(artistLine, title).filter { it.isNotBlank() }.joinToString(" ").trim()

  /** A human-friendly one-liner for logs and the UI. */
  val display: String
    get() = if (artists.isEmpty()) title else "$artistLine — $title"

  /**
   * Fills gaps in this record from [other]. Used to combine the structured embed payload (clean
   * artist array, millisecond duration, explicit flag) with the canonical page's Open Graph tags,
   * which are the only place the **album name** appears.
   */
  fun mergedWith(other: SpotifyTrackMeta?): SpotifyTrackMeta {
    if (other == null) return this
    return copy(
      title = title.ifBlank { other.title },
      artists = artists.ifEmpty { other.artists },
      album = album ?: other.album,
      durationSeconds = durationSeconds ?: other.durationSeconds,
      releaseYear = releaseYear ?: other.releaseYear,
      ogType = ogType ?: other.ogType,
      durationMillis = durationMillis ?: other.durationMillis,
      isExplicit = isExplicit ?: other.isExplicit,
      isPlayable = isPlayable ?: other.isPlayable,
      playabilityReason = playabilityReason ?: other.playabilityReason,
    )
  }
}

/**
 * Extracts [SpotifyTrackMeta] from the server-rendered `open.spotify.com` HTML.
 *
 * Only the `<meta>` tags are read, never the React payload, so this survives front-end rewrites.
 * Attribute order is not assumed and values are HTML-unescaped.
 */
object SpotifyMetaParser {

  private val META_TAG = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
  private val KEY_ATTR = Regex("""(?:property|name)\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
  private val CONTENT_ATTR = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

  /**
   * Spotify normally emits `, ` here, but a live capture on 2026-07-28 returned U+060C ARABIC
   * COMMA, so accept every comma-ish separator we might plausibly be served.
   */
  private val ARTIST_SEPARATOR = Regex("""[,;\u060C\u061B\u3001\uFF0C\uFF1B]\s*""")

  /** `og:description` joins its fields with U+00B7 MIDDLE DOT; tolerate lookalikes. */
  private val DESCRIPTION_SEPARATOR = Regex("""\s*[\u00B7\u2022\u2219\u30FB\u0387]\s*""")

  private val YEAR = Regex("""(19|20)\d{2}""")

  /** Parses every `<meta>` tag into a key → content map (first occurrence wins). */
  fun metaTags(html: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (tag in META_TAG.findAll(html)) {
      val raw = tag.value
      val key = KEY_ATTR.find(raw)?.groupValues?.get(1)?.trim() ?: continue
      val content = CONTENT_ATTR.find(raw)?.groupValues?.get(1) ?: continue
      if (key.isNotEmpty() && !out.containsKey(key)) out[key] = unescapeHtml(content)
    }
    return out
  }

  /** Returns `null` when the page carried no usable title (blocked, 404, or a JS-only shell). */
  fun parse(html: String?): SpotifyTrackMeta? {
    if (html.isNullOrBlank()) return null
    val meta = metaTags(html)

    val title = (meta["og:title"] ?: meta["twitter:title"])?.trim().orEmpty()
    if (title.isEmpty()) return null

    val description = meta["og:description"] ?: meta["twitter:description"]
    val descriptionParts = description?.split(DESCRIPTION_SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }

    val artists =
      meta["music:musician_description"]
        ?.let { splitArtists(it) }
        ?.takeIf { it.isNotEmpty() }
        ?: descriptionParts?.firstOrNull()?.let { splitArtists(it) }
        ?: emptyList()

    return SpotifyTrackMeta(
      title = title,
      artists = artists,
      album = albumFrom(meta, descriptionParts),
      durationSeconds = meta["music:duration"]?.trim()?.toIntOrNull()?.takeIf { it > 0 },
      releaseYear = yearFrom(meta, descriptionParts),
      ogType = meta["og:type"],
    )
  }

  private fun albumFrom(meta: Map<String, String>, descriptionParts: List<String>?): String? {
    // `music:album` is a URL, not a name, so the description is the only source of the album title.
    // Layout is `Artist · Album · Song · Year`; shorter forms have no album.
    if (descriptionParts != null && descriptionParts.size >= 4) {
      return descriptionParts[1].takeIf { it.isNotBlank() }
    }
    return null
  }

  private fun yearFrom(meta: Map<String, String>, descriptionParts: List<String>?): Int? {
    meta["music:release_date"]?.let { date -> YEAR.find(date)?.value?.toIntOrNull()?.let { return it } }
    descriptionParts?.lastOrNull()?.let { last -> if (YEAR.matches(last)) return last.toIntOrNull() }
    return null
  }

  private fun splitArtists(raw: String): List<String> =
    raw.split(ARTIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

  internal fun unescapeHtml(input: String): String {
    if ('&' !in input) return input
    val out = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
      val c = input[i]
      if (c != '&') {
        out.append(c)
        i++
        continue
      }
      val end = input.indexOf(';', i + 1)
      if (end == -1 || end - i > 10) {
        out.append(c)
        i++
        continue
      }
      val entity = input.substring(i + 1, end)
      val replacement =
        when {
          entity.startsWith("#x", ignoreCase = true) ->
            entity.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
          entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
          else -> NAMED_ENTITIES[entity]
        }
      if (replacement == null) {
        out.append(c)
        i++
      } else {
        out.append(replacement)
        i = end + 1
      }
    }
    return out.toString()
  }

  private val NAMED_ENTITIES =
    mapOf(
      "amp" to "&",
      "lt" to "<",
      "gt" to ">",
      "quot" to "\"",
      "apos" to "'",
      "nbsp" to "\u00A0",
      "hellip" to "\u2026",
      "mdash" to "\u2014",
      "ndash" to "\u2013",
      "middot" to "\u00B7",
      "bull" to "\u2022",
      "rsquo" to "\u2019",
      "lsquo" to "\u2018",
      "ldquo" to "\u201C",
      "rdquo" to "\u201D",
    )
}
