#!/bin/sh
# Builds a release signed with the key in ../secrets and publishes it as a GitHub Release, where
# Obtainium on the devices picks it up. Run from a clean, pushed main.
set -eu
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

[ -z "$(git status --porcelain)" ] || { echo "Commit or stash changes first." >&2; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || { echo "Release from main." >&2; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "Push main first." >&2; exit 1; }
[ -f ../secrets/android-release.jks ] || { echo "No release key in ../secrets." >&2; exit 1; }

build=$(git rev-list --count HEAD)
tag="v0.2.$build"
./gradlew -q testDebugUnitTest assembleRelease -PversionCode="$build"
apk=app/build/outputs/apk/release/app-release.apk

# Refuse to publish anything not signed by our key (e.g. if signing silently fell back to unsigned).
apksigner=$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner | tail -1)
want=$("$JAVA_HOME/bin/keytool" -list -keystore ../secrets/android-release.jks -storepass "$(cat ../secrets/android-release.password)" -alias assistant \
  | sed -n 's/.*SHA-256): //p' | tr -d ':' | tr 'A-F' 'a-f')
got=$("$apksigner" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
[ -n "$want" ] && [ "$want" = "$got" ] || { echo "APK isn't signed with the release key." >&2; exit 1; }

cp "$apk" "app/build/assistant-$tag.apk"
last=$(git describe --tags --abbrev=0 2>/dev/null || true)
notes=$(git log --format='- %s' ${last:+"$last"..HEAD} | head -40)
gh release create "$tag" "app/build/assistant-$tag.apk" --target "$(git rev-parse HEAD)" --title "$tag" --notes "$notes"
echo "Published $tag"
