# AGENTS.md — Android development in this folder

Notes for agent sessions working in `C:\src\spotitube`. The Android toolchain on this
machine was installed and verified end-to-end on **2026-07-28** (scaffold → build →
unit tests → instrumented tests on emulator → install → launch → screenshot).

This folder now contains **Spotitube**, a working Android app (see §7), tracked in git.

---

## 0. Session model policy (IMPORTANT — applies to every new session)

- **All new sessions** must run **Claude Opus 5** with **1M context** (`long_context`)
  and **Max reasoning** (`reasoning_effort: max`).
- **Exception / addition:** for **every coding session**, also spawn a paired **reviewer
  session** running **GPT-5.6 Sol** with **1M context** and **Max reasoning**.

```jsonc
// coding session
{ "model": "claude-opus-5", "context_tier": "long_context", "reasoning_effort": "max" }

// paired reviewer session (one per coding session)
{ "model": "gpt-5.6-sol",  "context_tier": "long_context", "reasoning_effort": "max" }
```

Apply these values when calling `create_session` (via its `kickoff` object) or the
`task` tool. Do not fall back to `auto` or default effort.

---

## 1. Environment (already configured — do not reinstall)

| Item | Value |
| --- | --- |
| `JAVA_HOME` | `C:\Program Files\Android\Android Studio\jbr` |
| JDK | OpenJDK **21.0.10** (Android Studio's bundled JBR — there is **no** standalone JDK) |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `C:\Users\rmukhamedov\AppData\Local\Android\Sdk` |
| Android Studio | `AI-261.25134.95.2612.15914620` |
| cmdline-tools | **22.0** (`cmdline-tools\latest\bin`) |
| platform-tools (adb) | 37.0.0 |
| build-tools | 36.0.0 |
| Platforms | `android-36`, `android-36.1` |
| Emulator | 36.6.11 |
| System image | `system-images;android-36.1;google_apis;x86_64` |
| AVD | **`Pixel_9_API_36`** (Pixel 9, 4 GB RAM, 6 GB data, GPU auto, no Play Store) |
| SDK licenses | all accepted |
| Host | Intel i7-13800H, AMD64, hypervisor present (emulator is HW-accelerated) |

These are set at **User** scope in the registry. `~/.gradle` cache is warm (~1.7 GB).

### PATH bootstrap for a fresh shell

Each `powershell` tool call is a **fresh process**. Env vars set at User scope are picked
up by new processes, but if `java` / `adb` / `sdkmanager` are not found, prepend this:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:Path"
```

---

## 2. Creating a project

Use the new **`android` CLI** (`cmdline-tools\latest\bin\android.exe`), which replaces
`sdkmanager`/`avdmanager` scaffolding:

```powershell
android --no-metrics create --name "Spotitube" -o C:\src\spotitube
```

Subcommands: `create`, `describe`, `docs`, `emulator`, `info`, `layout`, `run`,
`screen`, `sdk`, `skills`, `studio`, `update`.
Only one template exists: `empty-activity` (Compose, `agp-9`).

> **Gotcha — flag order.** Global flags must come *before* the subcommand.
> `android --no-metrics create ...` works; `android create --no-metrics ...` fails with
> `Unknown option: '--no-metrics'`.

> **Gotcha — exit code.** `android create` may exit `-1073740791`
> (`STATUS_STACK_BUFFER_OVERRUN`) *after* printing `INFO: Successfully created project`.
> The project is fine. Verify by listing files, not by checking `$LASTEXITCODE`.

### What the template generates

Compose + Navigation 3 + ViewModel, Kotlin DSL, version catalog, Gradle wrapper.

```
build.gradle.kts  settings.gradle.kts  gradle.properties  local.properties  gradlew(.bat)
gradle/libs.versions.toml  gradle/wrapper/
app/build.gradle.kts
app/src/main/java/com/example/<name>/{MainActivity,Navigation,NavigationKeys}.kt
app/src/main/java/com/example/<name>/{data,theme,ui/main}/
app/src/test/...        (JVM unit tests)
app/src/androidTest/... (Compose UI / instrumented tests)
```

Package name is derived as `com.example.<lowercased name>`.

### Versions the template pins (as of 2026-07-28)

| | |
| --- | --- |
| Gradle wrapper | **9.1.0** (latest release is 9.6.1) |
| Android Gradle Plugin | **9.0.1** (latest release is 9.3.1) |
| Kotlin | **2.3.20** (latest release is 2.4.10) |
| compileSdk / targetSdk | 36 |
| minSdk | 24 |
| Java source/target + `jvmToolchain` | **17** |
| Compose BOM | 2026.03.01 |
| Navigation 3 | 1.0.1 |
| Test | JUnit 4.13.2, androidx.test 1.7.0, Espresso 3.7.0 |

`gradle.properties` enables **build cache** and **configuration cache** by default.

> The build runs on JDK 21 but the Kotlin/Java toolchain is 17. The
> `foojay-resolver-convention` plugin in `settings.gradle.kts` auto-provisions
> **Adoptium JDK 17** into `~\.gradle\jdks\` on first build — already downloaded here,
> but it needs network on a clean cache.

---

## 3. Build & test commands

Run from the project root. `--no-daemon --console=plain` keeps output parseable in
tool calls; drop `--no-daemon` for faster iterative local work.

```powershell
# ALWAYS pin the serial first. Gradle device tasks target EVERY attached device and honour
# ANDROID_SERIAL, NOT `adb -s` — this is how the debug APK once got installed on, and then
# uninstalled from, the owner's personal phone without consent. See §8.
$env:ANDROID_SERIAL='emulator-5554'

.\gradlew.bat --no-daemon --console=plain :app:assembleDebug          # build APK
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest      # JVM unit tests
.\gradlew.bat --no-daemon --console=plain :app:connectedDebugAndroidTest  # needs a device
.\gradlew.bat --no-daemon --console=plain :app:lintDebug
```

**Verified timings** (this machine, first run of each):

- `assembleDebug` + `testDebugUnitTest`, cold Gradle cache → **5 min 40 s**
- `connectedDebugAndroidTest`, warm cache → **3 min 18 s**

Always give these `initial_wait: 300`+ in the `powershell` tool, or run them async.

Benign warning, safe to ignore:
`Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so`

---

## 4. Emulator workflow

Launch **detached** so it survives the session and doesn't block:

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Pixel_9_API_36 -no-snapshot-save -no-boot-anim -gpu auto
```

Wait for boot (`adb devices` shows `offline` → `device`; takes ~1–2 min):

```powershell
adb -s emulator-5554 wait-for-device                     # never bare `adb wait-for-device`
adb -s emulator-5554 shell getprop sys.boot_completed    # "1" when ready
```

Install, launch, inspect:

```powershell
adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5554 shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1
adb -s emulator-5554 shell pidof <applicationId>         # non-empty ⇒ running
adb -s emulator-5554 logcat -d -s <TAG>
adb -s emulator-5554 exec-out screencap -p > shot.png    # then view the PNG
```

Shut down **gracefully** — never force-kill:

```powershell
adb -s emulator-5554 emu kill
```

### Creating another AVD (only if needed)

```powershell
avdmanager.bat create avd --name <Name> --package "system-images;android-36.1;google_apis;x86_64" --device pixel_9 --force
```

> **Gotcha.** `avdmanager create avd` prints a harmless
> `Error: Could not load devices from ...\system-images\...\devices.xml`, **and** it writes
> literal placeholders into `~\.android\avd\<Name>.avd\config.ini`:
> `avd.id=<build>`, `avd.name=<build>`, `disk.dataPartition.path=<temp>`.
> Fix them by hand (set `AvdId`/`avd.id`/`avd.name` to the AVD name and delete the
> `disk.dataPartition.path` line) or the emulator may misbehave.
> Tuning knobs worth adding: `hw.ramSize=4096`, `hw.gpu.enabled=yes`, `hw.gpu.mode=auto`,
> `disk.dataPartition.size=6G`, `hw.keyboard=yes`.

---

## 5. SDK package management

`sdkmanager` still works but **prints a deprecation warning** — prefer `android sdk`.

```powershell
sdkmanager.bat --list_installed
sdkmanager.bat "platforms;android-36"        # install
sdkmanager.bat --licenses                    # pipe "y" repeatedly to auto-accept
```

Package download URLs come from
`https://dl.google.com/android/repository/repository2-3.xml`.
Google Maven, Maven Central, and services.gradle.org are all reachable from this machine.

---

## 6. Conventions for sessions in this folder

- Spawn every session per the **§0 session model policy**: Claude Opus 5 / 1M / Max, plus
  a paired GPT-5.6 Sol / 1M / Max reviewer session for each coding session.
- Do all development and testing in **separate sessions**; keep any coordinating session
  for coordination only.
- The folder **is** a git repo now (initialised 2026-07-28). The template `.gitignore`
  already covers `local.properties`, `.gradle/`, `build/` and `*.iml`.
- `local.properties` hard-codes `sdk.dir` and must never be committed.
- Clean up scratch projects under `$env:TEMP` when done; keep `~\.gradle` (warm cache).
- Prefer `adb ... emu kill` over `Stop-Process` for the emulator.

---

## 7. Spotitube — what this app is and how to run it

**Problem.** The owner has YouTube Premium but not Spotify Premium. Friends share Spotify
links; opening them plays ads. Spotitube intercepts a Spotify **track** link, finds the same
recording on YouTube Music, and launches it playing. Albums / playlists / artists / shows /
episodes are bounced straight back to Spotify.

No API keys, no OAuth, no accounts. Three public endpoints only:

### Evidence status — what is measured vs assumed

Keep this honest; several claims here were overclaimed at some point and had to be walked back.

| Claim | Status |
| --- | --- |
| YT Music **starts playing** from `music.youtube.com/watch?v=` + `setPackage` | **Measured** on a real vivo X300 Pro (YT Music 9.29.54): `dumpsys media_session` showed `state=PLAYING(3)`, correct metadata, and position advancing 898 ms → 26966 ms. A loaded-but-idle screen cannot advance position. |
| `spotify:{type}:{id}` routes correctly for all 6 forwarded types | **Measured**, screenshot-verified against real Spotify 9.1.68.1888. |
| Spotify declares an https VIEW filter for `open.spotify.com` | **Measured** via `dumpsys package com.spotify.music`. |
| Explicit `setPackage` + https bypasses domain-verification filtering | **Measured** on real vivo OriginOS hardware at API 36: with `pm get-app-links com.spotify.music` reporting *Verification link handling allowed: false*, an explicit `setPackage` + canonical HTTPS intent still reached Spotify. Previously AOSP-source-only; this was the last big unmeasured claim. |
| Share-sheet path end-to-end, album bounce into the real Spotify app | **Measured** on hardware — external `ACTION_SEND` resolves and plays, confirmed twice via MediaSession, including with the link embedded in surrounding prose. Note the caveat above: this is reachability-limited, not reliability-limited. |
| No user text, URL or query reaches logcat | **Measured** on hardware, not just source-reviewed: no `uri=` on `RESULT`, no `query=` on `SEARCH`, `INPUT … link=none (no Spotify link in 50 chars)`, and no message text anywhere in a demonstrably live logcat. Three-way correlation still works without `uri=` — the watch URL is reconstructed from the `videoId` and cross-checked against public oEmbed and MediaSession. |
| `Http.resolveFinalUrl` throwing on redirect-budget exhaustion | **Unexercised, and expected to stay so** — not "untested so far". No live multi-hop chain exists to feed it: `spotify.link` is historical (see below) and canonical URLs do not redirect. The behaviour is accepted by inspection; do not open a task to test it. |
| A **live `spotify.link` short code** expanding to canonical | **Never exercised, anywhere.** Every test uses an invented code, which only reaches Branch's unknown-code landing page. The user could not produce a short link at all — desktop Chrome and the mobile Spotify app both now yield canonical `open.spotify.com/track/…?si=…` — so the path looks largely historical and no real code was mintable to test with. The **degraded** path *is* runtime-verified: an unresolvable short link produced zero `market://` or `com.android.vending` references and landed in Spotify, so the Play Store trap is closed. |

`connectedAndroidTest` is **parser / network / target-selection evidence only**. It proves the
intent was constructed, never that anything played. Do not cite it for playback.

Two evidence traps that produced false conclusions here:

* **`match` values prove nothing.** Spotify returns `0x208000` and YT Music `0x508000` for
  *deliberate garbage* as readily as for valid input — Spotify declares a bare `spotify:` scheme
  with no host/path, YT Music declares `music.youtube.com` with path `GLOB: .*`. So
  `resolve-activity` only proves an app claims the scheme/host. Hence
  `ActivityNotFoundException` fires **only when the app is absent** and is *not* a malformed-input
  guard — validate ids yourself (`^[A-Za-z0-9_-]{11}$`, and exactly 22 base62).
* **`am start -W` "Activity not started … brought to the front" is not a failure.** Spotify's main
  activity is single-top, so 5 of 6 type probes printed that while working perfectly. Judging on
  `am -W` alone gave a false negative on 5 of 6.

* `GET https://open.spotify.com/embed/track/{id}` → `__NEXT_DATA__` JSON (structured artists,
  millisecond duration, explicit flag) — **no album**.
* `GET https://open.spotify.com/track/{id}` → Open Graph `<meta>` tags — the only source of the
  **album name**. Fetched concurrently with the embed and merged.
* `POST https://music.youtube.com/youtubei/v1/search` (InnerTube, `WEB_REMIX` client,
  `params=EgWKAQIIAWoKEAoQAxAEEAkQBQ==` to restrict to the Songs shelf).

### Run it

```powershell
cd C:\src\spotitube
.\gradlew.bat --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
```

Drive it without tapping anything, and read the fixed `Spotitube` logcat tag:

```powershell
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell "am start -a android.intent.action.VIEW `
  -d 'https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8' `
  -n com.example.spotitube/.LinkHandlerActivity"
adb -s emulator-5554 logcat -d -s Spotitube:V
```

Every run ends with one structured line, e.g.

```
RESULT outcome=PLAY started=true target=com.google.android.apps.youtube.music
       via=preferred-app videoId=lYBUbBu4W08 score=1.060
```

No URI is logged: these lines land in logcat on the owner's own phone, and the URI is what
they are listening to. `videoId` plus `outcome` is enough to diagnose a mis-resolution.
`started` means the system accepted the intent — not that anything rendered or played.

`outcome` is `PLAY` | `SEARCH` | `BOUNCE` | `LOOPGUARD` | `UNSUPPORTED`; `via` is
`preferred-app` | `scheme-fallback` | `browser-fallback` | `chooser-excluding-self` |
`no-handler`.

The share path is the one that is unaffected by Android 12+ link verification, because
`ACTION_SEND` is not a web intent and no other app can claim it away from us. (The code path
is exercised on the emulator; end-to-end share-sheet behaviour on a physical device is listed
as unmeasured in the evidence table above until the device session reports.)

```powershell
adb -s emulator-5554 shell "am start -a android.intent.action.SEND -t text/plain `
  --es android.intent.extra.TEXT 'listen https://open.spotify.com/track/<id>?si=x' `
  -n com.example.spotitube/.LinkHandlerActivity"
```

### Layout

`app/src/main/java/com/example/spotitube/`

| Path | Role |
| --- | --- |
| `core/` | **Pure Kotlin, zero Android** — link parsing, meta parsing, InnerTube parsing, normalisation, match scoring, orchestration. All unit tests live here. |
| `net/` | `HttpURLConnection` implementations of the two `core` interfaces. No OkHttp. |
| `LaunchIntents.kt` | Explicit-package launching + loop-proof browser fallback. |
| `LinkHandlerActivity.kt` | Translucent one-shot worker; handles `VIEW` and `SEND`. |
| `MainActivity.kt` | Explainer, link-settings deep link, live self-test button. |

Fixtures (real captured responses) are in `app/src/test/resources/fixtures/`; unit tests
never touch the network. `app/src/androidTest/.../LiveNetworkTest.kt` is the only thing
that does.

---

## 8. Things learned building Spotitube (2026-07-28)

### Emulator image contents — the `google_apis` AVD is not bare

`system-images;android-36.1;google_apis;x86_64` has **no Play Store**, but it *does* ship:

* `com.google.android.apps.youtube.music` ← YouTube Music **is** preinstalled
* `com.google.android.youtube`
* `com.android.chrome`

So the "target app is not installed" branch cannot be tested by assuming absence. Force it
with `pm disable-user --user 0 <pkg>` and re-enable afterwards:

```powershell
adb -s emulator-5554 shell "pm disable-user --user 0 com.google.android.apps.youtube.music"
# ... drive the app ...
adb -s emulator-5554 shell "pm enable --user 0 com.google.android.apps.youtube.music"
```

> A `disabled-user` package still succeeds `getPackageInfo()`. Use
> `queryIntentActivities(intent.setPackage(pkg))` — or check `ApplicationInfo.enabled` — or
> you will "launch" into an `ActivityNotFoundException`.

### `connectedAndroidTest` runs on *every* attached device
A physical phone paired over adb will be included, and it **uninstalls both APKs when it
finishes**, so a later `adb shell am start` fails with
`Activity class {...} does not exist`. Reinstall after connected tests. Pinning works:

```powershell
$env:ANDROID_SERIAL='emulator-5554'   # verified: restricts the run to the emulator only
```

### `launchMode="singleTask"` silently swallows repeat intents

For a one-shot handler activity, `singleTask` makes a second link arriving while the first
is still working return `Warning: Activity not started, its current task has been brought
to the front` — the intent is dropped, and `onNewIntent` is the only way to see it. Use the
default `standard` launch mode with `noHistory` + `excludeFromRecents` instead.

### …and `standard` still needs `onNewIntent`

Even with `standard`, an *identical* intent (same component, same data) aimed at the
top-most instance is delivered rather than starting a new one:

```
Warning: Activity not started, intent has been delivered to currently running top-most instance.
```

Watch `ActivityTaskManager` in logcat for the result code — `result code=0` means started,
**`result code=3` means delivered/coalesced**:

```powershell
adb -s emulator-5554 logcat -d | Select-String 'ActivityTaskManager.*START.*<yourpkg>'
```

On a *cold* start the coalescing is even stronger: intents that arrive before `onCreate`
runs are merged into the pending activity record, so four rapid `am start`s produce exactly
one `onCreate`. To exercise per-intent behaviour, warm the process first and space the fires
~2 s apart.

### Checking Android 12+ link handling from the shell

```powershell
adb -s emulator-5554 shell "pm get-app-links --user 0 com.example.spotitube"
```

`android:autoVerify="false"` does **not** remove the domains from the user-selectable list —
they still appear under *Selection state* and can be enabled from
`Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`. That is the right value for a domain you do
not own, and it clears lint's `AppLinkWarning`.

> **But Spotify owns the domain.** `https://open.spotify.com/.well-known/assetlinks.json`
> (and the same file on `spotify.link` and `spotify.app.link`) delegates
> `delegate_permission/common.handle_all_urls` to `com.spotify.music`, plus the Lite, canary,
> debug and TV variants. On Android 12+ that makes those domains **verified** to Spotify, and
> a domain can be held by only one app. So on any phone with Spotify installed, sending the
> user to *our* "Open by default" screen is not enough — they must turn **Spotify's** "Open
> supported links" off first, then enable ours. Spotitube detects this
> (`LinkHandling.BLOCKED_BY_SPOTIFY`) and shows a two-step handoff with a deep link into
> Spotify's own settings via `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` +
> `package:com.spotify.music`. The `ACTION_SEND` share target is unaffected by any of this.
>
> The emulator has no Spotify, so it always shows the one-step `AVAILABLE` branch; the
> decision itself lives in `core/LinkHandling.kt` and is unit-tested.

### Spotify metadata: two endpoints, neither sufficient alone

`GET https://open.spotify.com/embed/track/{id}` (~10 KB) serves a
`<script id="__NEXT_DATA__" type="application/json">` island with
`props.pageProps.state.data.entity`:

```
name / title   "Never Gonna Give You Up"
artists[].name ["Post Malone", "Swae Lee"]   <- a real array, no separator guessing
duration       213573                        <- milliseconds
isExplicit     false
releaseDate    { isoString: "1987-11-12T00:00:00Z" }
```

**It has no album name.** The album appears only in `og:description` on the canonical
`/track/{id}` page (`Artist · Album · Song · Year`), and the album is what disambiguates two
YouTube uploads of the same recording on different releases. So Spotitube fetches **both
concurrently and merges** — embed for the structured fields, Open Graph for the album.

The canonical page is also UA- and CDN-variable: a **desktop Chrome** UA has been observed
returning a ~6 KB JavaScript shell with no `og:`/`music:` tags at all, while
`facebookexternalhit/1.1` (~28 KB), a mobile Chrome UA (~139 KB) and a plain
`Spotitube/1.0 (+Android)` all get the server-rendered page. Repeated probes from a desktop
browser alternated between shell and rich, so treat it as unstable rather than
UA-deterministic and keep a fallback chain. Send `Accept-Language: en-US` too — without it
one capture came back with U+060C ARABIC COMMA separating the artists.

`https://open.spotify.com/oembed?url=…` always answers but returns the **title only**, which
is not enough to tell an original from a cover — it can open search, never auto-play.

### YouTube Music explicit badges

```
musicResponsiveListItemRenderer.badges[]
  .musicInlineBadgeRenderer.icon.iconType == "MUSIC_EXPLICIT_BADGE"
```

Absent `badges` means "not stated", not "clean".

### `spotify.link` short links are Branch links, and UA decides what you get

`https://spotify.link/{code}` is not a plain redirect to a canonical URL. With an **Android Chrome**
UA it answers `307` with an Android **intent URI**, captured verbatim:

```
Location: intent://open?link_click_id=…#Intent;scheme=spotify;package=com.spotify.music;
          S.browser_fallback_url=market%3A%2F%2Fdetails%3Fid%3Dcom.spotify.music;B.branch_intent=true;end
```

`java.net.URL` cannot represent that, so blind redirect-following fails with an opaque
`MalformedURLException`. Follow redirects **manually**, reject any non-http/https scheme, and pin a
non-browser UA (`Spotitube/1.0 (+Android)`).

> **Do not test this with an invented code.** An invalid code returns Branch's unknown-code landing
> page — `200`, no redirect — which looks exactly like "this UA doesn't work" and will lead you to
> false conclusions in both directions. A real code has to be minted from Spotify's share sheet.
>
> **And you probably cannot mint one.** As of 2026-07-28 neither desktop Chrome nor the mobile
> Spotify app would produce a `spotify.link` URL — both now share canonical
> `open.spotify.com/track/…?si=…`. Treat this whole path as historical: keep the handling, do not
> spend time hunting for a code to test it with.

That `S.browser_fallback_url` matters for the failure path: if expansion fails and you hand the
original Branch link to a browser while Spotify is **not** installed, the user lands on the Play
Store being asked to install Spotify — the exact opposite of this app's purpose.

### Diagnostic logging: `BuildConfig.DEBUG` is not available here

`app/build.gradle.kts` sets `buildConfig = false` in `buildFeatures` (alongside `aidl` and
`shaders`), so **`BuildConfig.DEBUG` does not resolve** and reaching for it gives a confusing
unresolved-reference rather than an obvious cause. Either flip that flag or use
`Log.isLoggable(TAG, Log.DEBUG)`, which needs no build change.

Related principle, learned the hard way while trimming what the `Spotitube` tag emits:

> **Removing the readable copy of data while leaving the resolvable copy is not a privacy fix.**
> Dropping `spotify="Artist — Title"` from the MATCH line while keeping `videoId=` would have
> destroyed the most useful diagnostic on the line and left listening history fully recoverable —
> `videoId` is one lookup from the title. Log privacy on this line is a **build-type** decision
> about the whole diagnostic block (`videoId` + `picked` + `spotify` + `score` + query together),
> not a field-by-field one.

Genuinely worth removing, by contrast: `e.message` from `ActivityNotFoundException` and
`SecurityException`, because the message embeds the entire `Intent` **including the data URI** — a
full-URL leak from inside a failure path, where nobody thinks to look.

### vivo OriginOS intermittently throttles app logcat

Mid-session on the vivo X300 Pro the app **stopped being able to write to logcat**. Share tests
produced no output at all and looked completely dead; they had in fact been working the whole time.

**It is intermittent, not permanent** — logging came back later in the same session, and the privacy
verification was completed against a demonstrably live logcat. So do not conclude the device has
stopped logging for good, and do not conclude a feature is broken.

> On this device, **absence of logcat output is not evidence of failure.** Confirm against
> `dumpsys media_session` or what is actually on screen before concluding anything is broken.
> MediaSession is the authoritative signal; logcat is a convenience this OEM can withdraw and
> restore without warning.

### A sleeping phone manufactures a convincing false failure

With the screen off and locked, our handler activity still runs and still logs — but the downstream
launch is silently blocked, **and** `dumpsys media_session` keeps serving a **stale** entry whose
`updated` timestamp never moves. The result reads exactly like a launch regression in the new build:
the handler ran, nothing played, and the media session "shows" the previous track.

Before concluding any device test failed:

```powershell
adb -s <serial> shell "dumpsys power | grep -E 'mWakefulness|screenState|mDreamingLockscreen'"
```

Unlocking made the identical command work first time. A stale `updated` timestamp is the tell —
compare it across two reads rather than trusting a single snapshot.

### Build attribution: compare sources, not timestamps

Checking an APK's build time against a commit's timestamp is the **wrong** test and will reject
perfectly valid artifacts — a rebuild with no source change is still the same code, and a commit
made after a build does not invalidate it.

The correct question is whether anything under `app/src` actually changed between the two:

```powershell
git --no-pager diff --name-only <commit> -- app/src
```

Empty output means the APK matches that commit regardless of clock order.

> **Three ways this device fakes a failure**, all hit in one evening: vivo's silent logcat
> suppression, a sleeping screen with a stale MediaSession, and timestamp-based build attribution.
> None of them look like the environment; all of them look like your code.

### Signal and Telegram do not offer Share on a link

Measured on the user's own phone: Signal silently copies a link on long-press, and Telegram offers
only *Open / Open In-App / Copy Link*. **Neither exposes Android's share sheet on a link.**

This inverts the obvious onboarding. `ACTION_SEND` needs no setup and cannot be taken away by domain
verification, which makes it sound like the safe primary path — but in the apps the user's friends
actually message them in, the affordance barely exists. Real usage order is:

1. **Tapping** the link — needs the two-step domain handoff (see the assetlinks note above).
2. **Copy → open Spotitube → tap** — the only zero-setup route that works in Signal today.
3. **Share** — genuinely useful, but not primary.

Onboarding leads with link handling accordingly, and the `INPUT` log line carries
`source=view|share|clipboard|manual` because `ACTION_SEND` alone cannot tell an external share from
our own in-app buttons.

> **The failure mode is reachability, not reliability.** SEND handling is measured working on
> hardware, twice via MediaSession, including with the link embedded in surrounding prose. The user
> simply often cannot *reach* it. So the user-facing wording is **"sharing needs no setup, where the
> sending app offers a share option"** — never "sharing always works", which would be technically
> defensible and practically misleading. Saying *where the sending app offers it* also tells the
> user why the option is missing, instead of leaving them to conclude the app is broken.

### Gradle notes

* Editing `gradle/libs.versions.toml` invalidates the **configuration cache**, turning a
  30-second incremental build into a ~10-minute one. Batch catalog edits.
* `:app:lintDebug`'s `NewerVersionAvailable` check hits the network and dominates the wall
  time; a full `assembleDebug + testDebugUnitTest + lintDebug` took **7–10 min** here.
* Running Gradle while the emulator is up can trip
  `Unable to connect to the child process 'Gradle Test Executor N'` — a load flake, not a
  test failure. Re-run the test task alone.
* The template applies the `kotlin-serialization` **plugin** but adds no serialization
  dependency; add `kotlinx-serialization-json` yourself. It is also the easy way to parse
  JSON in code shared with JVM unit tests, where `org.json` is a throwing stub.
* The template's Navigation-3 dependencies can be deleted outright if you do not use them.

### Measured timings on this machine

| Task | Time |
| --- | --- |
| `assembleDebug`, warm cache, first run of new code | 3 m 37 s |
| `testDebugUnitTest`, incremental | 18–33 s |
| `assembleDebug + testDebugUnitTest + lintDebug`, config cache invalidated | 7–10 min |
| `connectedDebugAndroidTest`, 2 devices, warm | 40 s |
