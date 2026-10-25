package net.xinkong.furagent.system

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.xinkong.furagent.core.AgentLogger
import java.util.*

/**
 * Android TTS 封装。
 *
 * 错误处理对齐桌面端 DesktopSpeechHelper：
 *  - 完整 state（speaking / text / error）
 *  - error 持久保留，直到 clearError() 或 reset()
 *  - stop() 不清 error
 *  - clearError() 只清 error，不动 speaking
 *  - reset() 全清（切会话用）
 *
 * 兼容层：
 *  - isSpeaking: StateFlow<Boolean>（老调用方仍可用）
 *  - lastError / languageMissing 保留为 state.error 的映射
 */
object SpeechHelper {

    private val RE_SENTENCE_SPLIT = Regex("(?<=[。！？\n])")

    data class State(
        val speaking: Boolean = false,
        val text: String = "",
        val error: String? = null,
        val languageMissing: Boolean = false
    )

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var initializing = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 兼容：老的 Boolean StateFlow。 */
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // 分段朗读：把长文本切成句子段，逐段加入 TTS 队列。
    // 支持 pause() 暂停 / resume() 从上次位置继续。
    // UI 层（浮层 / 消息长按菜单）下一轮补。
    @Volatile private var segments: List<String> = emptyList()
    @Volatile private var currentSegmentIdx: Int = 0
    @Volatile private var paused: Boolean = false

    /** 兼容：老调用方读 lastError。 */
    val lastError: String?
        get() = _state.value.error

    /** 兼容：老调用方读 languageMissing。 */
    val languageMissing: Boolean
        get() = _state.value.languageMissing

    fun init(context: Context) {
        if (tts != null || initializing) return
        initializing = true
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                initializing = false
                if (ready) {
                    try {
                        val r = tts?.setLanguage(Locale.CHINA)
                        val miss = (r == TextToSpeech.LANG_MISSING_DATA ||
                                r == TextToSpeech.LANG_NOT_SUPPORTED)
                        if (miss) {
                            AgentLogger.log("SYS", "TTS 中文语音包未安装")
                            _state.value = _state.value.copy(
                                error = "中文语音包未安装",
                                languageMissing = true
                            )
                        }
                        tts?.setSpeechRate(1.0f)
                        tts?.setPitch(1.0f)
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                // 跟踪当前段索引，供 resume() 用
                                if (utteranceId != null && utteranceId.startsWith("seg_")) {
                                    val i = utteranceId.removePrefix("seg_").toIntOrNull()
                                    if (i != null) currentSegmentIdx = i
                                }
                                _isSpeaking.value = true
                                _state.value = _state.value.copy(speaking = true)
                            }
                            override fun onDone(utteranceId: String?) {
                                _isSpeaking.value = false
                                _state.value = _state.value.copy(speaking = false)
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                _isSpeaking.value = false
                                AgentLogger.log("SYS", "TTS onError（utteranceId=$utteranceId）")
                                _state.value = _state.value.copy(
                                    speaking = false,
                                    error = "朗读失败"
                                )
                            }
                        })
                    } catch (e: Exception) {
                        AgentLogger.log(
                            "ERR",
                            "TTS 初始化后配置失败：${e.javaClass.simpleName}: ${e.message}"
                        )
                        _state.value = _state.value.copy(error = e.message ?: "TTS 配置失败")
                    }
                } else {
                    AgentLogger.log("ERR", "TTS 初始化失败（status=$status）")
                    _state.value = _state.value.copy(error = "TTS 初始化失败")
                }
            }
        } catch (e: Exception) {
            initializing = false
            AgentLogger.log(
                "ERR",
                "创建 TTS 实例失败：${e.javaClass.simpleName}: ${e.message}"
            )
            _state.value = _state.value.copy(error = e.message ?: "创建 TTS 失败")
        }
    }

    fun isReady(): Boolean = ready

    fun speak(text: String, flush: Boolean = true): Boolean {
        if (!ready || tts == null) {
            return false
        }
        if (text.isBlank()) return false
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return try {
            val result = tts?.speak(text, mode, null, "furagent_${System.currentTimeMillis()}")
            if (result == TextToSpeech.ERROR) {
                _isSpeaking.value = false
                AgentLogger.log("ERR", "TTS speak 返回 ERROR（文本长度=${text.length}）")
                _state.value = _state.value.copy(
                    speaking = false,
                    error = "朗读失败（系统返回 ERROR）"
                )
                false
            } else {
                _isSpeaking.value = true
                _state.value = _state.value.copy(speaking = true, text = text)
                true
            }
        } catch (e: Exception) {
            _isSpeaking.value = false
            AgentLogger.log(
                "ERR",
                "TTS speak 异常：${e.javaClass.simpleName}: ${e.message}"
            )
            _state.value = _state.value.copy(
                speaking = false,
                error = "朗读异常：${e.message}"
            )
            false
        }
    }

    /**
     * 分段朗读：把文本按句号 / 感叹号 / 问号 / 换行切段，逐段加入 TTS 队列。
     * 长文本可暂停 / 继续。
     */
    fun speakSegmented(text: String): Boolean {
        if (!ready || tts == null) return false
        if (text.isBlank()) return false
        val segs = text.split(Regex("(?<=[。！？!?\\n])"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (segs.isEmpty()) return false
        segments = segs
        currentSegmentIdx = 0
        paused = false
        return speakFrom(0)
    }

    private fun speakFrom(idx: Int): Boolean {
        if (idx >= segments.size) return false
        currentSegmentIdx = idx
        for (i in idx until segments.size) {
            val seg = segments[i]
            try {
                tts?.speak(
                    seg,
                    if (i == idx) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    "seg_$i"
                )
            } catch (_: Exception) { }
        }
        _isSpeaking.value = true
        _state.value = _state.value.copy(speaking = true)
        return true
    }

    /** 暂停：停止当前朗读，记住位置。 */
    fun pause() {
        if (segments.isEmpty()) {
            stop()
            return
        }
        paused = true
        try { tts?.stop() } catch (_: Exception) { }
        _isSpeaking.value = false
        _state.value = _state.value.copy(speaking = false)
    }

    /** 继续：从上次暂停的位置开始。 */
    fun resume(): Boolean {
        if (!paused || segments.isEmpty()) return false
        paused = false
        return speakFrom(currentSegmentIdx)
    }

    /** 当前是否处于暂停状态。 */
    fun isPaused(): Boolean = paused

    /** 停止朗读。**不清除 error**。 */
    fun stop() {
        try { tts?.stop() } catch (_: Exception) { }
        _isSpeaking.value = false
        _state.value = _state.value.copy(speaking = false)
        // stop 时清空分段状态
        segments = emptyList()
        currentSegmentIdx = 0
        paused = false
    }

    /** 只清 error，不动 speaking。 */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 彻底重置（切换会话用）。 */
    fun reset() {
        stop()
        _state.value = State()
        _isSpeaking.value = false
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) { }
        tts = null
        ready = false
        _isSpeaking.value = false
        _state.value = State()
    }
}