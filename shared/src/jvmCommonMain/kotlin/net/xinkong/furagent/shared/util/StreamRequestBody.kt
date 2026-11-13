package net.xinkong.furagent.shared.util

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

/**
 * 从输入流边读边写的 HTTP 请求体。
 *
 * 存在意义：手机端通过 SAF 选中的文件是 `content://` URI，没有真实文件路径。
 * 旧实现必须先整份复制到本地暂存目录再上传，几 GB 文件会占用双倍空间、
 * 耗时翻倍，存储不足时直接失败（用户看到的就是「导入不识别」）。
 * 用本类可以直接把 ContentResolver 的输入流写进 socket，全程只占一个 128KB 缓冲。
 *
 * [contentLength] 为实际字节数；<= 0 时 OkHttp 自动退化为 chunked 编码，
 * 两端的 /upload 服务端都支持 chunked。
 */
class StreamRequestBody(
    private val contentLength: Long,
    private val openStream: () -> InputStream?,
    private val onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
) : RequestBody() {

    override fun contentType(): MediaType = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = if (contentLength > 0) contentLength else -1L

    override fun writeTo(sink: BufferedSink) {
        val input = openStream()
            ?: throw IOException("无法打开文件流（文件可能已被移动、删除，或没有读取权限）")
        input.use { stream ->
            val buf = ByteArray(128 * 1024)
            var sent = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n == 0) continue
                sink.write(buf, 0, n)
                sent += n
                onProgress(sent, contentLength)
            }
        }
    }
}
