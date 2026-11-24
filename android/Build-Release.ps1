param([Parameter(Mandatory)][string]$KeyStore, [string]$Alias = 'penrelay',
    [string]$BuildTools)
$ErrorActionPreference = 'Stop'
if (!$BuildTools) {
    if (!$env:ANDROID_HOME) { throw '공용 Android SDK 경로를 ANDROID_HOME에 지정하세요.' }
    $BuildTools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
}
$signer = (Resolve-Path -LiteralPath (Join-Path $BuildTools 'apksigner.bat')).Path
if (!$env:PENRELAY_KEYSTORE_PASSWORD) { throw 'PENRELAY_KEYSTORE_PASSWORD 환경 변수에 릴리스 키 암호를 지정하세요.' }
$key = (Resolve-Path $KeyStore).Path
$out = New-Item -ItemType Directory -Force "$PSScriptRoot/../artifacts/direct/0.11.12.0"
$apk = "$out/PenRelay-0.11.12.apk"
Push-Location $PSScriptRoot
try {
    .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE) { throw 'Android 릴리스 빌드 실패' }
    & $signer sign --ks $key --ks-key-alias $Alias --ks-pass env:PENRELAY_KEYSTORE_PASSWORD --v4-signing-enabled false --out $apk app/build/outputs/apk/release/app-release-unsigned.apk
    if ($LASTEXITCODE) { throw 'APK 서명 실패' }
    & $signer verify --verbose $apk
    if ($LASTEXITCODE) { throw 'APK 서명 검증 실패' }
    Get-Item $apk
} finally { Pop-Location }
