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
 * enables "Open supported links") and `ACTION_SEND` of `text/plain`, which always works.
 */
class LinkHandlerActivity : ComponentActivity() {

  private var status by mutableStateOf("Looking up the track…")

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
    Log.i(TAG, "INPUT action=${intent?.action} raw=${input?.take(300)}")

    // Belt-and-braces: every launch we make targets an explicit package, so a cycle should be
    // impossible. If the same link keeps coming back anyway, hand it to a browser and stop.
    val guardKey = SpotifyLinkParser.findIn(input)?.canonicalUrl ?: input.orEmpty()
    if (guardKey.isNotEmpty() && loopGuard.recordAndCheck(guardKey, System.currentTimeMillis())) {
      val report = LaunchIntents.open(this, guardKey, preferredPackage = null)
      Log.w(TAG, "RESULT outcome=LOOPGUARD $report")
      finish()
      return
    }

    lifecycleScope.launch {
      val outcome =
        runCatching { resolver.resolve(input) }
          .getOrElse { error ->
            Log.w(TAG, "Resolve failed", error)
            ResolveOutcome.Unsupported(error.message ?: error.javaClass.simpleName)
          }
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
            "spotifyDuration=${outcome.spotify.durationSeconds}",
        )
        status = "Opening ${outcome.description}"
        val report = LaunchIntents.open(this, outcome.url, LaunchIntents.YT_MUSIC_PACKAGE)
        result("PLAY", report, extra = "videoId=${outcome.videoId} score=${"%.3f".format(outcome.score)}")
      }
      is ResolveOutcome.SearchOnYouTubeMusic -> {
        Log.i(TAG, "NO CONFIDENT MATCH reason=${outcome.reason}")
        status = "No confident match — opening search"
        val report = LaunchIntents.open(this, outcome.url, LaunchIntents.YT_MUSIC_PACKAGE)
        result("SEARCH", report, extra = "query=\"${outcome.query}\" reason=\"${outcome.reason}\"")
      }
      is ResolveOutcome.BounceToSpotify -> {
        status = "Opening in Spotify"
        val report = LaunchIntents.open(this, outcome.url, LaunchIntents.SPOTIFY_PACKAGE)
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
      android.widget.Toast.makeText(this, "Spotitube: nothing could open that link", android.widget.Toast.LENGTH_LONG)
        .show()
    }
  }

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
