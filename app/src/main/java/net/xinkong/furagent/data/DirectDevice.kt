package net.xinkong.furagent.data

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.util.DirectDevice
import net.xinkong.furagent.shared.util.DirectDeviceCodec
import net.xinkong.furagent.shared.util.FileRecovery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * 直连设备本地存储（Android）。
 * 数据类 DirectDevice 已移入 shared，这里只负责 SharedPreferences / 文件持久化。
 */
object DirectDeviceStore {
    private const val FILE_NAME = "direct_devices.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun atomicWrite(target: File, content: String) {
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    /**
     *   - 数组元素不是 object → 跳过
     *   - 单条字段缺失/类型异常 → 单条 try-catch 跳过
     *   - 顶层反序列化失败 → FileRecovery 备份损坏文件
     */
    fun list(context: Context): List<DirectDevice> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<DirectDevice>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    skipped++
                    continue
                }
                try {
                    out.add(
                        DirectDevice(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", ""),
                            host = o.optString("host", ""),
                            port = o.optInt("port", 0),
                            token = o.optString("token", ""),
                            lastSeenAt = o.optLong("lastSeenAt", 0L)
                        )
                    )
                } catch (e: Exception) {
                    skipped++
                    AgentLogger.log(
                        "DIRECT",
                        "device[$i] 解析失败，已跳过：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            if (skipped > 0) {
                AgentLogger.log(
                    "DIRECT",
                    "已跳过 $skipped 个异常设备，保留 ${out.size} 个"
                )
            }
            out.sortedByDescending { it.lastSeenAt }
        } catch (e: Exception) {
            AgentLogger.log(
                "DIRECT",
                "读取直连设备失败，已备份损坏文件：${e.javaClass.simpleName}: ${e.message}"
            )
            FileRecovery.backupCorrupted(file(context))
            emptyList()
        }
    }

    fun save(context: Context, list: List<DirectDevice>) {
        try {
            val arr = JSONArray()
            list.forEach { d ->
                arr.put(JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("host", d.host)
                    put("port", d.port)
                    put("token", d.token)
                    put("lastSeenAt", d.lastSeenAt)
                })
            }
            atomicWrite(file(context), arr.toString(2))
        } catch (e: Exception) {
            AgentLogger.log(
                "DIRECT",
                "保存直连设备失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun add(context: Context, device: DirectDevice) {
        val l = list(context).toMutableList()
        l.removeAll { it.id == device.id }
        l.add(0, device)
        save(context, l)
    }

    fun update(context: Context, device: DirectDevice) {
        val l = list(context).toMutableList()
        val idx = l.indexOfFirst { it.id == device.id }
        if (idx >= 0) {
            l[idx] = device
            save(context, l)
        }
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    fun touch(context: Context, id: String) {
        val l = list(context).toMutableList()
        val idx = l.indexOfFirst { it.id == id }
        if (idx >= 0) {
            l[idx] = l[idx].copy(lastSeenAt = System.currentTimeMillis())
            save(context, l)
        }
    }

    // 快捷转发到 shared 的 DirectDeviceCodec
    fun parseConnectionString(raw: String, defaultName: String = ""): DirectDevice? =
        DirectDeviceCodec.parseConnectionString(raw, defaultName)

    fun buildConnectionString(host: String, port: Int, token: String, name: String = ""): String =
        DirectDeviceCodec.buildConnectionString(host, port, token, name)
}