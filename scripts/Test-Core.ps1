[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $projectRoot 'test-output/core'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$logFile = Join-Path $outputRoot 'results.txt'
"Core regression $(Get-Date -Format o)" | Set-Content -Encoding utf8 $logFile
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core/src'),(Join-Path $projectRoot 'tests') -Filter '*.java' -Recurse | ForEach-Object FullName)
if ($sourceFiles.Count -eq 0) { throw 'No core/test Java source files found.' }
& $javac -encoding UTF-8 -d $outputRoot @sourceFiles 2>&1 | Tee-Object -FilePath $logFile -Append
if ($LASTEXITCODE -ne 0) { throw "Core compilation failed ($LASTEXITCODE). See $logFile" }
foreach ($testClass in @('se.nils.skymning.GameTest', 'se.nils.skymning.LanTest')) {
    "Running $testClass" | Tee-Object -FilePath $logFile -Append
    & $java -ea -cp $outputRoot $testClass 2>&1 | Tee-Object -FilePath $logFile -Append
    if ($LASTEXITCODE -ne 0) { throw "$testClass failed ($LASTEXITCODE). See $logFile" }
}
'PASS: core and loopback LAN regression tests.' | Tee-Object -FilePath $logFile -Append
