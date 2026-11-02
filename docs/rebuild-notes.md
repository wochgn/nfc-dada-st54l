# Ink NFC Lab — rebuild notes (`lab-rebuild/`)

Rebuild of the prior prototype **dev.inknfclab** ("Ink NFC Lab") from the jadx-decompiled
sources of `_device/inknfclab.apk`, compiled and packaged with only the installed Android
SDK + JDK (no Gradle, no network, no third-party libraries).

- Current artifact: `lab-rebuild/dist/InkNfcLab-0.5.4.apk`
- SHA-256: `c266616e34cb3bf5741e0450f67f8e2fd7d8e7da8de88720e6bb4e51d9180acb`
  (also written to `dist/InkNfcLab-0.5.4.apk.sha256`)
- Package id: `dev.inknfclab`, versionCode `15`, versionName `0.5.4`
- minSdk 26 / targetSdk 35, NFC permission only, no network permission

Build history: `0.5.1` (`2b639884afb42bab2938843261b0459a34a6409aef3c0a674642e0a56aa39cdf`) was the
first rebuild; `0.5.2` (`0edc70534a07bdc27755b50b59bc53a0ff1c8841b51cbedd4deda9b83844b773`)
enabled the "dada" root profile; `0.5.3` (`22db903138480a244620ef3da2d24991f78f4cae1c224a5f9b0f18653a3080f5`)
makes the app report the measured 7A firmware limitation; `0.5.4` adds the D1 pre-charge + retry
used by the pudding-HAL/FW-overlay experiment (dedicated sections below).

## Project layout

```
lab-rebuild/
├─ AndroidManifest.xml        original manifest semantics; version bumped to 0.5.3 / 14
├─ src/dev/inknfclab/*.java   13 classes decompiled from the original dex (+ hand-fixed methods)
├─ res/
│  ├─ drawable/ic_launcher.xml         decoded from original resources.arsc
│  ├─ drawable/preview_placeholder.xml decoded from original resources.arsc
│  └─ values/styles.xml, public.xml    AppTheme + pinned public resource ids (0x7f010000…)
├─ assets/                    extracted byte-for-byte from the original APK
│  ├─ frida-inject            84,553,240 bytes  sha256 af77c1d6…c530c3
│  ├─ mifare-compat.js         1,596 bytes      sha256 c3dc59c8…0521c4
│  ├─ FRIDA-COPYING.txt        2,416 bytes
│  └─ NOTICE.txt                 565 bytes
├─ keystore/debug.keystore    local debug keystore generated on first build
│                             (alias `inknfclab`, store/key pass `android`, 10000 days)
├─ build.py                   Windows/Python build driver (this is the build command)
├─ build/                     scratch output (classes, classes.jar, classes.dex, res.zip,
│                             unsigned/aligned APKs, verification dumps and screenshots)
└─ dist/                      signed APKs + .sha256 / .idsig
```

`build/` and `dist/` are disposable; `AndroidManifest.xml`, `src/`, `res/`, `assets/`,
`build.py`, `keystore/` are the reproducible project inputs. The original APK is not
required to rebuild (the assets are already checked in), only to compare against.

## Build

Requirements (already installed on this machine):

| Tool        | Path / version |
|-------------|----------------|
| `ANDROID_HOME` | `D:\Apps\AndroidSDK` (build-tools 36.1.0 used; falls back through 35/34/33; platform android-36.1 android.jar) |
| `JAVA_HOME` | `D:\Apps\OpenJDK` (JDK 17) |
| Python      | 3.10 |

```powershell
cd E:\Project\vibe\eink\lab-rebuild
$env:JAVA_HOME="D:\Apps\OpenJDK"
$env:ANDROID_HOME="D:\Apps\AndroidSDK"
python build.py
```

`build.py` runs, in order:

1. `javac --release 8 -classpath <android.jar> -d build/classes src/**/*.java` then `jar cf build/classes.jar`.
2. `d8 --min-api 26 --lib android.jar --output build/dex build/classes.jar` → `classes.dex`.
3. `aapt2 compile --dir res -o build/res.zip`.
4. `aapt2 link -o build/unsigned.apk -I android.jar --manifest AndroidManifest.xml -A assets
   --min-sdk-version 26 --target-sdk-version 35 --no-compile-sdk-metadata build/res.zip`
   (aapt2 packages `res/` + `assets/` and the binary manifest; `--no-compile-sdk-metadata`
   suppresses injected `compileSdkVersion` attributes because the build is toolchain-dependent).
5. `classes.dex` is appended into the unsigned APK with Python `zipfile` (deflated).
6. `zipalign -f 4`, then `apksigner sign` with `keystore/debug.keystore`
   (generated with `keytool -genkeypair` on first run).
7. `apksigner verify --verbose`, writes `dist/InkNfcLab-<versionName>.apk` and `.sha256`.

Tool executables are invoked directly (`java -cp lib/d8.jar …`, `java -jar lib/apksigner.jar …`)
so no `PATH`/`.bat` wrapper quirks are involved.

## Install / launch on the connected device

The rebuild is signed with a local debug key, which differs from the original prototype key,
so the first install of a rebuild requires uninstalling the old package:

```powershell
adb -s [redacted-serial] uninstall dev.inknfclab
adb -s [redacted-serial] install -r E:\Project\vibe\eink\lab-rebuild\dist\InkNfcLab-0.5.2.apk
adb -s [redacted-serial] shell am start -W -n dev.inknfclab/.MainActivity
adb -s [redacted-serial] logcat -d | Select-String 'AndroidRuntime|FATAL|dev.inknfclab'
```

Evidence collected for 0.5.2 (device `dada`, serial `[redacted-serial]`, Android 17 / SDK 37):

- `apksigner verify --verbose`: **Verifies**, v2 + v3 schemes, 1 signer.
- `aapt2 dump badging`: `package: name='dev.inknfclab' versionCode='13' versionName='0.5.2'`,
  minSdk 26 / targetSdk 35, `launchable-activity: dev.inknfclab.MainActivity`, NFC permission present.
- `adb install`: Success (incremental install); `dumpsys package` reports versionCode 13 / 0.5.2.
- `am start -W`: `Status: ok`, `LaunchState: COLD`, `TotalTime: 176ms`, `Displayed … +176ms`.
- `logcat` for >10 s after launch: no `FATAL EXCEPTION`, no `ANR`, app process alive; no NFC
  send/refresh was triggered (the app only starts a session after a user tap).
- Foreground screenshot: `build/screen-052b.png` shows the expected UI
  (`INK STUDIO / NFC`, 552×768 preview placeholder, 选择图片 / 画面设置 / 选择图片，开始创作).
- APK contents: `res/drawable/ic_launcher.xml`, `res/drawable/preview_placeholder.xml`,
  `resources.arsc` (912 bytes, same size as original), `assets/frida-inject` (84,553,240 bytes,
  sha256 matches the original), `assets/mifare-compat.js`, `assets/FRIDA-COPYING.txt`,
  `assets/NOTICE.txt`, `classes.dex`; signed `META-INF/`.

## Decompilation fixes (jadx → javac)

jadx 1.5.1 emitted Java that did not compile for 19 sites in three classes, and two methods it
could not decompile at all (they were replaced by `throw new UnsupportedOperationException(...)`).
Fixes, in the order they were applied:

1. **`MainActivity.m7lambda$onTagDiscovered$0...` (NFC session core) — hand-reconstructed.**
   jadx skipped the 430-instruction method body ("Code decompiled incorrectly"). It was rebuilt
   from a raw `dexdump -d` dump / Soot IR and cross-checked instruction by instruction against
   `Protocol`, `ImageTransfer` and the `m8..m11` UI callbacks. Logic restored:
   read tag UID/tech list → `IsoDep.get` (fail with "标签没有 ISO-DEP 技术，无法发送 D1") →
   `connect()` + `setTimeout(3000)` → `D1`/`Protocol.READ_INFO` → log D1 RX →
   `Protocol.parseDeviceInfo`/`deviceInfo` → if an upload was requested: validate
   `supportsImageTransfer`/`preparedImage`, UI callback, `ImageTransfer.send(isoDep, packed, new C00042())`
   (D8/PIN auth + D3 chunks + D4 refresh + DE polling happen inside `ImageTransfer.send`/`refresh`),
   mark `uploadRequested=false`/`refreshComplete=true`, success callback, `requestStop(rootProcess)`;
   otherwise info-only callback; catch → `sessionFailed`, stop helper, error callback;
   finally → close IsoDep, clear `connection`/`reading`, and `finishStandardSession()` when the
   session was a standard-NFC session. Verified in the built dex: the method calls
   `IsoDep.connect/transceive`, `Protocol.parseDeviceInfo`, `ImageTransfer.send`, and
   `ImageTransfer.send` calls `ImageTransfer.refresh` (D4/DE polling + `Protocol.authentication`).
2. **`ResearchQuantizer.quantize(...)` — hand-reconstructed.** jadx skipped the 661-instruction
   method. Rebuilt from the raw dump: parameter validation, kernel/divisor selection
   (`f0FS`/`SIERRA`/`STUCKI`, 16/4/42), serpentine scan direction, alpha blend + brightness/contrast,
   saturation-based "smoothness", Lab-based `choose(...)` palette selection, Bayer 8×8 threshold for
   algorithm 5, error diffusion into `double[3][(width+4)*3]`, row rotation, cancellation check on
   `Thread.currentThread().isInterrupted()`, and the `无效的研究算法参数` guard. Used by algorithms
   2–6 (SMOOTH_FS, SIERRA_LITE, STUCKI, BAYER, CLEAN).
3. **`ImageTransfer.refresh(...)` — structure repaired.** jadx produced invalid try/catch scoping
   (`e = e`, `throw th`, uninitialized locals). Rewritten as the original
   `try { D4 → DE poll loop } catch (Exception e) { trace REFRESH_FAILED; throw e; } finally
   { setTimeout(timeout) → trace TIMEOUT_RESTORE_FAILED on failure }`, with the 60 s deadline and
   `refreshClock.sleep(Math.min(500, remaining))` polling exactly as in the bytecode.
4. **`MainActivity.m12lambda$prepareSelectedImage$0...` — structure repaired.** Inlined the broken
   try-with-resources / duplicate-catch artifacts into the original shape: bounds decode with stream
   close, size guard ("图片尺寸无效或过大"), sample-size loop, `ImageDecoder` (API ≥ 28) or
   `BitmapFactory` fallback, `ImageCodec.prepare`, elapsed timing, success callback `m13…`, and the
   catch that recycles the bitmap and posts error callback `m14…`.
5. **`MainActivity.m42lambda$startAutomaticCompatibility$0...` — structure repaired.** Restored
   `try { if (resumed) { su -c helper; read stdout lines; COMPAT_READY detection } } catch { log
   AUTO_COMPAT_FAILED + UI } finally { compatibilityProcess=null; compatibilityRunning=false }`, and
   replaced jadx's illegal `final boolean z = true; … z = false;` with the equivalent
   `final boolean z = z2 && start.waitFor() == 0;` (same short-circuit semantics).
6. **`RootProbe.run(...)` — structure repaired.** jadx could not restore the try/finally cleanup
   structure used by every mode (compat/rfinspect/rftest/restore/inspect/probe/upload). Rewritten
   with the same mode dispatch and one `finally` cleanup block: restore RF snapshot if allowed,
   restore polling baseline if captured, `resetController()` if mutated/not restored,
   `resumePolling()`, unregister the vendor-NCI callback, `callbacks.shutdownNow()`, `stopTrace()`;
   errors → `event("ERROR", describe(th))` + return 1; normal → return 0.
7. Small compile-only jadx artifacts:
   - `MainActivity.imageSlider`: anonymous `OnSeekBarChangeListener` had a bogus constructor
     argument and a synthetic `this$0` field; removed both (`MainActivity.this.prepareSelectedImage()`).
   - `MainActivity.render`: undefined `r1.getVisibility()` → `linearLayout3.getVisibility()`.
   - `MainActivity.disableReader`: missing `try/catch` around `IsoDep.close()` in the close thread.
   - `RootProbe.main`: `RootProbe.this` used from a static context in two thread lambdas →
     the captured local `rootProbe`.
   - `RootProbe.initialize`: anonymous `InvocationHandler.invoke` needs `throws Throwable`.

No application behavior was intentionally changed by these repairs: reconstructed methods were
parameter/control-flow matched against the original `dexdump -d` output, and the only semantic
adjustments are Java-legal equivalents of the same operands (e.g. the `&&` form of the boolean
assignment above, and `>=` vs jadx's `>` for the refresh deadline where the dex used `if-gtz`).

## "dada" root backend enablement (0.5.2)

Device: Xiaomi 15, `ro.product.device=dada`, `ro.build.version.sdk=37`, Android 17
(verified with `adb shell getprop`).

`src/dev/inknfclab/DeviceSupport.java` is the single gate used by both the app
(`usesRootBackend()` → `DeviceSupport.useRoot`) and the root helper (`RootProbe.run` →
`DeviceSupport.verifiedRootProfile`), so enabling it here enables the root/7A path everywhere:

```diff
-    public static boolean verifiedRootProfile(String str, int i) {
-        return "pudding".equals(str) && i == 37;
-    }
+    public static boolean verifiedRootProfile(String str, int i) {
+        return i == 37 && ("pudding".equals(str) || "dada".equals(str));
+    }
```

`summary()` keeps its message contract and now reports, for a possible NFC device with a verified
root profile: `dada` → "小米 15：ST54L Root 7A 配置已启用（实验）；仍需原机固件接口与授权。",
`pudding` keeps the original 小米 17 text, and non-verified devices keep the previous
standard-ISO-DEP / no-NFC messages. `useRoot()` is unchanged
(`z && !z2 && verifiedRootProfile(str, i)`); i.e. root is still only used when NFC exists and the
user has not forced "标准路径".

Host-JVM check of the compiled class (`build/verify/DeviceSupportCheck.java`):
`verifiedRootProfile(dada,37)=true`, `(pudding,37)=true`, `(dada,36)=false`, `(other,37)=false`,
`useRoot(dada,37,nfc=true,standardOnly=false)=true`, `standardOnly=true → false`.

## 0.5.3 — report the 7A firmware limitation (final investigation state)

Final investigation result (device dada, ST54L FW 02.04.1188): the firmware does **not** support
the custom 7A poll-frame command — polling-config reads/writes involving the 7-bit 0x7A wake frame
are rejected with ST/NCI `SYNTAX_ERROR 0x05`; the panel also does not answer standard NFC polling,
so the root 7A path cannot wake it. Root/Frida/MIFARE compatibility and RF diagnostics do work; a
physical RF test with the panel present showed no RF-response evidence. The app therefore stops
advertising the root 7A path as working on dada and reports the limit.

Changes (minimal, no UI rewrite):

1. `DeviceSupport.java`
   - `verifiedRootProfile()` still accepts dada + SDK 37 (root diagnostics/Frida/APC stay enabled).
   - `summary()` for dada now says:
     "小米 15（dada）：已实测确认本机 ST54L 固件 02.04.1188 不支持 7A 自定义寻卡帧
     （NCI SYNTAX_ERROR 0x05），无法发射 7-bit 0x7A 唤醒帧；Root/Frida/MIFARE 兼容与 RF
     诊断可用，标准 NFC 轮询也无法唤醒需要 7A 的驱动板。"
   - new `wakeLabel(String device)`: `pudding` → "已验证 Root 唤醒",
     `dada` → "Root 诊断 · 本机固件不支持 7A", otherwise → "标准 NFC（不能保证 7A 唤醒）".
2. `MainActivity`
   - `confirmUpload()` 刷图确认对话框 now prints `DeviceSupport.wakeLabel(Build.DEVICE)` instead of
     the old `usesRootBackend() ? "已验证 Root 唤醒" : …` ternary, so it is honest on dada.
   - `m5lambda$helperLine$0…` handles the new `CAPABILITY_7A` helper event → state text
     "本机固件不支持 7A 唤醒 · 查看日志与建议"; `CHECKED` keeps that text when the capability was
     reported as unsupported (instead of overwriting it with "NCI 查询成功").
3. `RootProbe.java`
   - new `private volatile boolean sevenAUnsupported;`.
   - `exchange(oid, subOid, …)`: when oid == 1 (polling config), response `poll[1] == 0x05`
     (`SYNTAX_ERROR`) and the sub-OID byte `bArr2[0]` is 0x15 (read polling frames) or 0x13
     (set polling frames), it sets the flag, emits `CAPABILITY_7A` **once**:
     "本机 ST54L 固件不支持 7A 自定义寻卡（ST 状态 0x05）：无法发射 7-bit 0x7A 唤醒帧。
     Root/Frida/MIFARE 兼容与 RF 诊断可用；建议升级 NFC 固件或使用外置 PN532 读卡器。"
     and throws `IllegalStateException("本机 ST54L 固件不支持 7A 自定义寻卡（ST 状态 0x05）：
     无法发射 7-bit 0x7A 唤醒帧。")`.
   - `run(mode)`: the baseline `readFrames()` is wrapped. For `inspect` with `sevenAUnsupported`
     it emits `CHECKED` ("厂商 NCI 接口正常；本机固件不支持 7A 唤醒（已实测）") and returns 0 (the
     normal `finally` cleanup does resume-polling / unregister callback / `stopTrace()` /
     `callbacks.shutdownNow()`); for `probe`/`upload` the error still propagates and the helper
     exits 1. No success is claimed anywhere.

APK: `lab-rebuild/dist/InkNfcLab-0.5.3.apk`, sha256
`22db903138480a244620ef3da2d24991f78f4cae1c224a5f9b0f18653a3080f5`, versionCode 14 /
versionName 0.5.3. `apksigner verify`: Verifies (v2+v3, same local debug key as 0.5.2), so the
0.5.2→0.5.3 install was a plain `adb install -r` update (no uninstall needed).

### Device verification (root helper run directly on dada, 2026-11-02)

Shell is already root via KernelSU; APK path from `pm path dev.inknfclab`:

```sh
CLASSPATH=/data/app/~~9AWsPLfRnEWUD0AoO3nrJA==/dev.inknfclab-XZgsd3gta3Vs-LSk65_0Fw==/base.apk \
  app_process /system/bin dev.inknfclab.RootProbe inspect /data/local/tmp/cap053.snapshot <apk> 0
```

`inspect` output (saved to `lab-rebuild/build/capability-7a-053.txt`), shell exit code 0:

```json
{"event":"ROOT","message":"root 辅助进程已启动，设备 24129PN74C"}
{"event":"CONNECTED","message":"厂商 NCI 回调已注册"}
{"event":"PAUSED","message":"已暂停轮询，连续执行配置事务"}
{"event":"NCI_TX","message":"GID=0F OID=1 15"}
{"event":"NCI_RX","message":"1505"}
{"event":"CAPABILITY_7A","message":"本机 ST54L 固件不支持 7A 自定义寻卡（ST 状态 0x05）：无法发射 7-bit 0x7A 唤醒帧。Root\/Frida\/MIFARE 兼容与 RF 诊断可用；建议升级 NFC 固件或使用外置 PN532 读卡器。"}
{"event":"NCI_TX","message":"GID=0F OID=1 15"}
{"event":"NCI_RX","message":"1505"}
{"event":"NCI_TX","message":"GID=0F OID=1 15"}
{"event":"NCI_RX","message":"1505"}
{"event":"CHECKED","message":"厂商 NCI 接口正常；本机固件不支持 7A 唤醒（已实测）"}
{"event":"RESUMED","message":"已请求恢复轮询"}
{"event":"EXIT","message":"辅助进程退出，代码 0"}
EXIT=0
```

`probe` mode (also run for completeness; saved to
`lab-rebuild/build/capability-7a-053-probe.txt`): same `NCI_RX 1505` → `CAPABILITY_7A` first, then
`{"event":"ERROR","message":"IllegalStateException: 本机 ST54L 固件不支持 7A 自定义寻卡（ST 状态 0x05）：无法发射 7-bit 0x7A 唤醒帧。"}`,
`RESUMED`, `TRACE_SUMMARY`, `{"event":"EXIT","message":"辅助进程退出，代码 1"}` with shell
`EXIT=1` — probe/upload fail honestly while inspect reports the capability.

After both runs: `dumpsys nfc` → `mState=on`; no leftover `app_process … RootProbe` processes.
The helper only paused/resumed normal polling and never wrote RF/polling config (the failure
happens at the read step, before `save`/`applyRf`/`setFrames`).

App install/launch: `adb install -r` on top of 0.5.2 → Success (same keystore); `dumpsys package
dev.inknfclab` → versionCode 14 / versionName 0.5.3; `am start -W` → `Status: ok`, COLD 171 ms;
no FATAL/ANR in >10 s of logcat. The unrelated `dev.inknfclab.test` package (versionName
0.5.10-test / versionCode 21) was **not** touched — it is not built by this team.

## 0.5.4 — D1 pre-charge + retry (pudding HAL/FW runtime overlay experiment)

Context (device experiment, 2026-11-02): the Xiaomi 17 (pudding, OS3) ST54L HAL + FW was overlaid
on dada at runtime (no APK overlay). NFC works and the chip FW was upgraded 02.04.1188 →
02.07.2128: the 7A polling frame SET (`0x13`) returns `1300` and GET (`0x15`) returns
`012A020A7A`. The physical Huawei panel was discovered (Tag UID=62EB9352, IsoDep+NfcA) and ISO-DEP
connected, but the first `D1` (`00 D1 00 00 00`) sent immediately after activation returned SW
`6403` (ST "execution not completed" family). Likely causes: the passive panel needs a moment of
continuous field to power up (upstream v2.17 added a 0–30 s "pre-charge" option) and/or MIFARE
reselection interference right after activation.

Patch (only `MainActivity`'s reconstructed `onTagDiscovered` lambda):

```diff
+    private static final int PRE_CHARGE_MS = 2000;
...
             this.connection = isoDep;
             isoDep.connect();
             isoDep.setTimeout(3000);
             log("ISO-DEP 已连接 / maxTransceive=" + isoDep.getMaxTransceiveLength());
+            try {
+                Thread.sleep(PRE_CHARGE_MS);
+            } catch (InterruptedException e) {
+                Thread.currentThread().interrupt();
+                throw new IllegalStateException("D1 充能等待被中断", e);
+            }
+            log("D1_CHARGE · 已连接，保场充能 " + PRE_CHARGE_MS + "ms");
             log("D1 TX " + Protocol.hex(Protocol.READ_INFO));
-            byte[] d1 = isoDep.transceive(Protocol.READ_INFO);
+            byte[] d1 = null;
+            String statusWord = "缺少状态字";
+            for (int attempt = 1; ; attempt++) {
+                d1 = isoDep.transceive(Protocol.READ_INFO);
+                int length = d1 == null ? 0 : d1.length;
+                if (length >= 2) {
+                    statusWord = String.format(Locale.US, "%02X%02X", Integer.valueOf(d1[length - 2] & 255), Integer.valueOf(d1[length - 1] & 255));
+                }
+                if (length >= 2 && (d1[length - 2] & 255) == 0x90 && (d1[length - 1] & 255) == 0x00) {
+                    break;
+                }
+                if (attempt >= 3) {
+                    throw new IllegalArgumentException("D1 读取失败：SW=" + statusWord);
+                }
+                log("D1_RETRY · attempt=" + attempt + " SW=" + statusWord);
+                try {
+                    Thread.sleep(800L);
+                } catch (InterruptedException e) {
+                    Thread.currentThread().interrupt();
+                    throw new IllegalStateException("D1 重试等待被中断", e);
+                }
+            }
             this.d1Received = true;
             log("D1 RX " + Protocol.hex(d1));
             Protocol.DeviceInfo info = Protocol.parseDeviceInfo(d1, tag.getId());
```

Semantics: charge 2000 ms of continuous field after ISO-DEP connect, then up to 3 D1 attempts (the
1st after the charge); success = response ends in `90 00`; failed attempts 1–2 log
`D1_RETRY · attempt=N SW=xxxx` and sleep 800 ms; after the 3rd failure throw
`IllegalArgumentException("D1 读取失败：SW=xxxx")`. Everything after a `9000` response
(`d1Received`, `D1 RX`, `parseDeviceInfo`/`deviceInfo` validation, D8/PIN/D3/D4/DE transfer) is
unchanged, as is all other behavior. Version: versionCode 15 / versionName 0.5.4; APK sha256
`c266616e34cb3bf5741e0450f67f8e2fd7d8e7da8de88720e6bb4e51d9180acb`; packaged dex contains the new
strings and `Thread.sleep` ×2 + `String.format` in the on-tag-discovered method.

Verified on device: `adb install -r` on top of 0.5.3 → Success (same keystore, versionCode 15 /
versionName 0.5.4); `am start -W` → `Status: ok`, COLD 125 ms, foreground; no FATAL/ANR in >10 s of
logcat; `dumpsys nfc` → `mState=on`. No NFC refresh/probe was run from the app for this build (the
user runs the physical test); the runtime HAL/FW overlay under `/odm`/mounts was not touched
(no reboot/unmount).

## How the rebuilt APK was verified

- **Dex contents** (extract `classes.dex` from the signed APK, `dexdump -d`):
  - `MainActivity.m7lambda$onTagDiscovered$0…` present and calls
    `IsoDep.connect`, `IsoDep.transceive`, `Protocol.parseDeviceInfo`, `ImageTransfer.send`;
  - `ImageTransfer.send` calls `ImageTransfer.refresh` (which calls `Protocol.parseRefreshStatus`,
    transceive, setTimeout, sleep) and `Protocol.authentication` for the D8/PIN step;
  - `ResearchQuantizer.quantize` present with the full validation/kernel loop (matches original);
  - **no** `UnsupportedOperationException("Method not decompiled: …")` and no
    `Method not decompiled`/`Method dump skipped` strings in the packaged dex;
  - behavior strings present: "标签没有 ISO-DEP 技术，无法发送 D1",
    "屏幕刷新已由 DE 返回 009000 确认", "REFRESH_FAILED", "TIMEOUT_RESTORE_FAILED",
    "无效的研究算法参数", "图片尺寸无效或过大", "设置读回不匹配".
  - Note: running jadx 1.5.1 back over the rebuilt dex *also* fails to decompile
    `onTagDiscovered$0` and `ResearchQuantizer.quantize` ("Method not decompiled") — this is the
    same decompiler limitation that produced the original stubs, not a problem with the rebuilt
    bytecode; `dexdump -d` shows the real instruction stream.
- **Assets**: byte-identical to the original APK's assets (sha256 compared for all four files);
  `assets/frida-inject` (84 MB) and `assets/mifare-compat.js` are packaged (deflated) in the APK.
- **Signature**: apksigner v2+v3 (no v1 needed at minSdk 26), 1 signer.

## Remaining risks / notes

- **7A wake on dada is a confirmed firmware limitation, not a software bug.** ST54L FW 02.04.1188
  rejects the 7-bit 0x7A polling command with `SYNTAX_ERROR 0x05`; the panel does not answer
  standard NFC polling. 0.5.3 reports this in the UI/dialog and in the root-helper events; the root
  path remains available on dada only for diagnostics / Frida / MIFARE compatibility / RF APC work.
- The reconstructed `onTagDiscovered`/`refresh`/`quantize` paths have **not** been exercised on
  hardware from this rebuild (no NFC send/refresh was performed on purpose — another teammate owns
  vendor-NCI experiments). Verification is bytecode/logic-level; the first real刷图 run should be
  treated as the acceptance test and watched in `adb logcat`.
- `ResearchQuantizer.quantize`'s numerical output was not diffed against the original dex bytecode
  output on real images; the loop/operation order follows the raw dump 1:1 but a pixel-level diff
  (original APK vs. rebuilt) is a good next check before relying on the non-default algorithms.
- The root path additionally needs the device-side helper files installed by another teammate:
  `/data/local/tmp/ink-frida-server` (Frida 17.24.0) and the app extracts
  `/data/local/tmp/ink-nfc-frida-inject-17.24.0` + `ink-nfc-mifare-compat.js` from its own assets.
  `RootProbe.run` still validates `/odm/etc/libnfc-hal-st.conf` contains `st21nfc` + `st54l`.
- The rebuild is signed with `keystore/debug.keystore` (local debug key), so on any device where the
  original prototype is installed you must `adb uninstall dev.inknfclab` before installing a rebuild.
- `build.py` suppresses aapt2's injected `compileSdkVersion`/`platformBuildVersion` metadata
  (`--no-compile-sdk-metadata`), so badging shows no compileSdkVersion; this does not affect
  runtime behavior.
- The `MainActivity.deviceReport()` string still says "Ink NFC Lab 0.5.0" (decompiled literal left
  untouched to avoid behavior/string changes); APK metadata is 0.5.3.
- Only the framework APIs needed by the decompiled code were compiled against
  `platforms/android-36.1/android.jar`; the vendor NCI APIs remain reflection-based, as in the
  original (they are not in the public SDK).
