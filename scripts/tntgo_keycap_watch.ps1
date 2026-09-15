# TNT GO 亮度键抓包探针（任务 AI · AI3）
#
# 背景：TNT GO 的输入设备【只在插上时存在】，且设备号每次插拔都变 ⇒ 必须按名字发现。
# 做法：轮询 `getevent -pl`，一旦发现 "TNT go" 的设备，就在【设备侧】起后台 getevent
#       （`setsid nohup ... &`，实测脱离 adb 会话仍存活），日志落在 /data/local/tmp/。
#
# 用法：pwsh -File scripts/tntgo_keycap_watch.ps1 -Serial <你的设备序列号>

param(
    [string]$Serial = '',
    [int]$TimeoutSec = 1800,
    [int]$CaptureSec = 1800
)

$ErrorActionPreference = 'Continue'

function Invoke-Adb([string]$cmd) {
    return (adb -s $Serial shell $cmd 2>&1 | Out-String)
}

# 解析 `getevent -pl` → @{ event='event8'; name='...' }
function Get-InputDevices {
    $raw = Invoke-Adb 'getevent -pl'
    $devices = @()
    $cur = $null
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match '^add device \d+:\s*(/dev/input/(event\d+))') {
            $cur = [pscustomobject]@{ dev = $Matches[1]; event = $Matches[2]; name = '' }
            $devices += $cur
        }
        elseif ($cur -and $line -match '^\s*name:\s*"(.+)"') {
            $cur.name = $Matches[1]
        }
    }
    return $devices
}

Write-Host "→ 探针启动：等 TNT GO 出现（轮询间隔 3s，最多 $TimeoutSec s）"
Write-Host "  通道：$Serial"

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$started = $false
$lastSeen = ''

while (-not $started -and (Get-Date) -lt $deadline) {
    $devs = Get-InputDevices
    $tnt = @($devs | Where-Object { $_.name -match 'TNT go' })

    if ($tnt.Count -eq 0) {
        $sig = ($devs | ForEach-Object { $_.event }) -join ','
        if ($sig -ne $lastSeen) {
            Write-Host ("  [{0:HH:mm:ss}] 还没插（当前 {1} 个设备：{2}）" -f (Get-Date), $devs.Count, $sig)
            $lastSeen = $sig
        }
        Start-Sleep -Seconds 3
        continue
    }

    # ★ 找到了 —— 起抓包
    Write-Host ("`n★ 发现 TNT GO：{0} 个输入设备" -f $tnt.Count)
    foreach ($d in $tnt) {
        $skip = $d.name -match 'Touchpad'      # 触摸板事件量大，且与亮度键无关
        $tag = "keycap_{0}" -f $d.event
        if ($skip) {
            Write-Host ("  - 跳过 {0}  {1}" -f $d.event, $d.name)
            continue
        }
        $remote = "/data/local/tmp/$tag.log"
        $cmd = "setsid nohup timeout $CaptureSec getevent -lt $($d.dev) > $remote 2>&1 < /dev/null & echo started"
        $r = Invoke-Adb $cmd
        Write-Host ("  ✓ {0}  {1}  → {2}   [{3}]" -f $d.event, $d.name, $remote, $r.Trim())
    }

    Write-Host "`n★ 抓包已在设备侧后台运行（脱离 adb 会话存活）。"
    Write-Host "  请在 TNT GO 键盘上依次按：F10、F11、F12（可各按 2 次），然后告诉我。"
    $started = $true
}

if (-not $started) {
    Write-Host "⏱ 超时：$TimeoutSec 秒内没等到 TNT GO。"
    exit 1
}
exit 0
