# hxwz4-release-android

基于幻想纹章4（Haxe HTML5 版）热更新方案的安卓壳工程。

- 更新源：CNB 仓库 raw —— `https://cnb.cool/hxwz4/hxwz4-release/-/git/raw/main/`
- 更新清单：`md5s.json`（文件级 MD5：`{相对路径: md5}`）
- 机制：启动 → 更新界面（可跳过）→ WebView（WebViewAssetLoader 加载本地 `files/web`）
- 约束：`index.html` 完全豁免（不下载、不覆盖、不删除），始终使用 APK 内置基线；差异文件增量覆盖更新、本地多余文件不删除；失败重试后跳过、不阻塞启动。

## 目录结构

```
hxwz4-release-android/
├── repo/                       # 目标资源仓库 clone 目录（需手动 clone，见下）
│   └── hxwz4-release/          #   git clone https://cnb.cool/hxwz4/hxwz4-release.git
├── app/                        # Android 壳工程
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/www/         # 基线资源（由 tools/sync_baseline.ps1 生成，不入库）
│       ├── java/com/hxwz4/app/
│       │   ├── MainActivity.kt      # 横屏+沉浸式全屏，更新界面 → WebView
│       │   ├── UpdateManager.kt     # 热更新：基线初始化/清单对比/增量下载
│       │   └── FileHasher.kt        # MD5 工具
│       └── res/
├── tools/
│   ├── sync_baseline.ps1       # 仓库资源 → app/assets/www + 生成随包 md5s.json
│   └── gen_md5s.ps1            # 通用 md5s.json 生成脚本（发布时对仓库执行）
├── gradle/                     # Gradle wrapper
├── settings.gradle.kts
└── ...
```

## 一、准备（一次性）

1. 环境要求：JDK 17+、Android SDK（compileSdk 34）、`local.properties` 已指向 SDK。
2. Clone 目标资源仓库（本工程不内置资源）：

```bat
mkdir repo
git clone https://cnb.cool/hxwz4/hxwz4-release.git repo/hxwz4-release
```

3. 生成 APK 基线资源（把仓库全部文件复制进 `app/src/main/assets/www/` 并生成 `md5s.json`）：

```bat
powershell -ExecutionPolicy Bypass -File tools\sync_baseline.ps1
```

> 说明：`sync_baseline.ps1` 复制仓库资源时，**仅对 APK 内置的 `index.html`** 做移动端适配（通过 `-CanvasW/-CanvasH` 可自定义目标尺寸，默认 `960x576`，传 `0` 跳过），**repo 目录本身不做任何修改**。`index.html` 不参与热更新（客户端白名单豁免），故 APK 内置版本即最终生效版本。

### index.html 移动端适配（最终方案，已在真机验证）

```html
<style>
    html,body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: #000; }
    #content  { background: #000000; width: 960px; height: 576px; transform-origin: 0 0; }
</style>
<script>
    lime.embed ("HxwzHaxe", "content", 960, 576, { allowHighDPI: true });
    // 容器等比缩放居中（兼容任意屏幕）
    (function() {
        var GW = 960, GH = 576;
        var ct = document.getElementById('content');
        function scale() {
            var ww = window.innerWidth, wh = window.innerHeight;
            var s = Math.min(ww / GW, wh / GH);
            ct.style.transform = 'scale(' + s + ')';
            ct.style.marginLeft = ((ww - GW * s) / 2) + 'px';
            ct.style.marginTop  = ((wh - GH * s) / 2) + 'px';
        }
        scale();
        window.addEventListener('resize', scale);
    })();
</script>
```

要点：

- **`lime.embed` 尺寸减半**为 `960x576`（原 1920x1152）——游戏舞台随 embed 尺寸变化，必须同步减半才与引擎视口匹配
- **`allowHighDPI: true`**——开启 dpr 超采样保持画面细腻（关闭会导致 canvas 缓冲=逻辑尺寸、画面模糊；但两者都不会引起"只显示左上角"）
- **`#content` 固定 `960x576` + `transform-origin: 0 0`**——`scale()` 必须配合 `transform-origin:0 0`，否则画面会错位到右下角
- **容器等比缩放 JS**：`Math.min(ww/GW, wh/GH)` 等比缩放 + margin 居中，任意屏幕比例都能完整显示、不变形
- 移除 repo 原版的 `devicePixelRatio > 2` viewport 修改脚本

## 二、构建 APK

```bat
gradlew.bat assembleDebug          # 调试包
gradlew.bat assembleRelease        # 发布包（当前未混淆）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 三、发布新版本（更新游戏资源）

在目标仓库目录内重新生成清单并推送：

```bat
cd repo\hxwz4-release
powershell -ExecutionPolicy Bypass -File ..\..\tools\gen_md5s.ps1 -SourceDir . -Output md5s.json
git add -A
git commit -m "update assets"
git push origin main
```

客户端下次启动会自动对比 `md5s.json` 差异并增量下载，无需重新发版。

## 四、热更新行为说明

| 场景 | 行为 |
| ---- | ---- |
| 首次启动 | 将 APK 内置基线复制到 `files/web/`，随后检查更新（可跳过） |
| 常规启动 | 拉取远程 `md5s.json` → 差异计算 → 增量下载（6 并发、失败重试 2 次） |
| 远程新增文件 | 下载到 `files/web/`（新增） |
| 双方 MD5 不同 | 下载新版本**覆盖** `files/web/` 中的旧文件（先写 `.tmp` 校验 MD5 后原子替换） |
| 远程已移除、本地仍存在 | 一律保留，不做删除 |
| `index.html` | 永远使用 APK 内置版本，不下载/不覆盖/不删除 |
| 断网/清单拉取失败 | 更新界面提示"重试 / 跳过"，跳过则用本地版本直接进游戏 |
| 更新源 | `BASE_URL` 定义于 `UpdateManager.kt`，如需切换镜像直接修改 |

## 五、关键实现点

- 横屏 + 沉浸式全屏：`MainActivity.enterFullscreen()`（`WindowInsetsControllerCompat`）。
- WebView：`WebViewAssetLoader` 以 `https://appassets.androidplatform.net/web/index.html` 虚拟域加载内部存储，规避 `file://` 的 fetch/XHR 跨域限制；开启 JS、DOM Storage、媒体自动播放。
- 更新：OkHttp + 协程 + 信号量并发（6），文件先写 `.tmp`、MD5 校验通过后原子改名，避免半截文件。
- 移动端适配：`sync_baseline.ps1` 对 APK 内置 `index.html` 注入最终适配方案（embed 减半 + allowHighDPI + 容器等比缩放居中），详见上文"index.html 移动端适配"。

## 六、TODO：资源加载方案演进设想

当前实现为「基线复制 + 差异覆盖」：APK 内置 2.2GB 基线 → 首启整体复制到 `files/web/` → 热更新覆盖差异文件 → WebView 全部从 `files/web/` 加载。以下两种设想为后续演进方向，仅做可行性/代价/风险分析，尚未实施。

### 方案 1：Overlay 优先加载（基线仍走 APK assets）

**设想**：游戏仍从 APK 内置基线（`assets/www`）加载，热更新资源仅存 `files/web/`。WebView 加载文件时**优先查 `files/web/`，命中即用；未命中回退 `assets/www`**（overlay 链）。基线文件无需复制到 `files/web/`，热更新只下载增量。

**可行性**：高。`WebViewAssetLoader` 支持自定义 `PathHandler`，可自行实现"先查内部存储、miss 后读 assets"的链式处理器；初始化逻辑从"全量复制"改为"仅复制清单/index.html + 对比下载增量"。

**优点**：

- 首启初始化极快（不再复制 2.2GB 基线）
- `files/web/` 只存增量，磁盘占用大幅下降
- 离线可用：基线在 APK 内天然兜底
- APK 内基线作为"出厂版本"，热更新增量叠加，版本回退容易

**代价 / 风险**：

- 需自定义 overlay 处理器并保证文件命中顺序正确
- **删除语义缺陷**：远程删除的资源，若基线仍存在会回退加载旧版，无法真正删除 —— 需要额外的 tombstone（删除标记）清单，复杂度上升
- assets 每次读取需从 APK 解压，性能略低于 files（首次读取后系统有缓存）
- `md5s.json` 对比基准需要改为"基线清单 + overlay 覆盖清单"两层

**成本**：中。核心是自定义 PathHandler + 初始化改造 + 删除语义处理（tombstone）。

### 方案 2：纯热更新（不存基线，全部依赖下载）

**设想**：APK 不再内置资源基线（或仅保留入口 `index.html` 骨架），首次启动即全量热更新，所有游戏资源仅存于 `files/web/`。

**可行性**：高。现有 `UpdateManager` 已实现清单对比 + 增量下载 + MD5 校验，把"首启复制基线"改为"首启下载全量"即可；`WebViewAssetLoader` 加载 `files/web` 无需改动。

**优点**：

- APK 体积从 ~2GB 骤减到 KB 级，分发/安装/更新成本大幅下降
- 磁盘仅存一份资源，无 APK 基线冗余
- 删除语义干净：以远程清单为准，本地无历史残留
- 后续内容完全由服务器控制，发版只推 `md5s.json` + 增量

**代价 / 风险**：

- **首启强依赖网络**：必须联网完成全量下载（2.2GB），弱网/断网用户无法进入游戏
- 首启下载耗时长（受网速限制）
- 服务器流量成本高（每位新用户全量拉取）
- 需要更健壮的下载保障：断点续传、失败重试策略、磁盘空间校验
- `index.html` 必须内置兜底（否则无网络连入口都没有）

**成本**：低-中。代码改动最少，但运维与体验成本（带宽、首启时长）最高。

### 对比小结

| 维度 | 方案 1（Overlay） | 方案 2（纯热更新） | 当前实现（基线复制） |
| ---- | ---- | ---- | ---- |
| APK 体积 | ~2GB | **KB 级** | ~2GB |
| 首启体验 | 快（无复制） | 慢（全量下载） | 慢（复制 2.2GB） |
| 离线可用 | ✅（基线兜底） | ❌（无基线） | ✅ |
| 磁盘占用 | 低（仅增量） | 低（一份） | 高（复制一份） |
| 删除语义 | 复杂（需 tombstone） | 干净 | 只增不删 |
| 实现复杂度 | 中 | 低 | 低 |
| 服务器带宽 | 低（仅增量） | **高** | 低 |

### TODO 建议路线

1. **短期（可选）**：维持当前"基线复制"实现，稳定优先，暂不演进。
2. **中期（推荐）**：若 APK 体积成为分发瓶颈 → 方案 2（纯热更新），先实现断点续传与首启下载进度/失败引导，再剥离基线。
3. **长期**：若希望"离线可用 + 体积折中" → 方案 1（Overlay），需额外实现 tombstone 删除语义与双层清单。
