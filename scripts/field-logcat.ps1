# 现场实测的 logcat 抓取器：清缓冲 -> 定时抓取 -> 落盘 .dev\field\run-<id>.log。
#
# 只留三条关键 tag：Peer（候选分布/wire 长度/ice 状态）、CallSession（状态机事件）、
# RtcEngine（初始化）。一场 60-90 秒的分享只会产出几十行，够回填 PLAN.md §14 的矩阵。
#
# 用法：
#   .\scripts\field-logcat.ps1 -Run 3 -Seconds 90    # 清缓冲后抓 90 秒（一场一轮）
#   .\scripts\field-logcat.ps1 -DumpOnly -Run 3      # 不清缓冲，只导出当前内容（补救用）
param(
    [string]$Run = (Get-Date -Format "yyyyMMdd-HHmmss"),
    [int]$Seconds = 60,
    [switch]$DumpOnly
)
$ErrorActionPreference = "Stop"

function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $local = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $local) { return $local }
    throw "找不到 adb（不在 PATH，且 %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe 不存在）"
}

$adb = Resolve-Adb
try {
    $state = (& $adb get-state 2>$null) -join ""
} catch { $state = "" }
if ($state -notmatch "device$") {
    throw "没有可用设备（adb get-state='$state'）。先插上手机并确认已授权 USB 调试。"
}

$tagFilter = "Peer:V", "CallSession:V", "RtcEngine:V"
$outDir = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.dev\field"))
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = Join-Path $outDir "run-$Run.log"

if ($DumpOnly) {
    & $adb logcat -d -v time -s @tagFilter | Out-File -FilePath $outFile -Encoding utf8
} else {
    & $adb logcat -c
    Write-Host "缓冲已清，开始抓 $Seconds 秒（tag: $($tagFilter -join ' ')）..."
    $job = Start-Job -ScriptBlock {
        param($adbPath, $tags)
        & $adbPath logcat -v time -s @tags
    } -ArgumentList $adb, $tagFilter
    if (-not (Wait-Job -Job $job -Timeout $Seconds)) { Stop-Job -Job $job }
    Receive-Job -Job $job | Out-File -FilePath $outFile -Encoding utf8
    Remove-Job -Job $job -Force
}

$lines = @(Get-Content -Path $outFile -ErrorAction SilentlyContinue)
$ready = @($lines | Select-String "signal ready").Count
$connected = @($lines | Select-String "ice=CONNECTED").Count
$answer = @($lines | Select-String "收到对方应答").Count
Write-Host "==> $outFile  ($($lines.Count) 行)"
Write-Host "    signal ready=$ready  ice=CONNECTED=$connected  收到应答=$answer"
Write-Host "    回填提示: srflx 看 signal ready 行的 typeSummary；CONNECTED 时间看行首时间戳。"
