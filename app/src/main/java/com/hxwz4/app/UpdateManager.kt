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
 * 1. 首次启动：将 APK assets/www 基线复制到外部私有目录（getExternalFilesDir/web），
 *    并写入随包 md5s.json 作为本地期望状态；
 * 2. 常规启动：拉取远程 md5s.json，与本地对比做差异收敛（差异覆盖、不删除多余文件）；
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

        /**
         * 豁免文件白名单：永不参与热更新（不下载、不写入本地清单）。
         * - index.html：始终使用 APK 内置版本（移动端适配）
         * - md5s.json：本地期望清单由 writeLocalMd5s 生成，若远程清单含自身条目
         *   （发布工具差异）也不应被下载覆盖
         */
        private val LOCKED_FILES = setOf("index.html", "md5s.json")

        /** 下载失败最大重试次数。 */
        private const val MAX_RETRY = 2

        /** 并发下载数。 */
        private const val CONCURRENCY = 6

        /**
         * 解析游戏资源根目录（外部存储私有目录 files/web 的镜像）。
         * 优先使用外部私有目录（无需存储权限，卸载自动清除），
         * 外部存储不可用时回退到内部 files/web。
         */
        fun resolveWebDir(context: Context): File {
            val ext = context.getExternalFilesDir(null)
            return if (ext != null) File(ext, "web") else File(context.filesDir, "web")
        }
    }

    private val webDir: File = resolveWebDir(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 执行更新流程，[onState] 在 UI 线程回调。 */
    suspend fun run(onState: (UpdateState) -> Unit) {
        withContext(Dispatchers.IO) {
            // 提升到 try 外：取消/异常时也要能收敛已成功的部分
            val failedPaths = mutableSetOf<String>()
            var remoteMd5s: JSONObject? = null
            var taskCount = 0
            try {
                // 0) 清理上次中断残留的临时文件
                cleanTmpFiles()

                // 1) 首次启动：基线初始化。
                //    以 index.html 是否存在判断完成（copyAssetsToWeb 保证它最后复制），
                //    中断时它尚未落盘 → 下次重新复制（覆盖幂等）；
                //    旧版本升级用户 index.html 已存在 → 直接跳过，无感。
                if (!File(webDir, "index.html").exists()) {
                    post(onState, UpdateState(UpdateState.Phase.INIT, message = "初始化本地资源…"))
                    copyAssetsToWeb { done, total ->
                        post(onState, UpdateState(UpdateState.Phase.INIT, done, total, "初始化本地资源 $done/$total"))
                    }
                }

                // 2) 拉取远程清单
                post(onState, UpdateState(UpdateState.Phase.CHECKING, message = "检查更新…"))
                remoteMd5s = fetchRemoteMd5s()
                    ?: throw IOException("无法获取远程更新清单 md5s.json")

                // 3) 差异计算
                val localMd5s = readLocalMd5s()
                val tasks = buildDiffTasks(remoteMd5s, localMd5s)

                // 4) 增量下载（记录失败文件，供清单合并时保留本地条目）
                taskCount = tasks.size
                if (tasks.isNotEmpty()) {
                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, 0, tasks.size, "下载更新 0/${tasks.size}"))
                    val done = AtomicInteger(0)
                    val semaphore = Semaphore(CONCURRENCY)
                    coroutineScope {
                        tasks.map { (path, md5) ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val actualMd5 = downloadWithRetry(path, md5)
                                    val n = done.incrementAndGet()
                                    post(onState, UpdateState(UpdateState.Phase.DOWNLOADING, n, tasks.size, "下载更新 $n/${tasks.size}"))
                                    if (actualMd5 == null) {
                                        Log.w(TAG, "跳过失败文件: $path")
                                        synchronized(failedPaths) { failedPaths.add(path) }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                // 收敛后写回本地期望清单：以远程（cnb）为准，但
                //   - index.html 等白名单永不写入
                //   - 本次失败的文件保留本地原条目，下次启动继续尝试
                writeLocalMd5s(remoteMd5s, failedPaths)
                if (taskCount > 0) {
                    post(onState, UpdateState(UpdateState.Phase.DONE, taskCount, taskCount, "更新完成"))
                } else {
                    post(onState, UpdateState(UpdateState.Phase.DONE, message = "已是最新版本"))
                }
            } catch (e: CancellationException) {
                // 用户跳过：也收敛已成功的文件，避免下次启动重复下载
                remoteMd5s?.let { writeLocalMd5s(it, failedPaths) }
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

        // index.html 调整到最后复制：它是 WebView 入口，落盘即代表基线完整。
        // 复制过程被杀/断电时它尚未写入，下次启动检测到缺失会重新完整复制。
        val ordered = files.filter { it != "index.html" } + files.filter { it == "index.html" }

        var done = 0
        for (rel in ordered) {
            val target = File(webDir, rel)
            target.parentFile?.mkdirs()
            // assets 中实际路径需带 www/ 前缀
            am.open("www/$rel").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            done++
            onProgress(done, ordered.size)
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

    /**
     * 写回本地期望清单：**以远程（cnb）清单为准**，但：
     * - `index.html` 等白名单条目永不写入（客户端豁免，始终用 APK 内置版本）
     * - 本次下载失败的文件保留本地原条目（或不加条目），下次启动继续尝试
     * - 远程已移除、本地仍保留的文件（只增不删策略）：保留本地条目
     *
     * 这样「均使用 cnb md5」的同时不丢失失败重试能力。
     */
    private fun writeLocalMd5s(remote: JSONObject, failedPaths: Set<String>) {
        val local = readLocalMd5s()
        val merged = JSONObject()
        // 1) 远程条目为准（跳过白名单与本次失败文件）
        for (name in remote.keys()) {
            if (name in LOCKED_FILES) continue
            if (name in failedPaths) continue
            merged.put(name, remote.getString(name))
        }
        // 2) 失败文件保留本地原条目（本地没有则不加，下次仍判差异重试）
        for (name in failedPaths) {
            if (local.has(name)) merged.put(name, local.getString(name))
        }
        // 3) 远程已移除但本地保留的文件：保留本地条目
        for (name in local.keys()) {
            if (name in LOCKED_FILES) continue
            if (!merged.has(name) && !remote.has(name)) {
                merged.put(name, local.getString(name))
            }
        }
        // 原子写：先写临时文件再改名，避免写一半断电导致清单损坏（损坏会触发全量下载）
        val tmp = File(webDir, "$MD5_FILE.tmp")
        tmp.writeText(merged.toString())
        val target = File(webDir, MD5_FILE)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 清理上次运行中断残留的临时文件（下载 .tmpN 与 md5s.json.tmp）。 */
    private fun cleanTmpFiles() {
        if (!webDir.isDirectory) return
        var cleaned = 0
        webDir.walkTopDown().forEach { file ->
            val name = file.name
            if (file.isFile && (name.endsWith(".tmp") || Regex(""".*\.tmp\d+$""").matches(name))) {
                if (file.delete()) cleaned++
            }
        }
        if (cleaned > 0) Log.i(TAG, "清理残留临时文件: $cleaned 个")
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

    /**
     * 下载单文件并原子替换。返回该文件在本地清单中应记录的 MD5，
     * 失败返回 null（调用方跳过该文件，下次启动继续尝试）。
     *
     * 三级处理：
     * 1. 实际 MD5 == 远程声明值 → 正常安装，返回声明值
     * 2. 不符但本地已有相同内容 → 远程清单过期（声明值与实际文件不符），
     *    内容已是最新无需替换，返回声明值使下次启动 0 差异，避免重复下载
     *    （典型场景：repo 改了 HxwzHaxe.js 但忘记重新生成 md5s.json）
     * 3. 其余 → 视为损坏/异常，拒绝并重试，最终返回 null
     */
    private fun downloadWithRetry(path: String, expectedMd5: String): String? {
        var attempt = 0
        while (attempt <= MAX_RETRY) {
            val tmp = File(webDir, "$path.tmp$attempt")
            try {
                downloadFile(path, tmp)
                val actualMd5 = FileHasher.md5File(tmp)
                if (actualMd5 == expectedMd5) {
                    installFile(tmp, File(webDir, path))
                    return expectedMd5
                }
                val target = File(webDir, path)
                if (target.isFile && FileHasher.md5File(target) == actualMd5) {
                    // 远程清单过期：下载内容与本地一致，仅收敛清单条目
                    Log.w(TAG, "远程清单 MD5 过期（内容与本地一致，按声明值收敛）: $path")
                    tmp.delete()
                    return expectedMd5
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
        return null
    }

    /** 原子替换目标文件（rename 失败时退化为复制）。 */
    private fun installFile(tmp: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
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
