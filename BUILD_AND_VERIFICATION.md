# Qrmzi TV — Build & Verification Report

**Artifact:** `build/qrmzi-tv.apk` (signed release)
**Package:** `tv.qrmzi.client` · versionCode 2 · versionName 1.1
**Target:** Mortal TX1-S · Android 10 (API 29) · `arm64-v8a`/`armeabi-v7a` (DEX only, no native code)
**Size / hash:** 3,731,798 bytes ·
`sha256 08b405657276cd03cdac23ddc68437737c1ba79be14b5d28c405a729b6035171`
**Date:** 6 November 2026

This file records exactly what was built, how it was checked, and — with equal
importance — what could **not** be checked on this host.

---

## 1. Build host and why the build looks unusual

| Constraint | Consequence |
|-----------|-------------|
| Host is aarch64 (Termux/proot Ubuntu); the Google build-tools binaries (`aapt`, `aapt2`, `zipalign`) are x86_64 | AAPT cannot run, so there is **no `res/` and no `resources.arsc`**; the manifest is generated as binary AXML by `tools/gen_manifest.py` and the APK zip is written by `tools/package_apk.py` |
| No Gradle (no network for dependencies, no Android plugin) | Sources are compiled with `kotlin-compiler-embeddable` directly and dexed with `d8.jar` |
| Kotlin compiler is memory-fragile here | `-Xmx192m`, serial GC, no daemon; compiles in ~2–3 minutes |
| SDK archives were unpacked manually | `android.jar` (API 34 platform), `d8.jar` + `apksigner.jar` (build-tools) live under `/tmp/opencode` |

Everything else (manifest correctness, dex validity, signature) is verified by
independent Python readers in `tools/`, not by trusting the generators.

---

## 2. Pipeline

```bash
bash tools/build.sh
```

```
== 1/5 compile Kotlin ==      39 source files -> build/classes.jar
== 2/5 dex (D8, min-api 29) == build/classes.jar + 4 runtime jars -> build/dex/classes.dex
== 3/5 binary manifest ==     tools/gen_manifest.py -> build/AndroidManifest.xml
== 4/5 package ==             tools/package_apk.py -> build/qrmzi-unsigned.apk
== 5/5 sign ==                apksigner -> build/qrmzi-tv.apk (+ apksigner verify)
```

Last run: exit 0 · 39 source files · 12 Kotlin compile warnings · 90 Kotlin-metadata
warnings from D8 · `classes.dex` 3,723,780 bytes · APK 3,731,798 bytes.

Details that matter on this device:

* **D8** runs `--release --min-api 29`. R8 8.2 cannot parse Kotlin 2.0 metadata
  and emits one warning per Kotlin class; the warnings are harmless (the class
  bytes are kept) and are redirected to `build/d8.log` so real errors remain
  visible. The build fails only on `Error`/`Compilation failed`/`Fatal`.
* **Classes.dex** is written **stored** (not deflated) and **4-byte aligned**
  using a padding extra field (`0xD935`), which is what ART wants for mmap. The
  packaging script re-reads the central directory and asserts both properties.
* **Manifest** is real binary AXML built from the same framework attribute ids a
  device uses (`tools/arsc_ids.py` resolves them from `framework-res.apk`), so it
  is byte-compatible with `aapt2` output for the attributes used.
* **Signing** is v1 + v2 + v3 with a locally generated RSA-2048 key
  (`keystore/qrmzi-release.jks`, alias `qrmzi`). The keystore is created on first
  build and must be kept for updates.

Runtime dependencies inside the APK: `kotlin-stdlib`, `kotlinx-coroutines-core`,
`kotlinx-coroutines-android`, `jsoup`. No AndroidX, no Room, no OkHttp, no Coil.

---

## 3. Static verification (`tools/verify_apk.py`)

Run:

```bash
python3 tools/verify_apk.py build/qrmzi-tv.apk build/classes.jar
```

It parses the produced APK with an independent AXML reader and its own zip/dex
readers — none of the generator code is reused.

| Area | Check | Result |
|------|-------|--------|
| zip | all CRCs valid | pass |
| zip | `AndroidManifest.xml` present | pass |
| zip | `classes.dex` present, stored (method 0), offset `% 4 == 0` | pass |
| zip | v1 signature entries (`MANIFEST.MF`, `.SF`, `.RSA`) | pass |
| zip | no `resources.arsc` (resource-less build, as designed) | pass |
| manifest | root `<manifest>`, package `tv.qrmzi.client` | pass |
| manifest | versionCode 2, versionName 1.1 | pass |
| manifest | minSdk 29, targetSdk 29 | pass |
| manifest | `INTERNET`, `ACCESS_NETWORK_STATE` permissions | pass |
| manifest | `android.software.leanback` required, `android.hardware.touchscreen` **not** required | pass |
| manifest | `application` → `tv.qrmzi.client.App`, label `قرمزي TV` | pass |
| manifest | theme = framework `NoActionBar` (`0x0103022E`), `usesCleartextTraffic=false`, `allowBackup=false` | pass |
| manifest | `MainActivity` exported with LEANBACK_LAUNCHER + LAUNCHER | pass |
| manifest | `PlayerActivity` **not** exported, `screenOrientation=landscape` | pass |
| dex | magic `dex\n039`, adler32 checksum, file_size, header_size 0x70, little endian | pass |
| dex | class list complete: **91/91** compiled classes present (2417 classes in the dex) | pass |
| dex | key classes present (`MainActivity`, `App`, `PlayerActivity`, all screens, `Repo`, `Store`, `Parsers`, `ThumbView`, coroutines, jsoup) | pass |

Result: **all static checks passed**.

## 4. Signature

```bash
java -jar apksigner.jar verify --min-sdk-version 29 --verbose build/qrmzi-tv.apk
# Verifies
# Verified using v3 scheme: true
```

apksigner reports only the schemes it needs for the given minimum SDK. Asking it
to verify as API 21 shows that the APK really carries all three:

```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

Android 10 (API 29) accepts v2 and v3, so installation is covered by two
independent schemes; v1 additionally covers older boxes and some file managers.

## 5. Parser regression tests

`tools/parsertest/run.sh` compiles the real data layer plus `ParserTest.kt` and
runs it on the JVM against the HTML fixtures captured from the live site
(`/tmp/opencode/qrmzi-evidence`).

```
checks=114 failures=0
```

Coverage includes: home feed shape (50 cards, per-kind counts, episode numbers,
`data-src` extraction), pagination end detection (page 20 empty at HTTP 200),
catalogue splitting (40/page, 921 episodes / 191 series / 49 movies), search
(`حب` → 23 results), series page (41 episodes, descending numbers, ribbons
`الحلقة الأخيرة` / `مؤجلة` / `نهاية الموسم`), movie pages (no `#episodes-tab`,
synopsis from `.singleSeries .story`), episode page (title, series link,
description fallbacks, 8 related cards, 1280 px cover), episode adjacency
(next/prev from DOM order, both edges), player-chain resolution
(`anaplayer → cdnplus`, `shoof → cdnplus`, JW Player marker, no `.m3u8` touched),
and robustness (empty/garbage HTML, unquoted `data-src`, number from title).
It also covers the local-data rules now implemented: `Playback.progress`,
`isResumable` (30 s floor, 95 % ceiling), `shouldStore` (real duration, no
overflow), `clock` formatting and `normalizeQuery` for search history.

The last 36 checks were added for this phase and cover the pure parts of the
TX1-S work:

* **filter matching** — default block hit, subdomain match, lookalike
  (`notpgo99.com`) not matched, essential host wins over a user block, `allow`
  wins over the built-in list, config-file `allow`/`block`/bare-domain/comment/
  invalid-domain handling, and normalisation (scheme, `www.`, `*.`, case);
* **diagnostics** — ring bounded at 160 lines and keeps the newest, verbose off
  drops routine lines but never `important` ones, counters add, `safeUrl` removes
  query/fragment, export writes the file and contains counters, clear empties
  both;
* **retry/offline policy** — 408/429/5xx/timeouts are transient, 4xx is final,
  attempts are capped and backoff grows, offline error text is recognised.

## 5.1 First real-device run — regression found and fixed

The first APK was side-loaded on a Redmi Note 8 (Android 11). The app started,
then every history/favourites screen showed:

```
حدث خطأ غير متوقع.
no such column: ribbon (code 1 SQLITE_ERROR): , while compiling:
SELECT url, title, image, kind, number, ribbon, positionMs, … FROM history …
```

**Cause.** `Store` created `favorites`/`history` with a `ribbon` column, but the
database version stayed at 2 and `onUpgrade` had no `ribbon` step. A phone that
had installed an earlier build kept its old tables, so `CREATE TABLE IF NOT
EXISTS` did nothing and the new `SELECT` failed. The same would have happened on
the TX1-S after an update, and `addFavorite` would have failed the same way.

**Fix (v1.1, schema v3).**

* `DB_VERSION = 3`, `onUpgrade` adds the missing columns;
* every open also runs `healColumns()`: `PRAGMA table_info` on `favorites` and
  `history`, then `ALTER TABLE ADD COLUMN` for anything the current queries need
  (`ribbon`, `seriesUrl`, `positionMs`, …). Old rows are preserved;
* `recordHistory` now stores `card.ribbon`, so history cards keep their badge.

The ALTER/SELECT pair was executed against a simulated old database with
`python3 -c sqlite3` on this host (old tables without `ribbon`/`seriesUrl`, one
existing row): the migration adds the columns, all four card queries succeed and
the row survives. That validates the SQL, not Android's `SQLiteOpenHelper`
callbacks — an in-place upgrade on a device is still the confirming test (§6
row 14).



## 6. What could not be verified here — and how to verify it on the box

This build host has **no device, no emulator, no adb and no WebView**. The only
real-device data point so far is a manual side-load on a Redmi Note 8, which
surfaced the database regression described in §5.1 (fixed) and nothing about
playback or the TV UI. Everything below is implemented but unproven:

| # | Item | Why it is unproven | Check on the TX1-S |
|---|------|--------------------|--------------------|
| 1 | Installation and launch | no adb | `adb install -r build/qrmzi-tv.apk` then `adb shell am start -n tv.qrmzi.client/.MainActivity` |
| 2 | Leanback launcher tile | no `resources.arsc`, so no icon/banner yet; Android falls back to the default tile | look at the Android TV home row |
| 3 | Embedded player playback | WebView + JW Player only exist on the device | open any episode, wait for the provider player |
| 4 | Fullscreen video | `WebChromeClient.onShowCustomView` needs a real WebView | press the player's fullscreen control |
| 5 | D-pad focus routing inside the player | provider UI decides its own focus | arrow keys + CENTER on the JW Player controls |
| 6 | Media keys (play/pause/seek) | requires a live video element | remote media keys during playback |
| 7 | Ad-filter behaviour | needs real ad requests | Settings → «تصفية الإعلانات», then play; watch `filter.block` in «التشخيص» |
| 8 | Memory under 1 GB | needs the real heap | `adb shell dumpsys meminfo tv.qrmzi.client` |
| 9 | Arabic shaping/RTL on TV | font fallback differs per device | inspect Home and Episode screens |
| 10 | HTTPS/redirect behaviour of the provider chain | network-dependent | watch for the error overlay; try "سيرفر آخر" |
| 11 | Native fallback control bar (INFO) | needs a live `jwplayer()`/`<video>` to read state | press INFO in the player; check play/pause, ±10 s, position/duration |
| 12 | Renderer-death recovery | needs a real renderer kill | `adb shell am kill <webview pid>` then press retry |
| 13 | Resume/continue-watching persistence | the WebView must report `position/duration` and the process must actually die between runs | watch ≥ 60 s, BACK, reopen the app: Home «متابعة المشاهدة» + episode «متابعة من …» |
| 14 | SQLite v2→v3 upgrade | needs an installed older build to upgrade in place | install the previous APK first (or one whose `history` lacks `ribbon`), then this one: History/Home must load and keep the old rows instead of showing «حدث خطأ غير متوقع» |
| 15 | Filter auto-disable | requires a player page that fails *and* refused sub-resources | block a host the player needs via `qrmzi-filter.txt`, reopen: Settings must show «متوقفة تلقائيًا» and the page must load on the retry |
| 16 | One-tap filter exception | needs a real blocked host to record | after a block appears in Settings, press «استثناء آخر نطاق محجوب» and confirm the request succeeds |
| 17 | Offline message + auto-retry | needs a real network transition | turn Wi-Fi off, open an episode, turn Wi-Fi on: it must retry once by itself |
| 18 | `onTrimMemory`/`onLowMemory` trims | needs the real low-memory callbacks | `adb shell am send-trim-memory tv.qrmzi.client RUNNING_LOW` then check «التشخيص» counters and a flat `meminfo` |
| 19 | Diagnostics export | needs the app's external files dir | Settings → التشخيص → «تصدير السجل», then `adb pull /sdcard/Android/data/tv.qrmzi.client/files/qrmzi-diag.txt` |

A manual pass with ADB key events covers the remote requirement:

```bash
adb shell input keyevent 20   # DPAD_DOWN
adb shell input keyevent 22   # DPAD_RIGHT
adb shell input keyevent 23   # DPAD_CENTER
adb shell input keyevent 4    # BACK
adb shell input keyevent 82   # MENU
```

`tools/device_check.sh` automates that sequence (install → launch → D-pad walk →
screenshots → `meminfo`/`gfxinfo` → filtered logcat):

```bash
ADB="adb -s 192.168.1.42:5555" tools/device_check.sh --install
```

## 7. Known limitations

* **No app icon or TV banner.** Without AAPT there is no `resources.arsc`, so the
  manifest cannot reference a drawable. The launcher shows a generic tile; adding
  a hand-built `resources.arsc` (planned ids `0x7F010000`/`0x7F010001`) plus
  generated PNGs is the follow-up.
* **Pagination is one page at a time** on Latest/Catalogue by design (memory
  first); there is no infinite prefetch.
* **No download/offline mode** and no Media3 player — the site's streams are
  HLS behind the provider's player, and the brief forbids extracting them.
* **The site can change.** Parsers are selector-based; if Qrmzi changes its
  template, episode/series parsing needs an update (the parser tests make that
  cheap to check).
* **Ad filter is best-effort**, a known ad/tracking host blocklist only (≈55
  domains); it can never block a trusted/essential host, it is on by default and
  can be switched off in Settings, and it disables itself (until re-enabled) only
  if a player load fails after it refused a sub-resource.

## 8. Reproducing this report

```bash
bash tools/build.sh
python3 tools/verify_apk.py build/qrmzi-tv.apk build/classes.jar
java -jar /tmp/opencode/bt/android-14/lib/apksigner.jar verify --min-sdk-version 21 --verbose build/qrmzi-tv.apk
bash tools/parsertest/run.sh
```
