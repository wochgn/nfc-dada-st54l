# 事故分析：系统 OTA 到 OS4.0.3.0 后 NFC 打不开（2026-11-12）

## 现象

- 用户将小米 15 `dada` 从 `OS4.0.0.23.XOCCNXM` OTA 升级到 **`OS4.0.3.0.XOCCNXM`**（Android 17，指纹 `Xiaomi/dada/dada:17/CP2A.260605.016/OS4.0.3.0.XOCCNXM`）。
- 升级后 NFC 完全打不开：`dumpsys nfc` → **`Can't find service: nfc`**；系统设置里 NFC 无法开启。
- 但 NFC HAL 进程在跑（`android.hardware.nfc-service-st`，`init.svc.nfc_hal_service=running`，`nfc.initialized=true`），`/odm` 上 5 个文件是我们模块 overlay 的 pudding 文件。

## 关键证据

### 1. 原厂 /odm NFC 文件没有被 OTA 改动

把 dm-45（/odm 的 dm 设备）只读挂到 `/mnt/odm_ro` 后比对，OS4.0.3.0 的原厂文件与旧版
`OS4.0.0.23` **完全一致**：

| 文件 | 大小 | SHA-256 | 结论 |
|---|---|---|---|
| `android.hardware.nfc-service-st` | 86,376 | `3a7abef8…2f83d` | 未变 |
| `nfc_nci.st21nfc.st.so` | 200,560 | `db667826…80c6` | 未变 |
| `st54l_fw.bin` | 250,917 | `15da4aa9…e9dc` | 未变 |
| `libnfc-hal-st.conf` | 6,282 | `045029d1…aacd` | 未变 |
| `st54l_conf.txt` | 6,759 | `9a93d8d6…bc83` | 未变 |

所以**不是固件/厂商栈被 OTA 换掉导致的不兼容**，芯片固件也仍是 `02.07.2128`。

### 2. NfcService 在开机时启动 ANR，之后系统服务再没注册

dropbox 里有两份系统 App ANR（`/data/system/dropbox/`）：

```
Process: com.android.nfc
PID: 17856 / 15216
Package: com.android.nfc v37 (17)
Build: Xiaomi/dada/dada:17/CP2A.260605.016/OS4.0.3.0.XOCCNXM:user/release-keys
Subject: Process ProcessRecord{…:com.android.nfc/1027} failed to complete startup
Timestamp: 2026-11-11 13:11:30.881 / 13:11:23.169
```

即 `com.android.nfc` 进程在启动阶段卡死超过 2 秒被判 ANR、随后被杀；`NfcService`
因此没有向 servicemanager 注册 `nfc` 服务，系统 UI 与 `dumpsys nfc` 都找不到它。
ActivityManager 长期停留在：

```
Persisent processes that are starting:
  Restarting PERS # 0: ProcessRecord{9659bc4 17856:com.android.nfc/1027}
```

### 3. 根因：模块 v1.1 的 `service.sh` 在开机启动过程中 kill HAL / NfcService

模块日志（`/data/local/tmp/nfc-dada-pudding.log`）显示，在 OTA 后这次启动中：

```
=== Wed Nov 11 13:09:25 CST 2026 service ===
boot completed
killed HAL 2284
killed NfcService 10011
NFC not on, retry
killed HAL 9784
killed NfcService 16074
```

而同一时间段系统还在启动中（`SystemServer: Entered the Android system server!`
在 13:09:31 之后），HAL 在 13:09:23 才刚由 init 拉起。`service.sh` 的
“boot_completed 后重启 HAL/NfcService”逻辑与新系统的启动时序错位：

1. HAL 刚启动，NfcService 正在由 SystemServer 绑定初始化；
2. 模块 `service.sh` 把它 kill 掉；
3. NfcService 启动过程中依赖的 HAL 连接被打断 → 主线程阻塞 → **startup ANR**；
4. 进程被杀后 `nfc` binder 服务未注册，后续不再恢复。

旧系统（OS4.0.0.23）上同样的 kill 操作发生在 NFC 初始化完成之后，框架能重连，所以
没有出问题；OTA 后启动时序变化暴露了这个隐患。

## 修复（模块 v1.2）

**v1.2 完全移除开机阶段的 HAL/NfcService 重启逻辑**：

- `post-fs-data.sh`：只做 overlay + `ls -lZ` 标签校验（失败不挂载），并在已挂载时
  幂等退出；
- `service.sh`：改为**只验证/记录**（等待 `sys.boot_completed`，打印 overlay 状态与
  `dumpsys nfc`），不 kill 任何进程、不重挂；
- `action.sh`：只重放 overlay（KernelSU 动作按钮），不重启服务。

验证（2026-11-12 重启后）：

```
mState=on
android.hardware.nfc-service-st pid=2146, com.android.nfc pid=5784
nfc.initialized=true, init.svc.nfc_hal_service=running
HAL: StNfc_hal_open ST21NFC AIDL HAL Version 25Q2-BP2A-20250620-PRC-25W25p0
芯片固件: 02.07.2128
0x13 SET 7A: resp=1300
```

即 NFC 工作正常，且刷屏能力（7A）在新系统上仍然可用。

## 复现/恢复步骤（供参考）

如再次遇到类似问题：

1. 先确认是 `com.android.nfc` 启动 ANR（`dumpsys nfc` 无服务 + HAL 在跑 + dropbox
   有 `failed to complete startup`），而不是 HAL 起不来（标签错误会显示 init
   `Could not start service ... incorrect label`）。
2. 将模块更新到 v1.2（或临时把模块内 `service.sh` 改名禁用），重启；
3. 若仍异常：`ksud module uninstall nfc-dada-pudding` + 重启，恢复原厂 NFC；
4. 手动检查：`cat /data/local/tmp/nfc-dada-pudding.log`。

## 结论

- OTA 没有改坏 NFC 固件/厂商文件；
- 故障是模块 v1.1 的 `service.sh` 在启动时序中的 kill 操作导致的 NfcService
  startup ANR；
- v1.2 改为“只 overlay、不重启服务”后，在 OS4.0.3.0 上 NFC 与刷屏能力均正常。
