$ErrorActionPreference = 'Stop'

# Native Windows counterpart to run-tests.sh. Requires JDK 17+ and Node 18+ on PATH.
# Capturing native stderr avoids Windows PowerShell 5.1 preference handling masking process exit codes.
$Root = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $Root 'build/tests'
$Classes = Join-Path $Out 'classes'
$ActivityOut = Join-Path $Out 'activity'
$ContractOut = Join-Path $Out 'contract'

if (Test-Path $Out) {
    Remove-Item -Path $Out -Recurse -Force
}
New-Item -ItemType Directory -Path $Out -Force | Out-Null
New-Item -ItemType Directory -Path $Classes -Force | Out-Null

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    # In Windows PowerShell 5.1, native stderr can interact with $ErrorActionPreference.
    # Merge native streams for display, then trust the process exit code for pass/fail.
    $NativeOutput = & $FilePath @Arguments 2>&1
    $NativeExitCode = $LASTEXITCODE
    if ($null -ne $NativeOutput) {
        $NativeOutput | ForEach-Object { Write-Host $_ }
    }
    if ($NativeExitCode -ne 0) {
        throw "$FilePath failed with exit code $NativeExitCode"
    }
}

Write-Host '== 1. packager self-test'
$PackagerSources = @(
    (Join-Path $Root 'buildSrc/src/main/java/com/nocturne/build/DictionaryPackager.java'),
    (Join-Path $Root 'tests/dictionary/PackagerSelfTest.java'),
    (Join-Path $Root 'tests/dictionary/EmitFixture.java')
)
$CompileArgs = @('-Xlint:all', '-d', $Classes) + $PackagerSources
Invoke-CheckedCommand -FilePath 'javac' -Arguments $CompileArgs
Invoke-CheckedCommand -FilePath 'java' -Arguments @('-ea', '-cp', $Classes, 'com.nocturne.build.PackagerSelfTest')

Write-Host '== 2. cross-language contract'
New-Item -ItemType Directory -Path $ContractOut -Force | Out-Null
Invoke-CheckedCommand -FilePath 'java' -Arguments @('-cp', $Classes, 'com.nocturne.build.EmitFixture', $ContractOut)
Invoke-CheckedCommand -FilePath 'node' -Arguments @((Join-Path $Root 'tests/dictionary/contract-check.cjs'), (Join-Path $ContractOut 'emit/out/dictionary'))

Write-Host '== 3. MainActivity compiles against the Android/AndroidX API stubs (signatures only, not behaviour)'
New-Item -ItemType Directory -Path $ActivityOut -Force | Out-Null
$StubSources = @(Get-ChildItem -Path (Join-Path $Root 'tests/android-stubs') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$ActivitySources = @('-Xlint:all', '-d', $ActivityOut) + $StubSources + @((Join-Path $Root 'app/src/main/java/com/nocturne/app/MainActivity.java'))
Invoke-CheckedCommand -FilePath 'javac' -Arguments $ActivitySources
Write-Host 'ok - MainActivity compiles with no warnings'

Write-Host '== 4. app smoke test'
Invoke-CheckedCommand -FilePath 'node' -Arguments @((Join-Path $Root 'tests/app-smoke.test.cjs'))
Write-Host 'ALL OFFLINE TESTS PASSED'
