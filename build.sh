#!/bin/bash
# 忆聊 · 无 Gradle 构建（aapt2 + javac + d8 + zipalign + apksigner）
# 用法: ./build.sh
set -e
cd "$(dirname "$0")"

# 路径都可以用环境变量覆盖，方便在自己的机器 / CI 上跑
BT=${BT:-/tmp/ast/build-tools}                     # 放着 aapt2 / apksigner 的目录
LIB=${LIB:-/tmp/sdk/android-14/lib}                # 放着 d8.jar 的目录
JDK=${JDK:-/opt/petjdk}                            # JDK 根目录
AJAR=${AJAR:-/root/12345678/mecha-echo-apk-build/android.jar}   # android.jar
KS=${KS:-memochat.keystore}
PASS=${PASS:-memochat123}
ALIAS=${ALIAS:-memochat}

# 有 ANDROID_HOME / JAVA_HOME 时按标准 SDK 布局自动找
if [ -n "$ANDROID_HOME" ]; then
  : "${BT:=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | tail -1)}"
  : "${AJAR:=$(ls "$ANDROID_HOME"/platforms/*/android.jar 2>/dev/null | tail -1)}"
  : "${LIB:=$(dirname "$(ls "$ANDROID_HOME"/build-tools/*/lib/d8.jar 2>/dev/null | tail -1)")}"
fi
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JDK="$JAVA_HOME"
fi

for f in "$BT/aapt2" "$BT/zipalign" "$JDK/bin/javac" "$AJAR" "$LIB/d8.jar" "$LIB/apksigner.jar"; do
  [ -e "$f" ] || { echo "缺少构建依赖：$f"; echo "可以用 BT/LIB/JDK/AJAR 环境变量指定，或设置 ANDROID_HOME/JAVA_HOME"; exit 1; }
done

rm -rf build
mkdir -p build/classes build/dex

if [ ! -f "$KS" ]; then
  echo "[0/6] 没有 keystore，生成一个临时签名（只用于本地安装测试）"
  "$JDK/bin/keytool" -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=MemoChat, OU=Personal, O=Me, L=Shanghai, ST=Shanghai, C=CN" >/dev/null 2>&1
fi

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
