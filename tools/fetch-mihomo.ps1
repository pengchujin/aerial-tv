# Fetches the optional proxy core (see tools/fetch-mihomo.sh, the same thing for macOS / Linux, and
# app/src/main/assets/proxy/NOTICE.txt): the official mihomo release asset, both pinned SHA-256
# values checked, installed as the git-ignored app/src/main/jniLibs/armeabi-v7a/libmihomo.so.
# -Proxy is optional (for example http://127.0.0.1:7890); without it curl uses the environment.
param(
    [string]$Proxy = ''
)
$ErrorActionPreference = 'Stop'
$version = '1.19.33'
# The official linux-armv7 build (static, with_gvisor), not android-armv7: the Android build's TUN
# reads /data/system/packages.xml (sing-tun packages_android.go), which an ordinary app cannot
# read, so its tun listener fails. The linux build takes the VpnService fd without reading it.
$assetHash = 'c8a8f9bcaab914269fbdca7569e4a9f024cc667ecf12eb43e8ccdc01b3731604'
$binaryHash = 'e9eaf8ad3467ca980af75abab56969ed7167b4d7a0dd63cd4a112004e4a39fcc'
$root = Split-Path $PSScriptRoot -Parent
$temp = Join-Path $root 'local/proxy-build'
$output = Join-Path $root 'app/src/main/jniLibs/armeabi-v7a/libmihomo.so'
New-Item -ItemType Directory -Force $temp,(Split-Path $output) | Out-Null
$gz = Join-Path $temp "mihomo-linux-armv7-v$version.gz"
$url = "https://github.com/MetaCubeX/mihomo/releases/download/v$version/mihomo-linux-armv7-v$version.gz"
try {
    $curlArgs = @('--fail', '--location', '--retry', '2', '--max-time', '180', '--output', $gz)
    if ($Proxy) { $curlArgs += @('--proxy', $Proxy) }
    & curl.exe @curlArgs $url
    if ($LASTEXITCODE -ne 0) { throw 'Official core download failed' }
    if ((Get-FileHash $gz -Algorithm SHA256).Hash.ToLowerInvariant() -ne $assetHash) { throw 'Compressed core checksum mismatch' }
    $source = [IO.File]::OpenRead($gz)
    try {
        $zip = [IO.Compression.GzipStream]::new($source,[IO.Compression.CompressionMode]::Decompress)
        try {
            $target = [IO.File]::Create($output)
            try { $zip.CopyTo($target) } finally { $target.Dispose() }
        } finally { $zip.Dispose() }
    } finally { $source.Dispose() }
    if ((Get-FileHash $output -Algorithm SHA256).Hash.ToLowerInvariant() -ne $binaryHash) {
        Remove-Item $output -Force
        throw 'Extracted core checksum mismatch'
    }
    Write-Output "Verified official linux-armv7 mihomo $version`: $output"
} finally { Remove-Item $gz -Force -ErrorAction SilentlyContinue }
