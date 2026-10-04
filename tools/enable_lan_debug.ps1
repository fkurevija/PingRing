param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$RelayHost = ''
)

# adb writes progress to stderr even on success ("1 file pulled"), which PowerShell
# turns into error records. Continue is set so a successful dump is not mistaken
# for a failure; failures are checked by whether the node was found, not by exit code.
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\fkure\AppData\Local\Android\Sdk\platform-tools\adb.exe'

function Dump($dev) {
    & $adb -s $dev shell uiautomator dump /sdcard/ui.xml *>$null
    & $adb -s $dev pull /sdcard/ui.xml "$env:TEMP\ui-$dev.xml" *>$null
    [xml](Get-Content "$env:TEMP\ui-$dev.xml")
}

function CentreOf($bounds) {
    $nums = ($bounds -replace '[^\d]', ' ') -split '\s+' | Where-Object { $_ -ne '' }
    $x = ([int]$nums[0] + [int]$nums[2]) / 2
    $y = ([int]$nums[1] + [int]$nums[3]) / 2
    "$([int]$x) $([int]$y)"
}

function Tap-Text($dev, $label) {
    $x = Dump $dev
    $node = $x.SelectNodes('//node') | Where-Object { $_.text -eq $label } | Select-Object -First 1
    if (-not $node) { return $false }
    & $adb -s $dev shell input tap (CentreOf $node.bounds)
    Start-Sleep -Seconds 2
    return $true
}

Write-Output "== $Device =="
& $adb -s $Device shell am force-stop com.fkurevija.pingring
& $adb -s $Device shell am start -n com.fkurevija.pingring/.MainActivity | Out-Null
Start-Sleep -Seconds 5

# Home -> App Settings.
if (-not (Tap-Text $Device 'Settings')) { Write-Output 'FAIL: no Settings button'; exit 1 }

# App Settings is scrollable; Developer Options sits near the foot. Scroll until
# it appears rather than assuming a coordinate, because the two emulators and the
# phone all have different heights.
$found = $false
for ($i = 0; $i -lt 8; $i++) {
    if (Tap-Text $Device 'Developer Options') { $found = $true; break }
    & $adb -s $Device shell input swipe 600 1600 600 700 300
    Start-Sleep -Seconds 1
}
if (-not $found) { Write-Output 'FAIL: Developer Options row never appeared'; exit 1 }

# Developer Options -> flip the LAN debug switch on if it is not already.
$x = Dump $Device
$sw = $x.SelectNodes('//node[@checkable="true"]') | Select-Object -First 1
if (-not $sw) { Write-Output 'FAIL: no switch on the Developer Options screen'; exit 1 }
if ($sw.checked -ne 'true') {
    & $adb -s $Device shell input tap (CentreOf $sw.bounds)
    Start-Sleep -Seconds 2
    $x = Dump $Device
    $sw = $x.SelectNodes('//node[@checkable="true"]') | Select-Object -First 1
}

# Relay host only appears once the switch is on, and it has to differ between an
# emulator (10.0.2.2 reaches the host machine) and a phone (127.0.0.1, which
# adb reverse forwards for us).
if ($RelayHost -ne '') {
    $x = Dump $Device
    $field = $x.SelectNodes('//node') | Where-Object { $_.class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($field) {
        & $adb -s $Device shell input tap (CentreOf $field.bounds)
        Start-Sleep -Milliseconds 800
        & $adb -s $Device shell input keyevent KEYCODE_MOVE_END
        for ($i = 0; $i -lt 20; $i++) { & $adb -s $Device shell input keyevent KEYCODE_DEL }
        & $adb -s $Device shell input text $RelayHost
        Start-Sleep -Seconds 1
        & $adb -s $Device shell input keyevent KEYCODE_BACK
        Start-Sleep -Seconds 1
    } else {
        Write-Output 'WARN: relay host field not found'
    }
}

$x = Dump $Device
$sw = $x.SelectNodes('//node[@checkable="true"]') | Select-Object -First 1
$hostValue = ($x.SelectNodes('//node') | Where-Object { $_.class -eq 'android.widget.EditText' } | Select-Object -First 1).text
Write-Output ("switch=" + $sw.checked + " relayHost=" + $hostValue)
