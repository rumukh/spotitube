package com.example.spotitube

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.spotitube.core.ResolveOutcome
import com.example.spotitube.core.SpotifyLinkParser
import com.example.spotitube.theme.SpotitubeTheme
import kotlinx.coroutines.launch

/** Explains the app, exposes the one-tap link-handling setting, and can self-test over real network. */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      SpotitubeTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { HomeScreen() }
      }
    }
  }
}

private const val SELF_TEST_TRACK = "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8"

@Composable
private fun HomeScreen() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var link by remember { mutableStateOf("") }
  var selfTestOutput by remember { mutableStateOf<String?>(null) }
  var running by remember { mutableStateOf(false) }

  val linkHandlingEnabled = remember { linkHandlingEnabled(context) }
  val ytMusicInstalled = remember { LaunchIntents.isInstalled(context, LaunchIntents.YT_MUSIC_PACKAGE) }
  val spotifyInstalled = remember { LaunchIntents.isInstalled(context, LaunchIntents.SPOTIFY_PACKAGE) }

  Column(
    modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Spotitube", style = MaterialTheme.typography.headlineMedium)
    Text(
      "Open a Spotify track link and Spotitube finds the same song on YouTube Music and starts " +
        "playing it. Albums, playlists, artists and podcasts are handed straight back to Spotify.",
      style = MaterialTheme.typography.bodyMedium,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Opening links automatically", style = MaterialTheme.typography.titleMedium)
        when {
          Build.VERSION.SDK_INT < Build.VERSION_CODES.S ->
            Text(
              "On this Android version Spotitube already appears when you tap a Spotify link.",
              style = MaterialTheme.typography.bodySmall,
            )
          linkHandlingEnabled == true ->
            Text(
              "Enabled. Tapping a Spotify link opens Spotitube directly.",
              style = MaterialTheme.typography.bodySmall,
            )
          else ->
            Text(
              "Android 12 and newer only let an app open web links automatically if it owns the " +
                "website — and nobody but Spotify owns spotify.com. Tap below, turn on " +
                "\"Open supported links\", and tick the Spotify addresses. Then link taps come here.",
              style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { openLinkSettings(context) }) { Text("Open link settings") }
        Text(
          "Either way, sharing always works: in any chat, use Share \u2192 Spotitube.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Try a link", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
          value = link,
          onValueChange = { link = it },
          label = { Text("Paste a Spotify link") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        val parsed = remember(link) { SpotifyLinkParser.findIn(link) }
        Text(
          text =
            parsed?.let { "Recognised: ${LinkHandlerActivity.describe(it.type)}" }
              ?: if (link.isBlank()) "" else "Not a Spotify link",
          style = MaterialTheme.typography.bodySmall,
        )
        Button(
          enabled = parsed != null,
          onClick = {
            context.startActivity(
              Intent(context, LinkHandlerActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, link)
            )
          },
        ) {
          Text("Open it")
        }
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connection self-test", style = MaterialTheme.typography.titleMedium)
        Text(
          "Runs the real pipeline against a known track over the live network \u2014 no key, no login " +
            "\u2014 and reports what it matched, without opening anything.",
          style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedButton(
            enabled = !running,
            onClick = {
              running = true
              selfTestOutput = null
              scope.launch {
                selfTestOutput = runSelfTest()
                running = false
              }
            },
          ) {
            Text("Run self-test")
          }
          if (running) CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
        selfTestOutput?.let {
          HorizontalDivider()
          SelectionContainer {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
          }
        }
      }
    }

    Text(
      "YouTube Music installed: $ytMusicInstalled \u00B7 Spotify installed: $spotifyInstalled",
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

/** Executes the full resolve pipeline and renders it as text. Never launches an app. */
private suspend fun runSelfTest(): String {
  val started = System.currentTimeMillis()
  val outcome =
    runCatching { LinkHandlerActivity.resolver.resolve(SELF_TEST_TRACK) }
      .getOrElse { return "FAILED: ${it.javaClass.simpleName}: ${it.message}" }
  val elapsed = System.currentTimeMillis() - started
  return when (outcome) {
    is ResolveOutcome.PlayOnYouTubeMusic ->
      buildString {
        appendLine("OK in ${elapsed}ms")
        appendLine("spotify : ${outcome.spotify.display}")
        appendLine("duration: ${outcome.spotify.durationSeconds}s")
        appendLine("matched : ${outcome.description}")
        appendLine("videoId : ${outcome.videoId}")
        append("score   : ${"%.3f".format(outcome.score)}")
      }
    is ResolveOutcome.SearchOnYouTubeMusic ->
      "NO CONFIDENT MATCH in ${elapsed}ms\nquery: ${outcome.query}\nwhy  : ${outcome.reason}"
    is ResolveOutcome.BounceToSpotify -> "Unexpected bounce to ${outcome.url}"
    is ResolveOutcome.Unsupported -> "Unsupported: ${outcome.reason}"
  }
}

/** `null` when the platform cannot tell us (below API 31). */
private fun linkHandlingEnabled(context: Context): Boolean? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
  return runCatching { linkHandlingEnabledS(context) }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.S)
private fun linkHandlingEnabledS(context: Context): Boolean {
  val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return false
  val state = manager.getDomainVerificationUserState(context.packageName) ?: return false
  return state.hostToStateMap.values.any {
    it == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
      it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
  }
}

private fun openLinkSettings(context: Context) {
  val uri = "package:${context.packageName}".toUri()
  val intents =
    buildList {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri))
      }
      add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
    }
  for (intent in intents) {
    if (runCatching { context.startActivity(intent) }.isSuccess) return
  }
}
