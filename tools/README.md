# tools/ — 设备端诊断工具（源码）

## inkprobe/Probe.java

一个 root 诊断程序（以 `app_process` 运行），用于在设备上读取 ST54L 信息、
发送厂商 NCI 命令、抓取 NCI 调试通知。编译时需要 `framework-nfc.jar`
（来自设备 `/system/framework/framework-nfc.jar`，包含 vendor 扩展
`NfcAdapter.sendVendorNciMessage` / `registerNfcVendorNciCallback`）。

主要模式：

| 模式 | 作用 |
|---|---|
| `info` | 打印 NfcAdapter / vendor API / OemExtension 能力 |
| `read` | 读取 RF 配置与自定义轮询帧（GET `0x15`，只读） |
| `set7a` | 设置 `7A` 轮询帧（SET `0x13`） |
| `restore` | 恢复保存的轮询配置 |
| `mfg` | 读取 ST 芯片身份与固件版本（manufacturer data） |
| `nci` / `testcmd` | 通用厂商 NCI 交互、TEST_DATA 等实验 |
| `testdata` / `td2` | ST TEST_DATA 相关实验（前期探测用） |
| `set / restore rf` 等 | APC 参数实验（见 `docs/rebuild-notes.md`） |

构建方式（本机）：

```bash
javac -cp android.jar:framework-nfc.jar -d out src/dev/inkprobe/Probe.java
d8 --lib android.jar --output out/dex out/dev/inkprobe/*.class
# 打包成 dex/jar 后 push 到设备 /data/local/tmp/inkprobe/，用 app_process 运行
```

输出为逐行 JSON（`{"tag":"INKPROBE","event":...}`），便于脚本解析。

> 该工具只做诊断/只读操作；`set7a`、`restore` 等会短暂暂停轮询，正常刷图不需要用到。
