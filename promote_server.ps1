# promote_server.ps1
# This script rotates the Stable build to Backup, then publishes the current codebase as the new Stable build.

$serverDir = "server"
$stableDir = "$serverDir/builds/stable"
$backupDir = "$serverDir/builds/backup"
$experimentalDir = "$serverDir/builds/experimental"

Write-Host "--- Nyxx Server Build Promotion ---" -ForegroundColor Cyan

# 1. Clear out the old Backup
if (Test-Path $backupDir) {
    Remove-Item -Recurse -Force "$backupDir\*" -ErrorAction Ignore
}

# 2. Rotate Stable to Backup
if (Test-Path "$stableDir\NyxxServer.exe") {
    Write-Host "Rotating current Stable build to Backup..."
    Copy-Item -Path "$stableDir\*" -Destination $backupDir -Recurse -Force
} else {
    Write-Host "No existing Stable build found to rotate." -ForegroundColor Yellow
}

# 3. Clear out Stable directory
Remove-Item -Recurse -Force "$stableDir\*" -ErrorAction Ignore

# 4. Publish new Stable build (Framework-dependent, Release mode)
Write-Host "Compiling and publishing the current codebase as the new Stable build..."
& "C:\Program Files\dotnet\dotnet.exe" publish "$serverDir/server.csproj" -c Release -o $stableDir /p:UseAppHost=true

if ($LASTEXITCODE -eq 0) {
    Write-Host "Successfully promoted new Stable build to $stableDir!" -ForegroundColor Green
} else {
    Write-Host "Failed to compile the new Stable build. Reverting rotation..." -ForegroundColor Red
    Copy-Item -Path "$backupDir\*" -Destination $stableDir -Recurse -Force
    Remove-Item -Recurse -Force "$backupDir\*" -ErrorAction Ignore
}
