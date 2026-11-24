#!/usr/bin/env bash
# Pengujian perangkat di emulator: memasang aplikasi contoh (APK tunggal + split via bundletool),
# lalu menjalankan CloneDeviceTest (instrumentasi). Hasil: device-report/summary.md (+ log).
set -uo pipefail
DIST="$1"
BUNDLETOOL="$2"
OUT="${3:-device-report}"
mkdir -p "$OUT"
log() { echo "[device-test] $*" | tee -a "$OUT/steps.txt"; }

adb wait-for-device
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
log "Perangkat: $MODEL, API $SDK, ABI $ABI"
adb shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true
adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true

FAIL=0
for s in simple multidex nativelib provider; do
  if adb install -r "$DIST/samples/$s-debug.apk" >>"$OUT/steps.txt" 2>&1; then log "pasang sampel $s: OK"; else log "pasang sampel $s: GAGAL"; FAIL=1; fi
done
if java -jar "$BUNDLETOOL" install-apks --apks="$DIST/samples/sample-split.apks" >>"$OUT/steps.txt" 2>&1; then
  log "pasang sampel split (bundletool install-apks): OK"
else
  log "pasang sampel split: GAGAL"; FAIL=1
fi
log "split terpasang: $(adb shell pm path com.clonsapps.sample.split | tr -d '\r' | tr '\n' ' ')"

APP=$(ls "$DIST"/ClonApps-*-debug-*.apk | head -1)
TEST=$(ls "$DIST"/test/app-debug-androidTest.apk | head -1)
adb install -r -t "$APP" >>"$OUT/steps.txt" 2>&1 && log "pasang ClonApps: OK" || { log "pasang ClonApps: GAGAL"; FAIL=1; }
adb install -r -t "$TEST" >>"$OUT/steps.txt" 2>&1 && log "pasang APK uji: OK" || { log "pasang APK uji: GAGAL"; FAIL=1; }

adb logcat -c
adb shell am instrument -w -e class com.clonsapps.CloneDeviceTest com.clonsapps.test/androidx.test.runner.AndroidJUnitRunner \
  | tr -d '\r' | tee "$OUT/instrument.txt"
adb logcat -d -s ClonAppsDeviceTest:I | tr -d '\r' > "$OUT/device-facts.txt" || true
adb logcat -d | tr -d '\r' > "$OUT/logcat-full.txt" || true

RESULT=$(grep -E "^OK \(|^Tests run:" "$OUT/instrument.txt" | tail -1)
{
  echo "## Pengujian perangkat (emulator)"
  echo
  echo "- Perangkat: \`$MODEL\`, API $SDK, ABI $ABI"
  echo "- Hasil instrumentasi \`CloneDeviceTest\`: **${RESULT:-tidak ada hasil}**"
  echo
  echo '```'
  grep -E "INSTALL via|BUILT|MARKER|provider|ikon|split sumber|APK rusak|kunci lain|jalur" "$OUT/device-facts.txt" | sed -E 's/^.*ClonAppsDeviceTest[^:]*: //' | head -80
  echo '```'
} > "$OUT/summary.md"
cat "$OUT/summary.md"

if grep -q "^OK (" "$OUT/instrument.txt" && [ "$FAIL" = 0 ]; then
  echo "DEVICE_TESTS=passed" > "$OUT/status.txt"
  exit 0
fi
echo "DEVICE_TESTS=failed" > "$OUT/status.txt"
exit 1
