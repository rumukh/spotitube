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
 * Sharing to the app needs no setup, **where the sending app offers a share option**: `ACTION_SEND`
 * is not a web intent, so domain verification cannot take it away from us. The distinction matters —
 * the failure mode is reachability, not reliability. SEND handling is measured working on hardware,
 * but Signal silently copies a link and Telegram offers only Open / Open In-App / Copy Link, so the
 * user often has no way to reach it. Claiming sharing "always works" would be technically
 * defensible and practically misleading.
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
     * @param spotifyHoldsLinks whether Spotify is *currently* the app that opens `open.spotify.com`.
     *   Distinct from [spotifyInstalled]: once the user completes step 1 of the handoff, Spotify is
     *   still installed but no longer holds the domain, and telling them to redo step 1 at that
     *   point — the exact moment they come back to check — is the most common way to look broken.
     */
    fun of(enabledForUs: Boolean?, spotifyInstalled: Boolean, spotifyHoldsLinks: Boolean): LinkHandling =
      when {
        enabledForUs == null -> NOT_REPORTABLE
        enabledForUs -> ENABLED
        spotifyInstalled && spotifyHoldsLinks -> BLOCKED_BY_SPOTIFY
        else -> AVAILABLE
      }
  }
}
