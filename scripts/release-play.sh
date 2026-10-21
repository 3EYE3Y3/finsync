#!/usr/bin/env bash
# Builds a signed release bundle (AAB) of the play flavour for Google Play.
#   - bumps versionCode in app/version.properties
#   - runs the tests, builds :app:bundlePlayRelease (R8 on), checks it's signed with the upload key
#   - puts the AAB and its R8 mapping file in builds/play/ (git-ignored), and FINSYNC_COPY_TO/play if set
# Upload in Play Console. Commit app/version.properties afterwards.
. "$(dirname "$0")/release-common.sh"

if [ ! -f "$HOME/.config/finsync/signing.properties" ]; then
    echo "No upload key (~/.config/finsync/signing.properties). Refusing: Play must never get a debug-signed build." >&2
    exit 1
fi
bump_version

./gradlew --quiet :engine:allTests :cli:test :app:testPlayReleaseUnitTest :app:bundlePlayRelease

aab=app/build/outputs/bundle/playRelease/app-play-release.aab
mapping=app/build/outputs/mapping/playRelease/mapping.txt
# The bundle must carry the upload certificate, not Android's debug one.
cert=$("${JAVA_HOME:+$JAVA_HOME/bin/}keytool" -printcert -jarfile "$aab" 2>/dev/null | sed -n 's/^.*Owner: //p' | head -1)
case "$cert" in
    *"FinSync upload key"*) ;;
    *) echo "The bundle isn't signed with the upload key (owner: ${cert:-none})." >&2; false ;;
esac

base="finsync-$name-$code"
mkdir -p builds/play
cp "$aab" "builds/play/$base.aab"
cp "$mapping" "builds/play/$base-mapping.txt"
copy_out play "builds/play/$base.aab" "builds/play/$base-mapping.txt"
trap - ERR

echo "versionCode $code, versionName $name"
echo "AAB:     builds/play/$base.aab ($(du -h "builds/play/$base.aab" | cut -f1))"
echo "Mapping: builds/play/$base-mapping.txt (upload it with the bundle for readable crash reports)"
echo "Signed by: $cert"
echo "Next: commit $PROPS; upload the AAB in Play Console."
