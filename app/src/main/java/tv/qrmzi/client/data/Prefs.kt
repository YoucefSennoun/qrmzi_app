package tv.qrmzi.client.data

import android.content.Context

/** User preferences (framework SharedPreferences; no extra dependency). */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("qrmzi_prefs", Context.MODE_PRIVATE)

    var adFilterEnabled: Boolean
        get() = sp.getBoolean(KEY_AD_FILTER, true)
        set(value) = sp.edit().putBoolean(KEY_AD_FILTER, value).apply()

    /** When filtering breaks a page we remember that fact and stop filtering. */
    var adFilterAutoDisabled: Boolean
        get() = sp.getBoolean(KEY_AD_FILTER_AUTO_OFF, false)
        set(value) = sp.edit().putBoolean(KEY_AD_FILTER_AUTO_OFF, value).apply()

    /**
     * Filter revision the app last started with. When the built-in host lists
     * change, `App` clears [adFilterAutoDisabled] once so a filter that gave up
     * on an older list gets a fresh chance. The user's own on/off choice
     * ([adFilterEnabled]) is never touched.
     */
    var filterRev: Int
        get() = sp.getInt(KEY_FILTER_REV, 0)
        set(value) = sp.edit().putInt(KEY_FILTER_REV, value).apply()

    /**
     * Extra block rules entered in Settings or in the config file. One domain
     * per line; empty means "use the built-in conservative list only".
     */
    var filterUserBlock: String
        get() = sp.getString(KEY_FILTER_BLOCK, "").orEmpty()
        set(value) = sp.edit().putString(KEY_FILTER_BLOCK, value).apply()

    /** Explicit allow rules. These win over every block rule. */
    var filterUserAllow: String
        get() = sp.getString(KEY_FILTER_ALLOW, "").orEmpty()
        set(value) = sp.edit().putString(KEY_FILTER_ALLOW, value).apply()

    /** Last host the filter refused, shown in Settings as a one-tap exception. */
    var lastBlockedHost: String
        get() = sp.getString(KEY_LAST_BLOCKED, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LAST_BLOCKED, value).apply()

    /** Verbose log: adds page/player/filter lines to the bounded diagnostics ring. */
    var verboseLogging: Boolean
        get() = sp.getBoolean(KEY_VERBOSE_LOG, false)
        set(value) = sp.edit().putBoolean(KEY_VERBOSE_LOG, value).apply()

    var reducedMotion: Boolean
        get() = sp.getBoolean(KEY_REDUCED_MOTION, false)
        set(value) = sp.edit().putBoolean(KEY_REDUCED_MOTION, value).apply()

    /** 0 = small thumbnails (fast on 1 GB RAM), 1 = sharper. */
    var imageQuality: Int
        get() = sp.getInt(KEY_IMAGE_QUALITY, 0)
        set(value) = sp.edit().putInt(KEY_IMAGE_QUALITY, value.coerceIn(0, 1)).apply()

    var lastSearch: String
        get() = sp.getString(KEY_LAST_SEARCH, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LAST_SEARCH, value).apply()

    private companion object {
        const val KEY_AD_FILTER = "ad_filter"
        const val KEY_AD_FILTER_AUTO_OFF = "ad_filter_auto_off"
        const val KEY_FILTER_REV = "filter_rev"
        const val KEY_FILTER_BLOCK = "filter_block"
        const val KEY_FILTER_ALLOW = "filter_allow"
        const val KEY_LAST_BLOCKED = "last_blocked_host"
        const val KEY_VERBOSE_LOG = "verbose_log"
        const val KEY_REDUCED_MOTION = "reduced_motion"
        const val KEY_IMAGE_QUALITY = "image_quality"
        const val KEY_LAST_SEARCH = "last_search"
    }
}
