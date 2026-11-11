# Qrmzi TV — Architecture

Companion to `README.md` (usage, build) and `INTEGRATION_PLAN.md` (rationale and
phases). This document is the reference for how the code is put together, so a
future change does not break the assumptions the app is tuned for.

---

## 1. Shape of the app

```
                    ┌──────────────────────────────┐
                    │         App : Application    │
                    │  Prefs · Store · Repo ·      │
                    │  ImageLoader  (singletons)   │
                    └──────────────┬───────────────┘
                                   │ used by
     ┌─────────────────────────────┴──────────────────────────────┐
     │                    MainActivity : Activity                 │
     │  TopBar (persistent, overlay)   ·   ArrayList<Screen> stack│
     │  implements Host  (navigation + toast + data access)       │
     └───────┬─────────────────────────────────────────┬──────────┘
             │ pushes / pops                           │ Intent
   ┌─────────▼──────────┐                    ┌─────────▼───────────┐
   │  ui/screens/*      │  read              │ player/PlayerActivity│
   │  Home Latest …     │───────┐           │  WebView + chrome    │
   └─────────┬──────────┘       │           └─────────┬───────────┘
             │                  │                     │ iframe URLs
   ┌─────────▼──────────┐  ┌────▼──────────┐   ┌──────▼───────────┐
   │ ui/ primitives     │  │ data/Repo     │   │ data/Parsers     │
   │ ThumbView Carousel │  │ caches        │──▶│ Jsoup selectors  │
   │ CardGrid Hero …    │  └────┬──────────┘   └──────┬───────────┘
   └────────────────────┘       │                     │
                          ┌─────▼──────┐        ┌─────▼──────┐
                          │ data/Store │        │ data/Http  │
                          │ SQLite     │        │ + allowlist│
                          └────────────┘        └────────────┘
```

No Fragments, no Navigation component, no ViewModel library, no DI container.
The activity *is* the host, screens are plain objects, and the application object
owns everything that must survive an activity.

## 2. Responsibilities

| Unit | Owns | Must not |
|------|------|----------|
| `App` | one `Prefs`, `Store`, `Repo`, `ImageLoader`, `Diag` sink; `onTrimMemory`/`onLowMemory` | hold any View or Activity reference |
| `MainActivity` | top bar, `Screen` stack, section switching, BACK/MENU, URL validation | parse HTML or touch the WebView |
| `Screen` (+ subclasses) | one view tree, its coroutine `Job`, focus memory (`lastFocused`) | start activities directly — it calls `Host` |
| `Repo` | TTL + LRU caches in front of `Http`/`Parsers`, stale-page fallback | cache bitmaps (that is `ImageLoader`) |
| `Store` | favorites + history tables | decide navigation |
| `ImageLoader` | bitmap cache, disk cache, decode sizing | be used off the main thread for UI mutations (it posts to main) |
| `Filter` | pure block/allow matching + config parsing, or `null` to leave a request alone | know about Android or decide when to apply |
| `Diag` | 160-line ring, counters, safe URL formatting, export | log query strings or grow without bound |
| `PlayerActivity` | WebView, iframe resolution, media keys, filter application, diagnostics | parse Qrmzi episode pages (it receives a page URL) |

## 3. Threading

* **Main thread**: all view construction/mutation, `Screen` coroutines use
  `Dispatchers.Main.immediate` and switch with `withContext(Dispatchers.IO)` for
  every network/DB call inside `Repo` and `Store`.
* **Image worker pool**: 3 daemon threads at `MIN_PRIORITY`; results are posted
  back with a `Handler(Looper.getMainLooper())` and dropped if the `ThumbView`'s
  `imageTag` changed (view recycling / screen destruction).
* **Player**: one coroutine on the UI dispatcher resolves the iframe chain; the
  WebView does its own networking on Chromium threads.
* Cache maps are plain `LinkedHashMap` guarded by `synchronized(map)` — no
  concurrent collections needed at this scale.

## 4. Caching and memory budget

| Cache | Capacity | TTL | Key |
|-------|----------|-----|-----|
| `htmlCache` | 14 pages | 10 min | URL |
| `feedCache` | 8 pages of cards | 3 min | URL |
| `catalogueCache` | 8 `Catalogue` | 3 min | URL |
| `seriesCache` | 10 `SeriesInfo` | 10 min | URL |
| `episodeCache` | 12 `EpisodeInfo` | 10 min | URL |
| `playerCache` | 24 resolved iframe URLs | — | episode URL |
| Bitmap LRU | `maxMemory / 8`, capped at 14 MB | — | URL |
| Image disk | 32 MB (`cacheDir/img`) | — | SHA-256 of URL |

Additional rules that keep a 1 GB box alive:

1. Archive screens append **one page per load** (50 cards home feed, 40 catalogue);
   no prefetching of page *n+1*.
2. Images are decoded at the requested size: `inSampleSize` for cards,
   `RGB_565` unless the user picks the sharper quality setting.
3. `App.onTrimMemory(≥ RUNNING_LOW)` clears the bitmap cache; `onLowMemory`
   clears it and the disk cache.
4. Only one WebView exists in the process, in `PlayerActivity`; it is destroyed
   (`stopLoading` → `about:blank` → `removeView` → `destroy`) in `onDestroy`.
5. `Screen.destroy()` cancels its coroutine `Job`, so a popped screen cannot
   update views or retain bitmaps.

## 5. Navigation and focus model

* `MainActivity.switchTo(section)` clears the stack and pushes the section's
  root screen; `push()`/`goBack()` maintain the stack and re-attach the previous
  screen's already-built view (no rebuild, no reload — `ensureLoaded()` guards).
* Every `Screen` records `lastFocused` through
  `viewTreeObserver.addOnGlobalFocusChangeListener`, and `restoreFocus()` prefers
  that view over "first focusable", so BACK returns the remote to the exact card
  it came from.
* Layouts are `LAYOUT_DIRECTION_RTL` with `layoutDirection` set explicitly,
  because the device may run with an English system locale.
* `Carousel` scrolls its `HorizontalScrollView` when focus reaches the edge
  (`Focus.scrollIntoView`), and `CardGrid` is a plain focus-ordered grid inside
  the scaffold's `ScrollView` — no `RecyclerView`, so focus order is
  deterministic and there is no adapter churn.
* BACK is handled by `Screen.onBack()` first (e.g. closing an inline panel), then
  by the activity stack; at the root it finishes the app.

## 6. Data layer contract

`Repo` exposes suspend functions (`home`, `latestPage`, `seriesCatalogue`,
`search`, `series`, `episode`, `playerSources`) and always runs IO on
`Dispatchers.IO`. `Parsers` are pure functions `String -> Model`, which is why
they can be tested on the JVM (`tools/parsertest`). `Http` is the only place that
touches the network:

* explicit connect/read timeouts (12 s / 20 s), max 6 redirects, gzip accepted,
  browser-like User-Agent, `Referer` when a sub-page is requested;
* one retry for transient failures (connect/TLS, 408, 429, 5xx) with a short
  backoff; 4xx is final, so a 403/404 never costs a second request;
* hard caps of 4 MiB of text / 8 MiB per image, so a broken response cannot
  exhaust the 1 GB heap;
* `isTrustedHost()` is the single allowlist used by both the WebView navigation
  policy and the filter's essential-resource rule.

`Repo.playerSources(pageUrl)` fetches the episode page **once** and returns both
the provider wrapper and the deepest public player page, replacing the old
two-request/two-parse `resolvePlayer` + `wrapperPlayer` pair. Its TTL cache means
re-opening an episode does not touch the network.

When a page fetch fails, `Repo.html` falls back to a stale cached copy of the
same URL (and counts `repo.stale`), so losing Wi-Fi mid-session does not throw
away a page the user already visited.

### 6.1 Local persistence (`Store`, `Playback`)

`Store` is a `SQLiteOpenHelper` on `qrmzi.db`, schema **v3**. It is the
"lightweight local persistence" option: Room would pull in AndroidX + KSP for
three small tables, which is not justified on a 1 GB box (and cannot be built on
this host). `onUpgrade` is additive — v2 adds `searches`, v3 adds the `ribbon`
badge column — and leaves existing favourites/history untouched.

Because `CREATE TABLE IF NOT EXISTS` never modifies an existing table, `onOpen`
also re-checks the columns of both card tables and adds anything missing. That
makes a database written by an older build (or restored from a backup) safe: a
`SELECT` can no longer fail with `no such column: ribbon` after an update.

| Table | Purpose | Bound |
|-------|---------|-------|
| `favorites` | one row per favourited series/movie/episode | user-driven |
| `history` | one row per opened page + `seriesUrl` + `positionMs`/`durationMs` | newest 200 |
| `searches` | recent query text (`COLLATE NOCASE`) | newest 12 |

API surface used by the UI:

* favourites: `addFavorite`, `removeFavorite`, `toggleFavorite`, `isFavorite`,
  `favorites`;
* history: `recordHistory` (preserves artwork and saved position when a caller
  has less information), `touchHistory`, `updatePosition`, `removeHistory`,
  `clearHistory`, `history`, `resumeOf`, `resumePosition`;
* continue watching: `continueWatching(limit)` — SQL-side filter
  `positionMs >= 30 s AND durationMs > 0 AND position/duration < 95 %`, newest
  first, so no work happens on the UI thread per card;
* searches: `addSearch` (collapses whitespace, prunes to 12), `searches`,
  `removeSearch`, `clearSearches`, `countSearches`.

`Playback` is dependency-free Kotlin (`MIN_RESUME_MS`, `MIN_STORE_MS`,
`FINISHED_FRACTION`, `progress`, `isResumable`, `shouldStore`, `clock`,
`normalizeQuery`) so the exact thresholds are covered by the JVM test suite.

Writes are single-row upserts and are issued from the player's 10 s heartbeat and
from pause/exit; the database therefore never holds more than a few kilobytes and
no media is ever downloaded or stored.

### 6.2 Diagnostics, filtering and resilience

**`Diag`** is a process-wide, bounded observer with no Android imports:

* ring of 160 timestamped lines (`verbose` off by default, `important` always),
  mirrored to logcat through a sink installed by `App`;
* counters for page loads, player resolution/loads/errors, filter blocks,
  auto-disables, image cache hits/misses/loads, memory trims and HTTP failures;
* `safeUrl()` reduces URLs to `scheme://host/path` (query and fragment removed,
  path capped at 80 chars) before anything is logged;
* `snapshot()` / `export(File)` feed the Settings viewer and the export action.

**`Filter`** is pure matching logic. `Config` is a pair of block/allow domains.
Effective rules = built-in conservative list + `prefs.filterUserBlock` +
`allow`/`block` lines from the optional external `qrmzi-filter.txt` +
`prefs.filterUserAllow`. `match()` returns the offending domain or `null`, and
checks in this order:

1. `Http.isTrustedHost` and `Filter.ESSENTIAL` (Qrmzi, player chain, JW Player,
   common JS CDNs) → always `null`;
2. any explicit allow rule → `null`;
3. first matching block rule → that domain.

`matches()` is suffix-aware (`host == d || host.endsWith(".$d")`) and normalisation
strips scheme, port, path, query, `www.` and `*.`, then validates the hostname, so
lookalikes (`notpgo99.com`) never match and user typos are ignored instead of
becoming broad blocks.

`PlayerActivity` applies the config only in `shouldInterceptRequest`, never for
the main frame, and only while the Settings switch is on. It counts each refusal
and remembers the last blocked host for the one-tap whitelist. If the main frame
fails **and** at least one request was refused during that load, it disables the
filter persistently, logs why, and retries once without it. Restarting the
activity after a renderer death re-reads the user's switch.

**Resilience.** `NetworkMonitor` uses `ConnectivityManager` only on error paths:
if the box is offline the player shows a specific message and registers a
one-shot callback that retries when a network appears. `Repo.trim()` drops the
oldest cached pages under `TRIM_MEMORY_RUNNING_LOW`; `ImageLoader.trimMemory()`
halves the bitmap LRU, and `COMPLETE`/`onLowMemory` clears it. Both are called by
`App`, never from a screen, so a screen never has to know its own memory budget.

## 7. Security model

* Internal navigation is restricted to `https` + `Http.isTrustedHost` (Qrmzi plus
  the approved player origins). Anything else opens in the system browser.
* `usesCleartextTraffic=false`, `allowFileAccess=false`,
  `allowContentAccess=false`, `MIXED_CONTENT_NEVER_ALLOW`, no JavaScript bridge
  (`addJavascriptInterface` is never called), no geolocation.
* `PlayerActivity` is not exported; episode URLs received from the UI are
  validated (`https` + Qrmzi host) before the player is started.
* Cookies are kept only by the WebView's own `CookieManager` (flushed on pause)
  so provider sessions survive a restart; no credentials, tokens or account data
  are written to `SharedPreferences` or SQLite (only favourites, watch positions
  and the queries the user typed).
* The filter is a sub-resource blocklist that can never block a trusted host or
  an essential CDN; it self-disables (persistently) only when a main frame fails
  after it refused a sub-resource, then retries once without it.
* Diagnostics keep only host/path, never query strings; the ring is bounded and
  can be cleared or switched off from Settings.

## 8. Adding a screen

1. Subclass `Screen`, implement `build()` (return the top view; use
   `scaffold()` / `pageHeader()` / `newGrid()` helpers), `load()` for data.
2. Use `host.repo` inside `scope.launch { … }` and `withContext` via `Repo`.
3. Override `onShow()` for refresh-on-return and `onBack()` only if the screen
   has an internal mode.
4. Add the entry point to `Host` + `MainActivity` and, if it is a top-level
   destination, to `TopBar.Section` and `MainActivity.create()`.

## 9. Known architectural debt

* No `resources.arsc` → no launcher icon/banner, all UI is programmatic. This is
  a build-host limitation, not a design choice; see `BUILD_AND_VERIFICATION.md`.
* Parsers are coupled to the site template; `WEBSITE_STRUCTURE.md` is the
  contract they encode.
* `Screen` subclasses build their views once and keep them; on a 1 GB box this is
  cheaper than rebuilding, but it means very deep stacks would grow the heap —
  the app keeps the stack shallow (section root + details).
