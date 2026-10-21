# Shared by the release scripts (sourced, not run).
# Optional machine settings live outside the repository in ~/.config/finsync/release.env, e.g.
#   FINSYNC_COPY_TO=/some/shared/folder   # where to copy finished builds as well as builds/
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f ./env.sh ] && . ./env.sh
[ -f "$HOME/.config/finsync/release.env" ] && . "$HOME/.config/finsync/release.env"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK (see the README)}"
PROPS=app/version.properties

# Bumps versionCode (Play and Android need it to grow with every release); put back if the build fails.
bump_version() {
    old=$(sed -n 's/^versionCode=//p' "$PROPS")
    name=$(sed -n 's/^versionName=//p' "$PROPS")
    code=$((old + 1))
    sed -i "s/^versionCode=.*/versionCode=$code/" "$PROPS"
    trap 'sed -i "s/^versionCode=.*/versionCode=$old/" "$PROPS"; echo "Failed: versionCode put back to $old." >&2' ERR
}

copy_out() { # copy_out SUBDIR FILE...
    local sub=$1; shift
    if [ -n "${FINSYNC_COPY_TO:-}" ]; then mkdir -p "$FINSYNC_COPY_TO/$sub"; cp "$@" "$FINSYNC_COPY_TO/$sub/"; echo "Copied to $FINSYNC_COPY_TO/$sub/"; fi
}
