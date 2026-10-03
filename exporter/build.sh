#!/usr/bin/env bash
# Builds the Songs of Syx exporter script mod and installs it into the (Proton) user mods folder.
set -euo pipefail
cd "$(dirname "$0")"
GAME="${SOS_GAME:-$HOME/.local/share/Steam/steamapps/common/Songs of Syx}"
USERDIR="${SOS_USER:-$HOME/.local/share/Steam/steamapps/compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming/songsofsyx}"
VER="${SOS_VER:-V71}"
rm -rf build && mkdir -p build/classes
javac --release 16 -encoding UTF-8 -cp "$GAME/SongsOfSyx.jar" -d build/classes $(find src -name '*.java')
(cd build/classes && jar cf ../sos2mc-export.jar .)
MOD="mod/sos2mc-export"
rm -rf "$MOD" && mkdir -p "$MOD/$VER/script"
cp build/sos2mc-export.jar "$MOD/$VER/script/"
cat > "$MOD/_Info.txt" <<INFO
VERSION: "1.0.0",
NAME: "Syx Map Exporter (sos2mc)",
DESC: "Exports the settlement map to sos2mc/*.syxmap in the user folder, for the Minecraft converter.",
AUTHOR: "gmz",
INFO: "sos2mc",
INFO
if [[ "${1:-}" == "--install" ]]; then
  rm -rf "$USERDIR/mods/sos2mc-export"
  cp -r "$MOD" "$USERDIR/mods/"
  echo "installed to $USERDIR/mods/sos2mc-export"
fi
