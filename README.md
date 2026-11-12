# nfc-dada-st54l

在已 root 的 **Xiaomi 15（`dada`，ST54L NFC）** 上驱动 **华为 552×768 四色 NFC 墨水屏手机壳（A1）**。

> **状态：✅ 已打通（2026-11-02 实机成功，两次重启验证）。**
> 传图 476 包全部 `9000`，`D4` 刷新确认 `9000`，`DE` 忙闲确认返回 **`009000`**，屏上画面正常显示。

本项目是从 0 到跑通的完整记录与可复现交付：**KernelSU 持久化模块 + 改造版刷屏 App + 协议/逆向/崩溃分析文档**。

## 适用环境（已验证）

| 项目 | 值 |
|---|---|
| 手机 | Xiaomi 15 `dada`（24129PN74C） |
| 系统 | Android 17 / HyperOS `OS4.0.0.23` 与 `OS4.0.3.0.XOCCNXM`（均已验证；后者需模块 v1.2） |
| NFC 控制器 | STMicroelectronics **ST54L**（siliconRev 3） |
| Root | KernelSU（模块依赖 KernelSU/Magisk API） |
| 屏幕壳 | 华为 552×768 四色 NFC 墨水屏（A1，C0=201EA001，扫描方向 00） |
| App | `InkNfcLab-0.5.4.apk`（本仓库构建） |

## 为什么小米 15 原厂刷不了

- 屏只响应 **7-bit 私有短帧 `0x7A`**，普通 NFC 轮询（WUPA/`0x52`）它不理会。
- 小米 15 原厂 ST54L 固件 `02.04.1188` 的 HAL 缺少 `stpropnci` 引擎，**拒绝 NCI 自定义轮询帧命令**（`0x1E`/`0x1D` GET/SET 返回 `SYNTAX_ERROR 0x05`），无法发出 `0x7A`。
- 直接照搬小米 15 Pro 的 Android 16 整套模块会因 **SELinux 标签错误**（HAL 服务文件被标成 `system_file`）导致 init 拒绝启动 HAL，NFC 直接打不开（详见 [docs/CRASH-ANALYSIS-20261102.md](docs/CRASH-ANALYSIS-20261102.md)）。

## 方案（已实机验证）

1. **运行时 overlay 小米 17（pudding OS3.0.319）的 ST54L NFC 栈** 到 `/odm`（5 个文件，tmpfs + `chcon` + `mount --bind`，不写分区）：
   - HAL 与 lib 启动时自动把控制器固件从 `02.04.1188` 升级到 **`02.07.2128`**；
   - `0x13` 配置 `7A` 短帧返回 `1300`，`0x15` 读回 `012A020A7A`。
2. **KernelSU 持久化模块 `nfc-dada-pudding` v1.2**：开机 `post-fs-data` 自动执行上面的 overlay，并做**标签校验**（失败则不挂载、保留原厂 NFC）。v1.2 起**不在开机阶段 kill/重启 HAL 或 NfcService**（v1.1 在系统 OTA 后因此导致 NfcService startup ANR，见 [docs/OTA-OS4.0.3.0-incident.md](docs/OTA-OS4.0.3.0-incident.md)）。
3. **改造版 App `InkNfcLab-0.5.4`**：
   - ISO-DEP 连接后**保场充能 2000 ms** 再发 D1（原版直接发 D1 会收到 `6401/6403`）；
   - D1 失败最多重试 3 次；
   - 刷图时在调试面板把 **APC 实验档设为 `+2`**（原厂档在 DE 阶段会 `68CA` 低电压，+2 后成功）。

## 实测结果（用户确认屏已刷新）

```
D1 读取成功 552 × 768 / 4 色 固件 20 / UID 匹配 刷图配置兼容检查：通过
D3_CONFIRMED · 476/476 个图像包均收到 9000
D4 RX 9000 · elapsed=3841ms
DE RX 019000 × N  →  DE RX 009000 · elapsed=19265ms   （刷新完成）
RF_RESTORED · 三组原始 APC 参数已恢复并逐字节读回确认
```
完整日志与复现步骤：[docs/SUCCESS-20261102.md](docs/SUCCESS-20261102.md)。

## 仓库结构

```
app/                    改造版 App（APK、重建源码、构建脚本）
module/                 KernelSU 持久化模块源码与构建说明（不含厂商二进制）
scripts/                运行时 overlay 重放/回滚脚本、0x13 辅助工具 q13.jar
docs/                   协议/逆向/崩溃分析/成功记录/前期调查报告
tools/inkprobe/         设备端 root 诊断程序源码（Probe）
```

## 快速开始

> ⚠️ **本仓库不包含小米/ST 的厂商二进制**（版权原因）。需要从你自己的设备/固件中提取，并核对 SHA-256。风险自负：刷固件/改系统栈可能导致 NFC 暂时不可用。

### 0. 前置条件

- Xiaomi 15 `dada`，已 root（KernelSU），Android 17；
- 备份好原厂 `/odm` 文件（本项目实测所用原厂文件哈希在 [module/fetch-blobs.md](module/fetch-blobs.md)）；
- 华为四色墨水屏手机壳。

### 1. 准备 5 个厂商文件（pudding OS3.0.319 / Xiaomi 17）

从**你自己的**小米 17（pudding）OS3.0.319 固件 `/odm` 分区提取，或从你已有的备份中获取，放入
`module/nfc-dada-pudding/blobs/`，并核对 SHA-256（见 [module/fetch-blobs.md](module/fetch-blobs.md)）：

| 文件 | 大小 | SHA-256（前缀） |
|---|---|---|
| `nfc-service-st` | 86,400 | `180fd757…3bae88` |
| `nfc_nci.st21nfc.st.so` | 283,656 | `e3ea02c6…272def` |
| `st54l_fw.bin` | 260,157 | `22a9e85d…2fbe4d` |
| `libnfc-hal-st.conf` | 7,177 | `fd2db7db…c188c6f9` |
| `st54l_conf.txt` | 6,771 | `035aba67…6d0007` |

### 2. 构建并安装模块

```bash
cd module
sh build-module.sh          # 生成 nfc-dada-pudding.zip（需先放好 blobs）
adb push nfc-dada-pudding.zip /data/local/tmp/
adb shell su -c '/data/adb/ksu/bin/ksud module install /data/local/tmp/nfc-dada-pudding.zip'
adb reboot
```

重启后检查（应看到 `OK: overlay mounted` 与 `mState=on`）：

```bash
adb shell su -c 'cat /data/local/tmp/nfc-dada-pudding.log'
adb shell su -c 'dumpsys nfc | head -3'
```

### 3. 安装 App

```bash
adb install -r app/InkNfcLab-0.5.4.apk
```

### 4. 刷图

1. 打开 Ink NFC Lab → 右上 `⋯` → **连接诊断与实验设置** → **APC 实验档：+2**（每次刷图前都要切）；
2. 选择图片 → 预览 → **刷图**；
3. 把驱动板贴到手机背面顶部 NFC 区并保持贴合，直到 `DE` 返回 `009000`（约 20–30 秒）。

## 安全与回滚

- 模块只做**运行时 bind mount**，不写任何分区；`post-fs-data` 中 `ls -lZ` 校验 HAL 服务标签，**校验失败不会挂载**（保留原厂 NFC）。
- 回滚：KernelSU 管理器移除模块（或 `ksud module uninstall nfc-dada-pudding`）+ 重启。之后原厂 HAL 会把控制器固件按版本差异回写为 `02.04.1188`（厂商正常升级路径），NFC 恢复原厂行为，7A 刷屏能力消失。
- 无线应急脚本：`scripts/nfc-overlay-apply.sh` / `scripts/nfc-overlay-remove.sh`（运行时手动重放/回滚，不依赖模块）。
- 日志：`/data/local/tmp/nfc-dada-pudding.log`。

## 已知限制

- 仅验证了 Xiaomi 15 `dada` + Android 17 + 华为 A1 四色 552×768 壳；其他机型/屏型需重新核对 blobs 与协议。
- 刷图依赖 App 内 **APC +2**（实验档，耗电略增）；贴合位置/压力影响供电，`68CA` 低电压时需调整线圈位置重试。
- 首次开机 overlay 生效时会触发一次控制器固件升级（约几秒）；期间 NFC 任务可能失败，属正常。
- **系统 OTA 注意**：v1.1 模块在 OTA 到 `OS4.0.3.0` 后会导致 NFC 打不开（启动时序变化 + 模块在开机时重启 HAL/NfcService → NfcService startup ANR）；**v1.2 已修复**，OTA 后请先更新到 v1.2 再重启。详见 [docs/OTA-OS4.0.3.0-incident.md](docs/OTA-OS4.0.3.0-incident.md)。
- `FINAL_REPORT.md` 是前期（2026-11-02 白天）的完整调查报告，其“原厂不可行”的初步结论已被后续实验推翻，保留作历史记录。

## 许可与致谢

- `module/`、`scripts/`、`docs/`、`tools/` 等本项目原创部分：**MIT**（见 [LICENSE](LICENSE)）。
- `app/` 派生自 [wiwiwei/ink-nfc-lab](https://github.com/wiwiwei/ink-nfc-lab) 0.5.0（**PolyForm Noncommercial 1.0.0**，见 [LICENSE-APP](LICENSE-APP)），保留 kur0neko 的 Required Notice；APK 内附带 Frida 17.24.0 injector（wxWindows Library Licence）。
- 参考与感谢：[Freeze-Over/nfc-15pro-st54l](https://github.com/Freeze-Over/nfc-15pro-st54l)（MIT，15 Pro 方案）、[ZhaoGY-N/huawei-nfc-eink-android-app](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app)、[wiwiwei/ink-nfc-lab](https://github.com/wiwiwei/ink-nfc-lab)、[solar2ain/ink-nfc-fmsc-by-pn532](https://github.com/solar2ain/ink-nfc-fmsc-by-pn532)、[kur0neko/huawei-epd-pn532](https://gitee.com/kur0neko/huawei-epd-pn532)。
- 厂商二进制归 Xiaomi / STMicroelectronics 所有，**未包含在本仓库**，仅限个人研究用途。

详见 [NOTICE.md](NOTICE.md)。
