package com.example.spotitube.core

/** One attempt in a launch plan. A null [packageName] means the excluding-self chooser. */
data class LaunchAttempt(val uri: String, val packageName: String?, val via: String) {
  val isChooser: Boolean
    get() = packageName == null
}

/**
 * The order in which we try to open a resolved URL, as pure data.
 *
 * This lives in `core` and takes its Android facts as lambdas so the ordering — which encodes
 * several decisions that are easy to get wrong and expensive to get wrong — can be unit-tested
 * without a device.
 *
 * The invariant that matters most: **no attempt may ever target this app**, or a Spotify link could
 * re-enter the handler and spin.
 */
object LaunchPlan {

  const val VIA_PREFERRED = "preferred-app"
  const val VIA_SCHEME = "scheme-fallback"
  const val VIA_BROWSER = "browser-fallback"
  const val VIA_CHOOSER = "chooser-excluding-self"

  /**
   * @param canHandle whether a package declares an activity for a URI (a pre-query, not a guess).
   * @param browserPackage an explicitly resolved browser that is not us, or `null`.
   */
  fun attempts(
    url: String,
    preferredPackage: String?,
    fallbackUri: String?,
    selfPackage: String,
    canHandle: (uri: String, packageName: String) -> Boolean,
    browserPackage: () -> String?,
  ): List<LaunchAttempt> {
    val out = ArrayList<LaunchAttempt>(4)

    if (preferredPackage != null && preferredPackage != selfPackage) {
      // Layer 1: the preferred app on the canonical https URL — forwards the user's original link
      // unmodified, so it is the safest thing to try first.
      if (canHandle(url, preferredPackage)) {
        out += LaunchAttempt(url, preferredPackage, VIA_PREFERRED)
      }
      // Layer 2: a validated custom-scheme URI at the SAME package, before falling out to a
      // browser. Ordering it after the browser would make it unreachable, because a browser start
      // almost always succeeds — and the two cases this exists for are precisely a false negative
      // from the pre-query and an https start that fails despite it.
      if (fallbackUri != null) {
        out += LaunchAttempt(fallbackUri, preferredPackage, VIA_SCHEME)
      }
    }

    // Layer 3: an explicitly resolved browser. Explicit so it is provably not us.
    browserPackage()?.takeIf { it != selfPackage }?.let { out += LaunchAttempt(url, it, VIA_BROWSER) }

    // Layer 4: a chooser with our own components excluded. Always present, always last.
    out += LaunchAttempt(url, null, VIA_CHOOSER)
    return out
  }
}
