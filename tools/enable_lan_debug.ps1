param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$RelayHost = ''
)

# adb writes progress to stderr even on success ("1 file pulled"), which PowerShell
# turns into error records. Continue is set so a successful dump is not mistaken
# for a failure; failures are checked by whether the node was found, not by exit code.
$ErrorActionPreference = 'Continue'
# adb is resolved rather than hardcoded, for the same reason as in tap_text.ps1: a
# committed absolute path would work on exactly one machine and fail there in a way
# that reads as a missing device. See that file for the resolution order.
function Resolve-Adb {
    $candidates = @()
    foreach ($var in @('ANDROID_SDK_ROOT', 'ANDROID_HOME')) {
        if (Get-Item "env:$var" -ErrorAction SilentlyContinue) {
            $candidates += (Get-Item "env:$var").Value
        }
    }
    $localProps = Join-Path $PSScriptRoot '..\local.properties'
    if (Test-Path $localProps) {
        $sdkDir = Select-String -Path $localProps -Pattern '^sdk\.dir=(.+)$' |
            Select-Object -First 1 |
            ForEach-Object { $_.Matches[0].Groups[1].Value }
        if ($sdkDir) {
            $candidates += ($sdkDir -replace '\\:', ':' -replace '\\\\', '\')
        }
    }
    foreach ($dir in $candidates) {
        $exe = Join-Path $dir 'platform-tools\adb.exe'
        if (Test-Path $exe) { return $exe }
        $exe = Join-Path $dir 'platform-tools/adb'
        if (Test-Path $exe) { return $exe }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "adb not found. Set ANDROID_SDK_ROOT, or open the project in Android Studio once so local.properties records the SDK."
}

$adb = Resolve-Adb

function Dump($dev) {
    # The serial goes in the filename, and a wireless serial carries dots and
    # colons ("192.168.2.65:44781") - which Windows rejects as a path. Hashing it
    # keeps two devices apart without putting an invalid character in a path.
    $local = Join-Path $env:TEMP "ui-$([Math]::Abs($dev.GetHashCode())).xml"
    # Removed first so a failed pull cannot be read as success. Without this the
    # previous dump is still sitting there, and a device that has since disappeared
    # keeps reporting the screen it showed a minute ago - which reads as the app
    # being uncooperative rather than as the adb connection being gone.
    Remove-Item $local -ErrorAction SilentlyContinue
    & $adb -s $dev shell uiautomator dump /sdcard/ui.xml *>$null
    & $adb -s $dev pull /sdcard/ui.xml $local *>$null
    if (-not (Test-Path $local)) { throw "no UI dump from $dev - is it still connected?" }
    [xml](Get-Content $local)
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

# Read the field the Developer Options screen actually owns. The first EditText on
# screen is only the relay host once we are on that screen: App Settings has theme
# and notification fields ahead of it, so an unqualified lookup reports one of those
# and prints a host nobody asked for. Requiring the 'Relay host' label alongside is
# what makes the value mean something.
function Relay-Host($dev) {
    $x = Dump $dev
    $label = $x.SelectNodes('//node') | Where-Object { $_.text -eq 'Relay host' } | Select-Object -First 1
    if (-not $label) { return $null }
    $field = $x.SelectNodes('//node') | Where-Object { $_.class -eq 'android.widget.EditText' } |
        Sort-Object { [int](($_.bounds -replace '[^\d]', ' ').Trim().Split(' ')[1]) } |
        Select-Object -First 1
    if ($field) { return $field.text }
    return $null
}

# Whether we are on the Developer Options screen itself, as opposed to still sitting
# on the App Settings row of the same name. The heading and the transport toggle are
# that screen's own content, so both appearing together is the signal.
#
# Deliberately not keyed on 'Relay host': that field only exists once the transport
# switch is on, which this function runs before. Waiting for it here meant the script
# scrolled the whole screen and gave up while standing on the right one.
function On-Developer-Options($dev) {
    $x = Dump $dev
    $heading = $x.SelectNodes('//node') | Where-Object { $_.text -eq 'Developer Options' } | Select-Object -First 1
    $toggle = $x.SelectNodes('//node') | Where-Object { $_.text -eq 'Use LAN Debug Transport' } | Select-Object -First 1
    return [bool]($heading -and $toggle)
}

Write-Output "== $Device =="
& $adb -s $Device shell am force-stop com.combustiblelemonlabs.pingring
& $adb -s $Device shell am start -n com.combustiblelemonlabs.pingring/.MainActivity | Out-Null
Start-Sleep -Seconds 5

# Home -> App Settings.
if (-not (Tap-Text $Device 'Settings')) { Write-Output 'FAIL: no Settings button'; exit 1 }

# App Settings is scrollable; Developer Options sits near the foot. Scroll until
# it appears rather than assuming a coordinate, because the two emulators and the
# phone all have different heights. Tap-Text returning true only means a node with
# that text was tapped, so confirm by the Developer Options screen's own content
# before acting on it - the same label appears as a row on App Settings and as a
# heading on the screen itself.
$found = $false
for ($i = 0; $i -lt 8; $i++) {
    if ((Tap-Text $Device 'Developer Options') -and (On-Developer-Options $Device)) { $found = $true; break }
    & $adb -s $Device shell input swipe 600 1600 600 700 300
    Start-Sleep -Seconds 1
}
if (-not $found) { Write-Output 'FAIL: Developer Options never opened'; exit 1 }

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
        Start-Sleep -Milliseconds 300

# Clear in verified rounds until the field is genuinely empty, rather than
        # guessing a count. A fixed count is wrong in both directions: too few leaves
        # a tail behind and the new text is appended to it - which is how 10.0.2.2
        # became 10.2.10.0.210.220 here - while too many is merely slow. Reading the
        # field back each round is what makes this converge instead of leaving one
        # character that still parses as part of a host.
        #
        # Select-all (CTRL_A) is not used: adb's keyevent does not deliver the
        # modifier reliably enough to trust, and here it silently does nothing.
        for ($round = 0; $round -lt 6; $round++) {
            $existing = Relay-Host $Device
            if (-not $existing) { break }
            $presses = $existing.Length + 4
            for ($i = 0; $i -lt $presses; $i++) {
                # Both delete keys, alternating. The cursor is not reliably at the end
                # even after MOVE_END, and DEL with the cursor at position 0 deletes
                # forward into nothing - so the final character survives and every
                # later round re-sends the same key at the same dead cursor, which is
                # how a field sat at "1" through twelve delete presses.
                if ($i % 2 -eq 0) {
                    & $adb -s $Device shell input keyevent KEYCODE_DEL
                } else {
                    & $adb -s $Device shell input keyevent KEYCODE_FORWARD_DEL
                }
            }
            Start-Sleep -Milliseconds 400
        }
        if (Relay-Host $Device) {
            Write-Output 'FAIL: could not clear the relay host field'
            exit 1
        }

        # Typed one character at a time. 'input text' as a single argument drops
        # characters when the IME is still opening, and a dropped character produces
        # a host that still parses - 10.2.01 for 10.0.2.2 - so the damage is not
        # visible until the socket refuses to connect much later.
        foreach ($ch in $RelayHost.ToCharArray()) {
            & $adb -s $Device shell input text $ch
            Start-Sleep -Milliseconds 120
        }
        Start-Sleep -Seconds 1

        # Read the field back before dismissing. BACK leaves Developer Options
        # entirely, and on App Settings there is no 'Relay host' label at all - so
        # reading afterwards reported an unrelated field, or nothing.
        $typed = Relay-Host $Device
        & $adb -s $Device shell input keyevent KEYCODE_BACK
        Start-Sleep -Seconds 1

        # Verified rather than reported and hoped for. A relay host wrong in any
        # character connects to nothing, and the symptom appears much later as peers
        # that never arrive - far from the field that was mistyped. 10.0.2.2 has
        # already been typed as 10.2.01 by a keystroke landing before focus settled,
        # and that still parses as a host, so it fails at the socket rather than here.
        if ($typed -ne $RelayHost) {
            Write-Output "FAIL: relay host is '$typed', expected '$RelayHost'"
            exit 1
        }
        Write-Output "relayHost set to $typed"
    } else {
        Write-Output 'WARN: relay host field not found'
    }
}

$x = Dump $Device
$sw = $x.SelectNodes('//node[@checkable="true"]') | Select-Object -First 1
Write-Output ("switch=" + $sw.checked)
