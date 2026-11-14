#!/usr/bin/env bash
# Decides which builds a push needs, so web-only changes skip the APK jobs and
# Android-only changes skip the web build. Skipped jobs count as passed for the
# required checks on claude/sample-import, so nothing waits on work it doesn't need.
# Writes apk=true|false and web=true|false to $GITHUB_OUTPUT.
set -euo pipefail
install_branch=claude/sample-import

run_all() {
  echo "Running everything: $1"
  { echo apk=true; echo web=true; } >> "$GITHUB_OUTPUT"
  exit 0
}

[ "${GITHUB_EVENT_NAME:-}" = push ] || run_all "not a push (${GITHUB_EVENT_NAME:-unknown})"

if [ "$GITHUB_REF_NAME" = "$install_branch" ]; then
  # A merge into the install branch: compare with what was there before.
  base="${BEFORE:-}"
  if [ -z "$base" ] || [ "$base" = 0000000000000000000000000000000000000000 ] \
      || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
    run_all "no previous commit to compare with"
  fi
else
  # A feature branch: compare with where it left the install branch.
  base=$(git merge-base "origin/$install_branch" HEAD 2>/dev/null) || run_all "no merge base with $install_branch"
fi

files=$(git diff --name-only "$base" HEAD)
[ -n "$files" ] || run_all "no changed files"

apk=false
web=false
while IFS= read -r f; do
  case "$f" in
    # Shared between the APK and the web app.
    app/src/main/cpp/*|app/src/main/assets/*|app/src/main/java/*) apk=true; web=true ;;
    .github/scripts/changed-areas.sh) apk=true; web=true ;;
    # Web only.
    web/*|.github/workflows/web.yml) web=true ;;
    # Everything else (Gradle files, Android resources, CI, ...) needs the APK checks.
    *) apk=true ;;
  esac
done <<< "$files"

echo "Changed files:"; echo "$files" | sed 's/^/  /'
echo "apk=$apk web=$web"
{ echo "apk=$apk"; echo "web=$web"; } >> "$GITHUB_OUTPUT"
