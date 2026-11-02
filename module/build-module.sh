#!/usr/bin/env bash
# Build the nfc-dada-pudding KernelSU module zip.
# Vendor blobs are NOT in this repository; put them into nfc-dada-pudding/blobs/
# first (see fetch-blobs.md) and verify their SHA-256.
set -eu
cd "$(dirname "$0")"

SRC=nfc-dada-pudding
OUT=nfc-dada-pudding.zip
EXPECT_SERVICE=180fd75773f8eaf568fc10e54df9ec1fde6f0f99164daf5880ae7ecba13bae88
EXPECT_HAL=e3ea02c60951c227233fbf359a73ef5aebcbd7214a62d5a0f96312eb9a272def
EXPECT_FW=22a9e85dfe459676f401fbc67cf9870176c7d9c583aefd9963701b2e112fbe4d
EXPECT_HALCONF=fd2db7dbdfcda7b4710edf60c0d270bf06bcfb662174771a0fe541a71188c6f9
EXPECT_STCONF=035aba67836fb28eb130bfece420dfd26b4210056dbe0a0c107d0bc1260d0007

check() {
  file="$1"; want="$2"
  [ -f "$file" ] || { echo "MISSING: $file (see fetch-blobs.md)"; exit 1; }
  got=$(sha256sum "$file" | cut -d' ' -f1)
  [ "$got" = "$want" ] || { echo "HASH MISMATCH: $file"; echo "  want $want"; echo "  got  $got"; exit 1; }
}

check "$SRC/blobs/nfc-service-st"        "$EXPECT_SERVICE"
check "$SRC/blobs/nfc_nci.st21nfc.st.so" "$EXPECT_HAL"
check "$SRC/blobs/st54l_fw.bin"          "$EXPECT_FW"
check "$SRC/blobs/libnfc-hal-st.conf"    "$EXPECT_HALCONF"
check "$SRC/blobs/st54l_conf.txt"        "$EXPECT_STCONF"

rm -f "$OUT"
export SOURCE_DATE_EPOCH=1700000000
(cd "$SRC" && zip -X -r "../$OUT" module.prop post-fs-data.sh service.sh action.sh uninstall.sh blobs)
echo "Built $OUT"
unzip -l "$OUT"
