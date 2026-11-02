#!/bin/bash
# Rebuild everything after a web change: Next.js build, both guest payloads,
# release APKs (+ arm64 debug for adb diagnostics), copied into dist/.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PY="$ROOT/build/hostpy/python/python.exe"
export JAVA_HOME=${JAVA_HOME:-"C:/Program Files/Java/jdk-21.0.10"}

cd "$ROOT/build/src/web"
rm -f tsconfig.tsbuildinfo
NEXT_PUBLIC_API_BASE=http://127.0.0.1:18801 NEXT_PUBLIC_AUTH_ENABLED=false NEXT_TELEMETRY_DISABLED=1 \
  npm run build > "$ROOT/build/web-build.log" 2>&1
echo "web ok"

cd "$ROOT"
"$PY" scripts/build_payload.py --abi aarch64 --assets android/app/src/arm64/assets/runtime \
  --jnilibs android/app/src/arm64/jniLibs/arm64-v8a > build/payload-aarch64.log 2>&1
"$PY" scripts/build_payload.py --abi x86_64 --assets android/app/src/x86_64/assets/runtime \
  --jnilibs android/app/src/x86_64/jniLibs/x86_64 > build/payload-x86_64.log 2>&1
echo "payloads ok"

cd "$ROOT/android"
./gradlew assembleArm64Release assembleX64Release assembleArm64Debug --console=plain -q > "$ROOT/build/gradle-release.log" 2>&1
cd "$ROOT"
mkdir -p dist
cp android/app/build/outputs/apk/arm64/release/app-arm64-release.apk dist/DeepTutor-1.7.1-arm64.apk
cp android/app/build/outputs/apk/x64/release/app-x64-release.apk dist/DeepTutor-1.7.1-x86_64-emulator.apk
echo "apks ok"
ls -la dist
