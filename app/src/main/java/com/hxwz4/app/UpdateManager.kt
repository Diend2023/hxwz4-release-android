package com.hxwz4.app

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 更新过程状态，供界面展示。 */
data class UpdateState(
    val phase: Phase,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = ""
) {
    enum class Phase { INIT, CHECKING, DOWNLOADING, DONE, FAILED }
}

/**
 * 热更新管理器：
 * 1. 首次启动：将 APK assets/www 基线复制到 files/web，并写入随包 md5s.json 作为本地期望状态；
 * 2. 常规启动：拉取远程 md5s.json，与本地对比做差异收敛（只增不改，不做删除）；
 * 3. 下载校验 MD5，失败重试后跳过，不中断整体。
 *
 * 约束：index.html 完全豁免——不下载、不覆盖、不删除，始终使用 APK 内置基线。
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        /** CNB 仓库 raw 基地址（路径必须带 /-/git/raw/ 段）。 */
        const val BASE_URL = "https://cnb.cool/hxwz4/hxwz4-release/-/git/raw/main/"

        /** 远程清单文件名。 */
        private const val MD5_FILE = "md5s.json"

        /** 豁免文件白名单：index.html 永不参与热更新。 */
        private val LOCKED_FILES = setOf("index.html")

        /** 下载失败最大重试次数。 */
        private const val MAX_RETRY = 2

        /** 并发下载数。 */
        private const val CONCURRENCY = 6
    }

    private val webDir: File = File(context.filesDir, "web")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 执行更新流程，[onState] 在 UI 线程回调。 */
    suspend fun run(onState: (UpdateState) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                // 1) 首次启动：基线初始化
                if (!File(webDir, "index.html").exists()) {
                    post(onState, UpdateState(UpdateState.Phase.INIT, message = "初始化本地资源…"))
                    copyAssetsToWeb { done, total ->
                        post(onState, UpdateState(UpdateState.Phase.INIT, done, total, "初始化本地资源 $done/$total"))
                    }
                }

                // 2) 拉取远程清单
                post(onState, UpdateState(UpdateState.Phase.CHECKING, message = "检查更新…"))
                val remoteMd5s = fetchRemoteMd5s()
                    ?: throw IOException("无法获取远程更新清单 md5s.json")

                // 3) 差异计算
                val localMd5s = readLocalMd5s()
                val tasks = buildDiffTasks(remoteMd5s, localMd5s)

                // 4) 增量下载
                if (tasks.isNotEmpty()) {
                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, 0, tasks.size, "下载更新 0/${tasks.size}"))
                    val done = AtomicInteger(0)
                    val semaphore = Semaphore(CONCURRENCY)
                    coroutineScope {
                        tasks.map { (path, md5) ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val ok = downloadWithRetry(path, md5)
                                    val n = done.incrementAndGet()
                                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, n, tasks.size, "下载更新 $n/${tasks.size}"))
                                    if (!ok) {
                                        Log.w(TAG, "跳过失败文件: $path")
                                    }
                                }
                            }
                        }.awaitAll()
                    }

                    // 收敛完成后写入本地期望清单
                    writeLocalMd5s(remoteMd5s)
                    post(onState, UpdateState(UpdateState.Phase.DONE, tasks.size, tasks.size, "更新完成"))
                } else {
                    writeLocalMd5s(remoteMd5s)
                    post(onState, UpdateState(UpdateState.Phase.DONE, message = "已是最新版本"))
                }
            } catch (e: CancellationException) {
                // 用户跳过：协程正常取消，不当作失败
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                post(onState, UpdateState(UpdateState.Phase.FAILED, message = e.message ?: "更新失败"))
            }
        }
    }

    // ------------------------------------------------------------------
    // 首次启动：基线复制
    // ------------------------------------------------------------------

    private suspend fun copyAssetsToWeb(onProgress: suspend (Int, Int) -> Unit) {
        val am = context.assets
        val files = ArrayList<String>()
        // 收集相对 web 根的路径（剥离 assets 中的 www 前缀），
        // 保证 files/web 布局与远程清单（无 www 前缀）完全一致
        collectAssets(am, "www", "", files)
        webDir.mkdirs()

        var done = 0
        for (rel in files) {
            val target = File(webDir, rel)
            target.parentFile?.mkdirs()
            // assets 中实际路径需带 www/ 前缀
            am.open("www/$rel").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            done++
            onProgress(done, files.size)
        }
    }

    private fun collectAssets(am: AssetManager, assetsDir: String, relDir: String, out: MutableList<String>) {
        val entries = am.list(assetsDir) ?: return
        for (name in entries) {
            val assetsPath = if (assetsDir.isEmpty()) name else "$assetsDir/$name"
            val relPath = if (relDir.isEmpty()) name else "$relDir/$name"
            // 目录的 list 返回子项；文件返回空数组
            val sub = am.list(assetsPath)
            if (sub != null && sub.isNotEmpty()) {
                collectAssets(am, assetsPath, relPath, out)
            } else {
                out.add(relPath)
            }
        }
    }

    // ------------------------------------------------------------------
    // 清单对比
    // ------------------------------------------------------------------

    private fun fetchRemoteMd5s(): JSONObject? {
        val request = Request.Builder().url(BASE_URL + MD5_FILE).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return runCatching { JSONObject(stripBom(body)) }
                .onFailure { Log.w(TAG, "远程 md5s.json 解析失败", it) }
                .getOrNull()
        }
    }

    private fun readLocalMd5s(): JSONObject {
        val file = File(webDir, MD5_FILE)
        return if (file.exists()) {
            runCatching { JSONObject(stripBom(file.readText())) }.getOrElse {
                Log.w(TAG, "本地 md5s.json 解析失败，按空清单处理", it)
                JSONObject()
            }
        } else {
            JSONObject()
        }
    }

    /** 兼容带 UTF-8 BOM 的清单文本（PowerShell 生成产物曾带 BOM）。 */
    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

    private fun writeLocalMd5s(remote: JSONObject) {
        val file = File(webDir, MD5_FILE)
        file.writeText(remote.toString())
    }

    /** 计算差异下载任务列表；index.html 豁免；本地多余文件不删除。 */
    private fun buildDiffTasks(remote: JSONObject, local: JSONObject): List<Pair<String, String>> {
        val tasks = ArrayList<Pair<String, String>>()
        val keys = remote.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            if (LOCKED_FILES.contains(path)) continue
            val remoteMd5 = remote.optString(path, "")
            val localMd5 = local.optString(path, null)
            val localFile = File(webDir, path)
            // 本地清单 MD5 与远程一致且文件存在 → 视为已就绪
            if (localFile.exists() && localMd5 == remoteMd5) continue
            tasks.add(path to remoteMd5)
        }
        return tasks
    }

    // ------------------------------------------------------------------
    // 增量下载
    // ------------------------------------------------------------------

    private fun downloadWithRetry(path: String, expectedMd5: String): Boolean {
        var attempt = 0
        while (attempt <= MAX_RETRY) {
            val tmp = File(webDir, "$path.tmp$attempt")
            try {
                downloadFile(path, tmp)
                if (FileHasher.md5File(tmp) == expectedMd5) {
                    val target = File(webDir, path)
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        // renameTo 失败时退化为复制
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    return true
                }
                Log.w(TAG, "MD5 校验失败: $path (attempt=$attempt)")
                tmp.delete()
            } catch (e: CancellationException) {
                // 用户跳过：清理临时文件后停止
                tmp.delete()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "下载失败: $path (attempt=$attempt)", e)
                tmp.delete()
            }
            attempt++
        }
        return false
    }

    private fun downloadFile(path: String, target: File) {
        val url = BASE_URL + encodePath(path)
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $path")
            val body = resp.body ?: throw IOException("empty body for $path")
            FileOutputStream(target).use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
    }

    /** 按路径段分别 URL 编码（保留 / 分隔）。 */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private suspend fun post(onState: (UpdateState) -> Unit, state: UpdateState) {
        withContext(Dispatchers.Main) { onState(state) }
    }
}
