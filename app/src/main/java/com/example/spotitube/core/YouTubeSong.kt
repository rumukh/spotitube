package com.example.spotitube.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One candidate row from the YouTube Music "Songs" shelf. */
data class YouTubeSong(
  val videoId: String,
  val title: String,
  val artists: List<String>,
  val album: String? = null,
  val durationSeconds: Int? = null,
  /** True when the row links to a real release (`MPREb_…`), a mild "this is an official upload" hint. */
  val hasAlbumLink: Boolean = false,
  /** True when the artist run links to an artist channel rather than a plain user channel. */
  val hasArtistChannel: Boolean = false,
  /** True when the row is badged explicit; `null` when the shape did not tell us. */
  val isExplicit: Boolean? = null,
  /** Zero-based rank in YouTube's own ordering; used only to break otherwise-equal scores. */
  val position: Int = 0,
) {
  /**
   * `null` when [videoId] is not a well-formed YouTube id.
   *
   * This has to be checked here rather than relying on `ActivityNotFoundException`: YouTube Music
   * declares `music.youtube.com` with a path pattern of `.*`, so it swallows *any* URL on that host
   * — a malformed id would launch "successfully" onto an indeterminate screen with nothing for us
   * to catch. Callers fall back to the search page instead.
   */
  val watchUrl: String?
    get() = if (isValidVideoId(videoId)) "${YouTubeMusic.ORIGIN}/watch?v=$videoId" else null

  val artistLine: String
    get() = artists.joinToString(", ")

  companion object {
    private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

    fun isValidVideoId(id: String): Boolean = VIDEO_ID.matches(id)
  }
}

/** Single source of truth for the YouTube Music origin. */
object YouTubeMusic {
  /**
   * Must be `music.youtube.com`. Measured on-device: `https://www.youtube.com/watch?v=…` with
   * `setPackage(com.google.android.apps.youtube.music)` resolves to NO ACTIVITY — YouTube Music does
   * not claim that host, so a stray `www.` URL silently lands in the YouTube app or a browser.
   */
  const val ORIGIN = "https://music.youtube.com"

  fun searchUrl(query: String): String =
    "$ORIGIN/search?q=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}

/**
 * Parses the InnerTube `/youtubei/v1/search` response.
 *
 * This is an unofficial, undocumented endpoint, so every step is optional-chained and there are two
 * independent strategies: the documented path first, then a brute-force walk of the whole tree.
 * A shape change should degrade to "no candidates" (→ open the search page) rather than crash.
 */
object InnerTubeParser {

  private const val MAX_DEPTH = 64
  private const val ITEM_KEY = "musicResponsiveListItemRenderer"

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  fun parseSongs(body: String?): List<YouTubeSong> {
    if (body.isNullOrBlank()) return emptyList()
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()

    val items = itemsFromShelves(root).ifEmpty { collectItems(root, 0) }
    val seen = HashSet<String>()
    val songs = ArrayList<YouTubeSong>(items.size)
    for (item in items) {
      val song = runCatching { toSong(item, songs.size) }.getOrNull() ?: continue
      if (seen.add(song.videoId)) songs += song
    }
    return songs
  }

  /** Documented path: prefer the shelf literally titled "Songs", else take shelves in order. */
  private fun itemsFromShelves(root: JsonElement): List<JsonElement> {
    val sections =
      root
        .obj("contents")
        .obj("tabbedSearchResultsRenderer")
        .arr("tabs")
        .flatMap { tab -> tab.obj("tabRenderer").obj("content").obj("sectionListRenderer").arr("contents") }

    val shelves = sections.mapNotNull { it.obj("musicShelfRenderer") }
    if (shelves.isEmpty()) return emptyList()

    val songShelves = shelves.filter { runsText(it.obj("title")).equals("Songs", ignoreCase = true) }
    val chosen = songShelves.ifEmpty { shelves }
    return chosen.flatMap { shelf -> shelf.arr("contents").mapNotNull { it.obj(ITEM_KEY) } }
  }

  /** Fallback: find every list item anywhere in the document, whatever the surrounding shape is. */
  private fun collectItems(node: JsonElement, depth: Int): List<JsonElement> {
    if (depth > MAX_DEPTH) return emptyList()
    val out = ArrayList<JsonElement>()
    when (node) {
      is JsonObject ->
        for ((key, value) in node) {
          if (key == ITEM_KEY && value is JsonObject) {
            out += value
          } else {
            out += collectItems(value, depth + 1)
          }
        }
      is JsonArray -> for (child in node) out += collectItems(child, depth + 1)
      else -> Unit
    }
    return out
  }

  private fun toSong(item: JsonElement, position: Int): YouTubeSong? {
    val videoId =
      item.obj("playlistItemData").str("videoId")
        ?: item.obj("overlay")
          .obj("musicItemThumbnailOverlayRenderer")
          .obj("content")
          .obj("musicPlayButtonRenderer")
          .obj("playNavigationEndpoint")
          .obj("watchEndpoint")
          .str("videoId")
        ?: findVideoId(item, 0)
        ?: return null

    val columns = item.arr("flexColumns").map { it.obj("musicResponsiveListItemFlexColumnRenderer") }
    val title = runsText(columns.getOrNull(0).obj("text"))?.trim().orEmpty()
    if (title.isEmpty()) return null

    val detailRuns = columns.getOrNull(1).obj("text").arr("runs")

    val artists = ArrayList<String>()
    var album: String? = null
    var hasArtistChannel = false
    var hasAlbumLink = false
    var duration: Int? = null

    for (run in detailRuns) {
      val text = run.str("text")?.trim().orEmpty()
      if (text.isEmpty()) continue
      when (pageTypeOf(run)) {
        "MUSIC_PAGE_TYPE_ARTIST" -> {
          artists += text
          hasArtistChannel = true
        }
        "MUSIC_PAGE_TYPE_ALBUM" -> {
          if (album == null) album = text
          hasAlbumLink = true
        }
        else -> TextNormalizer.parseClockDuration(text)?.let { duration = it }
      }
    }

    // Some layouts drop the navigation endpoints entirely — fall back to splitting the raw line.
    if (artists.isEmpty()) {
      val segments =
        runsText(columns.getOrNull(1).obj("text"))
          ?.split(Regex("""\s*[\u2022\u00B7\u2219]\s*"""))
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          .orEmpty()
      segments.firstOrNull()?.let { artists += splitArtistLine(it) }
      if (album == null && segments.size >= 3) album = segments[1]
      if (duration == null) segments.lastOrNull()?.let { duration = TextNormalizer.parseClockDuration(it) }
    }
    if (duration == null) {
      duration =
        item
          .arr("fixedColumns")
          .firstNotNullOfOrNull {
            TextNormalizer.parseClockDuration(runsText(it.obj("musicResponsiveListItemFixedColumnRenderer").obj("text")))
          }
    }

    return YouTubeSong(
      videoId = videoId,
      title = title,
      artists = artists,
      album = album,
      durationSeconds = duration,
      hasAlbumLink = hasAlbumLink,
      hasArtistChannel = hasArtistChannel,
      isExplicit = explicitBadge(item),
      position = position,
    )
  }

  /**
   * `badges[].musicInlineBadgeRenderer.icon.iconType == "MUSIC_EXPLICIT_BADGE"`.
   *
   * A row that carries a `badges` array without the explicit icon is known-clean; a row with no
   * `badges` key at all tells us nothing, so it stays `null` rather than claiming "clean".
   */
  private fun explicitBadge(item: JsonElement): Boolean? {
    val badges = item.arr("badges").ifEmpty { return null }
    return badges.any {
      it.obj("musicInlineBadgeRenderer").obj("icon").str("iconType") == "MUSIC_EXPLICIT_BADGE"
    }
  }

  /** YouTube Music joins collaborating artists with `,` and `&`, unlike Spotify which uses `,` only. */
  private fun splitArtistLine(line: String): List<String> =
    line.split(Regex("""\s*(?:,|&|\sand\s|\u060C)\s*""")).map { it.trim() }.filter { it.isNotEmpty() }

  private fun pageTypeOf(run: JsonElement?): String? =
    run
      .obj("navigationEndpoint")
      .obj("browseEndpoint")
      .obj("browseEndpointContextSupportedConfigs")
      .obj("browseEndpointContextMusicConfig")
      .str("pageType")

  private fun findVideoId(node: JsonElement?, depth: Int): String? {
    if (node == null || depth > MAX_DEPTH) return null
    return when (node) {
      is JsonObject -> {
        node["videoId"].str()?.let { return it }
        node.values.firstNotNullOfOrNull { findVideoId(it, depth + 1) }
      }
      is JsonArray -> node.firstNotNullOfOrNull { findVideoId(it, depth + 1) }
      else -> null
    }
  }

  private fun runsText(textNode: JsonElement?): String? {
    val runs = textNode.arr("runs")
    if (runs.isNotEmpty()) return runs.joinToString("") { it.str("text").orEmpty() }
    return textNode.str("simpleText")
  }

  // --- tolerant accessors -------------------------------------------------------------------

  private fun JsonElement?.obj(key: String): JsonElement? = (this as? JsonObject)?.get(key)

  private fun JsonElement?.arr(key: String): List<JsonElement> = (this?.obj(key) as? JsonArray) ?: emptyList()

  private fun JsonElement?.str(key: String): String? = this.obj(key).str()

  private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
