# Remote Control — Implementation & Test Plan

Every item below is implemented in code. **None of the runtime tests have been
executed yet**: this build host has no Android device, no emulator, no adb and no
WebView. The status column says exactly that, and the "how" column is the script
to run on the TX1-S (`tools/device_check.sh` automates the key sequence).

Legend: ✅ implemented + statically verified · 🟡 implemented, needs a device ·
⬜ not implemented.

---

## 1. Key map (what the remote does)

| Input | App (browsing) | Player |
|-------|----------------|--------|
| DPAD_UP / DOWN | move focus vertically, scroll content | provider UI (or native bar when open) |
| DPAD_LEFT / RIGHT | move focus horizontally, carousels scroll at the edge | provider seek/volume UI |
| DPAD_CENTER / ENTER | open card / activate button | provider play/pause |
| BACK | one level up; at root exits | native bar → fullscreen → player → exit |
| MENU | open Settings | switch provider server |
| INFO | — | toggle native control bar (play/pause, ±10 s, fullscreen, server, exit) |
| MEDIA_PLAY_PAUSE | — | native play/pause + transient bar |
| MEDIA_FF / REW | — | native ±10 s + transient bar |

The native bar exists because **a provider is not obliged to bind any key**. It
talks to the page through `jwplayer()` when present and falls back to the
`<video>` element. When neither exists the bar says "لا يوجد مشغّل" instead of
pretending to work.

## 2. Test matrix

| # | Area | Test | Implemented by | Static evidence | Device status |
|---|------|------|----------------|-----------------|---------------|
| 1 | Home navigation | vertical/horizontal focus, row scroll, CENTER opens detail, BACK returns focus to the same card | `HomeScreen`, `Carousel`, `CardGrid`, `Focus`, `Screen.restoreFocus` | focus code compiled; 50-card feed parsed | 🟡 not run |
| 2 | Episode carousel | LEFT/RIGHT inside a row scrolls it; UP/DOWN changes row; no dead ends | `Carousel`, `Focus.scrollIntoView` | compiled | 🟡 not run |
| 3 | Search | Arabic on-screen keyboard reachable and usable with the D-pad; query runs with CENTER | `SearchScreen` keyboard grid, `Host.repo.search` | parser test: `حب` → 23 results | 🟡 not run |
| 4 | Search results | result grid focusable, empty-query and empty-result states reachable | `SearchScreen` + `StateView` | compiled | 🟡 not run |
| 5 | Series pages | cover/synopsis/season list, "شاهد الآن", episode grid, favourite toggle | `SeriesScreen`, `EpisodesScreen` | parser tests (41 episodes, ribbons) | 🟡 not run |
| 6 | Episode selection | episode card → detail → play; next/previous episode navigation | `EpisodeScreen`, `Models.EpisodeInfo` | adjacency tests (next/prev, both edges) | 🟡 not run |
| 7 | WebView navigation | WebView receives D-pad after load; non-trusted links open in the browser instead of trapping the user | `PlayerActivity.client.shouldOverrideUrlLoading`, `web.requestFocus()` | trusted-host allowlist compiled | 🟡 not run |
| 8 | Embedded iframe controls | provider's own controls usable with the remote, or the native bar covers the gap | WebView focus + native bar (INFO) | — | 🟡 not run |
| 9 | Fullscreen playback | provider fullscreen (`onShowCustomView` / `webkitEnterFullscreen`) and native "ملء الشاشة" button; BACK leaves fullscreen before exiting | `WebChromeClient`, `toggleFullscreen()`, `onBackPressed` | compiled | 🟡 not run |
| 10 | BACK navigation | each layer peels correctly: error overlay → native bar → fullscreen → player → app | `PlayerActivity.onBackPressed`, `MainActivity.onBackPressed`, `Screen.onBack` | compiled | 🟡 not run |
| 11 | Focus visibility | white 2 dp ring / scale on every focusable; bar buttons use the same theme | `ThumbView`, `MediaCard`, `Ui.actionButton`, `SettingsScreen.SettingRow` | compiled | 🟡 not run |
| 12 | Focus predictability | first focusable focused after every load, previous focus restored on return | `Screen.ensureLoaded`, `restoreFocus`, `lastFocused` | compiled | 🟡 not run |
| 13 | Local data | add/remove a favourite, watch an episode ≥ 60 s, kill and reopen the app: Home shows «متابعة المشاهدة», the episode page offers «متابعة من …», Settings shows the history/search counts, clear rows empty them | `Store` (schema v3, self-healing `onOpen`), `Playback`, `PlayerActivity` heartbeat, `HomeScreen.onShow` | JVM test: resume/clock/query rules (114/114) | 🟡 not run |
| 14 | Ad filter | toggle «تصفية الإعلانات», play an episode, check that playback is unaffected and that «التشخيص» counts blocks; add `block example.com` to `qrmzi-filter.txt` and confirm the counter rises; press «استثناء آخر نطاق محجوب» and confirm it stops | `PlayerActivity.client.shouldInterceptRequest`, `Filter`, `FilterConfig` | filter matching/config/priority rules (JVM), essential host wins | 🟡 not run |
| 15 | Filter auto-disable | force a load failure while the filter refuses something: Settings must show «متوقفة تلقائيًا» and the automatic retry must load the page | `PlayerActivity.onPageError` + `blockedThisLoad`, `prefs.adFilterAutoDisabled` | code path compiled; cannot be triggered here | 🟡 not run |
| 16 | Diagnostics | open Settings → التشخيص, toggle «سجل مفصّل», play something, read the log, export it, clear it; URLs must show host/path only | `Diag`, `InfoDialog`, `SettingsScreen` | 9 JVM checks (ring bound, verbose gating, `safeUrl`, export, clear) | 🟡 not run |
| 17 | Network interruption | turn Wi-Fi off, open an episode (offline message, no crash), turn Wi-Fi on: one automatic retry; also browse back to a cached page while offline | `NetworkMonitor`, `PlayerActivity`, `Repo` stale fallback, `Http` retry | retry/offline policy (JVM) | 🟡 not run |
| 18 | Low memory | `adb shell am send-trim-memory tv.qrmzi.client RUNNING_LOW`, scroll Home again, check «التشخيص» trim counter and that `meminfo` PSS stays flat | `App.onTrimMemory`, `ImageLoader`, `Repo.trim` | compiled | 🟡 not run |

## 3. Android 10 device run

```bash
ADB="adb -s <box-ip>:5555" tools/device_check.sh --install
```

Manual pass, in order:

1. **Home** — press DOWN three times, RIGHT five times: the row scrolls, the
   focus ring is never clipped, UP returns to the top bar.
2. **Carousel** — RIGHT to the last visible card: the row scrolls by one card,
   not by a page; LEFT at the first card does not jump rows unexpectedly.
3. **Search** — top bar → البحث, type a title with the on-screen keyboard
   (CENTER on letters), then CENTER on «بحث». Results appear; BACK returns to the
   keyboard with the text intact.
4. **Series page** — open a series from Home, DOWN through the episode grid,
   CENTER on an episode, then BACK twice; focus must land back on that episode.
5. **Episode selection** — from an episode detail, use «الحلقة التالية» and
   «السابقة»; verify the page and the player both change.
6. **Player** — CENTER on «شاهد الآن»: the provider page loads, then:
   * press INFO → native bar appears with play/pause, ±10 s, time, server, exit;
   * LEFT/RIGHT inside the bar moves focus, CENTER activates;
   * BACK closes the bar (does not stop playback);
   * press the media play/pause key → playback toggles and a transient bar shows.
7. **Fullscreen** — use the provider's fullscreen control, then the native
   "ملء الشاشة" button; BACK must leave fullscreen first, then the player.
8. **BACK chain** — while a load error is shown, BACK must not exit the app
   immediately (the error overlay has focusable retry buttons).
9. **Memory** — `adb shell dumpsys meminfo tv.qrmzi.client` after two minutes of
   playback: PSS should be stable, not climbing every minute.
10. **Local data** — watch an episode for ~60 s, press BACK, return Home:
   «متابعة المشاهدة» shows it with progress; CENTER resumes from roughly where
   you left (or plays from the start with a "provider did not accept the seek"
   toast when the provider forbids seeking). Open the episode page: it offers
   «متابعة من …» and «من البداية». Force-stop the app and reopen: the same rows
   are still there. Add a favourite, restart, check **المفضلة**. Type two
   searches, reopen Search: the chips are there; long press removes one; the
   «مسح سجل البحث» chip and the Settings row clear them. `adb shell
   run-as tv.qrmzi.client ls -l databases` should show a `qrmzi.db` of only a
   few kilobytes.

## 4. When the provider does not cooperate

| Situation | Behaviour | Verified on device |
|-----------|-----------|--------------------|
| Provider binds its own keys | native bar is still available via INFO; nothing is intercepted while it is closed | 🟡 |
| Provider ignores arrow keys | native bar gives play/pause, ±10 s, fullscreen, server switch, exit | 🟡 |
| No `jwplayer()` and no `<video>` on the page | bar shows "لا يوجد مشغّل"; retry / server buttons remain in the error overlay | 🟡 |
| `setFullscreen()` unsupported | toast "المزوّد لا يوفّر ملء الشاشة برمجيًا"; provider's own button still works | 🟡 |
| Page hijacks BACK | activity-level `onBackPressed` wins first, so the user is never trapped | ✅ by construction |

## 5. Result of the automated part (this host)

* `tools/parsertest/run.sh` → **114/114 checks pass** (data layer, the
  resume/clock/query rules the remote drives, filter matching, diagnostics and
  the retry/offline policy).
* `tools/verify_apk.py build/qrmzi-tv.apk build/classes.jar` → **all static
  checks pass** (the player, its native bar and every screen are in the dex).
* No Android 10 runtime result exists yet; see `BUILD_AND_VERIFICATION.md` §6 for
  the full list of unverifiable items.
