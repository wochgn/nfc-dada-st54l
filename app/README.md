# app/ — Ink NFC Lab 0.5.4（刷屏 App）

本目录是适配小米 15 `dada`＋华为四色墨水屏的刷屏 App。它派生自
[wiwiwei/ink-nfc-lab](https://github.com/wiwiwei/ink-nfc-lab) 0.5.0：

- 0.5.0 原始 APK 为实验性调试签名；本项目通过 jadx 反编译 + IR 补全重建，
  再按需打补丁，产出版本 **0.5.1 → 0.5.4**（详见 `../docs/rebuild-notes.md`）。
- 许可：PolyForm Noncommercial 1.0.0（见 `../LICENSE-APP`、`../NOTICE.md`）。
- 保留 kur0neko 的 Required Notice；APK 内含 Frida 17.24.0 injector
  （wxWindows Library Licence）。

## 版本与 SHA-256

| 版本 | versionCode | SHA-256 |
|---|---|---|
| `InkNfcLab-0.5.4.apk` | 15 | `c266616e34cb3bf5741e0450f67f8e2fd7d8e7da8de88720e6bb4e51d9180acb` |

## 0.5.4 关键补丁（让 D1 成功）

原版 App 在 ISO-DEP 连接后立即发送 D1，dada + pudding 栈组合下 D1 返回
`6401`/`6403`（执行未完成）。0.5.4 在 `MainActivity.onTagDiscovered` 中：

1. `IsoDep.connect()` 成功后先 `Thread.sleep(2000)` **保场充能**；
2. 再发 D1；失败最多重试 3 次（每次间隔 800 ms）；
3. 只有响应末尾是 `90 00` 才继续后续 D8/D3/D4/DE 流程。

## 构建

依赖：JDK 17+、Android SDK（`build-tools/36.1.0`：`aapt2`、`d8`、`zipalign`、
`apksigner`；`platforms/android-37/android.jar`）、真实 `framework-nfc.jar`
（设备 `/system/framework/framework-nfc.jar`，用于 vendor NCI API 编译）。

`build.py` 中的工具路径按本机环境调整（当前为 `D:\Apps\OpenJDK` 与
`D:\Apps\AndroidSDK`）：

```bash
python build.py          # 产出 InkNfcLab-<version>.apk + .idsig + sha256
adb install -r InkNfcLab-0.5.4.apk
```

安装包签名使用本地调试 keystore，升级必须保持同一签名（`install -r`）。

## 使用

1. 打开 App → 右上 `⋯` → **连接诊断与实验设置** → 展开；
2. **APC 实验档：+2**（每次刷图前切；不跨进程保存）；
3. 选择图片 → 预览 → **刷图** → 确认；
4. 驱动板贴紧手机背面顶部 NFC 区，直到显示刷新完成（DE `009000`）。

App 的 Root 后端会自动：暂停轮询 → 读取/保存 RF 配置 → 设置 `7A` 轮询帧 →
注入 MIFARE 兼容 hook → 恢复轮询 → 进入 ReaderMode；刷图结束后恢复 APC 与
轮询配置（逐字节读回校验）。
