#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?Indiquer le chemin du SDK Android}"
: "${LAN_VITALS_KEYSTORE:?Indiquer le chemin du keystore PKCS12}"
: "${LAN_VITALS_KEYSTORE_PASSWORD_FILE:?Indiquer le fichier contenant son mot de passe}"

source_dir="$(cd "$(dirname "$0")" && pwd)"
sdk="$ANDROID_HOME/platforms/android-36/android.jar"
tools="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p "$source_dir/build"
build_dir="$(mktemp -d "$source_dir/build/compile.XXXXXX")"
mkdir -p "$build_dir/classes"
mapfile -t sources < <(find "$source_dir/src" -name '*.java' | sort)

"$tools/aapt2" compile --dir "$source_dir/res" -o "$build_dir/resources.zip"
"$tools/aapt2" link -I "$sdk" --manifest "$source_dir/AndroidManifest.xml" \
    --java "$build_dir" -o "$build_dir/resources.apk" "$build_dir/resources.zip"
javac -encoding UTF-8 --release 8 -classpath "$sdk" -d "$build_dir/classes" \
    "$build_dir/fr/lanvitals/R.java" "${sources[@]}"
# Archive les classes générées, puis ajoute le DEX à un APK de ressources neuf.
# Jar only the generated classes, then add the DEX to a fresh resource APK.
jar cf "$build_dir/classes.jar" -C "$build_dir/classes" .
"$tools/d8" --release --min-api 23 --lib "$sdk" --output "$build_dir" "$build_dir/classes.jar"
cp "$build_dir/resources.apk" "$build_dir/unsigned.apk"
(
    cd "$build_dir"
    zip -q -0 unsigned.apk classes.dex
)
"$tools/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
"$tools/apksigner" sign --ks "$LAN_VITALS_KEYSTORE" --ks-type PKCS12 \
    --ks-key-alias lan-vitals --ks-pass "file:$LAN_VITALS_KEYSTORE_PASSWORD_FILE" \
    --out "$build_dir/lan-vitals.apk" "$build_dir/aligned.apk"
"$tools/apksigner" verify "$build_dir/lan-vitals.apk"
printf '%s\n' "$build_dir/lan-vitals.apk"
