# Huawei 552×768 4-color e-ink panel (FMSC / JD79665AA) — byte-level protocol spec

Status: extracted from primary sources (reference reverse-engineering repos, the upstream Android app + its
tests, and the on-device prior prototype). Every non-obvious claim is cited as
`path:line` (relative to `E:\Project\vibe\eink`) or as a URL. Claims that are inferred rather than
directly evidenced are marked **[hypothesis]**.

Target: 552×768, 4-color (black/white/yellow/red), panel families **A0**, **A1** (and the separate
**A0X** variant), controller JD79665AA, FMSC NFC front end. Image frame = **105984 bytes** (= 552*768/4,
2 bits per pixel, 4 pixels/byte). Secondary variant **A1.1** uses 115200-byte frames and is out of
scope for this app (`refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/_assets/drivers.json`, ids
`huawei_a11_jd79665`).

Sources (commits pinned 2026-10-30):

| Ref | Location | Commit |
|---|---|---|
| `kur0neko/huawei-epd-pn532` (gitee) | `refs/huawei-epd-pn532/` | `8298480` (2026-10-19) |
| `solar2ain/ink-nfc-fmsc-by-pn532` (github) | `refs/ink-nfc-fmsc-by-pn532/` | `59befef` (2026-10-19) |
| upstream app `ZhaoGY-N/huawei-nfc-eink-android-app` v2.17.0 | `upstream-v217/` | `7c664f4` (2026-10-28) |
| same app v2.10 main | `huawei-nfc-eink-android-app/` | `f16adae` (2026-10-19) |
| prior prototype `dev.inknfclab` (decompiled) | `_device/inknfclab-src/sources/dev/inknfclab/` | APK 0.5.0 |
| ST NFC HAL (LineageOS mirror of `platform/hardware/st/nfc`) | `refs/android_hardware_st_nfc/` | `7cf8d50` |

The two reference repos are the reverse-engineered ground truth for the FMSC command set:
`solar2ain` cites the Good Display "NFC FMSC ESL User Development Manual" chapter numbers for each
command (`refs/ink-nfc-fmsc-by-pn532/docs/protocol.md:11-13,35-42`). `kur0neko` is the original
capture/Pn532 work from which the upstream Android app's packing was adapted.

---

## 1. Stack / prerequisites

```
Phone NFC controller ──NFC-A 106 kbps──> FMSC front end ──SPI──> JD79665AA source/gate drivers
        └ ISO-DEP (ISO14443-4), I-block APDUs, S(WTX) supported by the panel
```

* Only ISO-DEP after activation. Android side uses `IsoDep.transceive`; each APDU's response is
  `<data> SW1 SW2` and **`9000` is the only success status**.
* The app requires `IsoDep.getMaxTransceiveLength() >= 257` because the largest D3 packet is
  7+250 = 257 bytes (`upstream-v217/src/org/nfcresearch/qpolllab/PanelSession.java:57`,
  `ProtocolTest.java:82`).
* WTX is supported by the panel; reference PN532 implementation allows max 50 WTX per command and
  shares a 60 s D4+DE budget (`refs/huawei-epd-pn532/iso_dep.py:12`,
  `refs/huawei-epd-pn532/README.md:63`).
* Session budget used by upstream Android app: 180 s + pre-charge budget for a full refresh
  (`PanelSession.java:33`).
* **Precondition:** the panel must first be woken by the vendor 7-bit poll frame `0x7A` (or `0x7D`).
  Without it the panel does not answer standard REQA/WUPA (`0x26`/`0x52`). See
  `docs/st-nci-notes.md` §4. Once activated it behaves as a normal ISO-DEP tag
  (ATQA `04 00`, SAK `0x28`; observed in refs: `refs/ink-nfc-fmsc-by-pn532/tests/test_isodep.py:26`
  and the original reverse-engineering write-up
  <https://www.bilibili.com/opus/1261490491120156704>).
* The panel is **NFC-field powered**. Coupling quality shows up as status `68CA` (low voltage) and as
  `TagLostException` during the long D4 refresh. There is no retry of a sent command in any reference
  implementation: a non-`9000` answer stops the operation.

### 1.1 What a successful session looks like

```
[wake via 7A poll] -> Tag(ISO-DEP) -> isoDep.connect()
D1 identify            (00 D1 00 00 00)              -> TLV profile, 9000
D8 permission query    (F0 D8 00 00 05 00 00 00 00 0E) -> 9000 or 6985
  if 6985: PIN verify  (00 20 00 01 05 52 65 69 99 19) -> 9000
           D8 again                                     -> 9000
  [some panels: one further PIN verify before/at D4; see §4.2]
D1 re-check (read-only, no DB/DA writes)               -> 9000
D3 x476 packets (53 LZO blocks)  F0 D3 ...             -> 9000 each
D4 refresh             (F0 D4 <0x80|wait/100> 80 00)   -> 9000 (host timeout >= wait+~6 s)
DE poll (F0 DE 00 00 01) until busy->3 consecutive idle; refresh window 90 s
```

---

## 2. APDU formats (command layer)

All values are **request bytes exactly as sent over ISO-DEP**, without any NCI wrapper. `SW` = 2-byte
status word at the end of every response.

| Op | Name | Request bytes | Response | Notes / citation |
|---|---|---|---|---|
| D1 | read driver-board info | `00 D1 00 00 00` | TLV data + `9000` | `Le = 00` (=256). `upstream-v217/src/org/nfcresearch/qpolllab/PanelProtocol.java:15`; `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/protocol.py:6` |
| D8 | permission / user-area prefix query | `F0 D8 00 00 05 00 00 00 00 0E` | `9000` = access granted; `6985` = auth required | `PanelProtocol.java:15`; semantic from `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/protocol.py:7` and `device.py:196-207` |
| PIN | VERIFY (INS 0x20) | `00 20 00 01 05 52 65 69 99 19` | `9000`; wrong PIN `63C2` (2 tries left) | Factory PIN `5265699919`; `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/protocol.py:10,88-91`, `refs/huawei-epd-pn532/epd_protocol.py:12`, `ProtocolTest.java:30` |
| D2 | raw image write (not needed for our panel) | `F0 D2 <slot> <sub> <len> <data…>` | `9000` | `protocol.py:120-123`; 250-byte application stride, ≤256 packets/slot |
| D3 | compressed image write | `F0 D3 <slot=0> <last> <Lc> <block> <sub> <data…>` | `9000` | §3; `protocol.py:124-133`, `PanelProtocol.java:111-118` |
| D4 | execute stored driver + refresh | `F0 D4 <0x80\|wait100> 80 00` | `9000` after panel accepts | §4.1; `PanelProtocol.java:119-122`, `refs/huawei-epd-pn532/epd_protocol.py:16-20` |
| DE | busy/status query | `F0 DE 00 00 01` | `01 9000` = busy, `00 9000` = idle | §4.2; `protocol.py:8`, `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/device.py:316-320` |
| (DB) | clear stored driver | `F0 DB 02 00 00` | `9000` | Reference only — **never sent by this app** (`protocol.py:9`, `docs/protocol.md:37`) |
| (DB) | write driver | `F0 DB 00 00 <Lc> <TLV…>` | `9000` | Reference only (`plans.py:55`, `drivers.json` `db_apdus`) |
| (DA) | set panel geometry | `F0 DA 00 00 03 F0 C7 20` | `9000` | Reference only (`plans.py:71`, `two-pass.json`) |
| (DA) | set scan mode | `F0 DA 01 01 03 12 00 30` | `9000` | Reference only (`plans.py:72`) |

**"Keep the factory driver" policy** (the one this app uses): do not send DB/DA at all. The app only
identifies (D1), authenticates (D8/PIN), uploads (D3) and refreshes (D4) with the driver already in
the panel's flash (`upstream-v217/README.md:182`, `PanelProtocol.java:170`).

---

## 3. D1 identity: TLV profile + validation

### 3.1 Response framing

`response = TLV* || SW1 SW2`. Each TLV is `tag(1) || len(1) || value(len)`; no long-form lengths,
no tag repetition. The parser must reject truncated headers/values and duplicate tags
(`PanelProtocol.java:61-69`; `protocol.py:53-65`).

### 3.2 Fields

Fixture used by the upstream Java tests (`ProtocolTest.java:11`, hex):
`A007F0C72006000228 A10700120030FFFFFF B10102 B20114 B30100 C004201EA001 C10401020304 D10701200000000000 9000`

| Tag | Len | Meaning | Validation rule |
|---|---|---|---|
| `A0` | 7 | geometry/model record | exactly `F0 C7 20 06 00 02 28` for A0/A1 (552×768 4-color). `A1.1` uses `F0 C7 20 06 00 02 58`. Layout: `[0..2]=model prefix`, `[3..4]=height big-endian (0x0600=1536 = 2×768 raw rows)`, `[5..6]=width big-endian (0x0228=552)`. For `B1=2` planes the effective logical panel is 768×552, 4 colors. `PanelProtocol.java:71-86`; `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/protocol.py:76-91`; `drivers.json` (`expected_d1_a0`). |
| `A1` | 7 | stored scan configuration | accepted: `00 12 00 30 FF FF FF` (bottom-up) or `01 12 00 30 FF FF FF` (top-down). Byte 0 is the **scan direction**: `00` = bottom-to-top pixels in RAM, `01` = top-down. `PanelProtocol.java:17,82`; `README.md:201-202`. |
| `B1` | 1 | number of image planes/slots | `0x02` (2 planes, 2 bits/pixel = 4 colors). `protocol.py:88-91`. |
| `B2` | 1 | firmware version | observed `0x14` (20); not gated on value. `PanelProtocol.java:71`, `protocol.py:84-91`. |
| `B3` | 1 | unknown/reserved | observed `0x00`; not validated (`ProtocolTest.java:11`). |
| `C0` | 4 | **FMSC APPID** | accepted: `20 1E A0 01` **or** `00 00 00 01`. Any other value → stop before auth/write. `C0[3]!=0` means image compression (D3) supported. `PanelProtocol.java:83-85`; `protocol.py:68-74,82-85`; `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/device.py:136-141`. |
| `C1` | 4/7/10 (any) | UID (diagnostic) | optional in upstream; reference requires it equals the ISO-DEP activated UID and length ∈ {4,7,10}. No allow-list of IDs. `PanelProtocol.java:89-90`, `protocol.py:70`. |
| `D1` | 7 | capability/config record | `D1[0] == 0x01` = supports D3 compressed image transfer. Other 6 bytes observed `20 00 00 00 00 00`; not validated. `PanelProtocol.java:71-81`; `protocol.py:246-247`. |

Any mismatch stops the session **before** D8/PIN/D3/D4 (`ProtocolTest.java:59-63,79`).

### 3.3 Pseudocode

```
info = parse_tlv(transceive(D1) - SW)
require_ok(SW)
if len(A0)!=7 or len(A1)!=7 or len(B1)!=1 or len(B2)!=1 or len(C0)!=4 or len(D1)!=7: fail
if A0 != F0C72006000228: fail
if A1 not in {00120030FFFFFF, 01120030FFFFFF}: fail
if B1 != 02: fail
if C0 not in {201EA001, 00000001}: fail
if D1[0] != 01: fail    # no D3 support
scan_top_down = (A1[0] == 1)
```

`6401` from D1 = "execution not completed" — the FMSC status table calls it an undocumented
execution error (`refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/protocol.py:14`); upstream saw D1
answer `6401` on some panels and could not fix it (`upstream-v217/README.md:203`). Treat as a hard
failure (no documented recovery); re-wake / re-seat the coil and retry the session.

### 3.4 Test vectors

* Full D1 response (above) must parse and validate.
* `A0 07 F0 90 00` (TLV cut short) must be rejected.
* Duplicated `B1` tag must be rejected.
* `A1 = 00 12 11 30 FF FF FF` must be reported with expected/actual values (`ProtocolTest.java:67-76`).
* C0 `00000002` must be rejected before any auth/write (`ProtocolTest.java:59-63`).

---

## 4. Authentication: D8 + PIN

### 4.1 One-PIN flow (upstream v2.17, solar2ain)

```
access = transceive(D8)
if SW == 0x6985:                 # "conditions of use not satisfied" -> image area locked
    transceive(PIN)              # 00 20 00 01 05 52 65 69 99 19, must be 9000
    access = transceive(D8)      # must now be 9000
require_ok(access)               # initial 9000 => no PIN needed (other boards/vendors)
```

`PanelProtocol.java:164-169`; reference equivalent with board-specific gating
(`refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/device.py:196-207`: the iPhone-style standard-wake
board never sends a PIN; Huawei private-wake board does).

The very first reverse-engineered tool has no D8 step and always sends the PIN **twice**
(`refs/huawei-epd-pn532/epd_writer.py:140-142`; also the decompiled prototype
`_device/inknfclab-src/sources/dev/inknfclab/ImageTransfer.java:135-141`). The upstream app reduced
this to a single conditional PIN. On at least one SN220 + A1.1-class panel, **D4 only succeeds after
a second PIN verification** (sequence `D8 -> PIN -> D8 -> PIN`; GitHub issue #3,
<https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/3>), whereas an SN100U + A1 panel
needs only one PIN (<https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/3#issuecomment-…>,
yhl88890 comment 2026-10-25). => implement PIN count as an adaptive/configurable option; always stop
on a failed PIN (do not guess or retry; `ProtocolTest.java:88`).

### 4.2 PIN details

* APDU: `00 20 00 01 05 52 65 69 99 19` = CLA `00`, INS `20` (VERIFY), P1 `00`, P2 `01`, Lc `05`,
  data = 5-byte factory PIN. No Le.
* Factory PIN bytes `52 65 69 99 19` were published with the maintainer's authorization
  (`refs/huawei-epd-pn532/README.md:16`, `epd_protocol.py:10-12`). Do not log the PIN
  (`PanelProtocol.java:130`).
* Outcome of a wrong PIN in the upstream test harness: `63C2` (verification failed, 2 attempts
  left). Never retried (`ProtocolTest.java:30,88`).

---

## 5. Image packing (105984-byte frame)

### 5.1 Color codes

Code values are fixed on the wire: **0 = black, 1 = white, 2 = yellow, 3 = red**
(`upstream-v217/src/org/nfcresearch/qpolllab/CalibrationPalette.java:6`;
`refs/huawei-epd-pn532/image_codec.py:9`; tutorial example: 4 pixels black/white/yellow/red pack to
`0x1B`). Preview palettes may differ (calibration) but the wire codes never do.

### 5.2 `packCodes` — logical pixels → 192-byte controller rows (A1 linear layout)

For x in `551..0` (right to left), for y in `767..0` step -4 (bottom to top):
`byte = code(y,x)<<6 | code(y-1,x)<<4 | code(y-2,x)<<2 | code(y-3,x)`; output rows are
`x`-major, each row = 768/4 = **192 bytes**, total 552 rows × 192 = 105984
(`PanelProtocol.java:92-102`; byte-identical reference `refs/huawei-epd-pn532/image_codec.py:74-81`;
prototype `_device/.../ImageCodec.java:77-92`).

Reference test vector: `codes = {3,0,2,1, 1,2,0,3}`, width 2, height 4 → `E4 1B`
(`ProtocolTest.java:77`).

### 5.3 Panel variants (ordering of the 192-byte rows)

| Variant | Row mapping (wire row `c` ← linear row `s`) | Additional transform |
|---|---|---|
| **A1** | `s = c` (linear) | none |
| **A0** | `c` even → `s = c/2`; `c` odd → `s = 551 - c/2` (first half → even rows, second half → reversed odd rows) | none |
| **A0X** | same as A0 | first swap the two halves of the linear frame: `out[0..52991]=linear[52992..105983]`, `out[52992..]=linear[0..52991]` (i.e. pixels `x → (x+276) mod 552`); preview applies the inverse |

`upstream-v217/src/org/nfcresearch/qpolllab/PanelType.java:12-33`; independent full-frame oracle in
`PanelTypeTest.java:18-25` (`source = c even ? c/2 : 551-c/2; x = (551-source + (A0X?276:0)) % 552`).
A0X exists to compensate a panel revision whose half-screen order is reversed (PR #9 / luzamm,
`README.md:84`). The panel variant **cannot** be inferred from D1; the user must pick it physically
(`README.md:18,200-203`).

### 5.4 Scan direction (D1 `A1[0]`)

The packed buffer above assumes the factory `00` scan (pixels bottom-up within each byte). If D1
reports `A1[0] == 1` (top-down), transform each 192-byte row before upload: reverse byte order **and**
reverse the 4 two-bit groups inside each byte:

```
for i in 0..95: b = row[191-i]; out[i] = ((b&3)<<6)|((b&12)<<2)|((b&48)>>2)|((b&192)>>6)
```
`PanelType.forScan` `PanelType.java:40-50`; reversibility tested in `PanelTypeTest.java:26-27`.
Transformation happens **after** a successful D1 and a re-check before upload; if the direction
changes during the session, abort (`PanelProtocol.java:161,171-172`).

### 5.5 Preview (inverse mapping)

`logical = inverse(row-mapping)` (undo A0 interleave; undo A0X half swap), then for each logical pixel
`(x,y)`: `index=(551-x)*192 + (767-y)/4`, `code=(frame[index] >> (6-2*((767-y)%4))) & 3`,
`color=palette[code]` (`ImageRaster.java:49-61`). The upstream app records SHA-256 of the **logical
packed frame** (pre-scan-transform) and of the actual wire image separately
(`PanelProtocol.java:163`, `PanelTypeTest.java:44`).

---

## 6. D3 compressed image transfer

### 6.1 Block structure

* Frame is split into **2000-byte blocks**: 53 blocks; blocks 0..51 = 2000 bytes, block 52 = 1984
  bytes (`build_transfer_plan` `refs/huawei-epd-pn532/epd_protocol.py:109-128`;
  `ImageCodec.java:94-133`).
* Each block is compressed **independently** with LZO1X. The reference implementation uses a real
  LZO1X compressor (`lzokay`, `sum of compressed = 106620` on the upstream all-zero test frame), but
  a **valid literal-only LZO1X stream** is accepted and is what the upstream Android app emits
  (no native library; `PanelProtocol.literalLzo`).
* Each compressed block is split into subpackets of exactly **250 data bytes** (last one shorter).
  The 250-byte stride is part of the FMSC application protocol and must not be changed when the
  transport fragments further (`refs/huawei-epd-pn532/epd_protocol.py:22-25`;
  `refs/ink-nfc-fmsc-by-pn532/docs/protocol.md:47`).
* On the upstream empty frame this yields **476 D3 APDUs / 53 blocks / 106620 compressed bytes**
  (`ProtocolTest.java:80-86`).

### 6.2 D3 APDU layout

```
F0 D3 00 <last> <Lc> <block> <sub> <compressed bytes…>
 |  |  |    |      |     |      |
 |  |  |    |      |     |      +-- sub-packet index in block (0-based)
 |  |  |    |      |     +--------- block index (0..52)
 |  |  |    |      +--------------- Lc = (data bytes) + 2  (block+sub are counted)
 |  |  |    +---------------------- 1 = last sub-packet of this compressed block, else 0
 |  |  +--------------------------- P1 = image slot (0)
 +--+------------------------------ CLA/INS
```
Total APDU length = `7 + data bytes`, max 257 when data = 250 (`PanelProtocol.java:111-118`;
`protocol.py:110-134` identical). A block's compressed stream is at most 256 subpackets
(references reject larger blocks, `ImageCodec.java:109-112`).

Sequence/ordering rule: `block` increments after every `last=1`; `sub` restarts at 0. The panel
answers `9000` for each packet; a non-9000 answer (e.g. `6995` "sequence error", `6992/6993/6994`
LZO errors) aborts — **no retransmission anywhere in the reference stack** (`PanelProtocol.java:181-183`,
`ProtocolTest.java:89,90`).

### 6.3 Literal-only LZO1X encoding (exact)

For a block of `n` raw bytes (1..2000):

```
if n <= 238:  stream = [17+n]
else:         stream = [0]; rest = n-18; while rest > 255: stream += [0]; rest -= 255; stream += [rest]
stream += raw_bytes
stream += [0x11, 0x00, 0x00]          # LZO1X end-of-stream marker
```
`PanelProtocol.java:103-110`; identical `protocol.py:94-107`; `ImageCodec.java:54-75`;
`refs/huawei-epd-pn532/epd_protocol.py` (real LZO1X compressor used there).

Test vectors (derived, verified by `ProtocolTest.java:80-86,116-120` fixture export):

| Raw block | Compressed header bytes | Compressed length |
|---|---|---|
| 2000 bytes | `00 00 00 00 00 00 00 00 C5` + raw + `11 00 00` | 2012 |
| 1984 bytes (final block) | `00 00 00 00 00 00 00 00 B5` + raw + `11 00 00` | 1996 |
| n ≤ 238 | `[17+n]` + raw + `11 00 00` | n+4 |

Full frame bookkeeping: 52×2012 + 1×1996 = **106620** compressed bytes; 52 blocks × 9 packets +
1 block × 8 packets = **476** packets.

### 6.4 Transfer timing/policy

* One request/response per subpacket; reference adds no artificial pacing by default
  (`packet_delay_ms = 0`, `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/device.py:65`; upstream also
  sends back-to-back). Practical full upload ≈ 16–18 s on NXP controllers
  (issue #5 comments), ≈ 31–35 s in the original PN532 tool at 115200 baud.
* Reference implementations cap the total refresh job to keep RF alive; the upstream Android app
  budgets 180 s + pre-charge (`PanelSession.java:33`), before which an optional **pre-charge** wait of
  0–30 s can be inserted before D8/PIN/D3 to raise the panel's storage capacitor voltage
  (`PanelProtocol.java:14,154-159`).
* If the tag is lost mid-transfer the image is partially written; re-running a full upload is the
  only recovery (no resume protocol).

---

## 7. D4 refresh + DE busy polling

### 7.1 D4 encoding

```
F0 D4 (0x80 | (power_on_wait_ms/100)) 80 00
```
* `power_on_wait_ms` ∈ [100, 12700], multiple of 100 (P1 bit7 = "skip detection/keep power" flag +
  7-bit delay in 100 ms units; `refs/huawei-epd-pn532/epd_protocol.py:16-20`).
* P2 = `0x80` fixed (no documented meaning in the sources; use exactly this).
* GUI/reference values 500/1000/2000/3000/4000/5000 ms → `F0 D4 85/8A/94/9E/A8/B2 80 00`
  (`ProtocolTest.java:32`; `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/device.py:65,70`).
* The wait is the panel's own power-up delay; the D4 **response arrives only after** the panel has
  consumed it. Measured D4 reply delay ≈ `power_on_wait_ms + 1.4…2.3 s` (issue #5 comments: 500 ms →
  1.26 s; 2000 ms → 3.44–3.55 s; 10000 ms → 12.3 s).
* Host timeout guidance: `max(8000 ms, power_on_wait_ms + 6000 ms)` (upstream v2.17 hard-codes 8000 ms
  for all waits — a real bug for ≥5000 ms waits, see PR #12 proposal in issue #5). Do not abort the
  refresh because the D4 response is slow; the whole refresh still needs a **90 s deadline**
  (`PanelProtocol.java:14,190`).
* D4 response payload is empty or `00`/`01` in references (`device.py:294-295`); treat payload as
  optional and require `9000` only. **D4 is never replayed** (`ProtocolTest.java:90`).

### 7.2 DE polling

* Command `F0 DE 00 00 01`; one response byte + `9000`: `01` = still refreshing (busy), `00` = idle
  (`PanelProtocol.java:15,195-196`; `protocol.py:8`; `device.py:316-320`).
* Start polling immediately after D4 `9000`. Recommended cadence 100–500 ms; reference uses a **9 Hz
  target** (≈111 ms, `device.py:65,338`) and upstream allows 0–500 ms (`PanelProtocol.java:150`).
* Completion rule: must have **seen busy at least once**, then **3 consecutive idle** replies
  (`PanelProtocol.java:197-204`; `device.py:299-337`). If busy was never observed, report
  `REFRESH_UNCONFIRMED` after a minimum observation window (upstream: 20 s from D4; reference:
  panel profile `minimum_observation_seconds` = 20 s for A0/A1, 25 s for A1.1
  (`drivers.json`)) — do not claim success.
* Deadline 90 s (upstream) / 60–120 s (references; `refresh_timeout_seconds` 90 s A0/A1,
  90 s A1.1 in `drivers.json`). On timeout or tag loss: report unconfirmed, **never resend D4**.
* Status words that show up here (FMSC table, `refs/ink-nfc-fmsc-by-pn532/src/ink_nfc_fmsc/protocol.py:13-29`):

| SW | Meaning |
|---|---|
| `9000` | OK |
| `6401` | execution not completed (undocumented) |
| `6502` | low voltage caused NVM stop (not a PIN error) |
| `6501/6503/6506/6508/6509/650A/650F` | NVM interrupted/address/permission/length/param/conflict/permission |
| `6700` | wrong Lc/Le |
| `6781` | total packet length error |
| `68C6`, `6986` | screen reset failed or screen absent |
| `68CA` | low-voltage warning (weak coupling / insufficient harvested power) |
| `68CB` | refresh timeout warning |
| `6991` | compression algorithm not initialized |
| `6992` | decompression failed |
| `6993` | decompressed length wrong |
| `6994` | compressed length wrong |
| `6995` | image subpacket sequence mismatch |
| `6982` | security state not satisfied |
| `6985` | conditions of use not satisfied (D8 => PIN required; also "access conditions") |
| `698A` | low-voltage warning |
| `63C2` | PIN verification failed (2 attempts left) |
| `6449/6450/6451/6452/6453/6454/6455/646A` | EEPROM / init data / init TAG / init content / missing init / refresh failed / reset failed / TLV count |
| `6A80/6A86/6A96` | bad data / bad P1P2 / image-slot mismatch |

Upstream Android app maps a subset for UI text (`PanelProtocol.java:43-55`); use the full table above.
`68CA` on a busy-poll reply means the panel is browning out — re-seat the phone, do not resend D4, and
report failure with the code.

---

## 8. End-to-end pseudocode (implementable)

```
def refresh(packed_codes, panel_variant, power_ms, poll_ms, charge_ms, pin_count):
    validate power_ms in [100..12700] step 100, poll_ms in [0..500], charge_ms in [0..30000]
    if charge_ms > 0: sleep(charge_ms)                  # keep coil attached, harvest energy
    info = D1(); require_ok; validate_profile(info)     # §3
    wire = pack(codes)                                  # §5.2
    wire = variant_rows(wire, panel_variant)            # A0/A1/A0X, §5.3
    wire = scan_transform(wire, info.top_down)          # §5.4
    acc = D8()
    if acc == 0x6985:
        for i in 1..pin_count:                          # pin_count usually 1, some panels 2
            require_ok(PIN()); require_ok(D8())
    else: require_ok(acc)
    info2 = D1(); require_ok; if info2.top_down != info.top_down: abort
    compressed = []
    for block in 0..52: compressed.append(literal_lzo(wire[block*2000 : min(2000,rest)]))  # §6.3
    for block,stream in compressed:
        for sub in 0..ceil(len(stream)/250)-1:
            chunk = stream[sub*250:(sub+1)*250]
            require_ok(D3(slot=0, last=(sub==last), block, sub, chunk))
    d4 = F0 D4 (0x80|power_ms/100) 80 00
    require_ok(D4(), timeout=max(8000, power_ms+6000))
    deadline = now + 90s; seen_busy=False; idle=0
    while now < deadline:
        st = require_ok(DE())                            # 1 byte
        assert st in (0,1)
        if st==1: seen_busy=True; idle=0
        else: idle+=1
        if not st==1 and idle>=3 and (seen_busy or now-start>=20s):
            return REFRESH_DONE if seen_busy else REFRESH_UNCONFIRMED
        sleep(poll_ms)                                   # measured after response
    return REFRESH_TIMEOUT_UNCONFIRMED
```

---

## 9. Known panel/phone variants and pitfalls

* **A0 vs A1 vs A0X**: not auto-detectable; wrong choice produces half-shifted/mirrored images.
  Upstream has separate full-frame tests (`PanelTypeTest.java:18-25`). Issue #3's unresolved
  "left half squeezed + right half mirrored" symptom was attributed to a panel revision
  (A1.1-class) requiring SPI-level window init (`0x61`/`0x83` DB commands) that the NFC path cannot
  apply — not a packing bug (<https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/3>).
* **A1.1** (115200-byte frames, `A0 = F0 C7 20 06 00 02 58`) is not supported by this app's
  105984-byte pipeline; do not silently crop.
* **PIN count** varies per panel (1 vs 2); see §4.1.
* **D4 timeout** must scale with the power-on wait (§7.1); fixed 8 s fails for ≥5 s waits.
* **6401** from D1: observed, undocumented; no documented recovery.
* **Tag loss during D4/DE** is normal on weak coupling: never auto-resend D4; report
  unconfirmed and let the user re-seat.
* **D8 may return 9000 without any PIN** on other FMSC boards/phones; do not require a PIN
  unconditionally (that breaks those cases), but do not skip the condition either.
* All reference implementations explicitly forbid: DB/DA writes, PIN guessing/retry, D4 replay,
  fabricating success from an idle-only DE sequence.

---

## 10. Uncertainty / open items

* `D8` data `00 00 00 00 0E` internal semantics are not documented; only the `9000`/`6985` outcome is
  relied upon.
* `A0`/`D1`/`B3` remaining bytes' meaning is unknown; only the listed constraints are validated.
* Whether the literal-only LZO stream is accepted by **every** FMSC firmware variant is inferred from
  the upstream app working on tested panels; a real LZO1X compressor (e.g. lzokay) is a safer
  drop-in if a panel rejects `6992/6993`.
* `DE` "busy" semantics during the panel's internal phases are coarse; the 3-idle rule is an
  observation heuristic, not a hardware contract.
* The 105984-byte frame `packCodes` order was validated byte-for-byte against `image_codec.py`
  (`README.md:203`) and against real D3 traffic (`PanelTypeTest.java:44`); if a panel displays a
  transposed image, verify panel variant/scan before changing packing.
