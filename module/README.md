# module/ — KernelSU 持久化模块 `nfc-dada-pudding`

在开机时把小米 17（pudding OS3.0.319）的 ST54L NFC 栈**运行时** overlay 到
Xiaomi 15 `dada` 的 `/odm`，使 7A 短帧刷屏能力在重启后自动生效。

- **不写任何分区**：只做 tmpfs + `mount --bind`；
- `post-fs-data.sh`：复制 blobs → `chcon` → `ls -lZ` **校验标签**；任一标签不对
  则**整体不挂载**（原厂 NFC 保持可用）；已挂载时幂等退出；
- `service.sh`（late_start）：**v1.2 起只验证/记录**（overlay 状态、`dumpsys nfc`），
  **不再 kill/重启 HAL 或 `com.android.nfc`** —— 旧版在 OTA 后的启动时序中重启服务
  导致 NfcService startup ANR，NFC 系统服务未注册（详见
  [../docs/OTA-OS4.0.3.0-incident.md](../docs/OTA-OS4.0.3.0-incident.md)）；
- `action.sh`：KernelSU 管理器“动作”按钮，只重放 overlay，不重启服务；
- 日志：`/data/local/tmp/nfc-dada-pudding.log`。

## 目录

```
nfc-dada-pudding/
├── module.prop
├── post-fs-data.sh
├── service.sh
├── action.sh
├── uninstall.sh
└── blobs/            ← 厂商二进制，本仓库不包含（见 fetch-blobs.md）
```

## 准备厂商文件

见 [fetch-blobs.md](fetch-blobs.md)。把 5 个文件放入 `nfc-dada-pudding/blobs/`，
文件名必须是：

```
nfc-service-st
nfc_nci.st21nfc.st.so
st54l_fw.bin
libnfc-hal-st.conf
st54l_conf.txt
```

## 构建

Linux/macOS:

```bash
sh build-module.sh
# -> module/nfc-dada-pudding.zip
```

Windows PowerShell:

```powershell
Compress-Archive -Path module\nfc-dada-pudding\* -DestinationPath module\nfc-dada-pudding.zip -Force
```

## 安装

```bash
adb push nfc-dada-pudding.zip /data/local/tmp/
adb shell su -c '/data/adb/ksu/bin/ksud module install /data/local/tmp/nfc-dada-pudding.zip'
adb reboot
```

（也可以用 KernelSU 管理器“从本地安装”选择 zip。）

## 安装后验证

```bash
adb shell su -c 'cat /data/local/tmp/nfc-dada-pudding.log'   # 期望 OK: overlay mounted
adb shell su -c 'dumpsys nfc | head -3'                      # 期望 mState=on
adb shell su -c 'sha256sum /odm/firmware/st54l_fw.bin'       # 期望 22a9e85d…2fbe4d
```

HAL 启动时会把控制器固件自动升级为 `02.07.2128`（原厂为 `02.04.1188`）。

## 卸载 / 回滚

```bash
adb shell su -c '/data/adb/ksu/bin/ksud module uninstall nfc-dada-pudding'
adb reboot
```

原厂 `/odm` 文件恢复；原厂 HAL 会在启动时按版本差异把控制器固件回写为
`02.04.1188`（厂商正常升级路径），7A 刷屏能力消失、NFC 恢复原厂行为。

## 注意

- 模块只针对 `dada`（Android 17 / OS4.0.0.23）验证；其他机型必须重新核对文件
  路径、标签与哈希。
- 若开机后日志显示 `ABORT`，模块不会破坏原厂 NFC；可用 `scripts/` 里的运行时
  脚本手动重放。
