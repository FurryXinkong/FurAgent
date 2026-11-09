package net.xinkong.furagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import net.xinkong.furagent.core.AgentLogger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** 投屏采集默认参数。 */
private const val MIRROR_DEFAULT_WIDTH = 720
private const val MIRROR_DEFAULT_FPS = 30
private const val MIRROR_DEFAULT_QUALITY = 70

/**
 * 投屏采集参数：分辨率(目标宽度) / 帧率 / 画质(映射到 H.264 码率)。
 *
 * 放在文件顶层（而非 companion 内），跨包引用直接用 `net.xinkong.furagent.service.MirrorConfig`。
 */
data class MirrorConfig(
    val targetWidth: Int = MIRROR_DEFAULT_WIDTH,
    val fps: Int = MIRROR_DEFAULT_FPS,
    val quality: Int = MIRROR_DEFAULT_QUALITY
) {
    val frameIntervalMs: Long
        get() = (1000L / fps.coerceIn(1, 60)).coerceAtLeast(1)

    /** 画质(0-100) → H.264 目标码率(bps)。H.264 比 JPEG 省得多，同码率下画质更高。 */
    val bitrate: Int
        get() = when {
            quality >= 90 -> 12_000_000
            quality >= 80 -> 8_000_000
            quality >= 65 -> 5_000_000
            quality >= 50 -> 3_000_000
            else -> 2_000_000
        }
}

/**
 * 手机端实时屏幕投屏采集（硬件 H.264 编码）。
 *
 * 与旧版「每帧 JPEG」不同：这里用 MediaProjection + VirtualDisplay 直接把画面渲染到
 * MediaCodec 的输入 Surface，由**硬件 H.264 编码器**编码（低延迟、高画质、低带宽），
 * 编码输出按 Annex-B 送入 [ScreenMirrorFrames]，再由 AndroidHttpFileServer 的
 * `/screen/stream` 以 `[类型][长度][数据]` 连续推给电脑端，电脑端用 jcodec 解码。
 *
 * MediaProjection 授权必须由前台 Activity 发起（见 ScreenMirrorPermissionActivity），
 * 这里只负责持有授权结果并长期采集。
 */
class ScreenMirrorService : Service() {

    companion object {
        const val ACTION_START = "net.xinkong.furagent.SCREEN_MIRROR_START"
        const val ACTION_STOP = "net.xinkong.furagent.SCREEN_MIRROR_STOP"
        const val EXTRA_RESULT_CODE = "screen_mirror_result_code"
        const val EXTRA_RESULT_DATA = "screen_mirror_result_data"
        /** 采集目标宽度(px)；<=0 表示自适应（按 MIRROR_DEFAULT_WIDTH）。 */
        const val EXTRA_RESOLUTION = "screen_mirror_resolution"
        const val EXTRA_FPS = "screen_mirror_fps"
        const val EXTRA_QUALITY = "screen_mirror_quality"
        private const val CHANNEL_ID = "furagent_screen_mirror"
        private const val NOTIFICATION_ID = 20261109
        private const val MIME = "video/avc"

        fun start(
            context: Context, resultCode: Int, data: Intent,
            config: MirrorConfig = MirrorConfig()
        ) {
            val i = Intent(context, ScreenMirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_RESOLUTION, config.targetWidth)
                putExtra(EXTRA_FPS, config.fps)
                putExtra(EXTRA_QUALITY, config.quality)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                AgentLogger.log("MIRROR", "启动投屏服务失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, ScreenMirrorService::class.java).apply { action = ACTION_STOP }
                )
            } catch (e: Exception) {
                AgentLogger.log("MIRROR", "停止投屏服务失败：${e.message}")
                ScreenMirrorFrames.deactivate()
            }
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var handlerThread: HandlerThread? = null
    @Volatile private var running = false
    private var drainThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            AgentLogger.log("MIRROR", "startForeground 失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }
                if (code == 0 || data == null) {
                    AgentLogger.log("MIRROR", "缺少 MediaProjection 授权结果，投屏未启动")
                    ScreenMirrorFrames.deactivate()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val config = MirrorConfig(
                    targetWidth = intent.getIntExtra(EXTRA_RESOLUTION, MIRROR_DEFAULT_WIDTH),
                    fps = intent.getIntExtra(EXTRA_FPS, MIRROR_DEFAULT_FPS),
                    quality = intent.getIntExtra(EXTRA_QUALITY, MIRROR_DEFAULT_QUALITY)
                )
                releaseAll()
                startCapture(code, data, config)
            }
            else -> {
                ScreenMirrorFrames.deactivate()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent, config: MirrorConfig) {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mgr.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection 授权结果无效")
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    AgentLogger.log("MIRROR", "系统收回了录屏授权")
                    releaseAll()
                }
            }, Handler(mainLooper))
            projection = mp

            val metrics = resources.displayMetrics
            val realW = metrics.widthPixels
            val realH = metrics.heightPixels
            // 偶数尺寸：H.264 编码器通常要求宽高为偶数
            var targetW = if (config.targetWidth <= 0) MIRROR_DEFAULT_WIDTH else config.targetWidth
                .coerceIn(240, 1920).coerceAtMost(realW)
            if (targetW % 2 != 0) targetW -= 1
            var targetH = (realH.toFloat() / realW * targetW).toInt().coerceAtLeast(320)
            if (targetH % 2 != 0) targetH -= 1

            fun buildFormat(withProfile: Boolean): MediaFormat =
                MediaFormat.createVideoFormat(MIME, targetW, targetH).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                    // 1 秒一个关键帧：既保证首帧可解，也让丢帧后能较快恢复
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (withProfile) {
                        // 基线档次：jcodec 对 baseline 支持最好
                        try {
                            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                        } catch (_: Exception) { }
                    }
                }

            var codec = MediaCodec.createEncoderByType(MIME)
            try {
                codec.configure(buildFormat(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // 部分机型不接受 baseline profile 配置，回退默认 profile 重配一次
                AgentLogger.log("MIRROR", "带 profile 配置失败，回退默认：${e.javaClass.simpleName}: ${e.message}")
                try { codec.release() } catch (_: Exception) { }
                codec = MediaCodec.createEncoderByType(MIME)
                codec.configure(buildFormat(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            inputSurface = surface

            val thread = HandlerThread("FurAgentMirror").apply { start() }
            handlerThread = thread
            virtualDisplay = mp.createVirtualDisplay(
                "FurAgentMirror",
                targetW, targetH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, Handler(thread.looper)
            )
            ScreenMirrorFrames.activate(realW, realH, targetW, targetH)
            AgentLogger.log(
                "MIRROR",
                "H.264 投屏已启动：真实 ${realW}x$realH → ${targetW}x$targetH · ${config.fps}fps · ${config.bitrate / 1000}kbps"
            )

            running = true
            drainThread = Thread({ drainEncoder() }, "FurAgentMirrorDrain").apply {
                isDaemon = true; start()
            }
        } catch (e: Exception) {
            AgentLogger.log("MIRROR", "启动采集失败：${e.javaClass.simpleName}: ${e.message}")
            ScreenMirrorFrames.deactivate()
            releaseAll()
        }
    }

    /** 从编码器不断取出已编码数据，转成 Annex-B 后发布。 */
    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        val codec = encoder ?: return
        while (running) {
            try {
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        val bos = ByteArrayOutputStream()
                        val csd0 = fmt.getByteBuffer("csd-0")   // SPS
                        val csd1 = fmt.getByteBuffer("csd-1")   // PPS
                        if (csd0 != null) appendAnnexB(bos, csd0)
                        if (csd1 != null) appendAnnexB(bos, csd1)
                        if (bos.size() > 0) ScreenMirrorFrames.setConfig(bos.toByteArray())
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val arr = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(arr, 0, info.size)
                            val annexB = toAnnexB(arr)
                            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            ScreenMirrorFrames.publishFrame(annexB, key)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED：继续
                }
            } catch (e: IllegalStateException) {
                break  // 已 release
            } catch (e: Exception) {
                if (running) AgentLogger.log("MIRROR", "编码输出异常：${e.javaClass.simpleName}: ${e.message}")
                break
            }
        }
    }

    /** 把 AVCC 格式（[4字节长度][NAL]...）转成 Annex-B（00 00 00 01 起始码）；已是 Annex-B 则原样返回。 */
    private fun toAnnexB(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            data[2].toInt() == 0 && data[3].toInt() == 1
        ) return data  // 已经是 Annex-B
        val bos = ByteArrayOutputStream(data.size + 16)
        var i = 0
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xFF) shl 24) or
                    ((data[i + 1].toInt() and 0xFF) shl 16) or
                    ((data[i + 2].toInt() and 0xFF) shl 8) or
                    (data[i + 3].toInt() and 0xFF)
            i += 4
            if (len <= 0 || i + len > data.size) break
            bos.write(0); bos.write(0); bos.write(0); bos.write(1)
            bos.write(data, i, len)
            i += len
        }
        return if (bos.size() == 0) data else bos.toByteArray()
    }

    /** csd-0/csd-1 是 AVCC 单 NAL，转成 Annex-B 追加到 bos。 */
    private fun appendAnnexB(bos: ByteArrayOutputStream, csd: ByteBuffer) {
        val dup = csd.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        val converted = toAnnexB(bytes)
        bos.write(converted)
    }

    private fun releaseAll() {
        running = false
        try { drainThread?.join(300) } catch (_: Exception) { }
        drainThread = null
        ScreenMirrorFrames.deactivate()
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { inputSurface?.release() } catch (_: Exception) { }
        try { encoder?.stop() } catch (_: Exception) { }
        try { encoder?.release() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        try { handlerThread?.quitSafely() } catch (_: Exception) { }
        virtualDisplay = null
        inputSurface = null
        encoder = null
        projection = null
        handlerThread = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "屏幕投屏", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "向电脑实时传输屏幕画面" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FurAgent 屏幕投屏")
            .setContentText("正在向电脑实时传输屏幕画面")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * 帧交换区：采集/编码线程写、HTTP 流线程读。
 *
 * H.264 是**帧间编码**（P 帧依赖前面的帧），所以这里不能像 JPEG 那样只留最新帧，
 * 必须**按顺序**把编码数据交给读取端，否则解码会花屏直到下一个关键帧。
 * 但码率很低（H.264 几 Mbps），正常 WiFi 完全跟得上，队列基本是空的、延迟很低。
 * 队列设一个上限做兜底：万一读取端长期跟不上，丢最旧帧（解码端会等到下一个关键帧恢复）。
 *
 * 另外单独保存编码器输出的 SPS/PPS（config），读取端需要先拿到它才能初始化解码器。
 */
object ScreenMirrorFrames {

    data class Frame(val version: Long, val data: ByteArray, val keyframe: Boolean)

    // 队列必须很小：解码端只要略慢于编码端，队列就会以「生产−消费」的速率越堆越满，
    // 而且消费=生产时永远排不空（单向棘轮）→ 延迟从 0 涨到几秒。砍到 6 帧（~200ms @30fps）
    // 把延迟钉死在上限，慢时丢最旧帧（解码端等下一个关键帧恢复），绝不累积。
    private const val MAX_QUEUE = 6

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private val queue = ArrayDeque<Frame>()
    @Volatile private var config: ByteArray? = null
    @Volatile private var version: Long = 0L
    @Volatile private var active = false
    @Volatile var realWidth = 0; private set
    @Volatile var realHeight = 0; private set
    @Volatile var frameWidth = 0; private set
    @Volatile var frameHeight = 0; private set

    fun activate(rw: Int, rh: Int, fw: Int, fh: Int) {
        synchronized(lock) {
            realWidth = rw; realHeight = rh; frameWidth = fw; frameHeight = fh
            queue.clear()
            config = null
            active = true
            lock.notifyAll()
        }
    }

    fun deactivate() {
        synchronized(lock) {
            active = false
            queue.clear()
            config = null
            version++
            lock.notifyAll()
        }
    }

    fun setConfig(bytes: ByteArray) {
        synchronized(lock) {
            config = bytes
            lock.notifyAll()
        }
    }

    fun currentConfig(): ByteArray? = synchronized(lock) { config }

    fun publishFrame(data: ByteArray, keyframe: Boolean) {
        synchronized(lock) {
            if (!active) return
            queue.addLast(Frame(++version, data, keyframe))
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            lock.notifyAll()
        }
    }

    fun isActive(): Boolean = active

    /** 供 /screen/info 返回给电脑端做点击坐标映射。 */
    fun infoJson(): String = synchronized(lock) {
        "{\"active\":$active,\"real_width\":$realWidth,\"real_height\":$realHeight," +
                "\"frame_width\":$frameWidth,\"frame_height\":$frameHeight}"
    }

    /** 按序取比 lastVersion 新的下一帧（H.264 必须按序），没有则等待到超时或失活。 */
    fun awaitNext(lastVersion: Long, timeoutMs: Long = 150): Frame? {
        synchronized(lock) {
            if (!active) return null
            fun next(): Frame? = queue.firstOrNull { it.version > lastVersion }
            next()?.let { return it }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (active) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                try { lock.wait(remaining) } catch (_: InterruptedException) { return null }
                next()?.let { return it }
            }
            return null
        }
    }
}
