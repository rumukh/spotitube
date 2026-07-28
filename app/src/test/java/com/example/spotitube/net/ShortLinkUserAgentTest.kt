package com.example.spotitube.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the short-link User-Agent policy.
 *
 * These are deterministic and need no network. They encode a measured finding: with an Android
 * Chrome User-Agent, `https://spotify.link/{code}` answers 307 with
 * `Location: intent://open?...#Intent;scheme=spotify;package=com.spotify.music;...` — an Android
 * intent URI, not a URL. `Http` refuses it (see `HttpRedirectTest`), so a browser UA cannot resolve
 * a short link at all.
 *
 * The regression this catches is someone reordering or appending to the list without knowing that,
 * which would silently break short-link expansion for every user.
 *
 * The live counterpart — expanding a *real* short code end to end — cannot be written until we hold
 * a genuine `spotify.link` code minted from Spotify's share sheet; every code available to this
 * session was invented, and an invalid code exercises only Branch's unknown-code landing page.
 */
class ShortLinkUserAgentTest {

  /** Substrings that betray a browser UA. Branch serves these an `intent://` redirect. */
  private val browserMarkers = listOf("Mozilla", "AppleWebKit", "Chrome", "Safari", "Gecko", "Edg/")

  @Test
  fun `the app user agent is tried first`() {
    assertEquals(
      "APP_USER_AGENT must be first — it is the only agent observed to reach a canonical URL",
      HttpSpotifyMetadataSource.APP_USER_AGENT,
      HttpSpotifyMetadataSource.SHORT_LINK_USER_AGENTS.first(),
    )
  }

  @Test
  fun `no browser user agent is used for short links`() {
    for (agent in HttpSpotifyMetadataSource.SHORT_LINK_USER_AGENTS) {
      for (marker in browserMarkers) {
        assertFalse(
          "Browser-shaped UA '$agent' would make Branch answer with intent:// and break short-link " +
            "expansion entirely. See HttpRedirectTest for the captured payload.",
          agent.contains(marker, ignoreCase = true),
        )
      }
    }
  }

  @Test
  fun `the app user agent is truthful and identifies this app`() {
    val agent = HttpSpotifyMetadataSource.APP_USER_AGENT
    assertTrue("should name the app: '$agent'", agent.startsWith("Spotitube/"))
    for (marker in browserMarkers) {
      assertFalse("must not impersonate a browser: '$agent'", agent.contains(marker, ignoreCase = true))
    }
  }

  @Test
  fun `the short link agent list has no duplicates and is not empty`() {
    val agents = HttpSpotifyMetadataSource.SHORT_LINK_USER_AGENTS
    assertTrue("at least one agent is required", agents.isNotEmpty())
    assertEquals("duplicate agents waste a request on an already-failed path", agents.size, agents.toSet().size)
  }
}
