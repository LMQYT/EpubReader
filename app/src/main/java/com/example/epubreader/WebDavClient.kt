package com.example.epubreader

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（okhttp，零反射）。
 *
 * 支持坚果云（https://dav.jianguoyun.com/dav/）与飞牛 OS 内置 WebDAV（http://<IP>:5005/dav/）：
 * 两者都是标准 WebDAV + Basic Auth，协议统一。
 *
 * 用 okhttp 而非 HttpURLConnection 的原因：Android 的 HttpURLConnection 官方不支持
 * PROPFIND/MKCOL（setRequestMethod 必抛 ProtocolException），只能反射改内部字段；
 * 而反射在部分安卓版本（如安卓 16 真机）会写到不生效的字段 → 实际请求以 GET 发出 →
 * 服务器对不存在的目录回 404（表现为「无法自动创建云端目录」）。okhttp 的
 * Request.Builder.method(...) 原生接受任意方法字符串，不依赖反射。
 *
 * 所有读写均为流式（8KB 缓冲），绝不整文件进内存。
 * 必须在线程池/后台线程调用，禁止在主线程执行。
 */
class WebDavClient(
    baseUrl: String,
    user: String,
    pass: String,
) {
    /** 归一化：保证以 / 结尾 */
    private val base: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val authHeader = "Basic " + Base64.encodeToString(
        "$user:$pass".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 构造带 Basic Auth 的请求；body 为 null 表示无请求体（GET/MKCOL/DELETE 等） */
    private fun request(method: String, url: String, body: RequestBody? = null): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("User-Agent", "EpubReader/1.0")
            .method(method, body)
            .build()

    /** PROPFIND 指定地址，返回状态码（响应体已关闭归还连接池） */
    private fun propfindCode(url: String, depth: String): Int {
        val req = request("PROPFIND", url, PROPFIND_BODY.toRequestBody(PROPFIND_CT))
            .newBuilder()
            .header("Depth", depth)
            .build()
        client.newCall(req).execute().use { resp ->
            resp.body?.close()
            return resp.code
        }
    }

    /**
     * PROPFIND Depth:1 列出 folderPath 下的书籍条目（不含文件夹、不含目录自身）。
     * 返回 文件名 → 云端大小（getcontentlength），供同步引擎按大小比对跳过未变化文件。
     * folderPath 需以 / 结尾。
     */
    fun list(folderPath: String): Map<String, Long> {
        val url = base + folderPath
        val req = request("PROPFIND", url, PROPFIND_BODY.toRequestBody(PROPFIND_CT))
            .newBuilder()
            .header("Depth", "1")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) {
                if (resp.code == 401) throw WebDavException(AUTH_HINT)
                throw WebDavException("列目录失败（HTTP ${resp.code}）")
            }
            val items = resp.body?.byteStream()?.use { parseMultistatus(it) } ?: emptyMap()
            return items
                .filterKeys { it != folderPath }                       // 去目录自身
                .filterKeys { it.endsWith(".epub", ignoreCase = true) } // 只要书籍
                .mapKeys { it.key.substringAfterLast('/') }             // 只取文件名
        }
    }

    /**
     * 解析 PROPFIND multistatus 响应：每条 <d:response> 提取 href + getcontentlength，
     * 返回 Map<href, size>（可能带命名空间前缀 d/D/lp1 等，取冒号后本地名比较）。
     */
    private fun parseMultistatus(input: InputStream): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        var inResponse = false
        var currentHref: String? = null
        var currentLength: Long? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            val local = parser.name?.substringAfterLast(':')
            when {
                event == XmlPullParser.START_TAG && local.equals("response", ignoreCase = true) -> {
                    inResponse = true
                    currentHref = null
                    currentLength = null
                }
                event == XmlPullParser.END_TAG && local.equals("response", ignoreCase = true) -> {
                    if (inResponse && currentHref != null) {
                        val decoded = try {
                            URLDecoder.decode(currentHref, "UTF-8")
                        } catch (e: Exception) {
                            currentHref
                        }
                        result[decoded] = currentLength ?: -1L
                    }
                    inResponse = false
                    currentHref = null
                    currentLength = null
                }
                event == XmlPullParser.START_TAG && inResponse && local.equals("href", ignoreCase = true) ->
                    currentHref = parser.nextText().trim()
                event == XmlPullParser.START_TAG && inResponse && local.equals("getcontentlength", ignoreCase = true) ->
                    currentLength = parser.nextText().trim().toLongOrNull()
            }
            event = parser.next()
        }
        return result
    }

    /** PUT 上传：把本地文件流式写入远端 folderPath/fileName（asRequestBody 不整读进内存）。 */
    fun put(folderPath: String, fileName: String, file: File) {
        val url = base + folderPath + encode(fileName)
        val body = file.asRequestBody("application/epub+zip".toMediaType())
        client.newCall(request("PUT", url, body)).execute().use { resp ->
            if (resp.code !in 200..299) {
                if (resp.code == 401) throw WebDavException(AUTH_HINT)
                throw WebDavException("上传 $fileName 失败（HTTP ${resp.code}）")
            }
        }
    }

    /** GET 下载：把远端 folderPath/fileName 流式写入本地文件。 */
    fun get(folderPath: String, fileName: String, dest: File) {
        val url = base + folderPath + encode(fileName)
        client.newCall(request("GET", url)).execute().use { resp ->
            if (resp.code !in 200..299) {
                if (resp.code == 404) throw WebDavException("$fileName 在云端不存在（HTTP 404）")
                if (resp.code == 401) throw WebDavException(AUTH_HINT)
                throw WebDavException("下载 $fileName 失败（HTTP ${resp.code}）")
            }
            resp.body?.byteStream()?.use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out, 8192) }
            }
        }
    }

    /**
     * GET 下载：文件存在写入 dest 返回 true；不存在（404）返回 false，不视为错误。
     * 供 manifest.json 等「可能还没有」的文件读取，区分首次同步与真实网络错误。
     */
    fun getOptional(folderPath: String, fileName: String, dest: File): Boolean {
        val url = base + folderPath + encode(fileName)
        client.newCall(request("GET", url)).execute().use { resp ->
            if (resp.code == 404) return false
            if (resp.code !in 200..299) {
                if (resp.code == 401) throw WebDavException(AUTH_HINT)
                throw WebDavException("下载 $fileName 失败（HTTP ${resp.code}）")
            }
            resp.body?.byteStream()?.use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out, 8192) }
            }
            return true
        }
    }

    /** DELETE 删除远端文件；404（不存在）视为已删除（幂等），不报错。folderPath 需以 / 结尾。 */
    fun delete(folderPath: String, fileName: String) {
        val url = base + folderPath + encode(fileName)
        client.newCall(request("DELETE", url)).execute().use { resp ->
            if (resp.code !in 200..299 && resp.code != 404) {
                if (resp.code == 401) throw WebDavException(AUTH_HINT)
                throw WebDavException("删除 $fileName 失败（HTTP ${resp.code}）")
            }
        }
    }

    /** PUT JSON（progress.json）：流式写 body，Content-Type application/json */
    fun putJson(folderPath: String, fileName: String, file: File) {
        val url = base + folderPath + encode(fileName)
        val body = file.asRequestBody("application/json; charset=utf-8".toMediaType())
        client.newCall(request("PUT", url, body)).execute().use { resp ->
            if (resp.code !in 200..299) {
                if (resp.code == 401) throw WebDavException(AUTH_HINT)
                throw WebDavException("上传 $fileName 失败（HTTP ${resp.code}）")
            }
        }
    }

    /** MKCOL 创建目录；已存在（405/301）视为成功。坚果云对 MKCOL 有频率限制，失败后稍等重试一次。 */
    fun mkcol(folderPath: String) {
        val url = base + folderPath.trimEnd('/')
        var code = -1
        for (attempt in 0..1) {
            client.newCall(request("MKCOL", url)).execute().use { resp ->
                code = resp.code
                if (code == 201 || code == 200 || code == 405) return
                if (attempt == 0) Thread.sleep(500) // 坚果云 MKCOL 频率限制：稍等再试
            }
        }
        throw WebDavException(folderCreateHint("MKCOL", url, code))
    }

    /**
     * 连接测试：确保远端目录存在（不存在则创建），并对它 PROPFIND。
     * 返回 true 表示可读写目录。
     */
    fun test(folderPath: String): Boolean {
        // 先探测目录：404 就尝试创建
        val probeUrl = base + folderPath
        val probeCode = propfindCode(probeUrl, "0")
        if (probeCode in 200..299) return true
        if (probeCode == 401) throw WebDavException(AUTH_HINT)
        if (probeCode != 404 && probeCode != 405) {
            throw WebDavException("连接失败（HTTP $probeCode）")
        }
        // 目录不存在 → 尝试创建；失败会抛带精确请求信息的指引
        mkcol(folderPath)
        // 建好后再 PROPFIND 验证：仍不存在则报错并附请求细节
        val verifyCode = propfindCode(probeUrl, "0")
        if (verifyCode in 200..299) return true
        throw WebDavException(folderCreateHint("PROPFIND", probeUrl, verifyCode))
    }

    /** 建目录失败时的可执行提示，附精确请求信息（方法+地址+状态码）用于定位。 */
    private fun folderCreateHint(method: String, url: String, code: Int): String {
        val detail = "$method $url → HTTP $code"
        val jianguoyun = base.contains("jianguoyun.com", ignoreCase = true)
        return if (jianguoyun) {
            "$detail。坚果云地址应为 https://dav.jianguoyun.com/dav/（末尾 /dav/ 别漏）；坚果云官方支持自动建目录，若仍失败请到网页版新建 EpubReader 文件夹后重试"
        } else {
            "$detail。飞牛地址应为 http://NAS的IP:5005/dav/；若仍失败请手动在网盘根目录建立 EpubReader 文件夹"
        }
    }

    companion object {
        private const val AUTH_HINT =
            "认证失败（HTTP 401）：账号或密码错误。坚果云请用网页端「安全」里生成的「应用密码」（不是登录密码）；飞牛请确认 WebDAV 服务已开启且该账号有权限"

        private val PROPFIND_CT = "application/xml; charset=utf-8".toMediaType()
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:getcontentlength/>
              </d:prop>
            </d:propfind>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        /** URL 编码文件名（保留原样避免 / 冲突），只转义需要转义的字符 */
        fun encode(name: String): String {
            return java.net.URLEncoder.encode(name, "UTF-8")
                .replace("+", "%20")
                .replace("%2F", "/")
        }
    }
}

/** WebDAV 业务异常（message 为中文，可直接展示给用户） */
class WebDavException(message: String) : Exception(message)
