# Project Backup Script - Copy, compress and move project folder

# Set project root directory (source)
$sourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectName = Split-Path -Leaf $sourceDir

# Destination directory
$destDir = "H:\Code_Project\Gradum"

# Generate timestamp
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"

# Zip output path (save to destination directory)
$zipFilename = "${projectName}_${timestamp}.zip"
$zipOutputPath = Join-Path $destDir $zipFilename

# Temporary backup directory (outside project folder to avoid recursion)
$tempBackupDir = Join-Path $env:TEMP "${projectName}_backup_${timestamp}"

Write-Host "Starting project backup: $sourceDir" -ForegroundColor Green
Write-Host "Timestamp: $timestamp" -ForegroundColor Cyan
Write-Host "Temporary directory: $tempBackupDir" -ForegroundColor Gray
Write-Host "-" * 50

try {
    # Check if destination directory exists
    if (-not (Test-Path $destDir)) {
        Write-Host "Creating destination directory: $destDir" -ForegroundColor Yellow
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }
    
    # 1. Copy project files (exclude unwanted files)
    Write-Host "`n[1/4] Copying project files..." -ForegroundColor Yellow
    Write-Host "Source: $sourceDir" -ForegroundColor Gray
    Write-Host "Destination: $destDir" -ForegroundColor Gray
    Write-Host "Excluding: Python cache files only" -ForegroundColor Gray
    
    # Create temp directory
    if (Test-Path $tempBackupDir) {
        Remove-Item -Path $tempBackupDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $tempBackupDir -Force | Out-Null
    
    Write-Host "Copying files with Robocopy..." -ForegroundColor Gray
    & robocopy $sourceDir $tempBackupDir *.* /E /XD __pycache__ /XF *.pyc *.pyo *.pyd backup.ps1 chat.md /NFL /NDL /NJH /NJS | Out-Null
    
    # Count files copied
    $fileCount = (Get-ChildItem -Path $tempBackupDir -Recurse -File).Count
    Write-Host "Copied $fileCount files" -ForegroundColor Green
    
    # 2. Compress backup directory
    Write-Host "`n[2/4] Compressing project files..." -ForegroundColor Yellow
    
    if (Test-Path $zipOutputPath) {
        Remove-Item -Path $zipOutputPath -Force -ErrorAction SilentlyContinue
        Write-Host "Removed existing zip file" -ForegroundColor Gray
    }
    
    Compress-Archive -Path "$tempBackupDir\*" -DestinationPath $zipOutputPath -Force
    Write-Host "Compression completed" -ForegroundColor Green
    
    # 3. Clean up temporary backup directory
    Write-Host "`n[3/4] Cleaning up temporary files..." -ForegroundColor Yellow
    Remove-Item -Path $tempBackupDir -Recurse -Force
    Write-Host "Temporary directory removed" -ForegroundColor Green
    
    # 4. Verify backup
    Write-Host "`n[4/4] Verifying backup..." -ForegroundColor Yellow
    if (Test-Path $zipOutputPath) {
        Write-Host "Backup file verified" -ForegroundColor Green
    }
    
    # Get zip file size
    $zipSize = (Get-Item $zipOutputPath).Length
    $zipSizeMB = [math]::Round($zipSize / 1MB, 2)
    
    Write-Host "`nBackup completed!" -ForegroundColor Green
    Write-Host "Zip file location: $zipOutputPath" -ForegroundColor Cyan
    Write-Host "Zip file size: $zipSizeMB MB" -ForegroundColor Cyan
    Write-Host "Total files: $fileCount" -ForegroundColor Cyan
    
} catch {
    Write-Host "`nBackup failed: $_" -ForegroundColor Red
    
    # Clean up temporary files
    if (Test-Path $tempBackupDir) {
        Remove-Item -Path $tempBackupDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $zipOutputPath) {
        Remove-Item -Path $zipOutputPath -Force -ErrorAction SilentlyContinue
    }
    
    throw
}
