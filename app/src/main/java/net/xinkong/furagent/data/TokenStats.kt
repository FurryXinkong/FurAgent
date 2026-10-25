package net.xinkong.furagent.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import net.xinkong.furagent.core.AgentLogger

/**
 * Token 用量统计。
 * 不设上限，直接累加；用 Long 存储，理论最大 9.2e18。
 */
object TokenStats {
    private const val PREFS = "FurAgentPrefs"
    private const val KEY_TOTAL = "token_total"
    private const val KEY_PROMPT = "token_prompt"
    private const val KEY_COMPLETION = "token_completion"

    private var _prompt by mutableLongStateOf(0L)
    private var _completion by mutableLongStateOf(0L)
    private var _total by mutableLongStateOf(0L)

    @Suppress("unused")
    val prompt: Long get() = _prompt

    @Suppress("unused")
    val completion: Long get() = _completion

    val total: Long get() = _total

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _prompt = p.getLong(KEY_PROMPT, 0L)
            _completion = p.getLong(KEY_COMPLETION, 0L)
            _total = p.getLong(KEY_TOTAL, 0L)
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "读取 Token 统计失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun add(p: Long, c: Long, t: Long) {
        if (p < 0 || c < 0 || t < 0) return
        _prompt += p
        _completion += c
        _total += t
        persist()
    }

    private fun persist() {
        // 高频调用路径，SharedPreferences 几乎不会失败，保留静默
        try {
            val ctx = net.xinkong.furagent.shared.util.AndroidContextHolder.get() ?: return
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putLong(KEY_PROMPT, _prompt)
                    .putLong(KEY_COMPLETION, _completion)
                    .putLong(KEY_TOTAL, _total)
            }
        } catch (_: Exception) { }
    }

    fun reset() {
        _prompt = 0
        _completion = 0
        _total = 0
        persist()
    }
}