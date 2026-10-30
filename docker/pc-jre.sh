#!/bin/sh
# Unpacks the Windows Java the portable zip carries into the folder named by $1 (its bin/ directly inside).
# Pinned and checked: an update to Java is a change here, not whatever the day's download happens to be.
# Keeps the download in $JRE_CACHE when that is set. Needs curl, unzip and sha256sum.
set -e
FILE=OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip
SHA256=2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628
URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/$FILE"
DEST="${1:?usage: pc-jre.sh <folder>}"
CACHE="${JRE_CACHE:-/tmp/bitchord-jre}"
mkdir -p "$CACHE"
if ! echo "$SHA256  $CACHE/$FILE" | sha256sum -c - >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$CACHE/$FILE" "$URL"
    echo "$SHA256  $CACHE/$FILE" | sha256sum -c -
fi
UNPACK=$(mktemp -d)
unzip -q "$CACHE/$FILE" -d "$UNPACK"
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$UNPACK"/jdk-* "$DEST"
rm -rf "$UNPACK"
