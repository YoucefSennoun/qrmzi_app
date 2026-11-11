# QRMZI TV — Integration & Build Plan

**Target:** Mortal TX1-S · Android 10 (API 29) · ~1 GB RAM · 1080p HDMI · IR/Bluetooth remote
**Source of truth:** `QRMZI_ANALYSIS.md` (what the site is), `WEBSITE_STRUCTURE.md` (how to read it),
`WEBVIEW_COMPATIBILITY.md` (what the runtime can do)
**Status:** implemented — signed APK built and statically verified; device/emulator
checks pending (see `BUILD_AND_VERIFICATION.md`). Code map and build commands are
in `README.md`.
**Date:** 6 November 2026

---

## 1. Architecture in one page

```
┌──────────────────────────────────────────────────────────────────┐
│                        :app  (single module)                     │
│                                                                  │
│  ui/                Native Android Views · RTL · D-pad first      │
│   Home · Latest · Series · Movies · Search · SeriesDetail         │
│   EpisodeDetail · Favorites · History · Settings                  │
│                     │ observes StateFlow                         │
│  vm/                ViewModel per screen (no LiveData)            │
│                     │                                            │
│  repo/              CatalogRepository · PlayerRepository          │
│                     │            · SettingsRepository             │
│        ┌────────────┴───────────┐                                │
│  net/ SiteClient (OkHttp)   db/ Room + DataStore                  │
│        │  single-flight,         │  series · episodes ·          │
│        │  2 parallel, TTL cache  │  feed · favorites · history   │
│        │                         │                               │
│  parse/ Jsoup parsers (versioned, fixture-tested)                 │
│                                                                  │
│  ─────────────────────────── process boundary ─────────────────  │
│                                                                  │
│  ui/player/  PlayerActivity  ── ONE WebView, created on demand ── │
│      ChainResolver (≤3 hops) · PlayerCss · AdFilter · fullscreen  │
└──────────────────────────────────────────────────────────────────┘
                        │ HTTPS (read-only, anonymous)
                        ▼
        www.qrmzi.tv (HTML + WP REST)
        w.anaplayer.online · shooof.online · cdnplus.sbs  (player chain)
```

**The rule that drives everything:** *native for everything the site renders as data; WebView only for the
thing the site renders as a player.* The WebView is never used for browsing — a single Qrmzi page is
~250 KB of HTML with ~180 KB of inline CSS and 50 images, which is unaffordable on 1 GB when a native list
can show the same content from 3 KB of parsed data.

### 1.1 Decision log

| # | Decision | Why | Rejected alternative |
|---|---|---|---|
| D1 | Single Gradle module, package-separated | Faster builds, no runtime benefit from modules on a solo app; keeps APK lean | `:core` / `:feature` modules |
| D2 | Native Views + RecyclerView, **no Leanback** | Leanback imposes its own rows/detail fragments and fights a Netflix-style design; `BrowseSupportFragment` is heavy | `androidx.leanback` |
| D3 | **No Jetpack Compose** | Brief requires native Views; Compose adds ~3–5 MB and extra RAM on a 1 GB box | Compose |
| D4 | Manual fragment transactions, **no Navigation graph** | Full control of focus restoration and BACK behaviour, which is the main UX risk | `androidx.navigation` |
| D5 | No DI framework | One `AppContainer` object with lazy fields is enough; Hilt adds build time and startup cost | Hilt / Koin |
| D6 | OkHttp + Jsoup, no Retrofit/Moshi codegen | 8 endpoint shapes, all HTML except two JSON calls; hand-rolled parsing is smaller and testable | Retrofit + Moshi |
| D7 | Coil for images with a **tight custom cache budget** | Coroutine-based, small, per-request size control; needs explicit limits for 1 GB | Glide (heavier), Picasso (stale) |
| D8 | Player in a **separate Activity**, one WebView, destroyed on exit | Guarantees WebView teardown, keeps browsing RAM free, isolates renderer crashes | WebView in a Fragment |
| D9 | `targetSdk = 29` | Device is API 29; keeps behaviour identical to the box and avoids scoped-storage surprises. Raising it later is a one-line change + retest | `targetSdk = 34` |
| D10 | No native libraries / no NDK | APK is ABI-agnostic → one universal build for any TX1-S revision; removes the "detect the ABI" risk from the critical path | arm64-only build |

---

## 2. Toolchain and project setup

### 2.1 Environment prerequisites (none of these exist in this analysis container)

| Item | Version | Note |
|---|---|---|
| JDK | 17 (21 works) | AGP 8.x requires ≥ 17; container already has 21 |
| Android SDK Platform | 34 | `compileSdk 34` |
| Build-Tools | 34.0.0 | |
| Platform-Tools (`adb`) | latest | required for all device gates |
| Gradle | 8.7+ (wrapper) | committed wrapper, no system Gradle |
| AGP | 8.5.x | |
| Kotlin | 2.0.x | with KSP for Room |

Phase 0 must install SDK + platform-tools and verify `adb devices` sees the box **before** any UI work.

### 2.2 Gradle configuration (agreed values)

```kotlin
// app/build.gradle.kts
android {
    namespace   = "tv.qrmzi.client"
    compileSdk  = 34

    defaultConfig {
        applicationId = "tv.qrmzi.client"
        minSdk        = 29          // Android 10, per brief
        targetSdk     = 29          // D9
        versionCode   = 1
        versionName   = "0.1.0"
    }

    androidResources { localeFilters += listOf("ar") }   // AGP 8.1+: strip unused locales
    buildFeatures { viewBinding = true }                 // no compose, no dataBinding
    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin") }
}
```

Manifest essentials:

```xml
<uses-feature android:name="android.software.leanback"     android:required="false" />
<uses-feature android:name="android.hardware.touchscreen"  android:required="false" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<application
    android:banner="@drawable/tv_banner"
    android:icon="@mipmap/ic_launcher"
    android:allowBackup="false"
    android:largeHeap="false"
    android:hardwareAccelerated="true"
    android:usesCleartextTraffic="false"
    android:networkSecurityConfig="@xml/network_security_config"
    android:theme="@style/Theme.Qrmzi">

  <activity android:name=".ui.MainActivity"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|uiMode">
    <intent-filter>
      <action android:name="android.intent.action.MAIN" />
      <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
      <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
  </activity>

  <activity android:name=".ui.player.PlayerActivity"
            android:screenOrientation="sensorLandscape"
            android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize"
            android:hardwareAccelerated="true" />
</application>
```

Both `LEANBACK_LAUNCHER` and `LAUNCHER` so the app appears on TV launchers **and** in plain launchers on
test devices. No `REQUEST_INSTALL_PACKAGES`, no storage permission, no `QUERY_ALL_PACKAGES`.

### 2.3 Dependencies (exhaustive — anything not listed is not added)

| Dependency | Why |
|---|---|
| `androidx.core:core-ktx` | KT extensions, `WindowInsets` helpers |
| `androidx.appcompat:appcompat` | `AppCompatActivity`, theme base |
| `androidx.recyclerview:recyclerview` | all grids/rows |
| `androidx.constraintlayout:constraintlayout` | detail/player overlay layouts |
| `androidx.lifecycle:{runtime,viewmodel}-ktx` | MVVM |
| `androidx.room:{runtime,ktx}` + KSP `room-compiler` | local catalogue, favourites, history |
| `androidx.datastore:datastore-preferences` | settings (filter, chain mode) |
| `androidx.webkit:webkit` | `WebViewFeature` capability checks, `WebViewCompat` |
| `com.squareup.okhttp3:okhttp` | HTTP + disk cache + `shouldInterceptRequest` hand-off |
| `org.jsoup:jsoup` | HTML parsing (required: unquoted `data-src` breaks regex) |
| `io.coil-kt:coil` | image loading with per-request sizing |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | concurrency |
| `junit4`, `okhttp-mockwebserver`, `truth` (test only) | parser/DAO contract tests |

Explicitly **not** used: Leanback, Compose, Hilt, Retrofit, Moshi/Gson, Navigation, WorkManager,
Firebase, ExoPlayer/Media3, ExoPlayer HLS extensions, any analytics SDK.

Expected APK: **6–9 MB** release, no native libs.

### 2.4 Source layout

```
app/src/main/java/tv/qrmzi/client/
├── App.kt                     Application, image loader + AppContainer init
├── AppContainer.kt            lazy singletons (db, client, repos, settings)
├── core/
│   ├── net/   UserAgent.kt  SiteClient.kt  RequestQueue.kt  Http.kt  NetErrors.kt
│   ├── parse/ CardParser.kt  HomeParser.kt  SeriesParser.kt  EpisodeParser.kt
│   │          SearchParser.kt  RestParser.kt  SitemapParser.kt  ParserVersion.kt
│   ├── model/ Card.kt  Series.kt  Episode.kt  ContentKind.kt  Ribbon.kt  Chain.kt
│   ├── db/    AppDatabase.kt  Entities.kt  Daos.kt  Converters.kt
│   ├── repo/  CatalogRepository.kt  PlayerRepository.kt  SettingsRepository.kt
│   └── util/  Arabic.kt  Urls.kt  Clock.kt  Log.kt
├── ui/
│   ├── MainActivity.kt  Navigator.kt  FocusMemory.kt
│   ├── common/ TvCardView.kt  TvRow.kt  TvGrid.kt  FocusScale.kt  EmptyState.kt
│   │          ErrorState.kt  LoadingRow.kt  Banner.kt
│   ├── home/  Latest/  catalog/  search/  detail/  favorites/  history/  settings/
│   └── player/ PlayerActivity.kt  ChainResolver.kt  PlayerCss.kt  AdFilter.kt
│               PlayerWebViewClient.kt  PlayerWebChromeClient.kt
└── res/  layout/ drawable/ values/ values-ar? (see §2.5) xml/ (network config, locator)
```

### 2.5 Localisation policy

* **Default resources are Arabic** (`res/values/strings.xml` written in Arabic). Cheap TV boxes often ship
  with an English or Chinese system locale; the app must still be Arabic without depending on the device
  language. No `values-en` unless requested.
* `android:supportsRtl="true"`, `android:layoutDirection="rtl"` on the root of every layout.
* Numerals stay **Latin** (the site uses Latin digits in episode badges).
* Font: system sans (Roboto/Noto Naskh) — *not* the site's Kirmizi webfont, which is site-licensed.
  Bundle nothing; fallback chain `sans-serif` → `sans-serif-medium` for headings.

---

## 3. Data layer

### 3.1 SiteClient contract

```kotlin
class SiteClient(private val ok: OkHttpClient, private val clock: Clock) {
    suspend fun html(url: String, policy: CachePolicy): Result<Fetched>   // GET, HTML
    suspend fun json(url: String, policy: CachePolicy): Result<Fetched>   // GET, REST
}
data class Fetched(val body: String, val finalUrl: String, val fromCache: Boolean, val fetchedAt: Long)
```

Rules:

1. **Single-flight per URL** — a `Mutex` map keyed by URL; a second caller joins the in-flight request.
2. **Global concurrency 2**, plus a minimum 300 ms gap per host (Cloudflare-friendly, per
   `QRMZI_ANALYSIS.md` §18.4).
3. **No bursts**: screens request only what they display; no background crawl of 921 episodes.
4. Follow redirects, store the **final URL** (a `/series/…-v1/` redirect was observed).
5. Retry only on `IOException`/`5xx`/`429`: 3 attempts, backoff 1 s → 2 s → 4 s, honour `Retry-After`.
6. `User-Agent`: one constant modern Android WebView string used by **both** the client and the player
   WebView (the site only branches on `themeMobile`, verified — a stable UA keeps behaviour reproducible).
7. Send `Accept-Language: ar`, `Accept-Encoding: gzip`. Never send `Referer` (not needed; the player host
   returns 200 without it).
8. Response size guard: reject > 2 MB bodies (defensive).
9. Never request `.m3u8`, `.ts`, `.m4s`, `.mp4`, `/srt/`, or any provider media URL. The parser stops at
   the **embed page** URL. This is enforced by an allowlist of path prefixes *and* a host+extension guard
   in `SiteClient` (defence in depth, and it documents the intent in code review).

### 3.2 Cache policy

| Data | TTL (fresh) | Stale-while-revalidate | Notes |
|---|---|---|---|
| Feed page 1 | 3 min | serve stale up to 24 h + refresh in background | home screen |
| Feed page N>1 | 15 min | 24 h | "latest" grid |
| Series detail | 6 h | 7 d | revalidate on explicit open |
| Episode detail | 30 min | 7 d | description + tabs |
| Player chain | 6 h | re-resolve on any player failure | `player_url` + `resolved_at` |
| Search result | session only (memory) | — | plus `search_cache` table for 24 h |
| Series sitemap | 24 h | 7 d | bootstrap index of 191 series |
| REST inventory totals | 6 h | — | telemetry/`x-wp-total` display only |
| Images | HTTP `max-age=5184000` (60 d) | — | Coil disk cache |

Two tiers: an in-memory parsed-object LRU (`LruCache<String, Any>`, ~2 MB, cleared on trim) and the
SQLite catalogue. HTML has **no ETag/Last-Modified** (Cloudflare `DYNAMIC`), so TTL — not conditional
requests — is the primary mechanism.

### 3.3 Room schema (v1)

```sql
CREATE TABLE series (
  url         TEXT PRIMARY KEY,     -- absolute, verbatim slug
  title       TEXT NOT NULL,
  synopsis    TEXT,
  cover_url   TEXT,                 -- 1280x720 when available
  poster_url  TEXT,                 -- 349x520 from a card, fallback
  episode_count INTEGER,
  fetched_at  INTEGER NOT NULL
);

CREATE TABLE episodes (
  url          TEXT PRIMARY KEY,
  series_url   TEXT,                -- NULL for movies
  kind         TEXT NOT NULL,       -- EPISODE | MOVIE
  title        TEXT NOT NULL,
  number       INTEGER,             -- Latin digits, NULL if unknown
  image_url    TEXT,                -- landscape when known
  poster_url   TEXT,
  ribbon       TEXT,                -- الحلقة الأخيرة | مؤجلة | نهاية الموسم | NULL
  description  TEXT,                -- .episode-desc, plain text
  player_url   TEXT,                -- deepest resolved embed URL (NOT a stream URL)
  chain_host   TEXT,
  resolved_at  INTEGER,
  published_at INTEGER,
  fetched_at   INTEGER NOT NULL,
  FOREIGN KEY (series_url) REFERENCES series(url) ON DELETE SET NULL
);
CREATE INDEX idx_ep_series ON episodes(series_url, number DESC);
CREATE INDEX idx_ep_kind   ON episodes(kind, fetched_at DESC);

CREATE TABLE feed_entry (           -- homepage / latest ordering snapshot
  page      INTEGER NOT NULL,
  position  INTEGER NOT NULL,
  url       TEXT NOT NULL,
  title     TEXT NOT NULL,
  kind      TEXT NOT NULL,
  image_url TEXT,
  poster_url TEXT,
  number    INTEGER,
  ribbon    TEXT,
  series_url TEXT,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (page, position)
);

CREATE TABLE favorites (
  url TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL,
  image_url TEXT, series_url TEXT, added_at INTEGER NOT NULL
);

CREATE TABLE history (
  url TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL,
  image_url TEXT, series_url TEXT, number INTEGER,
  last_opened_at INTEGER NOT NULL,
  playback_ms INTEGER DEFAULT 0,    -- best-effort from WebView localStorage after playback
  duration_ms INTEGER DEFAULT 0
);
CREATE INDEX idx_hist_recent ON history(last_opened_at DESC);

CREATE TABLE search_cache (
  query TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched_at INTEGER NOT NULL
);

CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
```

Size estimate: 921 episodes + 49 movies + 191 series + 970 feed rows ≈ **< 1 MB** SQLite. Negligible for
1 GB; the cost of this cache is far lower than re-fetching HTML.

> **As shipped** the persistence layer is smaller than this sketch: framework
> SQLite (no Room), tables `favorites` / `history` / `searches`, and column names
> as listed in `ARCHITECTURE.md` §6.1. `ribbon` is present on **both** card
> tables there — it is also the column whose absence broke upgrades to v1.1, so
> `Store.onOpen` now heals it (see `BUILD_AND_VERIFICATION.md` §5.1). The filter
> preference is `true` (on) by default, and the settings keys are the
> `Prefs` properties, not DataStore.

DAO notes:

* `feed_entry` is fully replaced per page inside a single transaction (`DELETE WHERE page=?` then insert).
* `episodes` / `series` use `@Upsert` (Room 2.5+) so a refresh never duplicates.
* Favourites/history store a **copy** of the display fields so those screens work offline.
* **No foreign-key cascade to `episodes` when a series refresh removes an episode** — deleted episodes are
  tombstoned by absence from `feed_entry`, not deleted from `episodes` (history/favourites may point at them).

### 3.4 Settings (DataStore Preferences)

| Key | Default | Values |
|---|---|---|
| `filter.enabled` | `false` | boolean |
| `filter.block_hosts` | built-in list (§6.2) | string set, user editable |
| `filter.allow_hosts` | empty | string set |
| `filter.auto_disabled_for` | empty | string set (hosts where filtering broke playback) |
| `player.chain_mode` | `page` | `page` (qrmzi page + CSS) · `direct` (deepest embed as top document) |
| `player.last_chain_warning_at` | 0 | long |
| `ui.grid_columns_home` | 5 | 4..6 |
| `ui.reduce_motion` | false | boolean (disables scale animation) |
| `data.image_quality` | `sd` | `sd` (470 px) · `hd` (1280 px covers only) |
| `privacy.clear_on_exit` | false | boolean (cookies + cache on player exit) |

`SettingsRepository` exposes a `Flow<Settings>`; the player reads a snapshot at start (no live re-config
mid-playback).

---

## 4. Parsing layer

Parsers are pure functions: `(html, baseUrl) -> Model`. No Android types, no coroutines → fully JVM-testable.

| Parser | Selector scope | Returns |
|---|---|---|
| `HomeParser` | `#load-post > article.post` (fallback: `main .sec-line article.post`) | `List<Card>` + `hasPagination` |
| `SearchParser` | `main .sec-line article.post` | `List<Card>` (series/movies only) |
| `SeriesCatalogParser` | `main article.postEp` + `div.pagination` | `List<Card>`, `currentPage`, `lastPage` |
| `SeriesParser` | `.singleSeries` header + `article.postEp` grid + optional `.ribbon` | `Series` + `List<Card>` (episodes) |
| `EpisodeParser` | `.getEmbed iframe[src]`, `.singleInfo h1`, `.singleSeries .info h2 a`, `.episode-desc`, `.series-story`, `#episodes-tab article.postEp`, `#relevants-tab`, `#new-tab` | `EpisodeDetail` |
| `MovieParser` | `.singleSeries` header + `.getEmbed iframe[src]` | `MovieDetail` |
| `RestParser` | `/wp-json/wp/v2/{episodes,movies}` items | `Card` (+ `date`) |
| `SitemapParser` | `series-sitemap.xml` `<loc>` | `List<String>` |

Non-negotiable parser rules (all from `WEBSITE_STRUCTURE.md` §15):

1. Jsoup only — `data-src` is **sometimes unquoted**; regexes fail.
2. Image = `img[data-src]`, never `src` (placeholder/base64).
3. Branch on `href` path (`/episode/`, `/series/`, `/movies/`), never on the article class.
4. Never assume a fixed card count; never treat HTTP 200 as "page exists" (empty archives return 200).
5. Never decode/re-encode a slug.
6. Navigation between episodes comes from `#episodes-tab` list adjacency, not `N ± 1`.
7. Do not invent seasons, genres, ratings, cast, or years — the markup has none.
8. `ParserVersion` constant is stored with cached rows; a parser change bumps the version and invalidates
   the parsed cache (cheap: the site is small).

---

## 5. Screens, state and D-pad contract

### 5.1 Behaviour matrix

| Screen | Data source | Key | Offline | Initial focus | BACK goes to |
|---|---|---|---|---|---|
| Home | feed p1 + catalogue + history/favorites | — | cached rows + banner | first card of row 1 | exit (double-BACK confirm) |
| Latest | feed p1..pN, paged | `/page/N/` | cached pages | first card | Home |
| Series catalogue | `/all-turkish-series/page/N/` | — | cached | first card | Home |
| Movies | `/all-turkish-movies/` (+ feed kind=MOVIE) | — | cached | first card | Home |
| Search | `?s=` + REST `/episodes?search=` | `s` | local title index | search field | Home |
| Series detail | `/series/<slug>/` | url | cached header + episodes | "تشغيل" CTA → grid | previous |
| Episode detail | `/episode/<slug>/` | url | cached + "player needs network" | "تشغيل" CTA | Series detail (or Home if unknown) |
| Player | chain resolution | url | — | native overlay | Episode detail |
| Favorites | Room | — | yes | first card | Home |
| History | Room | — | yes | first card | Home |
| Settings | DataStore | — | yes | first item | Home |

### 5.2 Home composition (derived, not invented)

All rows come from real site data; the grouping is app-side organisation of the **same** feed/catalogue
(`QRMZI_ANALYSIS.md` §6 — the site has no sections, no slider, no curated hero):

1. **متابعة المشاهدة** — from `history` (only if non-empty; max 10).
2. **أحدث الحلقات** — `feed_entry` page 1, newest-first, mixed episode+movie, max 20.
3. **مسلسلات** — portrait/landscape series cards from the catalogue cache (max 20).
4. **أفلام** — `kind = MOVIE` (49 available, max 20).
5. **المفضلة** — from `favorites` (only if non-empty).
6. **تصفح المزيد** — last row: "كل المسلسلات" / "كل الأفلام" tiles → catalogue screens.

If a row has no data it is **not rendered** (no empty shelves). No fabricated content, no placeholder
titles, no "trending".

### 5.3 Focus system (the part that decides whether the app is usable)

| Concern | Implementation |
|---|---|
| Focusable card | `TvCardView` = `FrameLayout` with `focusable=true`, `focusableInTouchMode=false`, ripple-less custom selector |
| Focus visual | scale 1.00 → 1.08 (`reduce_motion` → 1.03), 180 ms `DecelerateInterpolator`, red 2 dp border + 8 dp elevation |
| Grid | `RecyclerView` + `GridLayoutManager`; `onInterceptFocusSearch` handles row-jump when the next row is a different section |
| Row | `RecyclerView` (horizontal) with `setHasFixedSize(true)`, `itemViewCacheSize(6)`, `RecyclerView.smoothScrollToPosition` on focus |
| Initial focus | explicit per screen (`FocusMemory.request(viewId)`) — never rely on the system default |
| Restore | `FocusMemory` (a small object keyed by screen tag) survives `onSaveInstanceState`/process death via a `Bundle` |
| WebView boundary | the only screen where focus leaves the native tree; on return, re-request focus explicitly |
| Key handling | `dispatchKeyEvent` in `MainActivity` for MENU (app menu) and BACK (double-exit confirmation); everything else flows to the view tree |
| Dead ends | every screen must pass "walk all four directions from every item; focus always lands somewhere" |

TV-specific pitfalls to code against: `RecyclerView` losing focus on data change, `descendantFocusability`
misuse, focus jumping to the first item after `notifyDataSetChanged`, and `smoothScrollToPosition` running
after the fragment is detached.

### 5.4 Loading / empty / error states

| Situation | UI |
|---|---|
| First load | skeleton cards (grey rectangles, same size) — never a spinner-only screen |
| Cached + refreshing | content shown immediately, thin progress bar under the header |
| Empty (end of list / no results) | Arabic text: `لا توجد نتائج` / `لا توجد حلقات` |
| Offline with cache | banner `لا يوجد اتصال — يتم عرض محتوى محفوظ` |
| Offline without cache | full error state + `إعادة المحاولة` |
| Parser failed (site changed) | `تعذر قراءة الصفحة` + `فتح الموقع` + a debug-only "report" that logs the parser version (no URL tokens) |

---

## 6. Player integration

### 6.1 Chain resolution (bounded, cached, honest)

```kotlin
suspend fun resolve(episodeUrl: String): Chain {
    // hop 0 is always the qrmzi episode page (it is the page the user chose)
    var current = client.html(episodeUrl, CachePolicy.EPISODE).getOrThrow()
    val hops = mutableListOf<Hop>()
    repeat(3) {                                  // hard cap: 3 hops, per WEBSITE_STRUCTURE.md §4.7
        val next = EpisodeParser.childIframe(current.body, current.finalUrl) ?: return Chain(hops, current.finalUrl)
        if (!Hosts.isPlayerHost(next)) return Chain(hops, current.finalUrl, untrusted = next)
        hops += Hop(next, current.finalUrl)
        if (Hosts.isDeepestPlayer(next)) return Chain(hops, next)   // cdnplus.sbs/embed-*.html
        current = client.html(next, CachePolicy.PLAYER).getOrThrow()
    }
    return Chain(hops, current.finalUrl)         // resolved as far as allowed
}
```

* Store `player_url`, `chain_host`, `resolved_at` on the episode row; reuse for 6 h.
* On any playback failure, drop the cached chain and resolve once more (never loop).
* **Unknown host in the chain → stop.** Show the host name and offer "open externally". Do not auto-trust
  new origins and do not add them to the allowlist at runtime.
* The resolved URL is an **embed page**, never a media playlist. `SiteClient` refuses media extensions
  (§3.1.9) so this cannot be violated by accident.

### 6.2 WebView setup (single instance, per Activity)

Full settings list: `WEBVIEW_COMPATIBILITY.md` §8.1. Additional operational points:

| Item | Value |
|---|---|
| Creation | on `PlayerActivity.onCreate`, with `applicationContext`-independent Activity context, `isSaveEnabled = false` |
| First load | `loadUrl(chain.deepest)` in `page` mode: `loadUrl(episodeUrl)` + inject `PlayerCss` on main-frame `onPageFinished` |
| Injection | only **top document** (`onPageFinished` fires for every frame → check `view.url == mainFrameUrl`) |
| Focus priming | `document.querySelector('.getEmbed iframe')?.focus()` (same-origin top document only) |
| Fullscreen | `WebChromeClient.onShowCustomView/onHideCustomView` → `FrameLayout` swap + immersive flags |
| Window suppression | `setSupportMultipleWindows(false)`, `onCreateWindow → false`, `shouldOverrideUrlLoading` external for everything else |
| Media playback | `mediaPlaybackRequiresUserGesture = true` (no autoplay is configured — verified) |
| Cookies | `setAcceptThirdPartyCookies(wv, true)` for the player only, `flush()` on `onStop` |
| Cache | WebView HTTP cache on (`LOAD_DEFAULT`); cleared by Settings only |
| Teardown | `onPause/onStop`: `webView.onPause()`, `pauseTimers()`; `onDestroy`: remove from parent, `stopLoading()`, `loadUrl("about:blank")`, `clearHistory()`, `destroy()` |
| Renderer death | `onRenderProcessGone` → finish player + snackbar "أعد المحاولة" (do **not** rebuild silently; the surface is gone) |
| Key routing | `dispatchKeyEvent`: try WebView; on `MEDIA_*` keys always dispatch synthetically; native overlay for BACK/Reload/Fullscreen |

### 6.3 Native player overlay

A translucent, auto-hiding (3 s) bar at the top with: episode title, `إعادة التحميل`, `ملء الشاشة`,
`فتح في المتصفح`. This guarantees that **some** usable control is always reachable with the D-pad even if
the provider's iframe never takes focus (`WEBVIEW_COMPATIBILITY.md` §6.4 mitigation 2), and it is the
"report the limitation" surface if playback cannot be controlled.

### 6.4 Failure ladder (product behaviour, no circumvention)

1. Player page loads, video plays → normal.
2. Video blocked / no media requests within 15 s → "لم يبدأ التشغيل" + `إيقاف الفلترة وإعادة المحاولة`
   (auto-disables filtering for that host) + `فتح في المتصفح`.
3. "Disable ADBlock" overlay detected (text probe in top document only, `#adbd` visible) → same prompt,
   filtering disabled permanently for that host.
4. Play-limit / premium box → explain: *هذا حد من مزوّد المشاهدة* + `فتح في المتصفح`. Never patched.
5. Chain changed / unknown host → show host + `فتح في المتصفح`.
6. WebView missing or < Chromium 55 → blocking screen with instructions (app cannot play).

---

## 7. Security

| Control | Implementation |
|---|---|
| Cleartext | `usesCleartextTraffic=false` + `networkSecurityConfig` deny-all-cleartext except none |
| TLS | verification on; **no** `onReceivedSslError` override; no certificate pinning (Cloudflare rotates) |
| Navigation allowlist | `www.qrmzi.tv`, `qrmzi.tv`, `w.anaplayer.online`, `shooof.online`, `cdnplus.sbs` — exact host match, no wildcards, no `http` |
| External links | share/social/premium/`mailto:` → `Intent.ACTION_VIEW` after `resolveActivity`; blocked with a toast if no handler |
| JS bridge | **none** (`addJavascriptInterface` never called) |
| Script injection | only same-origin top document (CSS + focus call); never into cross-origin frames |
| File access | `allowFileAccess=false`, `allowContentAccess=false`, `allowFileAccessFromFileURLs=false`, `allowUniversalAccessFromFileURLs=false` |
| Popups | multiple windows off, `onCreateWindow → false` |
| Cookies | third-party only in the player WebView; `clearOnExit` option; never sent to the API client |
| Storage | no external storage, no media download, `allowBackup=false`, no `getExternalFilesDir` usage |
| Logging | release build logs nothing above `W`; URLs are logged with query strings stripped and no cookies |
| Rate/IP hygiene | §3.1 rules; the app must never look like a scraper (no page enumeration beyond what the user scrolls) |
| Data minimisation | no account, no device id, no analytics, no crash-reporting SDK |

---

## 8. Optional ad filter (safe by construction)

> **Status note (Phase 7):** this section was the design sketch; the shipped
> implementation is described in `WEBVIEW_COMPATIBILITY.md` §9 and
> `ARCHITECTURE.md` §6.2. Two points changed after testing the real embed chain:
> `tagivi.com`/`girellaharelip.com` are now **essential** (the player uses them),
> and there is no extension sniffer — the blocklist itself contains no media
> hosts, so no path/extension logic is needed.

Implemented in `PlayerWebViewClient.shouldInterceptRequest`:

* **URL host match only**, no regex on paths; the main frame is never touched.
* **Hard allowlist (evaluated first, cannot be overridden by the user list):**
  `Http.isTrustedHost` (Qrmzi + provider chain) plus `Filter.ESSENTIAL`
  (JW Player CDNs, common JS CDNs, the player's own tag hosts).
* **Default block list:** ≈55 known popunder/display/analytics hosts
  (`pgo99.com`, `cdn.foxpush.net`, `popads.net`, `doubleclick.net`,
  `googletagmanager.com`, `google-analytics.com`, …). No media/CDN host is on it.
* **Precedence:** essential/trusted → explicit `allow` → first `block`.
* **Self-disabling and remembered:** only when a load fails *after* the filter
  refused at least one sub-resource during that load; it then retries once
  without the filter.
* UI wording: «تقليل الإعلانات من شبكات معروفة» — never "إزالة الإعلانات".
* The list is visible, extensible and disableable in Settings, and a
  `qrmzi-filter.txt` file lets a user test `allow`/`block` rules from a PC
  without an app update.

---

## 9. Memory plan (1 GB device)

| Screen | Budget (PSS) | Mechanism |
|---|---|---|
| App baseline (cold start, Home) | ≤ 90 MB | no Leanback/Compose, minimal theme, 1 Activity |
| Browsing (10 min, all screens) | ≤ 160 MB | image cache 12 MB, parsed LRU 2 MB, DB 1 MB |
| Player screen (peak) | ≤ 380 MB | one WebView, browsing views detached, images released |
| Worst case (player + renderer + ads) | ≤ 450 MB | accepted on 1 GB only because the native UI is idle behind the player |

Mandatory measures:

* `Bitmap.Config.RGB_565` for card thumbnails (`Coil` `precision(PIXEL_INEXACT)`), decode to the row height,
  not the source size.
* Coil: memory cache 12 MB (`0.06 × memoryClass`, capped), disk cache 96 MB, `bitmapConfig(RGB_565)`,
  `allowHardware(true)`.
* `onTrimMemory(RUNNING_LOW/UI_HIDDEN)` → clear Coil memory cache, drop parsed LRU, trim Room.
* `onLowMemory()` → additionally clear the feed image cache.
* RecyclerViews: `setHasFixedSize(true)`, `itemViewCacheSize(6)`, `recycledViewPool` shared between rows.
* Player Activity finishes on BACK; no WebView is ever kept in the back stack.
* No `WorkManager`, no services, no polling; the only background work is the in-flight HTTP call of the
  visible screen.
* No `largeHeap`, no `android:process` split (adds duplication, not savings).

---

## 10. Phased delivery

Every phase ends with: **build → test → device check → document**. Do not start a phase while a previous
phase has an open critical defect.

### Phase 0 — Toolchain and device truth (1–2 d)

* Install SDK 34 + build-tools + platform-tools; commit Gradle wrapper.
* Build a hello-world APK and install it on the TX1-S.
* Record: `dumpsys webviewupdate`, `getprop ro.product.cpu.abilist`, `dumpsys media.player` codecs,
  free RAM (`dumpsys meminfo`), Android WebView version.
* Run the D-pad probe on a trivial page with 2 nested iframes.

**Exit criteria:** APK installs and launches from the TV launcher; WebView version recorded;
`WEBVIEW_COMPATIBILITY.md` §12 test 1 answered; ABI confirmed (expected: `arm64-v8a`, irrelevant to the
build because there are no native libs).

### Phase 1 — Skeleton, theme, focus primitives (2–3 d)

* Theme: dark `#0B0B0C`, surface `#161618`, accent `#A50F2E`, text `#F2F2F3`, RTL.
* `MainActivity` + fragment host + `Navigator` + BACK stack + double-BACK exit.
* `TvCardView`, `TvRow`, `TvGrid`, focus scale, focus memory.
* Stub screens for all 9 destinations with fake items.

**Exit criteria:** every screen reachable by D-pad only; focus visible on every item; BACK restores the
previously focused card; cold start < 2 s; no ANR while spamming keys 100×.

### Phase 2 — Network + parsers + fixtures (3–4 d)

* `SiteClient`, `Hosts`, error model.
* All parsers + `ParserVersion`.
* Fixture set (10 files, ~2.5 MB) + JVM tests asserting the counts in `WEBSITE_STRUCTURE.md` §16.

**Exit criteria:** unit tests green on fixtures; a manual live run of each parser prints the expected
structure (50 / 40 / 41 / 23 / 191 / 921 / 49); parsers never touch media URLs (grep test for
`.m3u8|.ts|.mp4` = 0 hits in `parse/`).

### Phase 3 — Persistence + repositories (2–3 d)

* Room v1 + DAOs + `@Upsert` + transactioned feed replace.
* `CatalogRepository` with the TTL matrix; DataStore settings.
* Offline behaviour: serve cache, banner, retry.

**Exit criteria:** kill network → all previously visited screens still render; relaunch offline shows
Home from cache; DB size < 2 MB after 50 pages visited; DAO tests pass; no main-thread I/O
(`StrictMode` in debug, zero violations).

### Phase 4 — Browsing UI complete (4–5 d)

Home, Latest (paged), Series catalogue (paged), Movies, Search (dual-source), Series detail, Episode
detail, Favorites, History, Settings persistence.

**Exit criteria:** all 11 destinations use real data (the 9 brief screens plus the series and movies
catalogues); D-pad traversal test passes on every screen (all four
directions from every item); search `حب` returns 18 series + 5 movies and episodes from REST; paging stops
at the first empty page; focus survives data updates and configuration changes; browsing PSS ≤ 160 MB.

### Phase 5 — Player (3–5 d) — **gated**

Gate: Phase 0's WebView answers + `WEBVIEW_COMPATIBILITY.md` §12 tests 1–7 must pass before this phase is
considered complete.

* `ChainResolver`, `PlayerActivity`, `PlayerCss`, overlay, fullscreen, cookies, renderer-death handling.
* Failure ladder (§6.4) including the "filtering off" retry.

**Exit criteria:** tests 1–11 and 13–16 of `WEBVIEW_COMPATIBILITY.md` §12 pass; BACK from fullscreen →
player → episode → list with no stuck surface or leaked audio; 60 s of 720p playback with no frame drop
spike and no crash; player PSS ≤ 380 MB.

### Phase 6 — Resume & continue-watching (2 d)

* Read playback position from the player's `localStorage` **only if** it is reachable (same-origin top
  document in `direct` mode; otherwise no position).
* `history` ordering, "متابعة المشاهدة" row, per-episode progress badge where available.
* Explicit non-goal stated in UI: progress is best-effort because it lives on the provider's page.

**Exit criteria:** history row appears after watching; clearing history empties the row; no position is
invented when unavailable (no fake progress bars).

### Phase 7 — Ad filter + settings hardening (2 d)

* `AdFilter`, allowlist guard, watchdog, auto-disable, per-host memory.
* Settings screen: filter toggle, editable lists, chain mode, clear player data, reduce motion.

**Exit criteria:** filtering on → no "Disable ADBlock" overlay on 5 sampled episodes; blocking the chain
origin is impossible from the UI; disabling filtering restores playback within one reload.

### Phase 8 — Hardening and release (3–4 d)

* R8 rules, resource shrinking, APK size check, ProGuard keep rules for Jsoup/Room/Coil.
* Memory profiling with `dumpsys meminfo` on all screens; `am send-trim-memory` behaviour.
* RTL polish pass, long-title truncation, Arabic typography at 1080p and 720p output.
* Accessibility: content descriptions on cards, focus order sanity, contrast check.
* Regression run of the full device test list; write `DEVICE_TEST_REPORT.md`; tag `v1.0.0`.

**Exit criteria:** release APK installs on a clean TX1-S, survives 30 min of mixed use with no crash/ANR,
cold start < 2 s, browsing ≤ 160 MB, player ≤ 380 MB, all docs updated.

**Total:** ~22–30 working days after Phase 0's prerequisites are available.

---

## 11. Test strategy

| Layer | Tool | What |
|---|---|---|
| Parsers | JUnit + fixture HTML | counts, field extraction, quirk regressions (unquoted `data-src`, empty page, movie card in feed) |
| Networking | `MockWebServer` | redirects, 429 + `Retry-After`, 5xx retry cap, single-flight, media-extension refusal |
| Room | instrumented (or Robolectric) | upsert idempotency, feed replace transaction, history ordering, migration path |
| ViewModels | coroutine test dispatcher | state machine: loading → content / empty / error / stale |
| UI focus | manual scripted ADB | `input keyevent DPAD_*` walkthrough, screenshot per step |
| Player | device | `WEBVIEW_COMPATIBILITY.md` §12 (16 checks) |
| Memory | `dumpsys meminfo`, `am send-trim-memory` | budgets in §9 |
| Regression | fixture suite + live smoke | fail the build if a parser contract breaks |

`adb` key-walk script (committed as `tools/dpad-walk.sh`):

```bash
#!/usr/bin/env bash
# Walks every direction from the current focus and captures a screenshot per key.
for k in DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_LEFT DPAD_UP DPAD_CENTER BACK; do
  adb shell input keyevent "KEYCODE_$k"; sleep 0.4
  adb exec-out screencap -p > "artifacts/$(date +%s)-$k.png"
done
```

---

## 12. Risk register

| # | Risk | L | I | Mitigation | Trigger to act |
|---|---|---|---|---|---|
| R1 | D-pad cannot control the player iframe | M | **H** | native overlay controls, synthetic key dispatch, `direct` chain mode, external fallback | §12 test 5 fails |
| R2 | Native HLS not supported by the box's decoder | L | **H** | verify in Phase 0; fallback = external browser; no stream extraction ever | §12 test 2 fails |
| R3 | WebView too old (< Chromium 55) | M | **H** | blocking update screen; document instructions | Phase 0 `dumpsys` |
| R4 | Ad-block overlay / play-limit gate | M | M | allowlist `pop.js`, watchdog, auto-disable, explain limits honestly | overlay appears with filter on |
| R5 | Provider changes the iframe chain or adds a new host | M | M | ≤3-hop resolver, unknown host → stop + external fallback, re-resolve on failure, 6 h cache | play fails while site works in a browser |
| R6 | Site HTML/theme change breaks parsers | M | M | fixture tests, `ParserVersion` invalidation, error state with "open website" | parser test or live smoke fails |
| R7 | 1 GB RAM: renderer kill during playback | M | M | `onRenderProcessGone` handling, one WebView, trimmed images, explicit budgets | crash log / meminfo |
| R8 | Cloudflare rate limiting / blocking | L | M | concurrency 2, 300 ms/host, TTL cache, no crawling | 429/403 responses |
| R9 | Brief creep toward "download / external player" | M | **H** | written non-goals (§14), review gate on any ExoPlayer/FFmpeg dependency | new dependency request |
| R10 | Arabic text truncation / RTL layout bugs on cheap launchers | M | L | default-Arabic resources, layout direction forced, tested at 720p and 1080p | visual QA |

L/I = likelihood/impact.

---

## 13. Open spikes (time-boxed, before or during the phase noted)

| # | Spike | Time-box | Exit criteria | Phase |
|---|---|---|---|---|
| S1 | TX1-S WebView version + HLS decode | 0.5 d | version string + 60 s of playback | 0 |
| S2 | D-pad focus into 2 nested iframes | 0.5 d | play/pause reachable, or documented failure | 0 |
| S3 | Fullscreen via `onShowCustomView` vs `webkitEnterFullscreen` | 0.5 d | one path works, BACK exits | 5 |
| S4 | `direct` chain mode viability (deepest embed as top document) | 0.5 d | video plays without qrmzi chrome | 5 |
| S5 | Playback position availability (`localStorage`) | 0.25 d | readable or explicitly unavailable | 6 |
| S6 | Filtering + `pop.js` interaction on 5 episodes | 0.5 d | no overlay, playback OK | 7 |
| S7 | Parser fixture harvest + legal review of committed fixtures | 0.5 d | 10 fixtures, no media URLs, size < 3 MB | 2 |

---

## 14. Explicit non-goals

1. No media extraction: no `.m3u8`, `.ts`, `.m4s`, `.mp4` fetching, parsing, proxying, caching, or playing
   outside the provider's own page. No ExoPlayer/Media3/FFmpeg dependency will be added.
2. No DRM, paywall, login, geo, play-limit, countdown, or anti-bot circumvention.
3. No ad-free promise; only a user-controlled, reversible, host-based filter with an auto-off safety net.
4. No separate content catalogue, no invented seasons, genres, ratings, cast, years, or "trending" data.
5. No bulk scraping: the app fetches only what the user is looking at, plus a 24 h sitemap refresh.
6. No downloads, no offline video, no casting, no background sync, no notifications service.
7. No account system and no server-side component of our own.

---

## 15. Definition of done (final deliverable)

- [ ] Release APK installs and launches on the Mortal TX1-S from the TV launcher.
- [ ] All 11 destinations usable with the remote only (the 9 brief screens + series/movies catalogues);
      every interactive element reachable; focus always visible.
- [ ] Real Qrmzi data everywhere (no hardcoded lists); offline shows cached content with a clear banner.
- [ ] Playback works, or the exact technical reason and the documented fallback are reported in
      `DEVICE_TEST_REPORT.md` (`WEBVIEW_COMPATIBILITY.md` §14 requires a written report, not a workaround).
- [ ] BACK behaviour correct across player → fullscreen → detail → list.
- [ ] Browsing ≤ 160 MB PSS, player ≤ 380 MB PSS, cold start < 2 s, no ANR, no leak across 30 min.
- [ ] Security allowlist enforced; no JS bridge; TLS untouched; external links leave the app.
- [ ] Filter defaults off, is reversible, and cannot break the player permanently.
- [ ] `QRMZI_ANALYSIS.md`, `WEBSITE_STRUCTURE.md`, `WEBVIEW_COMPATIBILITY.md`, `INTEGRATION_PLAN.md`
      updated to the shipped state; `DEVICE_TEST_REPORT.md` attached.

---

## Appendix A — Endpoint summary used by the app

| Purpose | Request | Frequency |
|---|---|---|
| Home / latest | `GET /` , `GET /page/N/` | on screen entry, TTL 3/15 min |
| Series catalogue | `GET /all-turkish-series/page/N/` | on entry, TTL 6 h |
| Movies catalogue | `GET /all-turkish-movies/` | on entry, TTL 6 h |
| Series detail | `GET /series/<slug>/` | on open, TTL 6 h |
| Episode detail | `GET /episode/<slug>/` | on open, TTL 30 min |
| Movie detail | `GET /movies/<slug>/` | on open, TTL 6 h |
| Search (series/movies) | `GET /?s=<q>` | per query (debounced 350 ms) |
| Search (episodes) | `GET /wp-json/wp/v2/episodes?search=<q>&per_page=50` | per query |
| Series index bootstrap | `GET /series-sitemap.xml` | 24 h |
| Inventory totals | `GET /wp-json/wp/v2/{episodes,movies}?per_page=1` (headers only) | 6 h, optional |
| Player chain | `GET /episode/<slug>/` → child iframes (≤3) | 6 h cache |

Not used by the app: `/news/`, `/feed/` (optional future 15-min poll), `/wp-json/wp/v2/media`,
`/wp-json/meta/watch-settings/1` (documented risk only, never fetched to alter behaviour).

## Appendix B — Snapshot baseline for regression

| Metric | Value on 6 Nov 2026 |
|---|---|
| Episodes | 921 |
| Movies | 49 |
| Series | 191 |
| Feed page size | 50 (19 full pages + 20) |
| Series page size | 40 (5 pages, last = 31) |
| Search `حب` | 23 cards (18 series + 5 movies) |
| Search `الخليفة` | 1 series, 41 episodes via REST |
| Series page (191-episode show) | 37 cards, numbers 155–191 |
| Deepest player | JW Player 8.34.5, HLS, no DRM |
| WebView minimum | Chromium 55 (88 recommended) |
