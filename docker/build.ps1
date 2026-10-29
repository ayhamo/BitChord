# Builds the BitChord fork's dev debug APK in Docker.
#  - source is bind-mounted; Gradle caches live in the bitchord-gradle volume (Docker's disk is on D:).
#  - debug signing key lives in docker/keys (gitignored), mounted as /root/.android so it never changes.
#  - APKs go to D:\Programs\BitChord\out
param([switch]$NoImage, [string]$Task = 'assembleDevDebug')
$ErrorActionPreference = 'Continue'
function Check($what) { if ($LASTEXITCODE -ne 0) { Write-Host "$what failed ($LASTEXITCODE)"; exit $LASTEXITCODE } }

$repo = Split-Path $PSScriptRoot -Parent
$out = 'D:\Programs\BitChord\out'
New-Item -ItemType Directory -Force $out, "$PSScriptRoot\keys" | Out-Null

if (-not $NoImage) {
    docker build -t bitchord-build $PSScriptRoot
    Check 'docker build'
}

# gradlew is checked out with CRLF on Windows; strip it inside the container (git sees no change).
$script = @"
set -e
sed -i 's/\r`$//' gradlew
./gradlew --no-daemon -Pbitchord.bundleWear=false $Task
if ls app/build/outputs/apk/dev/debug/*.apk >/dev/null 2>&1; then cp app/build/outputs/apk/dev/debug/*.apk /out/; fi
"@

docker run --rm `
    -v "${repo}:/work" `
    -v bitchord-gradle:/root/.gradle `
    -v "$PSScriptRoot\keys:/root/.android" `
    -v "${out}:/out" `
    bitchord-build bash -c $script
Check 'gradle build'
Get-ChildItem $out -Filter *.apk | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,1)}}
