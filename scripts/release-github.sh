#!/usr/bin/env bash
# Builds the signed APK of the github flavour for a GitHub release (also what Obtainium installs).
#   - bumps versionCode in app/version.properties; the release is tagged v<versionName>, so set a new
#     versionName first
#   - runs the tests, builds :app:assembleGithubRelease (R8 on), checks it's signed with the direct key
#   - writes to builds/github/v<versionName>/: finsync-<versionName>.apk, its .sha256, version.json (read by
#     the app's update check), mapping.txt (keep it, don't publish it) and release-notes.md
# It doesn't publish anything: the printed `gh release create` does that. Commit app/version.properties too.
. "$(dirname "$0")/release-common.sh"

if [ ! -f "$HOME/.config/finsync/direct-signing.properties" ]; then
    echo "No direct-download key (~/.config/finsync/direct-signing.properties). Refusing to make a debug-signed release." >&2
    exit 1
fi
name=$(sed -n 's/^versionName=//p' "$PROPS")
if git rev-parse -q --verify "refs/tags/v$name" >/dev/null || [ -d "builds/github/v$name" ]; then
    echo "v$name was released already: set a new versionName in $PROPS first." >&2
    exit 1
fi
bump_version

./gradlew --quiet :engine:allTests :cli:test :app:testGithubReleaseUnitTest :app:assembleGithubRelease

apk=app/build/outputs/apk/github/release/app-github-release.apk
apksigner=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
cert=$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate DN: //p')
case "$cert" in
    *"FinSync direct key"*) ;;
    *) echo "The APK isn't signed with the direct-download key (signer: ${cert:-none})." >&2; false ;;
esac

out="builds/github/v$name"
file="finsync-$name.apk"
mkdir -p "$out"
cp "$apk" "$out/$file"
cp app/build/outputs/mapping/githubRelease/mapping.txt "$out/mapping.txt"
(cd "$out" && sha256sum "$file" > "$file.sha256")
sha=$(cut -d' ' -f1 "$out/$file.sha256")
printf '{"versionCode":%d,"versionName":"%s","apk":"%s","sha256":"%s"}\n' "$code" "$name" "$file" "$sha" > "$out/version.json"
prev=$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)
{
    echo "## FinSync $name"
    echo
    echo "Changes${prev:+ since $prev}:"
    git log --no-merges --format='- %s' ${prev:+"$prev"..HEAD}
    echo
    echo "**Install:** download \`$file\` below and open it (allow installing from your browser if asked)."
    echo "Or add this repository to [Obtainium](https://obtainium.imranr.dev/) to get updates automatically."
    echo "This APK and the Google Play version are signed differently and can't update each other:"
    echo "uninstall one before installing the other."
    echo
    echo "SHA-256 of \`$file\`: \`$sha\`"
    echo "Signing certificate: $cert"
} > "$out/release-notes.md"
copy_out "github/v$name" "$out/$file" "$out/$file.sha256" "$out/version.json" "$out/release-notes.md" "$out/mapping.txt"
trap - ERR

echo "versionCode $code, versionName $name"
echo "APK: $out/$file ($(du -h "$out/$file" | cut -f1)), SHA-256 $sha"
echo "Signed by: $cert"
echo "Next: commit $PROPS, then publish (needs a gh login):"
echo "  gh release create v$name --title \"FinSync $name\" --notes-file $out/release-notes.md \\"
echo "     $out/$file $out/$file.sha256 $out/version.json"
