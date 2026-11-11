package tv.qrmzi.client.ui.screens

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.data.Diag
import tv.qrmzi.client.data.Filter
import tv.qrmzi.client.data.FilterConfig
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.InfoDialog
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.Theme
import tv.qrmzi.client.ui.Ui
import java.io.File
import java.util.Locale

/**
 * "الإعدادات": the handful of preferences that actually matter on a 1 GB TV
 * box — ad filtering, animation cost, image quality and local data.
 *
 * Every destructive action is a plain D-pad-reachable row and reports its
 * result with a toast, so nothing here needs a confirm dialog or a touchscreen.
 */
class SettingsScreen(host: Host) : Screen(host) {

    private lateinit var page: Scaffold
    private lateinit var rows: LinearLayout

    private var adRow: SettingRow? = null
    private var filterFileRow: SettingRow? = null
    private var excludeRow: SettingRow? = null
    private var allowClearRow: SettingRow? = null
    private var blockClearRow: SettingRow? = null
    private var motionRow: SettingRow? = null
    private var qualityRow: SettingRow? = null
    private var cacheRow: SettingRow? = null
    private var historyRow: SettingRow? = null
    private var searchRow: SettingRow? = null
    private var favoritesRow: SettingRow? = null
    private var verboseRow: SettingRow? = null
    private var logRow: SettingRow? = null
    private var exportRow: SettingRow? = null
    private var clearLogRow: SettingRow? = null
    private lateinit var filterStats: TextView
    private lateinit var diagStats: TextView

    override fun build(): View {
        Theme.animationsEnabled = !prefs.reducedMotion
        images.quality = prefs.imageQuality

        page = scaffold(topPaddingDp = 100)
        page.column.addView(
            pageHeader("الإعدادات", "التطبيق على هذا الجهاز"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), 0,
                Theme.dp(ctx, Theme.SCREEN_HPAD), 0
            )
        }
        page.column.addView(
            rows,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        adRow = row(
            "تصفية الإعلانات",
            "حجب نطاقات إعلانية معروفة ونوافذ منبثقة داخل المشغّل. لا يمسّ الفيديو ولا تسجيل الدخول."
        ) {
            prefs.adFilterEnabled = !prefs.adFilterEnabled
            if (prefs.adFilterEnabled) prefs.adFilterAutoDisabled = false
            refresh()
        }

        filterFileRow = row(
            "ملف قواعد التصفية",
            "ملف نصي اختياري لإضافة حجب أو سماح. اضغط للتفاصيل والمسار."
        ) {
            showFilterFile()
        }

        excludeRow = row(
            "استثناء آخر نطاق محجوب",
            "إضافة النطاق الأخير إلى قائمة السماح إذا تبيّن أنه ضروري للمشغّل."
        ) {
            allowLastBlocked()
        }

        allowClearRow = row(
            "مسح استثناءات التصفية",
            "إزالة كل النطاقات التي سمحت بها يدويًا."
        ) {
            if (prefs.filterUserAllow.isBlank()) {
                host.toast("لا توجد استثناءات")
            } else {
                prefs.filterUserAllow = ""
                host.toast("تم مسح الاستثناءات")
            }
            refresh()
        }

        blockClearRow = row(
            "مسح الحجب الإضافي",
            "إزالة النطاقات التي أضفتها يدويًا. تبقى القائمة الافتراضية."
        ) {
            if (prefs.filterUserBlock.isBlank()) {
                host.toast("لا توجد قواعد إضافية")
            } else {
                prefs.filterUserBlock = ""
                host.toast("تم مسح الحجب الإضافي")
            }
            refresh()
        }

        filterStats = Ui.text(ctx, "", Theme.TINY, Theme.TEXT_FAINT, maxLines = 4).apply {
            setLineSpacing(Theme.dpf(ctx, 3f), 1f)
            setPadding(0, Theme.dp(ctx, 8), 0, Theme.dp(ctx, 4))
        }
        rows.addView(
            filterStats,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        motionRow = row(
            "تقليل الحركة",
            "إيقاف تكبير البطاقات عند التنقل بالريموت لتسريع الواجهة."
        ) {
            prefs.reducedMotion = !prefs.reducedMotion
            Theme.animationsEnabled = !prefs.reducedMotion
            refresh()
        }

        qualityRow = row(
            "جودة الصور",
            "«اقتصادية» تحمّل صورًا أصغر وتستهلك ذاكرة أقل."
        ) {
            prefs.imageQuality = if (prefs.imageQuality == 0) 1 else 0
            images.quality = prefs.imageQuality
            refresh()
        }

        section("البيانات المحلية")

        cacheRow = row(
            "مسح ذاكرة الصور",
            "حذف الصور المخزّنة وإعادة تحميلها عند الحاجة."
        ) {
            images.clearDisk()
            repo.invalidateAll()
            host.toast("تم مسح ذاكرة الصور")
            refresh()
        }

        historyRow = row(
            "مسح سجل المشاهدة",
            "إزالة كل الحلقات والأفلام التي فتحتها من هذا الجهاز، مع مواضع المتابعة."
        ) {
            store.clearHistory()
            host.toast("تم مسح السجل")
            refresh()
        }

        searchRow = row(
            "مسح سجل البحث",
            "إزالة الكلمات التي بحثت عنها من هذا الجهاز."
        ) {
            store.clearSearches()
            host.toast("تم مسح سجل البحث")
            refresh()
        }

        favoritesRow = row(
            "مسح المفضلة",
            "إزالة كل العناصر المحفوظة في قائمتك."
        ) {
            store.favorites().forEach { store.removeFavorite(it.url) }
            host.toast("تم مسح المفضلة")
            refresh()
        }

        section("التشخيص")
        verboseRow = row(
            "سجل مفصّل",
            "تسجيل أحداث الصفحات والمشغّل والتصفية في حلقة صغيرة داخل الذاكرة."
        ) {
            prefs.verboseLogging = !prefs.verboseLogging
            Diag.setVerbose(prefs.verboseLogging)
            refresh()
        }

        logRow = row(
            "عرض السجل",
            "آخر الأحداث المحفوظة في هذه الجلسة. اضغط للعرض."
        ) {
            showLog()
        }

        exportRow = row(
            "تصدير السجل",
            "حفظ نسخة نصية في مساحة التطبيق لمشاركتها عند الحاجة."
        ) {
            exportLog()
        }

        clearLogRow = row(
            "مسح السجل",
            "إفراغ السجل والعدادات. لا يؤثر على المشاهدة."
        ) {
            Diag.clear()
            host.toast("تم مسح السجل")
            refresh()
        }

        diagStats = Ui.text(ctx, "", Theme.TINY, Theme.TEXT_FAINT, maxLines = 4).apply {
            setLineSpacing(Theme.dpf(ctx, 3f), 1f)
            setPadding(0, Theme.dp(ctx, 8), 0, Theme.dp(ctx, 4))
        }
        rows.addView(
            diagStats,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        section("حول التطبيق")
        rows.addView(
            Ui.text(
                ctx,
                "قرمزي TV — عميل غير رسمي مخصّص لموقع qrmzi.tv.\n" +
                    "كل المحتوى والصور ملك لأصحابها ويُعرض مباشرة من الموقع. " +
                    "التطبيق لا ينزّل الفيديوهات ولا يتجاوز أي قيود وصول أو حماية.\n" +
                    "الإصدار 1.0 (Android 10+)",
                Theme.SMALL,
                Theme.TEXT_FAINT,
                maxLines = 8
            ).apply {
                setLineSpacing(Theme.dpf(ctx, 5f), 1f)
                setPadding(0, Theme.dp(ctx, 6), 0, Theme.dp(ctx, 26))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return page.frame
    }

    override fun load() {
        page.state.hide()
        refresh()
        rows.post { tv.qrmzi.client.ui.Focus.focusIn(rows) }
    }

    override fun onShow() {
        refresh()
    }

    private fun refresh() {
        val ad = adRow ?: return
        ad.value = when {
            !prefs.adFilterEnabled -> "متوقفة"
            prefs.adFilterAutoDisabled -> "متوقفة تلقائيًا"
            else -> "مفعّلة"
        }
        ad.highlight = prefs.adFilterEnabled && !prefs.adFilterAutoDisabled

        motionRow?.value = if (prefs.reducedMotion) "مفعّل" else "متوقف"
        motionRow?.highlight = prefs.reducedMotion

        qualityRow?.value = if (prefs.imageQuality == 0) "اقتصادية" else "عالية"
        qualityRow?.highlight = prefs.imageQuality == 1

        cacheRow?.value = sizeLabel(images.diskSizeBytes())

        val history = store.countHistory()
        historyRow?.value = if (history == 0) "فارغ" else "$history عنصرًا"
        historyRow?.highlight = false

        val searches = store.countSearches()
        searchRow?.value = if (searches == 0) "فارغ" else "$searches كلمة"
        searchRow?.highlight = false

        val favorites = store.countFavorites()
        favoritesRow?.value = if (favorites == 0) "فارغ" else "$favorites عنصرًا"
        favoritesRow?.highlight = false

        val fileText = FilterConfig.read(ctx)
        val cfg = Filter.build(prefs.filterUserBlock, prefs.filterUserAllow, fileText)
        val fileRules = Filter.parseConfig(fileText)
        val fileBlocks = fileRules.count { !it.allow && Filter.normalize(it.domain) != null }
        val fileAllows = fileRules.count { it.allow && Filter.normalize(it.domain) != null }
        val userBlocks = Filter.parseList(prefs.filterUserBlock).count { Filter.normalize(it) != null }
        val userAllows = Filter.parseList(prefs.filterUserAllow).count { Filter.normalize(it) != null }

        filterFileRow?.value = when {
            fileRules.isEmpty() -> "افتراضي"
            else -> "${fileRules.size} قاعدة"
        }
        excludeRow?.value = prefs.lastBlockedHost
        allowClearRow?.value = if (fileAllows + userAllows == 0) "" else "${fileAllows + userAllows} نطاق"
        blockClearRow?.value = if (fileBlocks + userBlocks == 0) "" else "${fileBlocks + userBlocks} نطاق"

        filterStats.text =
            "محجوب منذ التشغيل: ${Diag.counter("filter.block")} طلبًا  •  " +
                "نوافذ محجوبة: ${Diag.counter("popup.block")}\n" +
                "قواعد فعّالة: ${cfg.blockCount} حجب / ${cfg.allowCount} سماح\n" +
                "المشغّل: ${Diag.counter("player.open")} محاولة، " +
                "${Diag.counter("player.error")} خطأ، " +
                "${Diag.counter("filter.autodisabled")} تعطيل تلقائي"

        verboseRow?.value = if (prefs.verboseLogging) "مفعّل" else "متوقف"
        verboseRow?.highlight = prefs.verboseLogging
        logRow?.value = "${Diag.lines().size} سطرًا"

        diagStats.text =
            "ذاكرة الصور: ${images.memoryUsageKb()} كيلوبايت، " +
                "${Diag.counter("img.loaded")} صورة محمّلة، ${Diag.counter("img.hit")} من الذاكرة\n" +
                "تخفيضات الذاكرة: ${Diag.counter("mem.trim")} • " +
                "أعطال الشبكة: ${Diag.counter("http.fail")} • " +
                "نسخ قديمة: ${Diag.counter("repo.stale")}"
    }

    private fun showFilterFile() {
        val file = FilterConfig.file(ctx)
        val rules = Filter.parseConfig(FilterConfig.read(ctx))
        val blocks = rules.count { !it.allow }
        val allows = rules.count { it.allow }
        InfoDialog.show(
            ctx,
            "ملف قواعد التصفية",
            "المسار:\n${file.absolutePath}\n\n" +
                "كل سطر: «block نطاق»، أو «allow نطاق»، أو نطاق وحده للحجب.\n" +
                "علامة # تبدأ تعليقًا.\n\n" +
                "القواعد في الملف الآن: $blocks حجب، $allows سماح.\n" +
                "يُقرأ الملف عند فتح المشغّل. نطاقات قرمزي والمشغّل و JW Player " +
                "أساسية ولا تُحجب أبدًا حتى لو كُتبت في الملف.",
            listOf(
                "إنشاء نموذج" to {
                    val created = FilterConfig.ensureSample(ctx)
                    host.toast(
                        if (created != null) "الملف جاهز: ${created.name}"
                        else "تعذّر إنشاء الملف"
                    )
                    refresh()
                },
                "إعادة قراءة" to {
                    host.toast("قواعد الملف: ${FilterConfig.ruleCount(ctx)}")
                    refresh()
                }
            )
        )
    }

    /**
     * One-tap whitelist editing: if a blocked host turns out to be essential,
     * the remote can fix it without a keyboard.
     */
    private fun allowLastBlocked() {
        val suspect = Filter.normalize(prefs.lastBlockedHost)
        if (suspect == null) {
            host.toast("لا يوجد نطاق محجوب مؤخرًا")
            return
        }
        val allowed = Filter.parseList(prefs.filterUserAllow)
            .mapNotNull { Filter.normalize(it) }
            .toMutableList()
        if (!allowed.contains(suspect)) allowed.add(suspect)
        prefs.filterUserAllow = allowed.joinToString("\n")
        prefs.filterUserBlock = Filter.parseList(prefs.filterUserBlock)
            .mapNotNull { Filter.normalize(it) }
            .filter { it != suspect }
            .joinToString("\n")
        prefs.lastBlockedHost = ""
        prefs.adFilterAutoDisabled = false
        Diag.important("filter", "user allowed $suspect")
        host.toast("أُضيف $suspect إلى قائمة السماح")
        refresh()
    }

    private fun showLog() {
        val lines = Diag.lines()
        val body = if (lines.isEmpty()) {
            "لا توجد سطور بعد. فعّل «سجل مفصّل» ثم افتح المشغّل أو تصفّح الموقع."
        } else {
            lines.joinToString("\n")
        }
        InfoDialog.show(ctx, "سجل التشخيص", body)
    }

    private fun exportLog() {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val file = File(dir, "qrmzi-diag.txt")
        if (Diag.export(file)) {
            host.toast("حُفظ السجل: ${file.name}")
            InfoDialog.show(
                ctx,
                "تم تصدير السجل",
                "المسار:\n${file.absolutePath}\n\nالحجم: ${file.length()} بايت."
            )
        } else {
            host.toast("تعذّر حفظ السجل")
        }
        refresh()
    }

    private fun sizeLabel(bytes: Long): String {
        if (bytes < 1024) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun section(title: String) {
        rows.addView(
            Ui.text(ctx, title, Theme.H3, Theme.TEXT, maxLines = 1).apply {
                typeface = Theme.medium
                setPadding(0, Theme.dp(ctx, 30), 0, Theme.dp(ctx, 6))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun row(title: String, subtitle: String, action: () -> Unit): SettingRow {
        val item = SettingRow(ctx, title, subtitle, action)
        rows.addView(
            item,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Theme.dp(ctx, 10) }
        )
        return item
    }

    /** One focusable settings entry: title + explanation + current value. */
    private class SettingRow(
        context: Context,
        title: String,
        subtitle: String,
        onActivate: () -> Unit
    ) : LinearLayout(context) {

        private val valueView = TextView(context)
        private val titleView = TextView(context)
        private val subtitleView = TextView(context)

        var value: String = ""
            set(text) {
                field = text
                valueView.text = text
                valueView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }

        var highlight: Boolean = false
            set(on) {
                field = on
                valueView.setTextColor(if (on) Theme.GREEN else Theme.TEXT_DIM)
            }

        init {
            orientation = HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Theme.tint(Theme.SURFACE, 12f, context)
            setPadding(
                Theme.dp(context, 22), Theme.dp(context, 14),
                Theme.dp(context, 22), Theme.dp(context, 14)
            )

            val texts = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            titleView.apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Theme.TEXT)
                typeface = Theme.medium
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.START
            }
            texts.addView(
                titleView,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            subtitleView.apply {
                text = subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.SMALL)
                setTextColor(Theme.TEXT_FAINT)
                typeface = Theme.regular
                includeFontPadding = false
                maxLines = 2
                gravity = Gravity.START
                setLineSpacing(Theme.dpf(context, 2f), 1f)
            }
            texts.addView(
                subtitleView,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = Theme.dp(context, 5) }
            )
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            valueView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.SMALL)
                setTextColor(Theme.TEXT_DIM)
                typeface = Theme.medium
                includeFontPadding = false
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(
                    Theme.dp(context, 16), Theme.dp(context, 7),
                    Theme.dp(context, 16), Theme.dp(context, 7)
                )
                background = Theme.pill(context, Theme.SURFACE_2, Theme.STROKE)
                visibility = GONE
            }
            addView(
                valueView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = Theme.dp(context, 18) }
            )

            setOnFocusChangeListener { v, focused ->
                v.background = if (focused) {
                    Theme.outline(Theme.SURFACE_2, 12f, 2f, Theme.FOCUS_RING, context)
                } else {
                    Theme.tint(Theme.SURFACE, 12f, context)
                }
                titleView.setTextColor(if (focused) Color.WHITE else Theme.TEXT)
                if (Theme.animationsEnabled) {
                    v.animate().scaleX(if (focused) 1.01f else 1f)
                        .scaleY(if (focused) 1.01f else 1f)
                        .setDuration(110).start()
                }
            }
            setOnClickListener { onActivate() }
        }
    }
}
