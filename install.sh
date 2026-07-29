#!/bin/sh
# Installs the sugr CLI - downloads the latest release's native binary for
# your OS/arch (built by .github/workflows/release.yml) into $SUGR_INSTALL_DIR
# (default ~/.sugr/bin) and tells you to add it to PATH if it isn't already.
#
# Usage: curl -fsSL https://raw.githubusercontent.com/sugr-app/sugr/main/install.sh | sh
set -eu

repo="sugr-app/sugr"
install_dir="${SUGR_INSTALL_DIR:-$HOME/.sugr/bin}"

os=$(uname -s)
arch=$(uname -m)

case "$os" in
    Linux)
        platform="linux-x64"
        ;;
    Darwin)
        case "$arch" in
            arm64) platform="macos-arm64" ;;
            *)
                echo "sugr: unsupported macOS architecture '$arch' - only Apple Silicon (arm64) builds are published so far." >&2
                echo "Build from source instead: see https://github.com/$repo#cli" >&2
                exit 1
                ;;
        esac
        ;;
    MINGW*|MSYS*|CYGWIN*)
        # Git Bash/MSYS2/Cygwin all report a *_NT-* uname - it's really Windows underneath,
        # just running this script from a POSIX shell. install.ps1 is the supported path
        # there: it persists to the real Windows user PATH, which a plain `export` from
        # inside this shell can't do (it would only last the current bash session).
        echo "sugr: this looks like Windows (via $os) - use install.ps1 instead, from PowerShell:" >&2
        echo "  irm https://raw.githubusercontent.com/$repo/main/install.ps1 | iex" >&2
        exit 1
        ;;
    *)
        echo "sugr: unsupported OS '$os' - see https://github.com/$repo#cli to build from source." >&2
        exit 1
        ;;
esac

asset="sugr-$platform"
url="https://github.com/$repo/releases/latest/download/$asset"
dest="$install_dir/sugr"

mkdir -p "$install_dir"
echo "Downloading $url"
curl -fsSL "$url" -o "$dest"
chmod +x "$dest"

echo "Installed sugr to $dest"

case ":$PATH:" in
    *":$install_dir:"*)
        ;;
    *)
        echo ""
        echo "Add it to your PATH:"
        echo "  export PATH=\"$install_dir:\$PATH\""
        echo ""
        ;;
esac

"$dest" --version
