# ZKTeco C6 fingerprint SDK — verified contract

Everything here was **read off the device**, not inferred from documentation or
GitHub samples. Source: `com.zkteco.android.zkfinger10sample` (the ZKFinger10Demo
app that ships on the unit), pulled with `adb` and disassembled with
`dexdump` from Android SDK build-tools 36.0.0.

Reproduce any line below with:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb pull (& $adb shell "pm path com.zkteco.android.zkfinger10sample").Replace("package:","")
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\dexdump.exe" -d classes.dex > disasm.txt
```

## Target device

| Property | Value |
|---|---|
| `ro.product.model` | `C6` |
| `ro.product.manufacturer` | `Handheld-Wireless` |
| `ro.build.version.release` | `10` |
| `ro.build.version.sdk` | **29** |
| `ro.build.display.id` | `C6.QV10.4.31_0.EEA` |
| Serial | `C600000000016564` |
| ABIs present in sample | `arm64-v8a`, `armeabi-v7a` |

`minSdk`/`targetSdk` must accommodate **API 29**.

## THE critical detail

Every public ZKFinger sample on GitHub targets an **external USB scanner**
(ZK9500/SLK20R over OTG) using `FingerprintFactory.createFingerprintSensor(...)`
with `TransportType.USB` and `PID = 289`. **None of that applies to the C6.**

The C6's integrated sensor is reached through a different factory, a different
transport, and a different product id:

```java
LogHelper.setLevel(7);
LogHelper.setNDKLogLevel(7);

Map<String, Integer> params = new HashMap<>();
params.put("param.key.vid", 6997);   // 0x1B55  ParameterHelper.PARAM_KEY_VID
params.put("param.key.pid", 770);    // 0x0302  ParameterHelper.PARAM_KEY_PID

NIDFPSensor sensor = NIDFPFactory.createNIDFPSensor(
        context,
        TransportType.USBSCSI,       // NOT TransportType.USB
        params);
```

`PID = 770` is a static initialised in the demo's `<clinit>`
(`const/16 v0, #int 770`). Using `289` or `TransportType.USB` yields an app that
compiles, installs, and never finds the sensor.

## Native libraries (bundled by the demo, so we bundle them too)

Extracted from the sample APK under `lib/<abi>/`. Both ABIs are present; ship
both or restrict `abiFilters` to `arm64-v8a`.

| Library | arm64-v8a | Purpose |
|---|---|---|
| `libzkalg12.so` | 3979 KB | matching algorithm |
| `libzksensorcore.so` | 218 KB | sensor core |
| `libzkusbctl.so` | 114 KB | USB/SCSI transport |
| `libzkfinger10.so` | 94 KB | ZKFinger10 API |
| `libidfprcap.so` | 45 KB | capture |
| `libdevapi.so` | 33 KB | device API |
| `libirdaSerialPort.so` | 9 KB | serial (unused by us) |

## Startup sequence (from `MainActivity.openDevice`)

Order matters — `ZKFingerService.init()` is called **after** `sensor.open()`.

```java
startNIDFPSensor();                  // the factory call above
sensor.setIDFPSupport(true);
sensor.open(0);
if (ZKFingerService.init() != 0) {   // 0 == success
    sensor.close(0);
    // "init fail"
}
sensor.setParameter(0, <code>, 0);
int w = sensor.getFpImgWidth();
int h = sensor.getFpImgHeight();
byte[] imageBuf = new byte[w * h];
// start capture thread
```

Failures throw `NIDFPException` — use `getErrorCode()` and `getMessage()`.

## `NIDFPSensor` — sensor surface

Package `com.zkteco.android.biometric.nidfpsensor`.

```java
void    open(int)                 void   close(int)         void destroy()
void    GetFPRawData(int, byte[])
int     FeatureExtract(int, byte[], byte[])
float   FeatureMatch(int, byte[], byte[])
float   ImageMatch(int, byte[], byte[])     float ImageMatch2(int, byte[], byte[])
int     getQualityScore(byte[], byte[])
int     getFpImgWidth()           int    getFpImgHeight()
int     getParameter(int, int)    void   setParameter(int, int, int)
String  getSerialNumber()         String getFirmwareVersion()
int     getDeviceStatus(int)      int    getExceptStatus()
void    setIDFPSupport(boolean)   void   setEncryptFirmware(boolean)
void    setLibusbFlag(boolean)    void   setWDTSwitch(boolean)
boolean wdtEnable(int)            void   wdtReset()
void    rebootDevice(int)         void   resetUSB(int)      void resetUSBEx(int)
void    clearImage(int)
static  Bitmap raw2TransparentPng(byte[], int, int, int, int, int)
```

Parameter codes exposed as fields: `PARAM_CODE_CAPTURE_MODE`,
`PARAM_CODE_DETECT_FLAG`, `PARAM_CODE_DETECT_STATUS`, `PARAM_CODE_FIRMWARE_VER`,
`PARAM_CODE_IMG_WIDTH`, `PARAM_CODE_IMG_HEIGHT`,
`SENSOR_PARAM_CODE_DEVICE_STATUS`.

## `ZKFingerService` — algorithm + on-device template store

Package `com.zkteco.android.biometric.module.fingerprintreader`. **All static.**
This is the class the demo actually calls; `com.zkteco.zkfinger.FingerprintService`
is the lower JNI layer beneath it.

```java
static int    init()                       // 0 == success. NO arguments.
static int    free()
static int    extract(byte[] img, int w, int h, byte[] tmpl, int[] size)
static int    merge(byte[] t1, byte[] t2, byte[] t3, byte[] regTemplate)
static int    save(byte[] regTemplate, String id)
static int    identify(byte[] tmpl, byte[] outId, int threshold, int count)
static int    verify(byte[] t1, byte[] t2)
static int    verifyId(byte[] tmpl, String id)
static int    del(String id)               static int clear()
static int    count()                      static int get(byte[] out, String id)
static int    getTemplateLength(byte[])    static int getTemplateQuality(byte[])
static int    convertTemplate(byte[], byte[])
static int    setParameter(int, int)
static int    getFPLimitCount()            static int getLicenseType()
static String getMachineCode()
static void   version(byte[])
```

License type constants: `LIC_TYPE_HARDWARE`, `LIC_TYPE_HARDWARE2`,
`LIC_TYPE_LIMIT`, `LIC_TYPE_SENSOR`. **Check `getLicenseType()` and
`getFPLimitCount()` early** — `LIC_TYPE_LIMIT` implies a cap on enrolled
fingers, which would constrain headcount.

## Buffer sizes and the identify result format

From the demo's capture loop:

- template buffer: **2048** bytes
- `identify` output id buffer: **256** bytes
- identify threshold used by the demo: **70**, count **1**
- `identify` returns `> 0` on a hit; the output buffer holds
  **`"<id>\t<score>"`** — the demo does `new String(outId).split("\t")` and takes
  element 0. Do not assume the buffer is just the id.

## Enrolment algorithm (as the demo implements it)

Three captures of the same finger, merged into one registered template:

1. `AcquireImageAndTemplate` → candidate template
2. `identify(...)` first — if it hits, the finger is already enrolled to another
   id; abort with "the finger already enroll by X, cancel enroll"
3. from the second sample on, `verify(previousSample, current)` must pass, else
   "please press the same finger 3 times for the enrollment"
4. at three samples: `merge(s1, s2, s3, regTemplate)` then `save(regTemplate, id)`

Worth keeping: rejecting a finger already registered to someone else is exactly
the control that stops buddy-punching, and it is cheap.

## Open questions still to resolve on-device

- Value passed to `setParameter(0, ?, 0)` in `openDevice` — read it before relying
  on defaults.
- ~~Whether `ZKFingerService`'s template store persists~~ **Answered: it does not.**
  The demo's private data directory (`run-as ... ls -la`) contains only `cache`
  and `code_cache` — no database, no template file, after the app has been used
  to enrol. `ZKFingerService.save()` therefore populates an **in-memory** cache
  that dies with the process.

  Consequence for our design, and it is not optional: **we own persistence.**
  Templates go into our own storage, and on every app start we must replay them
  into the engine (`save(template, id)` per employee) before `identify()` will
  match anything. Miss this and the app works perfectly until the first reboot,
  then silently recognises nobody.
- `getLicenseType()` / `getFPLimitCount()` actual values on this unit.

## Build-time dependency (unsolved)

The `.so` files are extractable from the sample APK, but the **Java SDK classes**
(`com.zkteco.android.biometric.*`, `com.zkteco.zkfinger.*`) are only present as
compiled dex. JNI binding is by exact package + class name, so these cannot be
renamed or reimplemented casually.

Options, in order of preference:

1. Obtain the official **ZKFinger SDK for Android** `.jar`/`.aar` from ZKTeco —
   cleanest, licensed, supportable.
2. Convert the sample APK's dex to a jar (`dex2jar`) and compile against it as a
   `compileOnly` dependency, loading the real implementation at runtime.
3. Re-declare the native method signatures in identically-named classes. Works
   only if the `.so` exports standard `Java_com_zkteco_...` symbols; unverified.

Route 1 should be requested from the vendor now, because it is the only one that
survives an audit.
