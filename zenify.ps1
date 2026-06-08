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
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Gradlew = Join-Path $Root "gradlew.bat"
$Activity = "com.pn.zenify.ui.MainActivity"
$DebugId = "com.pn.zenify.debug"
$ReleaseId = "com.pn.zenify"

function Info($msg)  { Write-Host "› $msg" -ForegroundColor Cyan }
function Ok($msg)    { Write-Host "✓ $msg" -ForegroundColor Green }
function Warn($msg)  { Write-Host "! $msg" -ForegroundColor Yellow }
function Fail($msg)  { Write-Host "✗ $msg" -ForegroundColor Red }

function Require-Device {
    $devices = (& adb devices) | Select-String "`tdevice$"
    if (-not $devices) {
        Fail "No device/emulator connected (check 'adb devices')."
        exit 1
    }
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
    Write-Host "Flags:  -NoLaunch   (don't relaunch after install)"
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
