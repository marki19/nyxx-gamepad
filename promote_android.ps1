# promote_android.ps1
# This script rotates the Stable Android APK to Backup, then compiles the current codebase as the new Stable APK.

$clientDir = "client"
$appDir = "$clientDir/app"
$stableDir = "$appDir/builds/stable"
$backupDir = "$appDir/builds/backup"
$experimentalDir = "$appDir/builds/experimental"
$apkPath = "$appDir/build/outputs/apk/debug/app-debug.apk"

Write-Host "--- Nyxx Android Build Promotion ---" -ForegroundColor Cyan

# 1. Clear out the old Backup
if (Test-Path $backupDir) {
    Remove-Item -Recurse -Force "$backupDir\*" -ErrorAction Ignore
}

# 2. Rotate Stable to Backup
if (Test-Path "$stableDir\Nyxx.apk") {
    Write-Host "Rotating current Stable APK to Backup..."
    Copy-Item -Path "$stableDir\Nyxx.apk" -Destination "$backupDir\Nyxx.apk" -Force
} else {
    Write-Host "No existing Stable APK found to rotate." -ForegroundColor Yellow
}

# 3. Clear out Stable directory
Remove-Item -Recurse -Force "$stableDir\*" -ErrorAction Ignore

# 4. Copy current APK to Stable
Write-Host "Copying the current Android APK as the new Stable build..."

if (Test-Path $apkPath) {
    Copy-Item -Path $apkPath -Destination "$stableDir\Nyxx.apk" -Force
    Write-Host "Successfully promoted new Stable Android build to $stableDir\Nyxx.apk!" -ForegroundColor Green
} else {
    Write-Host "Failed to find existing APK at $apkPath. Reverting rotation..." -ForegroundColor Red
    if (Test-Path "$backupDir\Nyxx.apk") {
        Copy-Item -Path "$backupDir\Nyxx.apk" -Destination "$stableDir\Nyxx.apk" -Force
    }
    Remove-Item -Recurse -Force "$backupDir\*" -ErrorAction Ignore
}
