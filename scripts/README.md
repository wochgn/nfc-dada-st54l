# scripts/ — 运行时 overlay 与 7A 辅助工具

模块是首选方式；这些脚本用于**不装模块时手动重放 overlay**，或模块异常后的应急。

## `nfc-overlay-apply.sh`

把 `/data/local/tmp/nfcmod/` 里的 5 个 pudding 文件运行时挂载到 `/odm`：

```bash
# 1) 先把 5 个文件放到设备
adb shell su -c 'mkdir -p /data/local/tmp/nfcmod'
adb push nfc-service-st nfc_nci.st21nfc.st.so st54l_fw.bin libnfc-hal-st.conf st54l_conf.txt /data/local/tmp/nfcmod/
adb push scripts/q13.jar /data/local/tmp/

# 2) 执行（会做标签校验，失败则中止、不挂载）
adb push scripts/nfc-overlay-apply.sh /data/local/tmp/
adb shell su -c 'sh /data/local/tmp/nfc-overlay-apply.sh'
adb shell su -c 'cat /data/local/tmp/nfc-overlay.log'   # 期望 OK / mState=on
```

脚本会：挂载 tmpfs → 复制并 `chcon` 5 个文件 → `ls -lZ` 校验 HAL 服务标签
（失败退出 5，不挂载）→ `mount --bind` 到 `/odm` → 重启 HAL 与
`com.android.nfc` → 用 `q13.jar` 发送 `0x13` 配置 7A。

## `nfc-overlay-remove.sh`

卸载 5 个 bind mount 并重启 HAL/NFC（回到原厂文件；芯片固件由原厂 HAL 在下次
启动时按版本差异回写）。

## `q13.jar`

最小 root 辅助程序，用于发送 ST 厂商 NCI 命令：

```bash
adb shell su -c 'CLASSPATH=/data/local/tmp/q13.jar /system/bin/app_process /system/bin dev.probe.Q13'
# 期望输出：SEND 13012A020A7A send=0 resp=1300
```

它调用 `NfcAdapter.sendVendorNciMessage(1, 0x0F, 0x01, {0x13,0x01,0x2A,0x02,0x0A,0x7A})`
并 `resumePolling`；只在 halo 栈已 overlay（固件支持自定义轮询帧）时有效。

> 正常刷图不需要手动跑 q13：App 的 Root 后端会自己配置 7A。
