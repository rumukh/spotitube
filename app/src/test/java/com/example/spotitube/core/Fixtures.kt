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

  const val ALBUM_HTML = "spotify_album.html"

  const val RICK_ASTLEY_SEARCH_JSON = "innertube_rickastley.json"

  const val SUNFLOWER_SEARCH_JSON = "innertube_sunflower.json"
}
