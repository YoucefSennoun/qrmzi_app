# Qrmzi TV — Development Plan & Status

The delivery phases below come from `INTEGRATION_PLAN.md` §10. This file records
what is **done**, what is **built but unverified**, and what is **not started**,
so the next session does not have to reconstruct it.

Legend: ✅ done and verified · 🟡 written, needs a device to verify · ⬜ not started

---

## Phase 0 — Toolchain and device truth ✅

* Four analysis documents delivered (`QRMZI_ANALYSIS.md`, `WEBSITE_STRUCTURE.md`,
  `WEBVIEW_COMPATIBILITY.md`, `INTEGRATION_PLAN.md`).
* Toolchain assembled without Gradle: `kotlin-compiler-embeddable`, `d8.jar`,
  `apksigner.jar`, platform `android.jar`, `jsoup`.
* Site truth captured as fixtures and encoded in `tools/parsertest`.

## Phase 1 — Skeleton, theme, focus primitives ✅

* `Theme` (dark palette, red accent, type scale, dp helpers, focus ring,
  `animationsEnabled`), `Ui` (buttons, chips, dividers, labels), `Focus`
  (first-focusable, scroll-into-view, focus-in), `ThumbView`, `MediaCard`,
  `Carousel`, `CardGrid`, `Hero`, `TopBar`, `StateView`, `Scrim`, `Screen`.
* RTL is forced in every root layout; no AndroidX views are used.

## Phase 2 — Network + parsers + fixtures ✅

* `Http` (HttpURLConnection, timeouts, redirect hop limit, gzip, referer,
  trusted-host allowlist) and `Parsers` (home feed, pagination, catalogue split,
  search, series, episode, iframe resolution).
* `tools/parsertest` → **60/60 checks pass** against the captured fixtures.

## Phase 3 — Persistence + repositories ✅

* Framework `SQLiteOpenHelper` (`Store`) — schema **v3** with `favorites`,
  `history` (position/duration/seriesUrl, pruned to 200) and `searches`
  (collapsed query text, pruned to 12), with additive `onUpgrade` plus an
  `onOpen` column healer (v3 fixed a `no such column: ribbon` upgrade crash
  found on the first real-device run); `Prefs` for ad
  filter, reduced motion, image quality; `Repo` with TTL/LRU caches and
  `invalidateAll`; `Playback` for the resume/clock rules (JVM-tested).
* Favourites are toggled from the series and episode pages and removed with a
  long press in **المفضلة**; history and search history each have a clear row in
  **الإعدادات**.

## Phase 4 — Browsing UI complete ✅

Home, Latest, Catalogue (series + movies), Search (Arabic on-screen keyboard
plus persisted recent queries), Series detail, Episode list, Episode detail,
Favorites, History, Settings — all 10 screens implemented with loading/empty/error
states and focus restoration.

## Phase 5 — Player 🟡

* Implemented: deep iframe resolution (max 3 hops), cookie persistence, trusted
  navigation policy, `WebChromeClient` fullscreen, media-key → `jwplayer()` /
  `<video>` bridge, MENU server switch, error overlay with retry, ad filter with
  auto-disable retry, wrapper → page fallback ladder.
* Native fallback bar (INFO): play/pause, ±10 s, position/duration, fullscreen,
  another server, exit — state read from `jwplayer()` with a `<video>` fallback.
  A provider that binds no keys is still fully controllable.
* `onRenderProcessGone` handled: no crash, explained error, retry restarts the
  player activity with the same episode.
* Unverified: actual playback, fullscreen transition and D-pad routing inside the
  provider UI — no device/WebView on this host (`REMOTE_TEST_PLAN.md`).

## Phase 6 — Resume & continue watching ✅ 🟡

* The player reads `position|duration|state` from `jwplayer()` (or `<video>`)
  every 10 s and on pause/exit, and writes it with `Store.updatePosition` only
  when `Playback.shouldStore` accepts it (real duration, > 5 s, no overflow).
* `Playback` decides resume (≥ 30 s, < 95 %). «متابعة من h:mm» / «من البداية» on
  the episode page, resume-on-open from Home's «متابعة المشاهدة» row, and a
  best-effort `jwplayer().seek()` (3 attempts) when the provider accepts it.
* Finished episodes clear their position, so they leave continue-watching while
  staying in history.
* Home refreshes the continue row when it returns from the player (signature
  check, no feed re-fetch).
* Unverified on device: whether the provider's time API is reachable through the
  iframe and whether `seek()` actually continues playback (see
  `REMOTE_TEST_PLAN.md` §3, item 10).

## Phase 7 — Filtering, diagnostics and TX1-S tuning ✅ 🟡

* **Filter** (`data/Filter.kt`): conservative known ad/tracking list, an absolute
  essential allowlist (Qrmzi + player chain + JW Player/CDNs), pure suffix
  matching, config-file parser, user block/allow lists. Applied only to
  sub-resources of the player WebView; the main frame is never touched. It
  auto-disables and retries once only when a load actually failed after refusals.
* **Settings**: filter switch, state (`متوقفة تلقائيًا`), rule file help/reload,
  one-tap exception for the last blocked host, clear user lists, live counters.
  Plus a diagnostics section (verbose toggle, bounded log viewer, export, clear).
* **Diagnostics** (`data/Diag.kt`): 160-line ring, counters for HTTP/player/
  filter/image/memory events, `safeUrl()` (no query strings), logcat sink,
  export file; `NetRules` retry/offline policy is unit-tested.
* **TX1-S tuning**: single-pass `Repo.playerSources` (one page fetch for wrapper
  + deep player), transient HTTP retry + 4 MiB/8 MiB response caps, stale-page
  fallback, image disk cap 32 MB + partial/full LRU trims, `Repo.trim`,
  `setOffscreenPreRaster(false)`, no form/password storage, offline detection
  with automatic one-shot retry when the network returns.
* ✅ JVM suite covers 114 checks; 🟡 real-world filter/offline/trim behaviour and
  the memory profile still need the box.

## Phase 8 — Hardening and release 🟡

* ✅ Signed APK built end-to-end (`build/qrmzi-tv.apk`), static verification
  passes (zip, manifest, dex header/checksum/class completeness), signature
  v1 + v2 + v3.
* ✅ `README.md`, `ARCHITECTURE.md`, `BUILD_AND_VERIFICATION.md` written.
* ✅ First manual side-load (Redmi Note 8) found a real upgrade bug — `no such
  column: ribbon` on databases created by an earlier build; fixed in v1.1
  (schema v3 + `onOpen` column healer) and covered by a SQL-level migration test.
* 🟡 Installation, launch, D-pad pass and memory profile need the TX1-S.
* ⬜ Launcher icon + TV banner (requires a hand-built `resources.arsc`).

---

## Immediate next steps (device session)

1. `adb install -r build/qrmzi-tv.apk` — confirms the manifest/dex are accepted by
   PackageManager (the one thing static checks cannot prove).
2. Launch from the Leanback home row; confirm Arabic renders and the first card
   is focused without any key press.
3. Walk every screen with DPAD keys; verify the focus ring is always visible and
   BACK returns to the previously focused card.
4. Open an episode: confirm the provider player loads, plays audio/video, enters
   fullscreen, and that BACK leaves fullscreen before exiting.
5. Toggle the ad filter and reload the player; confirm it never blocks playback.
   If an exception is needed, use «استثناء آخر نطاق محجوب»; check that the
   filter state and counters update in Settings.
6. `adb shell dumpsys meminfo tv.qrmzi.client` while scrolling Home — target is a
   flat PSS after the bitmap cache fills. Then background the app and check
   `adb logcat -s QrmziTV` for trim events (enable «سجل مفصّل» first).
7. Rotate the network (off) and reopen a page — confirm the specific offline
   message and that playback retries once when Wi-Fi returns.
8. Open `Android/data/tv.qrmzi.client/files/qrmzi-filter.txt`, add
   `block example.com`, reopen the player and confirm the counter rises; then use
   the one-tap exception and confirm it stops.
9. In-place upgrade check: install the 1.0 APK, open a few episodes, then
   `adb install -r` the 1.1 build. Home and **السجل** must load the old rows
   without «حدث خطأ غير متوقع» (§5.1 of `BUILD_AND_VERIFICATION.md`).

## Follow-up backlog

* Hand-built `resources.arsc` + generated icon/banner so the Leanback tile is
  branded (`0x7F010000` drawable, `0x7F010001` banner in `gen_manifest.py`).
* Report real playback position from the WebView into `Store` for resume.
* Optional: "continue watching" row on Home built from `Store.history()`.
* Parser regression run in CI-style before every APK build (`tools/build.sh`
  could call `tools/parsertest/run.sh` when fixtures exist).
