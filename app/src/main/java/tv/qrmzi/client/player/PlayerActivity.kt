package tv.qrmzi.client.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tv.qrmzi.client.App
import tv.qrmzi.client.data.Diag
import tv.qrmzi.client.data.Filter
import tv.qrmzi.client.data.FilterConfig
import tv.qrmzi.client.data.NavPolicy
import tv.qrmzi.client.data.NetworkMonitor
import tv.qrmzi.client.data.Playback
import tv.qrmzi.client.ui.Theme
import tv.qrmzi.client.ui.Ui
import java.io.ByteArrayInputStream

/**
 * Fullscreen playback of the provider's own embedded player.
 *
 * The site exposes a public `<iframe>` chain (qrmzi → anaplayer → shooof →
 * cdnplus). We resolve the deepest *public* player page and load it in a
 * WebView. No stream URL is ever read, and DRM/authentication is left entirely
 * to the provider.
 *
 * D-pad: the WebView keeps its own focus handling (JW Player reacts to the
 * centre button and arrow keys); media keys are translated into
 * `jwplayer()` / `<video>` calls, MENU switches to the provider's server
 * picker, BACK exits.
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_PAGE = "page_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SERIES = "series_url"

        /** Resume point in milliseconds; 0 means "from the beginning". */
        const val EXTRA_START_MS = "start_ms"

        /** How often the player reports its position to the store while playing. */
        private const val PERSIST_INTERVAL_MS = 10_000L

        /**
         * Player state as `position|duration|state`. JW Player is present on the
         * provider page; the `<video>` fallback covers pages that use HTML5
         * video directly. Only values are read — no stream URL is extracted.
         */
        private const val JS_STATE =
            "(function(){try{if(window.jwplayer){var p=jwplayer();" +
                "if(p&&p.getState){return [p.getPosition(),p.getDuration(),p.getState()].join('|');}}" +
                "catch(e){}var v=document.querySelector('video');" +
                "if(v){return [(v.currentTime||0),(v.duration||0),(v.paused?'paused':'playing')].join('|');}" +
                "return '0|0|none';})()"

        /**
         * Fullscreen is a provider capability, not a right: we ask the player
         * API first and the HTML5 API second, and report when neither works.
         */
        private const val JS_FULLSCREEN =
            "(function(){try{if(window.jwplayer){var p=jwplayer();" +
                "if(p&&p.setFullscreen){p.setFullscreen(true);return 'jw';}}}" +
                "catch(e){}try{var v=document.querySelector('video');" +
                "if(v&&v.requestFullscreen){v.requestFullscreen();return 'html5';}}catch(e){}" +
                "return 'none';})()"

        /**
         * Best-effort resume. Returns `true` when a player accepted the seek, so
         * the caller can retry a few times while the provider finishes booting.
         * It never reads a stream URL and never bypasses the provider's player.
         */
        private fun jsSeekTo(ms: Long): String =
            "(function(t){try{if(window.jwplayer){var p=jwplayer();" +
                "if(p&&p.seek){p.seek(t);return 'true';}}}catch(e){}" +
                "try{var v=document.querySelector('video');" +
                "if(v){v.currentTime=t;return 'true';}}catch(e){}" +
                "return 'false';})(${ms.coerceAtLeast(0L)})"
    }

    private val app: App get() = application as App
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var overlay: FrameLayout
    private lateinit var loadingBox: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var errorRetry: TextView
    private lateinit var errorServer: TextView
    private lateinit var hint: TextView

    private lateinit var controls: LinearLayout
    private lateinit var playPause: TextView
    private lateinit var positionLabel: TextView
    private lateinit var progress: ProgressBar
    private var controlsShown = false
    private var playbackState = "paused"

    private var pageUrl: String = ""
    private var title: String = ""
    private var wrapperUrl: String? = null
    private var currentUrl: String? = null

    // Resume + progress persistence. Only values the player itself reports are
    // kept, and only while the page has told us a real duration.
    private var startMs: Long = 0L
    private var lastPosMs: Long = -1L
    private var lastDurMs: Long = -1L
    private var lastPersistAt: Long = 0L
    private var resumeSettled: Boolean = false
    private var resumeTries: Int = 0

    private var filterActive = false
    private var filterRules = Filter.Config()
    private var blockedThisLoad = 0
    /**
     * Set when a failing load was retried without filtering. The automatic
     * disable is only persisted once that unfiltered load actually succeeds,
     * so a provider-side error is not mistaken for "the filter broke it".
     */
    private var filterRelaxed = false
    private var autoOffPersisted = false
    private val network by lazy { NetworkMonitor(this) }
    private var networkWatch: (() -> Unit)? = null
    private var triedWrapper = false
    private var triedPage = false
    private var inError = false
    private var destroyed = false

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var rendererGone = false

    // -------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageUrl = intent.getStringExtra(EXTRA_PAGE).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        startMs = intent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L)
        if (pageUrl.isEmpty()) {
            finish()
            return
        }

        Diag.setVerbose(app.prefs.verboseLogging)
        Diag.count("player.open")
        Diag.log("player", "open ${Diag.safeUrl(pageUrl)}")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Theme.immersive(this)

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        hint = buildHint()
        root.addView(
            hint,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP)
        )

        // Native fallback controls sit above the WebView but below the
        // loading/error overlay, so a broken provider page never hides them.
        controls = buildControls()
        root.addView(
            controls,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
        )

        overlay = buildOverlay()
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        configure(web.settings)
        web.webViewClient = client
        web.webChromeClient = chrome

        resolve()
    }

    private fun configure(settings: WebSettings) = settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = false
        mediaPlaybackRequiresUserGesture = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        loadWithOverviewMode = true
        useWideViewPort = true
        cacheMode = WebSettings.LOAD_DEFAULT
        // Keep the offscreen copy out of the renderer's memory: one fullscreen
        // player is all this box can afford.
        setOffscreenPreRaster(false)
        setSaveFormData(false)
        setSavePassword(false)
        setSupportMultipleWindows(false)
        // The whole chain is HTTPS; never downgrade.
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setGeolocationEnabled(false)
        allowFileAccess = false
        allowContentAccess = false
    }

    override fun onResume() {
        super.onResume()
        Theme.hideSystemUi(this)
        web.onResume()
    }

    override fun onPause() {
        persistProgress()
        pausePlayback()
        hideControls()
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        job.cancel()
        main.removeCallbacksAndMessages(null)
        stopNetworkWatch()
        hideCustomView()
        runCatching {
            web.stopLoading()
            web.clearHistory()
            web.loadUrl("about:blank")
            root.removeView(web)
            web.destroy()
        }
        Diag.count("player.close")
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Never clear the WebView cache while a video is on screen: it would
        // force the provider to re-download its player. App handles bitmaps and
        // page caches; here we only record the event.
        Diag.count("player.trim")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Theme.hideSystemUi(this)
    }

    // ---------------------------------------------------------------- resolve
    private fun resolve() {
        inError = false
        blockedThisLoad = 0
        filterRelaxed = false
        showLoading("جارٍ تجهيز المشغّل…")
        hint.text = title
        filterRules = activeRules()
        scope.launch {
            val sources = runCatching { app.repo.playerSources(pageUrl) }.getOrNull()
            if (destroyed) return@launch
            wrapperUrl = sources?.wrapper
            filterActive = app.prefs.adFilterEnabled &&
                !app.prefs.adFilterAutoDisabled &&
                filterRules.hasBlocks
            Diag.log(
                "player",
                "sources wrapper=${Diag.safeHost(wrapperUrl)} deep=${Diag.safeHost(sources?.deep)}"
            )
            val target = sources?.deep ?: wrapperUrl ?: pageUrl
            load(target)
        }
    }

    /** Built-in list + user lists + the optional config file. */
    private fun activeRules(): Filter.Config = Filter.build(
        app.prefs.filterUserBlock,
        app.prefs.filterUserAllow,
        FilterConfig.read(this)
    )

    private fun load(url: String) {
        if (destroyed) return
        currentUrl = url
        inError = false
        blockedThisLoad = 0
        Diag.count("player.load")
        Diag.log("player", "load ${Diag.safeUrl(url)}")
        showLoading("جارٍ تحميل المشغّل…")
        web.loadUrl(url, mapOf("Referer" to pageUrl))
        web.requestFocus()
    }

    private var client = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url?.toString().orEmpty()
            if (!request.isForMainFrame) return false
            if (isAllowedPage(url)) return false

            // Anything else is the ad scripts trying to move the browser, or an
            // outbound link. Cancelling here is what stops the pop-ups: the
            // page can keep playing and no second app/window is opened.
            val host = NavPolicy.hostOf(url)
            Diag.count("popup.block")
            Diag.log("player", "blocked navigation ${host ?: "?"}")

            if (NavPolicy.externalAllowed(url, request.hasGesture())) {
                Diag.count("popup.external")
                openExternal(url)
            }
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (!filterActive) return null
            // The page itself is never touched: only its third-party
            // subresources are candidates.
            if (request.isForMainFrame) return null
            val host = request.url?.host?.lowercase() ?: return null
            val rule = Filter.match(host, filterRules) ?: return null

            blockedThisLoad++
            Diag.count("filter.block")
            if (app.prefs.lastBlockedHost != host) app.prefs.lastBlockedHost = host
            Diag.log("filter", "blocked $host (rule $rule)")

            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0))
            )
        }

        /**
         * A renderer kill on a 1 GB box must not take the app down with it. The
         * WebView cannot be reused after this, so retry restarts the activity.
         */
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            if (destroyed) return true
            rendererGone = true
            stopNetworkWatch()
            Diag.count("player.rendererGone")
            Diag.important("player", "renderer gone (didCrash=${detail.didCrash()})")
            hideOverlay()
            showError("توقّف محرّك العرض (غالبًا بسبب ضغط الذاكرة).")
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (destroyed || inError) return
            Diag.count("player.ready")
            Diag.log("player", "ready ${Diag.safeUrl(url)} blocked=$blockedThisLoad")
            // The page only loaded once filtering was relaxed: now it is fair to
            // remember that for the next session.
            if (filterRelaxed && !autoOffPersisted) {
                filterRelaxed = false
                autoOffPersisted = true
                app.prefs.adFilterAutoDisabled = true
                Diag.count("filter.autodisabled")
                Diag.important(
                    "filter",
                    "auto-disabled: the page loaded only with filtering off"
                )
            }
            hideOverlay()
            view.requestFocus()
            if (!controlsShown) {
                hint.text =
                    "$title   •   INFO: أدوات التحكم   •   MENU: سيرفر آخر"
            }
            scheduleHintFade()
            maybeResume()
            startHeartbeat()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (destroyed || !request.isForMainFrame) return
            Diag.count("player.error")
            Diag.log("player", "error ${error.description} on ${Diag.safeUrl(currentUrl)}")
            if (!network.isOnline()) {
                showOffline()
                return
            }
            handleFailure("تعذّر تحميل المشغّل (${error.description}).")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (destroyed || !request.isForMainFrame) return
            if (errorResponse.statusCode >= 400) {
                Diag.count("player.httpError")
                handleFailure("رفض المزوّد الطلب (${errorResponse.statusCode}).")
            }
        }
    }

    private val chrome = object : WebChromeClient() {

        /**
         * `window.open()` from an ad script must never create a WebView or a
         * browser window. Multi-window is already off; this makes the refusal
         * explicit and counted in diagnostics.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            Diag.count("popup.window")
            Diag.log("player", "refused window.open (gesture=$isUserGesture)")
            return false
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            hint.visibility = View.GONE
            Theme.hideSystemUi(this@PlayerActivity)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        customView = null
        root.removeView(view)
        val callback = customCallback
        customCallback = null
        callback?.onCustomViewHidden()
        Theme.hideSystemUi(this)
    }

    // -------------------------------------------------------------- failures
    private fun handleFailure(message: String) {
        if (destroyed) return
        stopNetworkWatch()

        // Filtering may have broken the page: retry this load without it. The
        // automatic disable is saved only when that unfiltered retry actually
        // loads (see onPageFinished), so an unrelated provider outage cannot
        // switch the filter off for good.
        if (filterActive && blockedThisLoad > 0) {
            filterActive = false
            filterRelaxed = true
            Diag.count("filter.relaxed")
            Diag.important(
                "filter",
                "retry without filter after $blockedThisLoad blocked request(s) on ${Diag.safeHost(currentUrl)}"
            )
            toast("قد تكون التصفية سبّبت المشكلة، أُعيدت المحاولة بدونها")
            currentUrl?.let { load(it) }
            return
        }
        if (filterRelaxed) {
            // The unfiltered retry failed too: filtering was not the cause, so
            // keep it enabled for the next playback.
            filterRelaxed = false
            Diag.count("filter.innocent")
        }

        val wrapper = wrapperUrl
        if (!triedWrapper && wrapper != null && wrapper != currentUrl) {
            triedWrapper = true
            load(wrapper)
            return
        }
        if (!triedPage && currentUrl != pageUrl) {
            triedPage = true
            load(pageUrl)
            return
        }

        showError(message)
    }

    private fun retry() {
        stopNetworkWatch()
        // A dead renderer cannot be revived in place: start a fresh activity.
        if (rendererGone) {
            restartPlayer()
            return
        }
        triedWrapper = false
        triedPage = false
        blockedThisLoad = 0
        filterRules = activeRules()
        filterActive = app.prefs.adFilterEnabled &&
            !app.prefs.adFilterAutoDisabled &&
            filterRules.hasBlocks
        resolve()
    }

    /**
     * Offline is not the provider's fault: keep the error screen, promise a
     * retry and register a one-shot callback for when connectivity returns.
     */
    private fun showOffline() {
        stopNetworkWatch()
        Diag.count("player.offline")
        showError("لا يوجد اتصال بالإنترنت. سيُعاد المحاولة تلقائيًا عند عودة الشبكة.")
        networkWatch = network.watchAvailable {
            networkWatch = null
            if (!destroyed && inError) {
                toast("عاد الاتصال، جارٍ إعادة المحاولة")
                retry()
            }
        }
    }

    private fun stopNetworkWatch() {
        networkWatch?.invoke()
        networkWatch = null
    }

    private fun restartPlayer() {
        stopNetworkWatch()
        Diag.count("player.restart")
        // Carry the furthest known position into the fresh activity.
        val position = if (lastPosMs > startMs) lastPosMs else startMs
        val again = Intent(intent).putExtra(EXTRA_START_MS, position)
        finish()
        startActivity(again)
    }

    private fun switchServer() {
        if (rendererGone) {
            restartPlayer()
            return
        }
        stopNetworkWatch()
        val wrapper = wrapperUrl
        when {
            wrapper != null && wrapper != currentUrl -> {
                load(wrapper)
                toast("صفحة اختيار السيرفر من المزوّد")
            }
            currentUrl != pageUrl -> {
                load(pageUrl)
                toast("إعادة التحميل من صفحة قرمزي")
            }
            else -> toast("لا يوجد سيرفر بديل متاح")
        }
    }

    // ------------------------------------------------------------------ input
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlay()
                    if (!controlsShown) showControls(takeFocus = false) else syncState()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(10)
                    if (!controlsShown) showControls(takeFocus = false) else syncState()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekBy(-10)
                    if (!controlsShown) showControls(takeFocus = false) else syncState()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    switchServer()
                    return true
                }
                KeyEvent.KEYCODE_INFO -> {
                    toggleControls()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
            return
        }
        // BACK always peels the topmost layer: native controls, then the player.
        if (controlsShown) {
            hideControls()
            return
        }
        persistProgress()
        pausePlayback()
        stopNetworkWatch()
        finish()
    }

    private fun togglePlay() {
        web.evaluateJavascript(
            "(function(){" +
                "try{if(window.jwplayer){var p=jwplayer();" +
                "if(p&&p.getState){if(p.getState()==='playing'){p.pause();return 'paused';}" +
                "p.play();return 'playing';}}}catch(e){}" +
                "var v=document.querySelector('video');" +
                "if(v){if(v.paused){v.play();return 'playing';}v.pause();return 'paused';}" +
                "return 'none';})()",
            null
        )
    }

    private fun seekBy(seconds: Int) {
        web.evaluateJavascript(
            "(function(){var d=$seconds;" +
                "try{if(window.jwplayer){var p=jwplayer();" +
                "if(p&&p.getPosition){p.seek(Math.max(0,p.getPosition()+d));return;}}}catch(e){}" +
                "var v=document.querySelector('video');" +
                "if(v){v.currentTime=Math.max(0,v.currentTime+d);}})()",
            null
        )
    }

    private fun pausePlayback() {
        if (!::web.isInitialized) return
        web.evaluateJavascript(
            "(function(){try{if(window.jwplayer){var p=jwplayer();if(p&&p.pause)p.pause();}}catch(e){}" +
                "var vs=document.querySelectorAll('video');" +
                "for(var i=0;i<vs.length;i++){try{vs[i].pause();}catch(e){}}})()",
            null
        )
    }

    // ------------------------------------------------------------------- hush
    private fun isAllowedPage(url: String): Boolean = NavPolicy.mainFrameAllowed(url)

    private fun openExternal(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            toast("تعذّر فتح هذا الرابط")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast("لا يوجد متصفح على هذا الجهاز")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------------- ui
    private fun buildHint(): TextView = TextView(this).apply {
        text = title
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Theme.TEXT)
        typeface = Theme.medium
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(
            Theme.dp(this@PlayerActivity, 32), Theme.dp(this@PlayerActivity, 20),
            Theme.dp(this@PlayerActivity, 32), Theme.dp(this@PlayerActivity, 20)
        )
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xCC000000.toInt(), 0x00000000)
        )
        visibility = View.VISIBLE
    }

    private fun scheduleHintFade() {
        hint.removeCallbacks(fadeHint)
        hint.alpha = 1f
        hint.visibility = View.VISIBLE
        hint.postDelayed(fadeHint, 5000)
    }

    private val fadeHint = Runnable {
        hint.animate().alpha(0f).setDuration(400).withEndAction {
            if (!inError) hint.visibility = View.GONE
        }.start()
    }

    // ------------------------------------------------- native fallback controls
    /**
     * The provider's own player UI is the primary control surface, but a remote
     * may not reach every control inside an iframe (and a provider may not bind
     * the media keys at all). This bar is the native fallback: play/pause, ±10 s,
     * fullscreen, another server and exit, all reachable with the D-pad.
     */
    private fun buildControls(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(
                Theme.dp(this@PlayerActivity, 40), Theme.dp(this@PlayerActivity, 18),
                Theme.dp(this@PlayerActivity, 40), Theme.dp(this@PlayerActivity, 26)
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF2070709.toInt(), 0x00000000)
            )
        }

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Theme.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Theme.dp(this@PlayerActivity, 4)
            )
        }
        bar.addView(progress)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Theme.dp(this@PlayerActivity, 14), 0, 0)
        }

        fun add(view: TextView, endMargin: Int = 10) {
            row.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Theme.dp(this@PlayerActivity, endMargin) }
            )
        }

        playPause = Ui.actionButton(this, "تشغيل", accent = true)
        playPause.setOnClickListener {
            togglePlay()
            main.postDelayed({ if (!destroyed) syncState() }, 350)
        }
        add(playPause, 14)

        val back10 = Ui.actionButton(this, "−10 ث", accent = false)
        back10.setOnClickListener {
            seekBy(-10)
            main.postDelayed({ if (!destroyed) syncState() }, 350)
        }
        add(back10)

        val forward10 = Ui.actionButton(this, "+10 ث", accent = false)
        forward10.setOnClickListener {
            seekBy(10)
            main.postDelayed({ if (!destroyed) syncState() }, 350)
        }
        add(forward10, 22)

        positionLabel = Ui.text(
            this,
            "0:00 / 0:00",
            Theme.SMALL,
            Theme.TEXT_DIM,
            maxLines = 1
        ).apply {
            typeface = Theme.medium
            gravity = Gravity.CENTER_VERTICAL
        }
        add(positionLabel, 22)

        val fullscreen = Ui.actionButton(this, "ملء الشاشة", accent = false)
        fullscreen.setOnClickListener { toggleFullscreen() }
        add(fullscreen)

        val server = Ui.actionButton(this, "سيرفر آخر", accent = false)
        server.setOnClickListener { switchServer() }
        add(server)

        val exit = Ui.actionButton(this, "خروج", accent = false)
        exit.setOnClickListener {
            persistProgress()
            pausePlayback()
            finish()
        }
        add(exit, 0)

        bar.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        bar.isFocusable = false
        return bar
    }

    private fun showControls(takeFocus: Boolean = true) {
        if (destroyed) return
        controlsShown = true
        controls.visibility = View.VISIBLE
        hint.visibility = View.GONE
        playPause.text = if (playbackState == "playing") "إيقاف" else "تشغيل"
        syncState()
        main.removeCallbacks(pollControls)
        main.post(pollControls)
        main.removeCallbacks(autoHideControls)
        if (takeFocus) {
            playPause.requestFocus()
        } else {
            // A media key should not steal focus from the provider's UI.
            main.postDelayed(autoHideControls, 4000)
        }
    }

    private fun hideControls() {
        if (!controlsShown) return
        controlsShown = false
        controls.visibility = View.GONE
        main.removeCallbacks(pollControls)
        main.removeCallbacks(autoHideControls)
        if (!destroyed && customView == null) web.requestFocus()
    }

    private fun toggleControls() {
        if (controlsShown) hideControls() else showControls(takeFocus = true)
    }

    private val autoHideControls = Runnable {
        // Only an unfocused bar hides itself; if the user is on it, leave it be.
        if (controlsShown && !playPause.hasFocus() && !controls.hasFocus()) {
            hideControls()
        }
    }

    /** Reads position/duration/state from JW Player or a plain <video>. */
    private fun syncState() {
        if (destroyed || inError) return
        web.evaluateJavascript(JS_STATE) { raw: String? ->
            if (destroyed) return@evaluateJavascript
            val value = raw?.trim()?.trim('"') ?: return@evaluateJavascript
            val parts = value.split('|')
            if (parts.size < 3) return@evaluateJavascript
            val pos = parts[0].toDoubleOrNull() ?: 0.0
            val dur = parts[1].toDoubleOrNull() ?: 0.0
            val state = parts[2]
            playbackState = state
            playPause.text = if (state == "playing") "إيقاف" else "تشغيل"
            positionLabel.text = if (dur > 0) {
                "${clock(pos)} / ${clock(dur)}"
            } else {
                if (state == "none") "لا يوجد مشغّل" else "—"
            }
            progress.progress = if (dur > 0) {
                ((pos / dur) * 1000).toInt().coerceIn(0, 1000)
            } else {
                0
            }

            // Cache what the player reported so leaving the screen can persist
            // synchronously, and write it at most once per interval.
            if (dur > 0) {
                lastPosMs = (pos * 1000).toLong()
                lastDurMs = (dur * 1000).toLong()
                val now = SystemClock.elapsedRealtime()
                if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                    lastPersistAt = now
                    persistProgress()
                }
            }
        }
    }

    /**
     * Writes the last reported position to the local history. It only stores
     * values the player actually reported (see `Playback.shouldStore`), so an
     * unloaded or broken player can never create a fake resume point.
     */
    private fun persistProgress() {
        val pos = lastPosMs
        val dur = lastDurMs
        if (pos < 0L || dur <= 0L) return
        runCatching { app.store.updatePosition(pageUrl, pos, dur) }
    }

    /**
     * Asks the provider's player to continue from the saved position. The
     * provider boots asynchronously, so this retries a few times and quietly
     * gives up: a resume that does not work must never block playback.
     */
    private fun maybeResume() {
        if (destroyed || resumeSettled) return
        if (startMs < Playback.MIN_RESUME_MS) {
            resumeSettled = true
            return
        }
        resumeTries++
        web.evaluateJavascript(jsSeekTo(startMs)) { raw: String? ->
            if (destroyed) return@evaluateJavascript
            if (raw?.contains("true") == true) {
                resumeSettled = true
                toast("متابعة من ${Playback.clock(startMs)}")
                scheduleHintFade()
            } else if (resumeTries < 3) {
                main.postDelayed({ maybeResume() }, 2_500)
            } else {
                resumeSettled = true
            }
        }
    }

    private var heartbeatStarted = false

    /** Reads the player state even while the native control bar is hidden. */
    private fun startHeartbeat() {
        if (heartbeatStarted || destroyed) return
        heartbeatStarted = true
        main.postDelayed(heartbeat, PERSIST_INTERVAL_MS)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyed) return
            syncState()
            main.postDelayed(this, PERSIST_INTERVAL_MS)
        }
    }

    private val pollControls = object : Runnable {
        override fun run() {
            if (destroyed || !controlsShown) return
            syncState()
            main.postDelayed(this, 1000)
        }
    }

    private fun clock(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Best effort: JW Player API first, then the HTML5 fullscreen API. */
    private fun toggleFullscreen() {
        if (customView != null) {
            hideCustomView()
            return
        }
        web.evaluateJavascript(JS_FULLSCREEN) { raw: String? ->
            if (!destroyed && raw != null && raw.contains("none")) {
                toast("المزوّد لا يوفّر ملء الشاشة برمجيًا")
            }
        }
    }

    private fun buildOverlay(): FrameLayout {
        val frame = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xE6070709.toInt())
            isClickable = true
            isFocusable = false
        }

        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val spinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Theme.ACCENT)
        }
        loadingBox.addView(
            spinner,
            LinearLayout.LayoutParams(Theme.dp(this, 46), Theme.dp(this, 46))
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        loadingText = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Theme.TEXT_DIM)
            typeface = Theme.regular
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        loadingBox.addView(
            loadingText,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = Theme.dp(this@PlayerActivity, 20)
                }
        )
        frame.addView(
            loadingBox,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        )

        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Theme.TEXT)
            typeface = Theme.regular
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 5
        }
        errorBox.addView(
            errorText,
            LinearLayout.LayoutParams(Theme.dp(this@PlayerActivity, 560), WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
        }
        errorRetry = Ui.actionButton(this, "إعادة المحاولة")
        errorRetry.setOnClickListener { retry() }
        errorServer = Ui.actionButton(this, "سيرفر آخر", accent = false)
        errorServer.setOnClickListener { switchServer() }
        actions.addView(
            errorRetry,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .apply { marginEnd = Theme.dp(this@PlayerActivity, 12) }
        )
        actions.addView(
            errorServer,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .apply { marginEnd = Theme.dp(this@PlayerActivity, 12) }
        )
        errorBox.addView(
            actions,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = Theme.dp(this@PlayerActivity, 24)
                }
        )
        frame.addView(
            errorBox,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        )
        return frame
    }

    private fun showLoading(message: String) {
        if (destroyed) return
        inError = false
        overlay.visibility = View.VISIBLE
        loadingBox.visibility = View.VISIBLE
        errorBox.visibility = View.GONE
        loadingText.text = message
        hint.visibility = View.VISIBLE
        hint.alpha = 1f
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        loadingBox.visibility = View.GONE
        errorBox.visibility = View.GONE
    }

    private fun showError(message: String) {
        if (destroyed) return
        inError = true
        overlay.visibility = View.VISIBLE
        loadingBox.visibility = View.GONE
        errorBox.visibility = View.VISIBLE
        errorText.text = "$message\n\n" +
            "يمكنك إعادة المحاولة، أو تجربة سيرفر آخر من المزوّد، أو مشاهدة الحلقة في المتصفح."
        hint.visibility = View.GONE
        errorRetry.post {
            if (!destroyed && errorBox.isShown) errorRetry.requestFocus()
        }
    }
}
