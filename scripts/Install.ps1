<# Installs only this project's APK. Never changes Play Protect or device settings. #>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ApkPath,[Parameter(Mandatory)][string]$Serial,[string]$OutputDirectory)
$ErrorActionPreference='Stop'
$apk=(Resolve-Path -LiteralPath $ApkPath).Path
if(-not $OutputDirectory){$OutputDirectory=Join-Path (Split-Path -Parent $PSScriptRoot) 'test-output/install'}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$adb=(Get-Command adb -ErrorAction Stop).Source
$stdout=Join-Path $OutputDirectory 'install.txt'
$stderr=Join-Path $OutputDirectory 'install-error.txt'
$process=Start-Process -FilePath $adb -ArgumentList @('-s',$Serial,'install','-r',('"'+$apk+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$deadline=(Get-Date).AddSeconds(120)
try {
    while(-not $process.WaitForExit(5000)) {
        if((Get-Date) -gt $deadline){throw 'Installation timed out. Inspect the device for an installation dialog.'}
        # Google can ask to upload an unknown APK. Decline upload for this app only,
        # without changing any global protection or upload preference.
        & $adb -s $Serial shell uiautomator dump /sdcard/skymning-install-ui.xml 2>$null | Out-Null
        $raw=& $adb -s $Serial shell cat /sdcard/skymning-install-ui.xml 2>$null
        try {
            [xml]$ui=$raw
            if($ui.SelectSingleNode('//node[@package="com.android.vending"]') -and $ui.SelectSingleNode('//node[@text="Skymning"]')) {
                $no=$ui.SelectSingleNode('//node[@text="Skicka inte" or @text="Do not send"]')
                if($no -and $no.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                    $tx=[int](([int]$Matches[1]+[int]$Matches[3])/2);$ty=[int](([int]$Matches[2]+[int]$Matches[4])/2)
                    & $adb -s $Serial shell input tap $tx $ty | Out-Null
                    Write-Host 'Declined optional APK upload for Skymning; device protection settings unchanged.'
                } elseif($ui.SelectSingleNode('//node[@text="Vill du skicka in appen för säkerhetskontroll?"]')) {
                    $scroll=$ui.SelectSingleNode('//node[@scrollable="true"]')
                    if($scroll -and $scroll.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                        $sx=[int](([int]$Matches[1]+[int]$Matches[3])/2);$sy=[int]$Matches[2];$eh=[int]$Matches[4]-$sy
                        & $adb -s $Serial shell input swipe $sx ([int]($sy+$eh*.8)) $sx ([int]($sy+$eh*.3)) 400 | Out-Null
                    }
                }
            }
        } catch [System.Xml.XmlException] { }
    }
    $process.WaitForExit()
    if($process.ExitCode -ne 0 -or (Get-Content -LiteralPath $stdout -Raw) -notmatch 'Success'){throw "APK install failed. See $stdout and $stderr"}
    Write-Host 'APK installed successfully.'
} finally {
    if(-not $process.HasExited){$process.Kill()}
    & $adb -s $Serial shell rm -f /sdcard/skymning-install-ui.xml 2>$null | Out-Null
    $process.Dispose()
}
