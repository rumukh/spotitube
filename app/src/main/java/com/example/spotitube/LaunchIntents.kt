package com.example.spotitube

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri

/**
 * Turns a resolved URL into an actual app launch.
 *
 * The hard requirement here is **no redirect loop**: this app registers intent filters for
 * `open.spotify.com`, so bouncing an album link "back to Spotify" with a bare `ACTION_VIEW` could
 * resolve to ourselves and spin. Every launch therefore targets an *explicit package*:
 *  1. the preferred app (Spotify / YouTube Music) when it is installed, enabled and actually
 *     handles the URL, otherwise
 *  2. a browser package discovered with a probe URL our own filters cannot match, otherwise
 *  3. a chooser that explicitly excludes our own component.
 */
object LaunchIntents {

  const val YT_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
  const val SPOTIFY_PACKAGE = "com.spotify.music"

  /**
   * Deliberately *not* a spotify.com URL: our manifest filters are host-scoped, so nothing we
   * declare can match this, which makes the resulting list browsers-only by construction.
   */
  private const val BROWSER_PROBE_URL = "https://www.example.com/"

  private const val ANDROID_RESOLVER_PACKAGE = "android"

  /**
   * Whether the system accepted the intent, plus where it went — for logging and the on-screen
   * message.
   *
   * `started` means only that the system ACCEPTED the intent — that `startActivity` did not throw.
   * It is not evidence that the target rendered anything, let alone played audio.
   *
   * [toString] deliberately omits [uri]: these lines go to logcat on the owner's personal phone,
   * and the URI carries what they are listening to. The videoId and outcome are logged separately
   * and are enough to debug with.
   */
  data class LaunchReport(val started: Boolean, val uri: String, val targetPackage: String?, val via: String) {
    override fun toString(): String = "started=$started target=${targetPackage ?: "-"} via=$via"
  }

  /** Where a launch would go. Computed separately from [open] so it can be asserted on in tests. */
  sealed interface Target {
    /** An explicit package — the only shape that is provably loop-free. */
    data class Explicit(val packageName: String, val via: String) : Target

    /** Last resort: a chooser with our own component removed from the list. */
    data object ChooserExcludingSelf : Target
  }

  /**
   * Every explicit package that could open [url], best first, with no side effects.
   *
   * Guaranteed never to contain this app: the preferred package is compared by name, and the
   * browser search probes a non-Spotify URL that none of our intent filters can match.
   */
  fun candidateTargets(context: Context, url: String, preferredPackage: String?): List<Target.Explicit> {
    val uri = url.toUri()
    val out = ArrayList<Target.Explicit>(2)
    if (preferredPackage != null &&
      preferredPackage != context.packageName &&
      canHandle(context, uri, preferredPackage)
    ) {
      out += Target.Explicit(preferredPackage, "preferred-app")
    }
    browserPackage(context)?.let { out += Target.Explicit(it, "browser-fallback") }
    return out
  }

  /** The single best target, or the chooser when nothing explicit is available. */
  fun chooseTarget(context: Context, url: String, preferredPackage: String?): Target =
    candidateTargets(context, url, preferredPackage).firstOrNull() ?: Target.ChooserExcludingSelf

  fun open(context: Context, url: String, preferredPackage: String?): LaunchReport {
    val uri = url.toUri()

    // Try each explicit target in turn: a package can pass the resolve check and still refuse the
    // start (disabled mid-flight, cross-profile restrictions, ...), and that must not cost us the
    // browser fallback.
    for (target in candidateTargets(context, url, preferredPackage)) {
      if (start(context, viewIntent(uri).setPackage(target.packageName))) {
        return LaunchReport(true, url, target.packageName, target.via)
      }
    }

    // A chooser is the only option left; exclude every component of ours that could claim the link
    // so the user cannot pick Spotitube and re-enter this same code path. EXTRA_EXCLUDE_COMPONENTS
    // is honoured *only* by a chooser intent — putting it on a plain ACTION_VIEW does nothing.
    val chooser =
      Intent.createChooser(viewIntent(uri), null).apply {
        putExtra(
          Intent.EXTRA_EXCLUDE_COMPONENTS,
          arrayOf(
            ComponentName(context, LinkHandlerActivity::class.java),
            ComponentName(context, MainActivity::class.java),
          ),
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    val started = start(context, chooser)
    return LaunchReport(started, url, null, if (started) "chooser-excluding-self" else "no-handler")
  }

  private fun viewIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /**
   * True when [packageName] is installed, enabled, and declares an activity for [uri].
   *
   * `getPackageInfo` alone is not enough: a `disabled-user` package still reports as installed but
   * throws [ActivityNotFoundException] on launch.
   */
  private fun canHandle(context: Context, uri: Uri, packageName: String): Boolean =
    context.packageManager
      .queryIntentActivities(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName), 0)
      .isNotEmpty()

  /** Installed *and* enabled. Used for the "is it installed?" line on the main screen. */
  fun isInstalled(context: Context, packageName: String): Boolean =
    try {
      context.packageManager.getApplicationInfo(packageName, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }

  /** Finds a browser that is definitely not us. Requires the `<queries>` http/https entries. */
  private fun browserPackage(context: Context): String? {
    val pm = context.packageManager
    val probe =
      Intent(Intent.ACTION_VIEW, BROWSER_PROBE_URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

    val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    if (default != null && default != ANDROID_RESOLVER_PACKAGE && default != context.packageName) return default

    return pm
      .queryIntentActivities(probe, 0)
      .asSequence()
      .map { it.activityInfo.packageName }
      .firstOrNull { it != context.packageName && it != ANDROID_RESOLVER_PACKAGE }
  }

  private fun start(context: Context, intent: Intent): Boolean =
    try {
      context.startActivity(intent)
      true
    } catch (e: ActivityNotFoundException) {
      // Log the exception CLASS, not its message: the message embeds the full Intent, data URI
      // included, which would put the listening history back into logcat by the side door.
      Log.w(LinkHandlerActivity.TAG, "No activity for ${intent.`package` ?: "chooser"} (${e.javaClass.simpleName})")
      false
    } catch (e: SecurityException) {
      Log.w(LinkHandlerActivity.TAG, "Not permitted to start ${intent.`package`} (${e.javaClass.simpleName})")
      false
    }
}
