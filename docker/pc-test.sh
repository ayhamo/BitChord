# Runs the desktop app's unit tests in eclipse-temurin:21-jdk. It builds for Windows, as pc-build.sh
# does: a Linux target moves to Compose 1.12, whose Skia the fork's poster code doesn't compile
# against. desktopApp/build.gradle.kts adds Linux FFmpeg to the test classpath for this case; with the
# Windows FFmpeg alone every decoder/codec test failed ("Could not initialize class ...avutil").
# From Git Bash, repo root:
#   MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/work" -v bitchord-gradle:/root/.gradle eclipse-temurin:21-jdk bash /work/docker/pc-test.sh [extra gradle args, e.g. --tests "*ForkTopPicks*"]
set -e
apt-get update -qq >/dev/null
apt-get install -y -qq cmake ninja-build g++-mingw-w64-x86-64 >/dev/null 2>&1
update-alternatives --set x86_64-w64-mingw32-g++ /usr/bin/x86_64-w64-mingw32-g++-posix >/dev/null 2>&1 || true
update-alternatives --set x86_64-w64-mingw32-gcc /usr/bin/x86_64-w64-mingw32-gcc-posix >/dev/null 2>&1 || true
cd /work
tr -d '\r' < gradlew > .gw
trap 'rm -f /work/.gw' EXIT
status=0
sh .gw --no-daemon -Pbitchord.target=windows :desktopApp:test --continue "$@" > /tmp/log.txt 2>&1 || status=$?
grep -E '^e: |FAILED|went wrong' -A3 /tmp/log.txt | head -80 || true
grep -E 'tests completed|BUILD' /tmp/log.txt || true
# The workflow stops the release on this.
exit $status
