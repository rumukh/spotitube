package com.example.spotitube

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.spotitube.core.ResolveOutcome
import com.example.spotitube.core.SpotifyEntityType
import com.example.spotitube.core.SpotifyLinkParser
import com.example.spotitube.core.SpotitubeResolver
import com.example.spotitube.net.HttpSpotifyMetadataSource
import com.example.spotitube.net.InnerTubeMusicSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hits the **real** Spotify page and the **real** InnerTube endpoint from the app's own code path.
 *
 * The JVM tests prove the parsers handle the responses we captured; this proves the responses still
 * look like that. Requires network on the device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class LiveNetworkTest {

  private val spotify = HttpSpotifyMetadataSource()
  private val youTube = InnerTubeMusicSearch()
  private val rickAstley = "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8"

  @Test
  fun spotifyTrackMetadataIsStillServerRendered() = runBlocking {
    val link = SpotifyLinkParser.parse(rickAstley)!!
    val meta = spotify.fetchTrack(link)
    assertNotNull("Spotify returned nothing parsable", meta)
    assertEquals("Never Gonna Give You Up", meta!!.title)
    assertEquals(listOf("Rick Astley"), meta.artists)
    assertEquals(214, meta.durationSeconds)
  }

  @Test
  fun innerTubeSearchStillReturnsTheSongsShelf() = runBlocking {
    val songs = youTube.searchSongs("Rick Astley Never Gonna Give You Up")
    assertTrue("expected a populated Songs shelf, got ${songs.size}", songs.size >= 5)
    songs.take(5).forEach {
      assertTrue("blank videoId in $it", it.videoId.isNotBlank())
      assertTrue("blank title in $it", it.title.isNotBlank())
    }
    assertTrue(
      "official upload missing from live results: ${songs.map { it.videoId }}",
      songs.any { it.videoId == "lYBUbBu4W08" },
    )
  }

  @Test
  fun trackResolvesEndToEndOverLiveNetwork() = runBlocking {
    val outcome = SpotitubeResolver(spotify, youTube).resolve(rickAstley)
    val play = outcome as? ResolveOutcome.PlayOnYouTubeMusic ?: error("expected a play outcome, got $outcome")
    assertEquals("lYBUbBu4W08", play.videoId)
    assertEquals("https://music.youtube.com/watch?v=lYBUbBu4W08", play.url)
  }

  @Test
  fun shortLinkFormatIsRejectedCleanlyWhenItCannotBeExpanded() = runBlocking {
    // A made-up short code: the point is that a dead short link degrades to a Spotify bounce
    // rather than throwing or searching YouTube for nothing.
    val outcome = SpotitubeResolver(spotify, youTube).resolve("https://spotify.link/zzzzzzzzzzz")
    assertTrue("got $outcome", outcome is ResolveOutcome.BounceToSpotify)
  }

  @Test
  fun albumLinkNeverReachesYouTube() = runBlocking {
    val album = "https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6"
    val outcome = SpotitubeResolver(spotify, youTube).resolve(album)
    val bounce = outcome as? ResolveOutcome.BounceToSpotify ?: error("expected a bounce, got $outcome")
    assertEquals(SpotifyEntityType.ALBUM, bounce.type)
    assertEquals(album, bounce.url)
  }

  @Test
  fun fallbackTargetIsNeverOurselves() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val urls =
      listOf(
        "https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6",
        "https://music.youtube.com/watch?v=lYBUbBu4W08",
        "https://music.youtube.com/search?q=test",
      )
    // Nothing preferred, Spotify preferred (absent from this image) and YouTube Music preferred all
    // have to end up somewhere that is not LinkHandlerActivity, or a Spotify link would loop.
    for (url in urls) {
      for (preferred in listOf(LaunchIntents.SPOTIFY_PACKAGE, LaunchIntents.YT_MUSIC_PACKAGE, null)) {
        for (target in LaunchIntents.candidateTargets(context, url, preferred)) {
          assertTrue(
            "candidate for url=$url preferred=$preferred resolved back to Spotitube",
            target.packageName != context.packageName,
          )
        }
        val chosen = LaunchIntents.chooseTarget(context, url, preferred)
        if (chosen is LaunchIntents.Target.Explicit) {
          assertTrue(
            "chosen target for url=$url preferred=$preferred is Spotitube itself",
            chosen.packageName != context.packageName,
          )
        }
      }
    }
  }

  @Test
  fun spotifyBounceAlwaysGetsAnExplicitTarget() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target =
      LaunchIntents.chooseTarget(
        context,
        "https://open.spotify.com/album/6eUW0wxWtzkFdaEFsTJto6",
        LaunchIntents.SPOTIFY_PACKAGE,
      )
    val explicit = target as? LaunchIntents.Target.Explicit ?: error("no explicit target, got $target")
    assertTrue("bounce target must never be Spotitube", explicit.packageName != context.packageName)

    if (LaunchIntents.isInstalled(context, LaunchIntents.SPOTIFY_PACKAGE)) {
      assertEquals("preferred-app", explicit.via)
      assertEquals(LaunchIntents.SPOTIFY_PACKAGE, explicit.packageName)
    } else {
      // No Spotify (e.g. the google_apis emulator image): must degrade to a browser, never to a
      // chooser and never back to us.
      assertEquals("browser-fallback", explicit.via)
    }
  }
}
