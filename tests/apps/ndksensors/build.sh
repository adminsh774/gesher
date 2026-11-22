#!/usr/bin/env bash
# Build a real NDK sensor APK for x86_64 or arm64-v8a (native2 CI artifact).
set -euo pipefail
app=$(cd "$(dirname "$0")" && pwd)
out=$(mkdir -p "$1" && cd "$1" && pwd)
abi=$2
shift 2
ndk= android_jar= tools= r8=
while [[ $# -gt 0 ]]; do
  case $1 in
    --ndk) ndk=$2; shift 2 ;;
    --android-jar) android_jar=$2; shift 2 ;;
    --build-tools) tools=$2; shift 2 ;;
    --r8) r8=$2; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
case $abi in
  x86_64) triple=x86_64-linux-android ;;
  arm64-v8a) triple=aarch64-linux-android ;;
  *) echo "unsupported ABI $abi" >&2; exit 2 ;;
esac
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
llvm=$ndk/toolchains/llvm/prebuilt/linux-x86_64
mkdir -p "$work/apk/lib/$abi" "$work/classes" "$work/dex"
"$llvm/bin/${triple}29-clang++" -std=c++20 -shared -fPIC -O2 -fstack-protector-strong \
  -Wall -Wextra -Werror -Wl,-soname,libndksensors.so \
  -o "$work/apk/lib/$abi/libndksensors.so" "$app/jni/sensors.cpp" -landroid
cp "$llvm/sysroot/usr/lib/$triple/libc++_shared.so" "$work/apk/lib/$abi/"
"$tools/aapt2" link -o "$work/unaligned.apk" -I "$android_jar" \
  --manifest "$app/AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 36
javac --release 11 -encoding UTF-8 -cp "$android_jar" -d "$work/classes" \
  "$app/java/com/gesher/ndksensors/SensorActivity.java"
java -cp "$r8" com.android.tools.r8.D8 --release --min-api 29 --lib "$android_jar" \
  --output "$work/dex" "$work/classes/com/gesher/ndksensors/SensorActivity.class"
(cd "$work/dex" && zip -q "$work/unaligned.apk" classes.dex)
(cd "$work/apk" && zip -q -0 "$work/unaligned.apk" lib/$abi/*.so)
"$tools/zipalign" -f -p 4 "$work/unaligned.apk" "$out/ndksensors-$abi.apk"
echo "Built ndksensors-$abi.apk"
