# QRMZI TV — Technical Analysis

**Analysis date:** 6 November 2026
**Target device:** Mortal TX1-S — Android 10 (API 29), ~1 GB RAM, TV remote only
**Scope:** feasibility and integration analysis only. **No application code was written.**

---

## 1. Executive summary

Qrmzi TV (`https://www.qrmzi.tv/`) is a **WordPress site** (custom theme `qrmzi`) that publishes
Turkish drama episodes with Arabic titles/descriptions and embeds a third-party video player.
It is an anonymous, no-login, no-paywall site.

**Bottom line for the Android TV client:**

| Question | Finding |
|---|---|
| Can the catalogue be read without a browser? | **Yes.** Episode and movie lists are available from the public WordPress REST API (`/wp-json/wp/v2/episodes`, `/movies`). Series (191 items) are **not** exposed through REST and must be read from HTML or the series sitemap. |
| Can the UI be native? | **Yes, and it should be.** The site's markup is stable and easy to parse; a native D-pad UI will be far faster and lighter than a WebView on 1 GB RAM. |
| Can video play in a WebView? | **Very likely yes, but not verifiable in this environment.** The video is a JW Player 8.34.5 HLS player inside 2–3 nested iframes, with no DRM and no `X-Frame-Options`/CSP. It relies on the platform H.264/AAC decoder (`androidhls:"true"`). |
| Is there any public video API? | **No.** There is a single embedded iframe per episode. The media file itself was deliberately **not** extracted or recorded. |
| Biggest technical risk | **Remote-control control of the player.** The player is inside cross-origin iframes, so native code cannot reach its DOM. D-pad → iframe → JW Player focus routing must be verified on the TX1-S. |
| Second risk | **Ads / overlays** in the player page. A naive request-blocker can trigger the "Disable ADBlock plugin" overlay because the page checks a flag set by its own ad script. |

**Recommended architecture in one line:** native Kotlin/AndroidX UI + Room + REST/HTML data layer,
with a **single dedicated WebView used only for playback** (and an optional "open full website" escape hatch).

---

## 2. Method and evidence

### 2.1 What was done

* Live HTTPS requests with `curl` against the production site, using:
  * Android mobile Chrome UA,
  * Android **WebView** UA (`...; wv ... Version/4.0 Chrome/120...`),
  * Dalvik UA,
  * desktop Chrome UA,
  * a deliberately old Chrome/74 UA.
* 30+ pages fetched: homepage, 20 pagination pages sampled, 15 episode pages spread across pages 1/2/10,
  2 series pages, the all-series catalogue (2 pages), search queries, movie page, movie detail, news page,
  XML sitemaps, RSS feed, WordPress REST endpoints, the theme CSS/JS, and the full 3-level player chain.
* Static analysis of theme JS, player JS, and the packed JW Player configuration
  (the configuration was decoded **only to fingerprint the player**; its media URL and tokens are not reproduced here).
* Header/behaviour tests: HTTP→HTTPS redirect, cookies, `Referer` variations, HTTP status for invalid pages.

### 2.2 What could **not** be done here

| Not possible in this environment | Consequence |
|---|---|
| Run Android WebView / an emulator | JS execution, fullscreen, H.264 decode, D-pad routing are **inferred**, not observed. Marked **UNVERIFIED** in `WEBVIEW_COMPATIBILITY.md`. |
| Run a headless browser | No DOM-after-JS measurements; layout conclusions come from CSS + markup. |
| Access the TX1-S device | WebView version, codecs, and real memory pressure are unknown. |

Environment facts: Linux chroot inside an Android device (Redmi Note 8, Android 16, arm64-v8a),
Java 21 present, **no** Gradle, **no** Android SDK, **no** `adb`, **no** browser engine.

### 2.3 Evidence store

All raw evidence is in `/tmp/opencode/qrmzi-evidence/` (see Appendix A). Numbers in this document are
a snapshot of 6 Nov 2026 and will drift as the site publishes episodes.

---

## 3. Platform identification (verified)

| Signal | Value |
|---|---|
| Generator | WordPress (`wp-json`, `wp-content`, `wp-theme-qrmzi`, `?p=` shortlinks) |
| Theme | `qrmzi` (`https://www.qrmzi.tv/wp-content/themes/qrmzi/`) |
| Body classes | `rtl home blog wp-theme-qrmzi` (home), `... single single-episodes ...`, `... single-series ...` |
| Front page | `show_on_front: "posts"`, `page_on_front: 0` → homepage is the latest-posts loop |
| JS libs | jQuery **2.2.4** (Google CDN), custom Bootstrap 3 build (9.7 KB), `core.js` (1.65 KB) |
| Page builder bits | Redux 4.5.7, Gutenberg global styles, WP 6.x REST (`wp/v2`, `wp-abilities/v1`, …) |
| Plugins (inferred) | Google Sitemap Generator 4.1.22, Code Snippets, Redirection, GTM, AlbaPlayer (on the player domains) |
| Edge | Cloudflare + Engintron/cPanel (`x-server-powered-by: Engintron`, `x-nginx-upstream-cache-status`) |
| Language | `<html dir="rtl" lang="ar">`, all UI strings Arabic |
| Auth | **None.** No login form, no paywall, no `Set-Cookie` on anonymous requests |

REST namespaces advertised in `/wp-json/`:
`oembed/1.0`, `code-snippets/v1`, `redirection/v1`, **`meta`**, `wp/v2`, `wp-site-health/v1`, `wp-block-editor/v1`, `wp-abilities/v1`.

The non-standard **`meta`** namespace exposes site configuration (`/wp-json/meta/watch-settings/1`) — see §12.

---

## 4. Content model and inventory (verified)

| Type | REST base | Public | Count (6 Nov 2026) | URL prefix |
|---|---|---|---|---|
| `episodes` | `/wp-json/wp/v2/episodes` | **Yes** (read-only, anonymous) | **921** (`x-wp-total`) | `/episode/<slug>/` |
| `movies` | `/wp-json/wp/v2/movies` | **Yes** | **49** | `/movies/<slug>/` |
| `series` | **not registered in REST** | HTML/sitemap only | **191** (series sitemap) | `/series/<slug>/` |
| `post` | `/wp-json/wp/v2/posts` | Yes but empty | **0** | — |

* `WP_Types` lists `post, page, attachment, nav_menu_item, wp_block, wp_template, wp_template_part, wp_global_styles, wp_navigation, wp_font_family, wp_font_face, movies, episodes` — **`series` is absent**.
* The homepage loop mixes **episodes + movies**: 50 cards/page × 19 pages + 20 = **970 = 921 + 49**. Verified by comparing REST `orderby=date&order=desc` with the HTML grid, and by finding a `/movies/...` card on page 2.
* Taxonomies exist for `series` (`seasons`, `category`, `post_tag`) and `movies` (`collection`, `actor`) but the **rendered HTML does not show them** on series/movie pages (only title, story, cover, episodes).
* Relationship direction: **episode → series** is rendered on the episode page; the series page lists its episodes. There is no REST field linking them, and the series page only shows the **currently published season**.

---

## 5. Information architecture and URL map

| Route | Template | Notes |
|---|---|---|
| `/` | latest feed | 50 cards, episodes + movies, pagination **commented out in HTML** |
| `/page/N/` | same | Works (`/page/20/` = 20 cards, `/page/21/` = 200 + 0 cards) |
| `/episode/<slug>/` | single episode | player iframe + metadata + series + 3 tabs |
| `/series/<slug>/` | single series | cover, story, episode grid (single season) |
| `/all-turkish-series/` | catalogue | 40 cards/page, pagination visible, **5 pages** (last has 31) |
| `/all-turkish-series/page/N/` | same | Works |
| `/all-turkish-movies/` | movie catalogue | 49 cards |
| `/movies/<slug>/` | single movie | same template family as series |
| `/?s=<query>` | search | series + movies only, no pagination |
| `/news/` | news list | 52 news links, not required by the brief |
| `/feed/` | RSS | 10 latest items with `media:content` landscape thumbnails |
| `/sitemap.xml` | sitemap index | includes `series-sitemap.xml`, `episodes-sitemap.xml`, `movies-sitemap.xml` |

Slugs are **mixed**: many series use percent-encoded Arabic slugs
(`/series/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d8%a7%d9%84%d8%ae%d9%84%d9%8a%d9%81%d8%a9/`),
later entries use ASCII slugs (`/series/anne-yarisi/`). Both must be stored and requested **verbatim**.
Redirects: one series resolved `/series/...-v1/` while older links point to the non-`v1` slug
(the site 301s, but the app must follow redirects and store the final URL).

---

## 6. Homepage (verified)

* `#load-post` contains exactly **50** `<article class="post">` cards.
* 10 of them carry `<span class="ribbon">` — observed values: `الحلقة الأخيرة`, `مؤجلة`, `نهاية الموسم`.
* The pagination block **exists in the HTML but is wrapped in an HTML comment**:
  `<!-- <div class="col-md-12"><div class="navigation mt20 clearfix">…</div></div> -->`
  → there is **no visible way to go to page 2 from the UI**, but `/page/N/` is public and returns 200.
* Empty pages still return **HTTP 200** with zero cards (not 404). Never use the status code to detect the end.
* The homepage is **not** a curated hero page: no slider, no sections, one flat grid.
  There is no per-genre row, no "trending", no featured banner — any rows in the app must be derived
  from the same single latest-first ordering (grouping by series is possible but is app-side organisation, not a new catalogue).

---

## 7. Episode cards and thumbnails (verified)

Two card families exist:

| Family | Container | Link path | Image class | Aspect | Observed size |
|---|---|---|---|---|---|
| Episode card | `.posterThumb` | `/episode/…` | `img.imgBg` | **portrait 2:3** | `349×520` (≈45.6 KB) |
| Series / episode-grid card | `.poster` | `/series/…` or `/episode/…` | `img.imgSer` | **landscape 16:9** | `470×264` or `470×255` (≈30 KB) |
| Series cover (details page) | `.cover` | — | `img.img` | **landscape 16:9 original** | `1280×720` (≈118 KB) |

Verified by measuring JPEG headers of the actual files.

* Every image is **lazy**: `src` is a placeholder (`placeholder.gif` or a base64 1×1 GIF) and the real URL is in
  `data-src`, loaded by an `IntersectionObserver` script. **Server-side HTML parsing is enough** — no JS needed.
* **No `srcset`/`sizes`** anywhere. One fixed file per usage.
* Attribute quoting is inconsistent: `data-src=https://…` (unquoted) appears as often as quoted values.
  A robust HTML parser is required (regex-only extraction is fragile).
* Portrait posters are the only asset available on episode cards. Landscape `1280×720` covers **are** available
  on each series page and on each episode page (in `.singleSeries .cover`), which is what a TV UI should use
  for hero/detail surfaces.
* Episode numbers use **Latin digits** inside `<span>` (`حلقة` / `41`), not Arabic-Indic digits.

---

## 8. Series pages (verified)

`.singleSeries` contains:

```html
<div class="singleSeries">
  <div class="cover img-cnt"><img class="img lazy" data-src="…/Halef-s2-Cover.jpg"></div>
  <div class="info">
    <h1>مسلسل الخليفة</h1>
    <div class="story">…Arabic synopsis…</div>
  </div>
</div>
```

* Episode grid: `article.postEp` cards, **current season only**, newest first (e.g. 41 episodes; the
  191-episode series shows only **37** cards, numbers **155–191**).
* **No season selector, no pagination, no episode filter** on the page.
* One ribbon can appear (`نهاية الموسم`).
* **No actor/category/rating metadata** is rendered (the markup is commented out).
* A large series can therefore hide older seasons from the app as well. **Do not invent or guess missing seasons.**

---

## 9. Episode pages (verified)

Server-rendered order in `<main>`:

1. `.getEmbed` → `.watch` → one `<iframe loading="lazy" src="https://w.anaplayer.online/albaplayer/…">`
2. `.singleInfo > h1` → `مسلسل الخليفة الحلقة 41`
3. share button + popup (Twitter/X, Facebook, WhatsApp, Telegram — all external links)
4. `.singleSeries` → `.cover img[data-src]`, `.info > h2 > a` (series link), `.story` with
   `.episode-desc` (episode text) and `.series-story` (series synopsis)
5. Tabs: `#episodes-tab` (الحلقات, **41** cards), `#relevants-tab` (ذات صلة, 8 cards), `#new-tab` (الجديد, 12 cards)
6. `#load-post`-like grids inside each tab.

Important negatives:

* **No previous/next episode links exist** anywhere on the page (`prev`/`next` hits are CSS only).
* **No server list** is rendered (`<ul class="serversList">` count = 0 on all 15 sampled episode pages);
  the legacy multi-server JS in `core.js` is dead code for current content.
* `#favorite` is **not rendered** (CSS exists, AJAX endpoint exists) → the site's favourite feature is disabled.
* Episode numbers in the `episodes-tab` are **descending**, and the current episode is present in the list.
  That tab is the only reliable source for "next/previous episode".
* The `episodes-tab` list is scoped to the same visible season as the series page.

---

## 10. Search (verified)

* Endpoint: `GET https://www.qrmzi.tv/?s=<query>` (`GET` form, field name `s`, no nonce, no CSRF token).
* Results are **series + movies**, not episodes:

  | Query | Cards | Series | Movies |
  |---|---|---|---|
  | `حب` | 23 | 18 | 5 |
  | `عشق` | 7 | 5 | 2 |
  | `الحب` | 6 | 3 | 3 |
  | `الخليفة` | 1 | 1 | 0 |
  | `ask` (Latin) | 0 | 0 | 0 |
  | `halef` (Latin) | 0 | 0 | 0 |

* Matching is a WordPress `LIKE %term%` over title/content → **substring** semantics (`حب` hits `محبة`, `حبيب`, …).
* **Arabic only**: transliterated/Latin queries return nothing.
* **No pagination** on results (`/page/2/?s=…` returns the same items) and **no "no results" message** — the page is simply empty.
* REST search is complementary: `/wp-json/wp/v2/search?search=…` returns **episodes, movies, pages** (86 episodes + 5 movies + 1 page for `حب`), and
  `/wp-json/wp/v2/episodes?search=…` reports `x-wp-total: 41` for `الخليفة`.
  → A native search screen should query **both** and merge (series/movie cards from HTML, episodes from REST).

---

## 11. Player chain (verified structurally)

One server per episode. Observed chain for the sampled episode:

```
qrmzi episode page
  └─ iframe  https://w.anaplayer.online/albaplayer/<provider-slug>        (WordPress + AlbaPlayer plugin)
       └─ iframe  https://shooof.online/albaplayer/<provider-slug>        (same AlbaPlayer plugin)
            └─ iframe  https://cdnplus.sbs/embed-<id>.html                (JW Player host)
                 └─ JW Player 8.34.5  →  HLS (m3u8)  →  native/HTML5 playback
```

* The chain length varies: for one older episode, `anaplayer` embedded `cdnplus.sbs` **directly** (2 levels),
  and the `shooof` page for that same episode embedded `anaplayer` with `?serv=0`. **Do not hardcode 3 levels**;
  follow whatever iframe the page contains.
* All three hosts are WordPress/AlbaPlayer installations with the same plugin assets
  (`/wp-content/plugins/AlbaPlayer/assets/js/albaplayer.js`, 17 KB, obfuscated).
* Deepest player configuration (decoded for fingerprinting only, URL/token omitted):

  | Property | Value |
  |---|---|
  | Player | **JW Player 8.34.5** (115,313-byte `jwplayer.js`) |
  | Source type | **HLS** (`m3u8`), single source |
  | `androidhls` | `"true"` → Android uses the **native HLS** path (platform decoder) |
  | `preload` | `"auto"` |
  | `fullscreenOrientationLock` | `"none"` |
  | DRM | **none** (`drm`/`widevine`/`playready` strings absent) |
  | Alternative formats | no `mp4`, no DASH |
  | Quality labels | `Original`, `FHD (1080p)`, `HD (720p)`, `SD (480p)`, `Mobile (360p)` |
  | Extras | `playbackRateControls`, empty captions track `/srt/empty.srt` |
  | Player key | proprietary JW license key present in the page (not reproduced) |

* The player page is small but heavy in requests: `/js/jquery.min.js` 89.5 KB, `jwplayer.js` 115.3 KB,
  `/css/main.css` 46.5 KB, plus ad tags.
* Cookies set by the player page: `file_id`, `aff`, `ref_url`, `vid` (10-day expiry).
  Progress is stored in `localStorage` under a per-file key (`tt…<id>`, 7-day TTL).
* `Referer` behaviour: `cdnplus.sbs` returns **200 with or without** a Referer; the Referer only changes the
  `ref_url` cookie value and a small part of the generated config. **Access is not referer-gated.**

No media playlist or segment URL was requested, stored, or recorded — by design.

---

## 12. Advertising and gating (verified)

| Layer | Evidence | Implication |
|---|---|---|
| qrmzi itself | `<script src="https://pgo99.com/1/…">` (popunder network) + GTM `G-089SVSFWGY` | Blocking this script is safe for playback and is the first thing the optional filter should handle |
| Deep player | `tagivi.com/1/…`, `ia.girellaharelip.com/…`, GTM `G-ZV8Z…` | Third-party ad tags — blockable, but see the gate below |
| Ad-block detector | `#adbd` overlay is shown when `window.cRAds === undefined`; `cRAds = true` is set by **`/js/pop.js`** (36 bytes) | **If you block `pop.js`, the "Disable ADBlock plugin" overlay appears.** Never blanket-block first-party player scripts |
| Play limit | `#play_limit_box` with a link to `cdnplus.org/premium.html` (`target="_blank"`) | Can appear after N plays; must be handled as a user-visible provider restriction, not bypassed |
| Video ads | `.video_ad`, `.video_ad_fadein`, VAST logic in the decoded config | Ads are part of the provider's player |
| Theme gate config | `/wp-json/meta/watch-settings/1`: `skip_type:"countdown"`, `skip_page_countdown_timer:"10"`, skip/watch domain lists, ad video on `cdn.foxpush.net` | The theme **can** insert a countdown/skip-page gate. It is **not present** in the 15 sampled episode pages, but may appear later |

**Rule for the app:** filtering is optional, user-controlled, and limited to known ad/tracker hosts.
It must never remove player assets, and it must auto-offer "disable filtering and reload" on failure.
It must not attempt to skip the provider's countdown/play-limit screens.

---

## 13. Mobile vs desktop (verified)

* The only server-side difference is one JS variable:
  `var themeDir="…",themeMobile="1";` (mobile UA) vs `themeMobile="";` (desktop UA).
* **HTML is otherwise byte-identical** (252,684 vs 252,683 bytes; first difference is that variable).
* Viewport meta is the same in both: `<meta name="viewport" content="width=device-width">`
  (no `initial-scale`, no `maximum-scale`).
* Layout is responsive via CSS: **29 unique media queries**, breakpoints at 992 / 769 / 768 / 668 / 580 / 480 px;
  base grid is **5 cards per row** (16.6 %) with a portrait box (`padding-bottom:150%`).
* Consequence: the UA does **not** unlock a different catalogue or player. For the TV WebView, use a modern
  Android WebView UA and control the viewport yourself.

---

## 14. Arabic RTL (verified)

* `<html dir="rtl" lang="ar">`, body class `rtl`, CSS uses `float:right`, `text-align:right`, `direction:rtl`.
* Font: custom **Kirmizi** (3 weights, `kirmizi-regular.woff2` 68,456 bytes) exposed as `--site-font: Kirmizi, Arial, sans-serif`.
  A second font (Almarai) is referenced only inside player-notification CSS.
* Numerals: **Latin** in episode badges and counts; Arabic text elsewhere. No Arabic-Indic digit conversion needed.
* Native UI implication: `android:layoutDirection="rtl"`, `textDirection="rtl"`, RTL-aware RecyclerView ordering,
  and a bundled Arabic-capable font (Kirmizi is site-licensed; use a system/Android font or a properly licensed one).

---

## 15. Cookies, storage, and JavaScript requirements (verified)

| Item | qrmzi.tv | Player chain |
|---|---|---|
| `Set-Cookie` on anonymous load | **none** (empty cookie jar) | `file_id`, `aff`, `ref_url`, `vid` |
| Login / session | none | none |
| `localStorage` | not used | progress key per file, 7-day TTL |
| `document.cookie` in theme JS | not used | used (jQuery cookie plugin) |
| JS required for images | **yes** (`IntersectionObserver` sets `src` from `data-src`) | yes |
| JS required for tabs | yes (Bootstrap tabs) | — |
| JS required for search | no (plain GET form) | — |
| Modern JS/CSS used | arrow functions, `let/const`, template literals, `Array.includes`, `IntersectionObserver`, `:is()`, `aspect-ratio`, flex `gap`, CSS custom properties | same |

Implication: a **static HTML fetch** is enough for all native data (images are in `data-src`), while the
WebView must be a modern Chromium (Chrome ≥ 88 for `:is()`/`aspect-ratio`; ≥ 84 for flex `gap`).

---

## 16. Weight and performance (measured)

| Asset | Bytes |
|---|---|
| Homepage HTML (raw) | **252,684** |
| Homepage HTML (gzip/br) | **42,109** |
| Inline `<style>` on homepage | **179,840** |
| jQuery 2.2.4 | 30,028 |
| Bootstrap JS | 9,670 |
| `core.js` | 1,650 |
| Kirmizi regular woff2 | 68,456 |
| 50 portrait posters | ~1.5–2.3 MB total |
| Episode page HTML | 250–308 KB |
| Player page + assets | ~250 KB + ads |

Caching: images `cache-control: max-age=5184000` (60 days, Cloudflare HIT); HTML is `cf-cache-status: DYNAMIC`
(no ETag/Last-Modified on HTML) → the app must use its own TTL/stale-while-revalidate policy.

For 1 GB RAM this rules out "browse the whole site in a WebView": each page is ~250 KB of HTML with 180 KB of
inline CSS and 50 images. Native lists + server-side parsing are dramatically cheaper.

---

## 17. Security posture and embedding (verified)

* `https://qrmzi.tv` → **301** → `https://www.qrmzi.tv/`; TLS via Cloudflare.
* No `Content-Security-Policy`, no `X-Frame-Options`, no `frame-ancestors`, no `Strict-Transport-Security`
  on qrmzi or on the three player hosts.
* The deepest player contains an **anti-embed script** that blocks when:
  1. `window.frameElement.hasAttribute("sandbox")` is true,
  2. the document is loaded from a `data:` URL,
  3. `document.domain` manipulation throws (sandboxed),
  4. `frameElement` is `null` **and** `document.domain === ""` (i.e. a stripped browsing context),
  and then removes the `<video>` and shows *"Disable sandbox to play the video"*.
  → **Never use the `sandbox` attribute**, never load the player via `data:`/`srcdoc`, keep normal HTTPS.
* The anti-embed check explicitly allows a **normal cross-origin iframe** (`window.parent !== window`,
  `frameElement` accessible/normal, `document.domain` = host).
* `robots.txt`: only `/wp-admin/` disallowed; sitemaps advertised.
* No authentication, subscription, DRM, or token gate was observed on the page/player URLs.
  Rate limiting is unknown (Cloudflare in front).

---

## 18. Legal and ethical boundaries for this project

The app must remain a **client for the public site**, not a media extractor:

1. **Do not** extract, store, proxy, or play the underlying media playlist/segments directly.
2. **Do not** bypass DRM, authentication, paywalls, play limits, countdown gates, or anti-bot challenges.
3. **Do not** strip or defeat the provider's ads as a product promise. Offer a *user-configurable*,
   conservative request filter only, with an easy off switch (as the brief requires).
4. **Do not** bulk-scrape aggressively. Cache, throttle, and use conditional requests where available.
5. **Do not** redistribute or download content. Playback happens inside the site's own player.
6. Keep the site's branding/attribution visible where reasonable; the app is "dedicated to Qrmzi TV",
   not a replacement catalogue.
7. The content itself is third-party copyrighted drama; the app adds no catalogue of its own.

---

## 19. Native vs WebView — decision summary

| Capability | Native | WebView | Why |
|---|---|---|---|
| Home / rows / focus / scrolling | ✅ | ❌ | D-pad, memory, speed |
| Search UI + results | ✅ | ❌ | TV keyboard, merging REST + HTML |
| Series details / episode list | ✅ | ❌ | Parsed HTML + REST |
| Favourites / history / settings | ✅ | ❌ | Room/DataStore |
| Image loading & caching | ✅ | ❌ | Size control for 1 GB |
| Data fetching / parsing | ✅ | ❌ | REST + HTML, cacheable |
| Embedded video playback | ❌ | ✅ | Nested provider iframes, HLS, cookies, anti-embed |
| Player fullscreen & key routing | ⚠️ container | ✅ page | Native container + WebView page |
| Ad filtering | ✅ | — | `shouldInterceptRequest` in the player WebView |
| "Open full website" fallback | ❌ | ✅ (optional) | Escape hatch for unparsed pages |

Details, risks and fallbacks are in `WEBVIEW_COMPATIBILITY.md`; the build plan is in `INTEGRATION_PLAN.md`.

---

## 20. Open questions (must be answered on the TX1-S)

1. Which **Android System WebView version** is installed (and can it update)?
2. Does the WebView use **hardware H.264** decode, and does HLS play smoothly at 720p/1080p on 1 GB RAM?
3. Does **D-pad focus** enter and control the nested player iframes? Which keys (Enter/Space/arrows) work?
4. Does **fullscreen** work via `WebChromeClient.onShowCustomView`, and does BACK exit it correctly?
5. Do the player cookies persist across app restarts (`CookieManager.flush()`), and are they needed?
6. Does the **ad-block overlay** appear when only qrmzi's `pgo99.com` script is blocked (it should not),
   and does the play-limit box appear after repeated plays?
7. Does Cloudflare throttle the app's request pattern (bursts of episode pages)?
8. Will the provider slug format (`…-s01e41`) stay stable, and how often does the chain change?
9. Do older seasons ever become reachable (currently hidden), and should the app surface a "season" hint at all?
10. Is the series sitemap always complete (191 entries) relative to the catalogue (191 cards over 5 pages)? — currently consistent.

---

## Appendix A — Evidence index

| File | What it proves |
|---|---|
| `home.html` (252,684 B) | homepage DOM, 50 cards, ribbons, commented pagination, lazy images |
| `home_desktop.html` | desktop UA returns the same HTML (only `themeMobile` differs) |
| `page2.html`, `p3.html`, `page10.html`, `pg17–19.html`, `p20.html` | pagination, 50 cards/page, mixed movie card, last page |
| `episode1.html` (295,587 B) | episode template, player iframe, tabs, no prev/next |
| `ep_sample_1…6.html`, `old_ep_1…3.html`, `p10_ep_1…2.html` | 15 episode pages: single anaplayer server, no `serversList` |
| `series1.html`, `big_series.html` | series template, 1280×720 cover, single-season grids (41 / 37 episodes) |
| `all_series.html`, `all_series_p2.html` | 40/page, visible pagination, 5 pages |
| `search_*.html`, `sq_*.html` | search = series + movies, substring, no pagination, no empty state |
| `movies_page.html`, `movie1.html` | movie catalogue and detail template |
| `player.html`, `shooof.html`, `cdnplus.html` | 3-level iframe chain, AlbaPlayer plugin |
| `cdnplus_main.css`, `jwplayer.js` | overlay/ad CSS, JW Player 8.34.5 |
| `unpacked.js` | decoded JW config (fingerprint only; media URL not reproduced) |
| `wpjson_*.json`, `watch_settings.json` | REST inventory, totals, 401 featured media, watch-gate config |
| `series-sitemap.xml`, `feed.xml` | 191 series, 10 latest with landscape `media:content` |
| `headers_home.txt`, `cdnplus_headers.txt` | Cloudflare, no CSP/XFO, no `Set-Cookie` |

## Appendix B — Snapshot numbers

| Metric | Value |
|---|---|
| Episodes | 921 |
| Movies | 49 |
| Series | 191 |
| Homepage cards | 50 (19 full pages + 20) |
| Homepage pages | 20 (page 21 returns 200 with 0 cards) |
| Series per page | 40 (5 pages, last 31) |
| Search results (`حب`) | 23 cards (18 series + 5 movies) |
| Player | JW Player 8.34.5, HLS, no DRM |
| Episode page size | 250–308 KB |
| Homepage size | 252 KB raw / 42 KB compressed |
