# 厂商二进制说明（本仓库不包含）

模块需要 **Xiaomi 17（pudding，OS3.0.319）** 的 ST54L NFC 栈 5 个文件。
它们版权归 Xiaomi / STMicroelectronics 所有，**本仓库不转分发**；请从
你自己的设备/固件备份中提取，并**核对下方 SHA-256**。

来源可选：

1. 你自己的小米 17（pudding）OS3.0.319 固件 `/odm` 分区：
   - `odm/bin/hw/android.hardware.nfc-service-st` → `nfc-service-st`
   - `odm/lib64/nfc_nci.st21nfc.st.so` → `nfc_nci.st21nfc.st.so`
   - `odm/firmware/st54l_fw.bin` → `st54l_fw.bin`
   - `odm/etc/libnfc-hal-st.conf` → `libnfc-hal-st.conf`
   - `odm/etc/st54l_conf.txt` → `st54l_conf.txt`
2. 已公开的 `Freeze-Over/nfc-15pro-st54l` Release `nfc-odm17.zip`（小米 17
   栈，MIT 项目打包）——**下载后务必核对哈希**，不要直接使用来源不明的二进制。

| 目标文件名 | 大小（字节） | SHA-256 |
|---|---|---|
| `nfc-service-st` | 86,400 | `180fd75773f8eaf568fc10e54df9ec1fde6f0f99164daf5880ae7ecba13bae88` |
| `nfc_nci.st21nfc.st.so` | 283,656 | `e3ea02c60951c227233fbf359a73ef5aebcbd7214a62d5a0f96312eb9a272def` |
| `st54l_fw.bin` | 260,157 | `22a9e85dfe459676f401fbc67cf9870176c7d9c583aefd9963701b2e112fbe4d` |
| `libnfc-hal-st.conf` | 7,177 | `fd2db7dbdfcda7b4710edf60c0d270bf06bcfb662174771a0fe541a71188c6f9` |
| `st54l_conf.txt` | 6,771 | `035aba67836fb28eb130bfece420dfd26b4210056dbe0a0c107d0bc1260d0007` |

## 原厂 dada 文件（回滚/比对用，同样不在仓库）

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `android.hardware.nfc-service-st` | 86,376 | `3a7abef8…2f83d`（完整值请从自身设备备份记录） |
| `nfc_nci.st21nfc.st.so` | 200,560 | `db667826…80c6` |
| `st54l_fw.bin` | 250,917 | `15da4aa9…e9dc` |
| `libnfc-hal-st.conf` | 6,282 | `045029d1…aacd` |
| `st54l_conf.txt` | 6,759 | `9a93d8d6…bc83` |

> 原厂固件 `st54l_fw.bin` 的 fileFwVersion 为 `0x02041188`；pudding 的是
> `0x02072128`。芯片当前实际固件可用本仓库 `tools/inkprobe` 的 `mfg` 模式读取。
