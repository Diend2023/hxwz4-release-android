# 幻想纹章4（hxwz4）静态页面打包 APK 与热更新方案

> 适用仓库：`https://cnb.cool/hxwz4/hxwz4-release`（main 分支）  
> 更新清单：`md5s.json`（文件级 MD5 清单：`{相对路径: md5哈希}`）  
> 方案性质：纯方案，不含实现代码



---

## 一、现状盘点（已实测）

| 项目     | 实测结论                                                                                                                                            |
| ------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| 仓库可见性  | 公开仓库，blob 页面可匿名访问                                                                                                                               |
| 项目类型   | Haxe 编写的横屏 HTML5 游戏，`index.html` 入口，`lime.embed("HxwzHaxe", "content", 1920, 1152)`，纯静态可运行                                                      |
| 入口依赖   | `lib/howler.min.js`、`lib/pako.min.js`、`lib/FileSaver.min.js`、`HxwzHaxe.js`                                                                      |
| 更新清单   | `md5s.json`：路径 → MD5，覆盖数百个文件（assets/、lib/、roles/、roleui/、res/、manifest/、根文件等）                                                                   |
| 更新源可用性 | **CNB raw 可匿名免鉴权访问**，正确格式：`https://cnb.cool/hxwz4/hxwz4-release/-/git/raw/main/{path}`（注意路径含 `/git/` 段，实测 md5s.json、PNG、JS 等均返回 HTTP 200 且内容完整） |

**结论**：md5s.json 已具备文件级增量更新的全部要素，更新源可直接使用现有 CNB 仓库，无需迁移。

---

## 二、总体架构

```
┌─────────────────────────────── Android APK ───────────────────────────────┐
│  MainActivity（横屏 + 沉浸式全屏）                                          │
│  ┌──────────────────┐   ┌───────────────────────────────────────────┐   │
│  │  更新界面（先行）  │→→→│  WebView（WebViewAssetLoader 加载本地文件）  │   │
│  │  初始化/检查/下载  │   │  https://appassets.androidplatform.net/.. │   │
│  │  可跳过 · 完成进入 │   └───────────────────────────────────────────┘   │
│  └────────┬─────────┘                                                    │
│           │ 对比/写入                                                │
│  ┌────────▼──────────────────────────────┐                               │
│  │ 本地缓存 files/web/                   │                               │
│  │  ├─ 游戏资源（assets/ roles/ ...）    │                               │
│  │  ├─ index.html / HxwzHaxe.js / lib/  │                               │
│  │  └─ md5s.json（本地期望状态）          │                               │
│  └──────────────────────────────────────┘                               │
│  首次启动：assets/ 基线资源 → files/web/（在更新界面完成）                  │
└──────────────┬───────────────────────────────────────────────────────────┘
               │ HTTPS · 拉取 md5s.json + 增量下载差异文件
┌──────────────▼───────────────────────────────────────────────────────────┐
│ 远程更新源（CNB 仓库 raw，`/-/git/raw/main/`）                       │
│  ├─ md5s.json（更新清单）                                                │
│  └─ 资源文件（与 md5s.json 中路径一一对应）                                │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 三、热更新机制设计（核心）

以 `md5s.json` 为**期望状态**，客户端每次启动做一次"清单对比 → 差异收敛"，天然支持增量、断点续传与失败重试。

### 3.1 更新流程（启动 → 更新界面 → 进入 WebView）

> **热更新范围约束（用户确认）**：`index.html` 不参与任何热更新操作——不下载、不覆盖、不删除，始终使用 APK 内置基线版本；清单中其余文件按正常 MD5 差异机制增量更新。

**统一入口（用户确认）**：每次启动先进入**更新界面**（可跳过），更新完成后再进入 WebView。首次启动与常规增量更新共用同一入口——先更新、后进 Web，逻辑一致。

1. **进入更新界面**：启动即展示更新界面（横屏、与游戏同屏风格），按状态显示"初始化 / 检查更新 / 下载进度（x/y）"。
   - **首次启动**：将 APK `assets/www/` 内的基线资源复制到 `files/web/`，并写入随包携带的 `md5s.json`（其中 `index.html` 条目标记为锁定，不参与后续对比），完成后自动进入 WebView。
   - **常规启动**：执行下方第 2~4 步。
2. **拉取清单**：`GET {BASE_URL}/md5s.json`（带缓存策略，如 ETag/Last-Modified）。
3. **差异计算**（远程清单 vs 本地清单 + 本地文件实际 MD5，**跳过 `index.html` 条目**）：
   - 远程存在、本地缺失 → **新增下载**
   - 双方存在但 MD5 不同 → **覆盖下载**
   - 远程不存在、本地存在 → **不处理**（本地多余文件一律保留，不做删除）
4. **增量下载**：仅下载差异文件，下载完成后计算 MD5 与清单比对，不匹配则重试（默认 2 次），失败文件跳过、不中断整体；界面实时展示进度。
5. **完成进入 WebView**：更新完成（或用户点击"跳过"）→ 关闭更新界面 → 启动 WebView 加载本地 `index.html`。跳过即沿用本地现有版本。
6. **失败处理**：任一步骤失败（断网、清单拉取失败）→ 更新界面提示"重试 / 跳过"，跳过即沿用本地现有版本进入游戏，不阻塞启动。

### 3.2 更新源选型（已实测确认）

| 方案                          | 免鉴权    | 国内速度       | 成本    | 说明                                                                                          |
| --------------------------- | ------ | ---------- | ----- | ------------------------------------------------------------------------------------------- |
| **A. CNB 仓库 raw（首选）**       | ✅ 实测可用 | 快          | 免费    | `https://cnb.cool/{owner}/{repo}/-/git/raw/{branch}/{path}`，与现有 md5s.json 零改动，**直接采用，无需迁移** |
| **B. Gitee 公开仓库 raw**       | ✅      | 快          | 免费    | `https://gitee.com/{owner}/{repo}/raw/{branch}/{path}`，备用镜像                                 |
| **C. GitHub 公开仓库 raw**      | ✅      | 一般（可套 CDN） | 免费    | `https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}`，全球可用                     |
| **D. EdgeOne Pages / 静态托管** | ✅      | 快（CDN）     | 有免费额度 | 无需仓库，md5s.json 照用，大文件（>100MB）场景的兜底                                                          |

**结论**：**首选方案 A（CNB 仓库 raw）**——更新源继续使用现有 `hxwz4/hxwz4-release` 仓库，客户端 `BASE_URL = https://cnb.cool/hxwz4/hxwz4-release/-/git/raw/main/`，与现有 md5s.json 结构完全兼容，发布流程不变。Gitee/GitHub 仅作为可选镜像。

### 3.3 边界与保护

- **index.html 完全豁免**：热更新对其不下载、不覆盖、不删除，本地始终使用 APK 内置基线版本（WebView 加载入口由壳内固定，避免与外部清单状态耦合）。
- **不做文件删除**：本地文件只增不改（新增/覆盖），即使远程清单中已移除某文件，本地对应文件仍保留。
- **断点续传**：每次启动以远程清单为期望状态重新收敛，中断后未完成文件自动补下，无需持久化进度。

---

## 四、APK 壳实现要点

### 4.1 横屏 + 沉浸式全屏

```xml

<activity
    android:name=".MainActivity"
    android:screenOrientation="landscape"          
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@style/Theme.AppCompat.NoActionBar" />
```

```kotlin
// MainActivity.kt：沉浸式全屏
WindowCompat.setDecorFitsSystemWindows(window, false)
WindowInsetsControllerCompat(window, window.decorView).apply {
    hide(WindowInsetsCompat.Type.systemBars())
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
```

### 4.2 WebView 配置

- **WebViewAssetLoader**（替代 `file://`，解决跨域与 fetch/XHR 限制）：

```kotlin
val assetLoader = WebViewAssetLoader.Builder()
    .addPathHandler("/web/",
        WebViewAssetLoader.InternalStoragePathHandler(context, File(context.filesDir, "web")))
    .build()
webView.webViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
        assetLoader.shouldInterceptRequest(request.url)
}
webView.loadUrl("https://appassets.androidplatform.net/web/index.html")
```

- 关键开关：`javaScriptEnabled`、`domStorageEnabled`、`setMediaPlaybackRequiresUserGesture(false)`（游戏音频自动播放）、默认开启硬件加速；`index.html` 已自带 touchmove 拦截与 dpr 适配，1920×1152 由 lime 自动缩放，无需额外处理。

### 4.3 更新管理器

- 技术栈：Kotlin + 协程 + OkHttp。
- 下载并发：4~8 并发，OkHttp 连接池复用。
- 网络要求：仅 HTTPS；Android 9+ 默认已禁明文，保持默认即可。
- 触发时机：启动时在**更新界面**内执行（协程，不阻塞 UI 线程）；更新完成或用户点击"跳过"后关闭更新界面、进入 WebView。首次启动（基线初始化）与常规增量更新共用该界面。
- **index.html 豁免实现**：清单对比与下载阶段对 `index.html` 键直接跳过（白名单过滤），本地该文件永不覆盖、永不删除；`md5s.json` 对比时同样忽略该条目。

---

## 五、发布流程（更新游戏资源）

1. 本地构建新版本资源（assets、roles、roleui 等变更）。
2. 脚本递归计算全部文件 MD5 → 生成新的 `md5s.json`。
3. 提交并推送至更新源仓库（CNB `hxwz4/hxwz4-release`，main 分支）。
4. 客户端下次启动自动检测差异并增量更新，无需重新发版。

---

## 六、风险与注意事项

1. **raw URL 格式必须带 `/git/` 段**：`https://cnb.cool/{owner}/{repo}/-/git/raw/{branch}/{path}`（不含 `/git/` 的 `/-/raw/` 路径返回错误页，客户端务必使用正确格式）。
2. **首次更新量大**：若 assets 基线不全，首次增量可能下载数百文件；APK 内应内置完整基线，首启免下载。
3. **APK 体积**：基线含全部游戏资源（图片/音频/角色数据），APK 可能较大（数十~数百 MB），属正常；可用 AAB 分发缓解。
4. **校验强度**：MD5 仅保证完整性，防篡改能力弱；如需更强校验可升级为 sha256 或在清单中加签名，当前阶段 MD5 可满足。
5. **单文件大小限制**：以 CNB 平台对单文件（及 git 传输）的限制为准，若存在超大文件（如 >100MB）需走 LFS 或改静态托管方案。
6. **资源替换时机**：更新仅在进入 WebView 之前完成，游戏运行期间不会发生文件替换，避免运行中资源不一致。

---

## 七、下一步建议

1. 更新源已确认：CNB 仓库 raw（`https://cnb.cool/hxwz4/hxwz4-release/-/git/raw/main/`），无需迁移。
2. 生成基线资源包：从 CNB 仓库导出 main 分支完整文件 → 放入 APK `assets/www/`。
3. 搭建 Android 壳工程（Gradle + Kotlin，按第四节要点实现横屏全屏、WebView 与更新管理器）。
4. 编写 md5s.json 生成脚本并接入 CI（提交即发布）。
