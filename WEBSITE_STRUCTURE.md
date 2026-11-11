# QRMZI TV — Website Structure Reference

**Purpose:** exact, implementation-ready description of the public Qrmzi HTML/JSON surface,
based on live fetches on 6 November 2026. Every URL and selector below was observed in the
evidence files listed in `QRMZI_ANALYSIS.md` (Appendix A).

**Conventions used here**

* “Card” = one `<article>` in a grid.
* “Slug” is the URL path segment, stored **verbatim** (it may be percent-encoded Arabic or ASCII).
* Numbers are a snapshot; the app must never hardcode counts.

---

## 1. Request profile

| Item | Observed |
|---|---|
| Canonical origin | `https://www.qrmzi.tv` (`http://` → **301**) |
| HTML content type | `text/html; charset=UTF-8` (UTF-8, Arabic) |
| Server | Cloudflare + Engintron/cPanel (`x-server-powered-by: Engintron`) |
| Compression | gzip/br (homepage 252,684 → 42,109 bytes) |
| `Set-Cookie` (anonymous) | **none** |
| Security headers | `x-content-type-options: nosniff`, `x-xss-protection`; **no CSP, no XFO, no HSTS** |
| UA sensitivity | only `themeMobile` changes; same markup for mobile/desktop/WebView/Dalvik/Chrome 74 |
| 404 behaviour | missing **archive pages** return **200 with 0 cards**; use card count, not status |
| robots.txt | `Disallow: /wp-admin/` only |
| Recommended UA (app) | modern Android Chrome/WebView UA; keep one constant string for both scraping and player |

Suggested parser headers: `Accept: text/html`, `Accept-Language: ar`, `Accept-Encoding: gzip`,
keep-alive, and a single-flight request queue (no bursts).

---

## 2. URL grammar

```
/                                         latest feed (episodes + movies)
/page/<N>/                                latest feed, page N (1-based)
/episode/<slug>/                          episode page
/series/<slug>/                           series page
/all-turkish-series/                      series catalogue
/all-turkish-series/page/<N>/             series catalogue pagination
/all-turkish-movies/                      movie catalogue
/movies/<slug>/                           movie page
/news/                                    news list
/?s=<query>                               search (GET)
/feed/                                    RSS 2.0
/sitemap.xml                              sitemap index
/series-sitemap.xml                       191 series URLs
/episodes-sitemap.xml                     episode URLs
/movies-sitemap.xml                       movie URLs
/wp-json/...                              WordPress REST
```

Slug examples (both forms are live):

```
/series/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d8%a7%d9%84%d8%ae%d9%84%d9%8a%d9%81%d8%a9/   (Arabic, percent-encoded)
/series/anne-yarisi/                                                                   (ASCII)
/episode/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d8%a7%d9%84%d8%ae%d9%84%d9%8a%d9%81%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-41/
```

Rules:

1. **Never decode then re-encode a slug** before requesting it — the server compares the encoded path.
2. Follow redirects and store the **final** URL (a `/series/…-v1/` redirect was observed).
3. Treat the URL as the primary key; there is no shared numeric ID between series and episodes in HTML.
4. Episode/movie/series URLs are stable enough for favourites/history, but always re-validate on open.

---

## 3. Global page chrome

Every page shares this skeleton (RTL):

```html
<!DOCTYPE html>
<html dir="rtl" lang="ar">
<head>
  <meta name="viewport" content="width=device-width">
  <title>… - موقع قرمزي</title>
  <!-- 4 inline <style> blocks (~180 KB total), Kirmizi.css preload, icons.css -->
</head>
<body class="rtl … wp-theme-qrmzi">
  <div id="headerNav">
    <div id="logo"><a href="https://www.qrmzi.tv"><img … alt="موقع قرمزي"></a></div>
    <button class="btn btn-mobile" data-toggle="collapse" data-target=".nav-main-collapse">…</button>
    <span class="iconSearch"><i class="icon-search"></i></span>
    <div class="navbar-collapse nav-main-collapse collapse">
      <ul id="topMain" class="nav nav-pills nav-main">…4 items…</ul>
    </div>
    <div id="search" class="con_search">
      <div class="sea_close"><i class="icon-clear"></i></div>
      <form role="search" method="GET" action="https://www.qrmzi.tv">
        <input name="s" type="text" placeholder="ادخل اسم المسلسل فقط">
        <input class="btn btn-primary" type="submit" value="البحث">
      </form>
    </div>
  </div>
  <main role="main"> … </main>
  <footer>…8 legal links…</footer>
</body>
```

Menu (top): `الرئيسية /`, `المسلسلات /all-turkish-series/`, `الافلام /all-turkish-movies/`, `الاخبار /news/`.

> The search panel is **collapsed by default** and opened by clicking `.iconSearch` (jQuery `core.js`).
> A native app does not need it; use `?s=` directly.

---

## 4. Page templates

### 4.1 Home / latest feed (`/`, `/page/N/`)

```html
<main role="main">
  <div class="sec-line">
    <div class="containers container-fluid">
      <div class="row">
        <div id="load-post">
          <article class="post">
            <div class="col-xs-5th col-sm-5th col-md-5th col-lg-5th">
              <div class="block-post">
                <a href="https://www.qrmzi.tv/episode/<slug>/" title="مسلسل الخليفة الحلقة 41 قرمزي">
                  <div class="episodeNum"><span>حلقة</span><span>41</span></div>
                  <span class="ribbon">الحلقة الأخيرة</span>          <!-- optional -->
                  <div class="posterThumb img-cnt">
                    <img class="imgBg lazy" alt="مسلسل الخليفة"
                         data-src=https://…/Halef-s2-Poster-349x520.jpg
                         src="data:image/gif;base64,R0lGOD…">
                  </div>
                  <div class="title">مسلسل الخليفة الحلقة 41</div>
                </a>
              </div>
            </div>
          </article>
          … 49 more …
        </div>
        <!-- <div class="col-md-12"><div class="navigation mt20 clearfix">…pagination…</div></div> -->
      </div>
    </div>
  </div>
</main>
```

Facts:

* **Exactly 50 cards** per page.
* **Mixed types**: most cards link to `/episode/…`, but a card may link to `/movies/…`
  (page 2 contains one). Branch on the `href` path, never on the card class.
* Pagination markup is **inside an HTML comment** (`.navigation` exists but is not visible).
* `article.post` is also used on search / movie list / news pages — always scope by container.

### 4.2 Search (`/?s=<query>`)

Same `.sec-line > .containers > .row` wrapper, **no `#load-post`**, cards are `article.post`
with `.posterThumb img.imgBg` (portrait) and links to **`/series/…` or `/movies/…`**.
There is no heading, no result count, no pagination and no “no results” text.

```html
<article class="post">
  <div class="col-xs-5th …">
    <div class="block-post">
      <a href="https://www.qrmzi.tv/series/<slug>/" title="مسلسل حبك نار قرمزي">
        <div class="posterThumb img-cnt"><img class="imgBg lazy" alt="مسلسل حبك نار" data-src=… src="data:…"></div>
        <div class="title">مسلسل حبك نار</div>
      </a>
    </div>
  </div>
</article>
```

* The first card on a search page is the **first match**, not a fixed banner (no special markup).
* `<title>` becomes `نتائج البحث عن <query>` (raw query reflected; escape it in your UI).

### 4.3 Series catalogue (`/all-turkish-series/`)

```html
<main role="main">
  <div class="sec-line"><div class="containers container-fluid"><div class="row">
    <article class="postEp">
      <div class="col-lg-4th col-md-4th col-sm-4th col-xs-4th">
        <div class="block-post">
          <a href="https://www.qrmzi.tv/series/anne-yarisi/" title="مسلسل نصف الأم قرمزي">
            <div class="poster img-cnt">
              <img class="imgSer lazy" alt="مسلسل نصف الأم"
                   data-src=https://…/Anne-Yarisi-Cover-470x264.jpg src="data:…">
            </div>
            <div class="title">مسلسل نصف الأم</div>
          </a>
        </div>
      </div>
    </article>
    … 39 more …
    <div class="col-md-12">
      <div class="navigation mt20">
        <div class='pagination'>
          <span class='current'>1</span>
          <a href='https://www.qrmzi.tv/all-turkish-series/page/2/' class='inactive'>2</a> …
        </div>
      </div>
    </div>
  </div></div></div>
</main>
```

* **40 cards/page**, 5 pages total (191 items; last page = 31).
* Landscape `img.imgSer`, thumbnails `470×264` **or** `470×255` (two source aspect ratios; both 16:9-ish).
* Pagination **is visible** here (unlike the homepage). Parse `div.pagination`:
  `span.current` = current page, `a.inactive` = other pages.
* There are only 4 grid columns classes (`col-lg-4th …`) → 4 per row on desktop, 5 per row on mobile;
  irrelevant for the native app.

### 4.4 Series detail (`/series/<slug>/`)

```html
<main role="main">
  <div class="secContainer bg"><div class="containers container-fluid"><div class="row">
    <div class="singleSeries">
      <div class="cover img-cnt">
        <img class="img lazy" alt="مسلسل الخليفة"
             src="…/placeholder.gif" data-src="https://…/Halef-s2-Cover.jpg">   <!-- 1280×720 -->
      </div>
      <div class="info">
        <h1>مسلسل الخليفة</h1>
        <div class="story">…Arabic synopsis…</div>
      </div>
      <!-- <div class="bg_heart"></div> -->
    </div>
    <div class="sec-line">
      <div class="containers container-fluid"><div class="row">
        <article class="postEp"> …episode card… </article>   <!-- × N (current season only) -->
      </div></div>
    </div>
  </div></div></div>
</main>
```

Series metadata available: **title (h1), synopsis (`.story`), cover (`img[data-src]`, 1280×720), episode list**.
Nothing else is rendered (no genres, actors, year, rating, trailer).

Episode cards inside a series page:

```html
<article class="postEp">
  <div class="col-lg-4th …">
    <div class="block-post">
      <a href="https://www.qrmzi.tv/episode/<slug>/" title="مسلسل الخليفة الحلقة 36 قرمزي">
        <div class="episodeNum"><span>حلقة</span><span>36</span></div>
        <div class="poster img-cnt"><img class="imgSer lazy" alt="…" data-src=…-470x264.jpg src="data:…"></div>
        <div class="title">مسلسل الخليفة الحلقة 36</div>
      </a>
    </div>
  </div>
</article>
```

Ordering is **newest first** (41 → 1; 191 → 155 for the 37-episode visible season).
No season tabs, no pagination, no “load more”.

### 4.5 Episode detail (`/episode/<slug>/`)

Server-rendered order inside `<main>`:

```html
<div class="secContainer bg"><div class="containers container-fluid"><div class="row">

  <!-- 1) PLAYER -->
  <div class="getEmbed ">
    <div class="watch">
      <iframe loading="lazy" src="https://w.anaplayer.online/albaplayer/<provider-slug>"
              frameborder="0" allowfullscreen="true" webkitallowfullscreen="true"
              mozallowfullscreen="true"></iframe>
    </div>
  </div>

  <!-- 2) TITLE -->
  <div class="singleInfo" style="…">
    <h1>مسلسل الخليفة الحلقة 41</h1>
    <div class="share-button-wrapper">…share button + popup…</div>
  </div>

  <!-- 3) SERIES BLOCK -->
  <div class="singleSeries">
    <div class="cover img-cnt">
      <img class="img lazy" alt="مسلسل الخليفة الحلقة 41" src="…/placeholder.gif"
           data-src="https://…/Halef-s2-Cover.jpg">
    </div>
    <div class="info">
      <h2><a href="https://www.qrmzi.tv/series/<series-slug>/" title="مسلسل الخليفة">مسلسل الخليفة</a></h2>
      <div class="story">
        <span class="episode-desc">مشاهدة وتحميل الحلقة 41 …</span>
        ،
        <span class="series-story">وتدور قصة المسلسل …</span>
      </div>
    </div>
  </div>

  <!-- 4) TABS -->
  <div class="watch-tabs-cnt">
    <div class="containers container-fluid"><div class="tab-content">
      <div class="tab-pane fade in active" id="episodes-tab">   <!-- 41 cards -->
      <div class="tab-pane fade" id="relevants-tab">            <!-- 8 cards -->
      <div class="tab-pane fade" id="new-tab">                  <!-- 12 cards -->
    </div></div>
  </div>

</div></div></div>
```

Key facts:

| Item | Finding |
|---|---|
| Player | exactly **one** iframe, inside `.getEmbed .watch`, **no server list** |
| Episode text | `.episode-desc` (contains the episode number and a Turkish title sometimes) |
| Series text | `.series-story` (same synopsis as the series page) |
| Series link | `.singleSeries .info h2 a[href]` |
| Episode list | `#episodes-tab` — **all visible episodes of the series**, newest first |
| Related | `#relevants-tab` — 8 cards, mostly same series + other series |
| New | `#new-tab` — 12 latest site-wide |
| Prev/next | **absent** (no anchors, no JS) |
| Favourite | **absent** (`#favorite` not rendered) |
| Download | no download UI; the word appears only in the description text |

### 4.6 Movie catalogue and movie detail

* `/all-turkish-movies/`: 49 cards, `article.post`, portrait `img.imgBg`, links `/movies/<slug>/`.
* `/movies/<slug>/`: same family as the series page — `singleSeries` cover (1280×720 original),
  `.info h1`, `.story`, and an embedded player iframe. No episode list.

### 4.7 Player chain

```
#1 qrmzi episode page
   iframe → #2 https://w.anaplayer.online/albaplayer/<provider-slug>
             iframe → #3 https://shooof.online/albaplayer/<provider-slug>   (may be skipped)
                       iframe → #4 https://cdnplus.sbs/embed-<id>.html
                                 JW Player 8.34.5 → HLS
```

Chain variability observed:

* episode 41: `anaplayer → shooof → cdnplus`
* episode 189: `anaplayer → cdnplus` (2 levels); the matching `shooof` URL embedded `anaplayer` with `?serv=0`

→ **Discovery rule:** load the qrmzi page (or its iframe URL), parse the child `<iframe src>`,
allow the known hosts, and follow at most **3** hops; cache the resolved deepest URL per episode but re-resolve on failure.

Hosts to allow for the player WebView:
`w.anaplayer.online`, `shooof.online`, `cdnplus.sbs` (plus `www.qrmzi.tv` for the page itself).
`cdnplus.org`, `tagivi.com`, `ia.girellaharelip.com`, `pgo99.com`, `googletagmanager.com`,
`cdn.foxpush.net` are ad/tracking hosts (see filtering rules).

---

## 5. Card grammar (the three variants)

| # | Where | `<article>` | Link | Image | Overlay | Aspect |
|---|---|---|---|---|---|---|
| A | home, search, movies list | `post` | `/episode/…`, `/series/…`, `/movies/…` | `img.imgBg` | `.episodeNum` (episodes only), `.ribbon` | portrait `349×520` |
| B | series page, episode tabs, series catalogue | `postEp` | `/episode/…` or `/series/…` | `img.imgSer` | `.episodeNum` | landscape `470×264` / `470×255` |
| C | series/episode/movie header | `singleSeries .cover` | — | `img.img` | — | landscape `1280×720` |

Shared inner structure:

```html
<div class="block-post">
  <a href="…" title="<title> قرمزي">
    [<div class="episodeNum"><span>حلقة</span><span>41</span></div>]
    [<span class="ribbon">الحلقة الأخيرة</span>]
    <div class="posterThumb img-cnt"> | <div class="poster img-cnt">
      <img class="imgBg|imgSer lazy" alt="…" data-src=… src="data:…">
    </div>
    <div class="title">…</div>
  </a>
</div>
```

Parsing rules:

1. Select cards by container, not globally: `#load-post > article.post`, `div.sec-line article.postEp`, `#episodes-tab article.postEp`.
2. Get the target from `a[href]` (may be episode/movie/series — branch on `/episode/`, `/movies/`, `/series/`).
3. Title: `div.title` (trim/collapse whitespace). `a[title]` includes a trailing `قرمزي` — strip it if used.
4. Image: `img[data-src]` **first**, then `img[src]` if it is not a placeholder/base64.
5. Episode number: `.episodeNum span:nth-child(2)`; fallback regex `الحلقة\s*(\d+)` on the title.
6. Ribbon: `.ribbon` text — observed `الحلقة الأخيرة`, `مؤجلة`, `نهاية الموسم`.
7. `alt` is usually the same as the title (useful fallback).

---

## 6. Field mapping (source → app model)

| App field | Primary source | Fallback | Notes |
|---|---|---|---|
| `title` | card `.title` / page `h1` | REST `title.rendered` | may contain the series name + episode number |
| `url` | `a[href]` / `link rel=canonical` | REST `link` | store verbatim |
| `imageUrl` | `img[data-src]` | RSS `media:content`, REST featured media (401) | portrait vs landscape depends on card type |
| `episodeNumber` | `.episodeNum` 2nd span | regex on title/slug | Latin digits |
| `seriesTitle` | `.singleSeries h2 a` | derived from title | |
| `seriesUrl` | `.singleSeries h2 a[href]` | — | linkage key |
| `episodeDescription` | `.episode-desc` | REST `content.rendered` | HTML-free text |
| `seriesSynopsis` | `.series-story` / series `.story` | — | |
| `ribbon` | `.ribbon` | — | three known values; show as badge |
| `publishedAt` | REST `date` / RSS `pubDate` | — | not shown in HTML |
| `playerUrl` | `.getEmbed iframe[src]` | — | may be absent on some pages (treat as "player unavailable") |
| `cover1280` | `.singleSeries .cover img[data-src]` | series page cover | best asset for TV hero |
| `related` | `#relevants-tab` cards | — | optional row |

The HTML has **no** genre, year, rating, cast, duration, or season number.
Do not fabricate them. Optional: parse the Turkish title out of `episode-desc` for display.

---

## 7. Pagination and end-of-list

| Section | Page size | Pagination markup | End detection |
|---|---|---|---|
| Home | 50 | **commented out** | `/page/N/` → 0 cards (HTTP still 200) |
| Series catalogue | 40 | `div.pagination` (`span.current`, `a.inactive`) | 0 cards / no `a.inactive` |
| Search | ~all matches | none | 0 cards |
| Series page | all visible episodes | none | — |
| Episode tabs | all visible episodes | none | — |

Safe algorithm for home/catalogue:

```
for page in 1..N:
    html = GET(url(page))
    cards = parse(html)
    if cards.isEmpty(): stop
    yield cards
```

Do **not** derive page count from `/page/21/` returning 200 — it is empty.
Use `x-wp-total` from REST for inventory/telemetry, never for pagination of HTML.

---

## 8. Lazy images and attribute quirks

* Real URL: `data-src`. `src` is `…/assets/images/placeholder.gif` or a `data:image/gif;base64,R0lGOD…` 1×1 pixel.
* Loader: inline script using `IntersectionObserver` with `rootMargin: '100px 0px'`, `threshold: 0.01`;
  on load it copies `data-src` → `src`, removes `data-src` and the `lazy` class, and adds `loaded` to the parent.
* `data-src` is **sometimes unquoted** (`data-src=https://…`), which breaks naive regexes and requires an HTML5 parser.
* No `srcset`/`sizes`; one file per card.
* Some `img` tags have `loading="lazy"` natively as well (the player iframe too).

**Static fetch is sufficient** — the URLs are in the HTML before JS runs.

---

## 9. Series ↔ episode graph, and “next episode”

Available edges:

```
episode page  --(.singleSeries h2 a)-->  series page
series page   --(article.postEp a)---->  episode pages   (current season, newest → oldest)
episode page  --(#episodes-tab a)----->  same list
```

There is **no** previous/next link anywhere. Derivation:

1. Open the episode page; read `#episodes-tab` links in DOM order (descending episode numbers).
2. Locate the current episode by URL (normalise trailing slash).
3. “Next episode” (the one to watch after this) = the **following** card in descending order
   (i.e. episode N−1). “Previous” = the **preceding** card (N+1).
4. Disable the action at the list edges. If the current episode is missing from the list, fall back to
   the series page grid; if that fails, hide the controls.

```
idx = list.indexOf(current)
next = list[idx + 1]   // lower number, later in DOM
prev = list[idx - 1]
```

Caveats:

* The list is **season-scoped**. At episode 155 (first of the visible season) there is no “next”
  even though episode 154 exists on the site. Do not invent a URL.
* Episode-number gaps exist (e.g. a series may skip numbers). Use list adjacency, **not** `N ± 1` arithmetic.

---

## 10. Search semantics (tested)

| Query | Kind | Series | Movies | Episodes (REST) |
|---|---|---|---|---|
| `حب` | Arabic substring | 18 | 5 | 86 |
| `عشق` | Arabic | 5 | 2 | — |
| `الحب` | Arabic | 3 | 3 | — |
| `الخليفة` | Arabic | 1 | 0 | 41 |
| `ask` | Latin | 0 | 0 | — |
| `halef` | Latin | 0 | 0 | — |

* HTML search = **series + movies only**; REST search = **episodes + movies + pages**.
* Substring matching (`LIKE %q%`) over title/content → `حب` also matches `محبة`, `حبيب`, …
* No pagination: `/page/2/?s=…` returns the same list.
* No empty state, no result count, no spelling correction, no transliteration.
* Query is echoed into `<title>`; escape it before display.
* No CSRF/nonce — the form is a plain GET.

**Native search plan:** debounce → fire HTML `?s=` (series/movies) **and**
`/wp-json/wp/v2/episodes?search=` (episodes) → merge by type → cache per query for the session.
For offline: a local index of the 191 series titles from the sitemap + last-seen cards.

---

## 11. REST reference

Base: `https://www.qrmzi.tv/wp-json/`

| Endpoint | Auth | Use | Notes |
|---|---|---|---|
| `/wp/v2/episodes?per_page=50&page=N` | none | **episode inventory** | `x-wp-total: 921`, `x-wp-totalpages: 19` |
| `/wp/v2/episodes?search=<q>` | none | episode search | `x-wp-total: 41` for `الخليفة` |
| `/wp/v2/episodes/<id>` | none | single episode | includes `date`, `link`, `content.rendered`, `featured_media` |
| `/wp/v2/episodes/<id>?_embed` | none | featured media | returns **401** `rest_forbidden` for `wp:featuredmedia` |
| `/wp/v2/movies?per_page=50` | none | movie inventory | `x-wp-total: 49`, 1 page |
| `/wp/v2/media…` | none | — | featured media is protected (401) → use HTML/RSS images |
| `/wp/v2/search?search=<q>` | none | mixed search | returns `episodes`, `movies`, `page` — **not** `series` |
| `/wp/v2/types` | none | type discovery | **`series` is not listed** |
| `/wp/v2/taxonomies` | none | taxonomy discovery | `seasons`, `collection`, `actor` exist but unused in HTML |
| `/meta/watch-settings/1` | none | site config | countdown/skip-page/ad config (see §13) |
| `/wp/v2/posts` | none | blog | empty (`[]`) |

Example episode object (trimmed):

```json
{
  "id": 3437,
  "date": "2026-11-05T20:56:40",
  "slug": "%d9%85%d8%b3%d9%84%d8%b3%d9%84-…-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-41",
  "link": "https://www.qrmzi.tv/episode/…-41/",
  "title": { "rendered": "مسلسل الخليفة الحلقة 41" },
  "content": { "rendered": "<p>مشاهدة وتحميل الحلقة 41 …</p>\n" },
  "featured_media": 3233,
  "class_list": ["post-3437","episodes","type-episodes","status-publish","has-post-thumbnail","hentry"]
}
```

Gotchas:

* REST `slug` is **already percent-encoded** and matches the URL path — safe to build `link`.
* `content.rendered` is HTML; strip tags (and unescape entities).
* Featured media is **not** usable (401). Get images from HTML cards or the RSS `media:content`.
* There is no `orderby=modified` guarantee for "latest"; use `date desc` (matches the HTML feed).
* The REST API returns `Access-Control-Expose-Headers: X-WP-Total, X-WP-TotalPages` → use those headers.

---

## 12. RSS and sitemaps

`/feed/` (10 items, `language: ar`, `sy:updatePeriod: hourly`):

```xml
<item>
  <title>مسلسل الخليفة الحلقة 41</title>
  <link>https://www.qrmzi.tv/episode/…-41/</link>
  <pubDate>Thu, 05 Nov 2026 17:56:40 +0000</pubDate>
  <description><![CDATA[مشاهدة وتحميل …]]></description>
  <media:content medium="image" url="…/Halef-s2-Cover-470x264.jpg" width="470" height="264"/>
</item>
```

* RSS is a **cheap “what's new” poll** (10 items, includes a **landscape** thumbnail) — ideal for a
  “new episodes” badge or a lightweight startup sync.
* `series-sitemap.xml`: **191** `<loc>` entries with `lastmod` — the only cheap complete series index.
* `episodes-sitemap.xml` / `movies-sitemap.xml` exist; REST is better for those (paging + totals).
* Sitemap URLs are percent-encoded Arabic slugs and must be fetched as-is.

---

## 13. Site configuration endpoint (`/meta`)

`GET /wp-json/meta/watch-settings/1` returns the theme's player-gate configuration:

| Field | Value (6 Nov 2026) |
|---|---|
| `skip_type` | `countdown` |
| `skip_page_countdown_timer` | `10` |
| `watch_pages` | 2 external “watch” pages |
| `watch_domains` | 2 domains |
| `skip_pages` / `skip_domains` | 3 / 3 |
| `skip_page_main_video` | an `.mp4` ad asset on `cdn.foxpush.net` |
| `watch_ad_top/bot`, `skip_page_head/top_ad/bottom_ad` | empty |

This gate is **not active** on the sampled episode pages (no reference in HTML), but the app must treat
its appearance as a provider decision and **not circumvent it**. Do not call this endpoint just to build
the UI; it is documented here as an operational risk.

---

## 14. Arabic / RTL details for parsing

* UTF-8 throughout; Arabic text is **logical order** in the HTML (no visual-order tricks).
* Title pattern: `مسلسل <series> الحلقة <n>` — split on `الحلقة` to get the series name and number.
* Episode badge uses `حلقة` + Latin digits.
* Some titles contain Turkish/Latin characters (e.g. `Halef Köklerin Çağrısı‎`) inside Arabic text —
  keep Unicode, do not transliterate or strip diacritics.
* Invisible characters: the synopsis contains a `‎` (LRM) after the Turkish title — normalise whitespace.
* Arabic comma `،` appears in `.story` between `.episode-desc` and `.series-story` — split by **element**, not text.

---

## 15. Parsing pitfalls checklist

1. `article.post` is reused by home, search, movies, news → always scope to the page's grid container.
2. `data-src` may be unquoted → use an HTML5 parser (Jsoup), not regex.
3. `src` is a placeholder/base64 → never use it as the image URL.
4. `.navigation` exists but is **commented out** on the homepage → a selector will match, but the element is invisible.
5. Empty archive pages return **200** → detect the end by card count.
6. `/page/N/` for N beyond the end still returns 200 (empty) → same rule.
7. Search pages have **no `#load-post`** → do not require it.
8. Series page has **no season selector** → never assume the full episode history is present.
9. Episode page has **no prev/next** and **no servers list** → derive navigation from `#episodes-tab`.
10. `#episode-tab` IDs: the tabs are `#episodes-tab`, `#relevants-tab`, `#new-tab` (Bootstrap panes).
11. `.singleSeries` is reused on **movies** too → branch on the URL path.
12. Percent-encoded slugs must not be decoded/re-encoded.
13. `a[title]` has a trailing `قرمزي`; `div.title` does not.
14. Episode numbers are Latin; do not localise them to Arabic-Indic unless purely cosmetic.
15. The player chain can be 2 or 3 levels and its slug can differ from the qrmzi slug.

---

## 16. Suggested selector test suite (fixtures)

Snapshot the following into test fixtures and assert counts/fields:

| Fixture | Assertion |
|---|---|
| `home.html` | `#load-post > article.post` = 50; `.episodeNum` ≥ 49; `.ribbon` = 10 |
| `page2.html` | 50 cards; exactly 1 card linking to `/movies/`; no overlap with page 1 |
| `p20.html` | 20 cards; all present in REST page 19 |
| `page21` (empty) | 0 cards, HTTP 200 |
| `all_series.html` | `article.postEp` = 40; `div.pagination` present; 40 `/series/` links |
| `all_series_p2.html` | 40 cards; `span.current` = 2 |
| `series1.html` | `.singleSeries h1` text; `.story` non-empty; `article.postEp` = 41; cover ends in `Cover.jpg` |
| `big_series.html` | 37 cards; numbers 191…155; `نهاية الموسم` ribbon |
| `episode1.html` | `.getEmbed iframe[src]` starts with `https://w.anaplayer.online/albaplayer/`; `.episode-desc` non-empty; `#episodes-tab` = 41 |
| `ep_sample_*.html` | no `<ul class="serversList">`; one iframe |
| `search_حب` | 23 cards: 18 `/series/` + 5 `/movies/`; no `#load-post`; no pagination |
| `search_halef` | 0 cards; no error text |
| `movie1.html` | `.singleSeries h1`; `.story`; embedded iframe |
| `feed.xml` | 10 items; each has `media:content` landscape image |
| `series-sitemap.xml` | 191 `<loc>` |
| `wpjson_episodes.json` | `x-wp-total` = 921; `link` matches `slug` |

---

## 17. Minimal parsing pseudo-code

```kotlin
// Kotlin + Jsoup sketch (selectors verified above)
fun parseCards(doc: Document, scope: String): List<Card> =
    doc.select("$scope article.post, $scope article.postEp").mapNotNull { art ->
        val a = art.selectFirst("div.block-post > a[href]") ?: return@mapNotNull null
        val img = art.selectFirst("img[data-src]") ?: art.selectFirst("img[src]")
        val url = a.absUrl("href")
        Card(
            url = url,
            title = art.selectFirst("div.title")?.text()?.trim().orEmpty(),
            image = img?.attr("data-src")?.ifBlank { img.attr("src") },
            number = art.selectFirst("div.episodeNum span:nth-child(2)")?.text()?.toIntOrNull()
                ?: Regex("الحلقة\\s*(\\d+)").find(a.text())?.groupValues?.get(1)?.toIntOrNull(),
            ribbon = art.selectFirst("span.ribbon")?.text(),
            kind = when {
                "/episode/" in url -> Kind.EPISODE
                "/movies/" in url -> Kind.MOVIE
                "/series/" in url -> Kind.SERIES
                else -> Kind.UNKNOWN
            }
        )
    }
```

For the player, only the URL is needed:

```kotlin
val playerUrl = doc.selectFirst(".getEmbed iframe[src]")?.absUrl("src")
```

---

## 18. Reference request set (for regression testing)

```
GET /
GET /page/2/  /page/20/  /page/21/
GET /all-turkish-series/  /all-turkish-series/page/5/  /page/6/
GET /series/<slug>/                       (one Arabic slug, one ASCII slug)
GET /episode/<slug>/                      (sampled from pages 1, 2, 10)
GET /?s=حب   /?s=عشق   /?s=الخليفة   /?s=ask
GET /all-turkish-movies/  /movies/<slug>/
GET /feed/  /sitemap.xml  /series-sitemap.xml
GET /wp-json/wp/v2/episodes?per_page=50
GET /wp-json/wp/v2/movies?per_page=50
GET /wp-json/wp/v2/types
GET /wp-json/wp/v2/search?search=حب&per_page=100
```
