<#
.SYNOPSIS
    Zenify dev helper — build, install, run, and live-reload the app.

.DESCRIPTION
    One script for the whole local loop. Examples:

        ./zenify.ps1 run            # build + install + launch (debug)
        ./zenify.ps1 dev            # watch sources; rebuild+reinstall+relaunch on save
        ./zenify.ps1 restart        # relaunch the app (no rebuild)
        ./zenify.ps1 debug          # assemble debug APK
        ./zenify.ps1 install-debug  # build + install debug
        ./zenify.ps1 release        # assemble release APK
        ./zenify.ps1 install-release# build + install release
        ./zenify.ps1 logcat         # tail app logs
        ./zenify.ps1 uninstall      # remove both debug & release
        ./zenify.ps1 help

    Note on "hot reload": native Android/Compose has no CLI hot reload (that is
    an Android Studio feature). `dev` is the practical equivalent — it watches
    your source files and does a fast incremental rebuild + reinstall + relaunch
    whenever you save, so you just Ctrl-S and watch the device update.
#>

param(
    [Parameter(Position = 0)]
    [string]$Command = "help",

    # Skip relaunch after install
    [switch]$NoLaunch,

    # Target a specific device (serial or partial match). When omitted and more
    # than one device is connected, the script prompts for a choice.
    [Alias("s")]
    [string]$Device
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Gradlew = Join-Path $Root "gradlew.bat"
$Activity = "com.pn.zenify.ui.MainActivity"
$DebugId = "com.pn.zenify.debug"
$ReleaseId = "com.pn.zenify"
$script:Serial = $null

function Info($msg)  { Write-Host "› $msg" -ForegroundColor Cyan }
function Ok($msg)    { Write-Host "✓ $msg" -ForegroundColor Green }
function Warn($msg)  { Write-Host "! $msg" -ForegroundColor Yellow }
function Fail($msg)  { Write-Host "✗ $msg" -ForegroundColor Red }

function Get-Devices {
    # Serials of all fully-online devices (skips 'offline'/'unauthorized').
    $devs = @()
    foreach ($line in (& adb devices)) {
        if ($line -match '^(\S+)\s+device\s*$') { $devs += $Matches[1] }
    }
    return $devs
}

function Get-DeviceLabel($serial) {
    $model = (& adb -s $serial shell getprop ro.product.model 2>$null)
    if ($model) { "$serial ($($model.ToString().Trim()))" } else { $serial }
}

# Pick exactly one target device and pin it for every adb + Gradle call via the
# ANDROID_SERIAL env var (both adb and the Android Gradle Plugin honor it). This
# is what stops 'am start' from failing with "more than one device/emulator".
function Require-Device {
    if ($script:Serial) { return }

    $devices = @(Get-Devices)
    if ($devices.Count -eq 0) {
        Fail "No device/emulator connected (check 'adb devices')."
        exit 1
    }

    $preferred = if ($Device) { $Device } elseif ($env:ANDROID_SERIAL) { $env:ANDROID_SERIAL } else { $null }
    if ($preferred) {
        $match = $devices | Where-Object { $_ -eq $preferred -or $_ -like "*$preferred*" } | Select-Object -First 1
        if (-not $match) { Fail "No connected device matches '$preferred'. Connected: $($devices -join ', ')"; exit 1 }
        $script:Serial = $match
    }
    elseif ($devices.Count -eq 1) {
        $script:Serial = $devices[0]
    }
    else {
        Warn "Multiple devices connected:"
        for ($i = 0; $i -lt $devices.Count; $i++) {
            Write-Host ("  [{0}] {1}" -f ($i + 1), (Get-DeviceLabel $devices[$i]))
        }
        $choice = Read-Host "Select device number (1-$($devices.Count)), or Enter for [1]"
        if ([string]::IsNullOrWhiteSpace($choice)) { $choice = "1" }
        $idx = 0
        if (-not ([int]::TryParse($choice, [ref]$idx)) -or $idx -lt 1 -or $idx -gt $devices.Count) {
            Fail "Invalid selection."
            exit 1
        }
        $script:Serial = $devices[$idx - 1]
    }

    $env:ANDROID_SERIAL = $script:Serial
    Ok "Using device: $(Get-DeviceLabel $script:Serial)"
}

function Gradle($task) {
    Info "gradlew $task"
    & $Gradlew $task
    if ($LASTEXITCODE -ne 0) { Fail "Gradle task '$task' failed."; exit 1 }
}

function Launch($appId) {
    Require-Device
    Info "Launching $appId"
    & adb shell am start -n "$appId/$Activity" | Out-Null
    Ok "Launched."
}

function Restart($appId) {
    Require-Device
    & adb shell am force-stop $appId | Out-Null
    Launch $appId
}

function Build-Debug        { Gradle ":app:assembleDebug" }
function Build-Release      { Gradle ":app:assembleRelease" }

function Install-Debug {
    Require-Device
    Gradle ":app:installDebug"
    Ok "Installed debug."
    if (-not $NoLaunch) { Launch $DebugId }
}

function Install-Release {
    Require-Device
    Gradle ":app:installRelease"
    Ok "Installed release."
    if (-not $NoLaunch) { Launch $ReleaseId }
}

function Dev-Watch {
    Require-Device
    Info "Initial build + install…"
    Gradle ":app:installDebug"
    Restart $DebugId
    Ok "Watching app/src for changes. Press Ctrl-C to stop."

    $srcDir = Join-Path $Root "app\src"
    function Snapshot {
        (Get-ChildItem -Path $srcDir -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object -Property LastWriteTimeUtc -Maximum).Maximum
    }
    $last = Snapshot
    while ($true) {
        Start-Sleep -Milliseconds 800
        $now = Snapshot
        if ($now -and $now -ne $last) {
            $last = $now
            Write-Host ""
            Info "Change detected → rebuilding…"
            & $Gradlew ":app:installDebug"
            if ($LASTEXITCODE -eq 0) {
                Restart $DebugId
                Ok ("Reloaded at {0:HH:mm:ss}" -f (Get-Date))
            } else {
                Warn "Build failed — fix the error and save again."
            }
        }
    }
}

function Logcat {
    Require-Device
    Info "Tailing logs (Zenify tags + crashes). Ctrl-C to stop."
    & adb logcat -v color -s ZenifyShizuku,ZenifyHibernator,ForceStop,AndroidRuntime,System.err
}

function Uninstall {
    Require-Device
    foreach ($id in @($DebugId, $ReleaseId)) {
        Info "Uninstalling $id"
        & adb uninstall $id 2>$null | Out-Null
    }
    Ok "Done."
}

function Show-Help {
    Write-Host ""
    Write-Host "Zenify dev helper" -ForegroundColor Green
    Write-Host "  run             build + install + launch (debug)"
    Write-Host "  dev             watch sources; rebuild + reinstall + relaunch on save"
    Write-Host "  restart         relaunch the app without rebuilding"
    Write-Host "  debug           assemble debug APK"
    Write-Host "  install-debug   build + install debug"
    Write-Host "  release         assemble release APK"
    Write-Host "  install-release build + install release"
    Write-Host "  logcat          tail app logs"
    Write-Host "  uninstall       remove debug & release builds"
    Write-Host "  help            this message"
    Write-Host ""
    Write-Host "Flags:  -NoLaunch          (don't relaunch after install)"
    Write-Host "        -Device <serial>   (target a device; serial or partial match)"
    Write-Host ""
    Write-Host "With multiple devices connected and no -Device, you'll be prompted to pick one."
    Write-Host ""
}

switch ($Command.ToLower()) {
    "run"             { Install-Debug }
    "dev"             { Dev-Watch }
    "watch"           { Dev-Watch }
    "restart"         { Restart $DebugId }
    "refresh"         { Restart $DebugId }
    "debug"           { Build-Debug }
    "install-debug"   { Install-Debug }
    "idebug"          { Install-Debug }
    "release"         { Build-Release }
    "install-release" { Install-Release }
    "irelease"        { Install-Release }
    "logcat"          { Logcat }
    "log"             { Logcat }
    "uninstall"       { Uninstall }
    default           { Show-Help }
}
