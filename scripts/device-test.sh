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

APP=$(ls "$DIST"/ClonApps-v*.apk | head -1)
TEST=$(ls "$DIST"/test/app-release-androidTest.apk | head -1)
adb install -r -t "$APP" >>"$OUT/steps.txt" 2>&1 && log "pasang ClonApps: OK" || { log "pasang ClonApps: GAGAL"; FAIL=1; }
adb install -r -t "$TEST" >>"$OUT/steps.txt" 2>&1 && log "pasang APK uji: OK" || { log "pasang APK uji: GAGAL"; FAIL=1; }

adb logcat -c
adb shell am instrument -w -e class com.clonsapps.CloneDeviceTest com.clonsapps.test/androidx.test.runner.AndroidJUnitRunner \
  | tr -d '\r' | tee "$OUT/instrument.txt"
adb logcat -d -s ClonAppsDeviceTest:I | tr -d '\r' > "$OUT/device-facts.txt" || true
adb logcat -d | tr -d '\r' > "$OUT/logcat-full.txt" || true

# ---------------- UI smoke test: aplikasi sungguhan dibuka dan dinavigasi (ponsel terang/gelap, tablet)
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  local xy
  xy=$(python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want, path = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    if n.get("text") == want or n.get("content-desc") == want:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; log "ketuk \"$1\" di $xy"; else log "teks \"$1\" tidak ditemukan"; fi
  sleep 3
}
shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "SCREENSHOT_BEGIN $1"; base64 -w 100 "$OUT/$1.png"; echo "SCREENSHOT_END $1"
}
launch_ui() {
  adb shell am force-stop com.clonsapps
  adb shell am start -W -n com.clonsapps/.MainActivity >/dev/null
  sleep 6
}
crashes() { adb logcat -d -b crash | grep -c "Process: com.clonsapps" || true; }

adb logcat -b crash -c || true
launch_ui;                       shot ui-1-pilih-sumber
tap_text "Sample Simple";        shot ui-2-atur-clone
adb shell input swipe 540 1900 540 300 300; adb shell input swipe 540 1900 540 300 300; sleep 1
tap_text "Periksa";  sleep 4;    shot ui-3-periksa
tap_text "Riwayat";              shot ui-4-riwayat
tap_text "Simple Kedua";         shot ui-5-detail
adb shell cmd uimode night yes >/dev/null 2>&1
launch_ui;                       shot ui-6-gelap
adb shell cmd uimode night no >/dev/null 2>&1
adb shell wm size 2560x1600; adb shell wm density 320
launch_ui; tap_text "Riwayat"; tap_text "Simple Kedua"; shot ui-7-tablet
adb shell wm size reset; adb shell wm density reset
UI_PID=$(adb shell pidof com.clonsapps | tr -d '\r')
UI_CRASH=$(crashes)
log "UI smoke: proses hidup=${UI_PID:-tidak}, crash ClonApps=$UI_CRASH"

RESULT=$(grep -E "^OK \(|^Tests run:" "$OUT/instrument.txt" | tail -1)
{
  echo "## Pengujian perangkat (emulator)"
  echo
  echo "- Perangkat: \`$MODEL\`, API $SDK, ABI $ABI"
  echo "- Hasil instrumentasi \`CloneDeviceTest\`: **${RESULT:-tidak ada hasil}**"
  echo "- UI smoke (buka & navigasi aplikasi rilis: ponsel terang/gelap, tablet 2560×1600): crash ClonApps = **${UI_CRASH}**, proses hidup = ${UI_PID:-tidak}"
  echo
  echo '```'
  grep -E "INSTALL via|BUILT|MARKER|provider|ikon|split sumber|APK rusak|kunci lain|jalur" "$OUT/device-facts.txt" | sed -E 's/^.*ClonAppsDeviceTest[^:]*: //' | head -80
  echo '```'
} > "$OUT/summary.md"
cat "$OUT/summary.md"

if grep -q "^OK (" "$OUT/instrument.txt" && [ "$FAIL" = 0 ] && [ "$UI_CRASH" = 0 ] && [ -n "$UI_PID" ]; then
  echo "DEVICE_TESTS=passed" > "$OUT/status.txt"
  exit 0
fi
echo "DEVICE_TESTS=failed" > "$OUT/status.txt"
exit 1
