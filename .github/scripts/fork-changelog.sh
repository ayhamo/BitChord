#!/usr/bin/env bash
# Fork-only. Prints a release's changelog (Markdown) for android.yml and fork-pc-release.yml:
#   fork-changelog.sh <tag prefix> <version> <path>...
#   fork-changelog.sh fork-v 1.9.1 app shared sharedUi
# Lists main's own commits (first parent: the fork's commits and its upstream merges, not every
# upstream commit inside a merge) that touch <path>, so the phone's list leaves out PC-only and
# workflow-only commits and the PC's leaves out phone-only ones. Two parts:
#   - new in this build: since the release of this same version that is about to be replaced
#   - everything since the previous version's release (the highest <prefix><version> below this one)
# Run it before the old release is deleted (it reads that release's commit). Needs the full history
# (checkout fetch-depth: 0), GH_TOKEN and GITHUB_REPOSITORY.
set -euo pipefail
prefix=$1 version=$2
shift 2
repo=$GITHUB_REPOSITORY

# The commit a release's tag points at, when it is in this checkout's history.
commit_of() {
  local sha
  sha=$(gh api "repos/$repo/commits/$1" --jq .sha 2> /dev/null) || return 0
  git cat-file -e "$sha^{commit}" 2> /dev/null && echo "$sha" || true
}

# Subjects of main's commits in a range that touch the given paths, readable for a release page.
changes() {
  git log --first-parent --format='%s' "$1" -- "${paths[@]}" |
    sed -E \
      -e 's/ ?\[skip ci\]//g' \
      -e "s/^Merge tag '(v[^']+)'.*/Upstream BitChord \1 merged in/" \
      -e 's/^Merge (remote-tracking )?branch .upstream\/main.*/Upstream BitChord changes merged in/' \
      -e 's/^Merge upstream (v[^ ]+).*/Upstream BitChord \1 merged in/' \
      -e 's/ \(#[0-9]+\)$//' \
      -e 's/^/- /'
}

# At most 40 lines, then how many more.
cap() {
  local lines
  lines=$(cat)
  local n
  n=$(printf '%s\n' "$lines" | grep -c . || true)
  printf '%s\n' "$lines" | head -40
  if [ "$n" -gt 40 ]; then echo "- …and $((n - 40)) more (see the commits on main)"; fi
}

paths=("$@")
current=$(commit_of "$prefix$version")
previous_version=$(gh release list -R "$repo" --limit 100 --json tagName,isDraft \
  -q ".[] | select(.isDraft | not) | .tagName | select(startswith(\"$prefix\"))" |
  sed "s/^$prefix//" | grep -E '^[0-9][0-9.]*$' | { cat; echo "$version"; } | sort -uV |
  grep -B1 -x -F "$version" | grep -v -x -F "$version" || true)
previous=$([ -n "$previous_version" ] && commit_of "$prefix$previous_version" || true)

if [ -n "$current" ] && [ "$current" != "$(git rev-parse HEAD)" ]; then
  new=$(changes "$current..HEAD" | cap)
  if [ -n "$new" ]; then
    echo "### New in this build"
    echo
    echo "$new"
    echo
  fi
fi
if [ -n "$previous" ]; then
  all=$(changes "$previous..HEAD" | cap)
  echo "### Changes since $previous_version"
  echo
  if [ -n "$all" ]; then echo "$all"; else echo "- Nothing new for this app since $previous_version."; fi
  echo
fi
