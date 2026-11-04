#!/bin/sh
# Builds BitChord.exe, the portable folder's launcher (desktopApp/native/launcher), into the folder
# named by $1, with version $2 (e.g. 1.9 or 1.9-beta1) in its Windows version info.
# Run from the repo root; needs x86_64-w64-mingw32-gcc and -windres (pc-build.sh installs them).
set -e
OUT="${1:?usage: pc-launcher.sh <folder> <version>}"
VERSION="${2:?usage: pc-launcher.sh <folder> <version>}"
SRC=desktopApp/native/launcher
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
# The numeric part only: 1.9-beta1 is 1.9.0.0 to Windows, as the installers' MAJOR.MINOR.BUILD is.
NUMBERS=$(echo "$VERSION" | sed 's/-.*//')
V1=$(echo "$NUMBERS" | cut -d. -f1); V2=$(echo "$NUMBERS" | cut -s -d. -f2); V3=$(echo "$NUMBERS" | cut -s -d. -f3)
{
    echo "#define BITCHORD_V1 ${V1:-0}"
    echo "#define BITCHORD_V2 ${V2:-0}"
    echo "#define BITCHORD_V3 ${V3:-0}"
    echo "#define BITCHORD_VERSION \"$VERSION\""
} > "$WORK/version.h"
tr -d '\r' < "$SRC/BitChord.rc" > "$WORK/BitChord.rc"
cp desktopApp/packaging/icons/AppIcon.ico "$WORK/AppIcon.ico"
(cd "$WORK" && x86_64-w64-mingw32-windres -O coff -i BitChord.rc -o BitChord.res)
mkdir -p "$OUT"
x86_64-w64-mingw32-gcc -O2 -s -static -municode -mwindows -Wall -Wextra -o "$OUT/BitChord.exe" \
    "$SRC/BitChord.c" "$WORK/BitChord.res" -lshell32
