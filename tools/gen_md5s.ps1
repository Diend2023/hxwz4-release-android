# gen_md5s.ps1
# 递归计算目录下所有文件的 MD5，生成 {相对路径: md5} 形式的 md5s.json 清单。
# 用法：
#   powershell -ExecutionPolicy Bypass -File gen_md5s.ps1 -SourceDir . -Output md5s.json
#   powershell -ExecutionPolicy Bypass -File gen_md5s.ps1 -SourceDir .\repo\hxwz4-release -Output .\repo\hxwz4-release\md5s.json
#
# 规则：
#   - 自动排除 .git 目录与清单本身（md5s.json）
#   - index.html 不参与热更新（APK 内置基线锁定），默认排除；如需包含传 -IncludeIndex
#   - 路径分隔符统一为 "/"

param(
    [Parameter(Mandatory = $true)][string]$SourceDir,
    [string]$Output = "md5s.json",
    [switch]$IncludeIndex
)

$ErrorActionPreference = "Stop"
$SourceDir = [System.IO.Path]::GetFullPath($SourceDir)

function Get-AllFiles([string]$dir) {
    $result = @()
    foreach ($item in Get-ChildItem -LiteralPath $dir -Force) {
        if ($item.Name -eq ".git") { continue }
        if ($item.PSIsContainer) {
            $result += Get-AllFiles $item.FullName
        } else {
            $result += $item
        }
    }
    return $result
}

$files = Get-AllFiles $SourceDir | Sort-Object FullName
$map = [ordered]@{}

foreach ($f in $files) {
    $rel = $f.FullName.Substring($SourceDir.Length).TrimStart('\', '/').Replace('\', '/')
    if ($rel -eq "md5s.json") { continue }
    if ($rel -eq "index.html" -and -not $IncludeIndex) { continue }

    $md5 = (Get-FileHash -LiteralPath $f.FullName -Algorithm MD5).Hash.ToLowerInvariant()
    $map[$rel] = $md5
}

$json = $map | ConvertTo-Json -Compress
$outPath = [System.IO.Path]::GetFullPath($Output)
$outDir = [System.IO.Path]::GetDirectoryName($outPath)
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
# 必须无 BOM 写出：PowerShell 5.1 的 [System.Text.Encoding]::UTF8 会写 BOM，
# 导致客户端 JSONObject 解析失败（\uFEFF 开头）
[System.IO.File]::WriteAllText($outPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "生成完成: $outPath"
Write-Host "文件数: $($map.Count)"
