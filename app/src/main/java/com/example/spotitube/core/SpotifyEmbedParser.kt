package com.example.spotitube.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parses `https://open.spotify.com/embed/track/{id}`, which serves a `__NEXT_DATA__` JSON island.
 *
 * Preferred over scraping the canonical page's Open Graph tags because it is **structured**: the
 * artists arrive as an array (no separator guessing — the canonical page has been observed using
 * U+060C ARABIC COMMA), the duration is in milliseconds, and there is an explicit flag. It is also
 * a third of the size and did not exhibit the JavaScript-shell variance the canonical page does.
 *
 * It carries no album name, which is why [SpotifyTrackMeta.mergedWith] exists.
 *
 * Undocumented, so every step is optional and a shape change degrades to `null`.
 */
object SpotifyEmbedParser {

  private const val MAX_DEPTH = 40

  private val NEXT_DATA =
    Regex(
      """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  fun parse(html: String?): SpotifyTrackMeta? {
    if (html.isNullOrBlank()) return null
    val payload = NEXT_DATA.find(html)?.groupValues?.get(1)?.trim() ?: return null
    val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: return null

    val entity =
      root
        .obj("props")
        .obj("pageProps")
        .obj("state")
        .obj("data")
        .obj("entity")
        ?.takeIf { it.str("name") != null || it.str("title") != null }
        ?: findTrackEntity(root, 0)
        ?: return null

    // Guard against an album or episode embed being parsed as a track.
    val type = entity.str("type")
    if (type != null && type != "track") return null

    val title = (entity.str("name") ?: entity.str("title"))?.trim().orEmpty()
    if (title.isEmpty()) return null

    val artists =
      (entity.obj("artists") as? JsonArray)
        ?.mapNotNull { it.str("name")?.trim()?.takeIf(String::isNotEmpty) }
        .orEmpty()

    val durationMillis = entity.obj("duration").int()?.takeIf { it > 0 }

    return SpotifyTrackMeta(
      title = title,
      artists = artists,
      durationSeconds = durationMillis?.let { (it + 500) / 1000 },
      durationMillis = durationMillis,
      releaseYear = releaseYear(entity),
      isExplicit = entity.obj("isExplicit").bool(),
      isPlayable = entity.obj("isPlayable").bool(),
      playabilityReason = entity.str("playabilityReason")?.takeIf { it.isNotBlank() },
      ogType = type?.let { if (it == "track") "music.song" else it },
    )
  }

  private fun releaseYear(entity: JsonElement?): Int? {
    val iso = entity.obj("releaseDate").str("isoString") ?: entity.str("releaseDate") ?: return null
    return Regex("""(19|20)\d{2}""").find(iso)?.value?.toIntOrNull()
  }

  /** Fallback for a moved entity: any object that looks like a track. */
  private fun findTrackEntity(node: JsonElement?, depth: Int): JsonElement? {
    if (node == null || depth > MAX_DEPTH) return null
    if (node is JsonObject) {
      val looksLikeTrack =
        node["type"].str() == "track" &&
          (node["name"].str() != null || node["title"].str() != null) &&
          node["duration"] != null
      if (looksLikeTrack) return node
      return node.values.firstNotNullOfOrNull { findTrackEntity(it, depth + 1) }
    }
    if (node is JsonArray) return node.firstNotNullOfOrNull { findTrackEntity(it, depth + 1) }
    return null
  }

  // --- tolerant accessors -------------------------------------------------------------------

  private fun JsonElement?.obj(key: String): JsonElement? = (this as? JsonObject)?.get(key)

  private fun JsonElement?.str(key: String): String? = this.obj(key).str()

  private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

  private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
