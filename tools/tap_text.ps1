param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Text,
    [switch]$Partial,
    [switch]$List
)

<#
.SYNOPSIS
Taps a UI node on a device by the text it shows.

.DESCRIPTION
Drives a device through uiautomator rather than through fixed coordinates,
because coordinates are the one thing that cannot survive a change: a phone and
an emulator differ in size, and a Compose screen moves its buttons whenever the
copy changes. Anything scripted against this repository's UI - enabling LAN
debug, walking the radar - would otherwise need a coordinate per device per
screen, redrawn after every layout change.

Resolves the node by its visible text, which is also what a person reading the
screen would use to name it. When nothing matches, it prints the text currently
on screen and exits non-zero, because the commonest cause is a wording change
rather than a missing control, and the on-screen list is the answer to that.

.PARAMETER Device
adb device serial. Needed whenever more than one device is attached, which is
the normal case here: the emulator and a physical phone are usually both up.

.PARAMETER Text
The text to match, exactly as it appears on screen.

.PARAMETER Partial
Match on substring instead of the whole string. Use it for text that carries a
dynamic tail - "Alice (3)" - where the stable part is the name.

.PARAMETER List
Print every non-empty text on screen and tap nothing. Useful for finding out what
a screen actually calls something before committing to a script.
#>

# adb writes progress to stderr even on success ("1 file pulled"), which PowerShell
# turns into error records. Continue is set so a successful dump is not mistaken
# for a failure; failures are checked by whether the node was found, not by exit code.
$ErrorActionPreference = 'Continue'

# Checked before anything touches the device. Text is validated here rather than by
# [Parameter(Mandatory)] because -List needs no text at all, and PowerShell drops an
# empty -Text '' before the binder sees it - so a mandatory parameter would force a
# placeholder argument on the one command that does not use it. A usage error should
# not cost a uiautomator dump, which is slow and briefly locks the screen.
if (-not $List -and [string]::IsNullOrEmpty($Text)) {
    Write-Output 'FAIL: pass -Text to tap, or -List to see what is on screen.'
    exit 1
}

# adb is resolved rather than hardcoded. A committed absolute path would make this
# script work on exactly one machine and nowhere else, and it would fail in a way
# that looks like a missing device. local.properties is checked because that is
# where Android Studio records the SDK it is actually using for this project,
# which beats both the environment variables and whatever is on PATH.
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
            # Properties files escape Windows paths, so C\:\\Users\\... is how
            # C:\Users\... is written. Left escaped, Test-Path would not match.
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

# The dump filename carries the process id so two of these running at once - one
# against the emulator, one against the phone, which is the normal setup here -
# cannot pull each other's file. uiautomator still serialises per device, so
# concurrent runs against the *same* device will collide regardless; run them in
# sequence when they target one screen.
$pidTag = [System.Diagnostics.Process]::GetCurrentProcess().Id
# The serial is not used in this filename: a wireless serial carries dots and
# colons ("192.168.2.65:44781"), which Windows rejects as a path. The process id
# is enough, since only one dump runs at a time here.
$dump = "/sdcard/ui-$pidTag.xml"
$local = Join-Path $env:TEMP "tap-ui-$pidTag.xml"

& $adb -s $Device shell uiautomator dump $dump *>$null
& $adb -s $Device pull $dump $local *>$null

if (-not (Test-Path $local)) {
    Write-Output "FAIL: could not read a UI dump from $Device"
    exit 1
}
$xml = [xml](Get-Content $local)
Remove-Item $local -ErrorAction SilentlyContinue

if ($List) {
    $onScreen = $xml.SelectNodes('//node') | ForEach-Object { $_.text } | Where-Object { $_ -ne '' }
    # An empty list is ambiguous on its own - a blank screen and a locked one both
    # dump successfully with no text - so say which it was instead of printing
    # nothing and leaving the caller to guess whether the dump worked.
    if (-not $onScreen) {
        Write-Output "(no text on screen - the screen may be off, locked, or showing a drawing without labels)"
        exit 1
    }
    Write-Output ($onScreen -join "`n")
    exit 0
}

$node = $xml.SelectNodes('//node') | Where-Object {
    if ($Partial) { $_.text -and $_.text.Contains($Text) } else { $_.text -eq $Text }
} | Select-Object -First 1

if (-not $node) {
    Write-Output "NOT FOUND: $Text"
    Write-Output ("on screen: " + (($xml.SelectNodes('//node[@text!=""]') | ForEach-Object { $_.text }) -join ' | '))
    exit 1
}

$numbers = ($node.bounds -replace '[^\d]', ' ') -split '\s+' | Where-Object { $_ -ne '' }
$x = [int](([int]$numbers[0] + [int]$numbers[2]) / 2)
$y = [int](([int]$numbers[1] + [int]$numbers[3]) / 2)
& $adb -s $Device shell input tap $x $y | Out-Null
Write-Output "tapped '$($node.text)' at $x,$y"
