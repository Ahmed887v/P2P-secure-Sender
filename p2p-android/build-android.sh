#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
SRC="$(pwd)"
DIST="$SRC/dist"
mkdir -p "$DIST"

need_cmd() {
  command -v "$1" >/dev/null 2>&1
}

if ! need_cmd java; then
  echo "Installing JDK 17..."
  if need_cmd apt-get; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y openjdk-17-jdk wget unzip rsync
  else
    echo "Install JDK 17 first, then rerun."
    exit 1
  fi
fi

if ! need_cmd wget || ! need_cmd unzip; then
  if need_cmd apt-get; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y wget unzip rsync
  else
    echo "Install wget and unzip first, then rerun."
    exit 1
  fi
fi

if ! need_cmd rsync && need_cmd apt-get; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get install -y rsync
fi

BUILD_ROOT="/tmp/p2p-android-build"
echo "Copying project to $BUILD_ROOT ..."
mkdir -p "$BUILD_ROOT"
if need_cmd rsync; then
  rsync -a --delete \
    --exclude '.android-sdk/' \
    --exclude '.gradle/' \
    --exclude 'app/build/' \
    --exclude 'build/' \
    --exclude 'dist/' \
    "$SRC/" "$BUILD_ROOT/"
else
  tar -C "$SRC" --exclude='.android-sdk' --exclude='.gradle' --exclude='app/build' --exclude='build' --exclude='dist' -cf - . | tar -C "$BUILD_ROOT" -xf -
fi
cd "$BUILD_ROOT"

SDK_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android-sdk-p2p}}"
mkdir -p "$SDK_HOME/cmdline-tools" "$SDK_HOME/licenses"
printf '%s\n' "24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$SDK_HOME/licenses/android-sdk-license"
printf '%s\n' "84831b9409646161da1e394414118184" > "$SDK_HOME/licenses/android-sdk-preview-license"

if [ ! -d "$SDK_HOME/cmdline-tools/latest" ]; then
  echo "Downloading Android command-line tools..."
  wget --show-progress -O /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -o /tmp/cmdtools.zip -d "$SDK_HOME/cmdline-tools"
  if [ -d "$SDK_HOME/cmdline-tools/cmdline-tools" ]; then
    mv "$SDK_HOME/cmdline-tools/cmdline-tools" "$SDK_HOME/cmdline-tools/latest"
  fi
fi

export ANDROID_HOME="$SDK_HOME"
export ANDROID_SDK_ROOT="$SDK_HOME"

if [ ! -d "$SDK_HOME/platforms/android-35" ] || [ ! -d "$SDK_HOME/build-tools/35.0.0" ]; then
  echo "Installing Android SDK packages..."
  "$SDK_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_HOME" --install \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi

if [ ! -s gradle/wrapper/gradle-wrapper.jar ]; then
  echo "Downloading Gradle wrapper jar..."
  mkdir -p gradle/wrapper
  wget --show-progress -O gradle/wrapper/gradle-wrapper.jar \
    https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar
fi

if [ ! -f gradlew ]; then
  cat > gradlew << 'EOF'
#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
java -Xmx1024m -cp "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
EOF
fi
chmod +x gradlew

printf "sdk.dir=%s\n" "$SDK_HOME" > local.properties
export GRADLE_USER_HOME="$HOME/.gradle-p2p"
mkdir -p "$GRADLE_USER_HOME"
export KOTLIN_DAEMON_ENABLED=false

if [ ! -f app/debug.keystore ]; then
  echo "Creating debug keystore..."
  keytool -genkeypair -v -keystore app/debug.keystore -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export LANGUAGE=en_US:en

echo "Building APK from $BUILD_ROOT ..."
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process assembleRelease

echo "Building AAB from $BUILD_ROOT ..."
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process bundleRelease || echo "AAB failed; APK may still be ready."

APK=""
if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  APK=app/build/outputs/apk/release/app-release.apk
elif [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
  APK=app/build/outputs/apk/release/app-release-unsigned.apk
fi

if [ -z "$APK" ]; then
  echo "Build finished but APK was not found. Check the Gradle errors above."
  exit 1
fi

cp "$APK" "$DIST/p2p-secure-share.apk"
if [ -f app/build/outputs/bundle/release/app-release.aab ]; then
  cp app/build/outputs/bundle/release/app-release.aab "$DIST/p2p-secure-share.aab"
fi

echo "Done:"
echo "  $DIST/p2p-secure-share.apk"
if [ -f "$DIST/p2p-secure-share.aab" ]; then
  echo "  $DIST/p2p-secure-share.aab"
fi
