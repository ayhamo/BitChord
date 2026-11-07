# Unpacks out/BitChord Portable.zip into out/pc-run and starts it for testing (Git Bash, repo root).
# APPDATA and LOCALAPPDATA point inside out/pc-run, so it can't read real browser profiles.
# Runs the zip's own Java. Test door: http://127.0.0.1:8765 (see ForkDebugBridge). Screenshot: docker/pc-shot.ps1.
#
# Plain (default): java.exe directly, user.home in out/pc-run/home. Not the portable mode: Java
#   Preferences still go to HKCU\Software\JavaSoft\Prefs\com\music, remove that when testing ends.
#   Output: out/pc-run/run.log.
# PORTABLE=1: as a listener starts it, through BitChord.exe --log. Everything, settings included,
#   stays in "out/pc-run/BitChord Portable/data" (kept between runs); output in its bitchord.log.
#   USERPROFILE points at out/pc-run/home, and the first start's import of the old app's data is
#   off unless IMPORT=1, which also reads the real registry's BitChord settings (read only).
set -e
cd out/pc-run
W=$(pwd -W)
mkdir -p home/AppData/Roaming home/AppData/Local home/tmp
export APPDATA="$W/home/AppData/Roaming" LOCALAPPDATA="$W/home/AppData/Local"
# Everything but the portable folder's data/, so a rebuilt lib/ has no stale jars.
if [ -d "BitChord Portable" ]; then
    find "BitChord Portable" -mindepth 1 -maxdepth 1 ! -name data -exec rm -rf {} +
fi
unzip -qo "../BitChord Portable.zip"
if [ "${PORTABLE:-0}" = 1 ]; then
    export USERPROFILE="$W/home"
    IMPORT_FLAG=-Dbitchord.portableImport=false
    [ "${IMPORT:-0}" = 1 ] && IMPORT_FLAG=
    # The java launcher reads JDK_JAVA_OPTIONS; BitChord.exe passes the environment on.
    # EXTRA_JAVA_OPTS: more -D flags for a test, e.g. -Dbitchord.updateUrl=http://127.0.0.1:8770/releases.json (ForkUpdater)
    export JDK_JAVA_OPTIONS="-Dbitchord.debugBridge=8765 $IMPORT_FLAG ${EXTRA_JAVA_OPTS:-}"
    "./BitChord Portable/BitChord.exe" --log
else
    nohup "./BitChord Portable/runtime/bin/java.exe" -Duser.home="$W/home" -Djava.io.tmpdir="$W/home/tmp" -Dbitchord.version=1.9 \
        -Dbitchord.debugBridge=8765 -Xmx512m -XX:+UseG1GC -XX:G1PeriodicGCInterval=20000 -XX:G1PeriodicGCSystemLoadThreshold=0 -cp "$W/BitChord Portable/lib/*" com.music.bitchord.desktop.MainKt > run.log 2>&1 &
fi
