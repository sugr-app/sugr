# Installs the sugr CLI - downloads the latest release's native binary
# (built by .github/workflows/release.yml) into $env:SUGR_INSTALL_DIR
# (default %LOCALAPPDATA%\sugr\bin) and adds it to your user PATH.
#
# Usage: irm https://raw.githubusercontent.com/sugr-app/sugr/main/install.ps1 | iex
$ErrorActionPreference = "Stop"

$repo = "sugr-app/sugr"
$installDir = if ($env:SUGR_INSTALL_DIR) { $env:SUGR_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA "sugr\bin" }

$asset = "sugr-windows-x64.exe"
$url = "https://github.com/$repo/releases/latest/download/$asset"
$dest = Join-Path $installDir "sugr.exe"

New-Item -ItemType Directory -Force -Path $installDir | Out-Null

Write-Host "Downloading $url"
Invoke-WebRequest -Uri $url -OutFile $dest

Write-Host "Installed sugr to $dest"

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notlike "*$installDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$installDir", "User")
    $env:Path = "$env:Path;$installDir"
    Write-Host "Added $installDir to your user PATH - open a new terminal for it to take effect everywhere."
}

& $dest --version
