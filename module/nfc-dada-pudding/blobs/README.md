# blobs/ (not committed)

Put the five Xiaomi 17 (pudding OS3.0.319) vendor files here before building the
module. They are **not** part of this repository; see `../../fetch-blobs.md` for
sizes, SHA-256 values and where to extract them.

Required files:

```
nfc-service-st
nfc_nci.st21nfc.st.so
st54l_fw.bin
libnfc-hal-st.conf
st54l_conf.txt
```

`build-module.sh` verifies their SHA-256 and refuses to build on mismatch.
