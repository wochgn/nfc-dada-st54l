# Changelog

## 2026-11-02 · v1.0 — 首次实机打通并开源整理

- 定位小米 15（dada）原厂不可刷的根因：ST54L 固件 `02.04.1188` 缺少 `stpropnci`
  引擎，拒绝 NCI 自定义轮询帧（`0x1E/0x1D` → `SYNTAX_ERROR 0x05`）。
- 找到可行方案：运行时 overlay 小米 17（pudding OS3.0.319）的 ST54L NFC 栈
  （HAL + lib + 固件 + 配置），不替换 `Nfc_st.apk`；芯片固件自动升级到 `02.07.2128`。
- 分析并复现了 haotian（Android 16）模块在 dada 上导致 NFC 打不开的原因：
  overlay 文件 SELinux 标签错误（`system_file` 而非 `hal_nfc_default_exec`），
  init 拒绝启动 `nfc_hal_service`。
- App 分支修复：
  - 0.5.1–0.5.3：dada 设备支持、Root 诊断、7A 能力检测与如实报告；
  - **0.5.4**：ISO-DEP 连接后保场充能 2000 ms + D1 重试 3 次，解决 D1 `6401/6403`。
- 实机成功：D1 校验通过 → D8 认证 → D3 476/476 全部 `9000` → D4 `9000`（3.8 s）
  → DE `009000`（19.3 s）；APC 实验档 `+2` 解决 `68CA` 低电压。
- 发布 KernelSU 持久化模块 `nfc-dada-pudding` v1.1（开机自动 overlay、标签校验、
  失败不挂载、late_start 兜底），并通过两次真实重启与重启后刷屏验证。

## 2026-11-12 · v1.2 — 系统 OTA 后 NFC 故障修复

- 用户 OTA 到 `OS4.0.3.0.XOCCNXM` 后 NFC 打不开；诊断为模块 v1.1 的 `service.sh`
  在开机启动过程中 kill HAL / `com.android.nfc`，导致 NfcService startup ANR、
  `nfc` 系统服务未注册（详见 [docs/OTA-OS4.0.3.0-incident.md](docs/OTA-OS4.0.3.0-incident.md)）。
- 确认 OTA **未改动**原厂 `/odm` NFC 文件（5 个哈希与旧版一致），非固件/兼容性损坏。
- 模块 v1.2：移除所有开机阶段的服务重启逻辑；`service.sh` 只验证/记录，
  `action.sh` 只重放 overlay；`post-fs-data.sh` 增加“已挂载即退出”的幂等判断。
- 验证：OS4.0.3.0 重启后 `mState=on`、HAL pid 正常、芯片 FW `02.07.2128`、
  `0x13` 返回 `1300`，NFC 与刷屏能力恢复。
