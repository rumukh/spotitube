package com.example.spotitube.core

/** The kind of thing a Spotify link points at. */
enum class SpotifyEntityType {
  TRACK,
  ALBUM,
  PLAYLIST,
  ARTIST,
  SHOW,
  EPISODE,

  /** `spotify.link` / `spotify.app.link` — needs a network redirect before we know more. */
  SHORT_LINK,
}

/**
 * A recognised Spotify link.
 *
 * [canonicalUrl] is always an `https://open.spotify.com/...` URL with locale prefixes and tracking
 * query parameters removed, except for [SpotifyEntityType.SHORT_LINK] where it equals the original.
 */
data class SpotifyLink(
  val type: SpotifyEntityType,
  val id: String?,
  val canonicalUrl: String,
  val originalUrl: String,
) {
  /** Track links are the only ones we redirect to YouTube Music; everything else bounces back. */
  val isTrack: Boolean
    get() = type == SpotifyEntityType.TRACK
}

/**
 * Parses Spotify links out of URLs, `spotify:` URIs, or arbitrary shared text.
 *
 * Deliberately framework-free so it can be exercised by plain JVM unit tests.
 */
object SpotifyLinkParser {

  private val TYPES =
    mapOf(
      "track" to SpotifyEntityType.TRACK,
      "album" to SpotifyEntityType.ALBUM,
      "playlist" to SpotifyEntityType.PLAYLIST,
      "artist" to SpotifyEntityType.ARTIST,
      "show" to SpotifyEntityType.SHOW,
      "episode" to SpotifyEntityType.EPISODE,
    )

  private val WEB_HOSTS = setOf("open.spotify.com", "play.spotify.com")
  private val SHORT_HOSTS = setOf("spotify.link", "spotify.app.link")

  /** Spotify base-62 ids are 22 chars; stay a little tolerant without accepting obvious junk. */
  private const val MIN_ID_LENGTH = 16

  private val URL_IN_TEXT = Regex("""https?://[^\s<>"'\\\]}]+""", RegexOption.IGNORE_CASE)
  private val URI_IN_TEXT =
    Regex("""spotify:(?:track|album|playlist|artist|show|episode):[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)

  /** URLs pasted into chat apps often pick up trailing sentence punctuation. */
  private const val TRAILING_JUNK = ".,;:!?)]}>\"'…"

  /**
   * Finds the first Spotify link anywhere in [text] — a bare URL, a URL embedded in a sentence, or
   * a `spotify:` URI. Returns `null` when there is nothing Spotify-ish to act on.
   */
  fun findIn(text: String?): SpotifyLink? {
    if (text.isNullOrBlank()) return null

    // `spotify:` URIs first: they are unambiguous and can be embedded next to a web URL.
    URI_IN_TEXT.findAll(text).forEach { m -> parseUri(m.value)?.let { return it } }
    URL_IN_TEXT.findAll(text).forEach { m -> parse(m.value.trimEnd { it in TRAILING_JUNK })?.let { return it } }

    // Last resort: the whole string might be a bare `open.spotify.com/...` with no scheme.
    val trimmed = text.trim()
    if (!trimmed.contains(' ') && trimmed.startsWith("open.spotify.com", ignoreCase = true)) {
      return parse("https://$trimmed")
    }
    return null
  }

  /** Parses a single URL or `spotify:` URI. Returns `null` if it is not a recognised Spotify link. */
  fun parse(raw: String?): SpotifyLink? {
    val input = raw?.trim().orEmpty()
    if (input.isEmpty()) return null
    if (input.startsWith("spotify:", ignoreCase = true)) return parseUri(input)
    if (!input.startsWith("http://", ignoreCase = true) && !input.startsWith("https://", ignoreCase = true)) {
      return null
    }

    val afterScheme = input.substringAfter("://")
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringAfter('@').substringBefore(':').lowercase().removePrefix("www.")

    val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')

    if (host in SHORT_HOSTS) {
      // Nothing usable in the path — the caller has to follow redirects first.
      if (path.isBlank()) return null
      return SpotifyLink(SpotifyEntityType.SHORT_LINK, id = null, canonicalUrl = input, originalUrl = input)
    }
    if (host !in WEB_HOSTS) return null

    val segments = path.split('/').filter { it.isNotBlank() }.map { decodeSegment(it) }
    return fromSegments(segments, input)
  }

  private fun fromSegments(rawSegments: List<String>, originalUrl: String): SpotifyLink? {
    var segments = rawSegments
    // Strip locale prefixes such as `intl-de`, and the embed player prefix.
    while (segments.isNotEmpty() &&
      (segments[0].startsWith("intl-", ignoreCase = true) || segments[0].equals("embed", ignoreCase = true))
    ) {
      segments = segments.drop(1)
    }
    // Legacy `/user/<name>/playlist/<id>` form.
    if (segments.size >= 2 && segments[0].equals("user", ignoreCase = true)) {
      segments = segments.drop(2)
    }
    if (segments.size < 2) return null

    val type = TYPES[segments[0].lowercase()] ?: return null
    val id = segments[1]
    if (id.length < MIN_ID_LENGTH || !id.all { it.isLetterOrDigit() }) return null

    return SpotifyLink(
      type = type,
      id = id,
      canonicalUrl = "https://open.spotify.com/${segments[0].lowercase()}/$id",
      originalUrl = originalUrl,
    )
  }

  private fun parseUri(uri: String): SpotifyLink? {
    val parts = uri.split(':')
    if (parts.size < 3) return null
    val type = TYPES[parts[1].lowercase()] ?: return null
    val id = parts[2]
    if (id.length < MIN_ID_LENGTH || !id.all { it.isLetterOrDigit() }) return null
    return SpotifyLink(
      type = type,
      id = id,
      canonicalUrl = "https://open.spotify.com/${parts[1].lowercase()}/$id",
      originalUrl = uri,
    )
  }

  private fun decodeSegment(segment: String): String =
    if ('%' in segment) {
      runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
    } else {
      segment
    }
}
