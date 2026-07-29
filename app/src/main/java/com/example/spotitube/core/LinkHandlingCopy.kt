package com.example.spotitube.core

/**
 * The heading and body of the link-handling card, for one [LinkHandling] state.
 *
 * Lifted out of `MainActivity` so the wording is unit-testable: it is the part a compiler cannot
 * check, and the part that was wrong. The card was titled "Make tapping links work" in *every*
 * state — including [LinkHandling.ENABLED], where it deliberately carries no button — and the body
 * there opened with the fragment "Set up.". Reported verbatim by the owner:
 *
 * > It is not clear if the "Make tapping links work" is supposed to have any actions. Intuitively
 * > it should do the setup or provide setup instructions. Now it only says "Set up. Tapping
 * > Spotify link in any app opens Spotitube directly."
 *
 * An imperative heading over a card with no button reads as an instruction whose action is
 * missing. The missing button is not the bug: the only thing a button could do in
 * [LinkHandling.ENABLED] is walk the user to a screen where they can switch link handling back
 * *off*, so offering one would be an affordance to undo the setup they just completed. The copy
 * therefore has to carry the whole job of saying "this is done, and there is nothing here for
 * you" — which is why the heading turns declarative and the body states a complete status.
 *
 * The honesty constraints on that status are all measured, not assumed (AGENTS.md §8), and are
 * pinned by `LinkHandlingCopyTest`:
 *
 * * Link taps only reach us **from apps that hand their links to Android**. Never "any app" —
 *   Telegram's "Open In-App" opens links in its own built-in browser and bypasses the setting
 *   entirely, so a blanket claim would be false on the owner's own phone.
 * * Copy → open Spotitube stays the escape hatch, and is named as such, because it is the only
 *   zero-setup route that works in Signal today.
 */
data class LinkHandlingCopy(val title: String, val body: String) {

  companion object {
    /** Imperative, because there is a button below it and something for the user to do. */
    const val SETUP_TITLE = "Make tapping links work"

    /** Declarative, because there is neither. */
    const val DONE_TITLE = "Tapping links already works"

    /**
     * @param spotifyInstalled only reaches [LinkHandling.AVAILABLE], where it decides whether the
     *   user is arriving fresh or has just completed step 1 of the Spotify handoff. Telling
     *   someone to redo step 1 at the exact moment they come back to check is the most common way
     *   to look broken.
     */
    fun of(handling: LinkHandling, spotifyInstalled: Boolean): LinkHandlingCopy =
      when (handling) {
        LinkHandling.NOT_REPORTABLE ->
          LinkHandlingCopy(
            SETUP_TITLE,
            "On this Android version, tapping a Spotify link should offer Spotitube in the " +
              "\"Open with\" list. If it does not, use the copy method below.",
          )
        LinkHandling.ENABLED ->
          LinkHandlingCopy(
            DONE_TITLE,
            "Tapping a Spotify link already opens Spotitube, in apps that hand links to " +
              "Android. There is nothing to set up here. Some apps open links in their own " +
              "built-in browser instead — Telegram's \"Open In-App\" is one — and those bypass " +
              "this setting entirely. Use the copy method below when that happens.",
          )
        LinkHandling.BLOCKED_BY_SPOTIFY ->
          LinkHandlingCopy(
            SETUP_TITLE,
            "This is the one setup worth doing: once it is done, tapping a Spotify link opens " +
              "it here, in apps that hand links to Android.\n\n" +
              "Spotify owns spotify.com, so Android has given those links to the Spotify app, " +
              "and only one app can hold them. Handing them over takes two steps:\n\n" +
              "1. In Spotify's settings, turn OFF \"Open supported links\".\n" +
              "2. Back in Spotitube's settings, turn ON \"Open supported links\" and tick the " +
              "spotify.com addresses.\n\n" +
              "If you would rather not change Spotify's settings, the copy method below works " +
              "with no setup at all.",
          )
        LinkHandling.AVAILABLE ->
          LinkHandlingCopy(
            SETUP_TITLE,
            "This is the one setup worth doing: once it is done, tapping a Spotify link opens " +
              "it here, in apps that hand links to Android.\n\n" +
              if (spotifyInstalled) {
                "Spotify is no longer holding these links, so this is now a single step: tap " +
                  "below, turn on \"Open supported links\", and tick the Spotify addresses."
              } else {
                "Android 12 and newer only open web links in an app automatically if that app " +
                  "owns the website, and we do not own spotify.com. Tap below, turn on " +
                  "\"Open supported links\", and tick the Spotify addresses."
              },
          )
      }
  }
}
