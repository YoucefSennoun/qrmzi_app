QRMZI TV — Dedicated Android TV Client

You are a senior Android TV developer, Kotlin engineer, WebView specialist and software architect.

Build a dedicated Android TV application for:

https://www.qrmzi.tv/

The application must be optimized for the Mortal TX1-S Android TV box running Android 10 with approximately 1 GB RAM.

1. Main objective

Create a Netflix-inspired Arabic Android TV experience dedicated exclusively to Qrmzi TV.

The application should make browsing and watching content on Qrmzi comfortable using an ordinary TV remote.

Do not build a generic multi-website launcher.

Do not invent a separate movie or series catalogue.

Qrmzi remains the content source.

2. Target hardware

- Device: Mortal TX1-S
- Android: Android 10
- Minimum SDK: 29
- RAM: 1 GB
- CPU ABI: Detect actual hardware architecture.
- Display: Television over HDMI.
- Input: IR or Bluetooth remote.
- Network: Wi-Fi/Ethernet.

Keep memory consumption low.

3. Technology

Use:

- Kotlin.
- Android native Views.
- AndroidX.
- MVVM.
- Android WebView.
- Room or lightweight local persistence.
- Gradle Kotlin DSL.
- Android TV-compatible navigation.

Avoid unnecessary libraries.

Do not introduce React Native, Flutter or heavyweight frameworks unless there is a compelling technical reason.

4. Qrmzi website integration

Primary domain:
https://www.qrmzi.tv/

The application must support:

- Loading the Qrmzi homepage.
- Browsing the latest episodes.
- Searching for series.
- Opening series pages.
- Opening episode pages.
- Navigating between episodes.
- Viewing episode descriptions.
- Playing embedded website videos when supported.
- Preserving website cookies and sessions when necessary.

Investigate the actual website structure before implementation.

Inspect:

- Homepage HTML.
- Search functionality.
- Series page structure.
- Episode page structure.
- Embedded iframe player.
- Navigation links.
- Responsive layout.
- JavaScript dependencies.
- Website behavior on Android WebView.

Do not assume that the website provides a public API.

Prefer the normal website interface and documented/publicly accessible functionality.

Do not bypass authentication, subscriptions, DRM, anti-bot protection or access restrictions.

Do not extract protected video URLs.

Do not download or redistribute copyrighted videos.

5. Application interface

Build a Netflix-inspired Arabic interface.

Visual design:

- Dark background.
- Red accent color.
- Arabic RTL support.
- Large episode thumbnails.
- Landscape content cards.
- Smooth focus animations.
- TV-readable typography.
- Proper spacing for remote navigation.

Main screens:

1. Home.
2. Latest episodes.
3. Series search.
4. Series details.
5. Episode selection.
6. Video playback.
7. Favorites.
8. Watch history.
9. Settings.

The home screen should display real content from Qrmzi where it can be obtained reliably and lawfully.

Do not hardcode episode lists.

6. D-pad support

Complete remote compatibility is mandatory.

Support:

- DPAD_UP
- DPAD_DOWN
- DPAD_LEFT
- DPAD_RIGHT
- DPAD_CENTER
- ENTER
- BACK
- MENU where applicable.

Requirements:

- Visible focus indicators.
- Correct focus restoration.
- No inaccessible interactive elements.
- Predictable navigation.
- Correct scrolling.
- Fullscreen player navigation.
- No touchscreen requirement.

WebView must support remote navigation inside the embedded player where the provider allows it.

Test with Android TV emulator and ADB key events.

7. Video playback

Initially preserve Qrmzi's original embedded player.

Investigate its actual compatibility with Android System WebView.

Requirements:

- Embedded iframe playback.
- HTML5 video support where compatible.
- Fullscreen support.
- Landscape playback.
- Correct BACK behavior.
- Network error handling.
- Audio playback.
- Remote-controlled play/pause where supported.

If the embedded player cannot work in WebView, report the exact technical reason and provide a compatible fallback.

Do not assume that Media3 can directly play the website's protected or undocumented streams.

Do not bypass DRM.

8. Advertising and filtering

Provide an optional lightweight filtering feature.

Requirements:

- Configurable domain blocklist.
- Whitelist.
- Enable/disable switch.
- No blocking of essential player resources.
- No interference with authentication or playback.
- No circumvention of access restrictions.

Do not promise complete ad removal.

If filtering breaks Qrmzi or its video provider, automatically allow users to disable it.

9. Memory optimization

The target has only 1 GB RAM.

Requirements:

- Use one primary WebView instance where possible.
- Avoid unnecessary background services.
- Limit image cache size.
- Lazy-load thumbnails.
- Release resources properly.
- Avoid memory leaks.
- Handle Android low-memory callbacks.
- Avoid loading unnecessary website pages.
- Keep startup fast.

10. Security

- Restrict WebView navigation to trusted destinations.
- Handle external links safely.
- Validate URLs.
- Avoid unsafe JavaScript bridges.
- Keep TLS verification enabled.
- Avoid storing sensitive information unnecessarily.
- Handle cookies securely.

Allow only the primary website and explicitly approved embedded player origins for internal navigation. Provide a safe external-browser fallback for other destinations.

11. Development workflow

Before writing code:

1. Inspect the existing repository.
2. Inspect Qrmzi's actual website structure.
3. Check Android SDK and Gradle.
4. Test WebView compatibility.
5. Investigate D-pad behavior.
6. Prepare ARCHITECTURE.md.
7. Prepare DEVELOPMENT_PLAN.md.

Implement in small phases.

After each phase:

- Compile.
- Run tests.
- Fix errors.
- Document progress.
- Do not continue if critical errors remain.

Final deliverable:
A working Android 10 APK tested for the Mortal TX1-S TV box.