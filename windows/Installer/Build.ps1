param([version]$Version = '0.5.2', [string]$Wix)
$ErrorActionPreference = 'Stop'
$windows = (Resolve-Path "$PSScriptRoot/..").Path
$repo = Split-Path $windows
if (!$Wix) { $Wix = Join-Path $repo '.local/wix/wix.exe' }
if (!(Test-Path -LiteralPath $Wix)) { throw 'WiX 5 필요 · dotnet tool install wix --version 5.0.2 --tool-path .local/wix' }
$out = Join-Path $repo "artifacts/P5-distribution/$Version"
# 매번 빈 게시 폴더를 사용해 이전 버전의 파일이 MSI에 섞이지 않도록 한다.
$payload = Join-Path $out ([Guid]::NewGuid().ToString('N'))
Push-Location $windows
try {
  dotnet publish ./PenRelay.Desktop/PenRelay.Desktop.csproj -c Release -r win-x64 --self-contained true -p:Platform=x64 -p:WindowsAppSDKSelfContained=true "-p:Version=$Version" -p:DebugType=None -p:DebugSymbols=false -o $payload
  if ($LASTEXITCODE) { throw "Windows 게시 실패 ($LASTEXITCODE)" }
  foreach ($required in 'PenRelay.Desktop.exe','PenRelay.Desktop.pri','HostWindow.xbf','CanvasViewer.xbf') {
    if (!(Test-Path (Join-Path $payload $required))) { throw "게시 리소스 누락 · $required" }
  }
  $msi = Join-Path $out "PenRelay-$Version-x64.msi"
  & $Wix build "$PSScriptRoot/Product.wxs" -arch x64 -d "ProductVersion=$Version" -d "Payload=$payload" -out $msi
  if ($LASTEXITCODE) { throw "MSI 생성 실패 ($LASTEXITCODE)" }
  Get-Item -LiteralPath $msi
} finally { Pop-Location }
