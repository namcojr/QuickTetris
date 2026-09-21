package com.namco.quicktetris

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.content.edit
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLSurface
import java.io.File
import java.io.IOException

/**
 * Boots MAME straight into atetris.
 *
 * MAME chdirs to getExternalFilesDir(null) (SDL_AndroidGetExternalStoragePath) and resolves
 * cfg/nvram/ini relative to it; the ROM lives in internal storage and is passed as an absolute
 * -rompath, as are the bgfx shader tree and its artwork.
 *
 * Tapping the game cycles bgfx CRT chains live through a hook in our MAME build
 * (chain_manager::request_chain); the choice persists and seeds -bgfx_screen_chains.
 */
class MameActivity : SDLActivity() {

    private var controlPad: ControlPadView? = null
    private var shaderToast: ShaderToastView? = null
    private var shader = CrtShader.entries.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must complete before SDLActivity spawns the native thread that calls getArguments().
        installAssets()
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SHADER, null)
        shader = CrtShader.entries.firstOrNull { it.chain == saved } ?: shader
        super.onCreate(savedInstanceState)
        installControlPad()
        installShaderToast()
    }

    /** Docks the control pad at the bottom of SDL's RelativeLayout and shrinks the surface above it. */
    private fun installControlPad() {
        // SDLActivity bails out of onCreate (error dialog, no layout) when native libs fail to load.
        val layout = SDLActivity.mLayout as? RelativeLayout ?: return
        val surface = SDLActivity.mSurface ?: return

        val pad = ControlPadView(this).apply { id = View.generateViewId() }
        layout.addView(pad, RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) })

        surface.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ABOVE, pad.id)
        }
        controlPad = pad
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

    override fun createSDLSurface(context: Context): SDLSurface = GameSurface(context)

    /**
     * SDL re-registers the surface as its own touch listener on every resume, so tap handling
     * lives in an onTouch override. atetris takes no touch input: a tap cycles the CRT shader.
     */
    private inner class GameSurface(context: Context) : SDLSurface(context) {
        private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                performClick()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                cycleShader()
                return true
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            taps.onTouchEvent(event)
            return true
        }
    }

    private fun cycleShader() {
        val all = CrtShader.entries
        shader = all[(shader.ordinal + 1) % all.size]
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
        "-nokeepaspect", // fill the surface; ControlPadView sizes it to a mildly stretched 4:3
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

    /** bgfx chains offered by the tap cycle, in order; [chain] names bgfx/chains/<chain>.json. */
    private enum class CrtShader(val chain: String, val title: String, val blurb: String) {
        CRT_GEOM("crt-geom", "CRT-GEOM", "CURVED TUBE · SCANLINES · SHADOW MASK"),
        CRT_GEOM_DELUXE("crt-geom-deluxe", "CRT-GEOM DELUXE", "HALATION · BLOOM · PHOSPHOR GLOW"),
        HLSL("hlsl", "HLSL CRT", "NTSC · CONVERGENCE · BLOOM · SCANLINES"),
        NONE("default", "NO SHADER", "CLEAN FILTERED PIXELS"),
    }

    private companion object {
        const val TAG = "QuickTetris"
        const val PREFS = "display"
        const val KEY_SHADER = "bgfx_chain"
        const val TOAST_MARGIN_DP = 12f

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
