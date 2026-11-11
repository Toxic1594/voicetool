# Builds the Android app without Gradle/Android Studio (Android SDK + JDK only).
# Usage:   powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1
# Output:  dist\EasyTTS-<version>.apk

param(
    [string]$Version = "0.4.0",
    [int]$VersionCode = 4,
    [string]$MinSdk = "26",
    [string]$TargetSdk = "36"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$appDir = Join-Path $root "android\app\src\main"
$buildDir = Join-Path $root "android\build\offline"
$distDir = Join-Path $root "dist"
$keystoreDir = Join-Path $root "android\keystore"

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "Android SDK not found. Set ANDROID_HOME." }

$buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "No build-tools found in the SDK." }

$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$d8 = Join-Path $buildTools.FullName "d8.bat"
$zipalign = Join-Path $buildTools.FullName "zipalign.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"

$platforms = Get-ChildItem (Join-Path $sdk "platforms") -Directory |
    Sort-Object { [version](($_.Name -replace '^android-', '') + '.0.0') } -Descending
$androidJar = $null
$preferred = Join-Path $sdk "platforms\android-$TargetSdk\android.jar"
if (Test-Path $preferred) { $androidJar = $preferred }
foreach ($p in $platforms) {
    if ($androidJar) { break }
    $candidate = Join-Path $p.FullName "android.jar"
    if (Test-Path $candidate) { $androidJar = $candidate; break }
}
if (-not $androidJar) { throw "android.jar not found." }

$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\javac.exe"))) {
    $javaHome = ((& java -XshowSettings:properties -version 2>&1 |
        Select-String -Pattern '^\s*java\.home =').ToString() -split '=', 2)[1].Trim()
}
if (-not (Test-Path (Join-Path $javaHome "bin\javac.exe"))) { throw "JDK not found (javac)." }
$keytool = Join-Path $javaHome "bin\keytool.exe"
$jarTool = Join-Path $javaHome "bin\jar.exe"
$javac = Join-Path $javaHome "bin\javac.exe"
if (-not (Test-Path $jarTool)) { $jarTool = "jar" }

Write-Host "SDK      : $sdk"
Write-Host "BuildTools: $($buildTools.Name)"
Write-Host "Platform : $androidJar"
Write-Host "Java     : $javaHome"

if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $buildDir, "$buildDir\gen", "$buildDir\classes", "$buildDir\dex", $distDir | Out-Null

# 1) Compile resources
Write-Host "==> aapt2 compile"
& $aapt2 compile --dir (Join-Path $appDir "res") -o (Join-Path $buildDir "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# 2) Link resources and generate R.java
Write-Host "==> aapt2 link"
# aapt2 requires a package attribute; AGP forbids it. Use a temporary copy.
$manifestSource = Join-Path $appDir "AndroidManifest.xml"
$manifestTemp = Join-Path $buildDir "AndroidManifest.xml"
(Get-Content $manifestSource -Raw) -replace '<manifest\s', '<manifest package="de.voicetool.reader" ' |
    Set-Content -Path $manifestTemp -Encoding UTF8
& $aapt2 link `
    -o (Join-Path $buildDir "app-unsigned.apk") `
    -I $androidJar `
    --manifest $manifestTemp `
    --java (Join-Path $buildDir "gen") `
    (Join-Path $buildDir "res.zip") `
    --min-sdk-version $MinSdk `
    --target-sdk-version $TargetSdk `
    --version-code $VersionCode `
    --version-name $Version
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 3) Compile Java
Write-Host "==> javac"
$sources = @(Get-ChildItem (Join-Path $appDir "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem (Join-Path $buildDir "gen") -Recurse -Filter R.java | ForEach-Object { $_.FullName })
$argFile = Join-Path $buildDir "sources.txt"
$sources | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' } | Set-Content -Path $argFile -Encoding ASCII
& javac --release 17 -encoding UTF-8 -classpath $androidJar -d (Join-Path $buildDir "classes") "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 4) Create DEX
Write-Host "==> d8"
$classesJar = Join-Path $buildDir "classes.jar"
& $jarTool cf $classesJar -C (Join-Path $buildDir "classes") .
& $d8 --lib $androidJar --min-api $MinSdk --output (Join-Path $buildDir "dex") $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# 5) Add classes.dex to the APK
Write-Host "==> Pack APK"
& $jarTool uf (Join-Path $buildDir "app-unsigned.apk") -C (Join-Path $buildDir "dex") classes.dex
if ($LASTEXITCODE -ne 0) { throw "Packaging the APK failed" }

# 6) Signing key (one-time)
$keystore = Join-Path $keystoreDir "voicetool.keystore"
$keystoreProps = Join-Path $keystoreDir "keystore.properties"
New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null
if (-not (Test-Path $keystore)) {
    Write-Host "==> Generate signing key (one-time)"
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes)
    & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias voicetool `
        -keyalg RSA -keysize 3072 -validity 10000 `
        -storepass $password -keypass $password `
        -dname "CN=EasyTTS, O=EasyTTS, C=DE"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    Set-Content -Path $keystoreProps -Value "storePassword=$password`nkeyAlias=voicetool" -Encoding ASCII
    Write-Host "Password stored in android\keystore\keystore.properties (gitignored)"
}
$password = (Get-Content $keystoreProps | Where-Object { $_ -like "storePassword=*" }) -replace "storePassword=", ""

# 7) Align and sign
Write-Host "==> zipalign + apksigner"
$aligned = Join-Path $buildDir "app-aligned.apk"
& $zipalign -f 4 (Join-Path $buildDir "app-unsigned.apk") $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$apk = Join-Path $distDir "EasyTTS-$Version.apk"
& $apksigner sign --ks $keystore --ks-pass "pass:$password" --ks-key-alias voicetool --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $apksigner verify --print-certs $apk | Select-Object -First 4

Write-Host ""
Write-Host "Done: $apk"
Write-Host "Install: adb install -r `"$apk`""

