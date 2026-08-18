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
#       1) lime.embed 尺寸减半 1920x1152 -> 960x576，并传 allowHighDPI:true
#       2) #content 容器固定为 960x576 + transform-origin:0 0 + 黑背景
#       3) 注入等比缩放 JS（scale + 居中），兼容任意屏幕
#   - -CanvasW/-CanvasH 为减半后的目标尺寸（默认 960x576），传 0 则跳过适配

param(
    [string]$RepoDir = (Join-Path $PSScriptRoot "..\repo\hxwz4-release"),
    [string]$DestAssets = (Join-Path $PSScriptRoot "..\app\src\main\assets\www"),
    [int]$CanvasW = 960,
    [int]$CanvasH = 576
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $RepoDir)) {
    Write-Error "仓库目录不存在: $RepoDir`n请先 clone：git clone https://cnb.cool/hxwz4/hxwz4-release.git $RepoDir"
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
#   1) lime.embed 尺寸减半 1920x1152 -> ${CanvasW}x${CanvasH} + allowHighDPI:true
#   2) #content 固定尺寸 + transform-origin:0 0
#   3) html,body 黑背景
#   4) 注入等比缩放 JS（scale + 居中），兼容任意屏幕
if ($CanvasW -gt 0 -and $CanvasH -gt 0) {
    $indexPath = Join-Path $DestAssets "index.html"
    if (Test-Path $indexPath) {
        $newContent = [System.IO.File]::ReadAllText($indexPath, [System.Text.Encoding]::UTF8)

        # 1) lime.embed 尺寸减半 + allowHighDPI:true（匹配带空格的原始写法）
        $embedPattern = 'lime\.embed\s*\(\s*"HxwzHaxe"\s*,\s*"content"\s*,\s*)1920(\s*,\s*)1152(\s*\)\s*;)'
        $embedReplacement = '${1}' + $CanvasW + '${2}' + $CanvasH + ', { allowHighDPI: true }${3}'
        $newContent = [regex]::Replace($newContent, $embedPattern, $embedReplacement)

        # 2) #content 固定尺寸 + transform-origin
        $contentStyle = "#content { background: #000000; width: ${CanvasW}px; height: ${CanvasH}px; transform-origin: 0 0; }"
        $newContent = $newContent -replace '#content\s*\{[^}]*\}', $contentStyle

        # 3) html,body 黑背景（若无）
        if ($newContent -notmatch 'html,body[^}]*background') {
            $newContent = $newContent -replace 'html,body\s*\{[^}]*\}', 'html,body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: #000; }'
        }

        # 4) 注入等比缩放 JS（避免重复）
        if ($newContent -notmatch '移动端缩放') {
            $fitTemplate = @'
<script type="text/javascript">
	// 移动端缩放 - 等比缩放 #content 容器并居中（兼容任意屏幕）
	(function() {
		var GW = __W__, GH = __H__;
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
'@
            $fitJs = $fitTemplate.Replace("__W__", "$CanvasW").Replace("__H__", "$CanvasH")
            if ($newContent.IndexOf('</body>') -ge 0) {
                $newContent = $newContent.Replace('</body>', $fitJs + "`n`n</body>")
            } else {
                $newContent += $fitJs
            }
        }

        [System.IO.File]::WriteAllText($indexPath, $newContent, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "已适配 APK 内置 index.html: embed ${CanvasW}x${CanvasH} + allowHighDPI + 等比缩放居中（repo 未改动）"
    }
}

# 生成随包 md5s.json（index.html 默认排除）
& (Join-Path $PSScriptRoot "gen_md5s.ps1") -SourceDir $DestAssets -Output (Join-Path $DestAssets "md5s.json")

$count = (Get-ChildItem -LiteralPath $DestAssets -Recurse -File -Force | Measure-Object).Count
$sizeMB = [Math]::Round(((Get-ChildItem -LiteralPath $DestAssets -Recurse -File -Force | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "基线导出完成: $DestAssets"
Write-Host "文件数: $count，总大小: ${sizeMB} MB"
