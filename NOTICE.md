# NOTICE

This repository mixes original work and code derived from third-party projects.
Read this file together with `LICENSE` (MIT, for this project's original work)
and `LICENSE-APP` (PolyForm Noncommercial 1.0.0, for `app/`).

## app/ — Ink NFC Lab 0.5.4

`app/` is derived from **wiwiwei/ink-nfc-lab** 0.5.0 (reconstructed via
decompilation and then patched; see `docs/rebuild-notes.md`):

- Upstream: https://github.com/wiwiwei/ink-nfc-lab
- License: **PolyForm Noncommercial 1.0.0** — https://polyformproject.org/licenses/noncommercial/1.0.0/
- The full license text is in `LICENSE-APP`.

Protocol definitions and D1 device-information parsing are adapted from:

- kur0neko, https://gitee.com/kur0neko/huawei-epd-pn532
- **Required Notice: Copyright (c) 2026 kur0neko**
- Upstream license: PolyForm Noncommercial 1.0.0

The APK bundles the **Frida 17.24.0** Android arm64 injector:

- https://github.com/frida/frida/releases/tag/17.24.0
- SHA-256: `261875c11ec37fa1d3c26c05f71c0390ef1176539b0545d0b253cdbf24ece0e5`
- Frida is distributed under the **wxWindows Library Licence**.

`app/src` contains the decompiled-then-rebuilt sources for reproducibility;
the patches relative to upstream 0.5.0 are documented in
`docs/rebuild-notes.md` (0.5.1–0.5.4). Do not use `app/` or its contents for
commercial purposes.

## Vendor binaries (NOT included)

The following files are copyright Xiaomi / STMicroelectronics and are **not
included in this repository**. They must be extracted from your own device
firmware and are provided/used for personal research only:

- `nfc_service-st` (a.k.a. `android.hardware.nfc-service-st`)
- `nfc_nci.st21nfc.st.so`
- `st54l_fw.bin`
- `libnfc-hal-st.conf`
- `st54l_conf.txt`
- (optional) `Nfc_st.apk`

See `module/fetch-blobs.md` for sizes and SHA-256 values.

## Module / scripts / docs (this project's original work)

Licensed under MIT (see `LICENSE`). The runtime overlay approach and the
KernelSU module were informed by, and are grateful to:

- **Freeze-Over/nfc-15pro-st54l** (MIT) — https://github.com/Freeze-Over/nfc-15pro-st54l
  (Xiaomi 15 Pro / Android 16 approach; this project re-implements the overlay
  for Xiaomi 15 / Android 17 with label verification and a fallback path.)
- **ZhaoGY-N/huawei-nfc-eink-android-app**
  (PolyForm Noncommercial 1.0.0) — https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app
- **wiwiwei/ink-nfc-lab**
  (PolyForm Noncommercial 1.0.0) — https://github.com/wiwiwei/ink-nfc-lab
- **solar2ain/ink-nfc-fmsc-by-pn532**
  (GPL-3.0-or-later) — https://github.com/solar2ain/ink-nfc-fmsc-by-pn532
  Only publicly documented protocol facts were used (D8/APDU definitions,
  LZO1X literal stream layout, A0/A1 layouts); no code is embedded.
- **kur0neko/huawei-epd-pn532** (PolyForm Noncommercial 1.0.0) —
  https://gitee.com/kur0neko/huawei-epd-pn532
- Android / ST HAL public source observations (see `docs/st-nci-notes.md`).

All trademarks belong to their respective owners. No vendor firmware or
proprietary binaries are redistributed here.
