package tv.qrmzi.client.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.Prefs
import tv.qrmzi.client.data.Repo
import tv.qrmzi.client.data.Store
import tv.qrmzi.client.ui.ImageLoader
import java.io.IOException

/** Everything a screen is allowed to ask of the activity. */
interface Host {
    val ctx: Context
    val repo: Repo
    val store: Store
    val prefs: Prefs
    val images: ImageLoader

    fun openCard(card: Card)
    fun openSeries(url: String)
    fun openEpisode(url: String, seriesUrl: String?, title: String)
    fun openEpisodes(seriesUrl: String, title: String)

    /**
     * Starts the player. [startMs] is the explicit resume point: `-1` means
     * "resume automatically when a reliable position exists", `0` means "from
     * the beginning" (used by the «من البداية» action).
     */
    fun play(pageUrl: String, title: String, seriesUrl: String?, startMs: Long = -1L)
    fun section(section: TopBar.Section)
    fun goBack()
    fun toast(message: String)
}

/**
 * Base class for every screen: owns its view, its coroutine scope and the
 * focus bookkeeping needed to restore the remote's position when the user
 * comes back from a detail page.
 */
abstract class Screen(protected val host: Host) {

    protected val ctx: Context get() = host.ctx
    protected val repo: Repo get() = host.repo
    protected val store: Store get() = host.store
    protected val prefs: Prefs get() = host.prefs
    protected val images: ImageLoader get() = host.images

    private val job = SupervisorJob()
    protected val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    private var built = false
    private var loaded = false
    private var lastFocused: View? = null

    lateinit var root: View
        private set

    /** Standard scrollable page scaffold. */
    class Scaffold(
        val frame: FrameLayout,
        val scroll: ScrollView,
        val column: LinearLayout,
        val state: StateView
    )

    fun view(): View {
        if (!built) {
            root = build()
            root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null) lastFocused = newFocus
            }
            built = true
            onBuilt()
        }
        return root
    }

    fun ensureLoaded() {
        if (!loaded) {
            loaded = true
            load()
        }
    }

    fun restoreFocus() {
        val remembered = lastFocused
        if (remembered != null && remembered.isShown && remembered.isFocusable) {
            if (remembered.requestFocus()) return
        }
        Focus.focusIn(root)
    }

    fun destroy() {
        scope.cancel()
        onDestroy()
    }

    protected abstract fun build(): View

    protected open fun onBuilt() {}

    /** Fetch the screen's data. Called exactly once per screen instance. */
    abstract fun load()

    /** Called whenever the screen becomes visible. */
    open fun onShow() {}

    open fun onHide() {}

    protected open fun onDestroy() {}

    /** Return true when the screen consumed BACK itself. */
    open fun onBack(): Boolean = false

    protected fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    /**
     * Runs [work] on the IO dispatcher, shows the loading state, then hands
     * the result to [render]. [render] is responsible for hiding the state or
     * showing an empty state.
     */
    protected fun <T> loadInto(
        state: StateView,
        loadingText: String = "جارٍ التحميل…",
        work: suspend () -> T,
        render: (T) -> Unit
    ) {
        state.loading(loadingText)
        scope.launch {
            try {
                val value = work()
                if (!isActive) return@launch
                render(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isActive) state.error(errorText(e)) {
                    loadInto(state, loadingText, work, render)
                }
            }
        }
    }

    protected fun errorText(error: Throwable): String = when (error) {
        is IOException ->
            "تعذّر الاتصال بموقع قرمزي.\nتحقّق من اتصال الإنترنت ثم أعد المحاولة."
        else -> "حدث خطأ غير متوقع.\n${error.message.orEmpty()}"
    }

    /** A page with a vertical scroll view, horizontal padding and a state overlay. */
    protected fun scaffold(
        topPaddingDp: Int = 104,
        bottomPaddingDp: Int = 48,
        horizontalPaddingDp: Int = 0
    ): Scaffold {
        val frame = FrameLayout(ctx).apply { setBackgroundColor(Theme.BG) }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(
                Theme.dp(ctx, horizontalPaddingDp),
                Theme.dp(ctx, topPaddingDp),
                Theme.dp(ctx, horizontalPaddingDp),
                Theme.dp(ctx, bottomPaddingDp)
            )
        }
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        frame.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val state = StateView(ctx)
        frame.addView(
            state,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return Scaffold(frame, scroll, column, state)
    }

    /** Big screen heading with optional subtitle, padded like the carousels. */
    protected fun pageHeader(title: String, subtitle: String? = null): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), 0,
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 18)
            )
        }
        box.addView(
            Ui.text(ctx, title, Theme.H1, Theme.TEXT).apply {
                typeface = Theme.medium
                maxLines = 1
            }
        )
        if (subtitle != null) {
            box.addView(
                Ui.text(ctx, subtitle, Theme.SMALL, Theme.TEXT_FAINT).apply {
                    maxLines = 1
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Theme.dp(ctx, 4) }
            )
        }
        return box
    }

    private var initialFocusDone = false

    /** Gives the D-pad a sensible starting point after the first render. */
    protected fun focusInitial(target: View) {
        if (initialFocusDone) return
        initialFocusDone = true
        target.post { if (target.isAttachedToWindow) Focus.focusIn(target) }
    }

    /** A grid inset by the standard screen padding. */
    protected fun newGrid(
        portrait: Boolean,
        cardWidthDp: Int,
        onLong: ((Card) -> Unit)? = null
    ): CardGrid = CardGrid(ctx).apply {
        this.portrait = portrait
        this.cardWidthDp = cardWidthDp
        columns = CardGrid.columnsFor(ctx, cardWidthDp)
        setPadding(
            Theme.dp(ctx, Theme.SCREEN_HPAD), 0,
            Theme.dp(ctx, Theme.SCREEN_HPAD), 0
        )
        onCardClick = { host.openCard(it) }
        onLongCardClick = onLong
    }

    /** A carousel wired to the host's navigation. */
    protected fun newCarousel(
        title: String,
        portrait: Boolean = true,
        cardWidthDp: Int = 156,
        onLong: ((Card) -> Unit)? = null
    ): Carousel = Carousel(ctx).apply {
        this.portrait = portrait
        this.cardWidthDp = cardWidthDp
        setTitle(title)
        onCardClick = { host.openCard(it) }
        onLongCardClick = onLong
    }

    /** Navigation row used by archive screens ("السابق" / "التالي"). */    protected fun pager(
        hasPrevious: Boolean,
        hasNext: Boolean,
        onPrevious: () -> Unit,
        onNext: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 10),
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 24)
            )
        }
        val previous = Ui.actionButton(ctx, "السابق", accent = false)
        previous.isEnabled = hasPrevious
        previous.alpha = if (hasPrevious) 1f else 0.35f
        previous.setOnClickListener { if (hasPrevious) onPrevious() }
        previous.isFocusable = hasPrevious

        val next = Ui.actionButton(ctx, "التالي", accent = true)
        next.isEnabled = hasNext
        next.alpha = if (hasNext) 1f else 0.35f
        next.setOnClickListener { if (hasNext) onNext() }
        next.isFocusable = hasNext

        row.addView(previous)
        (previous.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(ctx, 12)
        row.addView(next)
        return row
    }
}
