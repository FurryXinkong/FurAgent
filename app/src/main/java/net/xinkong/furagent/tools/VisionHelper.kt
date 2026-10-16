package net.xinkong.furagent.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import net.xinkong.furagent.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object VisionHelper {

    /**
     * 全局默认（当未显式设置压缩参数时使用）。
     */
    private const val DEFAULT_MAX_DIM = 1024
    private const val DEFAULT_JPEG_QUALITY = 70
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    /**
     * 当前生效的压缩参数（由 Agent.kt 每轮设置，来自 AppSettings）。
     * 用 volatile 保证跨线程可见。
     */
    @Volatile private var currentMaxDim: Int = DEFAULT_MAX_DIM
    @Volatile private var currentQuality: Int = DEFAULT_JPEG_QUALITY
    @Volatile private var currentEnabled: Boolean = true

    /**
     * 由 Agent 循环在启动时调用，把 settings.imageCompress 系列参数同步进来。
     * 避免 VisionHelper 依赖 AppSettings 造成循环依赖。
     */
    fun configureFromSettings(enabled: Boolean, quality: Int, maxDim: Int) {
        currentEnabled = enabled
        currentQuality = quality.coerceIn(30, 100)
        currentMaxDim = maxDim.coerceIn(320, 4096)
    }

    private val pending = ConcurrentLinkedQueue<String>()

    fun pushPendingImage(path: String) {
        if (path.isNotBlank()) pending.offer(path)
    }

    fun drainPendingImages(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val p = pending.poll() ?: break
            out.add(p)
        }
        return out
    }

    fun clearPendingImages() {
        pending.clear()
    }

    fun readAsDataUrl(path: String): String? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        return try {
            val bytes = if (currentEnabled) {
                compressToJpeg(file) ?: file.readBytes()
            } else {
                // 关闭压缩时，仅对大图做硬性降采样，避免 token 爆炸
                val hardLimit = maxOf(currentMaxDim, 1920)
                compressToJpeg(file, maxDim = hardLimit, quality = 90)
                    ?: file.readBytes()
            }
            if (bytes.isEmpty()) return null
            val mime = if (path.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mime;base64,$b64"
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "读取图片为 data URL 失败（$path）：${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private fun compressToJpeg(
        file: File,
        maxDim: Int = currentMaxDim,
        quality: Int = currentQuality
    ): ByteArray? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            var sample = 1
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
                sample *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

            var q = quality
            var bytes: ByteArray
            do {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                bytes = bos.toByteArray()
                q -= 15
            } while (bytes.size > MAX_IMAGE_BYTES && q >= 20)

            bmp.recycle()
            bytes
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "压缩图片失败（${file.absolutePath}）：${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    fun buildMultiModalUserMessage(text: String, imagePaths: List<String>): JSONObject {
        val validPaths = imagePaths.filter { File(it).exists() }
        if (validPaths.isEmpty()) {
            return JSONObject().apply {
                put("role", "user")
                put("content", text)
            }
        }

        val arr = JSONArray()
        if (text.isNotBlank()) {
            arr.put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        }
        for (path in validPaths) {
            val dataUrl = readAsDataUrl(path) ?: continue
            arr.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", dataUrl))
            })
        }

        return JSONObject().apply {
            put("role", "user")
            put("content", arr)
        }
    }

    fun screenshotDir(): File {
        val dir = File(SDCARD_PATH, "FurAgent/screenshots")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun newScreenshotPath(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        return File(screenshotDir(), "screenshot_$ts.png").absolutePath
    }
}