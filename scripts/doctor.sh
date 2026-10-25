#!/usr/bin/env bash
# Environment check: green = ready to rip. Yellow = known deferred item (needs sudo/human).
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

PASS=0; WARN=0; FAIL=0
ok()   { printf '\033[1;32m  OK \033[0m %s\n' "$*"; PASS=$((PASS+1)); }
warn() { printf '\033[1;33mWARN \033[0m %s\n' "$*"; WARN=$((WARN+1)); }
bad()  { printf '\033[1;31mFAIL \033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }

echo "── hermes-wearos doctor ──────────────────────────"
[ -x "$JAVA_HOME/bin/javac" ] && ok "JDK 17: $($JAVA_HOME/bin/java -version 2>&1 | head -1)" || bad "JDK 17 missing at $JAVA_HOME"
[ -x "$SDKMANAGER" ] && ok "cmdline-tools present" || bad "cmdline-tools missing (run bootstrap.sh)"
[ -d "$ANDROID_HOME/platforms/android-35" ] && ok "platforms;android-35" || warn "platforms;android-35 missing (run bootstrap.sh)"
[ -d "$ANDROID_HOME/build-tools/35.0.0" ] && ok "build-tools;35.0.0" || warn "build-tools;35.0.0 missing (run bootstrap.sh)"
[ -x "$DEVKIT/gradle/current/bin/gradle" ] && ok "Gradle present" || warn "Gradle missing (run bootstrap.sh)"
command -v node >/dev/null && ok "node $(node --version)" || bad "node missing"
command -v python3 >/dev/null && ok "python3 $(python3 --version 2>&1 | cut -d' ' -f2)" || bad "python3 missing"

# emulator acceleration
if [ -e /dev/kvm ]; then
  if [ -w /dev/kvm ]; then ok "/dev/kvm writable (emulator will be fast)"; else warn "/dev/kvm exists but not writable — needs: sudo usermod -aG kvm $USER (deferred sudo item)"; fi
else
  warn "/dev/kvm absent — emulator would run slow (BIOS virtualization?)"
fi

# adb + devices
if command -v adb >/dev/null; then
  devs=$(adb devices | tail -n +2 | grep -c 'device$' || true)
  [ "${devs:-0}" -gt 0 ] && ok "adb: $devs device(s) attached" || ok "adb present, no devices yet (watch joins at M3)"
else
  warn "adb not on PATH"
fi

# tailscale + this machine's identity
if command -v tailscale >/dev/null; then
  if tailscale status --peers=false --json >/dev/null 2>&1; then
    ok "tailscale up: $(tailscale ip -4 2>/dev/null | head -1)"
  else
    warn "tailscale not running"
  fi
else
  bad "tailscale missing"
fi

# hermes surfaces
for p in 8642 9119; do
  code=$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$p/api/status" 2>/dev/null) || code="000"
  case "$code" in
    000|"") warn "nothing on 127.0.0.1:$p (S2: Hermes API server / dashboard not up yet)" ;;
    *)      ok "127.0.0.1:$p answering (HTTP $code)" ;;
  esac
done

free_data=$(df --output=avail -BG "$DEVKIT" 2>/dev/null | tail -1 | tr -dc '0-9')
[ "${free_data:-0}" -gt 30 ] && ok "devkit disk: ${free_data}G free" || warn "devkit disk low: ${free_data}G free"

echo "── $PASS ok · $WARN warn · $FAIL fail ─────────────"
[ "$FAIL" -eq 0 ] || exit 1
