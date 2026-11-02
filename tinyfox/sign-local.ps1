param(
    [Parameter(Mandatory=$true)][string]$UnsignedApk,
    [Parameter(Mandatory=$true)][string]$OutputApk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$BuildTools,
    [switch]$Initialize
)
$ErrorActionPreference = 'Stop'
$signingDir = Join-Path $env:LOCALAPPDATA 'TinyFox\signing'
$keyFile = Join-Path $signingDir 'release.p12'
$passwordFile = Join-Path $signingDir 'release.password.dpapi'
$publicCert = Join-Path $signingDir 'release.cer'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$javaExe = Join-Path $JavaHome 'bin\java.exe'
$apksigner = Join-Path $BuildTools 'lib\apksigner.jar'
if (!(Test-Path -LiteralPath $UnsignedApk)) { throw 'Unsigned APK is missing.' }
if ((Test-Path -LiteralPath $keyFile) -ne (Test-Path -LiteralPath $passwordFile)) {
    throw 'Incomplete signing material: recover the existing key instead of creating a replacement.'
}
try {
    if (!(Test-Path -LiteralPath $keyFile)) {
        if (!$Initialize) { throw 'No signing key. Use -Initialize once to create a permanent TinyFox key.' }
        New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
        $bytes = New-Object byte[] 48
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes)
        $rng.Dispose()
        $env:TINYFOX_LOCAL_PASSWORD = [Convert]::ToBase64String($bytes)
        $secure = ConvertTo-SecureString $env:TINYFOX_LOCAL_PASSWORD -AsPlainText -Force
        ConvertFrom-SecureString $secure | Set-Content -LiteralPath $passwordFile
        & $keytool -genkeypair -keystore $keyFile -storetype PKCS12 -alias tinyfox-release `
            -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=TinyFox Personal' `
            -storepass:env TINYFOX_LOCAL_PASSWORD -keypass:env TINYFOX_LOCAL_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed. Existing material was retained for diagnosis.' }
    } else {
        $secure = Get-Content -Raw -LiteralPath $passwordFile | ConvertTo-SecureString
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $env:TINYFOX_LOCAL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    }
    & $keytool -exportcert -keystore $keyFile -alias tinyfox-release -file $publicCert -storepass:env TINYFOX_LOCAL_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the existing signing certificate.' }
    $fingerprint = (Get-FileHash -Algorithm SHA256 -LiteralPath $publicCert).Hash.ToLowerInvariant()
    $pinFile = Join-Path $PSScriptRoot 'signing-certificate.sha256'
    if ((Test-Path -LiteralPath $pinFile) -and (Get-Content -Raw -LiteralPath $pinFile).Trim() -ne $fingerprint) {
        throw 'Signing key does not match the pinned TinyFox certificate.'
    }
    $fingerprint | Set-Content -LiteralPath $pinFile
    & $javaExe -jar $apksigner sign --ks $keyFile --ks-key-alias tinyfox-release `
        --ks-pass env:TINYFOX_LOCAL_PASSWORD --key-pass env:TINYFOX_LOCAL_PASSWORD `
        --out $OutputApk $UnsignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $javaExe -jar $apksigner verify --verbose --print-certs $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
    Write-Output "Signed TinyFox APK: $OutputApk"
    Write-Output "Permanent key location: $keyFile"
    Write-Output 'Password is protected with Windows DPAPI for the current Windows account.'
} finally {
    Remove-Item Env:TINYFOX_LOCAL_PASSWORD -ErrorAction SilentlyContinue
}
