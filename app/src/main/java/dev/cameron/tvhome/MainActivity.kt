package dev.cameron.tvhome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The whole launcher: one screen with two states.
 *
 * Home:      time, date and weather along the top; pinned apps, "apps" and "x" along
 *            the bottom left; the looping animation in the bottom right.
 * All apps:  the same frame, with every installed app in alphabetical columns and "back".
 *
 * Layout numbers are the mockup's 1920x1080 pixels halved into dp (a TV is 960x540 dp).
 */
class MainActivity : Activity() {

    private data class AppEntry(val pkg: String, val label: String)

    private lateinit var prefs: SharedPreferences
    private lateinit var font: Typeface
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var tempView: TextView
    private lateinit var condView: TextView
    private lateinit var homeRow: LinearLayout
    private lateinit var appsPanel: LinearLayout
    private lateinit var appsScroll: HorizontalScrollView
    private lateinit var appsGrid: LinearLayout
    private lateinit var loopSlot: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private var gif: AnimatedImageDrawable? = null
    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null
    private var loopTexture: TextureView? = null
    private var playerReady = false
    private var videoPosition = 0
    private var videoRetries = 0
    private var resumed = false
    private var gifPaused = false
    private var showingApps = false
    private var lastWeather = 0L
    private var apps: List<AppEntry> = emptyList()

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            refreshWeather()
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    /**
     * Sleep and wake. A pause lasts only until the Shield sleeps, and the video player
     * is rebuilt on waking because the system can take its decoder away during sleep.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    gifPaused = false
                    closeVideo()
                }
                Intent.ACTION_SCREEN_ON -> {
                    closeVideo()
                    openVideoIfReady()
                    if (resumed) gif?.start()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("home", MODE_PRIVATE)
        font = resources.getFont(R.font.commit_mono)
        buildLayout()
        loadLoop()
        tempView.text = prefs.getString(KEY_TEMP, "")
        condView.text = prefs.getString(KEY_COND, "")

        val screenEvents = IntentFilter(Intent.ACTION_SCREEN_OFF)
        screenEvents.addAction(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenEvents, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenEvents)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // Was never registered.
        }
        closeVideo()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val fresh = loadApps()
        if (fresh != apps || homeRow.childCount == 0) {
            apps = fresh
            rebuildHome()
            if (showingApps) rebuildGrid()
        }
        handler.removeCallbacks(tick)
        tick.run()
        resumed = true
        openVideoIfReady()
        if (!gifPaused) gif?.start()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        resumed = false
        gif?.stop()
        // Hand the video decoder back while another app is in front.
        closeVideo()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        showHome()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showHome()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout() {
        val pad = dp(48)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(pad, pad, pad, pad)
        // The animation's canvas reaches past the text margins, so let it draw there.
        root.clipToPadding = false
        root.clipChildren = false

        timeView = label("")
        dateView = label("")
        tempView = label("")
        condView = label("")

        val topLeft = row()
        topLeft.addView(timeView)
        topLeft.addView(dateView, startGap(28))
        root.addView(topLeft, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))

        val topRight = row()
        topRight.addView(tempView)
        topRight.addView(condView, startGap(12))
        root.addView(topRight, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))

        homeRow = row()
        root.addView(homeRow, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))

        appsGrid = row()
        appsScroll = HorizontalScrollView(this)
        appsScroll.isHorizontalScrollBarEnabled = false
        appsScroll.overScrollMode = View.OVER_SCROLL_NEVER
        appsScroll.addView(appsGrid, FrameLayout.LayoutParams(WRAP, WRAP))

        appsPanel = LinearLayout(this)
        appsPanel.orientation = LinearLayout.VERTICAL
        appsPanel.visibility = View.GONE
        appsPanel.addView(appsScroll, LinearLayout.LayoutParams(WRAP, WRAP))
        val backParams = LinearLayout.LayoutParams(WRAP, WRAP)
        backParams.topMargin = dp(36)
        appsPanel.addView(item("back", TAG_BACK) { showHome() }, backParams)
        root.addView(appsPanel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))

        // The video's canvas is larger than the pendant inside it, to leave room for the
        // light flares. It overhangs the margins so the pendant's lowest point lands on
        // the baseline of the app row and its black border falls outside the layout.
        loopSlot = FrameLayout(this)
        val loopParams = FrameLayout.LayoutParams(px(LOOP_SIZE), px(LOOP_SIZE), Gravity.BOTTOM or Gravity.END)
        loopParams.marginEnd = -px(LOOP_OVERHANG_END)
        loopParams.bottomMargin = -px(LOOP_OVERHANG_BOTTOM)
        root.addView(loopSlot, loopParams)

        setContentView(root)
    }

    private fun row(): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.HORIZONTAL
        return layout
    }

    private fun startGap(gapDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(WRAP, WRAP)
        params.marginStart = dp(gapDp)
        return params
    }

    private fun endGap(gapDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(WRAP, WRAP)
        params.marginEnd = dp(gapDp)
        return params
    }

    /** Plain white text. Every piece of text on screen is this one size. */
    private fun label(value: String): TextView {
        val view = TextView(this)
        view.text = value
        view.typeface = font
        view.setTextColor(Color.WHITE)
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE)
        view.includeFontPadding = false
        view.maxLines = 1
        return view
    }

    /** Selectable text: grey, turning white with an underline when focused. */
    private fun item(value: String, key: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): TextView {
        val view = label(value)
        view.tag = key
        view.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(Color.WHITE, GREY)
            )
        )
        view.background = Underline(dp(1).toFloat())
        view.setPadding(0, dp(5), 0, dp(5))
        view.isFocusable = true
        view.isClickable = true
        view.defaultFocusHighlightEnabled = false
        view.ellipsize = TextUtils.TruncateAt.END
        view.setOnClickListener { onClick() }
        if (onLong != null) {
            view.setOnLongClickListener {
                onLong.invoke()
                true
            }
        }
        return view
    }

    // ------------------------------------------------------------------ home

    private fun rebuildHome() {
        val keep = homeRow.focusedChild?.tag as? String
        homeRow.removeAllViews()
        val byPkg = apps.associateBy { it.pkg }
        for (pkg in pinned()) {
            val app = byPkg[pkg] ?: continue
            homeRow.addView(item(app.label, pkg) { launch(pkg) }, endGap(ITEM_GAP))
        }
        homeRow.addView(item("apps", TAG_APPS) { showApps() }, endGap(ITEM_GAP))
        homeRow.addView(item("x", TAG_PAUSE) { toggleGif() }, LinearLayout.LayoutParams(WRAP, WRAP))
        if (!showingApps) {
            val target = (if (keep != null) homeRow.findViewWithTag<View>(keep) else null) ?: homeRow.getChildAt(0)
            target?.requestFocus()
        }
    }

    private fun showHome() {
        if (!showingApps) return
        showingApps = false
        appsPanel.visibility = View.GONE
        homeRow.visibility = View.VISIBLE
        homeRow.post {
            val target = homeRow.findViewWithTag<View>(TAG_APPS) ?: homeRow.getChildAt(0)
            target?.requestFocus()
        }
    }

    // -------------------------------------------------------------- all apps

    private fun showApps() {
        showingApps = true
        homeRow.visibility = View.GONE
        appsPanel.visibility = View.VISIBLE
        rebuildGrid()
    }

    private fun rebuildGrid(focusPkg: String? = null) {
        appsGrid.removeAllViews()
        val pins = pinned().toSet()
        var column: LinearLayout? = null
        for ((index, app) in apps.withIndex()) {
            if (index % ROWS == 0) {
                val fresh = LinearLayout(this)
                fresh.orientation = LinearLayout.VERTICAL
                val params = LinearLayout.LayoutParams(dp(COL_WIDTH), WRAP)
                if (index > 0) params.marginStart = dp(COL_GAP)
                appsGrid.addView(fresh, params)
                column = fresh
            }
            val text = if (app.pkg in pins) app.label + " *" else app.label
            val view = item(text, app.pkg, onLong = { togglePin(app.pkg) }) { launch(app.pkg) }
            view.maxWidth = dp(COL_WIDTH)
            column?.addView(view, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        val columns = (apps.size + ROWS - 1) / ROWS
        val scrollParams = appsScroll.layoutParams
        scrollParams.width =
            if (columns > VISIBLE_COLS) dp(VISIBLE_COLS * COL_WIDTH + (VISIBLE_COLS - 1) * COL_GAP) else WRAP
        appsScroll.layoutParams = scrollParams

        appsGrid.post {
            val first = (appsGrid.getChildAt(0) as? ViewGroup)?.getChildAt(0)
            val target = (if (focusPkg != null) appsGrid.findViewWithTag<View>(focusPkg) else null) ?: first
            target?.requestFocus()
        }
    }

    /** Long-press an app in the all apps list to pin it to, or unpin it from, the home row. */
    private fun togglePin(pkg: String) {
        val list = pinned().toMutableList()
        if (!list.remove(pkg)) list.add(pkg)
        prefs.edit().putString(KEY_PINNED, list.joinToString("\n")).apply()
        rebuildGrid(pkg)
        rebuildHome()
    }

    private fun pinned(): List<String> {
        val saved = prefs.getString(KEY_PINNED, null) ?: return DEFAULT_PINNED
        return saved.split('\n').filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ apps

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val found = LinkedHashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || found.containsKey(pkg)) continue
                found[pkg] = info.loadLabel(pm).toString()
            }
        }
        if (!found.containsKey(SETTINGS_PKG)) found[SETTINGS_PKG] = "settings"
        return found
            .map { AppEntry(it.key, it.value.trim().lowercase(Locale.getDefault())) }
            .sortedBy { it.label }
    }

    private fun launch(pkg: String) {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: if (pkg == SETTINGS_PKG) Intent(Settings.ACTION_SETTINGS) else return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // App vanished or refuses to start: stay on the launcher.
        }
    }

    // ------------------------------------------------------------------ loop

    /**
     * Shows the animation from app/src/main/assets: home.mp4 if there is one, otherwise
     * home.gif or home.webp. Replace that file to change it.
     */
    private fun loadLoop() {
        val names = try {
            assets.list("")?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (LOOP_VIDEO in names) {
            val texture = TextureView(this)
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openVideoIfReady()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    closeVideo()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
            loopTexture = texture
            loopSlot.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
            return
        }

        val name = names.firstOrNull { it.startsWith("home.") } ?: return
        try {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, name))
            val image = ImageView(this)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setImageDrawable(drawable)
            loopSlot.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                gif = drawable
            }
        } catch (e: Exception) {
            // A file that will not decode: leave the corner empty.
        }
    }

    /** Starts a player if the launcher is in front, has somewhere to draw and has none yet. */
    private fun openVideoIfReady() {
        val texture = loopTexture ?: return
        if (!resumed || player != null) return
        val surfaceTexture = texture.surfaceTexture ?: return
        openVideo(Surface(surfaceTexture))
    }

    private fun openVideo(surface: Surface) {
        closeVideo()
        playerSurface = surface
        try {
            val mp = MediaPlayer()
            assets.openFd(LOOP_VIDEO).use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { ready ->
                if (player === ready) {
                    playerReady = true
                    videoRetries = 0
                    ready.seekTo(videoPosition)
                    if (resumed && !gifPaused) ready.start()
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                closeVideo()
                if (videoRetries < MAX_VIDEO_RETRIES) {
                    videoRetries++
                    handler.postDelayed({ openVideoIfReady() }, 1_000L)
                }
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            closeVideo()
        }
    }

    private fun closeVideo() {
        val mp = player
        player = null
        if (mp != null) {
            try {
                if (playerReady) videoPosition = mp.currentPosition
            } catch (e: Exception) {
                // Keep the last known position.
            }
            try {
                mp.release()
            } catch (e: Exception) {
                // Already gone.
            }
        }
        playerReady = false
        playerSurface?.release()
        playerSurface = null
    }

    /** The "x" on the home row. A pause lasts until the Shield next sleeps. */
    private fun toggleGif() {
        gifPaused = !gifPaused
        if (gifPaused) {
            gif?.stop()
            try {
                if (playerReady) player?.pause()
            } catch (e: Exception) {
                // Nothing playing.
            }
        } else {
            gif?.start()
            if (player == null) {
                openVideoIfReady()
            } else {
                try {
                    if (playerReady) player?.start()
                } catch (e: Exception) {
                    // Still preparing: it starts itself once it is ready.
                }
            }
        }
    }

    // ------------------------------------------------------- clock + weather

    private fun updateClock() {
        val now = Date()
        val pattern = if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm"
        timeView.text = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now)
            .lowercase(Locale.getDefault())
    }

    /** Current conditions from Open-Meteo (free, no key). Asks at most every ten minutes. */
    private fun refreshWeather() {
        val now = System.currentTimeMillis()
        if (now - lastWeather < WEATHER_EVERY_MS) return
        lastWeather = now
        Thread {
            try {
                val connection = URL(WEATHER_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val current = JSONObject(body).getJSONObject("current")
                val temp = current.getDouble("temperature_2m").roundToInt().toString() + "°"
                val cond = describe(current.getInt("weather_code"))
                runOnUiThread {
                    tempView.text = temp
                    condView.text = cond
                    prefs.edit().putString(KEY_TEMP, temp).putString(KEY_COND, cond).apply()
                }
            } catch (e: Exception) {
                // Offline or the service is down: keep the last reading, try again in a minute.
                lastWeather = now - WEATHER_EVERY_MS + 60_000L
            }
        }.start()
    }

    /** WMO weather codes, as Open-Meteo reports them, in a word or two. */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorm"
        else -> ""
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun px(valueDp: Float): Int = (valueDp * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val GREY = 0xFF7A7A7A.toInt()
        const val TEXT_SIZE = 16f
        const val ITEM_GAP = 28
        const val LOOP_SIZE = 289f
        const val LOOP_OVERHANG_END = 33.5f
        const val LOOP_OVERHANG_BOTTOM = 51f
        const val LOOP_VIDEO = "home.mp4"
        const val MAX_VIDEO_RETRIES = 3
        const val ROWS = 7
        const val COL_WIDTH = 180
        const val COL_GAP = 28
        const val VISIBLE_COLS = 3

        const val KEY_PINNED = "pinned"
        const val KEY_TEMP = "weather_temp"
        const val KEY_COND = "weather_cond"
        const val TAG_APPS = "#apps"
        const val TAG_PAUSE = "#pause"
        const val TAG_BACK = "#back"

        const val SETTINGS_PKG = "com.android.tv.settings"

        /** Home row until you pin your own: youtube, plex, netflix, spotify, settings. */
        val DEFAULT_PINNED = listOf(
            "com.google.android.youtube.tv",
            "com.plexapp.android",
            "com.netflix.ninja",
            "com.spotify.tv.android",
            SETTINGS_PKG
        )

        /** Cambridge, MA. Change latitude and longitude to move the weather. */
        const val WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=42.3736&longitude=-71.1097" +
                "&current=temperature_2m,weather_code&temperature_unit=fahrenheit"
        const val WEATHER_EVERY_MS = 10 * 60_000L
    }
}

/** A white line along the bottom edge, drawn only while the view is focused. */
private class Underline(private val thickness: Float) : Drawable() {
    private val paint = Paint().apply { color = Color.WHITE }

    override fun draw(canvas: Canvas) {
        if (!state.contains(android.R.attr.state_focused)) return
        val b = bounds
        canvas.drawRect(b.left.toFloat(), b.bottom - thickness, b.right.toFloat(), b.bottom.toFloat(), paint)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
