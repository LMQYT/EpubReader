package com.example.epubreader

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 一条 WebDAV 服务器配置（如坚果云 / 飞牛） */
data class WebDavConfig(
    val id: String,
    val name: String,
    val url: String,
    val user: String,
    val pass: String,
    val auto: Boolean,
    val enabled: Boolean,
)

/**
 * WebDAV 云同步引擎：书籍 + 进度，支持多份配置并存、启用其一。
 *
 * 书籍同步放原生层（filesDir/epubs ↔ 云端 EpubReader/ 目录），零 CORS 问题；
 * 进度走 JS 收集（localStorage）后经 MainActivity 交给本引擎上传/下载 progress.json。
 *
 * 智能同步：云端 EpubReader/ 目录维护一份 manifest.json，记录每本 .epub 的 SHA-256。
 * 覆盖上传/云端下载按「本地流式哈希 vs 清单哈希」比对，未变化的文件跳过，避免重复
 * 上传/下载整本书（标准 WebDAV PROPFIND 不返回内容哈希，只能靠这份自维护清单）。
 * 哈希流式计算（8KB 缓冲），绝不整书进内存。
 *
 * 本类只做文件与 WebDAV 的往返，必须在后台线程调用。
 */
class WebDavSync(
    context: Context,
    private val store: EpubStore,
) {
    /** 同步模式 */
    enum class Mode {
        /** 自动同步：保守双向 —— 仅一端有则传，两端同名跳过（不覆盖） */
        AUTO,

        /** 覆盖上传：本地→云端，未变化（哈希一致）跳过，变化/新增覆盖上传 */
        UPLOAD_ALL,

        /** 云端下载：云端→本地，未变化（哈希一致）跳过，变化/新增覆盖下载 */
        DOWNLOAD_ALL,
    }

    /** 同步结果汇总（skipped* 为因哈希一致被跳过的本数） */
    data class SyncResult(
        val uploaded: Int,
        val downloaded: Int,
        val skippedUpload: Int = 0,
        val skippedDownload: Int = 0,
        val error: String? = null,
    )

    /** 云端固定目录：meta 文件（manifest.json / progress.json）在 EpubReader/ 根目录 */
    companion object {
        const val REMOTE_FOLDER = "EpubReader/"
        private const val TAG = "WebDavSync"
        val HEX = "0123456789abcdef".toCharArray()
    }

    /** 书籍子目录：所有 .epub 统一放这里（v1.0.16 起，老版本的书在根目录，同步时边迁移） */
    private val bookFolder = REMOTE_FOLDER + "book/"

    private val remoteFolder = REMOTE_FOLDER

    private val prefs: SharedPreferences =
        context.getSharedPreferences("webdav_config", Context.MODE_PRIVATE)

    private val running = AtomicBoolean(false)

    // ---------------- 配置（多份） ----------------

    /** 读取全部配置；无 configs 键但存在旧单键配置时迁移成一条启用配置 */
    fun getConfigs(): List<WebDavConfig> = loadConfigs()

    /** 当前启用的配置（同一时刻至多一条 enabled=true） */
    fun getActiveConfig(): WebDavConfig? = loadConfigs().firstOrNull { it.enabled }

    fun isConfigured(): Boolean = getActiveConfig()?.url?.isNotBlank() == true

    fun getAutoSync(): Boolean = getActiveConfig()?.auto ?: false

    /**
     * 新增/更新一条配置。若该配置 enabled=true 则其它配置全部置为禁用（互斥）；
     * 若 enabled=false 则只存储、不影响当前启用项。
     */
    fun upsertConfig(cfg: WebDavConfig) {
        val list = loadConfigs().toMutableList()
        val idx = list.indexOfFirst { it.id == cfg.id }
        if (idx >= 0) list[idx] = cfg else list.add(cfg)
        if (cfg.enabled) {
            for (i in list.indices) {
                if (list[i].id != cfg.id && list[i].enabled) {
                    list[i] = list[i].copy(enabled = false)
                }
            }
        }
        saveConfigs(list)
    }

    fun deleteConfig(id: String) {
        val list = loadConfigs().filter { it.id != id }.toMutableList()
        // 删掉了启用项时，自动把剩余第一条启用，保证始终有可用配置
        if (list.isNotEmpty() && list.none { it.enabled }) {
            val first = list.first()
            list[0] = first.copy(enabled = true)
        }
        saveConfigs(list)
    }

    /** 供 JS 显示同步状态：active 配置摘要 + 名称 */
    fun getConfig(): JSONObject = JSONObject().apply {
        val cfg = getActiveConfig()
        put("configured", cfg != null && cfg.url.isNotBlank())
        put("name", cfg?.name ?: "")
        put("url", cfg?.url ?: "")
        put("auto", cfg?.auto ?: false)
        put("lastSync", prefs.getString("last_sync", "") ?: "")
    }

    private fun loadConfigs(): MutableList<WebDavConfig> {
        val raw = prefs.getString("configs", null)
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    WebDavConfig(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "我的配置"),
                        url = o.optString("url", ""),
                        user = o.optString("user", ""),
                        pass = o.optString("pass", ""),
                        auto = o.optBoolean("auto", true),
                        enabled = o.optBoolean("enabled", false),
                    )
                }.toMutableList()
            } catch (e: Exception) {
                Log.e(TAG, "parse configs failed", e)
                mutableListOf()
            }
        }
        // 迁移旧版单键配置（v1.0.12 及以前）
        val url = prefs.getString("url", null)
        if (!url.isNullOrBlank()) {
            val cfg = WebDavConfig(
                id = UUID.randomUUID().toString(),
                name = "我的配置",
                url = url,
                user = prefs.getString("user", "") ?: "",
                pass = prefs.getString("pass", "") ?: "",
                auto = prefs.getBoolean("auto", true),
                enabled = true,
            )
            saveConfigs(mutableListOf(cfg))
            return mutableListOf(cfg)
        }
        return mutableListOf()
    }

    private fun saveConfigs(list: List<WebDavConfig>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("url", c.url)
                put("user", c.user)
                put("pass", c.pass)
                put("auto", c.auto)
                put("enabled", c.enabled)
            })
        }
        prefs.edit().putString("configs", arr.toString()).apply()
    }

    private fun client(): WebDavClient {
        val cfg = getActiveConfig() ?: WebDavConfig("", "", "", "", "", false, false)
        return WebDavClient(cfg.url, cfg.user, cfg.pass)
    }

    // ---------------- 连接测试 ----------------

    /** 用指定配置测试连接（供配置页测试当前编辑的配置）。返回错误信息；null 表示成功。 */
    fun testConnection(cfg: WebDavConfig): String? {
        return try {
            val client = WebDavClient(cfg.url, cfg.user, cfg.pass)
            if (client.test(remoteFolder)) null else "无法访问云端目录"
        } catch (e: WebDavException) {
            e.message ?: "连接失败"
        } catch (e: Exception) {
            "连接失败：${friendlyError(e)}"
        }
    }

    /** 连接异常翻译成用户友好提示；识别证书/自签证书场景（用 https 连内网 NAS 的 WebDAV 会抛证书校验异常） */
    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: "网络错误"
        val isCertError = e is javax.net.ssl.SSLHandshakeException ||
            msg.contains("CertPathValidatorException") ||
            msg.contains("Trust anchor") ||
            msg.contains("SSLHandshakeException")
        return if (isCertError) {
            "$msg。可能是内网环境（如飞牛 NAS 的 WebDAV）证书问题，改用 http:// 地址即可（http://NAS的IP:5005/dav/），无需 https"
        } else {
            msg
        }
    }

    // ---------------- 书籍同步 ----------------

    /** 双目录列表结果：books 为合并清单（book/ 优先，老布局根目录的书并入），rootOnly 为仅存在于根目录的旧书 */
    private class BookListing(
        val books: Map<String, Long>,
        val rootBooks: Map<String, Long>,
        val rootOnly: Set<String>,
    )

    /** 列出云端书籍：同时看 book/ 子目录与根目录（老布局残留），book/ 优先合并。404（目录未建）按空处理，其它错误上抛。 */
    private fun listBooksForSync(client: WebDavClient): BookListing {
        val bookDir = listDirLoose(client, bookFolder)
        val root = listDirLoose(client, remoteFolder)
        val merged = HashMap(bookDir)
        root.forEach { (name, size) -> if (!merged.containsKey(name)) merged[name] = size }
        return BookListing(merged, root, root.keys - bookDir.keys)
    }

    private fun listDirLoose(client: WebDavClient, folder: String): Map<String, Long> {
        return try {
            client.list(folder)
        } catch (e: WebDavException) {
            if (e.message?.contains("HTTP 404") == true) emptyMap() // 目录还没建：按空处理
            else throw e                                              // 401/网络错：真实故障，上抛
        }
    }

    /** 边同步边迁移：book/ 与根目录都有同名书时，根目录副本已冗余，删掉（404 视为已删）。 */
    private fun cleanupRootCopy(client: WebDavClient, name: String) {
        try {
            client.delete(remoteFolder, name)
        } catch (e: Exception) {
            Log.e(TAG, "cleanup root copy $name failed", e)
        }
    }

    /**
     * 执行书籍同步（阻塞，需后台线程）。
     * 返回成功/失败汇总；失败时 result.error 为中文错误信息。
     */
    fun syncBooks(mode: Mode): SyncResult {
        if (!running.compareAndSet(false, true)) {
            return SyncResult(0, 0, error = "同步进行中")
        }
        try {
            val client = client()
            val local = store.listFiles().associateBy { it.name }
            val listing = try {
                client.mkcol(remoteFolder)
                runCatching { client.mkcol(bookFolder) } // book/ 建不了就靠 PUT 自动建（坚果云式）
                listBooksForSync(client)
            } catch (e: WebDavException) {
                Log.e(TAG, "list remote failed", e)
                return SyncResult(0, 0, error = e.message ?: "无法连接云端")
            } catch (e: Exception) {
                Log.e(TAG, "list remote failed", e)
                return SyncResult(0, 0, error = "无法连接云端：${friendlyError(e)}")
            }
            val remote = listing.books
            val rootBooks = listing.rootBooks

            // 云端清单（记录每本 .epub 的 SHA-256）。首次同步没有清单 → 空 map（全部视为有变化）；
            // 真实网络错误则中止本次同步，避免因清单读不到而把整库重传。
            val manifest = try {
                downloadManifest(client)
            } catch (e: Exception) {
                Log.e(TAG, "read manifest failed", e)
                return SyncResult(0, 0, error = "无法读取云端清单：${friendlyError(e)}")
            }

            var uploaded = 0
            var downloaded = 0
            var skippedUpload = 0
            var skippedDownload = 0

            when (mode) {
                Mode.AUTO -> {
                    // 保守双向：本地有云端无 → 上传到 book/；云端有本地无 → 下载；同名跳过（不覆盖）。
                    // 实际发生传输时同步维护清单，保证后续覆盖上传不会重传已同步过的书。
                    val newManifest = HashMap(manifest)
                    var manifestChanged = false
                    // 老布局：本地有、根目录有、book/ 还没有的书，一并上传进 book/（边迁移，根目录副本稍后清理）
                    val localOnly = local.keys - remote.keys + (local.keys intersect listing.rootOnly)
                    val remoteOnly = remote.keys - local.keys
                    for (name in localOnly) {
                        val file = store.resolveFile(name) ?: continue
                        try {
                            client.put(bookFolder, name, file)
                            if (name in rootBooks) cleanupRootCopy(client, name) // 老布局：删根目录旧副本
                            newManifest[name] = sha256Hex(file)
                            uploaded++
                            manifestChanged = true
                        } catch (e: Exception) {
                            Log.e(TAG, "upload $name failed", e)
                        }
                    }
                    for (name in remoteOnly) {
                        try {
                            if (downloadBookWithMigrate(client, name, name in listing.rootOnly)) {
                                newManifest[name] = sha256Hex(store.resolveFile(name) ?: continue)
                                downloaded++
                                manifestChanged = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "download $name failed", e)
                        }
                    }
                    if (manifestChanged) writeManifestSafe(client, newManifest)
                }

                Mode.UPLOAD_ALL -> {
                    // 哈希覆盖上传：云端清单里的哈希与本地一致（且文件确实还在云端）→ 跳过；
                    // 否则上传覆盖到 book/ 并把新哈希写进清单。清单缺失/云端被清空 → 全量重传 + 重建清单。
                    val newManifest = HashMap(manifest)
                    var manifestChanged = false
                    for (name in local.keys) {
                        val file = store.resolveFile(name) ?: continue
                        val inBookDir = name !in listing.rootOnly
                        val mHash = manifest[name]
                        if (inBookDir && mHash != null && remote.containsKey(name) && sha256Hex(file) == mHash) {
                            skippedUpload++
                            continue
                        }
                        try {
                            client.put(bookFolder, name, file)
                            if (name in rootBooks) cleanupRootCopy(client, name) // 老布局：删根目录旧副本
                            newManifest[name] = sha256Hex(file)
                            uploaded++
                            manifestChanged = true
                        } catch (e: Exception) {
                            Log.e(TAG, "upload $name failed", e)
                        }
                    }
                    if (manifestChanged) writeManifestSafe(client, newManifest)
                }

                Mode.DOWNLOAD_ALL -> {
                    // 哈希云端下载：本地内容与清单哈希一致 → 跳过；否则下载覆盖并更新清单。
                    // 清单里没有的云端书（首次同步/清单被删）→ 下载。
                    val newManifest = HashMap(manifest)
                    var manifestChanged = false
                    for (name in remote.keys) {
                        val localFile = local[name]?.let { store.resolveFile(name) }
                        val inBookDir = name !in listing.rootOnly
                        if (localFile != null && inBookDir) {
                            val mHash = manifest[name]
                            if (mHash != null && sha256Hex(localFile) == mHash) {
                                skippedDownload++
                                continue
                            }
                        }
                        try {
                            if (downloadBookWithMigrate(client, name, name in listing.rootOnly)) {
                                newManifest[name] = sha256Hex(store.resolveFile(name) ?: continue)
                                downloaded++
                                manifestChanged = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "download $name failed", e)
                        }
                    }
                    if (manifestChanged) writeManifestSafe(client, newManifest)
                }
            }

            // 边同步边迁移：book/ 与根目录都有同名书时，根目录副本已冗余，删掉旧副本
            for (name in rootBooks.keys) {
                if (name !in listing.rootOnly) cleanupRootCopy(client, name)
            }

            if (uploaded > 0 || downloaded > 0) {
                prefs.edit()
                    .putString("last_sync", System.currentTimeMillis().toString())
                    .apply()
            }
            // 记录最近一次同步看到的云端书籍清单（供删除弹窗判断该书是否在云端）
            prefs.edit()
                .putStringSet("synced_books", remote.keys.toSet())
                .apply()
            return SyncResult(uploaded, downloaded, skippedUpload, skippedDownload)
        } finally {
            running.set(false)
        }
    }

    /**
     * 下载一本书到本地。优先 book/；fromRoot=true 表示仅根目录有（老布局残留），从根目录下完后
     * 顺手把本地文件补传进 book/ 并删掉根目录副本（边同步边迁移）。返回 true 表示下载成功。
     */
    private fun downloadBookWithMigrate(client: WebDavClient, name: String, fromRoot: Boolean): Boolean {
        // 先下到临时文件，成功后再落到书架目录，避免半截文件污染书架
        val tmp = File(store.tempDir, "sync_${System.nanoTime()}_$name")
        try {
            client.get(if (fromRoot) remoteFolder else bookFolder, name, tmp)
            tmp.inputStream().use { store.saveDownloaded(name, it) }
            if (fromRoot) {
                // 老布局书：本地已到手，搬进 book/（迁移）；book/ 补传成功才删根目录副本，避免丢云备份
                val saved = store.resolveFile(name)
                if (saved != null) {
                    runCatching { client.put(bookFolder, name, saved) }
                        .onSuccess { runCatching { client.delete(remoteFolder, name) } }
                }
            }
            return true
        } finally {
            tmp.delete()
        }
    }

    // ---------------- 哈希清单（manifest.json） ----------------

    /**
     * 读取云端 manifest.json（书名 → SHA-256 hex）。文件不存在（首次同步）返回空 map；
     * 其它错误（网络/权限）抛异常，由调用方中止同步。
     */
    private fun downloadManifest(client: WebDavClient): Map<String, String> {
        val tmp = File(store.tempDir, "manifest_${System.nanoTime()}.json")
        try {
            if (!client.getOptional(remoteFolder, "manifest.json", tmp)) return emptyMap()
            return parseManifest(tmp.readText(Charsets.UTF_8))
        } finally {
            tmp.delete()
        }
    }

    private fun parseManifest(json: String): Map<String, String> {
        val map = HashMap<String, String>()
        val obj = JSONObject(json)
        obj.keys().forEach { k -> map[k] = obj.getString(k) }
        return map
    }

    /** 写回 manifest.json（临时文件→PUT，Content-Type json）。失败不影响已完成的书籍同步。 */
    private fun writeManifestSafe(client: WebDavClient, manifest: Map<String, String>) {
        try {
            val obj = JSONObject()
            manifest.forEach { (k, v) -> obj.put(k, v) }
            val tmp = File(store.tempDir, "manifest_${System.nanoTime()}.json")
            try {
                tmp.writeText(obj.toString(), Charsets.UTF_8)
                client.putJson(remoteFolder, "manifest.json", tmp)
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "write manifest failed", e)
        }
    }

    /** 流式计算文件 SHA-256（8KB 缓冲，绝不整文件进内存），返回 hex 小写。 */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8192)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    // ---------------- 进度同步 ----------------

    /** 上传进度 JSON 到云端 progress.json（全量覆盖，本地赢） */
    fun uploadProgress(json: String) {
        try {
            val client = client()
            val tmp = File(store.tempDir, "progress_${System.nanoTime()}.json")
            try {
                tmp.writeText(json, Charsets.UTF_8)
                client.putJson(remoteFolder, "progress.json", tmp)
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload progress failed", e)
        }
    }

    /**
     * AUTO 同步用：把本地收集的进度「合并」进云端 progress.json 再写回。
     *
     * 与 uploadProgress（全量覆盖）不同：覆盖上传是有意让本地赢；而自动同步时本地快照
     * 可能不完整（新设备、刚下载的书本地还没有进度/元数据），若全量覆盖会把云端已有的
     * 进度和封面（epub_meta_* 里含 base64 封面）整个冲掉，导致下载的书丢进度、丢封面。
     * 合并规则：以云端为底，本地覆盖同名 key，云端独有保留（progress / meta / lastOpened 三段）。
     */
    fun uploadProgressMerged(json: String) {
        try {
            val client = client()
            // 云端现有 progress.json（不存在→空对象；读失败也不覆盖云端，降级为只传本地）
            val cloud = downloadProgress()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            val local = JSONObject(json)
            listOf("progress", "meta", "lastOpened").forEach { section ->
                val cloudSec = cloud.optJSONObject(section) ?: JSONObject()
                val localSec = local.optJSONObject(section) ?: JSONObject()
                if (localSec.length() > 0) {
                    val it = localSec.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        cloudSec.put(k, localSec.getString(k))
                    }
                    cloud.put(section, cloudSec)
                }
            }
            val tmp = File(store.tempDir, "progress_${System.nanoTime()}.json")
            try {
                tmp.writeText(cloud.toString(), Charsets.UTF_8)
                client.putJson(remoteFolder, "progress.json", tmp)
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "merge upload progress failed", e)
        }
    }

    /** 下载云端 progress.json，返回内容；无/失败返回 null */
    fun downloadProgress(): String? {
        return try {
            val client = client()
            val tmp = File(store.tempDir, "progress_dl_${System.nanoTime()}.json")
            try {
                client.get(remoteFolder, "progress.json", tmp)
                tmp.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "download progress failed", e)
                null
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 删除云端记录 ----------------

    /** 该书是否在云端（基于最近一次同步记录的云端书籍清单；删除弹窗据此决定是否提示删云） */
    fun hasCloudCopy(name: String): Boolean =
        prefs.getStringSet("synced_books", emptySet())?.contains(name) == true

    /**
     * 删除某本书的云端记录：云端书文件（book/ 主 + 根目录残留）+ manifest 记录 + progress 记录。
     * 文件不存在（404）视为已删除。返回 null=成功，否则为中文错误信息。需后台线程调用。
     */
    fun deleteCloudBook(name: String): String? {
        return try {
            val client = client()
            // 1. 删书文件：book/ 为主（失败上抛）；根目录残留尽力而为
            client.delete(bookFolder, name)
            runCatching { client.delete(remoteFolder, name) }
            // 2. 更新 manifest：移除该书记录
            val manifest = downloadManifest(client)
            if (manifest.containsKey(name)) {
                val newM = HashMap(manifest)
                newM.remove(name)
                writeManifestSafe(client, newM)
            }
            // 3. 更新 progress.json：移除该书进度/封面/最近打开
            removeCloudProgress(client, name)
            // 4. 清掉本地记录的云端清单条目
            val synced = prefs.getStringSet("synced_books", emptySet()) ?: emptySet()
            prefs.edit().putStringSet("synced_books", synced - name).apply()
            null
        } catch (e: Exception) {
            Log.e(TAG, "delete cloud book $name failed", e)
            friendlyError(e)
        }
    }

    /** 从云端 progress.json 移除某本书的 progress/meta/lastOpened 记录（key 以书名结尾） */
    private fun removeCloudProgress(client: WebDavClient, name: String) {
        val tmp = File(store.tempDir, "progress_del_${System.nanoTime()}.json")
        try {
            if (!client.getOptional(remoteFolder, "progress.json", tmp)) return
            val obj = JSONObject(tmp.readText(Charsets.UTF_8))
            var changed = false
            listOf("progress", "meta", "lastOpened").forEach { section ->
                val sec = obj.optJSONObject(section) ?: return@forEach
                val keys = sec.keys().asSequence().filter { it.endsWith(name) }.toList()
                if (keys.isNotEmpty()) {
                    keys.forEach { sec.remove(it) }
                    changed = true
                }
            }
            if (changed) {
                tmp.writeText(obj.toString(), Charsets.UTF_8)
                client.putJson(remoteFolder, "progress.json", tmp)
            }
        } finally {
            tmp.delete()
        }
    }

}
