# ZKAttendance — Android app for the ZKTeco C6

Replaces the device's ADMS push, which never sent a single request despite a
correct configuration. This app reads the fingerprint sensor directly, matches
against locally stored templates, and POSTs punches to the existing n8n webhook
over HTTPS.

**The server side is unchanged.** The app emits the same ADMS wire format the
n8n parser already handles, so `n8n/parse-adms-request.js` (28 passing tests)
and the Snowflake MERGE statements work exactly as before.

## Why this beats the ADMS route even if ADMS had worked

| | ADMS device push | This app |
|---|---|---|
| Transport | plain HTTP; device offers HTTPS but untested | HTTPS enforced (`usesCleartextTraffic=false`) |
| Authentication | none — anyone who guesses the URL can post | shared secret header |
| URL | host + port only, so needed a proxy rewrite | full URL, **Phase 4 routing is unnecessary** |
| Offline | opaque device buffer | explicit queue in SQLite, visible on screen |
| Retry | unknown | WorkManager with exponential backoff, survives reboot |

## Build

```powershell
cd zk-attendance\android
# Android Studio: File > Open, select this folder.
```

There is no Gradle wrapper checked in (a `gradle-wrapper.jar` is a binary and
does not belong in a review diff). Android Studio will offer to generate one on
first open, or run `gradle wrapper` once if you have Gradle installed.

Requirements: Android Studio with SDK 34, and **JDK 17**.

Android Studio's bundled JBR is **no longer** a safe default. As of the current
install it is JDK 25, and nothing in this build accepts that: Gradle 8.9 supports
up to 22, AGP 8.5.2 wants 17-21, and Kotlin 1.9.24 predates 25 entirely. It fails
on class-file version before reaching any project code, which reads as a mystery
rather than a version mismatch.

In Android Studio, set the Gradle JDK explicitly (Settings ▸ Build, Execution,
Deployment ▸ Build Tools ▸ Gradle ▸ Gradle JDK) rather than trusting the default.
From the command line, point `JAVA_HOME` at a JDK 17 for the build only - a
portable Temurin zip needs no installer and no admin rights, which matters on a
managed machine.

### The vendor SDK

`app/libs/zkteco-sdk.jar` is **not** downloaded from ZKTeco — no such artifact
is published for the C6's built-in sensor. It is extracted from the ZKFinger
demo that ships on the device itself.

Rebuild it any time with the device connected:

```powershell
.\tools\build-sdk-jar.ps1
```

The script fails loudly if any class the app imports is missing, so a silently
truncated jar cannot reach a build. `jniLibs/` holds the matching native
libraries, from the same APK. Provenance for every constant is in
[SDK-CONTRACT.md](SDK-CONTRACT.md).

## One change needed on the n8n side

The app sends `X-ZK-Secret` on every request. **The workflow currently ignores
it.** Until a check is added, the header is decoration and the webhook remains
open to anyone on the internet who guesses the path.

Add this as the first node after the webhook, before the parser:

```javascript
const expected = $env.ZK_SHARED_SECRET;
const got = $input.first().json.headers?.['x-zk-secret'];
if (!expected || got !== expected) {
  throw new Error('rejected: bad or missing X-ZK-Secret');
}
return $input.all();
```

Then set the same value in the app under Admin → Settings → Shared secret.
Generate one properly, e.g. `openssl rand -base64 32` — it is a password.

## First run on a device

1. Install, open the app. It should say **Place your finger**. If it says
   *Sensor unavailable*, check logcat for `FingerprintEngine`.
2. **Admin** (default PIN `0000`) → set Server URL, Shared secret, and **change
   the admin PIN**. The screen warns while it is still the default.
3. Enrol yourself: payroll number, name, then press the same finger three times.
4. Go back and punch. Expect *Welcome, <name>* and the queue line to clear.
5. Confirm the row landed:
   `SELECT * FROM DEVELOPMENT.ATTENDANCE.ATTENDANCE_LOGS ORDER BY PUNCH_TS DESC;`

Test offline behaviour before trusting it: turn Wi-Fi off, punch a few times,
watch the queue count rise, turn Wi-Fi on, watch it drain.

## Design decisions worth knowing

**We own the templates.** The ZKTeco matching engine stores templates in memory
only — verified, the vendor demo's data directory contains no database. Room
holds the authoritative copy and `Biometrics.ensureReady()` replays every one
into the engine at startup. Without that the app matches nobody after a reboot
and reports no error at all.

**No late/absent logic anywhere.** That was an explicit requirement. It also
means changing your mind later is a SQL change, not a fleet-wide app update.

**Enrolment refuses a finger already registered to someone else.** It is the
cheapest anti-buddy-punching control available and it costs one `identify()`
call.

**`logId` is built identically to the n8n parser's** —
`<deviceSn>|<pin>|<yyyy-MM-dd HH:mm:ss>` — so a retried upload de-duplicates in
the Snowflake MERGE rather than double-counting attendance.

**`allowBackup=false`.** The database holds fingerprint templates, which are
special-category personal data under NDPR/GDPR. Letting adb backup or a cloud
sync copy it off the device would put that data somewhere nobody audits.

## Still to verify on hardware

None of this has run on the device yet. In likely order of trouble:

1. **Sensor opens at all.** `NIDFPFactory.createNIDFPSensor` with
   `TransportType.USBSCSI` may need USB permission granted to our app, which
   the vendor demo requests via `UsbManager` for its external-scanner path. If
   `open()` throws, that is the first thing to try.
2. **`setParameter(0, ?, 0)`** — the demo passes a parameter code this app does
   not set. Defaults may be fine; if capture quality is poor, read the value
   out of the demo's bytecode.
3. **`getLicenseType()` / `getFPLimitCount()`** — logged at startup. A
   `LIC_TYPE_LIMIT` result would cap how many people can be enrolled.
4. **Poll interval.** 80 ms is ours, not the vendor's. Tune if the device runs
   hot or feels unresponsive.
