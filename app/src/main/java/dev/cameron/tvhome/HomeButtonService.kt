package dev.cameron.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes the Home button open DG TV, without ADB.
 *
 * It only works while it is switched on in the Shield's Settings under
 * Device Preferences > Accessibility. Switch it off there to get the stock
 * behavior back.
 *
 * Two things happen:
 *  - A press of Home opens DG TV directly. A quick double press opens recent apps,
 *    as it does on a stock Shield.
 *  - If the stock home screen appears anyway (after a restart, or when an app
 *    exits to it), DG TV is opened over it.
 */
class HomeButtonService : AccessibilityService() {

    private var lastHomeUp = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) {
            val now = event.eventTime
            if (now - lastHomeUp < DOUBLE_PRESS_MS) {
                lastHomeUp = 0L
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            } else {
                lastHomeUp = now
                goHome()
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in STOCK_HOME_SCREENS) goHome()
    }

    override fun onInterrupt() {}

    private fun goHome() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Nothing to fall back to: the stock home screen stays up.
        }
    }

    private companion object {
        const val DOUBLE_PRESS_MS = 400L

        val STOCK_HOME_SCREENS = setOf(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher"
        )
    }
}
