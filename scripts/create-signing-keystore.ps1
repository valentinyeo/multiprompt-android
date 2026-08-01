[CmdletBinding()]
param(
    [switch]$ConfigureGitHub
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $projectRoot 'signing'
$keystorePath = Join-Path $signingDirectory 'multiprompt-companion.jks'
$propertiesPath = Join-Path $projectRoot 'keystore.properties'

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    throw 'keytool was not found. Install JDK 17 or open the project once in Android Studio.'
}
$keyAlias = 'multiprompt-companion'
if (Test-Path -LiteralPath $keystorePath) {
    if (-not $ConfigureGitHub) {
        throw "Signing keystore already exists at $keystorePath. It was not overwritten."
    }
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        throw "The existing keystore has no matching $propertiesPath. Refusing to guess its password."
    }
    $properties = @{}
    Get-Content -LiteralPath $propertiesPath | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $properties[$matches[1].Trim()] = $matches[2] }
    }
    $password = $properties.storePassword
    $keyAlias = $properties.keyAlias
    if (-not $password -or -not $keyAlias) {
        throw 'The existing signing properties are incomplete.'
    }
    Write-Host "Using existing signing keystore at $keystorePath"
} else {
    New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
    $random = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $password = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')

    & $keytool.Source `
        -genkeypair `
        -keystore $keystorePath `
        -storepass $password `
        -alias $keyAlias `
        -keypass $password `
        -keyalg EC `
        -groupname secp256r1 `
        -validity 10000 `
        -dname 'CN=multiprompt companion, O=multiprompt, C=DE'

    @"
storeFile=signing/multiprompt-companion.jks
storePassword=$password
keyAlias=$keyAlias
keyPassword=$password
"@ | Set-Content -LiteralPath $propertiesPath -Encoding utf8NoBOM

    Write-Host "Created $keystorePath"
    Write-Host "Created ignored local signing config at $propertiesPath"
    Write-Warning 'Back up the keystore and password. Losing them permanently breaks in-app updates.'
}

if ($ConfigureGitHub) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw 'GitHub CLI (gh) is required for -ConfigureGitHub.'
    }
    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
    $base64 | gh secret set ANDROID_KEYSTORE_BASE64 -R valentinyeo/multiprompt-android
    $password | gh secret set ANDROID_KEYSTORE_PASSWORD -R valentinyeo/multiprompt-android
    $keyAlias | gh secret set ANDROID_KEY_ALIAS -R valentinyeo/multiprompt-android
    $password | gh secret set ANDROID_KEY_PASSWORD -R valentinyeo/multiprompt-android
    Write-Host 'Configured the four Android signing secrets in valentinyeo/multiprompt-android.'
}
