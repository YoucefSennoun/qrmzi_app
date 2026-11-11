# QRMZI TV — Android WebView Compatibility Assessment

**Target:** Mortal TX1-S, Android 10 (API 29), ~1 GB RAM, 1080p HDMI, IR/Bluetooth remote
**Site:** `https://www.qrmzi.tv/` (WordPress) + 3-level third-party player chain
**Date:** 6 November 2026

**Status legend**

| Tag | Meaning |
|---|---|
| **VERIFIED** | Observed directly in HTTP responses / HTML / JS assets |
| **INFERRED** | Follows from verified evidence + documented platform behaviour, but not executed here |
| **UNVERIFIED** | Requires the real device; must be tested before it is relied on |

> **Important limitation:** this analysis environment has no Android SDK, no emulator, no `adb`,
> and no browser engine (see `QRMZI_ANALYSIS.md` §2). Everything about *runtime* behaviour below is
> INFERRED or UNVERIFIED and comes with a device test procedure in §12.

---

## 1. What the target WebView actually is

On Android 10 the “Android System WebView” is a **Chromium** runtime, updated independently of the OS
through the Play Store / WebView Update Service. On TV boxes it is frequently frozen at an older build
(Chrome 74–95 are all common on Android 10 TV firmware), and some boxes ship **no updatable WebView**.

Consequences:

* Minimum practical Chromium for this site: **≥ 55** (needed for `async/await` and stable
  `IntersectionObserver`; below that, lazy images never receive `src`).
* Recommended: **≥ 88** for `:is()` and `aspect-ratio` support. Older builds still render the site —
  the affected CSS is cosmetic (wordpress preset classes, a few flex `gap`s), not structural.
* HLS playback does **not** depend on the Chromium media stack alone: JW Player is configured with
  `androidhls:"true"`, so it hands the `m3u8` to the platform media pipeline (MediaPlayer/Codec2).
  That means **the box's H.264/AAC decoder decides** whether playback works, not Chrome's version.

Verify on the device:

```bash
adb shell dumpsys webviewupdate
adb shell pm list packages | grep -i webview
adb shell dumpsys package com.google.android.webview | grep -E "versionName|versionCode"
adb shell getprop ro.product.cpu.abilist
adb shell dumpsys media.player | head -40      # codecs
```

If the WebView is older than 55, the app must show a blocking “update Android System WebView” screen
(the site's lazy-image script will not run, and the player page uses modern JS).

---

## 2. What the site requires from the runtime (verified requirements)

| Requirement | Evidence | Minimum |
|---|---|---|
| JavaScript enabled | lazy-image loader, Bootstrap tabs, JW8 | any |
| `IntersectionObserver` | inline loader on every page | Chromium 51+ |
| `async/await`, arrow functions, `let/const` | inline scripts + theme JS | Chromium 55+ |
| `Array.prototype.includes` | inline loader | Chromium 47+ |
| CSS custom properties | `--site-font`, player theme vars | Chromium 49+ |
| CSS `:is()` | WP global styles | Chromium 88+ (cosmetic) |
| CSS `aspect-ratio` | WP preset variables | Chromium 88+ (cosmetic) |
| Flex `gap` | header/share/player notice CSS | Chromium 84+ (cosmetic) |
| DOM storage (`localStorage`) | player progress + `localstorage-slim` | must be enabled |
| Cookies | player (`file_id`, `aff`, `ref_url`, `vid`) | must be enabled |
| Iframes (2–3 nested) | player chain | must be allowed |
| `data:` images | 1×1 lazy placeholders | must be allowed |
| Native HLS (`m3u8`) | JW config `androidhls:"true"` | platform H.264 |
| Audio output | video element | platform AAC |
| Fullscreen video | JW uses `video.webkitEnterFullscreen` | WebView video fullscreen |
| Orientation | `fullscreenOrientationLock:"none"` | no lock needed (TV is landscape) |

No WebSockets, no Service Worker, no WebRTC, no WebGL, no WASM, no EME (DRM), no MSE requirement
(the native HLS path is used on Android).

---

## 3. Feature compatibility matrix

| # | Feature | Status | Finding / risk |
|---|---|---|---|
| 1 | Site HTML/CSS rendering | **VERIFIED (static)** | 252–308 KB pages, 180 KB inline CSS; renders in any Chromium ≥ 55 |
| 2 | Arabic RTL layout | **VERIFIED** | `dir="rtl" lang="ar"`, `float:right`; WebView handles RTL natively |
| 3 | Custom font (Kirmizi woff2, 68 KB) | **VERIFIED** | loads over HTTPS, 60-day cache; falls back to Arial |
| 4 | Lazy thumbnails | **VERIFIED (code)** | needs `IntersectionObserver`; no JS = no images (HTML `data-src` still present for native parsing) |
| 5 | Bootstrap tabs (`#episodes-tab` …) | **VERIFIED (markup)** | plain Bootstrap 3 tab JS, no exotic APIs |
| 6 | Search form | **VERIFIED** | plain GET to `/?s=`; works without JS |
| 7 | Nested player iframes | **VERIFIED (markup)** | depth 2–3, all HTTPS, **no XFO/CSP** |
| 8 | JW Player 8.34.5 | **VERIFIED (asset)** | 115 KB bundle, ES5-compatible output |
| 9 | HLS playback | **INFERRED** | native HLS path; depends on device decoder |
| 10 | DRM / Widevine | **VERIFIED absent** | no `drm`/`widevine` in config → no EME needed |
| 11 | Autoplay | **VERIFIED not used** | no `autostart`; `preload:"auto"` only |
| 12 | Video fullscreen | **INFERRED** | `webkitEnterFullscreen` path; needs runtime test |
| 13 | Player-chrome fullscreen | **INFERRED** | may use element fullscreen; needs `WebChromeClient.onShowCustomView` |
| 14 | Orientation lock | **VERIFIED** | `fullscreenOrientationLock:"none"` |
| 15 | D-pad focus inside player | **UNVERIFIED** | the main risk; see §6 |
| 16 | Remote play/pause | **UNVERIFIED** | depends on §15 |
| 17 | Seek / quality / CC menus | **UNVERIFIED** | JW menus are focusable in a desktop browser; TV focus order unknown |
| 18 | Cookies across iframes | **VERIFIED (set)** | need `CookieManager.flush()` to persist |
| 19 | `localStorage` progress | **VERIFIED (code)** | needs `setDomStorageEnabled(true)` |
| 20 | Anti-embed script | **VERIFIED (code)** | passes for normal cross-origin iframes; **fails if `sandbox` is used** |
| 21 | Anti-adblock overlay | **VERIFIED (code)** | appears if `window.cRAds` is undefined (i.e. if `/js/pop.js` is blocked) |
| 22 | Play-limit overlay | **VERIFIED (element)** | trigger unknown (cookie/play count); must not be bypassed |
| 23 | Popunders / new windows | **VERIFIED (scripts)** | `pgo99.com` on qrmzi, `tagivi`/`girellaharelip` in player; suppress windows natively |
| 24 | External share links | **VERIFIED** | `target="_blank"`; route to external browser |
| 25 | Image caching | **VERIFIED** | `max-age=5184000`; enable WebView HTTP cache for the player only |

---

## 4. Video playback — detailed assessment

### 4.1 The chain (verified)

```
qrmzi episode page  (top document)
 └─ iframe  w.anaplayer.online/albaplayer/<slug>      ← AlbaPlayer WordPress plugin
    └─ iframe  shooof.online/albaplayer/<slug>        ← sometimes skipped
       └─ iframe  cdnplus.sbs/embed-<id>.html         ← JW Player 8.34.5 → HLS
```

* Each hop is a **separate origin**. Native code can inject JS only into the **top document**
  (`www.qrmzi.tv`); the player documents are cross-origin and unreachable.
* All hosts return `200` with **no `X-Frame-Options` and no CSP** → framing is allowed.
* `cdnplus.sbs` returns `200` even with **no Referer** (the Referer only changes the `ref_url` cookie).

### 4.2 Media path

| Property | Value | Implication |
|---|---|---|
| Format | HLS (`m3u8`), single source | adaptive variants handled by the player/platform |
| `androidhls` | `true` | uses the **native** `<video>` + platform HLS, not hls.js |
| Codecs (expected) | H.264 + AAC in MPEG-TS/fMP4 | TX1-S (Amlogic-class SoC) decodes these in hardware |
| DRM | none | no `EME`/`requestMediaKeySystemAccess` needed |
| `preload` | `auto` | starts buffering immediately; watch memory on 1 GB |
| Quality labels | 360p → 1080p | 1080p on 1 GB is possible with hardware decode, but UI must not compete for RAM |
| Captions | empty `/srt/empty.srt` track | CC menu will be empty |
| Playback rates | 0.5×–3× | exposed in the player menu |

**No media playlist or segment URL is used by this analysis, and the app must not fetch them either.**
The app only loads the provider's own embed page and lets its player do the work.

### 4.3 Fullscreen

JW Player 8 on Android calls `video.webkitEnterFullscreen()` (verified in the bundle: 2 occurrences;
`webkitExitFullscreen` 3). That is the **native video fullscreen** path, which Android WebView
handles internally; `WebChromeClient.onShowCustomView` is used for *element* fullscreen
(`requestFullscreen`) or `webkitRequestFullScreen` on a container.

Therefore implement **both**:

1. `WebChromeClient.onShowCustomView(view, callback)` → swap in a full-screen `FrameLayout`,
   hide system UI (`SYSTEM_UI_FLAG_FULLSCREEN | IMMERSIVE_STICKY`), keep screen on;
   `onHideCustomView()` → restore.
2. `onBackPressed` → if a custom view is showing, hide it; else leave the player screen.
3. Do **not** force landscape (the config asks for no lock), but the player Activity should already be
   `sensorLandscape` on a TV.
4. Keep `android:hardwareAccelerated="true"`.

**Risk:** on some Android 10 TV firmwares the WebView's internal video fullscreen is disabled
(`WebSettings.setMediaPlaybackRequiresUserGesture` aside, the `VideoSurface` path can be missing).
If fullscreen fails, the fallback is to hide the page chrome with injected CSS and make the iframe fill
the window (already the plan, see §8.3) — fullscreen becomes "in-app fullscreen" without a mode switch.

### 4.4 Autoplay and audio

* No autoplay is configured, so `setMediaPlaybackRequiresUserGesture(true)` (the default) is fine and
  matches user expectation on TV: the user presses OK to start.
* Do not request audio focus in the app; the WebView/video element does it. Release focus when leaving
  the player screen (`audioManager.abandonAudioFocus` only if you requested it).
* Pause the WebView on `onPause()` (`webView.onPause()`, `pauseTimers()`), resume on `onResume()`.

### 4.5 Failure modes and what they mean

| Symptom | Likely cause | Correct response |
|---|---|---|
| Black player box, spinner forever | native HLS unsupported / network blocked | show retry + “open in browser”; **do not** try to fetch the stream |
| “Disable ADBlock plugin…” overlay | a first-party player script was blocked (`pop.js`) | reload with filtering off; never patch the page |
| “Upgrade your account…” box | provider play limit | explain it is the provider's limit; offer “open in browser” |
| Video plays but no sound | codec/audio track issue | report; try reload; no workaround |
| Video stops after N minutes | provider session/limit | reload; no circumvention |
| WebView renderer crash (`onRenderProcessGone`) | low memory on 1 GB | recreate WebView, restore URL, show “resume” |

---

## 5. Cookies, storage, and session

| Item | Evidence | Required WebView setting |
|---|---|---|
| Player cookies (`file_id`, `aff`, `ref_url`, `vid`) | set by `cdnplus.sbs` via jQuery cookie | `CookieManager.setAcceptCookie(true)` |
| Cross-site cookie use inside the chain | nested iframes on different domains | `CookieManager.setAcceptThirdPartyCookies(webView, true)` **only in the player WebView** |
| Persistence across restarts | 10-day cookie expiry | `CookieManager.flush()` on `onPause`/`onStop` |
| Progress | `localStorage` (`ls.set('tt…', …)`) | `setDomStorageEnabled(true)` |
| qrmzi session | none (no `Set-Cookie`) | nothing to preserve |
| Login | none | no login UI required |

Privacy note: third-party cookies are only needed for the provider chain; scope them to the player
WebView instance, clear them from Settings (“reset player data”), and never attach them to API calls.

---

## 6. D-pad and focus — the critical risk

### 6.1 How key events reach a WebView

Android delivers `KeyEvent`s to the focused `View`. `WebView` forwards them to Chromium, which routes
them to the focused **DOM element**. Inside an iframe, the iframe element itself must have focus for the
child browsing context to receive keys; inside *that* iframe, the next iframe (or the player) must be
focused. This chain works in desktop Chromium and in WebView, but it depends on the page making elements
focusable.

Evidence that helps:

* The qrmzi episode iframe is a plain `<iframe>` (focusable by default).
* JW8 sets `tabindex="-1"` on its `<video>` element (so the video itself is **not** in the tab order).
* JW8 installs a `keydown` listener and an **Enter/13 → activate** handler (verified in the bundle).
* No explicit arrow-key seek shortcuts were found (no `ArrowLeft`/`ArrowRight` strings; `keyCode` use is
  limited), so arrow keys are expected to perform **focus navigation** among the player's control
  elements, with Enter activating them.

### 6.2 What will probably happen on the TX1-S

| Key | Expected in player | Confidence |
|---|---|---|
| DPAD_CENTER / ENTER | activate focused control (play/pause, menu) | medium |
| DPAD_LEFT/RIGHT | move focus between control-bar items, or seek if the player captures arrows | low |
| DPAD_UP/DOWN | move focus / show control bar | low |
| BACK | exit fullscreen, then player screen | high (app-controlled) |
| MEDIA_PLAY_PAUSE / MEDIA_FF / MEDIA_REW | may be ignored by the page | low |
| MENU | nothing on the page; use for app menu only | high |

**If the deepest iframe never takes focus, the remote cannot control playback** — this is the single
biggest project risk, and it cannot be settled without the box.

### 6.3 Test protocol (on the TX1-S)

```bash
# 1. Does the page receive keys at all?
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell screencap -p /sdcard/k1.png && adb pull /sdcard/k1.png

# 2. Focus the player, then try activation
adb shell input keyevent KEYCODE_DPAD_CENTER
adb shell input keyevent KEYCODE_ENTER
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE

# 3. Check whether the player reacts (logcat + screenshot)
adb logcat -s chromium:* WebViewFactory:* AndroidRuntime:*
```

Pass criteria: at least **play/pause** and **fullscreen enter/exit** must be reachable with
D-pad only, and BACK must return to the episode screen without leaving a stuck fullscreen surface.

### 6.4 Mitigations, in order of preference

1. **Focus priming (same-document JS, allowed):** after `onPageFinished` of the qrmzi page, run
   `document.querySelector('.getEmbed iframe').focus()` and call `webView.requestFocus()`.
   Add a tiny CSS injection that makes `.getEmbed` fill the viewport so the iframe is large and visible.
2. **Key routing in the Activity:** override `dispatchKeyEvent`; for keys the WebView ignores, send a
   synthetic `KeyEvent` to the WebView with `webView.dispatchKeyEvent(...)`, and fall back to a native
   overlay (“Play/Pause”, “Reload”, “Fullscreen”, “Open in browser”).
3. **Direct-embed mode (opt-in fallback):** load the provider's embed URL as the **top document** so the
   deepest player is same-document and reachable via `evaluateJavascript("jwplayer().play()")`.
   Trade-offs: skips qrmzi's page-level ads/context, may change which provider ads run, and the chain
   still has one iframe. Offer it only when the default fails, make it visible in Settings, and keep it
   a *compatibility* mode — not an ad-avoidance feature.
4. **External browser fallback:** `Intent.ACTION_VIEW` to the episode URL. Always available, always
   correct, never a workaround.

**Do not** attempt to control the video by extracting its media URL into ExoPlayer/Media3 — that is
stream extraction and is explicitly out of scope.

---

## 7. Anti-embedding and anti-adblock behaviour (verified logic)

The deepest player page contains (verbatim logic, abbreviated):

```js
// blocks if: frameElement has sandbox | data: URL | document.domain throws |
//            frameElement is null with empty document.domain
// then: clears <video>.src, removes the player, shows
//       <h2>Disable sandbox to play the video</h2>
```

and:

```js
// #adbd overlay is visible unless window.cRAds is defined
// cRAds = true is set by /js/pop.js  (36 bytes: "var xRads=false; var cRAds=true;")
```

Implications for the WebView:

| Do | Don't |
|---|---|
| Load the page over normal HTTPS | Use `sandbox` on any iframe (or a `data:`/`srcdoc` document) |
| Keep `document.domain` untouched | Strip or rewrite the page's scripts |
| Allow `/js/pop.js`, `/js/jquery*.js`, `/js/localstorage-slim.js`, `/css/main.css`, `/player/jw8/jwplayer.js` | Blanket-block every script on the player host |
| Block only known ad/tracker hosts | Inject CSS/JS into the cross-origin player to “clean” it |

If the player page's own ad script is blocked, `cRAds` stays undefined → the overlay appears and the
video is removed after ~900 ms. This is a **hard compatibility constraint** on the ad filter design.

---

## 8. Recommended WebView configuration

### 8.1 Settings (player WebView)

```kotlin
settings.javaScriptEnabled = true
settings.domStorageEnabled = true            // player progress
settings.databaseEnabled = false
settings.allowFileAccess = false
settings.allowContentAccess = false
settings.allowFileAccessFromFileURLs = false
settings.allowUniversalAccessFromFileURLs = false
settings.javaScriptCanOpenWindowsAutomatically = false
settings.setSupportMultipleWindows(false)    // kill popunders
settings.mediaPlaybackRequiresUserGesture = true
settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
settings.cacheMode = WebSettings.LOAD_DEFAULT
settings.loadsImagesAutomatically = true
settings.blockNetworkImage = false
settings.userAgentString = MODERN_ANDROID_WEBVIEW_UA
settings.useWideViewPort = true
settings.loadWithOverviewMode = true
settings.builtInZoomControls = false
settings.displayZoomControls = false
settings.textZoom = 100
```

Not used: `addJavascriptInterface` (no bridge in the player), `setGeolocationEnabled`,
`setSaveFormData`, `setAllowFileAccess`.

### 8.2 Clients

* `WebViewClient`
  * `shouldOverrideUrlLoading`: allow `www.qrmzi.tv`, `w.anaplayer.online`, `shooof.online`, `cdnplus.sbs`;
    everything else → `Intent.ACTION_VIEW` (after `resolveActivity` check) or block.
  * `shouldInterceptRequest`: ad/tracker blocklist (see §9), **never** player assets or media hosts.
  * `onReceivedError` / `onReceivedHttpError`: main-frame error UI with retry.
  * `onRenderProcessGone`: recreate the WebView and restore the URL (mandatory on 1 GB).
  * `onPageFinished` (main frame only): inject the “player-only” CSS and focus the iframe.
* `WebChromeClient`
  * `onShowCustomView` / `onHideCustomView`: fullscreen.
  * `onCreateWindow`: return `false` (popups suppressed).
  * `onProgressChanged`: loading indicator.
  * Do **not** implement `onJsAlert`/`onJsConfirm` as auto-accept; show them as native dialogs (the site's
    favourite code uses `confirm()`, and the player's ad gate may use alerts).

### 8.3 “Player-only” CSS injection (top document only)

```css
#headerNav, footer, .singleInfo, .singleSeries, .watch-tabs-cnt,
.share-button-wrapper, #search { display: none !important; }
body { margin: 0 !important; background: #000 !important; overflow: hidden !important; }
main, .secContainer, .containers, .row { margin: 0 !important; padding: 0 !important; }
.getEmbed { position: fixed; inset: 0; z-index: 2147483000; min-height: 0 !important; }
.getEmbed .watch { margin: 0 !important; padding-bottom: 0 !important; height: 100vh !important; }
.getEmbed iframe { position: absolute; inset: 0; width: 100%; height: 100%; }
```

* This is **same-origin** manipulation of the page the app itself loaded (normal app behaviour), not
  tampering with the provider's cross-origin document.
* It also removes the 55 % padding hack, giving a true 16:9/full-height video area.
* Keep a visible native “Back / Reload / Fullscreen” overlay that auto-hides.

### 8.4 Viewport strategy for the player

The player iframe is width-100 % of its container, so the fastest path is:

```
webView.setInitialScale(100)
webView.settings.useWideViewPort = true
webView.settings.loadWithOverviewMode = true
```

with the injected CSS above forcing a full-viewport box. No page zoom games are needed.
Do **not** use a desktop UA to “get a bigger layout” — it changes nothing except `themeMobile`
(verified), and the mobile UA is the one the provider expects.

### 8.5 Security hardening

* TLS verification stays on; **never** override `onReceivedSslError`.
* Navigation allowlist as above; no wildcard `*`.
* No `file://`, no `content://`, no `javascript:` navigation from links.
* External links (share, premium, social) leave the app via `Intent` — never load them in the WebView.
* Clear WebView data from Settings: `CookieManager.removeAllCookies`, `WebStorage.deleteAllData`,
  `WebView.clearCache(true)`, and reset the per-episode resolved chain.
* Do not log URLs with tokens; the provider's embed IDs are not secrets but keep logs minimal.

---

## 9. Ad filtering as implemented (`data/Filter.kt`)

The design question was "how do we reduce the popunders the embed chain throws
without ever making the player fail?". The answer is a **sub-resource-only,
allowlist-first** filter, shipped on by default because it can always be turned
off in one press and it can disable itself.

**What is intercepted**

* only `WebViewClient.shouldInterceptRequest` for **sub-resources** of the
  player WebView;
* never the main frame (`isForMainFrame` is ignored — the page itself always
  loads), never navigations, never the native lists (they do not use WebView);
* no script rewriting, no CSS surgery, no `fetch`/XHR monkey-patching, no
  cross-origin frame poking — so nothing can desynchronise the player's own
  JavaScript.

**Allowlist (never blocked, cannot be overridden)**

`Filter.ESSENTIAL` plus anything `Http.isTrustedHost` accepts:

```
qrmzi.tv, anaplayer.online, shooof.online, cdnplus.sbs, cdnplus.org, brqz.online,
tagivi.com, girellaharelip.com,
jwplayer.com, jwpcdn.com, jwplatform.com, jwpsrv.com, jwplayer.org,
jsdelivr.net, cdnjs.cloudflare.com, bootstrapcdn.com,
googleapis.com, gstatic.com, jquery.com, fontawesome.com, cloudflareinsights.com
```

`tagivi.com` and `girellaharelip.com` are the player's own ad/tagging hosts. On
this site they are also part of the playback chain, so they are treated as
**essential** and left alone: a filter is not allowed to trade a few popups for
a broken stream. This is deliberately less aggressive than the earlier draft.

**Blocklist (built in, on by default)**

Known popunder/push, programmatic-display and analytics hosts only — roughly 55
domains, e.g. `pgo99.com`, `cdn.foxpush.net`, `popads.net`, `propellerads.net`,
`adsterra.com`, `doubleclick.net`, `googlesyndication.com`, `adnxs.com`,
`googletagmanager.com`, `google-analytics.com`, `hotjar.com`, `clarity.ms`,
`connect.facebook.net`, `taboola.com`, `outbrain.com`. Broad hosts (`google.com`,
`cloudflare.com`, `facebook.com`, `amazon.com`) are intentionally absent, and no
entry is a media/CDN host used by the player. The list is reviewed in
`QRMZI_ANALYSIS.md` terms: it only contains what the site loads for
advertising/tracking.

**Rules**

1. Order of precedence: essential/trusted → explicit `allow` → first `block`.
   A user `block` rule can therefore never shadow an essential host, and one
   `allow` line is enough to rescue a host that turns out to be required.
2. Matching is suffix-aware (`host == d || host.endsWith(".d")`) after
   normalisation (scheme/port/path/query/`www.`/`*.` stripped, hostname
   validated), so `notpgo99.com` is never matched and a typo is ignored instead
   of becoming a broad block.
3. Extra rules live in `prefs.filterUserBlock`/`filterUserAllow` (editable in
   Settings) and in the optional external file
   `Android/data/tv.qrmzi.client/files/qrmzi-filter.txt`
   (`allow <domain>`, `block <domain>`, bare domain = block, `#` comments). The
   file is re-read on every player open, so testing a rule needs no restart.
4. **Auto-disable is evidence-based, not time-based.** The player counts refused
   requests per load. Only if the main frame then fails *and* at least one
   request was refused during that same load does it persist
   `adFilterAutoDisabled`, explain itself in Settings («متوقفة تلقائيًا»), and
   retry once without the filter. A Wi-Fi drop, a 502 from the provider or a
   cache miss therefore never disables the filter.
5. Settings offers a one-tap whitelist for the last blocked host (recorded with
   only host/path, no query string) so a user can repair a false positive
   without editing files, plus "clear user blocks" / "clear allow list".
6. Copy says «تقليل الإعلانات من الشبكات المعروفة» ("reduce ads from known ad
   networks"), never "ad-free", and never suggests bypassing access controls.

The net effect: a native blocklist the user can see, extend, export and disable;
an allowlist the app cannot break; and a self-disabling failure path — which is
what the brief's "never break playback intentionally, do not bypass DRM/auth"
requirement allows.

---

## 10. Memory and performance on 1 GB

The numbers below are **estimates from the site's own weights** (home page
HTML, the embed chain and what the player loads), not measurements from the box —
this host has no Android runtime. They are useful to size the budget, not as a
pass criterion; `BUILD_AND_VERIFICATION.md` §6 lists the real measurements to
take with `dumpsys meminfo`.

| Component | Estimated cost | Mitigation in this app |
|---|---|---|
| App process (native UI, SQLite, images) | 60–110 MB | framework views only, bitmaps RGB_565 at row size, LRU ≈ `maxMemory/8` capped at 14 MB, 32 MB disk cache, paginated feeds |
| WebView renderer (qrmzi page + up to 3 iframes) | 120–220 MB | **one** WebView, created and destroyed with `PlayerActivity`; no hidden WebView behind a screen |
| JW player + HLS buffers | 30–80 MB | provider-side; playback only while the player activity is foreground; `onPause`/`pauseTimers` + `destroy()` on finish |
| Ad/popunder scripts | 10–40 MB + new windows | conservative sub-resource filter, `setSupportMultipleWindows(false)`, popups not opened |
| **Total worst case** | **~350–450 MB** | viable on 1 GB only because the native UI behind the player is idle |

Measures implemented (all in code, see `ARCHITECTURE.md` §4 and §6.2):

* **One WebView instance**, owned by `PlayerActivity`; `onPause` + `pauseTimers`
  when backgrounded, `destroy()` on finish, renderer death handled by
  `onRenderProcessGone` instead of a process crash.
* **Browsing never uses the WebView** and never fetches the full homepage HTML
  per screen: `Repo` parses once and caches the model, screens paginate 40–50
  cards per request.
* `onTrimMemory(≥ RUNNING_LOW)` → halve the bitmap LRU, drop the oldest cached
  pages; `onLowMemory`/`TRIM_MEMORY_COMPLETE` → clear both. Only `App` decides
  this; screens have no memory logic of their own.
* **No background services, no polling timers, no periodic feed refresh.** The
  only repeating work is the player's 10 s progress heartbeat (while the player
  is resumed) and the native bar's 1 s state poll (while it is open).
* Image pipeline: one loader, `RGB_565`, `inSampleSize` decode, 3 threads,
  duplicate requests deduplicated, disk cap enforced opportunistically, trimmed
  on low memory.
* `android:largeHeap="false"`, `setOffscreenPreRaster(false)`,
  `setCacheMode(LOAD_DEFAULT)`, DOM storage on, no form/password storage.
* Provider-chain resolution is **one page fetch** (`Repo.playerSources`), with
  one transient HTTP retry and stale-page fallback, so the slowest first-play
  step happens once per episode and is cached (24 entries).

Expected UX on the TX1-S: native browsing should feel instant after the first
screen (cached models + disk images); first play of an episode takes ~2–5 s to
resolve and initialise the embed chain, then re-opening the same episode is a
cache hit. These expectations are exactly what the device test in §12 must
confirm or falsify.

---

## 11. Mobile vs desktop, and why it does not matter for the player

* Verified: the only server-side difference is `themeMobile="1"` vs `themeMobile=""`; markup is otherwise
  identical (252,684 vs 252,683 bytes, first diff at that variable).
* The viewport meta is `width=device-width` in both cases.
* CSS is fully responsive (29 breakpoints, base 5 cards/row, portrait 150 % box).
* Therefore: pick **one** modern Android WebView UA and keep it stable. The app's native UI is unaffected.

---

## 12. Device verification plan (required before Phase 5 is accepted)

Run on the actual TX1-S (or an Android 10 TV emulator with a comparable WebView).

| # | Test | Command / action | Pass condition |
|---|---|---|---|
| 1 | WebView version | `adb shell dumpsys webviewupdate` | Chromium ≥ 55 (≥ 88 preferred) |
| 2 | HLS decode | open an episode, press play | video + audio, stable ≥ 60 s |
| 3 | 720p/1080p | switch quality in the player menu | no crash, no audio desync |
| 4 | Fullscreen | press the player fullscreen control | fills screen; BACK exits |
| 5 | D-pad controls | arrows + OK | play/pause reachable; focus visible |
| 6 | Media keys | `input keyevent 85 / 90 / 89` | play/pause or explicit “unsupported” note |
| 7 | BACK chain | BACK from player → episode → list | no stuck fullscreen, no leaked audio |
| 8 | Cookies persist | kill app, reopen, play | no repeated gate; `flush()` works |
| 9 | Ad overlay | enable filter, reload | **no** “Disable ADBlock” overlay (or auto-disabled) |
| 10 | Play limit | play many episodes | overlay explained, not bypassed |
| 11 | Low memory | `adb shell am send-trim-memory <pkg> RUNNING_LOW` | cache trimmed, UI alive |
| 12 | Renderer kill | `adb shell am kill <webview process>` (or low-memory run) | app recreates player, no crash |
| 13 | Popunders | play 5 episodes | no new windows, no external browser jumps |
| 14 | RTL | inspect player + native screens | correct direction, no clipped Arabic |
| 15 | Network loss | airplane mode during play | error UI + retry, no ANR |
| 16 | Cold start | `adb shell am start -W` | native home < 2 s on the box |

Capture evidence (`screencap`, `logcat`, `dumpsys meminfo`) for each; attach to the phase report.

---

## 13. Fallback ladder (in order)

1. **Default:** qrmzi episode page in a single WebView, chrome hidden, iframe focused, fullscreen custom view.
2. **No key control:** native overlay buttons that dispatch synthetic key events; if still unresponsive →
   direct-embed mode (user-visible compatibility setting).
3. **No native HLS:** retry once; show the provider error; offer “open in browser”. Do **not** switch to
   stream extraction.
4. **No WebView at all (too old):** blocking update screen with instructions; the app cannot function.
5. **Provider changed the chain:** re-resolve iframes at runtime (max 3 hops); if unknown host → do not
   auto-trust, show the URL host and ask the user to open it externally.

---

## 14. Go / No-Go criteria

Proceed to full implementation only if tests 1–7 pass on the TX1-S (or a close emulator):

* WebView ≥ 55,
* native HLS plays with audio,
* fullscreen enters/exits,
* D-pad can at least **play/pause** and reach fullscreen,
* BACK returns cleanly.

If tests 2 or 5 fail, the project must **not** fall back to media extraction; it should ship as a
native browser/catalogue with an “open in browser” player, and the limitation must be reported to the
user with the exact technical reason.

---

## Appendix — what the shipped client actually does

This appendix maps the assessment above to the code in
`app/src/main/java/tv/qrmzi/client/player/PlayerActivity.kt`, including the two
places where the implementation deliberately departs from the recommendation.

### A.1 WebView settings (as shipped)

| Setting | Value | Why |
|---|---|---|
| `javaScriptEnabled` | true | the provider player is JS-only |
| `domStorageEnabled` | true | player progress + gate state in `localStorage` |
| `databaseEnabled` | false | WebSQL is deprecated and unused, saving a DB per origin |
| `mediaPlaybackRequiresUserGesture` | **false** | *deviation*: the provider player starts itself after its own countdown. On a TV there is no touch gesture, and the activity launch + the user pressing «شاهد الآن» is the gesture. Autoplay is still not forced by us — we never call `play()` except from a remote key or the native bar |
| `javaScriptCanOpenWindowsAutomatically` | false | no popunders |
| `setSupportZoom` / zoom controls | false | remote-only device |
| `mixedContentMode` | `NEVER_ALLOW` | the whole chain is HTTPS |
| `cacheMode` | `LOAD_DEFAULT` | honour HTTP cache, keep RAM low |
| `allowFileAccess` / `allowContentAccess` | false | no local file surface |
| geolocation | disabled | not needed |
| JavaScript bridge | **none** | `addJavascriptInterface` is never called; all JS is one-shot `evaluateJavascript` |
| third-party cookies | enabled **only** in the player WebView | required by the provider chain; flushed on pause |

### A.2 Iframe fullscreen — both paths are wired

| Path | Trigger | Handled by |
|---|---|---|
| Native video fullscreen (`video.webkitEnterFullscreen()`, what JW Player 8 calls on Android) | provider fullscreen button | WebView internal fullscreen; activity stays landscape + immersive |
| Element fullscreen (`requestFullscreen` / `webkitRequestFullScreen`) | provider fullscreen button or the native bar | `WebChromeClient.onShowCustomView` → the view is added full-bleed over the WebView; `onHideCustomView` restores it |
| Provider has no fullscreen API | native bar "ملء الشاشة" | `jwplayer().setFullscreen(true)`, then `video.requestFullscreen()`; if both are missing a toast says the provider does not offer it |

BACK order is fixed: **fullscreen → native controls → player → app**. A user can
never be trapped in a fullscreen view or in the native bar.

### A.3 Remote keys inside the player

The WebView is the primary receiver (it gets focus after every load). Because a
provider may bind no keys at all, a native bar is available on INFO and after any
media key:

* play/pause, ±10 s, position/duration, fullscreen, another server, exit;
* it reads state through `jwplayer().getState/getPosition/getDuration` with a
  `<video>` fallback, so it never claims to control something that is not there;
* it uses the provider's API only — it never inspects or extracts stream URLs;
* the same reader runs every 10 s in the background and on pause/exit so
  «متابعة المشاهدة» has a real position; only `position`/`duration` numbers are
  stored, never a media URL.

### A.4 Renderer death (1 GB devices)

`onRenderProcessGone` is handled: the app does not crash, the player shows an
out-of-memory explanation, and "إعادة المحاولة" restarts the player activity with
the same episode (a destroyed renderer cannot be reloaded in place).

### A.5 Status of the runtime checks

Sections 4, 6, 12 and 14 are **still unexecuted** here (no device, emulator or
adb on the build host). They are listed as pending in `REMOTE_TEST_PLAN.md` and
`BUILD_AND_VERIFICATION.md` §6, with the exact commands to run them.
