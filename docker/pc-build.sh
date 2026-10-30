# Cross-builds the Windows portable zip of the desktop app (branch pc) in eclipse-temurin:21-jdk.
# From Git Bash, repo root:
#   MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/work" -v "$(pwd -W)/out:/out" -v bitchord-gradle:/root/.gradle eclipse-temurin:21-jdk bash /work/docker/pc-build.sh
set -e
apt-get update -qq >/dev/null
apt-get install -y -qq cmake ninja-build g++-mingw-w64-x86-64 >/dev/null 2>&1
update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix >/dev/null 2>&1 || true
update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix >/dev/null 2>&1 || true
cd /work
tr -d '\r' < gradlew > .gw
trap 'rm -f /work/.gw' EXIT
sh .gw --no-daemon -Pbitchord.target=windows -Pbitchord.version=1.9 ${1:-:desktopApp:windowsPortable} > /tmp/log.txt 2>&1 || { grep -nE '^e: |error:|FAILED|fatal|went wrong' -A3 /tmp/log.txt | head -60; exit 1; }
grep -E 'windowsPortable:|BUILD|tests completed' /tmp/log.txt
if ls desktopApp/build/distributions/*.zip >/dev/null 2>&1; then mkdir -p /out; cp desktopApp/build/distributions/*.zip /out/; fi
