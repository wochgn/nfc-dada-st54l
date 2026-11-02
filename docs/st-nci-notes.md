# ST54L / ST21NFC vendor-NCI notes for the Huawei e-ink panel path

Status: compiled 2026-10-30 from **primary sources** (ST NFC HAL source, the ST Android NFC extension
framework `stnfc.jar` decompiled from the device, the prior `dev.inknfclab` prototype decompiled from
the APK, AOSP docs/source, and real device logs). Evidence is tagged:

* **[SRC]** — directly read in source/bytecode (cite).
* **[DEV]** — observed on a real phone (logcat / issue report).
* **[HYP]** — inference or hypothesis; must be validated empirically.

Pinned sources:

| What | Where | Version |
|---|---|---|
| ST NFC HAL (`platform/hardware/st/nfc` LineageOS mirror) | `refs/android_hardware_st_nfc/` | `7cf8d50` (lineage-24.0) |
| ST Android NFC extension framework (device) | `_device/framework/stnfc.jar` → class `com.st.android.nfc_extensions_16.StNfcOemExtension`; bytecode dump `refs/_work/stnfc-dump-full.txt` | from prototype device (Android 17 / ST54L) |
| Prior prototype | `_device/inknfclab-src/sources/dev/inknfclab/`; `_device/inknfclab.apk` | 0.5.0 |
| AOSP Android proprietary NCI commands doc | `refs/_work/aosp-nci-doc.txt` (saved copy) and <https://source.android.com/docs/core/connect/android-proprietary-nci-commands> | current (2026-10-09) |
| AOSP `NfcAdapter.java` source | `refs/_work/aosp-NfcAdapter.java` (<https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/main/framework/java/android/nfc/NfcAdapter.java>) | main |
| Upstream app Q-Poll findings | `upstream-v217/` + GitHub issues #3/#5/#10 | v2.17.0 |
| Real device logcat (dada) | `_device/logcat-all.txt` (lines ~216k–262k) | 2026-10-30 14:15–14:17 |

---

## 1. ST54L stack / how a third-party app can inject vendor NCI

Two distinct ST code layers exist and must not be confused:

1. **ST HAL** (`libstnfc-nci` + `libstpropnci`), runs in the NFC HAL process.
   It owns the NCI transport to the ST54L NFCC and implements the **ST proprietary NCI** channel
   between the Android NFC stack / ST extension service and the NFCC firmware.
2. **ST Android NFC extensions** (`stnfc.jar`, package `com.st.android.nfc_extensions_16`), runs in
   `com.android.nfc` / system process. It exposes the Java-side helpers and the
   `NfcVendorNciCallback` bridge. `StNfcOemExtension` is the singleton glue:
   - `register(Context, StNfcOemExtensionVendorNtfCallback)` and
     `NfcAdapter.registerNfcVendorNciCallback(Executor, NfcAdapter$NfcVendorNciCallback)` **[SRC]**
     (`refs/_work/stnfc-dump-full.txt` `doRegister()` section),
   - `sendVendorNciMessage(byte[] payload, int timeout)` / internal
     `sendVendorCommand(gid, oid, firstPayloadByte, addFirstByte, payload)` which calls the framework
     `NfcAdapter.sendVendorNciMessage(int, int, int, byte[])` and waits for
     `onVendorNciResponse(gid, oid, data)` **[SRC]**.
   - `setRfCustomPollingFrames(byte[])`, `getRfCustomPollingFrames()`, `getNciConfig(int)`,
     `setNciConfig(int,byte[])`, `sendPropGetConfig(int,int)`, `sendPropSetConfig(int,int,byte[])`,
     `applyPropRfConfig()` (**all hidden**; callable via reflection if the class is present) **[SRC]**
     (`stnfc-dump-full.txt`, methods `setRfCustomPollingFrames`/`getRfCustomPollingFrames`/...).

The prior prototype (validated on Xiaomi 17 / Android 17 / ST54L, codename *pudding*) used the raw
framework path only:

```
NfcAdapter.class.getMethod("sendVendorNciMessage", int.class, int.class, int.class, byte[].class)
    .invoke(adapter, 1 /*handle*/, 0x0F /*GID*/, oid, payload)
NfcAdapter.class.getMethod("registerNfcVendorNciCallback", Executor.class, Class.forName("android.nfc.NfcAdapter$NfcVendorNciCallback"))
NfcAdapter.class.getMethod("getNfcOemExtension").invoke(adapter).pausePolling(30000L) / resumePolling()
```
`_device/inknfclab-src/sources/dev/inknfclab/RootProbe.java:150-182,229-239,408-428` **[SRC]**

Whether `sendVendorNciMessage` / `NfcVendorNciCallback` / `getNfcOemExtension` exist and work on the
**Xiaomi 15 (dada)** is device/ROM specific and must be verified with the probe (that is exactly what
task-1/st-probe is testing). `_device/framework/framework.jar` is **not** a usable reference for this:
it is a host/Ravenwood test framework jar (no `android.nfc.NfcAdapter` vendor strings), so API presence
must be checked on-device (reflection / `dumpsys` / decompiled `framework-nfc.jar` of the phone).
**[HYP for dada]**

**Pause polling before any vendor config write.** The prototype always bracketed config commands with
`pausePolling()`/`resumePolling()` and snapshotted the original values before changing them; restore was
verified by read-back (`RootProbe.java:408-428,481-509,880-905`) **[SRC]**. Never leave RF config /
polling frames modified — restore and verify, or `svc nfc disable/enable` as last resort
(`RootProbe.java:464-479`).

---

## 2. ST proprietary NCI channel: GID / OID / payload formats

All ST-vendor traffic uses **GID 0x0F**. The first payload byte selects the ST OID; for OID 0x01 the
**second payload byte is the sub-OID**. Confirmed by decompiling `StNfcOemExtension` and by the ST HAL
dispatcher (`refs/android_hardware_st_nfc/libstpropnci/src/stpropnci_prop_st.cc:250-374`).

### 2.1 OID map (ST54L firmware / HAL)

| GID | OID | Name | Notes |
|---|---|---|---|
| 0x0F | **0x01** | `ST_PROP_NCI_OID` (extension service ↔ HAL) | payload = `[subOID][subOID data]`; RF custom poll frames: sub `0x13` set / `0x15` get; many other subOIDs `0x00..0x26` (`stpropnci_prop_st.h:175-257`) |
| 0x0F | **0x02** | `ST_NCI_MSG_PROP` (host ↔ NFCC firmware) | sub `0x02` set NFC mode, `0x03` GET_CONFIG, `0x04` SET_CONFIG, `0x0A` APPLY_RF_CONFIG, `0x20` LOG (`stpropnci_prop_st.h:47-68`) |
| 0x0F | 0x03 | `ST_NCI_MSG_PROP_TEST` | factory/test commands |
| 0x0F | 0x04 | `ST_NCI_MSG_PROP_LOADER` | firmware loader |
| 0x0F | 0x05/0x06 | power-monitor NTFs | RW on/off |
| 0x0F | **0x0C** | `NCI_MSG_PROP_ANDROID` (= `ANDROID_PROP_OID` 12 in `stnfc.jar`) | Android proprietary commands, e.g. `0x09` `NCI_ANDROID_SET_POLLING_LOOP_ANNOTATION` (`stpropnci_prop_st.h:175`; AOSP doc) |
| 0x0F | 0x0F | `ST_DEBUG_CONF_OID` | debug config |
| (GID 0x0F, OID) | **0x1D / 0x1E** | firmware custom passive poll frame set/get | what ST HAL sends to the NFCC after translating the extension/Android request (`stpropnci_prop_st.h:142-144`) |

### 2.2 Response formats (important, they differ)

From `StNfcOemExtension.exchangeVendorCmdRsp` bytecode **[SRC]** (`refs/_work/stnfc-dump2.txt:282-345`),
verified against prototype behavior (`RootProbe.java:169-178`):

* **OID 0x01 (long/RSP-with-subOID format):** `response = [subOID echo][status][data…]`; status byte
  `0x00` = success.
* **OID 0x02 (short format):** `response = [status][data…]`; status `0x00` = success.
* `NfcAdapter.sendVendorNciMessage(...)` **return value** is only the host-side submission status
  (`0` = accepted for delivery), never the NFCC result — always read the async
  `onVendorNciResponse(gid, oid, data)`.

### 2.3 Polling-frame table format (framework → HAL → firmware)

`RfFrameEntry.getRfFrameAsTlv()` **[SRC]** (`refs/_work/stnfc-dump-full.txt` RfFrameEntry section) and
`stpropnci_prop_st.cc:940-1006` **[SRC]** define, after the sub-OID byte:

```
count                         (1 byte, 0..4; 0 disables the feature)
repeat count times:
  qualifier = position<<5 | rsp_type<<3 | frame_type
  len       = frame_len + 1                 (the +1 is the waiting-time byte)
  wait      = crc_flag<<7 | waiting_time    (crc_flag only honoured for frame_type 0/1 = NFC-A/B standard)
  frame     = frame_len bytes (1..16)       (7A: 1 byte)
  [crc_lo][crc_hi]  appended by ST HAL only if crc_flag=1 and frame_type<=NFC_B_FRAME
                      (ISO14443 CRC: Type A init 0x6363, Type B init 0xFFFF; `stpropnci_prop_android.cc:1485-1509`)
```
Constants **[SRC]**: `position` 0..4 (index in discovery loop), `rsp_type` 0=no response, 1=standard
response expected, 2=non-standard response; `frame_type` 0=NFC-A standard, 1=NFC-B standard,
2=NFC-A short (7-bit), 3=NFC-V, 4/5=NFC-B no-SOF variants (`RfFrameEntry` fields + `stpropnci-internal.h:131-144`).

`sendVendorNciMessage` payload for **set 7A** is therefore exactly:

```
GID=0x0F OID=0x01  payload = 13 01 2A 02 0A 7A
                             ^^  ^^ ^^ ^^ ^^ ^^
                             |   |  |  |  |  +-- frame byte: 7-bit NFC-A short frame value 0x7A
                             |   |  |  |  +----- wait/crc byte: crc=0, wait=0x0A (10)
                             |   |  |  +-------- len = frame+1 = 2
                             |   |  +----------- qualifier 0x2A = pos 1, std response, NFC-A short frame
                             |   +-------------- count = 1
                             +------------------ subOID 0x13 = ST_PROP_SET_RF_CUSTOM_POLL_FRAME
```
Readback: `GID=0x0F OID=0x01 payload = 15` → response `[0x15][status][count…frames]`.
The prototype's `Protocol.polling7A()` returns exactly `{1,42,2,10,122}` = `01 2A 02 0A 7A`
(`_device/.../Protocol.java:119-121`) and `RootProbe.setFrames()` prepends `0x13`
(`RootProbe.java:902-905`); the HAL builds the on-wire firmware command
`2F 1D 05 01 2A 02 0A 7A` (GID/OID header + count/qualifier/len/wait/frame) **[HYP for exact NCI header
bytes, derived from `NCI_MSG_BLD_HDR0/1` + format]**.

The prototype disassembly also confirms the read path uses **subOID `0x15`**, not an OID of its own
(`RootProbe.java:443-458` `readFrames()` = `exchange(21, {})` where the 2-arg helper sends
`oid=0x01 payload={0x15}` — the task brief's wording "vendor OID 21 read/write polling frames" is
a misreading of this call).

### 2.4 RF config (196-byte parameter blobs, params 2/3/4)

The ST NFCC keeps an analog RF configuration per technology as **three 196-byte parameter blobs
(param IDs 2, 3, 4)** inside config subset `0x10`; they are addressed through OID 0x02
`GET_CONFIG`/`SET_CONFIG`/`APPLY_RF_CONFIG`:

| Operation | `sendVendorNciMessage(handle=1, gid=0x0F, oid=0x02, payload)` | Response |
|---|---|---|
| read param N (N=2,3,4) | `03 00 10 01 <N>` | `[status=00][01][N][C4][196 bytes]` |
| write param N | `04 00 10 01 <N> C4 <196 bytes>` | `[status]` |
| apply (commit all) | `0A` | `[status]` |

`RootProbe.java:460-462,942-955` **[SRC]**; consistency with `StNfcOemExtension.sendPropGetConfig /
sendPropSetConfig / applyPropRfConfig` bytecode **[SRC]** (`refs/_work/stnfc-dump2.txt:207-217,484-620`)
and with firmware OIDs `stpropnci_prop_st.h:51-59` — the framework prepends the subOID byte, therefore
the `03`/`04` is the first byte *we* pass and the `00 10` is `[?][configSubSetId=0x10]`.

**196-byte blob layout (reverse-engineered by the prototype, re-used for read-back verification):**

```
byte 0      = 0x44
byte 1      = 0x00
bytes 2..3  = CRC-16, little-endian (see below)
bytes 4..195= 192 bytes of ST analog RF config
```
APC ("automatic power control" per prototype naming) fields: two 32-bit **little-endian** words at byte
offsets **4** and **100**; each word has two 5-bit fields at bits **11–15** and **27–31**. Prototype
`RfConfig.adjust(delta)` adds `delta` (-1..+2) to both fields of both words, clamps each to 2..21, then
recomputes the CRC (`_device/.../RfConfig.java:9-29,51-72`). It logs `PARAM=2/3/4 APC a/b/c/d → ...`
and treats this only as an experiment ("仅实验档，不代表实测功率"). APC field semantics are **not**
documented in public ST sources **[HYP]**.

**CRC**: the decompiled prototype implements `crc = 0x9944; for each byte in blob[4:196]:
crc ^= byte<<8; 8×{ crc = ((crc&0x8000)?0x1021:0) ^ (crc<<1) }` (i.e. CCITT poly 0x1021, MSB-first,
non-standard init 0x9944, no final XOR) and compares it with little-endian `blob[2:4]`
(`RfConfig.java:31-40,55-59`). This is **not** CRC-16/X25 (X25 is reflected, init/xorout 0xFFFF); the
task brief's "CRC-16/X25" label is inaccurate. The prototype only ever wrote back blobs that it had
just read (and validated with this routine), so the routine is empirically consistent with real
firmware blobs — but the true ST definition should be re-derived from a second device/blob before
relying on it in new code. **[SRC for the algorithm, HYP for it being ST's official definition]**

Safety: read all three params, snapshot to a root-only file, only then write; after the session write
the snapshot back and verify by read-back (prototype: `applyRf`/`restoreRf`,
`RootProbe.java:62-99,489-509`). The RF-config step is **optional** for the e-ink path; only the
polling-frame (7A) step is required to wake the panel. **[HYP]**

---

## 3. Android 17 public "reader mode annotation" path (possible clean alternative)

Android 17 (API 37) contains a public reader-mode API whose purpose is to inject a custom NFC-A polling
frame into the discovery loop:

```
NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION   // byte[] in enableReaderMode() extras
NfcAdapter.isReaderModeAnnotationSupported()             // true if HAL/NFCC supports it
// gated by aconfig flag com.android.nfc.module.flags.FLAGS.FLAG_READER_MODE_ANNOTATIONS
```
`refs/_work/aosp-NfcAdapter.java:276-284,1189-1209` **[SRC]** and
<https://developer.android.com/reference/android/nfc/NfcAdapter#EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION>.
The framework forwards the annotation (raw `byte[]` from the extras) into `NfcDiscoveryParameters`, and
the NFC service/native layer turns it into the Android proprietary NCI command
**`NCI_ANDROID_SET_POLLING_LOOP_ANNOTATION` (GID 0x0F, OID 0x0C, payload[0]=0x09)**:

```
payload after subOID 0x09: count(0..4)
  per entry: qualifier-type(1) | length(1) | value(length) = waiting(1) + RF frame(1..16)
```
AOSP "Android proprietary NCI commands" doc (`refs/_work/aosp-nci-doc.txt`, section "Set polling loop
annotation command"); capability "Reader mode annotation" = `0x05` in `NCI_ANDROID_GET_CAPS`.
This is the **same logical content as the ST vendor path** — a custom NFC-A frame in the poll loop —
and for the e-ink panel the annotation would be `2A 02 0A 7A`.

**Caveat that decides whether this is usable on ST54L [SRC]:** the ST HAL's translation of the Android
proprietary command (`refs/android_hardware_st_nfc/libstpropnci/src/stpropnci_prop_android.cc:1278-1368`)
accepts only `qualifier == 0x20` (position 1, *no* response expected, **NFC-A standard 8-bit frame**,
`frame_type=0`), then re-maps it to an ST firmware custom frame and appends the ISO14443-A CRC. It does
**not** pass `frame_type=2` (short 7-bit frame), so through this Android path the NFCC would emit a
standard 8-bit frame with payload `7A` (plus CRC), which is **not** the 7-bit short-frame wake the
panel requires. The ST OEM-extension path (OID 1 / sub 0x13) does accept arbitrary qualifiers,
including `0x2A` (**[SRC]** `stpropnci_prop_st.cc:973-999` performs no qualifier masking; it only
derives `frame_type = qualifier & 7`).

Also unknown: exact Java-extra byte encoding (whether the app passes raw frame bytes or
waiting+frame) — the AOSP javadoc does not specify it; the native conversion is in AOSP
`packages/modules/Nfc` Rust/native code not fetched here. **[HYP]**

=> **On dada (Android 17) the public annotation API is worth testing first because it is clean and
unprivileged, but the ST HAL behavior above suggests it may not emit a true 7-bit frame. Verify on
the wire (StNfcHal logcat, §7) and fall back to the ST vendor OID-1 path if it does not wake the
panel.**

---

## 4. What "7A" is, and the Q-Poll ↔ 7A relationship

### 4.1 The panel's wake requirement (references, confirmed empirically)

* The Huawei FMSC board **does not answer standard 106 kbps NFC-A request frames** `0x26` (REQA) /
  `0x52` (WUPA). It answers only after a vendor **7-bit short frame `0x7A`** (or `0x7D`); this was the
  central discovery of the original reverse-engineering work
  (`refs/huawei-epd-pn532/pn532.py:11-18,588-619` sets PN532 framing to 7 bits and sends the code;
  `refs/ink-nfc-fmsc-by-pn532/pn532.py` names the set `MAGSAFE_WUPA_CODES = (0x7A,0x7B,0x7C,0x7D)` with
  `FMSC_REQA=0x7A`, `FMSC_WUPA=0x7D`, and notes "7A answers only from IDLE; 7D also answers after
  HLTA"; `docs/protocol.md:21` "标准 26/52 或特殊 7A/7D 的 7-bit 短帧寻卡").
* After the 7A wake the panel answers ATQA `04 00`, SAK `0x28` (ISO-DEP), then a normal ISO-DEP ATS
  (`refs/ink-nfc-fmsc-by-pn532/tests/test_isodep.py:26`; write-up
  <https://www.bilibili.com/opus/1261490491120156704> shows the corresponding sniffer capture: special
  wake → UID/select → ISO-DEP, with a 2× baud-rate change).
* All 7A/7D codes are **7-bit short frames** (no CRC, LSB-first on air). The ST custom-poll-frame
  encoding above reproduces this exactly: `frame_type=2` (NFC-A short), `crc_flag=0`, one frame byte
  `0x7A`.

Why 7A/7D: the author's hypothesis is that this is an Apple MagSafe-accessory style 7-bit wake
sequence (7-bit vendor code, 7A/7B/7C/7D family) — **not confirmed by any public spec** **[HYP]**
(the original write-up itself says "属于苹果 MagSafe 配件认证的那一套…没有明确资料证实").

### 4.2 Q-Poll is the NXP mechanism for emitting the same 7A frame

On NXP controllers (SN220/SN100U/SN220U) the vendor feature is called **Q-Poll ("Q Passive Poll")**.
It is selected by an NXP-vendor RF technology/mode byte in `RF_DISCOVER`, and Huawei's phones expose
it to their e-ink app via a system-level allow-list ("Q Reader" mode) rather than a public API.

Known NXP facts **[DEV, from upstream README/issues/logs]**:

| Fact | Evidence |
|---|---|
| Normal Android NFC-A poll command | `21 03 03 01 00 01` (RF_DISCOVER, 1 config, tech/mode `00`) |
| Q-Poll variant sent by `DirectQPoll` | `21 03 03 01 71 01` (tech/mode byte `0x71`); accepted: `41 03 01 00` |
| Must stop an already running discovery first | `21 06 01 00` (RF_DEACTIVATE) → `41 06 01 00`; otherwise `41 03 01 A0` (DISCOVERY_ALREADY_STARTED, issue #5 comment) |
| NXP QTag enable/disable vendor command | `2F 70 02 31 01` / `2F 70 02 31 00` (GID 0x0F OID 0x70, payload `{0x31,action}`); many ROMs answer `4F 70 01 05`, i.e. **not supported/not allowed — does not prove Q-Poll is absent** (issues #3, #5, #10) |
| Controller accepts Q-Poll but Android does not create a Tag | `61 03` RF_DISCOVER_NTF and `61 05` RF_INTF_ACTIVATED_NTF carry technology byte `0x71`; Android NFA only knows `0x00`, so no Tag callback (issue #3). HAL GOT-hook workaround: outbound RF_DISCOVER `00→71`, inbound NTF `71→00` (`upstream-v217/native/nci_rewrite.h:7-24`, `README.md:210-212,244-256`) |
| Real device success | Xiaomi 14 (SN220): hook + D1→D3→D4 work; K90 Pro Max/SN220U: full refresh; RMX3888 SN100U: D4 dies at NFCC firmware level (`60 08 02 B2 00` = RF_TIMEOUT_ERROR) ~1.26 s after D4 (issue #5 comments) |
| NXP-side naming in HAL logs | "NxpNci: Mode = Q Passive Poll", "phNxpNciHal_select_RF_Discovery" (issue #5 comment) |

**ST54L has no 0x71 technology code in its HAL**: the ST implementation of the same idea is the
"custom passive polling frame" table described in §2.3. The ST firmware also reports an activation
produced by a custom poll frame with mode byte `0x78` (`NFC_CUST_PASSIVE_POLL_MODE`,
`stpropnci-internal.h:138`), which the HAL translates into a normal NFC-A ISO-DEP activation for the
stack (`stpropnci_std.cc:426-509`) and, for the OEM extension, into proprietary NTF
`ST_PROP_RF_INTF_ACTIV_CUST_POLL_NTF` (0x14) with tech codes `PROP_A_POLL=0x80 …`
(`stpropnci-internal.h:139-144`).

=> One-line summary: **Q-Poll and the ST custom poll frame are the same product idea on different
controllers ("emit the panel's 7A 7-bit wake inside the poll loop"). Q-Poll is what NXP/Huawei phone
stacks do; on ST54L we must program the equivalent frame through ST vendor NCI.** **[SRC+DEV]**

---

## 5. Prior-prototype deltas vs the upstream app (what to keep / what to fix)

The prior prototype (Xiaomi 17/pudding) is a *standard-session* app with root helpers. Its protocol
layer differs from upstream in ways that matter for dada:

| Topic | Prototype | Upstream v2.17 / references | Action |
|---|---|---|---|
| D8 permission query | **absent** — sends PIN twice blindly (`ImageTransfer.java:135-141`) | D8 → conditional single PIN → D8 (`PanelProtocol.java:164-169`; `device.py:196-207`) | implement D8 flow + configurable extra PIN for panels needing 2 (§4.1 of protocol-spec) |
| D1 validation | parses TLV, checks A0/B1/B2/C0/C1, requires C0 length 4 and UID match (`Protocol.java:62-109`) | same + A1 scan bytes + D1 capability byte + B1==2 + C0 ∈ {201EA001,00000001} | use upstream validation set |
| D4 | fixed `F0 D4 94 80 00` (2000 ms) with 60 s total ISO-DEP timeout (`ImageTransfer.java:39-101`) | power wait user-selectable 100–12700 ms; D4 timeout must scale (max(8000, wait+6000)); 90 s window | use scaled timeout; expose wait |
| DE busy→idle | **one** idle reply = done (`ImageTransfer.java:70-83`) | 3 consecutive idle + must have seen busy (`PanelProtocol.java:197-204`; `device.py:299-337`) | use 3-idle rule |
| D3 chunk | `min(250, isoDep.getMaxTransceiveLength()-7)` (`ImageCodec.java:128`) | fixed 250 app stride, ISO-DEP fragments below (`protocol.py:22-25`, `docs/protocol.md:47`) | keep 250; ensure `getMaxTransceiveLength()>=257` |
| Image packing | A1 linear only; no scan-direction handling (`ImageCodec.pack`) | A0/A1/A0X + D1 scan transform | port `PanelType` |
| Q-Poll/wake | ST vendor NCI: set polling frames to 7A (+APC boost), MIFARE-compat Frida patch on `libstnfc_nci_jni.so` | NXP: QTag/Q-Poll/HAL hook | reuse §2.3 route; verify patch symbols |

Prototype standard-session flow (recovered by disassembling `classes.dex` because jadx failed on that
method): `IsoDep.get(tag) → connect() → setTimeout(3000) → transceive(D1) → Protocol.parseDeviceInfo
(resp, tag.getId()) → supportsImageTransfer() → ImageTransfer.send(packed)`; `DeviceSupport.java:9`
states dada was "standard ISO-DEP supported; 7-bit 7A wake and MIFARE patch still need on-device
adaptation". **[SRC]**

### 5.1 MIFARE-compat patch (Frida, `com.android.nfc`)

The 7A-woken panel is delivered by the ST stack as a MIFARE-like activation; the prototype intercepted
tag creation in `com.android.nfc` native code to force the platform to accept it:

```
target process : com.android.nfc
target library : libstnfc_nci_jni.so
hook           : exported "createNativeNfcTag"
conditions     : 17-byte activation buffer starts 01 04 00 04 00 04, ends ...00 00 00 00 00 00 28,
                 and getNumDiscNtfEv(state) == 1
action         : setNumDiscNtfEi(state, 0)   // clear pending discovery-notification count
                 (UID = activation bytes 6..9)
expiry         : 240 s, then detach
```
`refs/_work/inknfclab-apk/assets/mifare-compat.js:1-42` (extracted from `_device/inknfclab.apk`);
`RootProbe.java:241-290` installs it with Frida 17.24.0 `frida-inject --eternalize` (**[SRC]**; the
asset binary is 84 MB). This is a **device-specific native-symbol hack** — symbol names and behavior
must be re-verified on dada (`libstnfc_nci_jni.so` may not exist or differ; Android 17 vs 17). Treat
as fallback only if the platform refuses to surface the tag after a successful 7A wake. **[HYP for dada]**

Decoding of the activation pattern is not fully established: it appears to carry
`[rf_disc_id=01][?=04][?=00][ATQA=04 00][?=04][UID(4)]…[SAK=28 at byte 16]`; do not rely on the
intermediate bytes, only on the empirical match/test in the script. **[HYP]**

---

## 6. Suggested implementation order on Xiaomi 15 (dada, ST54L, Android 17)

1. **Probe framework APIs** (task-1/st-probe): `NfcAdapter.sendVendorNciMessage(1,0x0F,...)`,
   `registerNfcVendorNciCallback`, `NfcAdapter.getNfcOemExtension().pausePolling/resumePolling`,
   `NfcAdapter.isReaderModeAnnotationSupported()` /
   `EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION`. Record exact `NoSuchMethodException` /
   permission / API-status results.
2. **Probe ST vendor NCI with read-only first**:
   - read polling frames: `sendVendorNciMessage(1, 0x0F, 0x01, {0x15})` → expect
     `[0x15][0x00][count][frames…]`; save snapshot;
   - read RF config params: `oid=0x02` payload `{03,00,10,01,02|03|04}` (optional, snapshot only).
3. **Test the public annotation API first** (unprivileged, no config write): `enableReaderMode` with
   NFC_A + SKIP_NDEF_CHECK + `EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION = {2A 02 0A 7A}` (encoding
   **TBD**; try raw frame `{7A}`, `{0A 7A}` and `{2A 02 0A 7A}` variants while watching StNfcHal
   logcat for the emitted frame). If the panel activates → done, no root/config mutation needed.
4. **Vendor path (prototype-equivalent)**: pause polling → `{0x13, 01 2A 02 0A 7A}` set → readback
   with `{0x15}`, compare exactly → resume polling → `enableReaderMode(NFC_A)` → wait for Tag.
   Never start a second discovery while one is running; make every config write recoverable.
5. **After tag**: standard ISO-DEP APDUs per `docs/protocol-spec.md` (D1 → D8 → PIN(s) → D3 ×476 →
   D4 → DE busy/3-idle). Keep the field on; never replay D4; on `68CA` re-seat, don't retry blindly.
6. **Only if the platform rejects the tag**: try the Frida MIFARE-compat patch (§5.1) after verifying
   the three symbols on dada; otherwise fall back to a custom ReaderMode/ISO-DEP implementation that
   accepts the ST activation directly (if feasible).

---

## 7. On-device observations available for verification (dada logcat)

From `_device/logcat-all.txt` (Xiaomi 15/dada, 2026-10-30, prototype standard session at 13:58,
ST HAL traces 14:15:59–14:17:05):

* ST HAL tag/format: `D StNfcHal: (#01144) Rx 6f 02 09 31 20 00 12 04 2d 04 92 6d` and friends; the
  HAL log line format is `(#<hex-seq>) LL Rx|Tx <hex bytes>` (parser: `NciTrace.java:9-36`).
* `6f 02 …` = notification, GID 0x0F, OID 0x02 (`ST_NCI_MSG_PROP`, firmware debug/log path). The
  prototype's trace parser explicitly labels `6F02…` as "ST 固件调试通知（未解码，不能据此判定板子回复）"
  (`NciTrace.java:15`). They appear in bursts every ~5–6 s during normal polling and contain bytes
  that look like poll frames (`… 01 52`, `… 03 26 01 00 …` in the payloads), consistent with firmware
  `FWLOG_T_*` RF traces defined in `stpropnci_prop_st.h:74-101`, but the exact record format was not
  decoded here **[HYP]**. They are **not** control traffic — do not gate the app on them.
* Also seen: `D StNfcHal: rx 20 <4B timestamp> a2 0a 11 03 00 1e 00 16 <4B timestamp>` lines during
  the same period (ST HAL internal debug, undecoded).
* No `Tx` lines appeared in this capture, and no 0x7A frame was present — that session used the
  standard path without the ST vendor polling config, and the panel was not woken. This is the
  expected negative control for dada.

When testing step 2/3, enable/keep ST HAL logging and look for the firmware custom-poll command
`2F 1D 05 01 2A 02 0A 7A` (Tx) and for an RF_INTF_ACTIVATED_NTF (`61 05`) whose payload indicates the
custom poll activation, then a normal tag callback.

---

## 8. Open questions / risks (must be resolved on dada)

1. Does `android.nfc.NfcAdapter.sendVendorNciMessage(int,int,int,byte[])` exist and is it callable
   from a root app_process / app on dada's Android 17 build? Same for
   `registerNfcVendorNciCallback` and `NfcOemExtension.pausePolling/resumePolling`.
   (Prototype reflection code was compiled/validated against pudding.)
2. Does `com.st.android.nfc_extensions_16` (stnfc.jar / StNfcExtensionService) exist and run on dada?
   If not, OID-1 vendor messages may be rejected/undelivered even if the framework API exists.
3. Is the Android 17 `EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION` path available (flag + capability
   0x05), and does the ST HAL convert it into a *7-bit short frame*? (Source says it hardcodes a
   standard 8-bit `0x20` qualifier — likely no.)
4. Exact Java-extra encoding of the annotation byte array.
5. Whether setting only the polling frame is enough (prototype also set APC/RF config and used
   MIFARE-compat), or whether RF power boost is needed on dada's coil/panel.
6. `libstnfc_nci_jni.so` symbol names/behavior on dada for the MIFARE-compat fallback.
7. Restore guarantees: verify readback equality of polling frames after session; if restore fails,
   require NFC off/on (`svc nfc disable/enable`) — exactly what the prototype did.
8. Persistent risk: firmware/ROM-specific vendor-NCI availability is not contractual; keep the
   implementation behind a capability probe with a clear "unsupported phone" path.
