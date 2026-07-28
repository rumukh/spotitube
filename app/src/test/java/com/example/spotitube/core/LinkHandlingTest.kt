package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkHandlingTest {

  @Test
  fun `below api 31 the state is not reportable`() {
    assertEquals(
      LinkHandling.NOT_REPORTABLE,
      LinkHandling.of(enabledForUs = null, spotifyInstalled = true, spotifyHoldsLinks = true),
    )
    assertEquals(
      LinkHandling.NOT_REPORTABLE,
      LinkHandling.of(enabledForUs = null, spotifyInstalled = false, spotifyHoldsLinks = false),
    )
  }

  @Test
  fun `already enabled wins regardless of spotify`() {
    assertEquals(
      LinkHandling.ENABLED,
      LinkHandling.of(enabledForUs = true, spotifyInstalled = true, spotifyHoldsLinks = true),
    )
    assertEquals(
      LinkHandling.ENABLED,
      LinkHandling.of(enabledForUs = true, spotifyInstalled = false, spotifyHoldsLinks = false),
    )
  }

  @Test
  fun `spotify holding the domain means a two step handoff is needed`() {
    // Spotify's assetlinks.json verifies open.spotify.com to com.spotify.music, and only one app
    // can hold a domain, so telling the user to "just turn it on" would be wrong.
    assertEquals(
      LinkHandling.BLOCKED_BY_SPOTIFY,
      LinkHandling.of(enabledForUs = false, spotifyInstalled = true, spotifyHoldsLinks = true),
    )
  }

  @Test
  fun `step one already done drops to a single step`() {
    // The user turned Spotify's "Open supported links" off and came straight back to check. Spotify
    // is still installed but no longer holds the domain, so telling them to redo step 1 here — the
    // exact moment they look — is the most common way to appear broken.
    assertEquals(
      LinkHandling.AVAILABLE,
      LinkHandling.of(enabledForUs = false, spotifyInstalled = true, spotifyHoldsLinks = false),
    )
  }

  @Test
  fun `without spotify one visit to our own settings is enough`() {
    assertEquals(
      LinkHandling.AVAILABLE,
      LinkHandling.of(enabledForUs = false, spotifyInstalled = false, spotifyHoldsLinks = false),
    )
  }

  @Test
  fun `a stale holds-links reading cannot resurrect the two step card once we are enabled`() {
    // Ordering guard: enablement is checked before the Spotify branches, so a lagging PackageManager
    // reading cannot re-show setup instructions to a user who is already set up.
    assertEquals(
      LinkHandling.ENABLED,
      LinkHandling.of(enabledForUs = true, spotifyInstalled = true, spotifyHoldsLinks = true),
    )
  }
}
