# sync_baseline.ps1
# 从目标仓库导出基线资源到 APK assets/www（md5s.json 沿用 repo 版本，不重新生成）。
# 用法：
#   powershell -ExecutionPolicy Bypass -File sync_baseline.ps1
#   powershell -ExecutionPolicy Bypass -File sync_baseline.ps1 -RepoDir ..\repo\hxwz4-release
#
# 说明：
#   - 默认从 ..\repo\hxwz4-release（CNB 目标仓库 clone 目录）导出
#   - 每次执行会清空并重新生成 app/src/main/assets/www
#   - index.html 正常复制（作为 APK 内置基线），但不会写入 md5s.json（客户端豁免）
#   - 对 APK 内置的 index.html 做移动端适配（repo 目录本身不做任何修改）：
#       1) lime.embed 宽高改为 0,0（正则匹配任意原始宽高）—— lime 在宽高为 0 时
#          自动取 #content 的 clientWidth/clientHeight 作为舞台尺寸，canvas 自适应窗口
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
#   1) lime.embed 宽高改为 0,0（正则匹配任意原始宽高，如 1920x1152 / 1920x1080）
#      —— lime 在宽高为 0 时自动取 #content 的 clientWidth/clientHeight 作为舞台尺寸
#   2) #content 保持 repo 原版 100% 自适应
#   3) 移除 devicePixelRatio>2 的 viewport setAttribute 残留（对齐新方案）
$indexPath = Join-Path $DestAssets "index.html"
if (Test-Path $indexPath) {
    $newContent = [System.IO.File]::ReadAllText($indexPath, [System.Text.Encoding]::UTF8)

    # 1) lime.embed 宽高改为 0,0
    #    不依赖 repo 里的具体宽高数值（历史上从 1920x1152 改为过 1920x1080），
    #    用 \d+ 匹配任意数值；组1 = 两数字之间的 ", "，组2 = 结尾的 ");"（含右括号与分号）
    $embedPattern = 'lime\.embed\s*\(\s*"HxwzHaxe"\s*,\s*"content"\s*,\s*\d+(\s*,\s*)\d+(\s*\)\s*;)'
    $embedReplacement = 'lime.embed ("HxwzHaxe", "content", 0, 0${2}'
    $newContent = [regex]::Replace($newContent, $embedPattern, $embedReplacement)

    # 2) 移除 dpr>2 的 viewport setAttribute 行（新方案中该脚本为无效空壳）
    $newContent = [regex]::Replace($newContent, '(?m)^\s*meta\.setAttribute\s*\(.*\)\s*;\s*\r?\n', '')

    # 3) 结果校验：未命中说明 repo 的写法变了，必须及时修正（避免像历史上那样静默失效）
    if ($newContent -notmatch 'lime\.embed\s*\(\s*"HxwzHaxe"\s*,\s*"content"\s*,\s*0\s*,\s*0\s*\)') {
        Write-Warning 'index.html 未匹配到 lime.embed("HxwzHaxe", "content", W, H)，移动端自适应适配未生效，请检查 repo 写法'
    }

    [System.IO.File]::WriteAllText($indexPath, $newContent, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "已适配 APK 内置 index.html: embed 0,0 自适应容器（repo 未改动）"
}

# md5s.json 直接沿用 repo（cnb）版本，不重新生成：
# 基线文件即从 repo 逐字节复制（仅 index.html 被适配且客户端白名单豁免），
# repo 清单天然描述 APK 内置文件状态；首启时本地清单与远程一致 → 0 差异直接进游戏。
# 仅当 repo 中不存在 md5s.json 时才兜底重新生成。
$md5InAssets = Join-Path $DestAssets "md5s.json"
if (-not (Test-Path $md5InAssets)) {
    $md5InRepo = Join-Path $RepoDir "md5s.json"
    if (Test-Path $md5InRepo) {
        Copy-Item -LiteralPath $md5InRepo -Destination $md5InAssets -Force
        Write-Host "md5s.json 已从 repo 复制（未重新生成）"
    } else {
        & (Join-Path $PSScriptRoot "gen_md5s.ps1") -SourceDir $DestAssets -Output $md5InAssets
        Write-Host "repo 无 md5s.json，已兜底重新生成"
    }
} else {
    Write-Host "md5s.json 沿用 repo 版本（未重新生成）"
}

$count = (Get-ChildItem -LiteralPath $DestAssets -Recurse -File -Force | Measure-Object).Count
$sizeMB = [Math]::Round(((Get-ChildItem -LiteralPath $DestAssets -Recurse -File -Force | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "基线导出完成: $DestAssets"
Write-Host "文件数: $count，总大小: ${sizeMB} MB"
