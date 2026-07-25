# AIApp build script (ASCII-safe)
# Compatible with Android 2.3 (API 9+)

$ErrorActionPreference = "Stop"

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$SDK  = "C:\AndroidSDK"
$BT   = "$SDK\build-tools\30.0.3"
$PLAT = "$SDK\platforms\android-33\android.jar"
$KS   = "$ROOT\aiapp.keystore"

Set-Location $ROOT

Write-Host "==> clean"
Remove-Item -Recurse -Force gen, obj, bin -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path gen, obj, bin | Out-Null

Write-Host "==> 1) aapt R.java"
& "$BT\aapt.exe" package -f -M AndroidManifest.xml -S res -I $PLAT -J gen -m
if ($LASTEXITCODE -ne 0) { throw "aapt R.java failed" }

Write-Host "==> 2) javac"
$srcs = Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$rgen = Get-ChildItem -Path gen -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$all  = @($srcs) + @($rgen)
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javacOut = & javac -source 1.7 -target 1.7 -encoding UTF-8 -bootclasspath $PLAT -d obj -classpath $PLAT $all 2>&1 | Out-String
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($javacExit -ne 0) {
    Write-Host $javacOut
    throw "javac failed"
}

Write-Host "==> 3) jar + d8"
Push-Location obj
& jar cf "$ROOT\bin\classes.jar" -C . .
Pop-Location
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& java -cp "$BT\lib\d8.jar" com.android.tools.r8.D8 `
    --output "$ROOT\bin\classes-dex.zip" --lib $PLAT --min-api 9 "$ROOT\bin\classes.jar" > $null 2>&1
$d8Exit = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($d8Exit -ne 0) { throw "d8 failed" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory("$ROOT\bin\classes-dex.zip", "$ROOT\bin\dex_extract")
Move-Item "$ROOT\bin\dex_extract\classes.dex" "$ROOT\bin\classes.dex" -Force
Remove-Item -Recurse -Force "$ROOT\bin\classes-dex.zip", "$ROOT\bin\dex_extract"

Write-Host "==> 4) aapt package"
& "$BT\aapt.exe" package -f -M AndroidManifest.xml -S res -I $PLAT -F "$ROOT\bin\app.unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt package failed" }

Write-Host "==> 5) aapt add dex"
Push-Location "$ROOT\bin"
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& "$BT\aapt.exe" add app.unsigned.apk classes.dex > $null 2>&1
if ($LASTEXITCODE -ne 0) { $ErrorActionPreference = $prevEAP; throw "aapt add classes.dex failed" }
$ErrorActionPreference = $prevEAP
Pop-Location

if (-not (Test-Path $KS)) {
    Write-Host "==> generate keystore"
    $ktArgs = @("-genkeypair","-keystore",$KS,"-storetype","JKS","-storepass","aiapp123",
        "-alias","aiapp","-keypass","aiapp123","-keyalg","RSA","-keysize","2048","-validity","20000",
        "-dname","CN=AIApp,O=AIApp,C=CN")
    $p = Start-Process -FilePath "keytool" -ArgumentList $ktArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput "$ROOT\kt_out.txt" -RedirectStandardError "$ROOT\kt_err.txt"
    if ($p.ExitCode -ne 0 -or -not (Test-Path $KS)) {
        Write-Host (Get-Content "$ROOT\kt_err.txt" -Raw)
        throw "keystore generation failed"
    }
    Remove-Item "$ROOT\kt_out.txt","$ROOT\kt_err.txt" -ErrorAction SilentlyContinue
}

Write-Host "==> 6) zipalign"
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$zipalignOut = & "$BT\zipalign.exe" -f -v 4 "$ROOT\bin\app.unsigned.apk" "$ROOT\bin\app.aligned.apk" 2>&1
$ErrorActionPreference = $prevEAP
Write-Host ($zipalignOut | Select-Object -Last 1)

Write-Host "==> 7) apksigner"
& "$BT\apksigner.bat" sign --v1-signing-enabled true --v2-signing-enabled true `
    --ks $KS --ks-pass pass:aiapp123 --ks-key-alias aiapp --key-pass pass:aiapp123 `
    --out "$ROOT\bin\(OAC).apk" "$ROOT\bin\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ""
Write-Host "BUILD OK: $ROOT\bin\(OAC).apk"
$size = (Get-Item "$ROOT\bin\(OAC).apk").Length
Write-Host ("Size: {0:N0} bytes ({1:N2} KB)" -f $size, ($size/1024))
