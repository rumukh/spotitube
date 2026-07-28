package com.example.spotitube

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.spotitube.core.ResolveOutcome
import com.example.spotitube.core.LoopGuard
import com.example.spotitube.core.SpotifyEntityType
import com.example.spotitube.core.SpotifyLinkParser
import com.example.spotitube.core.SpotitubeResolver
import com.example.spotitube.net.HttpSpotifyMetadataSource
import com.example.spotitube.net.InnerTubeMusicSearch
import com.example.spotitube.theme.SpotitubeTheme
import kotlinx.coroutines.launch

/**
 * The activity that actually does the work.
 *
 * It is translucent, `noHistory` and `excludeFromRecents`, so from the user's point of view tapping
 * a Spotify link shows a brief spinner and then YouTube Music opens playing the song.
 *
 * Accepts both `ACTION_VIEW` (a real link tap, which on Android 12+ only reaches us once the user
 * enables "Open supported links") and `ACTION_SEND` of `text/plain`. The SEND filter is not a
 * web intent, so Android's domain verification cannot take it away from us — which is why it is
 * the path onboarding leads with.
 */
class LinkHandlerActivity : ComponentActivity() {

  private var status by mutableStateOf("Looking up the track…")

  /** In-flight resolve, cancelled when a newer intent arrives. */
  private var resolveJob: kotlinx.coroutines.Job? = null

  /** Incremented per intent; only the newest generation may act or finish. */
  private var currentGeneration = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SpotitubeTheme { OverlayCard(status) } }
    handle(extractInput())
  }

  /**
   * A second identical link arriving while the first is still resolving is delivered here rather
   * than starting a new instance (`Activity not started, intent has been delivered to currently
   * running top-most instance`). Without this override it would be silently dropped.
   */
  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handle(extractInput())
  }

  /** Pulls a candidate string out of whichever intent shape delivered us here. */
  private fun extractInput(): String? {
    val data = intent?.dataString
    if (!data.isNullOrBlank()) return data
    val shared = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
    if (!shared.isNullOrBlank()) return shared
    return intent?.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()
  }

  private fun handle(input: String?) {
    // Claim a generation and cancel any in-flight resolve BEFORE anything else, including the loop
    // guard. Otherwise a third identical intent that trips the guard would launch a browser while
    // the previous resolve was still completing, and both could act.
    resolveJob?.cancel()
    resolveJob = null
    val generation = ++currentGeneration

    // Never log the raw input. On a SEND this is the friend's entire message, which can contain
    // anything they wrote around the link, and logcat is readable by adb on the user's own phone.
    // The parsed link is enough to debug with: it is the part we actually act on.
    val parsed = SpotifyLinkParser.findIn(input)
    Log.i(
      TAG,
      "INPUT action=${intent?.action} link=${parsed?.canonicalUrl ?: "none"}" +
        (if (parsed == null && !input.isNullOrBlank()) " (no Spotify link in ${input.length} chars)" else ""),
    )

    // Belt-and-braces: every launch we make targets an explicit package, so a cycle should be
    // impossible. If the same link keeps coming back anyway, hand it to a browser and stop.
    //
    // Only ever guard on a PARSED link. Keying on raw input would put the friend's whole message
    // into the guard, into the log line below, and — worse — into LaunchIntents.open() as if it
    // were a URL. There is also nothing to loop on without a link: we only ever launch parsed ones.
    val guardKey = parsed?.canonicalUrl
    if (guardKey != null && loopGuard.recordAndCheck(guardKey, System.currentTimeMillis())) {
      if (generation != currentGeneration) return
      val report = LaunchIntents.open(this, guardKey, preferredPackage = null)
      Log.w(TAG, "RESULT outcome=LOOPGUARD $report")
      finish()
      return
    }

    // A second link can arrive via onNewIntent while the first is still resolving. The generation
    // was claimed at the top of handle(), so only the newest request can act or finish: otherwise
    // whichever resolve returned first would launch its result and finish() the activity, either
    // dropping the newer intent or launching two apps back to back.
    resolveJob =
      lifecycleScope.launch {
        val outcome =
          runCatching { resolver.resolve(input) }
            .getOrElse { error ->
              // Log the class only. Throwable messages from the HTTP layer can embed the URL, and
              // passing the Throwable itself would print message and stack into logcat.
              Log.w(TAG, "Resolve failed (${error.javaClass.simpleName})")
              ResolveOutcome.Unsupported(error.message ?: error.javaClass.simpleName)
            }
        if (generation != currentGeneration) return@launch
        act(outcome)
        finish()
      }
  }

  private fun act(outcome: ResolveOutcome) {
    when (outcome) {
      is ResolveOutcome.PlayOnYouTubeMusic -> {
        Log.i(
          TAG,
          "MATCH videoId=${outcome.videoId} score=${"%.3f".format(outcome.score)} " +
            "picked=\"${outcome.description}\" spotify=\"${outcome.spotify.display}\" " +
            "spotifyDuration=${outcome.spotify.durationSeconds} " +
            "explicit=${outcome.spotify.isExplicit} playable=${outcome.spotify.isPlayable}" +
            (outcome.spotify.playabilityReason?.let { " playabilityReason=$it" } ?: "") +
            " metaSource=${outcome.spotify.source ?: "unknown"}",
        )
        status = "Opening ${outcome.description}"
        val report = LaunchIntents.open(this, outcome.url, LaunchIntents.YT_MUSIC_PACKAGE)
        result("PLAY", report, extra = "videoId=${outcome.videoId} score=${"%.3f".format(outcome.score)}")
      }
      is ResolveOutcome.SearchOnYouTubeMusic -> {
        Log.i(TAG, "NO CONFIDENT MATCH reason=${outcome.reason}")
        status = "No confident match — opening search"
        val report = LaunchIntents.open(this, outcome.url, LaunchIntents.YT_MUSIC_PACKAGE)
        // The query is the artist and title the user is looking up; keep it out of logcat.
        result("SEARCH", report, extra = "reason=\"${outcome.reason}\"")
      }
      is ResolveOutcome.BounceToSpotify -> {
        // An unexpanded short link is the one case where the browser fallback actively harms the
        // user: Branch answers a browser with `browser_fallback_url=market://details?id=…`, so
        // someone without Spotify lands on a Play Store page asking them to install it — the exact
        // opposite of why they installed this app. Doing nothing is more honest.
        val unexpandedShortLink = outcome.type == SpotifyEntityType.SHORT_LINK
        if (unexpandedShortLink && !LaunchIntents.isInstalled(this, LaunchIntents.SPOTIFY_PACKAGE)) {
          Log.w(TAG, "RESULT outcome=BOUNCE started=false reason=\"short link unresolved, Spotify not installed\"")
          status = "Could not open that link"
          android.widget.Toast.makeText(
              this,
              "Spotitube: that short link could not be resolved, and Spotify is not installed",
              android.widget.Toast.LENGTH_LONG,
            )
            .show()
          return
        }
        status = "Opening in Spotify"
        val report =
          LaunchIntents.open(this, outcome.url, LaunchIntents.SPOTIFY_PACKAGE, fallbackUri = outcome.schemeUri)
        result("BOUNCE", report, extra = "spotifyType=${outcome.type}")
      }
      is ResolveOutcome.Unsupported -> {
        Log.w(TAG, "RESULT outcome=UNSUPPORTED reason=\"${outcome.reason}\"")
        status = "Not a Spotify link"
        android.widget.Toast.makeText(this, "Spotitube: ${outcome.reason}", android.widget.Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun result(kind: String, report: LaunchIntents.LaunchReport, extra: String) {
    Log.i(TAG, "RESULT outcome=$kind $report $extra")
    if (!report.started) {
      // Nothing on the device would take the link — not the target app, not a browser, not even a
      // chooser. A bare "it failed" toast leaves the user with no way forward, so hand them the
      // resolved URL on the clipboard: they can paste it wherever they like. This is the same
      // clipboard route the main screen documents, so it is a path they may already know.
      val copied = copyToClipboard(report.uri)
      android.widget.Toast.makeText(
          this,
          if (copied) {
            "Spotitube: nothing could open that link — it is on your clipboard"
          } else {
            "Spotitube: nothing could open that link"
          },
          android.widget.Toast.LENGTH_LONG,
        )
        .show()
    }
  }

  /** Puts [url] on the clipboard. Returns false if the system refused, so the toast can stay honest. */
  private fun copyToClipboard(url: String): Boolean =
    runCatching {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotitube link", url))
        true
      }
      .getOrDefault(false)

  companion object {
    /** Fixed logcat tag — the emulator verification greps for `RESULT outcome=`. */
    const val TAG = "Spotitube"

    val resolver: SpotitubeResolver by lazy {
      SpotitubeResolver(HttpSpotifyMetadataSource(), InnerTubeMusicSearch())
    }

    /** Shared across instances: the launch mode is `standard`, so every intent builds a new one. */
    private val loopGuard = LoopGuard()

    /** Kept in one place so the UI and tests describe entity types identically. */
    fun describe(type: SpotifyEntityType): String =
      when (type) {
        SpotifyEntityType.TRACK -> "track"
        SpotifyEntityType.ALBUM -> "album"
        SpotifyEntityType.PLAYLIST -> "playlist"
        SpotifyEntityType.ARTIST -> "artist"
        SpotifyEntityType.SHOW -> "show"
        SpotifyEntityType.EPISODE -> "episode"
        SpotifyEntityType.SHORT_LINK -> "short link"
      }
  }
}

@Composable
private fun OverlayCard(status: String) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
      shape = MaterialTheme.shapes.large,
      tonalElevation = 6.dp,
      shadowElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        CircularProgressIndicator(modifier = Modifier.size(34.dp))
        Text(text = "Spotitube", style = MaterialTheme.typography.titleMedium)
        Text(text = status, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}
