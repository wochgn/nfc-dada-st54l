# 崩溃分析 · 2026-11-02 夜间「刷入 haotian 模块后 NFC 打不开」

设备：Xiaomi 15 `dada`（24129PN74C），系统 `Xiaomi/dada/dada:17/CP2A.260605.016/OS4.0.0.23.XOCCNXM`，KernelSU。
事件：用户在 KernelSU 中直接安装第三方 `Freeze-Over/nfc-15pro-st54l` 的 Release `nfc-odm17.zip`（为 **小米 15 Pro `haotian` + Android 16 / HyperOS OS3** 打包）并重启；随后 NFC 无法打开。用户已自行卸载模块并重启。

## 1. 崩溃日志在哪里（本次实际读取，均只读）

| 日志 | 路径 | 内容 |
|---|---|---|
| KernelSU 每次启动的 logcat | `/data/adb/ksu/log/logcat.old.log`（失败的那次启动）、`logcat.log`（当前启动） | 完整 Android logcat（含 system_server/NfcService/init 输出） |
| KernelSU 每次启动的 dmesg | `/data/adb/ksu/log/dmesg.old.log`、`dmesg.log` | 内核 + **init 服务启动日志**（关键证据在这里） |
| Android crash buffer / dropbox | `logcat -b crash -d`、`/data/system/dropbox/` | init SIGABRT、system_server SIGSEGV、system_app_anr 等 |
| 模块自身日志 | `/data/local/tmp/nfc-odm17.log` | post-fs-data / service.sh 输出 |
| 本机留存副本 | `stack-port/crash-analysis/` | logcat.old/current、dmesg.old/current、crash-buffer、dropbox 列表、模块日志等 |

## 2. 根因（决定性证据）

失败那次启动的 dmesg（`dmesg.old.log`，启动后 6.36 秒）：

```
init: Could not start service 'nfc_hal_service' as part of class 'hal':
File /odm/bin/hw/android.hardware.nfc-service-st
(labeled "u:object_r:system_file:s0") has incorrect label
or no domain transition from u:r:init:s0 to another SELinux domain defined.
```

对比当前正常启动的 dmesg（`dmesg.log`）：

```
init: starting service 'nfc_hal_service'...
KernelSU: hook_manager: unmark 2183 exec /odm/bin/hw/android.hardware.nfc-service-st
init: ... started service 'nfc_hal_service' has pid 2183
servicemanager: Caller(pid=2183,uid=1027,sid=u:r:hal_nfc_default:s0)
                Found android.hardware.nfc.INfc/default in device VINTF manifest.
```

结论链：

1. 模块 `post-fs-data.sh` 用 tmpfs + bind mount 覆盖了 `/odm/bin/hw/android.hardware.nfc-service-st`。
2. 覆盖文件的 SELinux 标签是 **`u:object_r:system_file:s0`**，而该服务路径必须为 **`u:object_r:hal_nfc_default_exec:s0`**（init 需要 `init → hal_nfc_default` 的域转换）。
3. **init 拒绝启动 `nfc_hal_service`**，AIDL NFC HAL 从未注册。
4. `com.android.nfc`（NfcService）一直等不到 `android.hardware.nfc.INfc/default`：

```
libbinder.ServiceManagerCppClient: Waited one second for android.hardware.nfc.INfc/default ...
com.android.nfc: Timeout waiting for NFC AIDL service (future).
```

5. 结果 = NFC 服务初始化失败 / 无法打开。

## 3. 为什么标签会错（与模块作者 15 Pro 环境的差异）

模块脚本标了 `chcon ... 2>/dev/null`（**静默忽略失败**）：

```sh
put() {
  cp -f "$1" "$2" || return 1
  chmod "$3" "$2"
  chcon "$4" "$2" 2>/dev/null
}
put ".../android.hardware.nfc-service-st" "$TMP/service" 755 u:object_r:hal_nfc_default_exec:s0
```

在 dada 的本次启动里，`chcon` 显然没有生效（最终标签仍是 `system_file`）。差异可能来自：

- dada 的 Surface 是 Android 17 系统（`dada:17/...OS4.0.0.23.XOCCNXM`），SELinux 策略 / 早期启动上下文与 haotian（Android 16）不同；
- KernelSU 在 post-fs-data 阶段执行脚本时的 SELinux 上下文、`CAP_MAC_ADMIN` 能力与目标类型允许集合不同；
- 模块文件在安装/拷贝后自身标签可能就是 `system_file`，而 tmpfs 默认标签与预期不同。

无论具体原因，**上游脚本未校验标签**（`ls -Z` 没检查、chcon 失败被吞），所以一旦策略差异出现，就表现为「HAL 起不来」。

**不是** NFC 固件被刷坏：失败启动的 dmesg 中 `Firmware update` 出现 0 次；当前原厂文件的 SHA-256 与备份一致（FW `15da4aa9…c8e9dc`），当前启动 HAL 正常 `StNfc_hal_open ST21NFC AIDL HAL Version 150-20240927-24W39p0`，NfcService 已正常使能。

## 4. 其他日志（非根因）

- 模块 `service.sh` 在 NFC 不可用时调用 `q13.jar`，导致：
  ```
  FATAL EXCEPTION: main
  java.lang.NullPointerException: null receiver
      at java.lang.reflect.Method.invoke(Native Method)
      at dev.probe.Q13.main(Q13.java:51)
  ```
  这是「NFC 已经不可用」的**后果**，不是原因。
- 当前（已卸载模块后）启动中还有一次 **system_server SIGSEGV**（23:01:13，`system_server_native_crash@…`，空指针，栈在 dex/JIT 数据）以及早期 init SIGABRT 记录；设备随后正常工作，NFC 也正常。这类早期启动崩溃在刷模块前的启动中也有类似记录（init 子进程 SIGABRT），判断与 NFC 故障无直接因果；但若后续频繁出现，可再做一次干净重启观察。

## 5. 对下一次尝试的要求（给后续 pudding Android 17 ROM 方案）

1. **必须使用与系统 Android 版本匹配的 pudding 栈**（dada 为 Android 17/API 37）。失败原因里还有一层尚未触发的风险：模块内 `Nfc_st.apk` 是 Android 16（OS3）版本，而 dada 框架是 Android 17 —— 即使 HAL 标签修好，框架 APK / AIDL 版本仍可能不兼容。故建议从 pudding **Android 17（OS4）** ROM 提取：`nfc_nci.st21nfc.st.so`、`st54l_fw.bin`、`libnfc-hal-st.conf`、`st54l_conf.txt`、`android.hardware.nfc-service-st`、`Nfc_st.apk`。
   - 已确认 pudding OS3 的 HAL service 也是 **AIDL**（`android.hardware.nfc.INfc`，`ST21NFC AIDL HAL Version 25Q2-BP2A-20250620-PRC-25W25p0`），并非 HIDL；它与 dada 原厂 `150-20240927-24W39p0` 同族但更新，需要核对 AIDL 版本。
2. **标签必须校验**：改完挂载后 `ls -Z` 逐项校验；若 `chcon` 在当前启动上下文仍失败，改用其它方案（例如按目标标签分别以 `context=` 选项挂 tmpfs；或先在可写分区用 root 正常设置标签再 bind；失败则**不要挂载**，保持原厂 NFC 可用）。
3. **先做“无风险标签实验模块”**：只创建 tmpfs、`cp`、`chcon`、`ls -Z` 打印、`umount`，不覆盖任何系统文件，重启验证 dada 的早期启动上下文是否允许这些标签；确认后再做完整 overlay。
4. **不要覆盖 APK 首轮测试**：先只替换 HAL/固件/配置（保持 dada 的 Nfc_st.apk），若框架拒收 ST 激活再考虑 APK（并确保来自 Android 17 的 pudding）。
5. **可回滚**：运行时 overlay（boot 后手工 mount）优先，失败立即 `umount` + 重启 HAL；或模块内置“标签校验失败即全部 umount”。
6. 保留备份：本机 `/data/local/tmp/nfc-backup-odm/` 与主机 `stack-port/backup-odm/` 保存了原厂 5 个文件（SHA-256 已记录）。

## 6. 原始证据摘要（可引用）

失败启动（boot A）：
```
init: Could not start service 'nfc_hal_service' as part of class 'hal':
File /odm/bin/hw/android.hardware.nfc-service-st (labeled "u:object_r:system_file:s0") has incorrect label ...
libbinder.ServiceManagerCppClient: Waited one second for android.hardware.nfc.INfc/default
com.android.nfc: Timeout waiting for NFC AIDL service (future).
```
当前启动（boot B，模块已卸载）：
```
init: starting service 'nfc_hal_service'...
init: ... started service 'nfc_hal_service' has pid 2183
servicemanager: Caller(pid=2183,uid=1027,sid=u:r:hal_nfc_default:s0) Found android.hardware.nfc.INfc/default ...
StNfcHal: HAL st21nfc: StNfc_hal_open ST21NFC AIDL HAL Version 150-20240927-24W39p0
NfcService: Enabling NFC
```
