# hxwz4-release-android

基于幻想纹章4（Haxe HTML5 版）热更新方案的安卓壳工程。

- 更新源：CNB 仓库 raw —— `https://cnb.cool/hxwz4/hxwz4-release/-/git/raw/main/`
- 更新清单：`md5s.json`（文件级 MD5：`{相对路径: md5}`）
- 机制：启动 → 更新界面（可跳过）→ WebView（WebViewAssetLoader 加载本地 `files/web`）
- 约束：`index.html` 完全豁免（不下载、不覆盖、不删除），始终使用 APK 内置基线；本地文件只增不改、不删除；失败重试后跳过、不阻塞启动。

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
- 移除 repo 原版的 `devicePixelRatio > 2` viewport 修改脚本（与官方 Capacitor 打包一致）

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
| `index.html` | 永远使用 APK 内置版本，不下载/不覆盖/不删除 |
| 本地多余文件 | 一律保留，不做删除 |
| 断网/清单拉取失败 | 更新界面提示"重试 / 跳过"，跳过则用本地版本直接进游戏 |
| 更新源 | `BASE_URL` 定义于 `UpdateManager.kt`，如需切换镜像直接修改 |

## 五、关键实现点

- 横屏 + 沉浸式全屏：`MainActivity.enterFullscreen()`（`WindowInsetsControllerCompat`）。
- WebView：`WebViewAssetLoader` 以 `https://appassets.androidplatform.net/web/index.html` 虚拟域加载内部存储，规避 `file://` 的 fetch/XHR 跨域限制；开启 JS、DOM Storage、媒体自动播放。
- 更新：OkHttp + 协程 + 信号量并发（6），文件先写 `.tmp`、MD5 校验通过后原子改名，避免半截文件。
- 移动端适配：`sync_baseline.ps1` 对 APK 内置 `index.html` 注入最终适配方案（embed 减半 + allowHighDPI + 容器等比缩放居中），详见上文"index.html 移动端适配"。
