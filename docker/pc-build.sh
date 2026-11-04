# Cross-builds the Windows portable zip of the desktop app (branch pc) in eclipse-temurin:21-jdk:
# out/BitChord Portable.zip, one folder "BitChord Portable" with BitChord.exe, its own Java and lib/.
# From Git Bash, repo root:
#   MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/work" -v "$(pwd -W)/out:/out" -v bitchord-gradle:/root/.gradle eclipse-temurin:21-jdk bash /work/docker/pc-build.sh
set -e
apt-get update -qq >/dev/null
apt-get install -y -qq cmake ninja-build g++-mingw-w64-x86-64 curl unzip >/dev/null 2>&1
update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix >/dev/null 2>&1 || true
update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix >/dev/null 2>&1 || true
cd /work
tr -d '\r' < gradlew > .gw
trap 'rm -f /work/.gw' EXIT
tr -d '\r' < docker/pc-jre.sh | JRE_CACHE=/root/.gradle/bitchord-jre sh -s -- /tmp/windows-jre
VERSION=$(tr -d '\r' < build.gradle.kts | sed -n 's/.*bitchordVersionName"\] = "\(.*\)"/\1/p')
tr -d '\r' < docker/pc-launcher.sh | sh -s -- /tmp/windows-launcher "$VERSION"
sh .gw --no-daemon -Pbitchord.target=windows -Pbitchord.version="$VERSION" -Pbitchord.windowsRuntime=/tmp/windows-jre -Pbitchord.windowsLauncher=/tmp/windows-launcher/BitChord.exe ${1:-:desktopApp:windowsPortable} > /tmp/log.txt 2>&1 || { grep -nE '^e: |error:|FAILED|fatal|went wrong' -A3 /tmp/log.txt | head -60; exit 1; }
grep -E 'windowsPortable:|BUILD|tests completed' /tmp/log.txt
# Only this build's zip: an older build's BitChord-<version>-windows-portable.zip may still be there.
if [ -f "desktopApp/build/distributions/BitChord Portable.zip" ]; then mkdir -p /out; cp "desktopApp/build/distributions/BitChord Portable.zip" /out/; fi
