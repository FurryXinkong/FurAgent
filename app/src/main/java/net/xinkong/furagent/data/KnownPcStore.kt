package net.xinkong.furagent.data

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * 已知电脑。
 *
 * 从哪来：
 *  - 首次连接成功后，从 welcome 消息里读 MAC 自动存一条
 *  - 用户手动添加（知道 MAC 但没连过）
 *
 * 用途：
 *  - 状态面板显示所有"曾经连接过"的电脑
 *  - 离线时提供 WOL 唤醒按钮
 */
data class KnownPc(
    /** 规范化 MAC：无分隔符大写，例如 "AABBCCDDEEFF"。唯一键。 */
    val mac: String,
    /** 显示用 MAC："AA:BB:CC:DD:EE:FF" */
    val displayMac: String,
    /** 设备名（上次连接时收到的 hostname） */
    val deviceName: String,
    /** 上次连接的 IP */
    val host: String,
    /** 上次连接的端口 */
    val port: Int,
    /** 上次在线时间戳 */
    val lastSeenAt: Long
)

object KnownPcStore {

    private const val FILE_NAME = "known_pcs.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    // ============================================================
    // MAC 规范化 / 格式化
    // ============================================================

    /** "AA:BB:CC:DD:EE:FF" / "aa-bb-cc-dd-ee-ff" / "AABBCCDDEEFF" → "AABBCCDDEEFF" */
    fun normalizeMac(raw: String): String =
        raw.replace(Regex("[^0-9A-Fa-f]"), "").uppercase(Locale.ROOT)

    /** "AABBCCDDEEFF" → "AA:BB:CC:DD:EE:FF" */
    fun formatMac(normalized: String): String {
        val s = normalizeMac(normalized)
        if (s.length != 12) return normalized
        return s.chunked(2).joinToString(":")
    }

    // ============================================================
    // 读写
    // ============================================================

    /**
     * 【修复 P0】
     *   - 数组元素不是 object → 跳过
     *   - 单条字段缺失/类型异常 → 单条 try-catch 跳过
     *   - 顶层反序列化失败 → FileRecovery 备份损坏文件
     */
    fun list(context: Context): List<KnownPc> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<KnownPc>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    skipped++
                    continue
                }
                try {
                    val mac = o.optString("mac", "")
                    if (mac.isBlank()) {
                        skipped++
                        continue
                    }
                    out.add(
                        KnownPc(
                            mac = mac,
                            displayMac = o.optString("displayMac", formatMac(mac)),
                            deviceName = o.optString("deviceName", ""),
                            host = o.optString("host", ""),
                            port = o.optInt("port", 8425),
                            lastSeenAt = o.optLong("lastSeenAt", 0L)
                        )
                    )
                } catch (e: Exception) {
                    skipped++
                    AgentLogger.log(
                        "KnownPc",
                        "pc[$i] 解析失败，已跳过：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            if (skipped > 0) {
                AgentLogger.log(
                    "KnownPc",
                    "已跳过 $skipped 条异常记录，保留 ${out.size} 条"
                )
            }
            out.sortedByDescending { it.lastSeenAt }
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "读取已知电脑列表失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file(context))
            emptyList()
        }
    }

    fun find(context: Context, mac: String): KnownPc? {
        val n = normalizeMac(mac)
        if (n.length != 12) return null
        return list(context).firstOrNull { it.mac == n }
    }

    /**
     * 新增或更新（按 mac 去重）。
     * 已存在则合并：
     *  - deviceName / host / port 用新的非空值覆盖
     *  - lastSeenAt 取最新
     */
    fun upsert(context: Context, pc: KnownPc) {
        val n = normalizeMac(pc.mac)
        if (n.length != 12) return

        val current = list(context).toMutableList()
        val idx = current.indexOfFirst { it.mac == n }
        val merged = if (idx >= 0) {
            val old = current[idx]
            old.copy(
                displayMac = formatMac(n),
                deviceName = pc.deviceName.ifBlank { old.deviceName },
                host = pc.host.ifBlank { old.host },
                port = if (pc.port > 0) pc.port else old.port,
                lastSeenAt = maxOf(pc.lastSeenAt, old.lastSeenAt)
            )
        } else {
            pc.copy(mac = n, displayMac = formatMac(n))
        }
        if (idx >= 0) current[idx] = merged else current.add(0, merged)
        save(context, current)
    }

    /** 更新"上次在线时间"。 */
    fun touch(context: Context, mac: String, host: String = "", port: Int = 0) {
        val n = normalizeMac(mac)
        if (n.length != 12) return
        val current = list(context).toMutableList()
        val idx = current.indexOfFirst { it.mac == n }
        if (idx < 0) return
        val old = current[idx]
        current[idx] = old.copy(
            host = host.ifBlank { old.host },
            port = if (port > 0) port else old.port,
            lastSeenAt = System.currentTimeMillis()
        )
        save(context, current)
    }

    fun remove(context: Context, mac: String) {
        val n = normalizeMac(mac)
        save(context, list(context).filterNot { it.mac == n })
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "清空已知电脑失败：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun save(context: Context, list: List<KnownPc>) {
        try {
            val arr = JSONArray()
            list.forEach { p ->
                arr.put(JSONObject().apply {
                    put("mac", p.mac)
                    put("displayMac", p.displayMac)
                    put("deviceName", p.deviceName)
                    put("host", p.host)
                    put("port", p.port)
                    put("lastSeenAt", p.lastSeenAt)
                })
            }
            // 【冗余清理 Batch 12b】内联 atomicWrite 收口到 shared/util/JsonFile
            net.xinkong.furagent.shared.util.JsonFile.atomicWrite(file(context), arr.toString(2))
        } catch (e: Exception) {
            AgentLogger.log(
                "ERR",
                "保存已知电脑失败（${list.size} 台）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}