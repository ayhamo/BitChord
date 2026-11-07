#!/bin/sh
# Builds BitChord.exe, the portable folder's launcher (desktopApp/native/launcher), into the folder
# named by $1, with version $2 (e.g. 1.9 or 1.9-beta1) and build number $3 (optional, 0 when left
# out) in its Windows version info. Beside it BitChordUpdate.exe, the in-app updater (ForkUpdater).
# Run from the repo root; needs x86_64-w64-mingw32-gcc and -windres (pc-build.sh installs them).
set -e
OUT="${1:?usage: pc-launcher.sh <folder> <version> [build]}"
VERSION="${2:?usage: pc-launcher.sh <folder> <version> [build]}"
BUILD="${3:-0}"
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
    echo "#define BITCHORD_BUILD $BUILD"
    echo "#define BITCHORD_VERSION \"$VERSION\""
} > "$WORK/version.h"
for file in BitChord.rc BitChordUpdate.rc BitChordUpdate.manifest; do
    tr -d '\r' < "$SRC/$file" > "$WORK/$file"
done
cp desktopApp/packaging/icons/AppIcon.ico "$WORK/AppIcon.ico"
(cd "$WORK" && x86_64-w64-mingw32-windres -O coff -i BitChord.rc -o BitChord.res)
(cd "$WORK" && x86_64-w64-mingw32-windres -O coff -i BitChordUpdate.rc -o BitChordUpdate.res)
mkdir -p "$OUT"
x86_64-w64-mingw32-gcc -O2 -s -static -municode -mwindows -Wall -Wextra -o "$OUT/BitChord.exe" \
    "$SRC/BitChord.c" "$WORK/BitChord.res" -lshell32
x86_64-w64-mingw32-gcc -O2 -s -static -municode -mwindows -Wall -Wextra -o "$OUT/BitChordUpdate.exe" \
    "$SRC/BitChordUpdate.c" "$WORK/BitChordUpdate.res" -lshell32 -lcomctl32
