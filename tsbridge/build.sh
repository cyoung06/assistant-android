#!/bin/sh
# Builds the embedded Tailscale bridge into app/libs/tsbridge.aar (needs Go 1.26+, the Android SDK and NDK).
set -e
cd "$(dirname "$0")"
TOOLCHAIN=$(GOTOOLCHAIN=auto go env GOROOT)
export GOTOOLCHAIN=local PATH="$TOOLCHAIN/bin:$(go env GOPATH)/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | tail -1)}"
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
mkdir -p ../app/libs
gomobile bind -target=android/arm64,android/amd64 -androidapi 29 -javapkg=com.syeyoung.assistant -o ../app/libs/tsbridge.aar .
