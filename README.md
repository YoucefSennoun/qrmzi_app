# QRMZI TV — Android TV client

A native Android TV client for **[qrmzi.tv](https://www.qrmzi.tv/)**, built for the
Mortal TX1-S box (Android 10 / API 29, ~1 GB RAM) and driven entirely by an
infrared or Bluetooth remote.

The app is a *client*, not a mirror: it fetches the public pages of Qrmzi,
parses the cards, series pages and episode pages, and hands playback to the
provider's own embedded player inside a WebView. No stream URL is read, no DRM
or access restriction is touched, and nothing is downloaded.

---

## What it looks like

| Screen | Content |
|--------|---------|
| **الرئيسية** (Home) | large featured episode, horizontal carousels from the live home feed, و«متابعة المشاهدة» (Continue watching) with the saved position of each episode |
| **أحدث الحلقات** (Latest) | paginated archive, 50 cards per page |
| **المسلسلات** (Series) | paginated catalogue, 40 cards per page |
| **الأفلام** (Movies) | the movie section of the catalogue |
| **البحث** (Search) | on-screen Arabic keyboard, results from the site search, persisted recent queries |
| **صفحة المسلسل** (Series detail) | cover, synopsis, season list, play / favourite |
| **الحلقة** (Episode) | description, «متابعة من …» / «من البداية», next/previous episode, related episodes |
| **المفضلة** (Favorites) | local list, long-press on the remote removes an entry |
| **السجل** (History) | recently opened items with a progress bar |
| **الإعدادات** (Settings) | ad filter + config file, one-tap exception, diagnostics log, reduced motion, image quality, clear history / favourites / search history |

Dark background, red accent (`#E50914`-family), full RTL layout, 1.04–1.06 focus
scale, 2 dp white focus ring, `Noto`/`sans-serif` Arabic-capable typography.

## Remote control

| Key | Action |
|-----|--------|
| D-pad | move focus; carousels scroll when focus reaches the edge |
| CENTER / ENTER | open card / activate button |
| BACK | one level up, then exit (in the player: close fullscreen → close the native bar → stop) |
| MENU | open Settings; inside the player: switch provider server |
| INFO | inside the player: open the native control bar (play/pause, ±10 s, fullscreen, server, exit) |
| MEDIA_PLAY_PAUSE | play/pause via `jwplayer()` or `<video>` (shows the bar briefly) |
| MEDIA_FAST_FORWARD / REWIND | seek ±10 s |

Focus is restored when returning to a screen, and the first focusable element is
focused after every load so the remote is never "lost".

## Architecture

```
tv.qrmzi.client
├── App                      Application singletons (prefs, store, repo, images)
├── MainActivity             single activity, persistent TopBar, screen stack
├── data/
│   ├── Http                 HttpURLConnection + one transient retry + size caps
│   ├── Parsers              Jsoup parsers for home / catalogue / search / series / episode
│   ├── Repo                 TTL LRU caches, single-pass player resolution, stale fallback
│   ├── Store                SQLite (framework, no Room): favorites + history + searches
│   ├── Playback             resume/progress thresholds and clock formatting (JVM-tested)
│   ├── Filter / FilterConfig conservative ad/tracking rules + optional config file
│   ├── Diag / NetRules      bounded in-memory log, counters, retry/offline helpers
│   ├── NetworkMonitor       one-shot "network is back" retry signal
│   ├── Prefs                SharedPreferences
│   ├── Models               Card / SeriesInfo / EpisodeInfo / Catalogue
│   └── Parsers …            (see QRMZI_ANALYSIS.md for the site selectors)
├── ui/                      framework-only Views, no AndroidX
│   ├── Theme, Ui, Focus, Screen (Host contract + scaffold helpers)
│   ├── ThumbView            hand-drawn poster: crop, radius, badge, ribbon, focus ring
│   ├── ImageLoader          LRU + 32 MB disk cache, RGB_565, 3 threads
│   ├── MediaCard, Carousel, CardGrid, Hero, TopBar, StateView, Scrim
│   ├── InfoDialog           dark, D-pad-friendly log/config dialog
│   └── screens/             Home, Latest, Catalogue, Search, Series, Episodes,
│                            Episode, Favorites, History, Settings
├── player/PlayerActivity    WebView player: iframe-chain resolution, cookies,
│                            fullscreen chrome client, media keys, filter, diagnostics
└── util/Arabic              whitespace/direction-mark cleanup and Arabic text helpers
```

**Why no AndroidX / Room / OkHttp / Coil?** The device has 1 GB of RAM and a slow
ARM core. The app needs framework views, one HTTP client, three tiny SQLite tables
and a small image cache — all of which the framework already provides. The only
libraries in the APK are `kotlin-stdlib`, `kotlinx-coroutines` and `jsoup`.

**State flow.** `MainActivity` keeps an `ArrayList<Screen>` stack. `Screen` is a
plain class with `build()` / `load()` / `onShow()` / `restoreFocus()` /
`destroy()`; screens talk to `Host` (implemented by `MainActivity`) for
navigation and to `Repo`/`Store`/`Prefs`/`ImageLoader` for data. No Fragments, no
Navigation component, no global observable state to leak.

**Memory.** one WebView, images decoded at the size they are shown
(`RGB_565`, `inSampleSize`), LRU bitmap cache sized from `maxMemory`, 32 MB disk
cap, partial/full `onTrimMemory` and `onLowMemory` hooks in `App` (bitmaps + page
caches), archive screens paginate one page at a time, and the player WebView is
destroyed with the activity.

## Local data (favourites, history, resume, search history)

Storage is the framework's own SQLite (`SQLiteOpenHelper`, database `qrmzi.db`,
schema v3) — the "lightweight local persistence" option, chosen over Room because
Room needs KSP/AndroidX, which would add megabytes and build tooling this 1 GB
device and this build host do not need.

| Table | Columns | Cap |
|-------|---------|-----|
| `favorites` | url (PK), title, image, kind, number, ribbon, addedAt | unbounded, user-driven |
| `history` | url (PK), title, image, kind, number, ribbon, seriesUrl, positionMs, durationMs, updatedAt | newest **200** rows, pruned on write |
| `searches` | query (PK, `NOCASE`), at | newest **12** queries |

Schema changes are additive (v1 → v2 added `searches`, v2 → v3 added `ribbon`),
and `onOpen` re-checks both card tables so a database from an older build gets a
missing column added instead of failing a `SELECT`.

* **Favourites** are added/removed from the series page and the episode page
  (`＋ قائمتي` / `✓ في قائمتي`), removed with a long press in **المفضلة**.
* **Watch history** records every episode/movie opened from the app, with the
  artwork and the series link, and is cleared from **السجل** or **الإعدادات**.
* **Resume** is written by the player itself: it reads
  `jwplayer().getPosition()/getDuration()` (or a `<video>` element) and stores a
  position only when the reported state is trustworthy — a real duration, more
  than 5 s played, no overflow. Resume is offered only past 30 s and below 95 %
  of the episode (`data/Playback.kt`, unit-tested). At ≥ 95 % the position is
  cleared so finished episodes leave «متابعة المشاهدة» while staying in history.
* **«متابعة المشاهدة»** on Home lists those resumable rows newest-first with a
  per-card progress bar; CENTER resumes playback from the saved second.
* **Search history** is the collapsed, trimmed query text only. It powers the
  chips under the search field (CENTER repeats, long press removes, last chip
  clears) and a clear row in Settings.
* No video files, no downloaded media, no names, emails or accounts are stored —
  only the URLs/titles that already appear on the public site, the positions, and
  the queries the user typed. Cookies stay in the WebView's own cookie store.

## Website behaviour the client relies on

Verified against saved fixtures (see `WEBSITE_STRUCTURE.md` and
`tools/parsertest/ParserTest.kt`):

* cards are `article.post` (portrait) / `article.postEp` (landscape) with the real
  image in `data-src`, episode number in the second `.episodeNum` span;
* totals: 921 episodes, 191 series, 49 movies (as captured);
* the home feed is 50 cards/page, the catalogue 40/page, and a past-the-end page
  answers HTTP 200 with zero cards — that is how pagination ends;
* episode adjacency comes from the DOM order of `#episodes-tab`;
* movie pages reuse the series template without an `#episodes-tab`;
* the player chain is `qrmzi.tv` → `anaplayer` → (optional `shooof`) →
  `cdnplus.sbs` JW Player 8.34.5, HLS, no DRM. The app resolves the deepest
  **public** iframe (max 3 hops) and loads it directly; the intermediate wrapper
  page is kept as a "switch server" fallback.

## Build

There is no Android SDK aapt/aapt2 for this host (aarch64 Termux with x86_64 SDK
archives), so the build does the packaging itself. Requirements: `java`,
`kotlin-compiler-embeddable`, `d8.jar`, `apksigner.jar`, `python3`, the platform
`android.jar`, and the runtime jars (`kotlin-stdlib`, `kotlinx-coroutines`,
`jsoup`). Paths are configured at the top of `tools/build.sh`.

```bash
bash tools/build.sh              # signed release APK in build/qrmzi-tv.apk
bash tools/build.sh --debug      # debuggable manifest
bash tools/build.sh --unsigned   # stop before signing
```

The pipeline is:

1. `kotlinc` (in-process, capped at `-Xmx192m`) → `build/classes.jar`;
2. `d8 --release --min-api 29` → `build/dex/classes.dex` (warnings go to `build/d8.log`);
3. `tools/gen_manifest.py` → binary `AndroidManifest.xml` (hand-built AXML);
4. `tools/package_apk.py` → zip with a stored, 4-byte-aligned `classes.dex`
   (what ART mmaps) and a deflated manifest;
5. `apksigner sign` → `build/qrmzi-tv.apk` (v1 + v2 + v3), then `apksigner verify`.

Install on the box:

```bash
adb connect <box-ip>:5555
adb install -r build/qrmzi-tv.apk
adb shell am start -n tv.qrmzi.client/.MainActivity
```

`keystore/qrmzi-release.jks` (alias `qrmzi`, password `qrmzi-tv-2026` unless
`QRMZI_STOREPASS` is set) is generated on first build. **Keep it**: updates must
be signed with the same key.

## Tests and verification

```bash
# 114 assertions: site parsers, local rules, filter matching, diagnostics, retry policy
bash tools/parsertest/run.sh

# static APK verification: zip layout, manifest, dex header/checksum/classes
python3 tools/verify_apk.py build/qrmzi-tv.apk build/classes.jar

# on a machine that can see the TV box: install, D-pad walk, screenshots, meminfo
ADB="adb -s 192.168.1.42:5555" tools/device_check.sh --install
```

Results of the last build are in `BUILD_AND_VERIFICATION.md`, including everything
that could *not* be tested (no device, no emulator, no adb on this host).

## Filtering, diagnostics and resilience

The filter (`Settings → تصفية الإعلانات`, on by default and switchable off)
only applies to **sub-resources of the player WebView**. It is conservative by
design:

* the default list contains known popunder/display/analytics hosts only
  (`pgo99.com`, `popads.net`, `doubleclick.net`, `google-analytics.com`, …);
* `Filter.ESSENTIAL` plus `Http.isTrustedHost` form an absolute allowlist:
  Qrmzi, `anaplayer`, `shooof`, `cdnplus`, JW Player's CDN and common JS CDNs
  can never be blocked, even by a hand-edited config file;
* the main page frame is never intercepted, and a cache-miss/offline error does
  not count as "filter broke playback";
* it auto-disables (persisted) only when the page failed **and** the filter
  actually refused a sub-resource during that load, then retries once without
  it. Settings shows the state as «متوقفة تلقائيًا» and the normal switch
  re-enables it.

Extra rules are optional and user-editable:

* in Settings: «استثناء آخر نطاق محجوب» is a one-tap whitelist for a host that
  turns out to be required; «مسح الحجب الإضافي» / «مسح استثناءات التصفية» reset
  the manual lists;
* from a PC, `qrmzi-filter.txt` in the app's external files directory
  (`/sdcard/Android/data/tv.qrmzi.client/files/`) accepts `block <domain>`,
  `allow <domain>` and `# comments`. It is read at every player open, so no
  restart is needed.

Diagnostics are always available in `Settings → التشخيص`: a 160-line bounded
in-memory log, counters (pages, player loads/errors, filter blocks, image cache,
memory trims, network failures), a viewer, an export to
`Android/data/tv.qrmzi.client/files/qrmzi-diag.txt`, and a clear action. Verbose
logging is opt-in. URLs are reduced to `scheme://host/path` before logging, so
query strings/tokens never reach the log or logcat.

Resilience: `Http` retries one transient failure (never 4xx) and caps response
sizes; `Repo` serves a stale cached page when Wi-Fi drops; the player detects
"offline" separately and retries once automatically when connectivity returns;
`App.onTrimMemory/onLowMemory` trims the bitmap cache and page caches; and a
WebView renderer death shows a recovery screen instead of crashing the process.

Playback is the provider's embedded player. If the deep iframe fails, the app
retries with the provider wrapper, then with the Qrmzi page, and finally offers
"إعادة المحاولة" / "سيرفر آخر". `WEBVIEW_COMPATIBILITY.md` explains what the
WebView runtime supports on Android 10 and why Media3 is not used.

## Documentation

| File | Contents |
|------|----------|
| `QRMZI_ANALYSIS.md` | what the site is, catalogue shape, URL grammar, player chain |
| `WEBSITE_STRUCTURE.md` | exact selectors, pagination, end detection, ad hosts |
| `WEBVIEW_COMPATIBILITY.md` | WebView capabilities, D-pad injection, fallbacks |
| `INTEGRATION_PLAN.md` | delivery phases, caching, risks, definition of done |
| `ARCHITECTURE.md` | code map, threading, caches, focus model, security model |
| `DEVELOPMENT_PLAN.md` | phase-by-phase status and the next device-session checklist |
| `REMOTE_TEST_PLAN.md` | key map, the full remote test matrix, and what is still unverified |
| `BUILD_AND_VERIFICATION.md` | build chain, test output, unverifiable items |

## Legal

Unofficial client. All content, images and video belong to their respective
owners and are shown directly from `qrmzi.tv` and its public player providers.
The app does not download, re-host or redistribute media, and does not bypass
authentication, subscriptions, DRM, anti-bot protection or other access controls.

## License

Released under the [MIT License](LICENSE).

© 2026 Youcef Sennoun

## Download

Prebuilt APK: see the **Releases** page of this repository (`qrmzi-tv.apk`,
minSdk 21, targetSdk 29, Android TV).
