package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkHandlingTest {

  @Test
  fun `below api 31 the state is not reportable`() {
    assertEquals(LinkHandling.NOT_REPORTABLE, LinkHandling.of(enabledForUs = null, spotifyInstalled = true))
    assertEquals(LinkHandling.NOT_REPORTABLE, LinkHandling.of(enabledForUs = null, spotifyInstalled = false))
  }

  @Test
  fun `already enabled wins regardless of spotify`() {
    assertEquals(LinkHandling.ENABLED, LinkHandling.of(enabledForUs = true, spotifyInstalled = true))
    assertEquals(LinkHandling.ENABLED, LinkHandling.of(enabledForUs = true, spotifyInstalled = false))
  }

  @Test
  fun `spotify installed and not enabled for us means a two step handoff is needed`() {
    // Spotify's assetlinks.json verifies open.spotify.com to com.spotify.music, and only one app
    // can hold a domain, so telling the user to "just turn it on" would be wrong.
    assertEquals(LinkHandling.BLOCKED_BY_SPOTIFY, LinkHandling.of(enabledForUs = false, spotifyInstalled = true))
  }

  @Test
  fun `without spotify one visit to our own settings is enough`() {
    assertEquals(LinkHandling.AVAILABLE, LinkHandling.of(enabledForUs = false, spotifyInstalled = false))
  }
}
