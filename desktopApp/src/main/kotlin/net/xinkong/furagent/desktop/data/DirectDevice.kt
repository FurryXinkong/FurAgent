package net.xinkong.furagent.desktop.data

import net.xinkong.furagent.desktop.system.DesktopLogger
import net.xinkong.furagent.shared.util.DirectDevice
import net.xinkong.furagent.shared.util.DirectDeviceCodec
import net.xinkong.furagent.shared.util.DirectDeviceJsonCodec
import java.io.File

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
        val f = file()
        if (!f.exists()) return emptyList()
        val result = DirectDeviceJsonCodec.parseList(f.readText(Charsets.UTF_8)) { msg ->
            DesktopLogger.warn("Direct", msg)
        }
        if (result == null) {
            DesktopLogger.error("Direct", "读取直连设备失败")
            return emptyList()
        }
        return result
    }

    fun save(list: List<DirectDevice>) {
        try {
            atomicWrite(file(), DirectDeviceJsonCodec.serializeList(list))
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