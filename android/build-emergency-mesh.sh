#!/bin/bash

# Emergency Mesh - Automated Build Script
# This script builds the production-ready APK

set -e  # Exit on error

echo "=========================================="
echo "  Emergency Mesh - Production Build"
echo "=========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if we're in the right directory
if [ ! -f "app/build.gradle" ]; then
    echo -e "${RED}Error: Please run this script from the android/ directory${NC}"
    exit 1
fi

# Check for Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}Error: Java not found. Please install JDK 17.${NC}"
    exit 1
fi

java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
echo -e "${YELLOW}Java version: $java_version${NC}"

if [ "$java_version" -lt 17 ]; then
    echo -e "${RED}Warning: Java 17+ recommended${NC}"
fi

# Make gradlew executable
chmod +x gradlew

# Clean previous builds
echo ""
echo -e "${YELLOW}Cleaning previous builds...${NC}"
./gradlew clean

# Build debug APK
echo ""
echo -e "${YELLOW}Building debug APK...${NC}"
./gradlew assembleDebug

if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo -e "${GREEN}✓ Debug APK built successfully${NC}"
    echo "  Location: app/build/outputs/apk/debug/app-debug.apk"
    echo "  Size: $(ls -lh app/build/outputs/apk/debug/app-debug.apk | awk '{print $5}')"
else
    echo -e "${RED}✗ Debug APK build failed${NC}"
    exit 1
fi

# Build release APK (unsigned)
echo ""
echo -e "${YELLOW}Building release APK (unsigned)...${NC}"
./gradlew assembleRelease

if [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
    echo -e "${GREEN}✓ Release APK built successfully${NC}"
    echo "  Location: app/build/outputs/apk/release/app-release-unsigned.apk"
    echo "  Size: $(ls -lh app/build/outputs/apk/release/app-release-unsigned.apk | awk '{print $5}')"
    echo ""
    echo -e "${YELLOW}To sign the release APK:${NC}"
    echo "  1. Create keystore: keytool -genkey -v -keystore emergency-mesh.keystore -alias emergency-mesh -keyalg RSA -keysize 2048 -validity 10000"
    echo "  2. Sign APK: apksigner sign --ks emergency-mesh.keystore app/build/outputs/apk/release/app-release-unsigned.apk"
else
    echo -e "${RED}✗ Release APK build failed${NC}"
    exit 1
fi

# Show build summary
echo ""
echo "=========================================="
echo "  Build Summary"
echo "=========================================="
echo ""
echo -e "${GREEN}Build completed successfully!${NC}"
echo ""
echo "Next steps:"
echo "  1. Install debug APK on test devices"
echo "  2. Run through testing checklist"
echo "  3. Sign release APK for distribution"
echo "  4. Share with family/friends"
echo ""
echo "Testing checklist: See PRODUCTION_DEPLOYMENT.md"
echo ""
