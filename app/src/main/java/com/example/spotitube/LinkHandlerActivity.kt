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
import com.example.spotitube.core.LatestLinkCoordinator
import com.example.spotitube.core.LinkRequestOutcome
import com.example.spotitube.core.LoopGuard
import com.example.spotitube.core.OutcomeLog
import com.example.spotitube.core.OwnerGeneration
import com.example.spotitube.core.ResolveOutcome
import com.example.spotitube.core.SpotifyEntityType
import com.example.spotitube.core.SpotifyLinkParser
import com.example.spotitube.core.SpotitubeResolver
import com.example.spotitube.core.YouTubeMusic
import com.example.spotitube.net.HttpSpotifyMetadataSource
import com.example.spotitube.net.InnerTubeMusicSearch
import com.example.spotitube.theme.SpotitubeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

  /**
   * INSTANCE field, deliberately outside the companion object, and the placement is the whole
   * point: a shared counter would make a superseded instance refuse to `finish()` — because a newer
   * *instance* would have advanced it — leaving a translucent handler window stranded on screen.
   *
   * Behavioural tests cannot catch that regression: they construct one [OwnerGeneration] per
   * simulated owner, which is the correct design rather than whatever production happens to wire.
   * `ownerIsNotSharedBetweenInstances` in OwnerGenerationTest asserts the placement instead.
   */
  private val owner = OwnerGeneration()

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

  /**
   * Which route delivered this intent, for the log line only.
   *
   * `ACTION_SEND` alone cannot tell an external share apart from our own in-app buttons, and they
   * now carry very different weight: Signal and Telegram do not expose the share sheet on a link,
   * so clipboard is the zero-setup path that actually gets used while share is comparatively rare.
   *
   * Our own launches tag themselves. Nothing is trusted from this beyond a log label — a hostile
   * app could set the extra, and all it would achieve is a mislabelled diagnostic.
   */
  private fun inputSource(): String =
    intent?.getStringExtra(EXTRA_SOURCE)
      ?: when (intent?.action) {
        android.content.Intent.ACTION_VIEW -> SOURCE_VIEW
        android.content.Intent.ACTION_SEND -> SOURCE_SHARE
        else -> "unknown"
      }

  private fun handle(input: String?) {
    // Claim ownership BEFORE anything else. One Activity instance can receive two links via
    // onNewIntent, and both await in this same lifecycleScope: without this, the older request's
    // Superseded callback calls finish(), destroying the scope that the NEWER request's waiter is
    // suspended in. See OwnerGeneration.
    val myGeneration = owner.next()

    // Never log the raw input. On a SEND this is the friend's entire message, which can contain
    // anything they wrote around the link, and logcat is readable by adb on the user's own phone.
    // The parsed link is enough to debug with: it is the part we actually act on.
    val parsed = SpotifyLinkParser.findIn(input)
    Log.i(
      TAG,
      "INPUT action=${intent?.action} source=${inputSource()} link=${parsed?.canonicalUrl ?: "none"}" +
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
      // Invalidate central work FIRST, so an older resolve cannot launch after this escape hatch
      // has already acted.
      coordinator.supersedeAll()
      val report = LaunchIntents.open(this, guardKey, preferredPackage = null)
      Log.w(TAG, "RESULT outcome=LOOPGUARD $report")
      finish()
      return
    }

    // Arbitration is PROCESS-scoped and the resolve job is NOT a child of this Activity: two
    // different link URIs create two independent instances, and a lifecycle-tied job for the newest
    // request would be killed when the superseded instance finishes.
    //
    // The Activity holds a ticket and awaits it. The coordinator never holds a reference back, so
    // an Activity cannot be leaked into the process singleton.
    val ticket = coordinator.submit(input)
    lifecycleScope.launch {
      when (val outcome = ticket.await()) {
        is LinkRequestOutcome.Superseded -> {
          // Exit silently: no launch, no search, no toast, nothing logged as a failure. Not a
          // RESULT line, so it can never be mistaken for an outcome.
          Log.i(TAG, "SUPERSEDED link=${parsed?.canonicalUrl ?: "none"}")
          // Only close the Activity if nothing NEWER has since taken ownership of this instance.
          // Finishing here regardless would destroy the lifecycleScope that the newer request's
          // waiter is suspended in, and its result would never be consumed.
          if (owner.isCurrent(myGeneration)) finish()
        }
        is LinkRequestOutcome.Resolved -> {
          // Atomic: a resolve that completed moments before a newer submission must not act now.
          if (owner.isCurrent(myGeneration) && !isFinishing && !isDestroyed) {
            coordinator.consumeIfCurrent(ticket) { act(outcome.value) }
            finish()
          }
        }
        is LinkRequestOutcome.Failed -> {
          // Log the class only. Throwable messages from the HTTP layer can embed the URL, and
          // passing the Throwable itself would print message and stack into logcat.
          Log.w(TAG, "Resolve failed (${outcome.error.javaClass.simpleName})")
          if (owner.isCurrent(myGeneration) && !isFinishing && !isDestroyed) {
            coordinator.consumeIfCurrent(ticket) {
              act(ResolveOutcome.Unsupported(outcome.error.javaClass.simpleName))
            }
            finish()
          }
        }
      }
    }
  }

  private fun act(outcome: ResolveOutcome) {
    when (outcome) {
      is ResolveOutcome.PlayOnYouTubeMusic -> {
        // Identifiers and numbers only, and the two paths are now held to the SAME standard.
        //
        // They were not. `MATCH` carried `picked="<candidate>"` and `spotify="<the user's own
        // track>"` as plain text on every successful play, since the first version — while the
        // SEARCH branch below was carefully keeping the query out. The path we were careful about
        // disclosed strictly less than the one we were not, which is how the gap survived: the
        // device audit grepped for `uri=`, `query=` and message bodies, and a leak in a field
        // nobody suspected cannot be found that way.
        //
        // Do not re-add a name here. The videoId resolves to the same recording via public oEmbed,
        // so correlation is unaffected, and the on-screen status keeps the readable name where it
        // belongs.
        //
        // The numbers are named individually for the same reason the names are absent: one bare
        // `score` field carried the RANK here while the threshold was applied to the CORE, and the
        // search branch used that same word for the core in its reason text. A device baseline
        // comparing "the score" of a play against "the score" of a search was comparing different
        // quantities. Both, and the bar they are judged against, now come from one renderer shared
        // with the search path.
        //
        // The whole field list is assembled in core/OutcomeLog.kt, which is what makes the exact
        // string that reaches logcat assertable from a JVM unit test — including a poison-sentinel
        // test proving no title, artist, album or upstream text can appear in it. Interpolating
        // fields here instead would put them back beyond the reach of any test this project runs.
        Log.i(TAG, "MATCH ${OutcomeLog.matchLine(outcome)}")
        status = "Opening ${outcome.description}"
        val report = LaunchIntents.open(this, outcome.url, LaunchIntents.YT_MUSIC_PACKAGE)
        result("PLAY", report, extra = OutcomeLog.playResultFields(outcome))
      }
      is ResolveOutcome.SearchOnYouTubeMusic -> {
        Log.i(TAG, "NO CONFIDENT MATCH reason=${outcome.reason}${outcome.diagnostic?.let { " $it" } ?: ""}")
        status = "No confident match — opening search"
        val report = LaunchIntents.open(this, outcome.url, LaunchIntents.YT_MUSIC_PACKAGE)
        // The query is the artist and title the user is looking up; keep it out of logcat. The
        // diagnostic identifies the losing candidate by videoId and sub-scores only — no title,
        // artist or album text. That is now the same standard the MATCH branch above is held to;
        // it did not used to be, and an earlier version of this comment cited MATCH's `picked=`
        // field as the precedent that made a name here acceptable. That field is gone. If you find
        // yourself justifying a name on one path by pointing at the other, check the other first.
        result(
          "SEARCH",
          report,
          extra =
            "strategy=${YouTubeMusic.SEARCH_STRATEGY} reason=\"${outcome.reason}\"" +
              (outcome.diagnostic?.let { " $it" } ?: ""),
        )
      }
      is ResolveOutcome.BounceToSpotify -> {
        // An unexpanded short link is the one case where the browser fallback actively harms the
        // user. This is not a guess about Branch's behaviour — it is measured: for a non-browser
        // client Branch's body does `location.replace("market://details?id=com.spotify.music")`,
        // and its intent carries `browser_fallback_url=market://details?id=com.spotify.music`.
        // Branch is *actively trying* to send the user to install Spotify. Hand it to a browser and
        // someone without Spotify lands on a Play Store page — the exact opposite of why they
        // installed this app. Doing nothing is more honest.
        val unexpandedShortLink = outcome.type == SpotifyEntityType.SHORT_LINK
        if (unexpandedShortLink && !LaunchIntents.isInstalled(this, LaunchIntents.SPOTIFY_PACKAGE)) {
          // The most total of total failures: nothing was launched and nothing can be. Route it
          // through the same clipboard recovery as any other dead end, or this is the one path
          // that leaves the user with no way to reach their own link.
          //
          // Copy the ORIGINAL short URL, not a canonical one — we never resolved it, so the short
          // URL is the only address we actually have.
          val copied = copyToClipboard(outcome.url)
          Log.w(
            TAG,
            "RESULT outcome=BOUNCE started=false copied=$copied " +
              "reason=\"short link unresolved, Spotify not installed\"",
          )
          status = "Could not open that link"
          android.widget.Toast.makeText(
              this,
              if (copied) {
                "Spotitube: that short link could not be resolved and Spotify is not installed — " +
                  "the link is on your clipboard"
              } else {
                "Spotitube: that short link could not be resolved, and Spotify is not installed"
              },
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

    /** Set by our own in-app launches so the log can tell them from an external share. */
    const val EXTRA_SOURCE = "com.example.spotitube.extra.SOURCE"

    /** Tapped a link somewhere else on the device. */
    const val SOURCE_VIEW = "view"

    /** Another app's share sheet. Rare in practice — Signal and Telegram do not offer it on links. */
    const val SOURCE_SHARE = "share"

    /** The one-tap card on our own main screen, fed by the clipboard. */
    const val SOURCE_CLIPBOARD = "clipboard"

    /** The paste box on our own main screen. */
    const val SOURCE_MANUAL = "manual"

    val resolver: SpotitubeResolver by lazy {
      SpotitubeResolver(HttpSpotifyMetadataSource(), InnerTubeMusicSearch())
    }

    /** Shared across instances: the launch mode is `standard`, so every intent builds a new one. */
    private val loopGuard = LoopGuard()

    /**
     * Process-scoped, and application-scoped rather than lifecycle-scoped.
     *
     * Both properties are load-bearing. Per-instance state cannot arbitrate between two activity
     * instances, and a lifecycle-scoped job for the newest request would be killed when the
     * superseded activity finishes.
     */
    private val coordinator =
      LatestLinkCoordinator<ResolveOutcome>(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        resolve = { resolver.resolve(it) },
      )

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
