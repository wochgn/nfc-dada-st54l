# ST54L vendor-NCI + Frida feasibility probe — Xiaomi 15 (dada)

- Date: 2026-10-30
- Device: Xiaomi 15, `ro.product.device=dada`, model `24129PN74C`, Android 17 (`ro.build.version.sdk=37`); ROM `OS4.0.0.22.XOCCNXM` (build `CP2A.260605.016`, build date 2026-10-16)
- Root: KernelSU; `adb -s [redacted-serial] shell su -c 'id'` → `uid=0(root) ... context=u:r:ksu:s0` (the adb shell itself also runs as uid=0 in this session, so `su -c` was not strictly required)
- NFC controller: STMicroelectronics ST54L (`ro.hardware.nfc=ST_NFC`, `/dev/st21nfc`, HAL service `/odm/bin/hw/android.hardware.nfc-service-st`, HAL lib `/odm/lib64/nfc_nci.st21nfc.st.so`, config `/odm/etc/libnfc-hal-st.conf`)
- NFC app: package `com.android.nfc` = `/system/app/Nfc_st/Nfc_st.apk` (ST-flavoured AOSP NfcService, single `classes.dex`, contains `lib/arm64-v8a/libstnfc_nci_jni.so`)
- Task: task-1. Probe sources/artifacts: `probe/`; probe dex runs as root via `app_process`.

## TL;DR

| Question | Verdict |
|---|---|
| Prototype's Java API surface (NfcAdapter vendor-NCI + OEM extension) present on dada? | **YES** — all classes/methods present and callable at runtime |
| Prototype adapter-acquisition trick (`NfcFrameworkInitializer` fallback + `ActivityThread.systemMain`/`getSystemContext`) works on app_process? | **YES** — non-null, enabled `NfcAdapter` |
| Vendor NCI round-trip to controller works? | **YES** — HAL/controller accept and answer; callback plumbing verified |
| ST54L RF config (196-byte, params 2/3/4) readable? | **YES** — CRC-valid 196-byte configs returned, exact hex below |
| Custom passive poll frames (7A wake) set/get? | **NO** — controller returns NCI `STATUS_SYNTAX_ERROR` (0x05) for BOTH set (0x13/0x1D) and get (0x15/0x1E), even while polling paused |
| Frida injection into `com.android.nfc` works? | **YES** — frida-server 17.24.0 + frida-inject 17.24.0 run as root, `INK_NFC_MIFARE_COMPAT_READY` received; 3 target symbols resolve uniquely |
| ST FW TEST channel (vendor oid=3) reachable? | **YES** — `ST_NCI_PROP_TEST_LOADER_PRODUCT_INFO` (0x07) returns a 60-byte product-info block; `TEST_FIELD` 0xB3 on/off accepted with status 0x00 |
| Can the vendor path carry arbitrary (non-prop) NCI? | **YES** — NCI CORE (gid=0) / RF (gid=1) commands are written verbatim to the controller (`20 02 ..`, `21 02 ..`) and answered |
| Raw RF TEST_DATA 0xB2 (potential 7A transmit without FW update)? | **TESTED IN DRY RUN — NOT USABLE**: every 0xB2 candidate made the NFCC stop answering; the stack watchdog aborted and restarted the NFC service. Abandoned. |
| TEST_DATA_EXTENDED 0xBB with the physical e-ink panel in place (§10)? | **INCONCLUSIVE / NOT USABLE AS-IS**: `BB 02 01 7A 00`, `BB 02 01 7A`, `BB 01 7A`, `BB 01 01 7A` are syntactically accepted (status 0x00, repeatable) but produced no RF activation/tag/panel-response evidence and no timing change vs the no-panel dry run; the real payload semantics remain unknown. |
| ST54L FW / ROM identity | **ST54L, silicon rev 3, FW 02.04.1188**; ROM `OS4.0.0.22.XOCCNXM` (build date 2026-10-16); config script Rev.C 2026-01-15, CRCs `9198`/`CA3D` confirmed live |
| Anything left modified on device? | **NO** — no config command was accepted; nothing written, no snapshot/backup needed; NFC verified `mState=on`, process alive |

Net: the **framework/root/vendor-NCI plumbing is viable on dada**; the **7A custom-poll-frame feature (0x1D/0x1E) is rejected by this ST54L firmware**, so the prototype's 7A-wake path is a **no-go on dada as-is**. Frida is viable. The **ST FW test/prop-test channel is reachable** and the vendor path carries raw NCI, but the `TEST_DATA` 0xB2 channel is not usable (it hangs the NFCC and restarts the stack), while the only syntactically accepted 0xBB `DATA_EXTENDED` forms produced no evidence of a frame exchange with the physical panel in place (§10). A software-only 7A transmit route therefore remains unproven and needs the ST54L FW test-command payload format and/or RF sniffing.

## 1. Environment notes and a corrected framework artifact

The earlier local pull `_device/framework/framework.jar` (51 MB, 6 dex) does **not** contain `android.nfc.NfcAdapter` — on this Android 17 build the NFC public API is in a separate boot jar:

- On device: `/system/framework/framework-nfc.jar` (431 085 B, mapped in `com.android.nfc`, `boot-framework-nfc.*` in boot image)
- Pulled for analysis: `probe/framework-nfc.jar` (new; the previous local pull missed it)
  - `android/nfc/NfcAdapter; sendVendorNciMessage:(III[B)I` — `PUBLIC`, hiddenapi `SDK,TEST-API`
  - `android/nfc/NfcAdapter; registerNfcVendorNciCallback(Executor,NfcAdapter$NfcVendorNciCallback)V`
  - `android/nfc/NfcAdapter; unregisterNfcVendorNciCallback(..)V`
  - `android/nfc/NfcAdapter$NfcVendorNciCallback; onVendorNciNotification:(II[B)V / onVendorNciResponse:(II[B)V`
  - `android/nfc/NfcAdapter; getNfcOemExtension()Landroid/nfc/NfcOemExtension;`
  - `android/nfc/NfcOemExtension; pausePolling:(J)I / resumePolling:()I / getMaxPausePollingTimeoutMills:()J`
  - `android/nfc/NfcFrameworkInitializer; get/setNfcServiceManager(...)`; `android/nfc/NfcServiceManager` + `$ServiceRegisterer` in `framework.jar` classes3.dex
  - `android/nfc/INfcAdapter; sendVendorNciMessage:(III[B)I, registerVendorExtensionCallback(INfcVendorNciCallback), pausePolling:(J)I, resumePolling:()I, getNfcAdapterVendorInterface:(Ljava/lang/String;)Landroid/os/IBinder;`
  - `android/nfc/INfcAdapter$Stub` → `sendVendorNciMessage` calls `NfcService$NfcAdapterService.sendVendorNciMessage`, which first calls `NfcPermissions.enforceAdminPermissions(mContext)` (root uid 0 passes; a normal app would need privileged NFC admin permission)

Note: on dada `NfcServiceManager` exposes only `getNfcManagerServiceRegisterer()` (not `getNfcAdapterServiceRegisterer`), but the prototype's fallback path works anyway because `NfcAdapter.getDefaultAdapter(ctx)` falls back to `context.getSystemService("nfc")` → `NfcManager` after the service registerer is resolved.

## 2. Runtime reflection / adapter creation — VERIFIED

Probe: `probe/src/dev/inkprobe/Probe.java` (mode `info`), dex `probe/build/out/classes.dex`, on-device `/data/local/tmp/inkprobe/{classes.dex,run.sh}`.

Run (from host):

```
adb -s [redacted-serial] push probe/build/out/classes.dex /data/local/tmp/inkprobe/classes.dex
adb -s [redacted-serial] shell "su -c 'sh /data/local/tmp/inkprobe/run.sh info'"
```

`run.sh`:

```sh
#!/system/bin/sh
cd /data/local/tmp/inkprobe || exit 9
CLASSPATH=classes.dex exec /system/bin/app_process /system/bin dev.inkprobe.Probe "$1"
```

Verbatim (trimmed to key lines; full log `probe/data/info-run.log`):

```text
INFO NfcFrameworkInitializer.getNfcServiceManager=null
INFO NfcFrameworkInitializer.setNfcServiceManager=installed android.nfc.NfcServiceManager
INFO systemContext=android
INFO adapter=non-null
INFO adapter.isEnabled=true
INFO ServiceManager.getService(nfc)=android.os.BinderProxy@f03a601
INFO INfcAdapter.asInterface=android.nfc.INfcAdapter$Stub$Proxy
INFO backend=adapter
INFO BUILD=Xiaomi 24129PN74C device=dada sdk=37 release=17
INFO class android.nfc.NfcAdapter=PRESENT
INFO class android.nfc.NfcAdapter$NfcVendorNciCallback=PRESENT
INFO class android.nfc.NfcAdapter$SendVendorNciStatus=PRESENT
INFO class android.nfc.NfcServiceManager=PRESENT
INFO class android.nfc.NfcServiceManager$ServiceRegisterer=PRESENT
INFO class android.nfc.NfcFrameworkInitializer=PRESENT
INFO class android.nfc.NfcOemExtension=PRESENT
INFO class android.nfc.INfcAdapter=PRESENT
INFO class android.nfc.INfcVendorNciCallback=PRESENT
INFO class android.nfc.NfcVendorNciCallbackListener=PRESENT
INFO class android.app.ActivityThread=PRESENT
INFO NfcAdapter vendor/oem methods=NfcOemExtension getNfcOemExtension() void pausePolling(int) void registerNfcVendorNciCallback(Executor,NfcVendorNciCallback) void resumePolling() int sendVendorNciMessage(int,int,int,byte[]) void unregisterNfcVendorNciCallback(NfcVendorNciCallback)
INFO NfcVendorNciCallback methods=void onVendorNciNotification(int,int,byte[]) void onVendorNciResponse(int,int,byte[])
INFO NfcServiceManager methods=ServiceRegisterer getNfcManagerServiceRegisterer()
INFO ServiceRegisterer methods=IBinder get() IBinder getOrThrow() void register(IBinder) IBinder tryGet()
INFO NfcFrameworkInitializer methods=NfcServiceManager getNfcServiceManager() NfcManager lambda$registerServiceWrappers$0(Context) void registerServiceWrappers() void setNfcServiceManager(NfcServiceManager)
INFO INfcAdapter vendor methods=long getMaxPausePollingTimeoutMs() IBinder getNfcAdapterVendorInterface(String) int pausePolling(long) void registerVendorExtensionCallback(INfcVendorNciCallback) int resumePolling() int sendVendorNciMessage(int,int,int,byte[]) void unregisterVendorExtensionCallback(INfcVendorNciCallback)
INFO INfcVendorNciCallback methods=void onVendorNotificationReceived(int,int,byte[]) void onVendorResponseReceived(int,int,byte[])
INFO getNfcOemExtension=android.nfc.NfcOemExtension
INFO oemExtension.getMaxPausePollingTimeoutMills=40000
INFO nfc binder=android.os.BinderProxy@f03a601
```

Key working recipe (exactly the prototype's `prepareAdapter()`, proven on dada):

1. `NfcFrameworkInitializer.getNfcServiceManager()` → `null` in an `app_process` (non-zygote) process.
2. Install fallback: `NfcFrameworkInitializer.setNfcServiceManager(new NfcServiceManager())` (class in `framework.jar`).
3. `ActivityThread.systemMain()` → `ActivityThread.getSystemContext()` → system `Context` (package `android`).
4. `NfcAdapter.getDefaultAdapter(context)` (or `getNfcAdapter(context)`) → non-null, `isEnabled()==true`.
5. Alternative/fallback also verified: `android.os.ServiceManager.getService("nfc")` → `INfcAdapter$Stub.asInterface(...)`.

`NfcAdapter.pausePolling(int)` and `resumePolling()` also exist on the adapter itself, but the useful pair is on the OEM extension: `NfcOemExtension.pausePolling(long)`/`resumePolling()` (both return int; 1 = success, 2 = retry). On dada `pausePolling(30000)` returned **1 on the first call** and `resumePolling()` returned **1**.

## 3. Vendor NCI protocol (framing derived from RootProbe and verified against the device)

The prototype's framing is correct for dada and matches the ST SDK (`com.st.android.nfc_extensions_16.StNfcOemExtension.sendVendorCommand`, decompiled at `_device/stnfc-src/`): `NfcAdapter.sendVendorNciMessage(mt=1, gid=15, oid, payload)` where `payload[0]` is the ST proprietary sub-OID and the rest is sub-command data.

- Read polling frames: `sendVendorNciMessage(1,15,1,{0x15})` (0x15 = `ST_PROP_GET_RF_CUSTOM_POLL_FRAME`)
- Set polling frames: `sendVendorNciMessage(1,15,1,{0x13, <count>, <RfFrameEntry TLVs...>})` (0x13 = `ST_PROP_SET_RF_CUSTOM_POLL_FRAME`)
- Read RF config: `sendVendorNciMessage(1,15,2,{0x03,0x00,0x10,0x01,param})` (oid 2 = `ST_NCI_MSG_PROP`, 0x03 = `ST_NCI_PROP_GET_CONFIG`)
- Response payload layouts seen on dada: `{subOID, status, data...}` for oid 1; `{status} ++ {0x01, param, 0xC4, <196 bytes>}` for oid 2.

### 3.1 Preconditions observed

- Registered `NfcVendorNciCallback` via `registerNfcVendorNciCallback(executor, proxy)`.
- `pausePolling(30000)` → `1` (no retry). ST HAL log confirms real discovery deactivation: `Tx 21 06 01 00 / Rx 41 06 01 00` (RF_DEACTIVATE).
- All vendor commands were executed while paused; `resumePolling()` → `1` afterwards.

### 3.2 ST-proprietary version read (vendor path sanity check) — SUCCESS

```text
NCI_TX  MT=1 GID=15 OID=1 PAYLOAD=0101
VENDOR_RESP GID=15 OID=1 RAW=01000002
NCI_RX  01000002
STPROPNCI_VERSION RAW=0002          (status 0, ST prop-NCI lib version 0x0002)
```

This is answered natively by the ST HAL (no NFCC traffic), proving registration/executor/response matching work on dada.

### 3.3 GET custom polling frames (OID 21) — CONTROLLER REJECTS

```text
NCI_TX  MT=1 GID=15 OID=1 PAYLOAD=15
VENDOR_RESP GID=15 OID=1 RAW=1505
NCI_RX  1505                        (subOID 0x15 echoed, status byte 0x05)
```

Device logcat (HAL service pid 2135):

```text
D/NfcService( 5942): sendVendorNciMessage: gid=0f, oid=01, payload[0]=15
D/StNfcHal( 2135): HAL st21nfc: StNfc_hal_write
D/StNfcHal( 2135): (#01AA2) Tx 2f 1e 00
D/StNfcHal( 2135): (#01AA3) Rx 4f 1e 01 05
D/StNfcOemExtension( 5942): StNfcVendorNciCallback.onVendorNciResponse: PROP:ST_OID:ST_PROP_GET_RF_CUSTOM_POLL_FRAME:1505
```

`2f 1e 00` = NCI CMD, GID 0x0F, OID 0x1E = `ST_NCI_MSG_PROP_RF_GET_CUST_PASSIVE_POLL_FRAME`, empty payload. `4f 1e 01 05` = response with status `0x05`. This is the **controller's own NCI answer** (`i2c` readback), not a HAL/framework invention.

### 3.4 GET_CONFIG params 2/3/4 (RF config) — SUCCESS

Wire (param 2 example): `Tx 2f 02 05 03 00 10 01 02` → `Rx 4f 02 c8 00 01 02 c4 44 00 67 4c ...`
Callback payloads (after status byte; layout `01 <param> C4 <196-byte config with CRC-16/X25 at cfg[2..3]>`):

```text
PARAM=2  RESP=01 02 C4
  4400674C0028992A0028992A0028992A0028992A0020EE220020EE220020EE220020EE220028522B0028522B0028522B0028522B0020EE220020EE220020EE220020EE220028522B0028522B0028522B0028522B0020EE220020EE220020EE220020EE22B72AD52AB72AD52AB72AD52AB72AD52A072320230723202307232023072320236B2B842B6B2B842B6B2B842B6B2B842B072320230723202307232023072320236B2B842B6B2B842B6B2B842B6B2B842B07232023072320230723202307232023
  (196 bytes, CRC ok, APC=5/5/5/5)

PARAM=3  RESP=01 03 C4
  440011B4F22A102BF22A102BF22A102BF22A102B39235223392352233923522339235223892B932B892B932B892B932B892B932B39235223392352233923522339235223892B932B892B932B892B932B892B932B392352233923522339235223392352232E3B4C4B2E3B4C4B2E3B4C4B2E3B4C4B6B2384236B2384236B2384236B238423AC2BC52BAC2BC52BAC2BC52BAC2BC52B6B2384236B2384236B2384236B238423AC2BC52BAC2BC52BAC2BC52BAC2BC52B6B2384236B2384236B2384236B238423
  (196 bytes, CRC ok, APC=5/5/7/9)

PARAM=4  RESP=01 04 C4
  44001B966A5388636A5388636A5388636A5388639D23B6239D23B6239D23B6239D23B623DE2BE32BDE2BE32BDE2BE32BDE2BE32B9D23B6239D23B6239D23B6239D23B623DE2BE32BDE2BE32BDE2BE32BDE2BE32B9D23B6239D23B6239D23B6239D23B623A573C37BA573C37BA573C37BA573C37BCF23E823CF23E823CF23E823CF23E823E52BE82BE52BE82BE52BE82BE52BE82BCF23E823CF23E823CF23E823CF23E823E52BE82BE52BE82BE52BE82BE52BE82BCF23E823CF23E823CF23E823CF23E823
  (196 bytes, CRC ok, APC=10/12/14/15)
```

Full logs: `probe/data/read-run.log`, wire log `probe/data/logcat-read.txt`.

### 3.5 SET 7A custom polling frames — CONTROLLER REJECTS

SET payload used (one `RfFrameEntry` TLV, position=1, rsp_type=1 STD_RESP, frame_type=2 = NFC-A short 7-bit frame, waiting_time=10, frame data 0x7A):

```text
NCI_TX  MT=1 GID=15 OID=1 PAYLOAD=13012A020A7A
VENDOR_RESP GID=15 OID=1 RAW=1305
NCI_RX  1305                        (subOID 0x13 echoed, status 0x05)
```

HAL wire log (the HAL built exactly the expected ST NCI command):

```text
D/NfcService( 5942): sendVendorNciMessage: gid=0f, oid=01, payload[0]=13
D/StNfcHal( 2135): (#018BD) Tx 2f 1d 05 01 2a 02 0a 7a
D/StNfcHal( 2135): (#018BE) Rx 4f 1d 01 05        <-- status 0x05
```

`2f 1d` = `ST_NCI_MSG_PROP_RF_SET_CUST_PASSIVE_POLL_FRAME` (0x1D), payload `01 2a 02 0a 7a` = count 1 + TLV. Attempted disable as well:

```text
NCI_TX  MT=1 GID=15 OID=1 PAYLOAD=1300
VENDOR_RESP RAW=1305               (status 0x05)
HAL: (#018C1) Tx 2f 1d 01 00 / (#018C2) Rx 4f 1d 01 05
```

### 3.6 Interpretation

- `0x05` = `NCI_STATUS_SYNTAX_ERROR` (`refs/android_hardware_st_nfc` `nci_defs.h`). For a GET with empty payload this means the command itself is not implemented/recognised by the NFCC firmware.
- ST HAL source (`refs/android_hardware_st_nfc/libstpropnci/src/stpropnci_prop_st.cc`) maps `ST_PROP_SET_RF_CUSTOM_POLL_FRAME` → NCI `2F 1D ...` and `ST_PROP_GET_RF_CUSTOM_POLL_FRAME` → `2F 1E 00`, both **passthrough to the NFCC** — there is no HAL-side gate that could be worked around from the host.
- Device prop `ro.nfc.st.oldFW=true` (and `NfcService.isStOldFW` read from it, used to select ST RF command variants for MiTouch) is consistent with the ST54L firmware being old enough not to implement custom passive poll frames. `ro.vendor.nfc.customRF=1` / `RF.conf.items=8` concern the stored RF/APC config (which does work — §3.4), not this feature.
- Conclusion: **7A wake via ST vendor-NCI custom polling frames is not available on dada with the current firmware.** Framing/permissions/pause/callback are all proven correct; the controller refuses the command.

### 3.7 Safety outcome (nothing left modified)

- No SET/clear command was accepted (`status=0x05` for every attempt), so no RF config change was ever applied; no snapshot was required and no `ink-probe-snapshot.bin` exists.
- `dumpsys nfc`: `mState=on`, `nfc.initialized=true`; `com.android.nfc` alive (pid 5942); `resumePolling()` returned 1 after every transaction.
- Full logs: `probe/data/set7a-run.log`, `probe/data/logcat-set7a.txt`.

## 4. Frida phase 2 — VERIFIED

Binaries:

- frida-server 17.24.0 (android-arm64) downloaded from GitHub releases (`frida/frida` tag 17.24.0), decompressed with Python `lzma` → `probe/frida/frida-server-17.24.0-android-arm64` (84 805 928 B)
- `frida-inject` extracted from prototype APK asset `assets/frida-inject` → `probe/frida/frida-inject` (84 553 240 B)
- `assets/mifare-compat.js` → `probe/frida/mifare-compat.js`

Device setup (all under /data/local/tmp, root, no writes elsewhere):

```
adb -s [redacted-serial] push probe/frida/frida-server-17.24.0-android-arm64 /data/local/tmp/ink-frida-server
adb -s [redacted-serial] push probe/frida/frida-inject /data/local/tmp/ink-nfc-frida-inject-17.24.0
adb -s [redacted-serial] push probe/frida/mifare-compat.js /data/local/tmp/ink-nfc-mifare-compat.js
adb -s [redacted-serial] shell 'chmod 755 /data/local/tmp/ink-frida-server /data/local/tmp/ink-nfc-frida-inject-17.24.0; chmod 600 /data/local/tmp/ink-nfc-mifare-compat.js'
adb -s [redacted-serial] shell '/data/local/tmp/ink-frida-server --listen=127.0.0.1:27042 --policy-softener=internal --disable-preload --daemonize'
```

Results:

```text
/data/local/tmp/ink-frida-server --version            -> 17.24.0
/data/local/tmp/ink-nfc-frida-inject-17.24.0 --version -> 17.24.0
pidof ink-frida-server                                -> 31811 (running, listening 127.0.0.1:27042)
pidof com.android.nfc                                 -> 5942
```

Injection of the prototype script into `com.android.nfc` (verbatim):

```text
$ timeout 10 /data/local/tmp/ink-nfc-frida-inject-17.24.0 --pid 5942 --script /data/local/tmp/ink-nfc-mifare-compat.js
{"type":"send","payload":"INK_NFC_MIFARE_COMPAT_READY"}
```

So the script's module lookup, symbol resolution, `Interceptor.attach` and `send()` all succeed on dada.

Module + symbol diagnostics (`probe/frida/frida-diag.js`, on-device `/data/local/tmp/ink-nfc-frida-diag.js`; verbatim, pretty-printed):

```json
{"event":"MODULES","count":7,"modules":[
  {"name":"libstnfc_nci_jni.so","path":"/system/app/Nfc_st/Nfc_st.apk!/lib/arm64-v8a/libstnfc_nci_jni.so","base":"0x7743e57000","size":2953216},
  {"name":"libnfc_vendor_extn_st.so","path":"/system/lib64/libnfc_vendor_extn_st.so","base":"0x7720a37000","size":1228800},
  ... (boot-framework-nfc.oat, com.st.android.nfc_extensions_16.odex, com.xiaomi.nfc.odex, Nfc_st classes.dex, libpower.so) ]}
{"event":"MODULE","name":"libstnfc_nci_jni.so","path":"/system/app/Nfc_st/Nfc_st.apk!/lib/arm64-v8a/libstnfc_nci_jni.so","base":"0x7743e57000","size":2953216}
{"event":"EXPORT_COUNT","count":4441}
{"event":"SYMBOL","part":"createNativeNfcTag","count":1,"matches":[{"name":"_ZN6NfcTag18createNativeNfcTagER14tNFA_ACTIVATED","type":"function","address":"0x7743fd30f4"}]}
{"event":"SYMBOL","part":"getNumDiscNtfEv","count":1,"matches":[{"name":"_ZN6NfcTag13getNumDiscNtfEv","type":"function","address":"0x7743fd9a84"}]}
{"event":"SYMBOL","part":"setNumDiscNtfEi","count":1,"matches":[{"name":"_ZN6NfcTag13setNumDiscNtfEi","type":"function","address":"0x7743fd9980"}]}
{"event":"DIAG_OK"}
```

Notes / caveats:

- Unlike the risk anticipated before probing, `Process.getModuleByName('libstnfc_nci_jni.so')` **does work on dada** even though the library is mapped from inside the APK (`.../Nfc_st.apk!/lib/arm64-v8a/libstnfc_nci_jni.so`) — Frida 17.24.0 resolves the linker-provided zip path name.
- The 3 symbols are C++-mangled but the script filters by substring, and each substring matches exactly **one** export — so the script's uniqueness assertion passes.
- Hook signature is structurally compatible: `NfcTag::createNativeNfcTag(tNFA_ACTIVATED&)` is a member function → `args[0]=this`, `args[1]=&activation` as the script assumes.
- **Not** verified: whether the script's MIFARE activation byte pattern (`010400040004` prefix / `00000000000028` suffix) and `getNumDiscNtfEv==1` heuristic actually match dada's `tNFA_ACTIVATED` payloads — that requires physically tapping a real MIFARE tag/board. Treat "injection + hooks install + READY" as verified; "MIFARE compat actually intercepts the target tag" as unverified.
- frida-server was left running (loopback only). Temp files: `/data/local/tmp/ink-frida-server`, `/data/local/tmp/ink-nfc-frida-inject-17.24.0`, `/data/local/tmp/ink-nfc-mifare-compat.js`, `/data/local/tmp/ink-nfc-frida-diag.js`, `/data/local/tmp/inkprobe/`.
- The eternalized compat hook self-detaches after the script's 240 s timer; no persistent hooks were left behind.

## 5. Recommendations for the app implementation

1. **Keep the prototype's reflection/adapter recipe** — it is proven on dada: fallback `NfcServiceManager` install + `ActivityThread.systemMain()/getSystemContext()` + `NfcAdapter.getDefaultAdapter()`. Add the `ServiceManager("nfc")` + `INfcAdapter$Stub.asInterface` path as a documented fallback (also works).
2. **Replace the device whitelist (`DeviceSupport.verifiedRootProfile`: only pudding/SDK 37) with a runtime capability probe.** This is the cleanest fix for dada:
   - acquire adapter, register vendor-NCI callback, `pausePolling()` (must return 1, retry on 2), then
   - `sendVendorNciMessage(1,15,1,{0x15})` (GET custom poll frames) with 0x13 disable as cleanup, then `resumePolling()`.
   - `status == 0` → custom poll frames supported (7A path allowed); `status == 0x05` (or anything else) → unsupported → **do not** attempt 7A and keep the standard ISO-DEP path. Persist the result per device/FW.
3. **On dada (current FW): 7A wake is not possible via vendor NCI.** The app should not advertise "7-bit 7A wake" for dada. The fallback is the already-implemented standard ISO-DEP path for a board that is already powered.
4. **Optional, high risk, needs explicit user decision:** ST54L firmware update (HAL supports `ST_NCI_PROP_NFC_FW_UPDATE`; `/odm/firmware/st54l_fw.bin` exists and HAL config points at it), which *might* add custom poll frame support. Risks: can brick NFC/eSE/wallet, may need vendor signing, irreversible. Not attempted and out of probe scope.
5. **Frida/MIFARE compat:** keep it; the module name, symbols and injection all work on dada (APK-embedded lib resolves by name). Still, make the script robust: fall back to `Process.enumerateModules()` filtering for a module whose path contains `libstnfc_nci_jni.so` / `Nfc_st.apk!/lib` if `getModuleByName` ever fails, and fail loudly with the list of loaded modules if a symbol isn't found. frida-inject/frida-server **17.24.0 are mandatory** (both verified).
6. **Root/tooling:** vendor NCI requires root (NFC service enforces `NfcPermissions.enforceAdminPermissions`; uid 0 passes). The prototype's `su -c` + `app_process` + `CLASSPATH=<dex/apk>` invocation works on dada unchanged. Keep `pausePolling/resumePolling` around every config transaction; never write RF config without a prior read (reads work, so a proper backup is possible if RF writes are ever attempted).
7. **Report honesty:** the prototype's own `READY` text already says RF emission is not independently confirmed. Even on a custom-poll-frame-capable device, "SET accepted + GET readback" proves controller configuration, not that the 7A frame was actually emitted on air; that needs a physical sniffer/board.

## 6. Commands used (condensed)

Build:

```powershell
javac --release 11 -classpath D:\Apps\AndroidSDK\platforms\android-36.1\android.jar -d probe\build\classes probe\src\dev\inkprobe\Probe.java
d8.bat --min-api 26 --lib D:\Apps\AndroidSDK\platforms\android-36.1\android.jar --output probe\build\out <all .class files>
```

Run on device (root; plain `adb shell` is already uid=0 here, `su -c` also works):

```sh
adb -s [redacted-serial] shell "su -c 'sh /data/local/tmp/inkprobe/run.sh info'"
adb -s [redacted-serial] shell "su -c 'sh /data/local/tmp/inkprobe/run.sh read'"
adb -s [redacted-serial] shell "su -c 'sh /data/local/tmp/inkprobe/run.sh set7a'"   # pause → GET → SET 7A → GET → SET clear → resume
```

Logcat capture: `adb -s [redacted-serial] logcat -c` before, `adb -s [redacted-serial] logcat -d -v brief` after (filter `StNfcHal`, `NfcService`, `StNfcOemExtension`).

Frida: see §4.

## 7. Artifacts (all under `probe/`)

| Path | Contents |
|---|---|
| `probe/src/dev/inkprobe/Probe.java` | probe source (modes: `info`, `read`, `set7a`, `restore`) |
| `probe/build/out/classes.dex` | built dex (pushed to `/data/local/tmp/inkprobe/classes.dex`) |
| `probe/run.sh` | on-device launcher (`app_process`) |
| `probe/framework-nfc.jar` | pulled `/system/framework/framework-nfc.jar` (contains `android.nfc.NfcAdapter` etc.) |
| `probe/framework-nfc-dump.txt` | dexdump disassembly of `framework-nfc.jar` |
| `probe/nfcapp.apk`, `probe/nfcapp-dump.txt` | pulled `/system/app/Nfc_st/Nfc_st.apk` + dexdump of `classes.dex` (NfcService) |
| `probe/libstnfc_nci_jni.so`, `probe/libnfc_vendor_extn_st.so`, `probe/nfc_nci.st21nfc.st.so` | pulled ST libs used for symbol/FW analysis |
| `probe/stnfc/`, `probe/stnfc-dump.txt` | `com.st.android.nfc_extensions_16` dex + disassembly |
| `probe/data/info-run.log` | reflection/api presence run (verbatim) |
| `probe/data/read-run.log`, `probe/data/logcat-read.txt` | read-only run + wire logcat |
| `probe/data/set7a-run.log`, `probe/data/logcat-set7a.txt` | SET/clear attempts + wire logcat |
| `probe/data/frida-diag.log` | Frida module/symbol diagnostics (verbatim) |
| `probe/data/frida-inject-ready.log` | inject READY proof |
| `probe/data/mfg-run.log` | live ST54L chip/FW version read (`mode=mfg`) |
| `probe/data/testcmd-run.log`, `probe/data/logcat-testcmd.txt` | ST PROP_TEST channel + non-prop NCI run + wire logcat |
| `probe/data/nci-run.log`, `probe/data/nci-run2.log`, `probe/data/logcat-nci2.txt` | clean read-only CORE_GET_CONFIG (`mode=nci`) + authoritative NCI wire traces |
| `probe/data/frida-ready-phase2.log`, `probe/data/frida-diag-phase2.log` | re-verification of frida-server + injection + module/symbols |
| `probe/fw/` | pulled/remote NFC artifacts for FW/config comparison (HAL conf, HAL service binary, remote LineageOS copies, `tree.json`, `miaomo-COMPATIBILITY.md`) |
| `probe/frida/` | frida-server/inject binaries, `mifare-compat.js`, `frida-diag.js` |
| `probe/tools/dexparse.py` | minimal dex parser used because androguard 4.1.4 fails on Android 17 hiddenapi dex |

## 8. Follow-up experiment: ST FW TEST channel + arbitrary NCI (2026-10-30, second session)

Motivation: the 0x1D/0x1E custom-poll-frame rejection left one software-only question open — does dada's FW expose the ST *test* command channel (oid=3 `ST_NCI_MSG_PROP_TEST`), which could carry a raw RF test frame without any FW update? ST HAL source (`refs/android_hardware_st_nfc/libstpropnci/src/stpropnci_prop_st.cc`) shows oid=3 is not handled by the prop library itself: the `switch (oid)` has cases for `ST_PROP_NCI_OID` (1) and `ST_NCI_MSG_PROP` (2) only, and the `default:` branch logs "unexpected but let it passthrough" → every oid=3 command is forwarded to the NFCC as NCI `2F 03 <len> <subOID>...`.

All commands below ran with `pausePolling(30000) -> 1` (discovery deactivated) and `resumePolling() -> 1` afterwards; final state `dumpsys nfc: mState=on`, `nfc.initialized=true`, `com.android.nfc` alive (pid 5942). Nothing persistent was written and the RF field was left OFF (see 0xB3).

### 8.1 ST PROP_TEST commands (oid=3)

```text
san (control) sendVendorNciMessage(1,15,1,{0x01}) -> callback 01000002  (ST prop lib 0x0002; HAL-native reply, no NFCC wire traffic)
neg (control) sendVendorNciMessage(1,15,1,{0x7F}) -> callback 7F0B (HAL default NOT_SUPPORTED 0x0B; HAL-native)

0x07 LOADER_PRODUCT_INFO   payload {0x07}
  wire Tx 2f 03 01 07
  wire Rx 4f 03 3e | payload 00 3c 03 02 01 a0 00 00 00 00 6a fc 40 51 f6 98 11 00 75 02 01 00 00 00 00 00 00 00 43 ...
  callback 003C030201A0000000006AFC4051F698110075020100000000000000430000000000000000000000FD00877D31E3CF0CD36800000030505AA2DF03E69CE7
  => {status=0x00, len=0x3C, 60-byte product/loader info} — SUCCESS

0xB0 TEST_READER_CFG       -> Tx 2f 03 01 b0 -> Rx 4f 03 01 05 -> 05
0xB1 TEST_CARD_CFG         -> Tx 2f 03 01 b1 -> Rx 4f 03 01 05 -> 05
0xB5 TEST_RFI_GET          -> Tx 2f 03 01 b5 -> Rx 4f 03 01 05 -> 05
0xC9 TEST_GET_MEASUREMENT  -> Tx 2f 03 01 c9 -> Rx 4f 03 01 05 -> 05
0xCD TEST_ANT_DIAG_V3      -> Tx 2f 03 01 cd -> Rx 4f 03 01 05 -> 05
  (0x05 = NCI STATUS_SYNTAX_ERROR; empty-payload variants not accepted — these commands likely
   need parameter payloads, or are not implemented on this FW. Not attempted with guessed payloads.)
```

### 8.2 TEST_FIELD 0xB3 (only run because 0x07 returned data; stopped before TEST_DATA per instruction)

```text
0xB3,0x00 -> Tx 2f 03 02 b3 00 -> Rx 4f 03 01 00 -> status 0x00   (field OFF accepted)
0xB3,0x01 -> Tx 2f 03 02 b3 01 -> Rx 4f 03 01 00 -> status 0x00   (field ON accepted)
0xB3,0x00 -> Tx 2f 03 02 b3 00 -> Rx 4f 03 01 00 -> status 0x00   (field back OFF; final state OFF)
```

**NOT attempted** (deliberately, per instruction): `TEST_DATA` 0xB2, `TEST_DATA_EXTENDED` 0xBB, `PROD_TEST_V3` 0xCC, `LOOPBACK` 0xCF. The ST HAL source contains only the numeric constants (`stpropnci_prop_st.h`), no payload layout — the 0xB2 frame format lives in the ST54L FW test specification, so blind writes were refused.

### 8.3 Non-prop NCI through the vendor path — carried verbatim and answered

The vendor path is not restricted to prop GID: the JNI/HAL writes the NCI header derived from `(mt,gid,oid)` verbatim and the NFCC answers.

```text
gid=0, oid=0x03 (CORE_GET_CONFIG, NCI 2.0: Param_ID + 2-byte length TLVs), read-only:
  param 0x00: Tx 20 03 01 00 -> Rx 40 03 02 00 00          -> callback 0000
  param 0x01: Tx 20 03 01 01 -> Rx 40 03 06 00 01 00 02 F4 01 -> callback 00010002F401
              ({status=00, param=01, len=0002, value=F4 01})
  param 0x02: Tx 20 03 01 02 -> Rx 40 03 04 09 01 01 00    -> callback 09010100   (status 0x09 INVALID_PARAM)
  param 0x03: Tx 20 03 01 03 -> Rx 40 03 04 09 01 01 00    -> callback 09010100
  gid=1, oid=0x02 (RF_GET_LISTEN_MODE_ROUTING): Tx 21 02 01 00 -> Rx 41 02 01 00 -> status 0x00

Earlier accidental variant (documented for honesty): gid=0 OID 0x02 is NCI CORE_SET_CONFIG,
not GET_CONFIG (ST extension log labels it NCI_MSG_CORE_SET_CONFIG). Sending {0x00}/{0x01}/{0x02}
as a lone byte produced Tx 20 02 01 xx -> Rx 40 02 02 00 00 / 40 02 03 09 01 00 / 40 02 03 09 01 01;
no value was supplied, no error surfaced and NFC state stayed healthy. It still proves passthrough.
```

### 8.4 Verdict for the software-only route

- Test/loader channel **reachable** (`0x07` returns real data; `0xB3` accepted).
- The only confirmed-unavailable feature remains the **custom passive poll frame** pair (0x1D/0x1E).
- Because raw NCI is carried through, the plausible software route to emit the 7-bit 0x7A frame without a FW update is `ST_NCI_PROP_TEST_DATA` **0xB2** (or 0xBB extended). That needs the ST54L test-frame payload format (not in the public HAL sources). One conservative probe was suggested to the Lead; it was **not executed** in this session.
- 0xB0/0xB1/0xB5/0xC9/0xCD are probably parameterised; empty-payload probes all returned 0x05, which is not conclusive of lack of support.

## 9. FW / config / ROM comparison (read-only)

### 9.1 Live device identity (read with the probe, not from props)

`sendVendorNciMessage(1,15,1,{0x02})` = `ST_PROP_NCI_GET_MANUF_DATA_SUBOID` → callback:

```text
MFG_RAW 0200060302041188030201004470BF0000CA3D000000000000919801
MFG_STATUS status=0 len=26
MANUF_HEX 060302041188030201004470BF0000CA3D000000000000919801
CHIP  idByte=6 chip=ST54L siliconRev=3
FW_VERSION 02.04.1188
CUSTOMER_DATA 0000919801
```

Layout follows `ManufacturerData.java` (`_device/stnfc-src`): `HW=[0..1]`, `FW=[2..5]`, `customer=[21..]`. So dada is **ST54L, silicon rev 3, FW 02.04.1188** — the same FW string as the Xiaomi 15 Pro (haotian) record in `3dhaoxinren/miaomo` docs (§9.4). Note `9198` = config-script CRC and `CA3D` = RF-parameter CRC from `/odm/etc/st54l_conf.txt` header — i.e. the device is running exactly the Rev.C script we hashed (§9.2).

### 9.2 Device file hashes (SHA-256)

| File | Size | SHA-256 |
|---|---|---|
| `/odm/firmware/st54l_fw.bin` (local `_device/st54l/st54l_fw.bin`) | 250 917 | `15DA4AA955C9F8E692C81EC94EBC44CA71385C31991F57D2842C577C40C8E9DC` |
| `/odm/etc/st54l_conf.txt` | 6 759 | `9A93D8D6DDAF71EDE2AA9272ADC51E1649134265A003489B409D5372089DBC83` |
| `/odm/etc/libnfc-hal-st.conf` (`probe/fw/libnfc-hal-st.conf`) | 6 282 | `045029D15B53DF23BCD63B238EA4E4C85BD56AF2C1B9F76D880022437815AACD` |
| `/odm/bin/hw/android.hardware.nfc-service-st` (`probe/fw/`) | 86 376 | `3A7ABEF823B2D3001712ADF831B2223AEA7F33C03494FA1B955CA55242C2F83D` |
| `/odm/lib64/nfc_nci.st21nfc.st.so` (`probe/`) | 200 560 | `DB667826560E95600136EAAB2552CAD2FF98690CE2032C902CB1CA82102D80C6` |
| `libstnfc_nci_jni.so` (inside `Nfc_st.apk`) | 2 947 928 | `201E8EE8ADFD6DD65633C19E5D49A08C79EDE003358E8A4CD30C79BD21D1906F` |
| `/system/lib64/libnfc_vendor_extn_st.so` | 1 230 224 | `050C0EC44188066624292A52A5A176B34B2C6EA54FA45FBAD24CA8BED45F8284` |

ELF build IDs: HAL service `3eef61542ac96e5544776d73d5ba3c79`; `nfc_nci.st21nfc.st.so` `ee81798c9f6c59376b2bee0ca7337893`; `libstnfc_nci_jni.so` `256b114a6fcb48433165b5a58f563c56`; `libnfc_vendor_extn_st.so` `578b50390433cd246b8e469b3f330bab`.

NFC package: `com.android.nfc` → `codePath=/system/app/Nfc_st`, `versionName=17`, `versionCode=37`, `targetSdk=37`. HAL interface: AIDL `android.hardware.nfc` `INfc/default` (+ `android.hardware.secure_element` `ISecureElement/eSE1`), from `/odm/etc/vintf/manifest/manifest_nfc_ese.xml`. ST prop-NCI lib version: `0x0002`.

### 9.3 Comparison with public LineageOS device trees (dada-devel)

- `https://github.com/dada-devel/vendor_xiaomi_sm8750-common` (default branch `lineage-23.2`, ~488 MB, Git LFS blobs)
- `https://github.com/dada-devel/vendor_xiaomi_dada` (same branch), plus `vendor_xiaomi_dada-firmware`, `device_xiaomi_dada`, `device_xiaomi_sm8750-common`

Results (files fetched via GitHub contents/raw API + LFS media endpoint, hashed locally):

| File | Device SHA-256 | LineageOS-23.2 tree SHA-256 | Match |
|---|---|---|---|
| `proprietary/odm/etc/libnfc-hal-st.conf` | `045029D1…15AACD` | `045029D1…15AACD` | **identical** |
| `proprietary/odm/etc/st54l_conf.txt` | `9A93D8D6…9DBC83` | `9A93D8D6…9DBC83` | **identical** |
| `proprietary/odm/bin/hw/android.hardware.nfc-service-st` | `3A7ABEF8…C2F83D` | `3A7ABEF8…C2F83D` | **identical** |
| `proprietary/odm/lib64/nfc_nci.st21nfc.st.so` (LFS) | `DB667826…102D80C6` | `DB667826…102D80C6` | **identical** |
| `proprietary/odm/firmware/st54l_fw.bin` | `15DA4AA9…C8E9DC` | — (file absent from both dada trees) | n/a |

The LineageOS dada vendor trees' NFC files are byte-identical to our device (so the public tree is extracted from the same/very similar stock ROM), but **`st54l_fw.bin` is not redistributed in either tree** — neither as a real blob nor as an LFS pointer (the only `/odm/firmware` LFS pointers are unrelated camera/Synaptics/wmfw files). There is therefore **no public reference SHA-256 for the ST54L FW package**; the FW is proprietary and not in the LineageOS blobs, which also means a "newer FW" cannot be validated from public trees.

### 9.4 ROM version / newer-firmware search (evidence only, nothing downloaded/flashed)

- Current device ROM: fingerprint `Xiaomi/dada/dada:17/CP2A.260605.016/OS4.0.0.22.XOCCNXM:user/release-keys`; incremental `OS4.0.0.22.XOCCNXM`; vendor build date `Fri Oct 16 17:04:20 CST 2026`; Android 17. This is already **HyperOS 4.0** (`ro.mi.os.version.name=OS4.0`), build date 2026-10-16 (≈2 weeks old at probe time).
- Public evidence of dada HyperOS 4 builds: onfix ROM listings for China Beta `OS4.0.0.6.XOCCNXM` and `OS4.0.0.8.XOCCNXM` (Android 17), i.e. **older** than our `4.0.0.22`; 4pda Xiaomi 15 firmware thread; Weibo HyperOS 4 beta OTA posts. (https://onfix.cn/rom/1062141, https://onfix.cn/rom/1063652, https://4pda.to/forum/lofiversion/index.php?t1103309-5320.html)
- `xiaomifirmwareupdater.com` is currently a parked/for-sale domain and its API endpoints return an ad page, so it could not be used to enumerate the latest ROM. No public source found listing a dada ROM newer than OS4.0.0.22, and no public source at all publishing a `st54l_fw.bin` for comparison.
- The FW config package on device is `AppTool 1.13`, `Script Date 1/15/2026`, `Device DB14`, `Version Rev.C`, `Script CRC 9198`, `RF parameter CRC CA3D`. The `9198`/`CA3D` CRCs are visible in the live manufacturer data (§9.1), so the loaded RF config corresponds to this script.
- Comparison point from the community: `3dhaoxinren/miaomo` `docs/COMPATIBILITY.md` reports Xiaomi 15 Pro (`haotian`, Android 16/HyperOS `OS3.0.309.0`) as **ST54L FW `02.04.1188`**, with the old bridge command returning **`15 0B`** (NOT_SUPPORTED), i.e. the same FW version string as dada although its host HAL generation is older. dada returns `15 05` (SYNTAX_ERROR) — both are refusals, but they come from different layers (dada's HAL forwards to the NFCC which answers SYNTAX_ERROR; the older haotian stack answered NOT_SUPPORTED earlier in the chain).

### 9.5 Risk assessment: ST54L FW update via the HAL (`NCI_PROP_NFC_FW_UPDATE`)

Read-only analysis from `refs/android_hardware_st_nfc` (`st21nfc/hal/hal_fd.cc`, `hal_wrapper.cc`, `libstpropnci/src/stpropnci_prop_st.h`):

- The ST HAL automatically compares **file** vs **chip** versions at HAL open: `ft_cmd_HwReset()` parses `/odm/firmware/st54l_fw.bin` (configured via `STNFC_FW_BIN_NAME` in `libnfc-hal-st.conf`) and logs `Firmware update needed from 0x%08X to 0x%08X` when `fileFwVersion != chipFwVersion`; it also compares the custom-parameter version (`st54l_conf.txt`) and may schedule `CONF_UPDATE_NEEDED`.
- The update itself uses `NCI_PROP_NFC_FW_UPDATE` (0x06 under `ST_NCI_MSG_PROP`) in a 100 ms-timer command stream, can run in loader or router mode, and has a retry budget (`mRetryFwDwl`); on exhaustion it logs `Reached maximum nb of retries, FW update failed, exiting` and reports `HAL_NFC_OPEN_CPLT_EVT / HAL_NFC_STATUS_FAILED` — the NFC service then fails to open the controller (NFC unusable until recovery/repair). Related recovery infrastructure exists (`ro.vendor.nfc.repair=1`, `libstnfc-streset.so`, `/vendor/lib64/libstnfc-streset.so`, reset GPIO), but it is vendor-controlled.
- **Therefore flashing/forcing a FW package is high risk**: a mismatched, corrupted or power-interrupted update can leave the controller in loader mode / brick the NFC+eSE+wallet path; the FW is signed/proprietary (no public hash or image to compare), there is no user-side rollback guarantee, and an OTA may overwrite/revert it. Recommendation: do **not** attempt a FW update without an ST/Xiaomi-signed image, a verified recovery path, and explicit user approval; the software test-channel route (§8) is the safer next experiment.

## 10. Physical e-ink panel test with TEST_DATA (0xBB) — 2026-10-30 14:57

Follow-up to §8. Question: if the ST54L test channel's `TEST_DATA`/`TEST_DATA_EXTENDED` can transmit a raw 106 kbps NFC-A frame, the 7-bit `0x7A` wake frame could be sent without any FW update. The dry runs showed the raw `0xB2` forms are not usable (see below), so the panel run used only the `0xBB` candidates whose syntax had returned status 0x00.

### 10.1 Harness and conditions

- Probe mode `td2` (single candidate, restart-aware): wait until the vendor path is healthy (version read) → register the vendor-NCI callback → `pausePolling(30000)` (returned 1) → `TEST_FIELD` ON `{0xB3,0x01}` → send the candidate with `sendVendorNciMessage(1,15,3,<payload>)` → `TEST_FIELD` OFF `{0xB3,0x00}` → `resumePolling()` (returned 1).
- Driver: `probe/run-testdata-panel.ps1`, which also clears and captures logcat per run.
- The operator held the Huawei 552×768 4-colour e-ink driver board flat on the phone's NFC area for the whole run (14:57:00–14:57:13, 13 s total). Each candidate was sent 3× (initial + 2 repeats in `TD2_REPEAT`).
- Request wire is the NCI packet the HAL writes: `<hdr 2f> 03 <len> <payload>`; response wire is `4f 03 01 00` = payload `{0x00}` (status).

### 10.2 Candidates, wire, status, latency

| # | payload (host) | wire TX | wire RX | status | latency (panel) | latency (dry run, no panel) |
|---|---|---|---|---|---|---|
| 1 | `BB 02 01 7A 00` | `2f 03 05 bb 02 01 7a 00` | `4f 03 01 00` | 0x00 | 3 ms | 5 ms |
| 2 | `BB 02 01 7A` | `2f 03 04 bb 02 01 7a` | `4f 03 01 00` | 0x00 | 3 ms | 3 ms |
| 3 | `BB 01 7A` | `2f 03 03 bb 01 7a` | `4f 03 01 00` | 0x00 | 708 ms | 705 ms |
| 5 | `BB 01 01 7A` | `2f 03 04 bb 01 01 7a` | `4f 03 01 00` | 0x00 | 706 ms | 4 ms (unreliable — see note) |

All four candidates returned **status 0x00** on the initial send and on both repeats (`TD2_REPEAT` REP=1/REP=2 → `RX=00`, status 0x00). `TEST_FIELD` ON/OFF also returned status 0x00; pause/resume returned 1.

Timing note: candidates 1/2 answer in 3–6 ms in both runs. Candidate 3 took ~705–708 ms **identically with and without the panel**. The dry-run candidate-5 value (4 ms) is not trustworthy: its `TEST_FIELD` ON captured a stale response (`RX=01000002`, `OK=false`) immediately after the previous `0xB2` candidate had crashed the NFC stack and triggered a restart; the panel-run value (706 ms) is the more meaningful one. **In no case does the panel run show a timing change attributable to the board.**

### 10.3 Logcat evidence

- No `RF_INTF_ACTIVATED`, `RF_INTF_DISCOVERED`, tag/presence/ATS or other tag-related event occurs anywhere in the panel run; only the usual pause/resume and discovery bookkeeping lines (plus unrelated system/app noise) are present.
- The `6f 02` FW-debug trace records that contain `7a` fragments appear in **both** the panel run and the no-panel dry run (e.g. dry run: `Rx 6f 02 24 31 20 00 0a 16 00 01 7a 03 bb 01 7a 01 01 …`; panel run: `Rx 6f 02 24 31 20 00 0a 16 00 02 01 7a 00 02 01 7a 00 02 …`). They echo the TEST_DATA command in the FW trace, and are not evidence of a panel/tag answer; no additional `6f 02` records attributable to the panel were found.
- The probe's vendor-NCI callback received only `GID=15 OID=3 RAW=00` (status) for the candidates — no response payload beyond the status byte, and no vendor NTFs (`VENDOR_NTF` count 0).

### 10.4 Why 0xB2 was excluded

In the no-panel dry run, the `0xB2` forms `{B2,7A}`, `{B2,01,7A}`, `{B2,01,01,7A}`, `{B2,02,01,7A,00}`, `{B2,01,7A,00}` did **not** return a status at all: the HAL wrote the command (e.g. `Tx 2f 03 02 b2 7a`, `Tx 2f 03 04 b2 01 7a 00`), the NFCC never answered, the JNI reported `nfcManager_nativeSendRawVendorCmd: timeout`, and the stack watchdog fired `NFA_DM_NFCC_TIMEOUT_EVT; abort` → `restartStack: Restarting NFC Service`. NFC auto-recovers, but every such attempt temporarily disables NFC, so those forms were deliberately not used in the physical-panel run. (Note this does **not** prove 0xB2 is transmit-only in a way that would work with a board present — it only proved the command is not answered in the no-tag case.)

### 10.5 Verdict

- `0xBB TEST_DATA_EXTENDED` payloads are **syntactically accepted** (status 0x00, repeatable) — the ST test channel itself still works.
- With the physical e-ink driver board held on the phone throughout, there is **no evidence of a 106 kbps Type-A frame exchange or any board response**: no RF activation/discovery/tag event, no data beyond the status byte, no timing difference vs the no-panel dry run.
- This is **inconclusive for "a frame was transmitted"** — a 7-bit wake frame may be emitted without the board answering, and proving either way needs an RF sniffer or board-side logging. It is **not proof that nothing was transmitted**.
- It is, however, **not a usable route as-is**: the actual `TEST_DATA`/`DATA_EXTENDED` payload semantics (which byte encodes length/frame-format/waiting, and what mode is required) remain unknown, and the syntactically accepted variants produce no observable effect on the panel.
- To settle it one would need the ST54L FW test/NCI payload specification (or a reference implementation/capture), plus an RF sniffer or the board's own debug UART; an NFC test tool in DTA mode would be an alternative. Not pursued further in this probe.

### 10.6 Cleanliness

- The run ended with `TEST_FIELD` OFF status 0x00 and `resumePolling -> 1`; `dumpsys nfc` shows `mState=on`, `nfc.initialized=true`, `com.android.nfc` alive; RF field left OFF.
- No persistent config was written (no RF config / polling-frame / FW writes), and no `/data/local/tmp/ink-probe-snapshot.bin` exists.
- Artifacts: `probe/data/panel-run-20261030-145700.log` (probe events), `probe/data/panel-logcat-20261030-145700.txt` + `panel-logcat-filtered-20261030-145700.txt` (logcat), plus the dry-run syntax map `probe/data/td2-{1..8}.log`, `probe/data/logcat-td2-map.txt`, `probe/data/logcat-testdata-dryrun.txt` and the harness `probe/run-testdata-panel.ps1`.


