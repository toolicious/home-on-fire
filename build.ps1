#requires -Version 5.1
<#
.SYNOPSIS
  Build, sign and (by default) install Home on Fire.

.DESCRIPTION
  Runs the manual aapt2 + javac + d8 pipeline used by this repo.
  Bumps versionCode and the patch part of versionName in
  AndroidManifest.xml before building (override with -NoBump).
  Installs the resulting APK on the first connected ADB device unless
  -NoInstall is passed.

.PARAMETER NoBump
  Skip the automatic version increment.

.PARAMETER NoInstall
  Skip the adb install step at the end.

.PARAMETER CleanReinstall
  Run `adb uninstall` before installing. Forces a clean SharedPreferences
  state on the device (useful when changing defaults).

.EXAMPLE
  ./build.ps1
  Normal build + auto-bump + install.

.EXAMPLE
  ./build.ps1 -NoBump
  Rebuild current version without bumping (e.g. when iterating on a
  resource that doesn't warrant a version number).

.EXAMPLE
  ./build.ps1 -CleanReinstall
  Bump + build + adb uninstall + adb install.
#>

[CmdletBinding()]
param(
    [switch] $NoBump,
    [switch] $NoInstall,
    [switch] $CleanReinstall
)

$ErrorActionPreference = 'Stop'

# --- Toolchain paths -----------------------------------------------------
$SdkRoot      = "$env:LOCALAPPDATA\Android\Sdk"
$BuildTools   = "$SdkRoot\build-tools\36.1.0"
$Aapt2        = "$BuildTools\aapt2.exe"
$D8           = "$BuildTools\d8.bat"
$ZipAlign     = "$BuildTools\zipalign.exe"
$ApkSigner    = "$BuildTools\apksigner.bat"
$AndroidJar   = "$SdkRoot\platforms\android-34\android.jar"
$Adb          = "$SdkRoot\platform-tools\adb.exe"

# Locate a Java toolchain (jar + javac).
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome) {
    $found = Get-ChildItem -Directory "C:\Program Files\Eclipse Adoptium" -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -like 'jdk-*' } | Select-Object -First 1
    if (-not $found) {
        throw "Could not find a JDK. Set JAVA_HOME or install Eclipse Adoptium under C:\Program Files\Eclipse Adoptium."
    }
    $JavaHome = $found.FullName
}
$Javac = "$JavaHome\bin\javac.exe"
$Jar   = "$JavaHome\bin\jar.exe"

# --- Project layout ------------------------------------------------------
$ProjectRoot  = $PSScriptRoot
$BuildDir     = Join-Path $ProjectRoot 'build'
$KeystoreDir  = Join-Path $ProjectRoot 'keystore'
$Keystore     = Join-Path $KeystoreDir 'home-on-fire.keystore'
$Manifest     = Join-Path $ProjectRoot 'AndroidManifest.xml'
$OutApk       = Join-Path $ProjectRoot 'home-on-fire.apk'
$Package      = 'io.github.toolicious.homeonfire'

if (-not (Test-Path $Keystore)) {
    throw "Keystore not found at $Keystore. Generate one with `keytool -genkey` or restore yours."
}

# --- Signing credentials --------------------------------------------------
# The store password lives in a gitignored properties file next to the
# keystore, never in this (public) script. PKCS12 keystores use a single
# password protecting both the store and the key inside it.
$PropsFile = Join-Path $KeystoreDir 'keystore.properties'
if (-not (Test-Path $PropsFile)) {
    throw "Missing $PropsFile. Create it with a single line:  storePass=<keystore password>"
}
$KeystorePass = $null
foreach ($line in Get-Content $PropsFile) {
    if ($line -match '^\s*storePass\s*=\s*(.+?)\s*$') { $KeystorePass = $Matches[1] }
}
if (-not $KeystorePass) {
    throw "No storePass entry in $PropsFile. Expected a line:  storePass=<keystore password>"
}

# --- Version bump --------------------------------------------------------
if (-not $NoBump) {
    $manifestText = Get-Content $Manifest -Raw -Encoding UTF8
    $oldCode = [regex]::Match($manifestText, 'android:versionCode="(\d+)"').Groups[1].Value
    $oldName = [regex]::Match($manifestText, 'android:versionName="([^"]+)"').Groups[1].Value

    $newText = [regex]::Replace($manifestText, 'android:versionCode="(\d+)"', {
        param($m) 'android:versionCode="' + ([int]$m.Groups[1].Value + 1) + '"'
    })
    $newText = [regex]::Replace($newText, 'android:versionName="(\d+)\.(\d+)\.(\d+)"', {
        param($m) 'android:versionName="' + $m.Groups[1].Value + '.' + $m.Groups[2].Value + '.' + ([int]$m.Groups[3].Value + 1) + '"'
    })

    $newCode = [regex]::Match($newText, 'android:versionCode="(\d+)"').Groups[1].Value
    $newName = [regex]::Match($newText, 'android:versionName="([^"]+)"').Groups[1].Value
    # Validate each field independently. The combined-text guard used to
    # miss a versionName-only no-op because the versionCode bump always
    # changes the text; checking them separately fails loudly before any
    # APK is produced rather than shipping desynced version fields.
    if ($newCode -eq $oldCode) {
        throw "Could not bump versionCode in AndroidManifest.xml."
    }
    if ($newName -eq $oldName) {
        throw "Could not bump versionName '$oldName' in AndroidManifest.xml (expected a three-part X.Y.Z value)."
    }
    Set-Content -Path $Manifest -Value $newText -Encoding UTF8 -NoNewline
    Write-Host "Bumped to versionCode=$newCode, versionName=$newName"
}

# --- Build dirs ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path "$BuildDir\gen","$BuildDir\classes","$BuildDir\dex" | Out-Null

# --- aapt2 compile + link -----------------------------------------------
Write-Host "aapt2 compile..."
& $Aapt2 compile --dir (Join-Path $ProjectRoot 'res') -o "$BuildDir\res-compiled.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed." }

Write-Host "aapt2 link..."
& $Aapt2 link `
    -o "$BuildDir\unsigned.apk" `
    -I $AndroidJar `
    --manifest $Manifest `
    -R "$BuildDir\res-compiled.zip" `
    --java "$BuildDir\gen" `
    --auto-add-overlay `
    --min-sdk-version 21 `
    --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed." }

# --- javac ---------------------------------------------------------------
Write-Host "javac..."
$sources = @()
$sources += Get-ChildItem -Recurse -Filter *.java (Join-Path $ProjectRoot 'src') | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -Recurse -Filter *.java "$BuildDir\gen" | ForEach-Object { $_.FullName }
$sourcesFile = Join-Path $BuildDir 'sources.txt'
$sources | Set-Content -Path $sourcesFile -Encoding ASCII

# Wipe old classes so renamed/deleted sources don't linger.
Get-ChildItem -Recurse -Filter *.class "$BuildDir\classes" -ErrorAction SilentlyContinue | Remove-Item -Force

& $Javac -encoding UTF-8 -source 8 -target 8 `
    -classpath $AndroidJar `
    -d "$BuildDir\classes" `
    "@$sourcesFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed." }

# --- d8 ------------------------------------------------------------------
Write-Host "d8..."
$classFiles = Get-ChildItem -Recurse -Filter *.class "$BuildDir\classes" | ForEach-Object { $_.FullName }
& $D8 --lib $AndroidJar --output "$BuildDir\dex" @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed." }

# --- Pack dex into apk --------------------------------------------------
Write-Host "Packaging dex..."
Copy-Item -Force "$BuildDir\unsigned.apk" "$BuildDir\unsigned-with-dex.apk"
Push-Location "$BuildDir\dex"
try {
    & $Jar uf "$BuildDir\unsigned-with-dex.apk" classes.dex
    if ($LASTEXITCODE -ne 0) { throw "jar uf failed." }
} finally {
    Pop-Location
}

# --- zipalign + sign -----------------------------------------------------
Write-Host "zipalign..."
& $ZipAlign -f -p 4 "$BuildDir\unsigned-with-dex.apk" "$BuildDir\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed." }

Write-Host "apksigner sign..."
& $ApkSigner sign `
    --ks $Keystore `
    --ks-pass "pass:$KeystorePass" `
    --key-pass "pass:$KeystorePass" `
    --out $OutApk `
    "$BuildDir\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed." }

Write-Host "APK built: $OutApk"

# --- Install -------------------------------------------------------------
if ($NoInstall) {
    Write-Host "Skipping install (-NoInstall)."
    return
}

$devices = & $Adb devices | Select-String -Pattern "`tdevice$"
if (-not $devices) {
    Write-Host "No ADB device connected; skipping install."
    return
}

if ($CleanReinstall) {
    Write-Host "adb uninstall $Package..."
    & $Adb uninstall $Package | Out-Null
}

Write-Host "adb install..."
& $Adb install -r $OutApk
if ($LASTEXITCODE -ne 0) { throw "adb install failed." }

Write-Host "Done."
& $Adb shell dumpsys package $Package | Select-String -Pattern 'versionName|versionCode' | Select-Object -First 2
