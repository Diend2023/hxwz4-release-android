package com.hxwz4.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 幻想纹章4 安卓壳主界面。
 * 启动 → 更新界面（可跳过）→ 完成进入 WebView（WebViewAssetLoader 加载本地 files/web）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MAIN_URL = "https://appassets.androidplatform.net/web/index.html"
    }

    private lateinit var updatePanel: View
    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button
    private lateinit var btnSkip: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var updateManager: UpdateManager
    private var updateJob: Job? = null
    private var enteringWeb = false
    private var webFallbackTried = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        setContentView(R.layout.activity_main)

        updatePanel = findViewById(R.id.updatePanel)
        webContainer = findViewById(R.id.webContainer)
        webView = findViewById(R.id.webView)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnRetry = findViewById(R.id.btnRetry)
        btnSkip = findViewById(R.id.btnSkip)

        btnSkip.setOnClickListener { skipUpdate() }
        btnRetry.setOnClickListener { retryUpdate() }

        updateManager = UpdateManager(this)
        startUpdate()
    }

    /** 横屏 + 沉浸式全屏（隐藏系统栏，滑动短暂浮现）。 */
    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startUpdate() {
        updateJob = scope.launch {
            updateManager.run(::onUpdateState)
        }
    }

    private fun retryUpdate() {
        btnRetry.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        startUpdate()
    }

    private fun skipUpdate() {
        updateJob?.cancel()
        finishUpdateToWeb()
    }

    private fun onUpdateState(state: UpdateState) {
        when (state.phase) {
            UpdateState.Phase.INIT -> {
                tvStatus.text = "初始化本地资源…"
                progressBar.isIndeterminate = false
                if (state.total > 0) {
                    progressBar.max = state.total
                    progressBar.progress = state.current
                }
            }
            UpdateState.Phase.CHECKING -> {
                tvStatus.text = "检查更新…"
                progressBar.isIndeterminate = true
            }
            UpdateState.Phase.DOWNLOADING -> {
                tvStatus.text = "下载更新 ${state.current}/${state.total}"
                progressBar.isIndeterminate = false
                progressBar.max = state.total
                progressBar.progress = state.current
            }
            UpdateState.Phase.DONE -> {
                if (!enteringWeb) finishUpdateToWeb()
            }
            UpdateState.Phase.FAILED -> {
                tvStatus.text = "更新失败：${state.message}"
                progressBar.isIndeterminate = false
                btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun finishUpdateToWeb() {
        if (enteringWeb) return
        enteringWeb = true

        tvStatus.text = "正在进入游戏…"
        progressBar.visibility = View.GONE
        btnRetry.visibility = View.GONE
        btnSkip.isEnabled = false

        webContainer.visibility = View.VISIBLE
        updatePanel.visibility = View.GONE
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        // file:// 兜底路径需要文件访问；虚拟域主路径不受影响
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = false
        @Suppress("DEPRECATION")
        webSettings.setAllowFileAccessFromFileURLs(true)

        // 通过 WebViewAssetLoader 以 https 虚拟域加载内部存储，避免 file:// 跨域限制
        val assetLoader = try {
            WebViewAssetLoader.Builder()
                .addPathHandler(
                    "/web/",
                    WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, "web"))
                )
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "WebViewAssetLoader 不可用", e)
            null
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                // 仅拦截虚拟域；其余请求（网络）放行
                if (url.scheme == "https" && url.host == "appassets.androidplatform.net") {
                    val resp = assetLoader?.shouldInterceptRequest(url)
                    if (resp != null) {
                        Log.d(TAG, "assetLoader 拦截: $url")
                        return resp
                    }
                    Log.w(TAG, "assetLoader 未拦截: $url")
                }
                return null
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError
            ) {
                Log.e(TAG, "onReceivedError: code=${error.errorCode} desc=${error.description} url=${request?.url}")
                // 虚拟域加载失败时降级到 file:// 直接加载本地文件
                if (!webFallbackTried) {
                    webFallbackTried = true
                    val fallback = "file://${File(filesDir, "web").absolutePath}/index.html"
                    Log.w(TAG, "降级加载: $fallback")
                    view.loadUrl(fallback)
                }
            }
        }

        webView.loadUrl(MAIN_URL)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webView.destroy()
    }
}
