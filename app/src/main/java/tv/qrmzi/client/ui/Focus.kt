package tv.qrmzi.client.ui

import android.view.View
import android.view.ViewGroup

/** Focus utilities shared by every screen (D-pad first). */
object Focus {

    /** Depth-first search for the first focusable, visible descendant. */
    fun firstFocusable(root: View?): View? {
        if (root == null || root.visibility != View.VISIBLE) return null
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = firstFocusable(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return if (root.isFocusable && root.isEnabled) root else null
    }

    fun focusIn(root: View?): Boolean {
        val target = firstFocusable(root) ?: return false
        return target.requestFocus()
    }

    /**
     * A view that receives focus only when nothing else is focused (used for
     * the scroll containers so the remote always lands on a real card).
     */
    fun container(view: ViewGroup): ViewGroup = view.apply {
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
}
