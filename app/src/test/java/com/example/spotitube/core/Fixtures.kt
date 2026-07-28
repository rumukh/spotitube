package com.example.spotitube.core

import java.io.InputStreamReader

/** Loads the real captured HTML/JSON responses under `app/src/test/resources/fixtures`. */
object Fixtures {

  fun read(name: String): String {
    val stream =
      Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$name")
        ?: error("Missing fixture: fixtures/$name")
    return stream.use { InputStreamReader(it, Charsets.UTF_8).readText() }
  }

  /** `open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8` — single artist, 214 s. */
  const val RICK_ASTLEY_HTML = "spotify_track_rickastley.html"

  /** `open.spotify.com/track/3KkXRkHbMCARz0aVfEt68P` — two artists, `&amp;`, 158 s. */
  const val SUNFLOWER_HTML = "spotify_track_sunflower.html"

  /** Same track, captured on a request that came back with U+060C ARABIC COMMA between artists. */
  const val SUNFLOWER_INTL_COMMA_HTML = "spotify_track_sunflower_intlcomma.html"

  /** `open.spotify.com/embed/track/4PTG3Z6ehGkBFwjybzWkR8` — `__NEXT_DATA__` payload. */
  const val RICK_ASTLEY_EMBED_HTML = "spotify_embed_rickastley.html"

  /** `open.spotify.com/embed/track/3KkXRkHbMCARz0aVfEt68P` — two artists, 158040 ms. */
  const val SUNFLOWER_EMBED_HTML = "spotify_embed_sunflower.html"

  const val ALBUM_HTML = "spotify_album.html"

  const val RICK_ASTLEY_SEARCH_JSON = "innertube_rickastley.json"

  const val SUNFLOWER_SEARCH_JSON = "innertube_sunflower.json"

  /** `open.spotify.com/embed/track/6qB3lZIfnDC8TE2245NDtO` — 夜の踊り子, sakanaction, 302920 ms. */
  const val YORU_NO_ODORIKO_EMBED = "spotify_embed_yoru_no_odoriko.html"

  /** `2jdbZGFp8KVTuk0YxDNL4l` — 高嶺の花子さん, back number, 294813 ms. */
  const val TAKANE_EMBED = "spotify_embed_takane_no_hanakosan.html"

  /** `35MeePbBnryubkVG0v8GbI` — ブルーアンバー, back number, 207845 ms. */
  const val BLUE_AMBER_EMBED = "spotify_embed_blue_amber.html"

  /** `5BC6kr6etk2Y9J62AyI4i3` — 青と夏, Mrs. GREEN APPLE, 270026 ms. */
  const val AO_TO_NATSU_EMBED = "spotify_embed_ao_to_natsu.html"

  const val YORU_NO_ODORIKO_SEARCH = "innertube_yoru_no_odoriko.json"
  const val TAKANE_SEARCH = "innertube_takane_no_hanakosan.json"
  const val BLUE_AMBER_SEARCH = "innertube_blue_amber.json"
  const val AO_TO_NATSU_SEARCH = "innertube_ao_to_natsu.json"

  /**
   * Canonical `/track/{id}` pages for the same four tracks.
   *
   * These exist because the embed payload carries **no album**, so a test built on the embed alone
   * silently measures the album-absent branch of [MatchScorer], which renormalises. The app merges
   * both sources, so on a device the album is present and mismatches cost real score. Without these,
   * the Japanese tests were green while the device fell to SEARCH.
   */
  const val YORU_NO_ODORIKO_HTML = "spotify_track_yoru_no_odoriko.html"

  const val TAKANE_HTML = "spotify_track_takane_no_hanakosan.html"

  const val BLUE_AMBER_HTML = "spotify_track_blue_amber.html"

  const val AO_TO_NATSU_HTML = "spotify_track_ao_to_natsu.html"

  /** A search whose rows carry `MUSIC_EXPLICIT_BADGE`. */
  const val EXPLICIT_SEARCH_JSON = "innertube_explicit.json"
}
