#!/bin/bash
# Project Backup Script for macOS
# Copy, compress and move project folder to USB drive

# Project root directory (parent of where this script is located)
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_NAME="$(basename "$SCRIPT_DIR")"

# Destination: USB drive "葛汪洋"
USB_DRIVE="/Volumes/葛汪洋"
DEST_DIR="${USB_DRIVE}/Code_Project/Gradum/"

# Generate timestamp
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Zip output path
ZIP_FILENAME="${PROJECT_NAME}_${TIMESTAMP}.zip"
ZIP_OUTPUT_PATH="${DEST_DIR}/${ZIP_FILENAME}"

# Temporary backup directory
TEMP_BACKUP_DIR="/tmp/${PROJECT_NAME}_backup_${TIMESTAMP}"

echo "=========================================="
echo "Starting project backup"
echo "=========================================="
echo "Source: $SCRIPT_DIR"
echo "Destination: $DEST_DIR"
echo "USB Drive: $USB_DRIVE"
echo "Timestamp: $TIMESTAMP"
echo ""

# Check if USB drive is connected
if [ ! -d "$USB_DRIVE" ]; then
    echo "ERROR: USB drive not found at $USB_DRIVE"
    echo "Please connect the USB drive and try again."
    exit 1
fi

# 1. Create destination directory if needed
echo "[1/5] Preparing directories..."
mkdir -p "$DEST_DIR"
mkdir -p "$TEMP_BACKUP_DIR"
echo "Directories prepared"

# 2. Copy project files (exclude unwanted files)
echo ""
echo "[2/5] Copying project files..."
echo "Excluding: __pycache__, *.pyc, *.pyo, *.pyd, backup.*, .git, .idea, .vscode, output, *.zip"

rsync -av --progress \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    --exclude='*.pyo' \
    --exclude='*.pyd' \
    --exclude='backup.*' \
    --exclude='.git' \
    --exclude='.idea' \
    --exclude='.vscode' \
    --exclude='output' \
    --exclude='*.zip' \
    --exclude='.DS_Store' \
    --exclude='.venv' \
    --exclude='venv' \
    "$SCRIPT_DIR/" "$TEMP_BACKUP_DIR/"

# Count files
FILE_COUNT=$(find "$TEMP_BACKUP_DIR" -type f | wc -l | tr -d ' ')
echo "Copied $FILE_COUNT files"

# 3. Compress backup directory
echo ""
echo "[3/5] Compressing project files..."
cd /temp
zip -r -q "$ZIP_FILENAME" "${PROJECT_NAME}_backup_${TIMESTAMP}"
echo "Compression completed"

# 4. Move to destination
echo ""
echo "[4/5] Moving to destination..."
mv "$ZIP_FILENAME" "$DEST_DIR/"
echo "Moved to: $ZIP_OUTPUT_PATH"

# 5. Clean up and verify
echo ""
echo "[5/5] Cleaning up temporary files..."
rm -rf "$TEMP_BACKUP_DIR"
echo "Temporary files removed"

# Get zip file size
ZIP_SIZE=$(stat -f%z "$ZIP_OUTPUT_PATH" 2>/dev/null || stat -c%s "$ZIP_OUTPUT_PATH" 2>/dev/null)
ZIP_SIZE_MB=$(echo "scale=2; $ZIP_SIZE / 1024 / 1024" | bc)

echo ""
echo "=========================================="
echo "Backup completed successfully!"
echo "=========================================="
echo "Zip file: $ZIP_OUTPUT_PATH"
echo "Zip file size: $ZIP_SIZE_MB MB"
echo "Total files: $FILE_COUNT"
echo ""

# Show notification on macOS
osascript -e "display notification \"Backup completed!\" with title \"Gradum Backup\" subtitle \"Files: $FILE_COUNT, Size: $ZIP_SIZE_MB MB\""