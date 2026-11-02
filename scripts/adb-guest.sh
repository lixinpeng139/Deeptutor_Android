#!/bin/bash
# Run a command in the app's guest on a connected device (debug builds only: needs run-as).
# usage: scripts/adb-guest.sh <adb-serial> <abi-dir: x86_64|arm64> <command...>
set -e
SERIAL=$1; ABI=$2; shift 2
SDK=${ANDROID_SDK_ROOT:-$(dirname "$0")/../toolchain/android-sdk}
ADB="$SDK/platform-tools/adb.exe -s $SERIAL"
export MSYS_NO_PATHCONV=1
HERE=$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)
$ADB push "$HERE/guest.sh" /data/local/tmp/guest.sh >/dev/null
LIB=$($ADB shell pm dump com.opendeeptutor.android | grep -m1 legacyNativeLibraryDir | sed 's/^[^=]*=//' | tr -d '\r')/$ABI
$ADB shell "run-as com.opendeeptutor.android sh /data/local/tmp/guest.sh $LIB $*"
