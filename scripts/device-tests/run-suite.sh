#!/usr/bin/env bash
# RavanGo on-device test suite: runs every Maestro flow in .maestro/flows one by one on the connected
# emulator/device, per app language, and collects per flow: Maestro log + JUnit report, screenshots,
# full logcat, the crash buffer, new dropbox crash/ANR entries, and a screen recording (kept only when the
# flow failed or the app crashed). Ends with summary.md (scripts/device-tests/summarize.py).
#
# Usage: scripts/device-tests/run-suite.sh <apk> <output-dir> [locales]   (locales default: "fa en")
# Env:   APP_ID (default com.ravango.app.debug), FLOW_TIMEOUT seconds (default 1500),
#        FLOWS (optional space-separated list of flow files to run instead of all),
#        OWNER_CODE_CONFIGURED=true when the build has an owner code hash (About → 7 taps shows the dialog),
#        RECORD_VIDEO=false to skip screen recording.
# Always exits 0 once results exist; read <output-dir>/result.env (CRASHES, ANRS, FAILED_FLOWS, …).
set -uo pipefail

APK="${1:?apk path}"
OUT="${2:?output dir}"
LOCALES="${3:-fa en}"
APP_ID="${APP_ID:-com.ravango.app.debug}"
FLOW_TIMEOUT="${FLOW_TIMEOUT:-1500}"
RECORD_VIDEO="${RECORD_VIDEO:-true}"
OWNER_CODE_CONFIGURED="${OWNER_CODE_CONFIGURED:-false}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MAESTRO="${MAESTRO:-$(command -v maestro || echo "$HOME/.maestro/bin/maestro")}"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
RESULTS="$OUT/results.tsv"
printf 'locale\tflow\tstatus\texit\tseconds\tcrash\tanr\n' > "$RESULTS"

log() { echo "[device-tests] $*"; }

adb wait-for-device
# Wait for a fully booted system (package manager up).
for _ in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done

{
  echo "device: $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "api: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "abi: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "release: $(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "gles: $(adb shell getprop ro.opengles.version | tr -d '\r')"
  echo "maestro: $("$MAESTRO" --version 2>/dev/null | tail -1)"
  echo "apk: $(basename "$APK") ($(du -h "$APK" | cut -f1))"
} > "$OUT/device.txt"
cat "$OUT/device.txt"
adb shell dumpsys media.camera 2>/dev/null | grep -iE "Camera ID|facing|Device [0-9]" | head -20 > "$OUT/cameras.txt" || true

# Quieter, deterministic UI.
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
# Slow emulators make system apps (launcher, System UI) ANR; their "isn't responding" dialogs would cover the app.
# Hide error dialogs (crashes/ANRs are still logged to logcat + dropbox, which is what the runner checks) and nudge
# a configuration change so the system re-reads the setting.
adb shell settings put global hide_error_dialogs 1 || true
# No "Viewing full screen — GOT IT" bubble over immersive screens (teleprompter, camera).
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell settings put system font_scale 1.01 || true
sleep 1
adb shell settings put system font_scale 1.0 || true

log "Installing $APK"
adb uninstall "$APP_ID" >/dev/null 2>&1 || true
if ! adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; then
  cat "$OUT/install.txt"
  echo "INSTALL_FAILED=1" > "$OUT/result.env"
  python3 "$ROOT/scripts/device-tests/summarize.py" "$OUT" || true
  exit 0
fi

if [ -n "${FLOWS:-}" ]; then
  FLOW_FILES="$FLOWS"
else
  FLOW_FILES="$(ls "$ROOT"/.maestro/flows/*.yaml | sort)"
fi

devtime() { adb shell date '+%Y-%m-%d\ %H:%M:%S' 2>/dev/null | tr -d '\r'; }

for LOCALE in $LOCALES; do
  log "=== Locale $LOCALE ==="
  adb shell pm clear "$APP_ID" >/dev/null 2>&1 || true
  for FLOW in $FLOW_FILES; do
    NAME="$(basename "$FLOW" .yaml)"
    DIR="$OUT/$LOCALE/$NAME"
    mkdir -p "$DIR"
    log "--- $LOCALE / $NAME"

    adb logcat -c 2>/dev/null || true
    adb logcat -b crash -c 2>/dev/null || true
    START_TIME="$(devtime)"
    echo "$START_TIME" > "$DIR/start_time.txt"
    adb logcat -v threadtime > "$DIR/logcat.txt" 2>&1 &
    LOGCAT_PID=$!

    REC_PID=""
    if [ "$RECORD_VIDEO" = "true" ]; then
      adb shell rm -f '/sdcard/rg_rec_*.mp4' >/dev/null 2>&1 || true
      # screenrecord stops after 180 s, so chain segments.
      adb shell 'i=0; while [ $i -lt 20 ]; do screenrecord --bit-rate 1000000 --time-limit 175 /sdcard/rg_rec_$i.mp4 || break; i=$((i+1)); done' >/dev/null 2>&1 &
      REC_PID=$!
    fi

    SECONDS=0
    timeout "$FLOW_TIMEOUT" "$MAESTRO" test \
      --format junit --output "$DIR/report.xml" \
      --test-output-dir "$DIR/maestro" \
      --debug-output "$DIR/debug" --flatten-debug-output \
      -e APP_LANG="$LOCALE" -e OWNER_CODE_CONFIGURED="$OWNER_CODE_CONFIGURED" \
      "$FLOW" > "$DIR/maestro.log" 2>&1
    EXIT=$?
    DURATION=$SECONDS
    # Flatten Maestro's output: step screenshots → screenshots/, its failure screenshot → failure.png.
    mkdir -p "$DIR/screenshots"
    find "$DIR/debug" -path '*/takeScreenshot/*.png' -exec mv {} "$DIR/screenshots/" \; 2>/dev/null || true
    LAST_FAIL="$(find "$DIR/debug" -path '*/screenshots/*.png' 2>/dev/null | sort | tail -1)"
    [ "$EXIT" -ne 0 ] && [ -n "$LAST_FAIL" ] && cp "$LAST_FAIL" "$DIR/failure.png"

    # Give a just-crashed process a moment to reach the logs.
    sleep 3
    if [ -n "$REC_PID" ]; then
      adb shell pkill -INT screenrecord >/dev/null 2>&1 || true
      sleep 2
      kill "$REC_PID" >/dev/null 2>&1 || true
    fi
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
    wait "$LOGCAT_PID" 2>/dev/null || true
    adb logcat -b crash -d -v threadtime > "$DIR/crash_buffer.txt" 2>&1 || true
    for TAG in data_app_crash data_app_native_crash data_app_anr SYSTEM_TOMBSTONE; do
      adb shell dumpsys dropbox --print "$TAG" > "$DIR/dropbox_$TAG.txt" 2>/dev/null || true
    done

    CRASH=0
    ANR=0
    if grep -qE "Process: $APP_ID(,| )|>>> $APP_ID <<<|Fatal signal .*\($APP_ID\)|name: .* >>> $APP_ID" "$DIR/crash_buffer.txt" "$DIR/logcat.txt" 2>/dev/null; then
      CRASH=1
    fi
    if grep -qE "ANR in $APP_ID" "$DIR/logcat.txt" 2>/dev/null; then
      ANR=1
    fi
    STATUS=passed
    [ "$EXIT" -ne 0 ] && STATUS=failed
    [ "$EXIT" -eq 124 ] && STATUS=timeout

    if [ -n "$REC_PID" ]; then
      if [ "$STATUS" != "passed" ] || [ "$CRASH" = "1" ] || [ "$ANR" = "1" ]; then
        mkdir -p "$DIR/video"
        for F in $(adb shell 'ls /sdcard/rg_rec_*.mp4 2>/dev/null' | tr -d '\r'); do
          adb pull "$F" "$DIR/video/" >/dev/null 2>&1 || true
        done
      fi
      adb shell rm -f '/sdcard/rg_rec_*.mp4' >/dev/null 2>&1 || true
    fi

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$LOCALE" "$NAME" "$STATUS" "$EXIT" "$DURATION" "$CRASH" "$ANR" >> "$RESULTS"
    log "$LOCALE / $NAME: $STATUS (exit $EXIT, ${DURATION}s, crash=$CRASH, anr=$ANR)"
    tail -25 "$DIR/maestro.log" | sed 's/^/    /'
  done
done

# Whole-run system state for reference.
adb shell dumpsys dropbox > "$OUT/dropbox_index.txt" 2>/dev/null || true
adb shell dumpsys meminfo "$APP_ID" > "$OUT/meminfo.txt" 2>/dev/null || true

python3 "$ROOT/scripts/device-tests/summarize.py" "$OUT" || log "summary generation failed"
cat "$OUT/result.env" 2>/dev/null || true
exit 0
