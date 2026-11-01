package net.xinkong.furagent.data

import android.content.Context
import net.xinkong.furagent.core.AgentLogger
import net.xinkong.furagent.shared.util.DirectDevice
import net.xinkong.furagent.shared.util.DirectDeviceCodec
import net.xinkong.furagent.shared.util.DirectDeviceJsonCodec
import net.xinkong.furagent.shared.util.FileRecovery
import java.io.File

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
        val f = file(context)
        if (!f.exists()) return emptyList()
        val result = DirectDeviceJsonCodec.parseList(f.readText(Charsets.UTF_8)) { msg ->
            AgentLogger.log("DIRECT", msg)
        }
        if (result == null) {
            AgentLogger.log("DIRECT", "读取直连设备失败，已备份损坏文件")
            FileRecovery.backupCorrupted(f)
            return emptyList()
        }
        return result
    }

    fun save(context: Context, list: List<DirectDevice>) {
        try {
            atomicWrite(file(context), DirectDeviceJsonCodec.serializeList(list))
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