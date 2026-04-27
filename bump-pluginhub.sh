#!/usr/bin/env bash
# Bumps plugins/log-adviser in the sibling plugin-hub clone to the latest
# commit on origin/master of this repo, then commits and pushes the
# currently-checked-out branch in that clone.
#
# Usage: ./bump-pluginhub.sh
#
# Prerequisites:
#   - C:/Users/Chico/source/repos/SFranciscoSouza/plugin-hub is a clone of
#     your fork (https://github.com/SFranciscoSouza/plugin-hub.git).
#   - The branch you want to update is checked out in that clone
#     (e.g. add-log-adviser-plugin for the open PR; a new branch for future
#     release PRs).
set -euo pipefail

plugin_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
hub_repo="$(cd "$plugin_repo/.." && pwd)/plugin-hub"

if [ ! -d "$hub_repo/.git" ]; then
  echo "plugin-hub clone not found at $hub_repo" >&2
  echo "Clone it with: git clone https://github.com/SFranciscoSouza/plugin-hub.git \"$hub_repo\"" >&2
  exit 1
fi

git -C "$plugin_repo" fetch origin master --quiet
sha="$(git -C "$plugin_repo" rev-parse origin/master)"

printf 'repository=https://github.com/SFranciscoSouza/LogAdviser.git\ncommit=%s\n' "$sha" \
  > "$hub_repo/plugins/log-adviser"

git -C "$hub_repo" add plugins/log-adviser

if git -C "$hub_repo" diff --cached --quiet; then
  echo "plugins/log-adviser already at $sha; nothing to do."
  exit 0
fi

branch="$(git -C "$hub_repo" rev-parse --abbrev-ref HEAD)"
short="${sha:0:7}"
echo "Bumping plugins/log-adviser to $short on branch $branch"
git -C "$hub_repo" commit -m "Bump log-adviser to $short"
git -C "$hub_repo" push origin "$branch"
