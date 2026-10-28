<#
.SYNOPSIS
Build, install and exercise Skymning on an authorized Android device.
.DESCRIPTION
Physical devices are preferred. Emulators require -AllowEmulator or an explicit -Serial.
This does not grant USB permission, unlock the phone, or change system settings.
Screenshots and process/lifecycle checks are smoke evidence, not a claim of human playtesting.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$SkipBuild,
    [switch]$AllowEmulator,
    [switch]$FullRound,
    [string]$ApkPath,
    [double]$TrainingX = 285,
    [double]$TrainingY = 332
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runRoot = Join-Path $projectRoot ('test-output/device-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$package = 'se.nils.skymning'
$component = "$package/.MainActivity"
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$script:checkpoints = [System.Collections.Generic.List[object]]::new()
$script:hasStateMarkers = $false
$report = [ordered]@{ started = (Get-Date -Format o); status = 'RUNNING'; serial = ''; physical = $false; apk = ''; checkpoints = @(); limitations = @('ADB gestures do not validate simultaneous multi-touch or subjective game feel.'); error = '' }

function Invoke-Adb {
    # No advanced-function parameter binding: adb flags such as -W and -v must
    # remain native arguments instead of matching PowerShell common parameters.
    [string[]]$Arguments = $args
    $nativeOutput = & $adbPath -s $script:selectedSerial @Arguments 2>&1
    $nativeExit = $LASTEXITCODE
    $textOutput = ($nativeOutput | ForEach-Object ToString) -join "`n"
    if ($nativeExit -ne 0) { throw "adb $($Arguments -join ' ') failed ($nativeExit): $textOutput" }
    return $textOutput
}
function Add-Checkpoint([string]$Name, [string]$Status, [string]$Detail) {
    $script:checkpoints.Add([ordered]@{ name = $Name; status = $Status; detail = $Detail; at = (Get-Date -Format o) })
    Write-Host "[$Status] $Name - $Detail"
}
function Device-Point([double]$X, [double]$Y) {
    return @([int]($script:canvasX + $X * $script:canvasScale), [int]($script:canvasY + $Y * $script:canvasScale))
}
function Tap([double]$X, [double]$Y) {
    $point = Device-Point $X $Y
    Invoke-Adb shell input tap $point[0] $point[1] | Out-Null
}
function Set-CanvasGeometry([string]$Xml) {
    $originX = 0; $originY = 0; $viewWidth = $script:screenWidth; $viewHeight = $script:screenHeight
    $marker = ''
    if ($Xml) {
        [xml]$document = $Xml
        $node = $document.SelectSingleNode("//node[contains(@content-desc,'screen=')]")
        if ($node) {
            $marker = $node.GetAttribute('content-desc')
            if ($node.GetAttribute('bounds') -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                $originX = [int]$Matches[1]; $originY = [int]$Matches[2]
                $viewWidth = [int]$Matches[3] - $originX; $viewHeight = [int]$Matches[4] - $originY
            }
        }
    }
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    if ($marker -match '(?:^|;)uiX=([\d.]+);uiY=([\d.]+);uiScale=([\d.]+)') {
        $script:canvasX = $originX + [double]::Parse($Matches[1], $culture)
        $script:canvasY = $originY + [double]::Parse($Matches[2], $culture)
        $script:canvasScale = [double]::Parse($Matches[3], $culture)
        $method = 'Exact Canvas transform from accessibility marker and View bounds.'
    } else {
        $left = 0; $top = 0; $right = 0; $bottom = 0
        $displayDump = Invoke-Adb shell dumpsys window displays
        $displayDump | Set-Content -Encoding utf8 (Join-Path $runRoot 'display.txt')
        if ($displayDump -match 'mDisplayCutout=DisplayCutout\{insets=Rect\((\d+), (\d+) - (\d+), (\d+)\)') {
            $left = [int]$Matches[1]; $top = [int]$Matches[2]; $right = [int]$Matches[3]; $bottom = [int]$Matches[4]
        }
        $script:canvasScale = [Math]::Min(($viewWidth-$left-$right)/1280.0, ($viewHeight-$top-$bottom)/720.0)
        $script:canvasX = $originX + $left + ($viewWidth-$left-$right-1280*$script:canvasScale)/2
        $script:canvasY = $originY + $top + ($viewHeight-$top-$bottom-720*$script:canvasScale)/2
        $method = 'Derived from View bounds and display cutout; validate against screenshot.'
    }
    if ($script:canvasScale -le 0) { throw 'Invalid Canvas transform.' }
    "x=$script:canvasX y=$script:canvasY scale=$script:canvasScale`n$method" | Set-Content -Encoding utf8 (Join-Path $runRoot 'touch-transform.txt')
    Add-Checkpoint 'Touch mapping' 'PASS' $method
}
function Assert-Running([string]$Label) {
    $appPid = Invoke-Adb shell pidof $package
    if (-not ($appPid -match '^\d')) { throw "App process is absent at $Label." }
    $activity = Invoke-Adb shell dumpsys activity activities
    $activity | Set-Content -Encoding utf8 (Join-Path $runRoot "$Label-activity.txt")
    if ($activity -notmatch "(?m)(mResumedActivity|topResumedActivity|ResumedActivity).*se\.nils\.skymning") {
        throw "App is not the resumed activity at $Label; inspect activity dump (phone may be locked)."
    }
    Add-Checkpoint $Label 'PASS' "Process $appPid is running and activity is resumed."
}
function Capture([string]$Label) {
    $remotePng = "/sdcard/skymning-$Label.png"
    Invoke-Adb shell screencap -p $remotePng | Out-Null
    Invoke-Adb pull $remotePng (Join-Path $runRoot "$Label.png") | Out-Null
    Invoke-Adb shell rm $remotePng | Out-Null
    $remoteXml = "/sdcard/skymning-$Label.xml"
    try {
        Invoke-Adb shell uiautomator dump $remoteXml | Out-Null
        Invoke-Adb pull $remoteXml (Join-Path $runRoot "$Label.xml") | Out-Null
        Invoke-Adb shell rm $remoteXml | Out-Null
        $uiText = Get-Content -Raw (Join-Path $runRoot "$Label.xml")
        if ($uiText -match 'screen=') { $script:hasStateMarkers = $true }
        return $uiText
    } catch {
        Add-Checkpoint "$Label UI tree" 'UNVERIFIED' $_.Exception.Message
        return ''
    }
}
function Assert-Screen([string]$Xml, [string]$Expected, [string]$Label) {
    if ($Xml -match 'screen=') {
        if ($Xml -notmatch "screen=$Expected(?:;|&|\s|`")") { throw "Expected screen=$Expected at $Label; inspect screenshot and XML." }
        Add-Checkpoint "$Label state" 'PASS' "screen=$Expected"
    } else {
        Add-Checkpoint "$Label state" 'UNVERIFIED' 'No accessible screen marker; screenshot requires visual inspection.'
    }
}
function Assert-Paused([string]$Xml, [bool]$Expected, [string]$Label) {
    $value = $Expected.ToString().ToLowerInvariant()
    if ($Xml -notmatch "paused=$value(?:;|&|\s|`")") { throw "Expected paused=$value at $Label; inspect screenshot and XML." }
    Add-Checkpoint "$Label pause state" 'PASS' "paused=$value"
}

try {
    Push-Location $projectRoot
    try {
        if (-not $SkipBuild) {
            & (Join-Path $PSScriptRoot 'Test-Core.ps1')
            if (Test-Path -LiteralPath (Join-Path $projectRoot 'gradlew.bat')) {
                & (Join-Path $projectRoot 'gradlew.bat') assembleDebug 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'build.txt')
                if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)." }
            } elseif (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'Build.ps1')) {
                & (Join-Path $PSScriptRoot 'Build.ps1')
            } else { throw 'Neither gradlew.bat nor scripts/Build.ps1 exists.' }
            Add-Checkpoint 'Build and core regression' 'PASS' 'Build and test commands returned successfully.'
        }
    } finally { Pop-Location }
    if (-not $ApkPath) { $ApkPath = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk' }
    $ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
    $report.apk = $ApkPath
    $devices = & $adbPath devices -l 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed.' }
    $devices | Set-Content -Encoding utf8 (Join-Path $runRoot 'devices.txt')
    $authorized = @($devices | ForEach-Object { if ($_ -match '^(\S+)\s+device\s') { $Matches[1] } })
    if ($Serial) {
        if ($authorized -notcontains $Serial) { throw "BLOCKED: requested device '$Serial' is not authorized/online. Approve USB debugging on the phone if adb reports unauthorized." }
        $script:selectedSerial = $Serial
    } else {
        $physical = @($authorized | Where-Object { $_ -notlike 'emulator-*' })
        if ($physical.Count -gt 1) { throw 'Multiple physical devices found; use -Serial to avoid installing on the wrong device.' }
        if ($physical.Count -eq 1) { $script:selectedSerial = $physical[0] }
        elseif ($AllowEmulator -and $authorized.Count -eq 1) { $script:selectedSerial = $authorized[0] }
        else { throw 'BLOCKED: no authorized physical Android device. Approve USB debugging on the phone. An existing emulator can be selected with -AllowEmulator.' }
    }
    $report.serial = $script:selectedSerial
    $report.physical = $script:selectedSerial -notlike 'emulator-*'
    $deviceInfo = @(
        "Serial: $script:selectedSerial",
        "Model: $(Invoke-Adb shell getprop ro.product.model)",
        "Android: $(Invoke-Adb shell getprop ro.build.version.release)",
        "API: $(Invoke-Adb shell getprop ro.build.version.sdk)",
        "ABI: $(Invoke-Adb shell getprop ro.product.cpu.abi)",
        "Display: $(Invoke-Adb shell wm size)",
        "Density: $(Invoke-Adb shell wm density)"
    )
    $deviceInfo | Set-Content -Encoding utf8 (Join-Path $runRoot 'device-info.txt')
    Invoke-Adb install -r $ApkPath | Set-Content -Encoding utf8 (Join-Path $runRoot 'install.txt')
    Invoke-Adb shell am force-stop $package | Out-Null
    # Keep a timestamp for filtering; do not clear unrelated device logs.
    $logSince = (Invoke-Adb shell date '+%m-%d\ %H:%M:%S.000').Trim()
    Invoke-Adb shell am start -W -n $component | Set-Content -Encoding utf8 (Join-Path $runRoot 'launch.txt')
    Start-Sleep -Seconds 2
    $size = Invoke-Adb shell wm size
    $sizeMatches = [regex]::Matches($size, '(\d+)x(\d+)')
    if ($sizeMatches.Count -eq 0) { throw "Cannot parse display dimensions: $size" }
    $sizeMatch = $sizeMatches[$sizeMatches.Count - 1]
    $script:screenWidth = [Math]::Max([int]$sizeMatch.Groups[1].Value, [int]$sizeMatch.Groups[2].Value)
    $script:screenHeight = [Math]::Min([int]$sizeMatch.Groups[1].Value, [int]$sizeMatch.Groups[2].Value)
    Assert-Running '01-menu'
    $menuXml = Capture '01-menu'
    Assert-Screen $menuXml 'menu' '01-menu'
    Set-CanvasGeometry $menuXml
    Tap $TrainingX $TrainingY
    Start-Sleep -Seconds 1
    Assert-Running '02-training'
    $trainingXml = Capture '02-training'
    Assert-Screen $trainingXml 'game' '02-training'
    $startPoint = Device-Point 126 583
    $endPoint = Device-Point 205 583
    Invoke-Adb shell input swipe $startPoint[0] $startPoint[1] $endPoint[0] $endPoint[1] 1600 | Out-Null
    Tap 1148 583
    Capture '03-controls' | Out-Null
    Tap 1195 48
    Start-Sleep -Milliseconds 400
    $pauseXml = Capture '04-pause'
    Assert-Screen $pauseXml 'game' '04-pause'
    Assert-Paused $pauseXml $true '04-pause'
    Tap 640 335
    Start-Sleep -Milliseconds 400
    Invoke-Adb shell input keyevent 3 | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb shell am start -W -n $component | Out-Null
    Start-Sleep -Seconds 1
    Assert-Running '05-resume'
    $resumeXml = Capture '05-resume'
    Assert-Screen $resumeXml 'game' '05-resume'
    Assert-Paused $resumeXml $true '05-resume'
    Tap 640 335
    for ($i = 0; $i -lt 12; $i++) { Tap 1148 583 }
    Assert-Running '06-rapid-input'
    $rapidXml = Capture '06-rapid-input'
    Assert-Paused $rapidXml $false '06-rapid-input'
    if ($FullRound) {
        Write-Host 'Waiting for a complete round; this is a lifecycle/input smoke test, not a skillful playthrough.'
        foreach ($part in 1..5) {
            Start-Sleep -Seconds 26
            Assert-Running "round-$part"
        }
        $resultXml = Capture '07-round-end'
        Assert-Screen $resultXml 'result' '07-round-end'
    }
    Invoke-Adb shell dumpsys meminfo $package | Set-Content -Encoding utf8 (Join-Path $runRoot 'memory.txt')
    Invoke-Adb shell dumpsys gfxinfo $package | Set-Content -Encoding utf8 (Join-Path $runRoot 'frames.txt')
    $log = Invoke-Adb logcat -d -v threadtime -T $logSince
    $log | Set-Content -Encoding utf8 (Join-Path $runRoot 'logcat.txt')
    if ($log -match '(?s)FATAL EXCEPTION[^\r\n]*\r?\n(?:[^\r\n]*\r?\n){0,4}[^\r\n]*Process: se\.nils\.skymning') { throw 'Package crash detected in logcat.' }
    if ($log -match 'ANR in se\.nils\.skymning') { throw 'Package ANR detected in logcat.' }
    Add-Checkpoint 'Crash/ANR scan' 'PASS' 'No package crash or ANR matched captured logcat.'
    $unverifiedChecks = @($script:checkpoints | Where-Object { $_.status -eq 'UNVERIFIED' })
    $report.status = if ($script:hasStateMarkers -and $unverifiedChecks.Count -eq 0) { 'SMOKE_PASS' } else { 'PARTIAL_SCREENSHOTS_REQUIRE_REVIEW' }
    $report.limitations += 'Human visual inspection and physical multi-touch/game-feel checks remain necessary.'
} catch {
    $report.error = $_.Exception.Message
    $report.status = if ($report.error.StartsWith('BLOCKED:')) { 'BLOCKED' } else { 'FAIL' }
    Write-Warning $report.error
} finally {
    $report.finished = Get-Date -Format o
    $report.checkpoints = @($script:checkpoints.ToArray())
    $report | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $runRoot 'report.json')
    Write-Host "Device test report: $runRoot"
}
if ($report.status -eq 'FAIL' -or $report.status -eq 'BLOCKED') { exit 1 }
