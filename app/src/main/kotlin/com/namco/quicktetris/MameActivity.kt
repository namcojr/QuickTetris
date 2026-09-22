package com.namco.quicktetris

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.RelativeLayout
import androidx.core.content.edit
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLSurface
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * Boots MAME straight into atetris.
 *
 * MAME chdirs to getExternalFilesDir(null) (SDL_AndroidGetExternalStoragePath) and resolves
 * cfg/nvram/ini relative to it; the ROM lives in internal storage and is passed as an absolute
 * -rompath, as are the bgfx shader tree and its artwork.
 *
 * Tapping the game opens a menu of bgfx chains and aspect modes. Chains switch live through a
 * hook in our MAME build (chain_manager::request_chain) and seed -bgfx_screen_chains; both
 * choices persist. The menu takes window focus, so SDL pauses the game while it is open.
 *
 * MAME's native thread starts [LAUNCH_DELAY_MS] after the first onCreate, not as soon as the
 * surface is ready: launching immediately sometimes comes up with a black renderer.
 */
class MameActivity : SDLActivity() {

    private var controlPad: ControlPadView? = null
    private var shaderToast: ShaderToastView? = null
    private var shader = CrtShader.entries.first()
    private var aspect = Aspect.entries.first()
    private var menuAnchor: View? = null
    private var menu: PopupMenu? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Shared by the game surface and the letterbox bars around it (touches the surface leaves to
     * the root layout). A gesture never spans both views, so their coordinate spaces don't mix.
     */
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showMenu(e.rawX, e.rawY)
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must complete before SDLActivity spawns the native thread that calls getArguments().
        installAssets()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getString(KEY_SHADER, null)
        shader = CrtShader.entries.firstOrNull { it.chain == saved } ?: shader
        val savedAspect = prefs.getString(KEY_ASPECT, null)
        aspect = Aspect.entries.firstOrNull { it.key == savedAspect }
            // Before the menu, aspect was a stretched/4:3 boolean.
            ?: if (prefs.getBoolean(KEY_STRETCH, true)) Aspect.STRETCH else Aspect.CLASSIC
        super.onCreate(savedInstanceState)
        if (!launchReleased) mainHandler.postDelayed(::releaseLaunch, LAUNCH_DELAY_MS)
        installControlPad()
        installShaderToast()
        installMenuAnchor()
    }

    /** Docks the control pad at the bottom of SDL's RelativeLayout and shrinks the surface above it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun installControlPad() {
        // SDLActivity bails out of onCreate (error dialog, no layout) when native libs fail to load.
        val layout = SDLActivity.mLayout as? RelativeLayout ?: return
        val surface = SDLActivity.mSurface ?: return

        val pad = ControlPadView(this).apply { id = View.generateViewId() }
        layout.addView(pad, RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) })

        controlPad = pad
        layoutSurface()
        // The pad's height depends on the window size and insets only, never on the aspect mode.
        pad.addOnLayoutChangeListener { _, _, top, right, _, _, oldTop, oldRight, _ ->
            if (top != oldTop || right != oldRight) pad.post { layoutSurface() }
        }
        layout.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
    }

    /**
     * Sizes the surface to [aspect] within the area above the pad, centered over black bars.
     * MAME runs -nokeepaspect, so it always fills the surface. Until the first layout pass the
     * surface just fills the area above the pad.
     */
    private fun layoutSurface() {
        val layout = SDLActivity.mLayout as? RelativeLayout ?: return
        val surface = SDLActivity.mSurface ?: return
        val pad = controlPad ?: return
        val box = if (layout.width > 0 && pad.top > 0) aspect.fit(layout.width, pad.top) else null
        val params = if (box == null) {
            RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { addRule(RelativeLayout.ABOVE, pad.id) }
        } else {
            val (w, h) = box
            val availH = pad.top
            RelativeLayout.LayoutParams(w, h).apply {
                addRule(RelativeLayout.CENTER_HORIZONTAL)
                topMargin = (availH - h) / 2
            }
        }
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        val old = surface.layoutParams as? RelativeLayout.LayoutParams
        if (old != null && old.width == params.width && old.height == params.height &&
            old.topMargin == params.topMargin && old.rules.contentEquals(params.rules)
        ) return
        surface.layoutParams = params
    }

    /** Overlays the plaque that names the active shader, centered at the top of the game. */
    private fun installShaderToast() {
        val layout = SDLActivity.mLayout as? RelativeLayout ?: return
        val toast = ShaderToastView(this)
        layout.addView(toast, RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            val margin = (TOAST_MARGIN_DP * resources.displayMetrics.density).toInt()
            setMargins(margin, margin, margin, 0)
        })
        shaderToast = toast
        toast.post { showShader() }
    }

    /** Zero-size view moved to each tap so the menu pops up where the finger is. */
    private fun installMenuAnchor() {
        val layout = SDLActivity.mLayout as? RelativeLayout ?: return
        val anchor = View(this)
        layout.addView(anchor, RelativeLayout.LayoutParams(0, 0))
        menuAnchor = anchor
    }

    private fun showMenu(rawX: Float, rawY: Float) {
        val layout = SDLActivity.mLayout as? RelativeLayout ?: return
        val anchor = menuAnchor ?: return
        if (menu != null) return
        val origin = IntArray(2).also(layout::getLocationOnScreen)
        anchor.layoutParams = RelativeLayout.LayoutParams(0, 0).apply {
            leftMargin = (rawX - origin[0]).toInt().coerceIn(0, layout.width)
            topMargin = (rawY - origin[1]).toInt().coerceIn(0, layout.height)
        }
        // The popup steals window focus; a held button would never see its release.
        controlPad?.releaseAll()

        val popup = PopupMenu(this, anchor)
        val shaders = popup.menu.addSubMenu(Menu.NONE, Menu.NONE, 0, "SHADERS")
        for (s in CrtShader.entries) {
            shaders.add(GROUP_SHADER, s.ordinal, s.ordinal, s.title).isChecked = s == shader
        }
        shaders.setGroupCheckable(GROUP_SHADER, true, true)
        val aspects = popup.menu.addSubMenu(Menu.NONE, Menu.NONE, 1, "ASPECT")
        for (a in Aspect.entries) {
            aspects.add(GROUP_ASPECT, a.ordinal, a.ordinal, a.title).isChecked = a == aspect
        }
        aspects.setGroupCheckable(GROUP_ASPECT, true, true)

        popup.setOnMenuItemClickListener { item ->
            when (item.groupId) {
                GROUP_SHADER -> selectShader(CrtShader.entries[item.itemId])
                GROUP_ASPECT -> selectAspect(Aspect.entries[item.itemId])
                else -> return@setOnMenuItemClickListener false // submenu headers
            }
            true
        }
        // Fires when a submenu replaces the root popup too; a new tap may open a fresh menu then.
        popup.setOnDismissListener { if (menu === it) menu = null }
        menu = popup
        popup.show()
    }

    override fun createSDLSurface(context: Context): SDLSurface = GameSurface(context)

    /**
     * SDL re-registers the surface as its own touch listener on every resume, so tap handling
     * lives in an onTouch override. atetris takes no touch input: a tap opens the display menu.
     */
    private inner class GameSurface(context: Context) : SDLSurface(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            gestures.onTouchEvent(event)
            return true
        }
    }

    private fun selectShader(choice: CrtShader) {
        if (choice == shader) return showShader()
        shader = choice
        getSharedPreferences(PREFS, MODE_PRIVATE).edit { putString(KEY_SHADER, shader.chain) }
        if (!SDLActivity.mBrokenLibraries) {
            try {
                nativeSetScreenChain(shader.chain)
            } catch (e: UnsatisfiedLinkError) {
                // A stock libmain without the chain hook: the choice still applies on next launch.
                Log.e(TAG, "Live shader switching unavailable", e)
            }
        }
        showShader()
    }

    private fun selectAspect(choice: Aspect) {
        aspect = choice
        getSharedPreferences(PREFS, MODE_PRIVATE).edit { putString(KEY_ASPECT, aspect.key) }
        layoutSurface()
        shaderToast?.show(aspect.title, "${aspect.ordinal + 1}/${Aspect.entries.size} · ${aspect.blurb}")
    }

    private fun showShader() {
        shaderToast?.show(shader.title, "${shader.ordinal + 1}/${CrtShader.entries.size} · ${shader.blurb}")
    }

    override fun onPause() {
        controlPad?.releaseAll()
        super.onPause()
    }

    /**
     * SDL re-requests orientation from MAME's window size (landscape for atetris), which would
     * override the manifest. The layout is portrait-first: game on top, controls below.
     */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String) = Unit

    override fun isNativeStartAllowed() = launchReleased

    /**
     * Retries the state transition SDL skipped while the launch was held. If the activity is
     * paused or unfocused by then, SDL starts the thread on its own next resume/focus.
     */
    private fun releaseLaunch() {
        launchReleased = true
        if (!SDLActivity.mBrokenLibraries && !isDestroyed) SDLActivity.handleNativeState()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        menu?.dismiss()
        super.onDestroy()
    }

    override fun getLibraries(): Array<String> = arrayOf("c++_shared", "SDL3", "main")

    override fun getArguments(): Array<String> = arrayOf(
        GAME,
        "-rompath", File(filesDir, ROM_DIR).absolutePath,
        "-video", "bgfx",
        "-bgfx_path", File(filesDir, "$MAME_DIR/bgfx").absolutePath,
        // Chain textures (shadow masks) resolve against the art path.
        "-artpath", File(filesDir, "$MAME_DIR/artwork").absolutePath,
        "-bgfx_backend", "gles", // only ESSL shaders are shipped
        "-bgfx_screen_chains", shader.chain,
        "-skip_gameinfo",
        "-nokeepaspect", // fill the surface; layoutSurface() sizes it per Aspect
    )

    /**
     * Mirrors [ASSET_DIRS] from assets/ into filesDir on first launch and after every app update.
     * The stamp is written last, so an interrupted copy is redone on the next launch.
     */
    private fun installAssets() {
        val stamp = File(filesDir, STAMP_FILE)
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == version && ASSET_DIRS.all { File(filesDir, it).isDirectory }) return

        try {
            stamp.delete()
            for (dir in ASSET_DIRS) {
                val dest = File(filesDir, dir)
                dest.deleteRecursively()
                copyAssetTree(dir, dest)
            }
            stamp.writeText(version)
            Log.i(TAG, "Installed assets $ASSET_DIRS")
        } catch (e: IOException) {
            // MAME reports a missing ROM/shader tree itself; don't block startup here.
            Log.e(TAG, "Asset install failed", e)
        }
    }

    /** APKs hold no empty directories, so an asset path without children is a file. */
    private fun copyAssetTree(path: String, dest: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        if (!dest.isDirectory && !dest.mkdirs()) throw IOException("mkdirs failed: $dest")
        for (child in children) copyAssetTree("$path/$child", File(dest, child))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // MAME's global state can't be torn down and restarted in-process; exit hard on BACK
        // from hardware buttons, matching upstream org.mamedev.mame.MAME.
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            (event.source and InputDevice.SOURCE_CLASS_BUTTON) != 0
        ) {
            Process.killProcess(Process.myPid())
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * bgfx chains offered by the menu, in order; [chain] is the file stem of a JSON anywhere
     * under bgfx/chains (MAME matches chains by stem, subdirectories included).
     */
    private enum class CrtShader(val chain: String, val title: String, val blurb: String) {
        CRT_GEOM("crt-geom", "CRT-GEOM", "CURVED TUBE · SCANLINES · SHADOW MASK"),
        CRT_GEOM_DELUXE("crt-geom-deluxe", "CRT-GEOM DELUXE", "HALATION · PHOSPHOR GLOW · HEAVY"),
        SCANLINES("scanlines", "SCANLINES", "FLAT ARCADE MONITOR · NO CURVATURE"),
        HLSL("hlsl", "HLSL CRT", "NTSC · CONVERGENCE · BLOOM · SCANLINES"),
        LCD("lcd-grid", "LCD GRID", "SUBPIXEL GRID · MOTION BLUR"),
        XBR("xbr-lv2", "XBR", "SMOOTH EDGES · SHARP UPSCALE"),
        HQ2X("hq2x", "HQ2X", "CLASSIC PIXEL-ART SMOOTHING"),
        // Single point-sampled blit; "default" would route through a bilinear prescale target.
        NONE("unfiltered", "NO SHADER", "RAW PIXELS · NO FILTERING"),
    }

    /** Game box sizes offered by the menu, in order; [key] is persisted. */
    private enum class Aspect(val key: String, val title: String, val blurb: String) {
        STRETCH("stretch", "STRETCHED", "FILLS THE GAME AREA"),
        CLASSIC("4:3", "ASPECT 4:3", "ORIGINAL MONITOR"),
        NATIVE("7:5", "ASPECT 7:5", "SQUARE PIXELS · 336×240"),
        INTEGER("integer", "PIXEL PERFECT", "WHOLE-NUMBER SCALE · BEST UNFILTERED"),
        ;

        /** (width, height) in px within the available area; null fills it. */
        fun fit(availW: Int, availH: Int): Pair<Int, Int>? = when (this) {
            STRETCH -> null
            CLASSIC -> ratio(availW, availH, 4, 3)
            NATIVE -> ratio(availW, availH, GAME_W, GAME_H)
            INTEGER -> min(availW / GAME_W, availH / GAME_H).let { scale ->
                // Screens under 336x240 px can't hold 1x; fall back to the nearest look.
                if (scale == 0) ratio(availW, availH, GAME_W, GAME_H) else GAME_W * scale to GAME_H * scale
            }
        }

        private fun ratio(availW: Int, availH: Int, aw: Int, ah: Int): Pair<Int, Int> {
            val h = min(availH, availW * ah / aw)
            return min(availW, h * aw / ah) to h
        }
    }

    private companion object {
        const val TAG = "QuickTetris"
        const val PREFS = "display"
        const val KEY_SHADER = "bgfx_chain"
        const val KEY_ASPECT = "aspect"
        const val KEY_STRETCH = "stretch" // legacy boolean, read only as the aspect default
        const val TOAST_MARGIN_DP = 12f
        const val GROUP_SHADER = 1
        const val GROUP_ASPECT = 2
        const val GAME_W = 336 // atetris' visible raster
        const val GAME_H = 240
        const val LAUNCH_DELAY_MS = 1000L

        /** Per process: only MAME's first start is delayed, never a resume or activity recreation. */
        var launchReleased = false

        /** Implemented in our MAME build (bgfx chainmanager.cpp); thread-safe. */
        @JvmStatic
        external fun nativeSetScreenChain(chain: String)
        const val GAME = "atetris"
        const val ROM_DIR = "roms"
        const val MAME_DIR = "mame"
        val ASSET_DIRS = listOf(ROM_DIR, MAME_DIR)
        const val STAMP_FILE = ".assets.stamp"
    }
}
