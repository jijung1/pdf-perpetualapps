# Runs CorpusTest against testdata/corpus/*.pdf on a device or emulator and prints a results table.
#   powershell -ExecutionPolicy Bypass -File testdata\run-corpus.ps1 [-Serial emulator-5554]
param([string]$Serial = "emulator-5554", [string]$Test = "app.folio.core.tools.CorpusTest#corpus")
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$adb = "D:\Android\platform-tools\adb.exe"
function A { & $adb -s $Serial @args }
$pkg = "app.folio.core.tools.test"

Push-Location $root
$env:ANDROID_SERIAL = $Serial
.\gradlew.bat :core:tools:installDebugAndroidTest --console=plain -q 2>&1 | Select-String 'e:|FAILED'
Pop-Location

A shell run-as $pkg rm -rf files/corpus
A shell run-as $pkg mkdir -p files/corpus
foreach ($f in Get-ChildItem (Join-Path $PSScriptRoot 'corpus') -Filter *.pdf) {
    A push $f.FullName "/data/local/tmp/corpus.pdf" | Out-Null
    A shell "cat /data/local/tmp/corpus.pdf | run-as $pkg sh -c 'cat > files/corpus/$($f.Name)'"
}
A shell rm -f /data/local/tmp/corpus.pdf

A logcat -c
A shell am instrument -w -e class $Test "$pkg/androidx.test.runner.AndroidJUnitRunner" | Select-String 'OK|FAIL|Error|Process crashed'
$lines = A logcat -d -v tag -s Corpus:I | ? { $_ -match '^I/Corpus' } | % { $_ -replace '^I/Corpus\s*:\s*', '' }
$out = Join-Path $PSScriptRoot 'corpus-results.txt'
$lines | Set-Content -Path $out -Encoding utf8
$lines
A shell run-as $pkg rm -rf files/corpus
Write-Output "Saved $out"
