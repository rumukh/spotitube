package com.example.spotitube.core

/**
 * How much this app can honestly promise about intercepting Spotify link taps.
 *
 * The interesting case is [BLOCKED_BY_SPOTIFY]. Spotify publishes
 * `/.well-known/assetlinks.json` on `open.spotify.com`, `spotify.link` and `spotify.app.link`,
 * delegating `delegate_permission/common.handle_all_urls` to `com.spotify.music` (verified live on
 * 2026-07-28, along with the Lite, canary, debug and TV variants). On Android 12+ that makes those
 * domains *verified* to Spotify, and a domain can only be held by one app — so while Spotify is
 * installed and holds the association, the user cannot simply switch Spotitube on. They have to
 * turn Spotify's "Open supported links" off first.
 *
 * Sharing to the app is unaffected by all of this and always works.
 */
enum class LinkHandling {
  /** Below API 31: the platform gives us no way to report the state honestly. */
  NOT_REPORTABLE,

  /** Link taps already reach us. */
  ENABLED,

  /** Spotify is installed and holds the verified domains; a two-step handoff is required. */
  BLOCKED_BY_SPOTIFY,

  /** Nothing is holding the domains; one visit to our own settings is enough. */
  AVAILABLE;

  companion object {
    /**
     * @param enabledForUs `null` when the platform cannot report it (below API 31).
     * @param spotifyInstalled whether `com.spotify.music` is present and enabled.
     */
    fun of(enabledForUs: Boolean?, spotifyInstalled: Boolean): LinkHandling =
      when {
        enabledForUs == null -> NOT_REPORTABLE
        enabledForUs -> ENABLED
        spotifyInstalled -> BLOCKED_BY_SPOTIFY
        else -> AVAILABLE
      }
  }
}
