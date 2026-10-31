#!/bin/sh
# Xcode build phase: sets the built bundle's version from the Android app's, so both platforms
# always ship the same one. app/build.gradle.kts has `val appVersionMinor = N`, which makes
# versionName 1.N and versionCode N; here that is CFBundleShortVersionString 1.N and
# CFBundleVersion N.
set -eu

GRADLE="${SRCROOT}/../app/build.gradle.kts"
MINOR=$(sed -nE 's/^[[:space:]]*val[[:space:]]+appVersionMinor[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$GRADLE" | head -n 1)
if [ -z "$MINOR" ]; then
  echo "error: appVersionMinor not found in app/build.gradle.kts"
  exit 1
fi

PLIST="${TARGET_BUILD_DIR}/${INFOPLIST_PATH}"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString 1.${MINOR}" -c "Set :CFBundleVersion ${MINOR}" "$PLIST"
echo "Version 1.${MINOR} (${MINOR})"
