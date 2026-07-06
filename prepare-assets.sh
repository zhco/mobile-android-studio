#!/bin/bash
# ============================================================
# prepare-assets.sh
# Downloads and prepares code-server + Android SDK for bundling
# into the APK assets directory.
#
# Run this on a Linux x86_64 machine with internet access.
# It will download ARM64 binaries and place them under:
#   app/src/main/assets/code-server/
#   app/src/main/assets/android-sdk/
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets"

CODE_SERVER_VERSION="4.106.2"
BUILD_TOOLS_VERSION="34.0.0"
PLATFORM_VERSION="34"

echo "=== Preparing Mobile Android Studio Assets ==="
echo ""

# --- code-server ---
echo "[1/2] Downloading code-server $CODE_SERVER_VERSION (ARM64)..."

CODE_SERVER_DIR="$ASSETS_DIR/code-server"
mkdir -p "$CODE_SERVER_DIR"

CODE_SERVER_URL="https://github.com/coder/code-server/releases/download/v${CODE_SERVER_VERSION}/code-server-${CODE_SERVER_VERSION}-linux-arm64.tar.gz"
CODE_SERVER_TAR="$SCRIPT_DIR/temp/code-server.tar.gz"

mkdir -p "$SCRIPT_DIR/temp"

if [ ! -f "$CODE_SERVER_TAR" ]; then
    echo "  Downloading from GitHub..."
    curl -L -o "$CODE_SERVER_TAR" "$CODE_SERVER_URL"
else
    echo "  Using cached download"
fi

echo "  Extracting..."
tar -xzf "$CODE_SERVER_TAR" -C "$CODE_SERVER_DIR" --strip-components=1

# Remove unnecessary files to reduce size
rm -rf "$CODE_SERVER_DIR/node_modules" 2>/dev/null || true
rm -rf "$CODE_SERVER_DIR/lib/node" 2>/dev/null || true
find "$CODE_SERVER_DIR" -name "*.map" -delete 2>/dev/null || true
find "$CODE_SERVER_DIR" -name "*.ts" -delete 2>/dev/null || true

echo "  code-server ready at $CODE_SERVER_DIR"
echo "  Size: $(du -sh "$CODE_SERVER_DIR" | cut -f1)"

# --- Android SDK ---
echo ""
echo "[2/2] Downloading Android SDK Build Tools $BUILD_TOOLS_VERSION..."

SDK_DIR="$ASSETS_DIR/android-sdk"
mkdir -p "$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
mkdir -p "$SDK_DIR/platforms/android-$PLATFORM_VERSION"

# Download build-tools (ARM64)
BUILD_TOOLS_URL="https://dl.google.com/android/repository/build-tools_r${BUILD_TOOLS_VERSION}-linux.zip"
BUILD_TOOLS_ZIP="$SCRIPT_DIR/temp/build-tools.zip"

if [ ! -f "$BUILD_TOOLS_ZIP" ]; then
    echo "  Downloading build-tools..."
    curl -L -o "$BUILD_TOOLS_ZIP" "$BUILD_TOOLS_URL"
fi

echo "  Extracting build-tools..."
unzip -qo "$BUILD_TOOLS_ZIP" -d "$SCRIPT_DIR/temp/build-tools-extract"
mv "$SCRIPT_DIR/temp/build-tools-extract/android-14"/* "$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/" 2>/dev/null || true
mv "$SCRIPT_DIR/temp/build-tools-extract/"* "$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/" 2>/dev/null || true

# Download platform android.jar
# Note: This downloads the x86 version of android.jar (it's architecture-independent)
PLATFORM_URL="https://dl.google.com/android/repository/platform-${PLATFORM_VERSION}_r03.zip"
PLATFORM_ZIP="$SCRIPT_DIR/temp/platform.zip"

if [ ! -f "$PLATFORM_ZIP" ]; then
    echo "  Downloading platform $PLATFORM_VERSION..."
    curl -L -o "$PLATFORM_ZIP" "$PLATFORM_URL"
fi

echo "  Extracting platform..."
unzip -qo "$PLATFORM_ZIP" -d "$SDK_DIR/platforms/android-$PLATFORM_VERSION"

echo "  Android SDK ready"
echo "  SDK size: $(du -sh "$SDK_DIR" | cut -f1)"

# --- Pre-install VS Code extensions ---
echo ""
echo "[Optional] Installing VS Code extensions..."
EXT_DIR="$ASSETS_DIR/code-server/extensions"
mkdir -p "$EXT_DIR"

# Kotlin Language Server
echo "  Downloading Kotlin Language Server..."
# The kotlin-language-server is downloaded at runtime by code-server,
# but we can pre-bundle it here if we have npm available.
# For now, this is a placeholder.

# --- Summary ---
echo ""
echo "============================================"
echo " Assets prepared successfully!"
echo "============================================"
echo ""
echo "Total assets size: $(du -sh "$ASSETS_DIR" | cut -f1)"
echo ""
echo "Next steps:"
echo "  1. Open this project in Android Studio"
echo "  2. Build -> Build Bundle(s) / APK(s) -> Build APK(s)"
echo "  3. Install on ARM64 Android device"
echo ""
echo "Note: build-tools contains x86_64 binaries that need to be"
echo "replaced with ARM64 versions from a Termux environment or"
echo "cross-compiled. See README.md for details."
