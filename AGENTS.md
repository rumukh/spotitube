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
adb wait-for-device
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

No API keys, no OAuth, no accounts. Two public endpoints only:

* `GET https://open.spotify.com/track/{id}` → Open Graph `<meta>` tags.
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
       via=preferred-app uri=https://music.youtube.com/watch?v=lYBUbBu4W08
       videoId=lYBUbBu4W08 score=1.070
```

`outcome` is `PLAY` | `SEARCH` | `BOUNCE` | `LOOPGUARD` | `UNSUPPORTED`; `via` is
`preferred-app` | `browser-fallback` | `chooser-excluding-self` | `no-handler`.

The share path works too, and is the one that always works on Android 12+:

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

### Spotify's HTML depends on the User-Agent

A **desktop Chrome** UA gets a ~6 KB JavaScript shell with *no* `og:`/`music:` tags at all.
A link-unfurler UA (`facebookexternalhit/1.1`) gets ~28 KB fully server-rendered; a mobile
Chrome UA gets ~139 KB, also server-rendered. Send `Accept-Language: en-US` too — without it
one capture came back with U+060C ARABIC COMMA separating the artists.

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
