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

### Who owns what (settled 2026-07-28, so it stops being re-litigated)

Three sessions share this one working tree. Cross-session messages have proved unreliable —
one session reported never receiving a list, and another re-raised the same three resolved
questions five times — so durable answers belong **here**, not in chat.

| Area | Owner |
| --- | --- |
| App icon, `.github/workflows/`, `.gitignore`, the GitHub remote | coordinator |
| `app/src/**` Kotlin and tests | implementation session |
| The physical phone (under a consent protocol) | device session |

Settled decisions, each verifiable rather than taken on trust:

* **`0fc5b1d` mixes the CJK fix with the coordinator's icon and CI work.** The message
  describes only the former. It stays that way: it is pushed, and force-pushing a public
  repo costs more than a misleading message. Verify with `git log --oneline`.
* **The icon lint regression is fixed** in `fa8af4f` — `IconLauncherShape` ×10 and
  `IconDuplicates` ×5 are gone, total lint back to the baseline 12. Verify by running
  `:app:lintDebug`, and hash `mipmap-xxhdpi/ic_launcher.png` against `ic_launcher_round.png`
  — they are distinct, and the round one is genuinely round.
* **There is no fourth session.** Unexplained rebuilds and icon edits were the coordinator
  working in the same tree.

Settled **implementation** decisions, recorded here for the same reason — these three were
each re-litigated across several rounds of dropped messages:

* **The settle window defaults to ZERO** (`42447ed`), with the mechanism retained, injectable
  and fully tested. Arbitration alone handles every spacing that has been *measured*, and a
  zero-window test pins that at 0–800 ms so the concurrency suite can never pass merely because
  a timer masked a race. A window can only ever add what arbitration cannot reach — latest-wins
  suppresses an older request only once a newer one **exists**, so a resolve that already
  launched at 400 ms is beyond any token — but that shape has never been observed on device,
  and after a launch the user is in YouTube Music, so tapping again is a deliberate second
  request. A non-zero default would add dead time on exactly the fast connections where the app
  would otherwise feel instant. **The boundary, if you ever turn it on, is `[0, window]` —
  inclusive at the top**; at 1,000 ms both 999 and 1000 coalesce and 1001 separates. That was
  measured, and a confident derivation predicted it backwards, so verify rather than reason.
  > **Process note, and it cost a full implement-and-revert cycle.** This shipped at `1_000L`
  > in `56edd56` because a ruling was relayed to the implementation session **100 minutes
  > stale** — the reviewer had already moved on. Cross-session relay is not a reliable carrier
  > for decisions. Put the decision and its reasoning in a commit message or here, and cite the
  > commit; a decision that exists only in chat will be acted on after it has been reversed.
* **Album corroborates, never contradicts** (`68e9dbf`). A disagreeing album may not push a
  candidate below the confidence threshold; it may only fail to lift it. Measured on four real
  tracks, album scored 0.00 for **three of four correct matches**, and rejected no wrong one —
  covers go to the artist veto, live/remix/instrumental to the variant veto, re-records to the
  duration veto. It keeps full strength in `rank`, which is the job it is actually good at.
* **The romanisation rule cannot be extended to the album field.** It needs
  `<non-Latin head> - <Latin suffix>` in one string; YouTube supplies only the romanised form
  for albums, with no original to pair against. Measured at 0.00 — it never fires. Fixing it
  needs real transliteration, and that still would not resolve ラブストーリー → "Love Story",
  which is back-translation. Do not attempt it.

### `git commit --only`, always

The index is shared state in one working tree. A concurrent `git add` between your `git add`
and your `git commit` means your commit takes their files too — this happened twice, in both
directions. Explicit staging does **not** protect you; `git commit --only <paths>` does.

> `--only` cannot stage a **new** file. For those, `git add <explicit paths>` first, then
> `git commit --only <the same explicit paths>`. Never a bare `git add -A`.

### Never state another session's HEAD from a message; read it from git

Cross-session messages arrive **minutes late and out of order**, and replies routinely cross.
On 2026-07-29 all three sessions independently accused each other of stale baselines, and every
one of those accusations was itself stale by the time it arrived. Two rounds were also spent
disputing a test count (11 vs 12 vs 16) that no one had measured — `(Select-String '@Test').Count`
settled it in seconds, and the answer was in none of the messages.

Before asserting anything about the repository:

```powershell
git fetch origin; git rev-parse --short origin/master     # not what a message said
git show <sha>:<path> | Select-String <pattern>           # not the working tree
```

`git show <sha>:<path>` is the strong form and the one to prefer: the working tree can contain a
fix that was never committed, and a pinned artifact must be attributable to a commit rather than
to a directory. It is how the `owner`-in-companion bug was confirmed fixed.

> **The rule that would have saved the most time: a directive is not landed until you have read it
> back off disk.** A settle-window constant was relayed as corrected three times over ninety
> minutes and committed wrong anyway; only `Select-String` on the file ever revealed it. If a
> directive fails to land twice, stop relaying and make the edit yourself.

### A green test proves nothing until you have watched it go red

Four tests in this repo named exactly the right property and proved nothing, each differently:

1. **A scenario that never occurred.** A concurrency test resolved request A *before* submitting B,
   so only a timer could pass it — and its comment then asserted arbitration *could not* fix the
   800 ms case. That claim was false, and it was the entire argument for a settle window.
2. **A suspension that could not fail.** `withContext(NonCancellable) { … }` protects the block but
   prompt-cancels on **exit** into a cancelled parent, so the resolver never returned and the guard
   under test never ran.
3. **A fixture more correct than production.** Each simulated owner built its own
   `OwnerGeneration`, which is the right design — while production shared one. The tests measured
   the intended shape, not the built one.
4. **A property asserted only at its boundary.** `a mismatched album never rejects an otherwise
   good match` used a *perfect* title, where the penalty lands exactly on the threshold. At title
   0.800 the property was false, and had been since it was written.

Two habits catch all four, and both are now used here:

* **Assert the scenario happened**, not just the outcome — e.g.
  `assertEquals("the test is worthless unless A really did return late", listOf("A"), returnedLate)`.
* **Reproduce the defect with the fix removed.** `without the guard the same sequence loses B
  entirely` and `ownerIsNotSharedBetweenInstances` were both *falsified* before being trusted.

Encode a finding where the build enforces it rather than where a reader must notice it. The
short-link User-Agent list is pinned by a test whose failure message *is* the finding; the CJK
fixtures carry `assertNotNull(meta.album)` so an embed-only capture cannot silently re-enter the
forgiving branch.

### Test fixtures must be assembled the way production assembles them

Stronger than "don't author both sides of a fixture", and the reason that weaker rule failed here:
the Japanese fixtures were **genuine captures** — of the embed endpoint only. The embed carries no
album, so `albumKnown = false` and the tests took `MatchScorer`'s renormalised branch while the app,
which merges the canonical page, took the strict one. Green tests, failing phone, nothing fake
anywhere. **Capturing only the endpoint that is convenient to parse is the same class of error as
inventing the data.**

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
| Tapping a link, sharing, clipboard, and non-track bounce into the real Spotify app | **Measured** on hardware. Includes the classical case — Rachmaninov, where the "artist" is both composer and performer and titles are long and inconsistently formatted — matched correctly and the chosen video independently confirmed to be that recording. |
| Low-confidence SEARCH fallback on device | **Measured.** Three Japanese tracks scored 0.51–0.55 on hardware and all opened search. Crucially YT Music's MediaSession did **not** change across all three runs — it still held the previous track — so SEARCH opens the search page and autoplays nothing. That branch is proven safe rather than assumed. The *cause* of those low scores has since been fixed; see the album row below. |
| A genuinely non-Latin **title** on device | **Measured, and it found a real defect.** ブルーアンバー, 高嶺の花子さん and 青と夏 all fell to SEARCH at 0.51–0.55. See the album row below — the title romanisation fix was working; the album term was sinking them. |
| **Album disagreement is not evidence** | **Measured on four real tracks, and the scorer was changed because of it.** With real merged metadata, album scored 0.00 for **three of four correct matches**: ラブストーリー vs "Love Story" and ブルーアンバー vs "Blue Amber" (same album, two scripts), and Attitude vs "Ao To Natsu" (YouTube naming the single, Spotify the parent album). As a flat −0.25 that dropped correct matches to 0.750, and anything short of a perfect title below the 0.70 threshold. Album now *corroborates but never contradicts* — full weight when it agrees at least as strongly as title and artist already do, renormalised away otherwise, and still a full ordering signal in `rank`. All four tracks now score **1.000**. Nothing was lost: album never rejected a wrong recording in that measurement — covers go to the artist veto, live/remix/instrumental to the variant veto, re-records to the duration veto. |
| Spotify declares an https VIEW filter for `open.spotify.com` | **Measured** via `dumpsys package com.spotify.music`. |
| Explicit `setPackage` + https bypasses domain-verification filtering | **Measured** on real vivo OriginOS hardware at API 36: with `pm get-app-links com.spotify.music` reporting *Verification link handling allowed: false*, an explicit `setPackage` + canonical HTTPS intent still reached Spotify. Previously AOSP-source-only; this was the last big unmeasured claim. |
| Share-sheet path end-to-end, album bounce into the real Spotify app | **Measured** on hardware — external `ACTION_SEND` resolves and plays, confirmed twice via MediaSession, including with the link embedded in surrounding prose. Note the caveat above: this is reachability-limited, not reliability-limited. |
| No user text, URL or query reaches logcat | **Measured** on hardware, not just source-reviewed: no `uri=` on `RESULT`, no `query=` on `SEARCH`, `INPUT … link=none (no Spotify link in 50 chars)`, and no message text anywhere in a demonstrably live logcat. Three-way correlation still works without `uri=` — the watch URL is reconstructed from the `videoId` and cross-checked against public oEmbed and MediaSession. |
| `Http.resolveFinalUrl` throwing on redirect-budget exhaustion | **Unexercised, and expected to stay so** — not "untested so far". No live multi-hop chain exists to feed it: `spotify.link` is historical (see below) and canonical URLs do not redirect. The behaviour is accepted by inspection; do not open a task to test it. |
| A **live `spotify.link` short code** expanding to canonical | **Structurally unsupported, not merely unverified.** `Http.resolveFinalUrl` follows only `3xx` + `Location` and never reads the body; Branch never offers that. Measured across all three UA shapes: the app UA and `facebookexternalhit/1.1` both return `200` with no `Location` (the body does `location.replace("market://details?id=com.spotify.music")`), and a mobile-browser UA returns the only `3xx` on offer — an `intent://…` URL we correctly refuse. **Adding another User-Agent cannot help.** The degraded path *is* runtime-verified: an unresolvable short link produced zero `market://` or `com.android.vending` references and landed in Spotify. Caveat: every measurement used an invalid code, because current Spotify emits canonical `open.spotify.com/…?si=…` from both desktop and mobile and no valid code is obtainable. |

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

> **Trap: a fixture built from the embed alone tests a different code path than the app runs.**
> The embed carries no album, so `MatchScorer` takes its album-*absent* branch, which renormalises
> the album weight away and is therefore systematically more forgiving than a device. Four Japanese
> tests were green this way while the phone fell to SEARCH on the same tracks. Anything asserting a
> match outcome must merge the canonical page in, exactly as the app does — see
> `JapaneseTrackMatchingTest.merged()`. Capturing only the endpoint that is convenient to parse is
> the same class of error as authoring both sides of a fixture by hand.

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

> **Do not test this with an invented code, and do not try to fix it with another User-Agent.**
> An invalid code returns Branch's unknown-code landing page — `200`, no redirect — which looks
> exactly like "this UA doesn't work" and will lead you to false conclusions in both directions.
>
> More importantly, **expansion is structurally impossible for a `3xx`-following client.** Measured
> across all three UA shapes: the app UA and `facebookexternalhit/1.1` both return `200` with no
> `Location`, and the body's only action is
> `window.top.location.replace("market://details?id=com.spotify.music")`; a mobile-browser UA
> returns a `307` whose `Location` is the `intent://` URL above. The hop is JavaScript or an Android
> intent, never an HTTP redirect. The only mechanism that could work is parsing the body, which is
> deliberately out of scope. `facebookexternalhit` was in the retry list and was **deleted after
> measurement** for exactly this reason.
>
> **And you probably cannot mint a code anyway.** As of 2026-07-28 neither desktop Chrome nor the
> mobile Spotify app would produce a `spotify.link` URL — both now share canonical
> `open.spotify.com/track/…?si=…`. Treat this whole path as historical: keep the handling, do not
> spend time on it.

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

### Never infer burst spacing from host-side command timing

Testing anything time-sensitive — races, debounce, coalescing — by firing two `adb shell am start`s
from separate `powershell` calls **does not produce the spacing you asked for**. Each call pays
process start, adb transport and device-side `am` overhead. A requested 250 ms gap was measured at
**~4 seconds** on the emulator: an order of magnitude out, in the direction that makes a race
quietly untestable, because a 4-second gap lets the first request finish and the second one is
then a legitimately separate action.

Put both starts in a **single** `adb shell` invocation, and derive the actual delta from the
**app's own log timestamps**, not from the host:

```powershell
adb -s <serial> shell "am start ... ; am start ..."
adb -s <serial> logcat -d -s Spotitube:V | Select-String 'INPUT'   # read the real delta here
```

> **The general rule, which outlived the specific bug:** the host requests a spacing, the device
> decides one. Anything asserted about concurrency must be measured on the device clock, or the
> test is describing an interleaving it never produced. This cuts both ways — a race that
> *reproduces* under inflated spacing is worse than it looks, and one that *fails to* reproduce
> may simply never have been attempted.

The Spotitube concurrency defect was found this way, and the zero-delay ruling rests on the
device-clock trace (`INPUT` A 09.432, `INPUT` B 09.683, B result 10.466, stale A result 10.766 —
B existed **1,083 ms** before A's side effect), not on any requested sleep.

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
