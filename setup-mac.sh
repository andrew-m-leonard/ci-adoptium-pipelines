#!/bin/bash
# setup-mac.sh - Quick setup and run script for Mac aarch64 testing

set -e

echo "🍎 Mac aarch64 Pipeline Quick Start"
echo ""

# Check if we're in the right directory
if [ ! -f "run-pipeline.py" ]; then
    echo "❌ Error: Must run from refactored_pipeline_examples directory"
    echo "   cd /Users/anleonar/workspace/bob/refactored_pipeline_examples"
    exit 1
fi

# Check for Python
echo "🔍 Checking Python..."
if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 is not installed"
    echo "   Install with: brew install python3"
    exit 1
fi
echo "✅ Python 3 is installed: $(python3 --version)"

# Check for jq
echo "🔍 Checking jq..."
if ! command -v jq &> /dev/null; then
    echo "❌ jq is not installed"
    echo "   Install with: brew install jq"
    exit 1
fi
echo "✅ jq is installed: $(jq --version)"

echo ""
echo "✅ All dependencies installed"
echo ""
echo "=========================================="
echo "Running Pipeline"
echo "=========================================="
echo ""

# Run the pipeline using run-pipeline.py
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --workspace ~/openjdk-test

echo ""
echo "=========================================="
echo "Setup Complete!"
echo "=========================================="
echo ""
echo "📂 Workspace: ~/openjdk-test"
echo "📝 Config: ~/openjdk-test/pipeline-config.json"
echo "📦 Artifacts: ~/openjdk-test/workspace/target/"
echo ""
echo "To run again:"
echo "  ./run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64"
echo ""
echo "To clean and rebuild:"
echo "  ./run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64 --clean-workspace"
echo ""
echo "For more options:"
echo "  ./run-pipeline.py --help"

# Made with Bob
