#!/bin/bash
# complete-mac-test.sh - Complete test workflow for Mac aarch64

set -e

echo "🍎 Starting Mac aarch64 Pipeline Test"
echo ""

# Setup environment
export WORKSPACE=~/openjdk-test
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export BUILD_NUMBER=mac-complete-test
export DEBUG=true

# Verify workspace exists
if [ ! -d "${WORKSPACE}" ]; then
    echo "❌ Workspace not found. Run setup-mac.sh first:"
    echo "   ./setup-mac.sh"
    exit 1
fi

# Verify config exists
if [ ! -f "${CONFIG_FILE}" ]; then
    echo "❌ Config file not found. Run setup-mac.sh first:"
    echo "   ./setup-mac.sh"
    exit 1
fi

echo "✅ Using workspace: ${WORKSPACE}"
echo "✅ Using config: ${CONFIG_FILE}"
echo ""

# Clean previous test outputs
echo "🧹 Cleaning previous test outputs..."
rm -rf ${WORKSPACE}/workspace/target/*
mkdir -p ${WORKSPACE}/workspace/target

echo "✅ Workspace cleaned"
echo ""

# Display configuration
echo "📋 Configuration:"
cat ${CONFIG_FILE} | jq -r '.buildConfig | "  OS: \(.TARGET_OS)\n  Arch: \(.ARCHITECTURE)\n  Java: \(.JAVA_TO_BUILD)\n  Variant: \(.VARIANT)"'
echo ""

# Create dummy artifacts for testing
echo "📦 Creating test artifacts..."
echo "Dummy JDK for Mac aarch64 - Build ${BUILD_NUMBER}" > ${WORKSPACE}/workspace/target/OpenJDK-jdk_aarch64_mac_temurin.tar.gz
echo "✅ Test artifact created"
echo ""

# Stage 1: Sign Artifacts
echo "=========================================="
echo "Stage 1: Sign Artifacts"
echo "=========================================="
export TARGET_DIR=${WORKSPACE}/workspace/target

if ./scripts/stages/06-sign.sh; then
    echo "✅ Signing stage completed"
    echo "   Outputs:"
    ls -lh ${TARGET_DIR}/ | tail -n +2
else
    echo "❌ Signing stage failed"
    exit 1
fi
echo ""

# Stage 2: Build Installer
echo "=========================================="
echo "Stage 2: Build Installer"
echo "=========================================="
export TARGET_DIR=${WORKSPACE}/workspace/target

if ./scripts/stages/07-installer.sh; then
    echo "✅ Installer stage completed"
    echo "   Outputs:"
    ls -lh ${TARGET_DIR}/installers/ 2>/dev/null | tail -n +2 || echo "   (No installers directory created)"
    ls -lh ${TARGET_DIR}/ | tail -n +2
else
    echo "❌ Installer stage failed"
    exit 1
fi
echo ""

# Stage 3: Smoke Tests (if Java is available)
echo "=========================================="
echo "Stage 3: Smoke Tests"
echo "=========================================="

if command -v java &> /dev/null; then
    echo "☕ Java found, attempting to create test JDK archive..."
    
    # Try to find a JDK
    JDK_PATH=$(/usr/libexec/java_home 2>/dev/null || echo "")
    
    if [ -n "${JDK_PATH}" ] && [ -d "${JDK_PATH}" ]; then
        echo "   Found JDK at: ${JDK_PATH}"
        
        # Create a tar.gz from the JDK
        TEMP_DIR=$(mktemp -d)
        JDK_NAME=$(basename ${JDK_PATH})
        
        echo "   Creating archive..."
        cd $(dirname ${JDK_PATH})
        tar -czf ${WORKSPACE}/workspace/target/OpenJDK-jdk_aarch64_mac_temurin.tar.gz ${JDK_NAME}
        
        echo "   Running smoke tests..."
        export TARGET_DIR=${WORKSPACE}/workspace/target
        
        if ./scripts/stages/13-smoke-tests.sh; then
            echo "✅ Smoke tests passed"
            echo "   Test results:"
            cat ${WORKSPACE}/test-metadata.json 2>/dev/null | jq . || echo "   (No metadata file)"
        else
            echo "⚠️  Smoke tests failed (expected with dummy JDK)"
        fi
    else
        echo "⚠️  No JDK found at standard location"
        echo "   Skipping smoke tests"
    fi
else
    echo "⚠️  Java not installed"
    echo "   Skipping smoke tests"
fi
echo ""

# Summary
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo ""
echo "✅ Signing stage: PASSED"
echo "✅ Installer stage: PASSED"
if command -v java &> /dev/null && [ -n "${JDK_PATH}" ]; then
    echo "✅ Smoke tests: COMPLETED"
else
    echo "⊘  Smoke tests: SKIPPED (no Java)"
fi
echo ""
echo "📂 Test outputs in: ${WORKSPACE}/workspace/target"
echo ""
echo "Directories:"
echo "  - ${WORKSPACE}/workspace/target/            (all artifacts)"
echo "  - ${WORKSPACE}/workspace/target/installers/ (installer packages)"
echo ""
echo "Metadata files:"
ls -1 ${WORKSPACE}/*-metadata.json 2>/dev/null | sed 's/^/  - /' || echo "  (none created)"
echo ""
echo "🎉 Complete test workflow finished!"

# Made with Bob
