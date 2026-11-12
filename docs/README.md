# docs/ — 文档索引

| 文档 | 内容 |
|---|---|
| [SUCCESS-20261102.md](SUCCESS-20261102.md) | **最终成功记录**：完整刷屏日志、复现步骤、持久化模块开机验证 |
| [CRASH-ANALYSIS-20261102.md](CRASH-ANALYSIS-20261102.md) | 事故分析：照搬小米 15 Pro（Android 16）模块后 NFC 打不开的根因（SELinux 标签） |
| [OTA-OS4.0.3.0-incident.md](OTA-OS4.0.3.0-incident.md) | 事故分析：系统 OTA 到 OS4.0.3.0 后 NFC 打不开（模块 v1.1 启动时 kill 服务 → NfcService startup ANR；v1.2 修复） |
| [probe-findings.md](probe-findings.md) | 前期探测记录：ST54L 原厂固件拒绝 7A、标准轮询失败、TEST_DATA 无 RF 证据等 |
| [st-nci-notes.md](st-nci-notes.md) | ST54L 厂商 NCI（GID/OID、TLV、自定义轮询帧、RF 配置 CRC）笔记 |
| [protocol-spec.md](protocol-spec.md) | 屏端协议：D1 TLV、D8/PIN、D3/LZO1X、D4/DE、A0/A1 打包 |
| [rebuild-notes.md](rebuild-notes.md) | App 重建过程与 0.5.1–0.5.4 补丁说明 |
| [FINAL_REPORT.md](FINAL_REPORT.md) | 前期（2026-11-02 白天）完整调查报告，**结论已被后续实验推翻**，保留作历史记录 |

> 时间线提醒：`FINAL_REPORT.md` 与 `probe-findings.md` 写于 os3 overlay 实验之前，
> 其中“原厂无法唤醒/必须换机或外置读卡器”的结论已被
> `SUCCESS-20261102.md` 推翻——最终方案是把小米 17 的 ST54L 栈 overlay 到小米 15。
