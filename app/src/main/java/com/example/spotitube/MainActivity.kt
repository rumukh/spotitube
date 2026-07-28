package com.example.spotitube

import android.content.ClipDescription
import android.content.ClipboardManager
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.spotitube.core.LinkHandling
import com.example.spotitube.core.ResolveOutcome
import com.example.spotitube.core.SpotifyLinkParser
import com.example.spotitube.theme.SpotitubeTheme
import kotlinx.coroutines.CancellationException
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

/**
 * The host every real shared Spotify link uses. `play.spotify.com` is also declared in the manifest
 * for legacy links, but selecting it alone tells us nothing about whether ordinary links reach us.
 */
private const val PRIMARY_LINK_HOST = "open.spotify.com"

/**
 * A canonical Spotify URL used only to ask the PackageManager *who currently opens this*. Never
 * fetched or launched — the id is a real track so the shape is beyond question.
 */
private const val PROBE_TRACK_URL = "https://$PRIMARY_LINK_HOST/track/4PTG3Z6ehGkBFwjybzWkR8"

@Composable
private fun HomeScreen() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var link by remember { mutableStateOf("") }
  var selfTestOutput by remember { mutableStateOf<String?>(null) }
  var running by remember { mutableStateOf(false) }

  // Everything below can change while the user is away in Settings or the Play Store. The settings
  // handoff is the headline flow, so showing stale instructions the moment the user returns from
  // completing it would be the worst possible time to be wrong — refresh it all on resume.
  var linkHandlingAllowed by remember { mutableStateOf(linkHandlingEnabled(context)) }
  var spotifyInstalled by
    remember { mutableStateOf(LaunchIntents.isInstalled(context, LaunchIntents.SPOTIFY_PACKAGE)) }
  var spotifyHoldsLinks by
    remember { mutableStateOf(LaunchIntents.holdsLinksFor(context, PROBE_TRACK_URL, LaunchIntents.SPOTIFY_PACKAGE)) }
  var ytMusicInstalled by
    remember { mutableStateOf(LaunchIntents.isInstalled(context, LaunchIntents.YT_MUSIC_PACKAGE)) }

  // Zero-setup path, and the only one that works in Signal or Telegram: if the clipboard already
  // holds a Spotify link, offer to open it in one tap. Read only while genuinely foregrounded and
  // only when it parses as a Spotify link; never logged, never persisted, and never launched
  // without the user tapping — an unprompted launch would be both spooky and, on Android 12+,
  // accompanied by a baffling "pasted from your clipboard" toast.
  var clipboardLink by remember { mutableStateOf<String?>(null) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        clipboardLink = spotifyLinkInClipboard(context)
        linkHandlingAllowed = linkHandlingEnabled(context)
        spotifyInstalled = LaunchIntents.isInstalled(context, LaunchIntents.SPOTIFY_PACKAGE)
        spotifyHoldsLinks = LaunchIntents.holdsLinksFor(context, PROBE_TRACK_URL, LaunchIntents.SPOTIFY_PACKAGE)
        ytMusicInstalled = LaunchIntents.isInstalled(context, LaunchIntents.YT_MUSIC_PACKAGE)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val linkHandling = LinkHandling.of(linkHandlingAllowed, spotifyInstalled, spotifyHoldsLinks)

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

    clipboardLink?.let { copied ->
      val parsedClip = remember(copied) { SpotifyLinkParser.findIn(copied) }
      if (parsedClip != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ready to open", style = MaterialTheme.typography.titleMedium)
            Text(
              "You copied a Spotify ${LinkHandlerActivity.describe(parsedClip.type).lowercase()} link.",
              style = MaterialTheme.typography.bodySmall,
            )
            Button(
              onClick = {
                context.startActivity(
                  Intent(context, LinkHandlerActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, copied)
                    .putExtra(LinkHandlerActivity.EXTRA_SOURCE, LinkHandlerActivity.SOURCE_CLIPBOARD)
                )
              },
            ) {
              Text(if (parsedClip.isTrack) "Open in YouTube Music" else "Open in Spotify")
            }
            Text(
              "This works whenever the copied link is still on your clipboard: copy a link, open " +
                "Spotitube, tap once. No setup needed.",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Make tapping links work", style = MaterialTheme.typography.titleMedium)
        when (linkHandling) {
          LinkHandling.NOT_REPORTABLE ->
            Text(
              "On this Android version, tapping a Spotify link should offer Spotitube in the " +
                "\"Open with\" list. If it does not, use the copy method below.",
              style = MaterialTheme.typography.bodySmall,
            )
          LinkHandling.ENABLED ->
            Text(
              "Set up. Tapping a Spotify link opens Spotitube, in apps that hand links to " +
                "Android. Some apps open links in their own built-in browser instead — " +
                "Telegram's \"Open In-App\" is one — and those bypass this setting entirely. Use " +
                "the copy method below when that happens.",
              style = MaterialTheme.typography.bodySmall,
            )
          LinkHandling.BLOCKED_BY_SPOTIFY ->
            Text(
              "This is the one setup worth doing: once it is done, tapping a Spotify link opens " +
                "it here, in apps that hand links to Android.\n\n" +
                "Spotify owns spotify.com, so Android has given those links to the Spotify app, " +
                "and only one app can hold them. Handing them over takes two steps:\n\n" +
                "1. In Spotify's settings, turn OFF \"Open supported links\".\n" +
                "2. Back in Spotitube's settings, turn ON \"Open supported links\" and tick the " +
                "spotify.com addresses.\n\n" +
                "If you would rather not change Spotify's settings, the copy method below works " +
                "with no setup at all.",
              style = MaterialTheme.typography.bodySmall,
            )
          LinkHandling.AVAILABLE ->
            Text(
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
              style = MaterialTheme.typography.bodySmall,
            )
        }

        if (linkHandling == LinkHandling.BLOCKED_BY_SPOTIFY) {
          OutlinedButton(onClick = { openLinkSettings(context, LaunchIntents.SPOTIFY_PACKAGE) }) {
            Text("1. Spotify's link settings")
          }
          Button(onClick = { openLinkSettings(context, context.packageName) }) {
            Text("2. Spotitube's link settings")
          }
        } else if (linkHandling != LinkHandling.ENABLED) {
          Button(onClick = { openLinkSettings(context, context.packageName) }) { Text("Open link settings") }
        }
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("No setup? Copy the link", style = MaterialTheme.typography.titleMedium)
        Text(
          "Long-press the link in your chat, choose Copy, then open Spotitube — the link appears " +
            "at the top, ready to play in one tap.\n\n" +
            "Sharing needs no setup either, where the sending app offers a share option. Some " +
            "apps, including Signal and Telegram, only offer Copy on a link — that is the app's " +
            "choice, not a fault in Spotitube, and the copy method above always gets you there.",
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
                .putExtra(LinkHandlerActivity.EXTRA_SOURCE, LinkHandlerActivity.SOURCE_MANUAL)
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

/**
 * The canonical Spotify URL on the clipboard, or `null`.
 *
 * Returns only the parsed canonical link, never the surrounding text: the clipboard may hold a
 * whole message and only the link is ours to act on. Uses `item.text` rather than `coerceToText`,
 * which would dereference a clipboard content URI — a provider read, on the main thread, against
 * arbitrary data. Nothing here is logged or persisted.
 */
private fun spotifyLinkInClipboard(context: Context): String? {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
  val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
  if (clip.itemCount == 0) return null
  if (clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true &&
    clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) != true
  ) {
    return null
  }
  val text = runCatching { clip.getItemAt(0).text?.toString() }.getOrNull()?.trim().orEmpty()
  if (text.isEmpty() || text.length > 2048) return null
  return SpotifyLinkParser.findIn(text)?.canonicalUrl
}

/** Executes the full resolve pipeline and renders it as text. Never launches an app. */
private suspend fun runSelfTest(): String {
  val started = System.currentTimeMillis()
  // `runCatching` would swallow CancellationException and render it as "FAILED: ...", telling the
  // user the network is broken when in fact they simply left the screen. Cancellation is rethrown
  // so it propagates; only a genuine failure becomes a message.
  val outcome =
    try {
      LinkHandlerActivity.resolver.resolve(SELF_TEST_TRACK)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Throwable) {
      return "FAILED: ${error.javaClass.simpleName}: ${error.message}"
    }
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
  // The per-domain selection can read as SELECTED while the app-wide "Open supported links" toggle
  // is off, so both have to be true before we can claim link taps will reach us.
  if (!state.isLinkHandlingAllowed) return false
  // Check the host that actually matters, not `values.any`. Every real shared link is
  // open.spotify.com, so a user who ticked only the legacy play.spotify.com would otherwise be told
  // setup was complete while every link they receive still went to the browser.
  //
  // In practice only DOMAIN_STATE_SELECTED is reachable for us — autoVerify="false" and we do not
  // own spotify.com, so we can never be VERIFIED — but VERIFIED genuinely would mean taps reach us,
  // so it is accepted rather than asserting a policy fact that could change.
  //
  // Matched case-insensitively: an exact-key lookup would silently return null if the map key ever
  // differed in case from the manifest literal.
  return state.hostToStateMap.any { (host, domainState) ->
    host.equals(PRIMARY_LINK_HOST, ignoreCase = true) &&
      (domainState == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
        domainState == DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
  }
}

/** Opens the "Open by default" screen for [packageName] — ours, or Spotify's, for the handoff. */
private fun openLinkSettings(context: Context, packageName: String) {
  val uri = "package:$packageName".toUri()
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
