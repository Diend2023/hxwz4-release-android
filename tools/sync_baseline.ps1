# sync_baseline.ps1
# 从目标仓库导出基线资源到 APK assets/www，并生成随包的 md5s.json。
# 用法：
#   powershell -ExecutionPolicy Bypass -File sync_baseline.ps1
#   powershell -ExecutionPolicy Bypass -File sync_baseline.ps1 -RepoDir ..\repo\hxwz4-release
#
# 说明：
#   - 默认从 ..\repo\hxwz4-release（CNB 目标仓库 clone 目录）导出
#   - 每次执行会清空并重新生成 app/src/main/assets/www
#   - index.html 正常复制（作为 APK 内置基线），但不会写入 md5s.json（客户端豁免）
#   - 对 APK 内置的 index.html 做移动端适配（repo 目录本身不做任何修改）：
#       1) lime.embed 宽高改为 0,0 —— lime 在宽高为 0 时自动取 #content 的
#          clientWidth/clientHeight 作为舞台尺寸，canvas 自适应窗口，无需固定尺寸
#       2) #content 保持 repo 原版 100% 自适应
#       3) 移除 devicePixelRatio>2 的 viewport setAttribute 残留（对齐新方案）

param(
    [string]$RepoDir = (Join-Path $PSScriptRoot "..\repo\hxwz4-release"),
    [string]$DestAssets = (Join-Path $PSScriptRoot "..\app\src\main\assets\www")
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $RepoDir)) {
    Write-Error "仓库目录不存在: $RepoDir`n请先 clone：git clone --depth=1 https://cnb.cool/hxwz4/hxwz4-release.git $RepoDir"
    exit 1
}

$RepoDir = [System.IO.Path]::GetFullPath($RepoDir)
$DestAssets = [System.IO.Path]::GetFullPath($DestAssets)

# 清空旧基线
if (Test-Path $DestAssets) {
    Remove-Item -Recurse -Force $DestAssets
}
New-Item -ItemType Directory -Force -Path $DestAssets | Out-Null

# 复制全部文件（排除 .git）
$source = Get-ChildItem -LiteralPath $RepoDir -Force
foreach ($item in $source) {
    if ($item.Name -eq ".git") { continue }
    Copy-Item -LiteralPath $item.FullName -Destination $DestAssets -Recurse -Force
}

# 对 APK 内置的 index.html 做移动端适配（不修改 repo）：
#   1) lime.embed 宽高改为 0,0 —— lime 在宽高为 0 时自动取 #content 的
#      clientWidth/clientHeight 作为舞台尺寸，canvas 自适应窗口，无需固定尺寸
#   2) #content 保持 repo 原版 100% 自适应
#   3) 移除 devicePixelRatio>2 的 viewport setAttribute 残留（对齐新方案）
$indexPath = Join-Path $DestAssets "index.html"
if (Test-Path $indexPath) {
    $newContent = [System.IO.File]::ReadAllText($indexPath, [System.Text.Encoding]::UTF8)

    # 1) lime.embed 宽高改为 0,0（匹配带空格的原始写法）
    $embedPattern = 'lime\.embed\s*\(\s*"HxwzHaxe"\s*,\s*"content"\s*,\s*1920(\s*,\s*)1152(\s*\)\s*;)'
    $embedReplacement = 'lime.embed ("HxwzHaxe", "content", 0, 0${2})'
    $newContent = [regex]::Replace($newContent, $embedPattern, $embedReplacement)

    # 2) 移除 dpr>2 的 viewport setAttribute 行（新方案中该脚本为无效空壳）
    $newContent = [regex]::Replace($newContent, '(?m)^\s*meta\.setAttribute\s*\(.*\)\s*;\s*\r?\n', '')

    [System.IO.File]::WriteAllText($indexPath, $newContent, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "已适配 APK 内置 index.html: embed 0,0 自适应容器（repo 未改动）"
}

# 生成随包 md5s.json（index.html 默认排除）
& (Join-Path $PSScriptRoot "gen_md5s.ps1") -SourceDir $DestAssets -Output (Join-Path $DestAssets "md5s.json")

$count = (Get-ChildItem -LiteralPath $DestAssets -Recurse -File -Force | Measure-Object).Count
$sizeMB = [Math]::Round(((Get-ChildItem -LiteralPath $DestAssets -Recurse -File -Force | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "基线导出完成: $DestAssets"
Write-Host "文件数: $count，总大小: ${sizeMB} MB"
