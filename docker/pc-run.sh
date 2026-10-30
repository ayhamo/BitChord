# Unpacks out/BitChord-*-windows-portable.zip into out/pc-run and starts it for testing (Git Bash, repo root).
# Its data stays inside out/pc-run: user.home, APPDATA and LOCALAPPDATA all point there, so it can't
# read real browser profiles. Needs a Java 21 runtime unpacked at out/pc-run/jdk-*.
# Java Preferences still go to HKCU\Software\JavaSoft\Prefs\com\music: remove that when testing ends.
# Test door: http://127.0.0.1:8765 (see ForkDebugBridge). Output: out/pc-run/run.log. Screenshot: docker/pc-shot.ps1.
set -e
cd out/pc-run
rm -rf BitChord
unzip -q ../BitChord-*-windows-portable.zip
W=$(pwd -W)
mkdir -p home/AppData/Roaming home/AppData/Local home/tmp
export APPDATA="$W/home/AppData/Roaming" LOCALAPPDATA="$W/home/AppData/Local"
nohup ./jdk-*/bin/java.exe -Duser.home="$W/home" -Djava.io.tmpdir="$W/home/tmp" -Dbitchord.version=1.9 \
    -Dbitchord.debugBridge=8765 -Xmx512m -XX:+UseG1GC -XX:G1PeriodicGCInterval=20000 -XX:G1PeriodicGCSystemLoadThreshold=0 -cp "$W/BitChord/lib/*" com.music.bitchord.desktop.MainKt > run.log 2>&1 &
