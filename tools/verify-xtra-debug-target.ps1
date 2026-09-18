[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string]$ExpectedHead,

    [string]$ApkPath = 'app\build\outputs\apk\debug\app-debug.apk',

    [string]$ExpectedPackage = 'com.github.andreyasadchy.xtra.debug',

    [string]$AdbPath,

    [string]$PlaybackDiagnosticAction = 'com.github.andreyasadchy.xtra.debug.DUMP_PLAYBACK_BACKEND',

    [string]$PlaybackDiagnosticComponent = 'com.github.andreyasadchy.xtra.debug/com.github.andreyasadchy.xtra.debug.PlaybackBackendDiagnosticReceiver',

    [ValidateSet('media3', 'legacy_exoplayer', 'android_media_player')]
    [string]$ExpectedPlaybackBackend,

    [switch]$RequireRunning,

    [switch]$RequirePlaybackBackend,

    [switch]$AllowDirtyWorktree
)

$ErrorActionPreference = 'Stop'

function Invoke-AdbText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & $script:AdbPath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): adb $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return ($output | Out-String).Trim()
}

function Invoke-AdbOptional {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    try {
        return Invoke-AdbText -Arguments $Arguments
    } catch {
        return ''
    }
}

function Get-FirstMatch {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern
    )

    $match = [regex]::Match($Text, $Pattern)
    if ($match.Success -and $match.Groups.Count -gt 1) {
        return $match.Groups[1].Value
    }
    return ''
}

function Get-LastMatch {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern
    )

    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -gt 0 -and $matches[$matches.Count - 1].Groups.Count -gt 1) {
        return $matches[$matches.Count - 1].Groups[1].Value
    }
    return ''
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $AdbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
}
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb was not found at $AdbPath. Pass -AdbPath explicitly."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gitRoot = (& git -C $repoRoot rev-parse --show-toplevel 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($gitRoot)) {
    throw "Unable to resolve the git root from $repoRoot."
}

$branch = (& git -C $repoRoot branch --show-current | Out-String).Trim()
$head = (& git -C $repoRoot rev-parse HEAD | Out-String).Trim()
$status = (& git -C $repoRoot status --porcelain=v1 | Out-String).Trim()
if (-not [string]::Equals($head, $ExpectedHead, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Git HEAD '$head' does not match the expected commit '$ExpectedHead'."
}
if (-not $AllowDirtyWorktree -and -not [string]::IsNullOrWhiteSpace($status)) {
    throw "The worktree is dirty. Commit or stash changes before exact verification, or pass -AllowDirtyWorktree explicitly."
}

$resolvedApkPath = if ([IO.Path]::IsPathRooted($ApkPath)) {
    $ApkPath
} else {
    Join-Path $repoRoot $ApkPath
}
$resolvedApkPath = [IO.Path]::GetFullPath($resolvedApkPath)
$resolvedRepoRoot = [IO.Path]::GetFullPath($repoRoot).TrimEnd('\', '/')
if (-not $resolvedApkPath.StartsWith("$resolvedRepoRoot$([IO.Path]::DirectorySeparatorChar)", [StringComparison]::OrdinalIgnoreCase)) {
    throw "APK '$resolvedApkPath' is outside the selected worktree '$resolvedRepoRoot'."
}
if (-not (Test-Path -LiteralPath $resolvedApkPath -PathType Leaf)) {
    throw "APK was not found at $resolvedApkPath. Build the requested variant first."
}

$buildToolsRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools'
$aapt = Get-ChildItem -LiteralPath $buildToolsRoot -Filter 'aapt.exe' -File -Recurse |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if ($null -eq $aapt) {
    throw "aapt.exe was not found below $buildToolsRoot."
}

$badging = & $aapt.FullName dump badging $resolvedApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "aapt could not inspect $resolvedApkPath.`n$($badging -join [Environment]::NewLine)"
}
$badgingText = $badging | Out-String
$apkPackage = Get-FirstMatch -Text $badgingText -Pattern "package: name='([^']+)'"
$apkVersionCode = Get-FirstMatch -Text $badgingText -Pattern "versionCode='([^']+)'"
$apkVersionName = Get-FirstMatch -Text $badgingText -Pattern "versionName='([^']+)'"
if ($apkPackage -ne $ExpectedPackage) {
    throw "The APK package is '$apkPackage', expected '$ExpectedPackage'."
}

$deviceState = Invoke-AdbText -Arguments @('-s', $Serial, 'get-state')
if ($deviceState -ne 'device') {
    throw "ADB serial '$Serial' is not ready. State: '$deviceState'."
}

$packagePath = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'pm', 'path', $ExpectedPackage)
if ($packagePath -notmatch '(?m)^package:') {
    throw "Package '$ExpectedPackage' is not installed on '$Serial'."
}
$packagePaths = @(
    $packagePath -split '\r?\n' |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -match '^package:.+\.apk$' } |
        ForEach-Object { $_.Substring('package:'.Length) }
)
if ($packagePaths.Count -ne 1) {
    throw "Expected exactly one installed base APK for '$ExpectedPackage', found $($packagePaths.Count): $($packagePaths -join ', ')"
}
$installedApkPath = $packagePaths[0]
$hash = (Get-FileHash -LiteralPath $resolvedApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$installedHashText = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'sha256sum', $installedApkPath)
$installedHash = Get-FirstMatch -Text $installedHashText -Pattern '(?m)^\s*([0-9a-fA-F]{64})\s+'
if ([string]::IsNullOrWhiteSpace($installedHash)) {
    throw "Unable to read the SHA-256 hash of installed APK '$installedApkPath' on '$Serial'."
}
$installedHash = $installedHash.ToLowerInvariant()
if ($installedHash -ne $hash) {
    throw "Installed APK hash '$installedHash' does not match local APK hash '$hash'."
}

$packageDump = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'package', $ExpectedPackage)
$installedVersionCode = Get-FirstMatch -Text $packageDump -Pattern 'versionCode=(\d+)'
$installedVersionName = Get-FirstMatch -Text $packageDump -Pattern 'versionName=([^\s]+)'
if ($installedVersionCode -ne $apkVersionCode) {
    throw "Installed versionCode '$installedVersionCode' does not match APK versionCode '$apkVersionCode'."
}
if ($installedVersionName -ne $apkVersionName) {
    throw "Installed versionName '$installedVersionName' does not match APK versionName '$apkVersionName'."
}

$avdResponse = Invoke-AdbText -Arguments @('-s', $Serial, 'emu', 'avd', 'name')
if ($avdResponse -match '(?im)^\s*KO\s*:') {
    throw "The emulator rejected the AVD name request: $avdResponse"
}
$avdName = @(
    $avdResponse -split '\r?\n' |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -ne 'OK' } |
        Select-Object -First 1
)
if ($avdName.Count -ne 1) {
    throw "Unable to determine the AVD name for '$Serial'. Response: $avdResponse"
}
$avdName = $avdName[0]
$activityDump = Invoke-AdbOptional -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'activity', 'activities')
$activity = Get-FirstMatch -Text $activityDump -Pattern '(?m)^\s*topResumedActivity=ActivityRecord\{[^\s]+\s+u\d+\s+([^\s}]+)'
if ([string]::IsNullOrWhiteSpace($activity)) {
    $activity = Get-FirstMatch -Text $activityDump -Pattern '(?m)^\s*ResumedActivity:\s+ActivityRecord\{[^\s]+\s+u\d+\s+([^\s}]+)'
}
$processOutput = Invoke-AdbOptional -Arguments @('-s', $Serial, 'shell', 'pidof', $ExpectedPackage)
$process = Get-FirstMatch -Text $processOutput -Pattern '^\s*(\d+)'

if ($RequireRunning) {
    if ([string]::IsNullOrWhiteSpace($process)) {
        throw "Package '$ExpectedPackage' is installed but is not running on '$Serial'."
    }
    if ($activity -notlike "$ExpectedPackage/*") {
        throw "Package '$ExpectedPackage' is running but is not the focused activity on '$Serial'. Focused activity: '$activity'."
    }
}

$playbackBackend = ''
$needsPlaybackDiagnostic = $RequirePlaybackBackend -or -not [string]::IsNullOrWhiteSpace($ExpectedPlaybackBackend)
if ($needsPlaybackDiagnostic) {
    if ([string]::IsNullOrWhiteSpace($process)) {
        throw "The current app process is not running; cannot query the playback backend."
    }
    $diagnosticToken = [guid]::NewGuid().ToString('N')
    Invoke-AdbText -Arguments @(
        '-s', $Serial,
        'shell',
        'am',
        'broadcast',
        '-a',
        $PlaybackDiagnosticAction,
        '-n',
        $PlaybackDiagnosticComponent,
        '--es',
        'token',
        $diagnosticToken
    ) | Out-Null
    $diagnosticLogcat = Invoke-AdbText -Arguments @('-s', $Serial, 'logcat', '-d', '-v', 'threadtime', '-s', 'PlaybackBackendDiagnostic:I', '*:S')
    $processPattern = [regex]::Escape($process.Trim())
    $tokenPattern = [regex]::Escape($diagnosticToken)
    $diagnosticState = Get-LastMatch -Text $diagnosticLogcat -Pattern "(?m)^\s*\S+\s+\S+\s+$processPattern\s+\S+\s+[VDIWEF]\s+PlaybackBackendDiagnostic:\s+playback_runtime_diagnostic\s+token=$tokenPattern\s+state=([a-z_]+)"
    if ([string]::IsNullOrWhiteSpace($diagnosticState)) {
        throw "The current playback diagnostic did not return a state for process '$process'."
    }
    if ($diagnosticState -ne 'none') {
        $playbackBackend = $diagnosticState
    }
}
if ($RequirePlaybackBackend -and [string]::IsNullOrWhiteSpace($playbackBackend)) {
    throw "No player is currently attached to the foreground app process '$process'."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedPlaybackBackend) -and $playbackBackend -ne $ExpectedPlaybackBackend) {
    throw "Current playback backend '$playbackBackend' does not match expected backend '$ExpectedPlaybackBackend'."
}

$statusLabel = if ([string]::IsNullOrWhiteSpace($status)) { 'clean' } else { 'dirty' }
$activityLabel = if ([string]::IsNullOrWhiteSpace($activity)) { '<none>' } else { $activity }
$processLabel = if ([string]::IsNullOrWhiteSpace($process)) { '<not running>' } else { $process }
$playbackBackendLabel = if ([string]::IsNullOrWhiteSpace($playbackBackend)) { '<unknown>' } else { $playbackBackend }

@(
    "worktree=$gitRoot"
    "branch=$branch"
    "head=$head"
    "expected_head=$ExpectedHead"
    "worktree_status=$statusLabel"
    "apk=$([IO.Path]::GetFullPath($resolvedApkPath))"
    "apk_sha256=$hash"
    "apk_package=$apkPackage"
    "apk_version_name=$apkVersionName"
    "apk_version_code=$apkVersionCode"
    "serial=$Serial"
    "avd=$avdName"
    "installed_package=$ExpectedPackage"
    "installed_apk=$installedApkPath"
    "installed_apk_sha256=$installedHash"
    "installed_version_name=$installedVersionName"
    "installed_version_code=$installedVersionCode"
    "activity=$activityLabel"
    "process=$processLabel"
    "playback_backend=$playbackBackendLabel"
) | Write-Output
