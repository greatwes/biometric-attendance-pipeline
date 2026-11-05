<#
    Rebuilds app/libs/zkteco-sdk.jar from the ZKFinger demo that ships on the C6.

    Why this exists: ZKTeco do not publish an SDK artifact for the C6's built-in
    sensor, and the public ZKFinger SDK targets external USB scanners with a
    different transport and product id. The only authoritative copy of the Java
    classes is the demo app on the device itself.

    Run this when you get a new device, a firmware update, or you need to prove
    where the jar came from. Requires the device connected with USB debugging.

    Prerequisites (all already present on this machine):
      - Android SDK platform-tools (adb)
      - dex-tools (dex2jar) - set $DexTools below
      - a JDK - Android Studio's bundled JBR is fine
#>

[CmdletBinding()]
param(
    [string]$Package  = 'com.zkteco.android.zkfinger10sample',
    [string]$DexTools = "$env:TEMP\dex2jar\dex-tools-v2.4",
    [string]$JavaHome = "$env:LOCALAPPDATA\Programs\android-studio\jbr",
    [string]$OutJar   = "$PSScriptRoot\..\app\libs\zkteco-sdk.jar"
)

$ErrorActionPreference = 'Stop'

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb))      { throw "adb not found at $adb" }
if (-not (Test-Path $DexTools)) { throw "dex-tools not found at $DexTools. Download dex2jar v2.4 and extract it there." }
if (-not (Test-Path $JavaHome)) { throw "JDK not found at $JavaHome" }

$work = Join-Path ([System.IO.Path]::GetTempPath()) "zksdk-$(Get-Random)"
New-Item -ItemType Directory -Force -Path $work | Out-Null

try {
    Write-Host "Locating $Package on the device..."
    $devicePath = (& $adb shell "pm path $Package" | Select-Object -First 1) -replace 'package:', '' -replace "`r", ''
    if (-not $devicePath) { throw "$Package is not installed on the connected device." }

    $apk = Join-Path $work 'demo.apk'
    & $adb pull $devicePath $apk | Out-Null
    if (-not (Test-Path $apk)) { throw "adb pull failed for $devicePath" }
    Write-Host ("Pulled {0:N0} bytes" -f (Get-Item $apk).Length)

    $env:JAVA_HOME = $JavaHome
    $env:PATH      = "$JavaHome\bin;$env:PATH"

    $full = Join-Path $work 'full.jar'
    & "$DexTools\d2j-dex2jar.bat" -f -o $full $apk 2>&1 | Out-Null
    if (-not (Test-Path $full)) { throw 'dex2jar produced no output' }

    # Keep only the vendor SDK. The demo's own UI classes would collide with
    # nothing, but shipping them is noise in an app we have to justify.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Force -Path (Split-Path $OutJar) | Out-Null
    Remove-Item $OutJar -Force -ErrorAction SilentlyContinue

    $src = [System.IO.Compression.ZipFile]::OpenRead($full)
    $zip = [System.IO.Compression.ZipFile]::Open($OutJar, 'Create')
    $count = 0
    foreach ($e in $src.Entries) {
        # cn/pda holds SerialPort, which powers the fingerprint module on via
        # GPIO. Omitting it produces a jar that compiles the matching code and
        # then fails to open the sensor, because the sensor is never switched on.
        if (($e.FullName -like 'com/zkteco/*' -or $e.FullName -like 'cn/pda/*') -and
            $e.FullName -like '*.class') {
            $ne = $zip.CreateEntry($e.FullName)
            $rs = $e.Open(); $ws = $ne.Open()
            $rs.CopyTo($ws)
            $ws.Close(); $rs.Close()
            $count++
        }
    }
    $zip.Dispose(); $src.Dispose()

    # Fail loudly if anything FingerprintEngine imports went missing - a jar
    # that is merely non-empty is not good enough.
    $required = @(
        'com/zkteco/android/biometric/nidfpsensor/NIDFPFactory.class'
        'com/zkteco/android/biometric/nidfpsensor/NIDFPSensor.class'
        'com/zkteco/android/biometric/nidfpsensor/exception/NIDFPException.class'
        'com/zkteco/android/biometric/module/fingerprintreader/ZKFingerService.class'
        'com/zkteco/android/biometric/core/device/TransportType.class'
        'com/zkteco/android/biometric/core/utils/LogHelper.class'
    )
    $z = [System.IO.Compression.ZipFile]::OpenRead($OutJar)
    $have = $z.Entries | ForEach-Object { $_.FullName }
    $z.Dispose()

    $missing = $required | Where-Object { $have -notcontains $_ }
    if ($missing) { throw "jar is missing required classes:`n  $($missing -join "`n  ")" }

    Write-Host ("OK: {0} classes, {1:N0} bytes -> {2}" -f $count, (Get-Item $OutJar).Length, $OutJar)
}
finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
