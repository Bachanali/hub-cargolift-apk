#!/data/user/0/ai.antigem/files/usr/bin/bash
set -e

BASE="$(cd "$(dirname "$0")" && pwd)"
cd "$BASE"

PKG_DIR="com/hubcargolift/app"
OUT="HubCargolift.apk"
JAR="$BASE/sdk/android-34/android.jar"

if [ ! -f "$JAR" ]; then
  echo "ERROR: android.jar nahi mila."
  exit 1
fi

rm -rf build
mkdir -p build/classes build/gen build/staging build/signed

echo "[1/6] Generating icons..."
python tools/make_icons.py

echo "[2/6] Compiling resources..."
aapt2 compile --dir res -o build/res.zip

echo "[3/6] Linking manifest and resources..."
aapt2 link -o build/base.apk -I "$JAR" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 23 --target-sdk-version 34 \
  --auto-add-overlay build/res.zip

echo "[4/6] Compiling Java..."
javac -source 17 -target 17 -encoding UTF-8 \
  -nowarn \
  -cp "$JAR" \
  -d build/classes \
  "build/gen/$PKG_DIR/R.java" src/$PKG_DIR/*.java

echo "[5/6] Converting to DEX and assembling..."
java -cp "$BASE/tools/r8.jar" com.android.tools.r8.D8 \
  --min-api 23 --lib "$JAR" --output build/staging \
  build/classes/$PKG_DIR/*.class

cp build/base.apk build/unsigned.apk
(cd build/staging && zip -q -r -X ../unsigned.apk classes.dex)
cp build/unsigned.apk build/aligned.apk

echo "[6/6] Signing..."
java -jar "$BASE/tools/uber-apk-signer.jar" \
  -a build/aligned.apk --out build/signed --skipZipAlign 2>&1 | tail -8

SIGNED="$(find build/signed -name '*.apk' | head -1)"
if [ -z "$SIGNED" ]; then
  echo "ERROR: signed APK nahi bana."
  exit 1
fi
cp "$SIGNED" "$BASE/$OUT"

echo
echo "APK ready:"
ls -lh "$BASE/$OUT"
echo
aapt2 dump badging "$BASE/$OUT" | head -14
echo "FINAL: $BASE/$OUT"
