package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering tests for the launch layer.
 *
 * These encode decisions that are cheap to break and expensive to break: a wrong order can make a
 * fallback unreachable, or — worst case — send a Spotify link back into this app and loop.
 */
class LaunchPlanTest {

  private val self = "com.example.spotitube"
  private val spotify = "com.spotify.music"
  private val browser = "com.android.chrome"
  private val url = "https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6"
  private val schemeUri = "spotify:album:6eUW0wxWtzkFdaEFsTJto6"

  private fun plan(
    preferred: String? = spotify,
    fallback: String? = schemeUri,
    canHandle: (String, String) -> Boolean = { _, _ -> true },
    browserPackage: () -> String? = { browser },
  ) = LaunchPlan.attempts(url, preferred, fallback, self, canHandle, browserPackage)

  @Test
  fun `the healthy order is preferred, scheme, browser, chooser`() {
    assertEquals(
      listOf(LaunchPlan.VIA_PREFERRED, LaunchPlan.VIA_SCHEME, LaunchPlan.VIA_BROWSER, LaunchPlan.VIA_CHOOSER),
      plan().map { it.via },
    )
  }

  @Test
  fun `the scheme fallback comes before the browser or it would be unreachable`() {
    // A browser start almost always succeeds, so ordering the scheme attempt after it would skip
    // exactly the cases it exists for.
    val vias = plan().map { it.via }
    assertTrue(vias.indexOf(LaunchPlan.VIA_SCHEME) < vias.indexOf(LaunchPlan.VIA_BROWSER))
  }

  @Test
  fun `the preferred app is skipped when it cannot handle the url but the scheme is still tried`() {
    // A disabled-user package reports as installed but declares no activity. The custom scheme is
    // the whole point of layer 2, so it must survive the https pre-query failing.
    val attempts = plan(canHandle = { _, _ -> false })
    assertEquals(
      listOf(LaunchPlan.VIA_SCHEME, LaunchPlan.VIA_BROWSER, LaunchPlan.VIA_CHOOSER),
      attempts.map { it.via },
    )
    assertEquals(schemeUri, attempts.first().uri)
    assertEquals(spotify, attempts.first().packageName)
  }

  @Test
  fun `no preferred package means browser then chooser`() {
    assertEquals(
      listOf(LaunchPlan.VIA_BROWSER, LaunchPlan.VIA_CHOOSER),
      plan(preferred = null, fallback = null).map { it.via },
    )
  }

  @Test
  fun `no scheme fallback available`() {
    assertEquals(
      listOf(LaunchPlan.VIA_PREFERRED, LaunchPlan.VIA_BROWSER, LaunchPlan.VIA_CHOOSER),
      plan(fallback = null).map { it.via },
    )
  }

  @Test
  fun `no browser available still ends in a chooser`() {
    assertEquals(
      listOf(LaunchPlan.VIA_PREFERRED, LaunchPlan.VIA_SCHEME, LaunchPlan.VIA_CHOOSER),
      plan(browserPackage = { null }).map { it.via },
    )
  }

  @Test
  fun `a chooser is always the last resort and never absent`() {
    val variants =
      listOf(
        plan(),
        plan(preferred = null, fallback = null, browserPackage = { null }),
        plan(canHandle = { _, _ -> false }, browserPackage = { null }),
      )
    for (attempts in variants) {
      assertTrue("plan must never be empty", attempts.isNotEmpty())
      assertEquals(LaunchPlan.VIA_CHOOSER, attempts.last().via)
      assertTrue("only the last attempt may be the chooser", attempts.dropLast(1).none { it.isChooser })
    }
  }

  // --- the loop invariant -----------------------------------------------------------------------

  @Test
  fun `no attempt ever targets this app`() {
    // Every hostile shape at once: we are the preferred package AND the resolved browser.
    val attempts =
      LaunchPlan.attempts(url, self, schemeUri, self, { _, _ -> true }, { self })
    assertTrue("planning must not target Spotitube", attempts.none { it.packageName == self })
    // Only the chooser is left, and it excludes our components at dispatch.
    assertEquals(listOf(LaunchPlan.VIA_CHOOSER), attempts.map { it.via })
    assertNull(attempts.single().packageName)
  }

  @Test
  fun `a browser that resolves to us is discarded rather than used`() {
    val attempts = plan(preferred = null, fallback = null, browserPackage = { self })
    assertTrue(attempts.none { it.packageName == self })
    assertEquals(listOf(LaunchPlan.VIA_CHOOSER), attempts.map { it.via })
  }

  @Test
  fun `explicit attempts carry a package and the chooser does not`() {
    for (attempt in plan()) {
      if (attempt.isChooser) assertNull(attempt.packageName) else assertTrue(attempt.packageName!!.isNotEmpty())
    }
  }

  @Test
  fun `only the scheme attempt rewrites the uri`() {
    for (attempt in plan()) {
      val expected = if (attempt.via == LaunchPlan.VIA_SCHEME) schemeUri else url
      assertEquals("via=${attempt.via}", expected, attempt.uri)
    }
  }
}
