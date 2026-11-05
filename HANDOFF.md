# ZKTeco C6 attendance system — handoff

Paste this whole file into a new chat to continue. It is written to be read
cold, with no prior context.

**Project root:** `C:\Users\dev\Documents\Cluade\zk-attendance`
**Last updated:** 2 September 2026

---

## 1. What we are building

Staff clock in with a fingerprint on a **ZKTeco C6 handheld**. Each punch must
reach a **Snowflake** table (`DEVELOPMENT.ATTENDANCE`) with no laptop involved,
running around the clock including nights and weekends.

Requirements the user stated explicitly, which have not changed:

- **No laptop in the loop** once a finger is scanned.
- **24/7** — shifts and weekends.
- **No late/absent rules.** Do not add them. Grading belongs in SQL, not on the
  terminal.
- User signs into Snowflake and Dataiku with **SSO** and **cannot create
  Snowflake users**. `DEVELOPMENT` is their sandbox; `CREATE TABLE` is allowed.
- No always-on Linux machine is obtainable.

## 2. Current state — read this first

### Working and verified

| Thing | Status |
|---|---|
| n8n ADMS parser (`n8n/parse-adms-request.js`) | **28/28 tests pass** |
| Local ADMS test server + device simulator | **6/6 protocol steps pass** |
| Snowflake DDL + MERGE statements | written, **never run against a warehouse** |
| Android app source | **compiles** |
| Android app APK | **built and installed** on the C6 |
| App launch, UI, WorkManager sync cycle | **working on device** |

### The one thing that does not work

The app shows **"Sensor unavailable"**. `NIDFPSensor.open()` fails.

**The immediate next step is to read the second, smaller line of text under
"Sensor unavailable" on the device screen.** It is the exception message and it
decides everything:

| Text on screen | Meaning | Fix |
|---|---|---|
| `USB permission … not granted` | Module powered up; Android needs approval | User taps OK on the dialog |
| `sensor.open failed` | GPIO 141 did not wake the module on this unit | Try other power lines / longer settle delay |
| `ZKFingerService.init() returned N` | Sensor open, matcher failed | Licence problem — different issue |

### Blocked on

`adb` cannot see the device. Windows shows a "USB Composite Device" so the cable
is fine, but the ADB interface is not exposed. On the C6: notification shade →
USB notification → **File Transfer** (not "Charging only"), and check
**Settings → System → Developer options → USB debugging** is still on.

## 3. Why the app exists at all (do not re-litigate this)

The original design had the device push ADMS directly to n8n. **The C6 never
sent a single request**, despite configuration that was verified correct on
screen:

```
Server type ADMS · Enable domain name OFF · Server address <laptop IP>
Server port 8099 · Enable proxy OFF · Open HTTPS OFF
```

Diagnosis that was already done — do not repeat it:

- Laptop firewall, network category, and listener were all verified working; a
  second device on the same hotspot reached the test server successfully.
- The C6 was on the same subnet and answered pings.
- The C6 made **zero TCP connection attempts** to the laptop.
- Chrome on the C6 could not reach the laptop either, while the phone could.
- `ZKFinger10Demo` is an SDK sample with no networking — punching in it proves
  the sensor works, nothing more.

Conclusion: write our own Android app that reads the sensor and POSTs over
HTTPS. This is strictly better than ADMS anyway — HTTPS enforced, a shared
secret, a visible offline queue, and it removes the need for the reverse-proxy
rewrite (the device could only be given host+port, never a path; our app uses a
full URL).

## 4. The SDK — hard-won, do not guess any of this

Full detail with reproduction steps: **`android/SDK-CONTRACT.md`**. Everything
there was read off the device by pulling
`com.zkteco.android.zkfinger10sample` with adb and disassembling it with
`dexdump`. Highlights:

**Every public ZKFinger sample on GitHub is for the wrong hardware.** They drive
external USB/OTG scanners. The C6's built-in sensor needs:

```kotlin
params["param.key.vid"] = 6997        // 0x1B55
params["param.key.pid"] = 770         // 0x0302   (public samples use 289)
NIDFPFactory.createNIDFPSensor(ctx, TransportType.USBSCSI, params)  // not .USB
```

**The module is powered OFF at boot.** Nothing appears on the USB bus until:

```kotlin
SerialPort().setGPIOlow(141)   // active low
Thread.sleep(3200)             // settle + enumerate
// then UsbManager.requestPermission, then sensor.open(0)
```

This is why `open()` failed with the useless "Open NIDFP sensor failed" — there
was genuinely no device on the bus. `setGPIOhigh(141)` powers it back down.

**Templates are stored in memory only.** The vendor demo's private data
directory contains no database. `ZKFingerService.save()` populates an in-memory
cache that dies with the process. **We own persistence** — Room holds the
authoritative copy and `Biometrics.ensureReady()` replays every template into
the engine at startup. Skip that and the app matches nobody after a reboot and
reports no error.

**Other non-obvious facts:** `identify()` writes `"<id>\t<score>"` into a
256-byte buffer, not a bare id; templates are 2048 bytes; the demo's threshold
is 70; enrolment is 3 samples → `merge()` → `save()` with an `identify()` first
to reject a finger already registered to someone else.

## 5. Machine quirks that will waste your day

All verified on this laptop (`NG4845E66ADFB7`).

**The user's Windows account is not an admin.** "Run as administrator" starts
the shell as a *different user* (`OAFAdmin`), so per-user installs vanish:
`node` reports "not recognized" in an elevated shell even though it is
installed. **A prompt reading `C:\WINDOWS\system32>` is elevated — close it.**
Node lives at `%LOCALAPPDATA%\Programs\nodejs`.

**AF_UNIX sockets are blocked under `%LOCALAPPDATA%`.** Modern JDKs build the
`Selector` wakeup pipe on an auto-bound AF_UNIX socket in `java.io.tmpdir`, so
the Gradle daemon dies with *"Unable to establish loopback connection"*. Setting
`org.gradle.jvmargs` does **not** fix it — Gradle only forwards a fixed set of
system properties to the daemon JVM. The environment variables in §6 do fix it.

**Both JDKs on the machine are Java 25.** Gradle 8.x + AGP 8.5 need **JDK 17**,
which was downloaded to the scratchpad (see §6).

## 6. Build and install — exact commands

Toolchain lives in the session scratchpad, which may be cleared. If
`jdk17`/`gradle` are missing, re-download Temurin JDK 17 and Gradle 8.9 (zips,
no admin needed) and adjust the paths.

```powershell
$sp   = "<scratchpad>"                       # holds jdk17\ and gradle\
$tmp  = "C:\Users\dev\gradle-tmp"
$sock = "C:\Users\dev\.jdksock"
New-Item -ItemType Directory -Force -Path $tmp, $sock | Out-Null

$env:JAVA_HOME        = (Get-ChildItem "$sp\jdk17" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME     = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:TMP  = $tmp
$env:TEMP = $tmp
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$sock -Djava.io.tmpdir=$tmp"

$gradle = (Get-ChildItem "$sp\gradle" -Directory | Select-Object -First 1).FullName
Set-Location "C:\Users\dev\Documents\Cluade\zk-attendance\android"
& "$gradle\bin\gradle.bat" assembleDebug --console=plain
```

Install and launch:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb logcat -c
& $adb shell "am start -n com.example.attendance.debug/com.example.attendance.MainActivity"
& $adb logcat -d -s SensorPower:V UsbPermission:V FingerprintEngine:V Biometrics:V
```

`adb` output does not survive this harness's background wrapper — redirect to a
file and read that instead.

Rebuild the vendor SDK jar (needs the device connected):
`android\tools\build-sdk-jar.ps1`

## 7. File map

```
zk-attendance/
  HANDOFF.md                  this file
  SETUP-WALKTHROUGH.md        original beginner walkthrough, Phases 0-7
  README.md                   original ADMS-route README
  n8n/
    parse-adms-request.js     THE parser, editable source of truth
    build-workflow.mjs        regenerates the workflow JSON; user hand-edited it
    test-parser.mjs           28 tests - run after ANY parser change
    adms-listener.workflow.json   generated; never hand-edit
  sql/
    00_snowflake_access.sql   PAT / service account / key-pair routes
    01_snowflake_schema.sql   EMPLOYEES, BIOMETRIC_TEMPLATES, ATTENDANCE_LOGS, RAW_PUSH_LOG
    02_snowflake_views.sql    dashboard views
  tools/
    local_test_server.mjs     hosts the parser, prints SQL instead of executing
    simulate_device.py        fake ADMS device
    test_snowflake_auth.py    test a credential outside n8n
  android/
    SDK-CONTRACT.md           verified SDK API + provenance   <-- read this
    README.md                 build, first run, what is unverified
    app/libs/zkteco-sdk.jar   90 classes extracted from the device's demo APK
    jniLibs/                  14 vendor .so files, both ABIs
    tools/build-sdk-jar.ps1   regenerates the jar, fails loudly if incomplete
    app/src/main/java/org/example/zkattendance/
      fingerprint/FingerprintEngine.kt   only file touching the SDK
      fingerprint/SensorPower.kt         GPIO 141 power control
      fingerprint/UsbPermission.kt       runtime USB permission
      fingerprint/Biometrics.kt          single engine owner + template replay
      data/                              Room: employees, punch queue
      sync/AttendanceApi.kt              ADMS wire format over HTTPS
      sync/SyncWorker.kt                 WorkManager, backoff, survives reboot
      MainActivity.kt                    clock-in kiosk
      AdminActivity.kt                   enrolment + settings
```

## 8. Design decisions to preserve

**The app speaks the ADMS wire format, not JSON.** It POSTs to
`<baseUrl>/iclock/cdata?SN=…&table=ATTLOG|USERINFO|BIODATA` so the existing
tested parser and Snowflake MERGEs work unchanged. ATTLOG is tab-positional
(`PIN\tyyyy-MM-dd HH:mm:ss\tstate\tverify\tworkcode`); the others are
tab-joined `KEY=value`.

**`logId` is `<deviceSn>|<pin>|<yyyy-MM-dd HH:mm:ss>`**, identical to what the
parser builds for a real device, so retries de-duplicate in the MERGE instead of
double-counting attendance. Snowflake does not enforce uniqueness — this key is
the only thing preventing duplicates.

**No `fallbackToDestructiveMigration`** on the Room database. It holds the only
copy of the templates and of any punch not yet in Snowflake.

**`allowBackup=false`** — fingerprint templates are special-category personal
data under NDPR/GDPR.

## 9. Outstanding work

**Security, before any real use:**

1. **The n8n webhook ignores `X-ZK-Secret`.** The app sends it; nothing checks
   it. `automations.example.org/webhook/*` is reachable from the public
   internet with no login, so until a check is added anyone who guesses the path
   can inject attendance records or post fingerprint templates. The code to add
   is in `android/README.md`.
2. Change the app's admin PIN from the default `0000`.
3. `REVOKE SELECT ON …BIOMETRIC_TEMPLATES FROM ROLE <analyst role>`.

**Never run:** Snowflake DDL, the MERGEs, or the n8n workflow. The workflow has
never been imported; n8n's management API returns 303 behind OAuth, so it must
be imported by hand.

**Snowflake auth is unresolved.** Three routes in `sql/00_snowflake_access.sql`,
try in order: (B) programmatic access token — self-service, no admin needed;
(A) service account — needs an admin, best long-term; (C) key pair on own user —
almost certainly closed, `RSA_PUBLIC_KEY` needs OWNERSHIP. Probe with
`ALTER USER "<login>" SET COMMENT = 'privilege probe';`.

**Unverified on hardware:** the `setParameter(0, ?, 0)` argument the demo passes
and we do not; `getLicenseType()` / `getFPLimitCount()` (a `LIC_TYPE_LIMIT`
result would cap enrolment headcount); the 80 ms poll interval is ours, not the
vendor's.

## 10. How to work with this user

- They asked to be corrected and pushed back on. Do it.
- They cross-check code and build in Codex, so comments should explain *why*,
  not restate the code.
- They said: "dont guess be sure and build optimally". When an API is unknown,
  pull the APK and disassemble it rather than infer from documentation.
- Give one-line shell commands. A backtick line-continuation lost in a paste
  once created a firewall rule that allowed **all** inbound traffic, because the
  first line was valid on its own and only the second line errored.
