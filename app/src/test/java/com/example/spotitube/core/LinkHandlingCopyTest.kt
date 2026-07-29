package com.example.spotitube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link-handling card in [LinkHandling.ENABLED] deliberately has no button: the only screen a
 * button could open from there is the one where the user switches link handling back *off*. That
 * makes the wording load-bearing — it is the only thing that can tell the user the card is a
 * status and not an instruction — so it is pinned by the build here rather than left to a reader
 * to notice.
 *
 * The first three tests are regression guards, each falsified against the wording they replaced
 * ("Make tapping links work" over a body opening "Set up.") before being trusted. The rest are
 * standing guards on claims AGENTS.md §8 records as measured, and their failure messages are the
 * findings themselves.
 */
class LinkHandlingCopyTest {

  private val enabled = LinkHandlingCopy.of(LinkHandling.ENABLED, spotifyInstalled = true)

  private val stillNeedsSetup = LinkHandling.entries.filter { it != LinkHandling.ENABLED }

  @Test
  fun `the completed card never shares a heading with the cards that want something done`() {
    // The reported defect exactly: the same imperative heading on a card with no button reads as
    // an instruction whose action has gone missing.
    assertEquals("this test is worthless unless there are setup states to be distinct from", 3, stillNeedsSetup.size)
    assertNotEquals(LinkHandlingCopy.SETUP_TITLE, enabled.title)
    val setupHeadings = stillNeedsSetup.map { LinkHandlingCopy.of(it, spotifyInstalled = true).title }
    assertFalse(
      "\"${enabled.title}\" is also shown over a card that has a setup button, so it reads as an " +
        "instruction the user is being asked to act on",
      enabled.title in setupHeadings,
    )
  }

  @Test
  fun `every state that still needs setup keeps the imperative heading`() {
    for (state in stillNeedsSetup) {
      for (spotifyInstalled in listOf(true, false)) {
        assertEquals(
          "$state shows a setup button, so its heading must still ask for the setup",
          LinkHandlingCopy.SETUP_TITLE,
          LinkHandlingCopy.of(state, spotifyInstalled).title,
        )
      }
    }
  }

  @Test
  fun `the completed body is a whole status, not a truncated label`() {
    assertFalse(
      "\"Set up.\" was read as a heading-plus-label with the button missing, not as a status",
      enabled.body.startsWith("Set up"),
    )
    val opening = enabled.body.substringBefore(". ")
    assertTrue(
      "the body must open with a full sentence about what happens now; \"$opening\" is a fragment",
      opening.split(" ").size >= 5,
    )
    assertTrue(
      "with no button on this card, only the copy can answer \"is something expected of me here?\", " +
        "so it has to say so outright: ${enabled.body}",
      enabled.body.contains("nothing to set up", ignoreCase = true),
    )
  }

  @Test
  fun `the completed body keeps every limit on what link handling can promise`() {
    // Measured on the owner's phone: Telegram's "Open In-App" never hands the link to Android, and
    // Signal offers only Copy. Dropping any of these turns an honest status into an overclaim, and
    // the user is then left concluding the app is broken.
    for (limit in listOf("in apps that hand links to Android", "built-in browser", "copy method below")) {
      assertTrue(
        "the completed status stops being honest without \"$limit\": ${enabled.body}",
        enabled.body.contains(limit),
      )
    }
  }

  @Test
  fun `no state claims tapping works in any app`() {
    val anyApp = Regex("""\bany app""", RegexOption.IGNORE_CASE)
    for (state in LinkHandling.entries) {
      for (spotifyInstalled in listOf(true, false)) {
        val copy = LinkHandlingCopy.of(state, spotifyInstalled)
        assertFalse(
          "$state claims \"any app\", which an in-app browser falsifies on the owner's own phone",
          anyApp.containsMatchIn(copy.title + " " + copy.body),
        )
      }
    }
  }

  @Test
  fun `only the states with a button name the settings switch`() {
    assertFalse(
      "naming \"Open supported links\" on the completed card describes an action with no button to " +
        "reach it, and the only thing that screen still offers is switching it back off",
      enabled.body.contains("Open supported links"),
    )
    for (state in listOf(LinkHandling.BLOCKED_BY_SPOTIFY, LinkHandling.AVAILABLE)) {
      assertTrue(
        "$state sends the user to settings, so the copy must name the switch they are looking for",
        LinkHandlingCopy.of(state, spotifyInstalled = true).body.contains("Open supported links"),
      )
    }
  }

  @Test
  fun `spotify's presence changes the copy for exactly one state`() {
    assertNotEquals(
      "AVAILABLE covers both arriving fresh and arriving back having just turned Spotify's switch " +
        "off; one wording for both tells that second user to redo a step they have already done",
      LinkHandlingCopy.of(LinkHandling.AVAILABLE, spotifyInstalled = true),
      LinkHandlingCopy.of(LinkHandling.AVAILABLE, spotifyInstalled = false),
    )
    for (state in LinkHandling.entries.filter { it != LinkHandling.AVAILABLE }) {
      assertEquals(
        "$state does not depend on whether Spotify is installed",
        LinkHandlingCopy.of(state, spotifyInstalled = true),
        LinkHandlingCopy.of(state, spotifyInstalled = false),
      )
    }
  }
}
