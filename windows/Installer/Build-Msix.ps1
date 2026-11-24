param([Parameter(Mandatory)][string]$CertificateThumbprint, [switch]$SelfContained)
$ErrorActionPreference = 'Stop'
$cert = Get-Item -LiteralPath "Cert:/CurrentUser/My/$CertificateThumbprint"
$windows = (Resolve-Path "$PSScriptRoot/..").Path
$repo = Split-Path $windows
$sdk = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/bin/10.0.26100.0/x64'
$manifest = [xml](Get-Content -Raw "$PSScriptRoot/AppxManifest.xml")
$manifest.Package.Identity.Publisher = $cert.Subject
$version = $manifest.Package.Identity.Version
$includeRuntime = $SelfContained.IsPresent.ToString().ToLowerInvariant()
$variant = if ($SelfContained) { 'SelfContained' } else { 'Dev' }
if ($SelfContained) {
  $null = $manifest.Package.Dependencies.RemoveChild($manifest.Package.Dependencies.PackageDependency)
}
$output = New-Item -ItemType Directory -Force "$repo/artifacts/direct/$version"
$stage = New-Item -ItemType Directory "$repo/artifacts/msix-$([Guid]::NewGuid().ToString('N'))"
$package = Join-Path $stage 'PenRelay.msix'
$payload = Join-Path $stage 'payload'
Push-Location $windows
try {
  # 설치된 MSIX는 매니페스트의 정적 의존성을 사용한다. 일반 EXE 개발 빌드는 SDK의 부트스트래퍼를 사용한다.
  dotnet publish ./PenRelay.Desktop/PenRelay.Desktop.csproj -c Release -r win-x64 --self-contained $includeRuntime -p:Platform=x64 "-p:WindowsAppSDKSelfContained=$includeRuntime" -p:WindowsAppSdkBootstrapInitialize=false "-p:Version=$version" -p:DebugType=None -p:DebugSymbols=false -o $payload
  if ($LASTEXITCODE) { throw 'Windows 앱 게시 실패' }
  $manifest.Save((Join-Path $payload 'AppxManifest.xml'))
  # Windows가 먼저 여는 resources.pri에 WinUI 테마와 앱 이름을 함께 넣는다.
  $priInput = New-Item -ItemType Directory (Join-Path $stage 'pri-input')
  Copy-Item -LiteralPath "$payload/PenRelay.Desktop.pri" -Destination $priInput
  Copy-Item -LiteralPath "$PSScriptRoot/Strings" -Destination $priInput -Recurse
  $priConfig = Join-Path $stage 'priconfig.xml'
  & "$sdk/makepri.exe" createconfig /cf $priConfig /dq en-US /o
  if ($LASTEXITCODE) { throw '패키지 언어 리소스 구성 실패' }
  # 단일 MSIX 직접 배포 시 두 언어를 기본 리소스 인덱스에 함께 포함한다.
  $config = [xml](Get-Content -Raw -LiteralPath $priConfig)
  $null = $config.resources.RemoveChild($config.resources.packaging)
  $config.Save($priConfig)
  & "$sdk/makepri.exe" new /pr $priInput.FullName /cf $priConfig /in $manifest.Package.Identity.Name /of "$payload/resources.pri" /o
  if ($LASTEXITCODE) { throw '패키지 통합 리소스 생성 실패' }
  $assets = New-Item -ItemType Directory -Force (Join-Path $payload 'Assets')
  Add-Type -AssemblyName System.Drawing
  $icon = [Drawing.Image]::FromFile((Join-Path $payload 'Assets/PenRelay.png'))
  try {
    foreach ($logo in @{ Store = 50; Tile = 150; Icon = 44 }.GetEnumerator()) {
      $size = $logo.Value
      $bitmap = [Drawing.Bitmap]::new($size, $size)
      $g = [Drawing.Graphics]::FromImage($bitmap)
      try {
        $g.InterpolationMode = 'HighQualityBicubic'
        $g.PixelOffsetMode = 'HighQuality'
        $g.DrawImage($icon, 0, 0, $size, $size)
        $bitmap.Save((Join-Path $assets ($logo.Key + '.png')), [Drawing.Imaging.ImageFormat]::Png)
      } finally { $g.Dispose(); $bitmap.Dispose() }
    }
  } finally { $icon.Dispose() }
  & "$sdk/makeappx.exe" pack /o /d $payload /p $package | Select-Object -Last 3
  if ($LASTEXITCODE) { throw 'MSIX 패키징 실패' }
  & "$sdk/signtool.exe" sign /fd SHA256 /sha1 $CertificateThumbprint $package
  if ($LASTEXITCODE) { throw 'MSIX 서명 실패' }
  Copy-Item -LiteralPath $package -Destination "$output/PenRelay-$version-x64-$variant.msix" -PassThru
} finally { Pop-Location }
