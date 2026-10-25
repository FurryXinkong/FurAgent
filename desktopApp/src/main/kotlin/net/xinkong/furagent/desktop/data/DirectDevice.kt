package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.util.DirectDevice
import net.xinkong.furagent.shared.util.DirectDeviceCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * 直连设备本地存储（桌面）。
 * 数据类 DirectDevice 已移入 shared。
 */
object DirectDeviceStore {
    private const val FILE_NAME = "direct_devices.json"

    private fun file(): File = File(DesktopPaths.configDir(), FILE_NAME)

    private fun atomicWrite(target: File, content: String) {
        net.xinkong.furagent.shared.util.JsonFile.atomicWrite(target, content)
    }

    fun list(): List<DirectDevice> {
        return try {
            val f = file()
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<DirectDevice>()
            var skipped = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) { skipped++; continue }
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
                    DesktopLogger.warn("Direct", "device[$i] 解析失败，已跳过：${e.message}")
                }
            }
            if (skipped > 0) DesktopLogger.warn("Direct", "已跳过 $skipped 个异常设备")
            out.sortedByDescending { it.lastSeenAt }
        } catch (e: Exception) {
            DesktopLogger.error(
                "Direct",
                "读取直连设备失败：${e.javaClass.simpleName}: ${e.message}"
            )
            emptyList()
        }
    }

    fun save(list: List<DirectDevice>) {
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
            atomicWrite(file(), arr.toString(2))
        } catch (e: Exception) {
            DesktopLogger.error(
                "Direct",
                "保存直连设备失败（${list.size} 个）：${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun add(device: DirectDevice) {
        val l = list().toMutableList()
        l.removeAll { it.id == device.id }
        l.add(0, device)
        save(l)
    }

    fun update(device: DirectDevice) {
        val l = list().toMutableList()
        val idx = l.indexOfFirst { it.id == device.id }
        if (idx >= 0) {
            l[idx] = device
            save(l)
        }
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    fun touch(id: String) {
        val l = list().toMutableList()
        val idx = l.indexOfFirst { it.id == id }
        if (idx >= 0) {
            l[idx] = l[idx].copy(lastSeenAt = System.currentTimeMillis())
            save(l)
        }
    }

    fun parseConnectionString(raw: String, defaultName: String = ""): DirectDevice? =
        DirectDeviceCodec.parseConnectionString(raw, defaultName)

    fun buildConnectionString(host: String, port: Int, token: String, name: String = ""): String =
        DirectDeviceCodec.buildConnectionString(host, port, token, name)
}