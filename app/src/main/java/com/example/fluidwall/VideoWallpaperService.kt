package com.example.fluidwall

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Live wallpaper: plays a playlist of pictures and live videos from two folders.
 *
 * Mirrors fluidwall.sh's logic:
 *  - every step lasts `interval`
 *  - pictures are shown as a still, cropped from the middle to fill the screen
 *  - live videos loop for the step's duration
 *  - liveEvery N: every Nth step is a video (0 = none, negative = videos only)
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FluidEngine()

    inner class FluidEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val io: ExecutorService = Executors.newSingleThreadExecutor()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val nextStep = Runnable { advance() }

        private var player: ExoPlayer? = null
        private var alive = false
        private var visible = false
        private var surfaceValid = false
        private var cfg = readConfig()

        private var images: List<DocumentFile> = emptyList()
        private var lives: List<DocumentFile> = emptyList()
        private var imageIdx = 0
        private var liveIdx = 0
        private var stepCounter = 0
        private var token = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            alive = true
            player = ExoPlayer.Builder(this@VideoWallpaperService).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
            }
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            alive = false
            handler.removeCallbacks(nextStep)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            player?.release()
            player = null
            io.shutdownNow()
            super.onDestroy()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                restart()
            } else {
                handler.removeCallbacks(nextStep)
                player?.pause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceValid = true
            restart()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceValid = false
            handler.removeCallbacks(nextStep)
            player?.pause()
            player?.setVideoSurface(null)
            super.onSurfaceDestroyed(holder)
        }

        // Settings changed in the app: restart the playlist with the new values.
        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            if (key != null && key in CONFIG_KEYS) handler.post { restart() }
        }

        /** Re-reads config, rescans both folders, and starts the playlist from the top. */
        private fun restart() {
            handler.removeCallbacks(nextStep)
            if (!alive || !visible || !surfaceValid) return
            cfg = readConfig()
            val picUri = cfg.picUri
            val liveUri = cfg.liveUri
            io.execute {
                val imgs = scan(picUri, IMAGE_EXTS)
                val vids = scan(liveUri, VIDEO_EXTS)
                handler.post {
                    if (!alive || !visible || !surfaceValid) return@post
                    images = imgs.shuffled()
                    lives = vids.shuffled()
                    imageIdx = 0
                    liveIdx = 0
                    stepCounter = 0
                    advance()
                }
            }
        }

        private fun advance() {
            handler.removeCallbacks(nextStep)
            if (!alive || !visible || !surfaceValid) return

            val step = pickNext()
            if (step == null) {
                setCurrent("no media found, check folders in the app")
                player?.pause()
                return
            }

            token++
            val myToken = token
            setCurrent(step.file.name ?: "unknown")
            if (step.live) showLive(step.file) else showImage(step.file, myToken)
            handler.postDelayed(nextStep, cfg.intervalMs)
        }

        private fun pickNext(): Step? {
            val idx = stepCounter++
            val wantLive = when {
                cfg.liveEvery < 0 -> true
                images.isEmpty() -> true
                lives.isEmpty() || cfg.liveEvery == 0 -> false
                else -> idx > 0 && idx % cfg.liveEvery == 0
            }

            if (wantLive) {
                if (lives.isEmpty()) return null
                val f = lives[liveIdx]
                liveIdx++
                if (liveIdx >= lives.size) {
                    liveIdx = 0
                    lives = lives.shuffled()
                }
                return Step(true, f)
            }

            if (images.isEmpty()) return null
            val f = images[imageIdx]
            imageIdx++
            if (imageIdx >= images.size) {
                imageIdx = 0
                images = images.shuffled()
            }
            return Step(false, f)
        }

        private fun showLive(file: DocumentFile) {
            val p = player ?: return
            p.setVideoSurface(surfaceHolder.surface)
            p.setMediaItem(MediaItem.fromUri(file.uri))
            p.prepare()
            p.play()
        }

        private fun showImage(file: DocumentFile, myToken: Int) {
            player?.pause()
            player?.setVideoSurface(null)

            val frame = surfaceHolder.surfaceFrame
            val sw = frame.width()
            val sh = frame.height()
            io.execute {
                val bmp = decode(file, sw, sh)
                handler.post {
                    if (bmp == null) return@post
                    if (alive && surfaceValid && myToken == token) draw(bmp)
                    bmp.recycle()
                }
            }
        }

        private fun decode(file: DocumentFile, sw: Int, sh: Int): Bitmap? = try {
            val source = ImageDecoder.createSource(contentResolver, file.uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Keep decoded size near screen size (2x headroom) to save memory.
                val scale = min(1f, min(2f * sw / info.size.width, 2f * sh / info.size.height))
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).toInt()),
                        max(1, (info.size.height * scale).toInt())
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode ${file.name}", e)
            null
        }

        /** Fill the screen, centered: crops the sides, keeps full height on landscape images. */
        private fun draw(bmp: Bitmap) {
            val holder = surfaceHolder
            val canvas = holder.lockCanvas() ?: return
            try {
                val sw = canvas.width.toFloat()
                val sh = canvas.height.toFloat()
                val s = max(sw / bmp.width, sh / bmp.height)
                val dx = (sw - bmp.width * s) / 2f
                val dy = (sh - bmp.height * s) / 2f
                canvas.drawColor(Color.BLACK)
                val m = Matrix().apply {
                    setScale(s, s)
                    postTranslate(dx, dy)
                }
                canvas.drawBitmap(bmp, m, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun setCurrent(name: String) {
            prefs.edit().putString(KEY_CURRENT, name).apply()
        }

        private fun scan(uriStr: String?, exts: Set<String>): List<DocumentFile> {
            if (uriStr == null) return emptyList()
            val dir = DocumentFile.fromTreeUri(this@VideoWallpaperService, Uri.parse(uriStr))
                ?: return emptyList()
            return dir.listFiles().filter { f ->
                f.isFile && f.name?.substringAfterLast('.', "")?.lowercase() in exts
            }
        }
    }

    private fun readConfig(): Config {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            picUri = p.getString(KEY_PIC, null),
            liveUri = p.getString(KEY_LIVE, null),
            intervalMs = p.getLong(KEY_INTERVAL, 1800L) * 1000L,
            liveEvery = p.getInt(KEY_LIVE_EVERY, 3)
        )
    }

    private data class Config(
        val picUri: String?,
        val liveUri: String?,
        val intervalMs: Long,
        val liveEvery: Int
    )

    private data class Step(val live: Boolean, val file: DocumentFile)

    companion object {
        const val PREFS = "prefs"
        const val KEY_PIC = "pic_uri"
        const val KEY_LIVE = "live_uri"
        const val KEY_INTERVAL = "interval_sec"
        const val KEY_LIVE_EVERY = "live_every"
        const val KEY_CURRENT = "current"
        private const val TAG = "FluidWall"

        private val CONFIG_KEYS = setOf(KEY_PIC, KEY_LIVE, KEY_INTERVAL, KEY_LIVE_EVERY)
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        private val VIDEO_EXTS = setOf("mkv", "mp4", "webm", "mov", "m4v")
    }
}
