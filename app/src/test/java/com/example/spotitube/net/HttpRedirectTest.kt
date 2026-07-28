package com.example.spotitube.net

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Redirect resolution is the one place where a remote server chooses a URL we then fetch, so the
 * hostile cases are tested directly. [Http.nextRedirectUrl] is pure, so none of this needs a server.
 */
class HttpRedirectTest {

  private val spotify = Http.HostAllowList("spotify.com", "spotify.link", "spotify.app.link")

  @Test
  fun `relative and absolute locations resolve against the current url`() {
    assertEquals(
      "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8",
      Http.nextRedirectUrl("https://open.spotify.com/intl-de/track/x", "/track/4PTG3Z6ehGkBFwjybzWkR8", spotify),
    )
    assertEquals(
      "https://open.spotify.com/track/abc",
      Http.nextRedirectUrl("https://spotify.link/xyz", "https://open.spotify.com/track/abc", spotify),
    )
    // Protocol-relative locations keep the current scheme.
    assertEquals(
      "https://open.spotify.com/track/abc",
      Http.nextRedirectUrl("https://spotify.link/xyz", "//open.spotify.com/track/abc", spotify),
    )
  }

  @Test
  fun `the real android intent redirect is refused rather than crashing`() {
    // Verbatim from a live `https://spotify.link/{code}` fetched with an Android Chrome UA: it
    // answers 307 with an Android intent URI. java.net.URL cannot represent this at all, so without
    // an explicit scheme check the failure surfaces as an opaque MalformedURLException.
    val location =
      "intent://open?link_click_id=123#Intent;scheme=spotify;package=com.spotify.music;" +
        "S.browser_fallback_url=market%3A%2F%2Fdetails%3Fid%3Dcom.spotify.music;B.branch_intent=true;end"
    val e =
      assertThrows(IOException::class.java) {
        Http.nextRedirectUrl("https://spotify.link/xyz", location, spotify)
      }
    assertEquals(true, e.message!!.contains("non-HTTP redirect scheme"))
  }

  @Test
  fun `every non http scheme is refused`() {
    for (location in
      listOf(
        "spotify:track:4PTG3Z6ehGkBFwjybzWkR8",
        "market://details?id=com.spotify.music",
        "file:///etc/passwd",
        "javascript:alert(1)",
        "data:text/html;base64,PHNjcmlwdD4=",
        "ftp://example.com/x",
      )) {
      assertThrows("expected refusal for $location", IOException::class.java) {
        Http.nextRedirectUrl("https://spotify.link/xyz", location, spotify)
      }
    }
  }

  @Test
  fun `a redirect off the allow list is refused`() {
    // The SSRF-shaped case: a link shortener we do not control aiming us at an arbitrary host.
    for (location in
      listOf(
        "https://evil.example.com/",
        "http://127.0.0.1:8080/admin",
        "https://169.254.169.254/latest/meta-data/",
        // Suffix matching must not be fooled by a lookalike registrable domain.
        "https://notspotify.com/track/abc",
        "https://spotify.com.evil.example/track/abc",
      )) {
      assertThrows("expected refusal for $location", IOException::class.java) {
        Http.nextRedirectUrl("https://spotify.link/xyz", location, spotify)
      }
    }
  }

  @Test
  fun `subdomains of an allowed host are permitted but the bare suffix rule is exact`() {
    assertEquals(true, spotify.allows("open.spotify.com"))
    assertEquals(true, spotify.allows("spotify.com"))
    assertEquals(true, spotify.allows("OPEN.SPOTIFY.COM"))
    assertEquals(false, spotify.allows("notspotify.com"))
    assertEquals(false, spotify.allows("spotify.com.evil.example"))
  }

  @Test
  fun `malformed locations become IOException rather than escaping as another type`() {
    // Anything the URI parser rejects must surface as IOException so the caller's existing
    // error handling covers it; a stray IllegalArgumentException would crash the resolve instead.
    for (location in listOf("http://[bad", "ht tp://example.com", "://nohost")) {
      assertThrows("expected IOException for $location", IOException::class.java) {
        Http.nextRedirectUrl("https://spotify.link/xyz", location, spotify)
      }
    }
  }

  @Test
  fun `whitespace around a location header is tolerated`() {
    assertEquals(
      "https://open.spotify.com/track/abc",
      Http.nextRedirectUrl("https://spotify.link/xyz", "  https://open.spotify.com/track/abc  ", spotify),
    )
  }
}
