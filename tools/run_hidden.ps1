param(
    [Parameter(Mandatory = $true)][string]$Command,
    [int]$TimeoutSeconds = 3600
)

<#
.SYNOPSIS
Runs a command in a hidden console window and streams the result back.

.DESCRIPTION
Every PowerShell process started with a visible console asks Windows to bring
that window to the foreground. When a person is mid-word in a text box, that
activation is what loses their keystrokes. This runs the work in a window that
never appears, copies its output and exit code back, and prints them here, so
the caller sees exactly what it would have seen and the person keeps their
cursor.

Output and the exit code are round-tripped rather than inherited, because a
hidden process has no console to inherit: Start-Process would otherwise give
back a process object and no output at all. Two consequences worth knowing:

  - stdout and stderr are captured to separate files and re-emitted separately,
    so a caller that filters on either stream keeps working.
  - the exit code is the command's, not Start-Process's, so a build that fails
    still reports failure to whatever ran this.

.PARAMETER Command
The PowerShell to run. Quoted exactly as it would have been typed.

.PARAMETER TimeoutSeconds
Kill the hidden process after this long. Exists so a hung build cannot wedge
the caller's shell with no output and no explanation; the default is an hour,
which is longer than any single Gradle invocation here.

.EXAMPLE
.\tools\run_hidden.ps1 -Command '.\gradlew.bat testDebugUnitTest --console=plain'

.EXAMPLE
.\tools\run_hidden.ps1 -Command 'git status --porcelain' -TimeoutSeconds 30
#>
$ErrorActionPreference = 'Stop'

$dir = Join-Path $env:TEMP ("run_hidden_" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dir -Force | Out-Null

$outFile = Join-Path $dir 'stdout.txt'
$errFile = Join-Path $dir 'stderr.txt'
$consoleErrFile = Join-Path $dir 'stderr_console.txt'
$codeFile = Join-Path $dir 'exit.txt'
$scriptFile = Join-Path $dir 'run.ps1'

try {
    # Written to a file rather than passed with -Command, because a multi-line
    # script full of quotes does not survive being flattened onto a command
    # line. -File sidesteps the quoting problem entirely.
    #
    # The error stream is redirected in two ways on purpose. `2>` covers
    # PowerShell's own error output, but code that writes through
    # [Console]::Error goes straight to the process handle and never touches
    # it - which is how Gradle's native output and any direct .NET write were
    # being dropped on the floor. Setting the console's error writer as well
    # catches both. AutoFlush matters for the same reason: a buffer flushed at
    # exit would lose everything if the process were killed.
    @"
`$ErrorActionPreference = 'Continue'
`$failed = `$false
`$errors = [System.Collections.Generic.List[string]]::new()
try {
    # Code that writes through [Console]::Error goes straight to the process
    # handle and never reaches PowerShell's error stream, so it is redirected
    # separately. A *different* file: opening one path from a StreamWriter and
    # from PowerShell's redirection simultaneously is a sharing violation, and
    # it throws before the command runs - taking the stdout file with it.
    `$errWriter = [System.IO.StreamWriter]::new('$consoleErrFile', `$false)
    `$errWriter.AutoFlush = `$true
    [Console]::SetError(`$errWriter)

    # Streams are separated in-process rather than with 2>, because Windows
    # PowerShell 5.1 formats a redirected ErrorRecord as a paragraph: the
    # message, a position, a stack frame, a category and a FullyQualifiedErrorId.
    # Redirected to a file that is all the file contains, and it buries the one
    # line anyone is looking for.
    #
    # Both streams are captured into one array and then split by type, which is
    # the only arrangement that actually separates them here.
    #
    # The obvious attempts all fail in ways that look like they work:
    # `1> out 2> err` is fine but loses anything written straight to the process
    # handle; `2>&1 1> out` is worse, because the merge happens first and the
    # file then takes everything including the errors; and reformatting the
    # resulting records is the thing being avoided below. `$all` keeps the
    # distinction that only the object type carries.
    #
    # `.TargetObject` rather than `.Exception.Message`: for a native command the
    # record is a RemoteException whose Message is the *rendered* form - the
    # command name, the text, then a position paragraph pointing into this
    # wrapper's own generated script. Taking the message re-introduces exactly
    # the noise being removed. TargetObject is the text the command emitted.
    `$all = @(& { $Command } 2>&1)
    foreach (`$item in `$all) {
        if (`$item -is [System.Management.Automation.ErrorRecord]) {
            `$message = if (`$item.TargetObject -is [string] -and `$item.TargetObject) {
                `$item.TargetObject
            } else {
                `$item.Exception.Message
            }
            `$errors.Add(`$message)
        } else {
            `$item | Out-File -FilePath '$outFile' -Encoding utf8 -Append
        }
    }
} catch {
    `$failed = `$true
    `$errors.Add(`$_.Exception.Message)
}
`$errors | Out-File -FilePath '$errFile' -Encoding utf8
# `$LASTEXITCODE is set by the last *native* command and is stale for a pure
# PowerShell script, so a command that never set it is treated as success.
# A caught exception overrides it: `throw` leaves `$LASTEXITCODE at its
# inherited value, and reporting a thrown command as success is worse than
# reporting a native one as failed.
`$code = if (`$failed) { 1 } elseif (`$null -eq `$LASTEXITCODE) { 0 } else { `$LASTEXITCODE }
`$code | Out-File -FilePath '$codeFile' -Encoding utf8
"@ | Set-Content -Path $scriptFile -Encoding utf8

    $process = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $scriptFile `
        -WindowStyle Hidden `
        -PassThru

    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $process.Kill() } catch { }
        # WriteErrorLine rather than Write-Error: the latter throws under the
        # $ErrorActionPreference = 'Stop' set above, so the caller saw a
        # position paragraph pointing at this line and exit code 1 instead of
        # the 124 that means "timed out". This prints the bare line and lets
        # the exit below run.
        $Host.UI.WriteErrorLine("run_hidden: timed out after $TimeoutSeconds s. The hidden window was killed.")
        exit 124
    }

    # Emitted a line at a time rather than as one blob. Write-Output of a
    # multi-line string hands the caller a single object, so `Select-String`
    # matches the whole build log as one item and every filter afterwards is
    # useless. Per line is what the command would have produced.
    if (Test-Path $outFile) {
        foreach ($line in ((Get-Content $outFile -Encoding utf8) -split "`r?`n")) {
            if ($line) { Write-Output $line }
        }
    }

    # stderr goes through WriteErrorLine rather than [Console]::Error. Both
    # reach the error stream as far as Windows is concerned, but only the
    # former is the *PowerShell* error stream, so a caller using 2>&1 would
    # otherwise lose it - and it prints the bare line, where an ErrorRecord
    # would arrive wrapped in a position, a stack trace and a category that the
    # original command never produced, swamping the message being grepped for.
    foreach ($file in @($errFile, $consoleErrFile)) {
        if (-not (Test-Path $file)) { continue }
        foreach ($line in ((Get-Content $file -Encoding utf8) -split "`r?`n")) {
            if ($line.Trim()) { $Host.UI.WriteErrorLine($line) }
        }
    }

    $exitCode = 0
    if (Test-Path $codeFile) {
        $raw = (Get-Content $codeFile -Raw -Encoding utf8).Trim()
        if ($raw) { $exitCode = [int]$raw }
    } else {
        # `exit 7` inside the generated script unwinds before the code file is
        # written, so the file is missing and the caller would be told 0 for a
        # command that plainly failed. PowerShell still sets the *process* exit
        # code from that `exit`, so the process is the authority when the file
        # never made it out. The file stays first choice because it is the value
        # the script computed deliberately; the process code is a fallback for
        # exactly this unwound-before-writing case.
        $exitCode = $process.ExitCode
    }
    exit $exitCode
}
finally {
    # Always cleaned up, including on timeout: these are in the temp directory,
    # so a leaked directory per invocation would otherwise be invisible.
    Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue
}