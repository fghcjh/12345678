#!/bin/bash
# 忆聊 · 无 Gradle 构建（aapt2 + javac + d8 + zipalign + apksigner）
# 用法: ./build.sh
set -e
cd "$(dirname "$0")"

BT=/tmp/ast/build-tools
LIB=/tmp/sdk/android-14/lib
JDK=/opt/petjdk
AJAR=/root/12345678/mecha-echo-apk-build/android.jar
KS=memochat.keystore
PASS=memochat123
ALIAS=memochat

rm -rf build
mkdir -p build/classes build/dex

echo "[1/6] 编译 Java"
find src -name '*.java' > build/sources.txt
"$JDK/bin/javac" -source 11 -target 11 -encoding UTF-8 -nowarn \
  -classpath "$AJAR" -d build/classes @build/sources.txt

echo "[2/6] dex"
"$JDK/bin/java" -cp "$LIB/d8.jar" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$AJAR" --output build/dex $(find build/classes -name '*.class')

echo "[3/6] 编译资源"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[4/6] 链接 APK"
"$BT/aapt2" link -o build/base.apk -I "$AJAR" \
  --manifest AndroidManifest.xml -R build/res.zip -A assets \
  --auto-add-overlay

echo "[5/6] 塞入 classes.dex"
rm -rf build/stage && mkdir -p build/stage
cp build/dex/classes.dex build/stage/
(cd build/stage && zip -q ../base.apk classes.dex)

echo "[6/6] 对齐 + 签名"
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=MemoChat, OU=Personal, O=Lead, L=Shanghai, S=Shanghai, C=CN" >/dev/null 2>&1
fi

"$JDK/bin/java" -jar "$LIB/apksigner.jar" sign --ks "$KS" --ks-pass "pass:$PASS" \
  --key-pass "pass:$PASS" --ks-key-alias "$ALIAS" \
  --min-sdk-version 24 --out memochat.apk build/aligned.apk

"$JDK/bin/java" -jar "$LIB/apksigner.jar" verify --print-certs memochat.apk | head -4
ls -lh memochat.apk
echo "OK -> $(pwd)/memochat.apk"
