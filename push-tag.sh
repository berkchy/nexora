#!/data/data/com.termux/files/usr/bin/bash
# Pushes a new tag to the private fork to trigger the build & release workflow,
# then prints the new run. Run from the repo root.
#
# Usage:
#   ./push-tag.sh [version]        # default v1.10.0  (re-tags current commit)
#   ./push-tag.sh v1.11.0          # new version tag
#
# Options:
#   -n, --no-commit   skip the git add/commit step (just re-tag existing HEAD)
set -euo pipefail

REMOTE="crashview"
VER="${1:?usage: $0 <version> [--no-commit] ;}"
BRANCH_REF=refs/heads/master
TAG_REF="refs/tags/$VER"

commit_arg="${2:-}"
commit=1
if [[ "$commit_arg" == "--no-commit" || "$commit_arg" == "-n" ]]; then
  commit=0
fi

if [[ "$commit" == "1" ]]; then
  echo "==> committing changes"
  git add -A
  if ! git diff --cached --quiet; then
    git commit -m "build: $VER"
  else
    echo "    nothing to commit"
  fi
else
  echo "==> skipping commit (--no-commit)"
fi

echo "==> pushing $BRANCH_REF to $REMOTE"
git push "$REMOTE" "$BRANCH_REF"

echo "==> re-pointing local tag $VER at HEAD and pushing"
git tag -f "$VER"            # move local tag (and remote) to current HEAD
git push -f "$REMOTE" "$TAG_REF"

echo "==> triggered. newest run:"
sleep 8
gh run list --repo berkchy/cs16-meta-patcher --limit 1 \
  --json databaseId,event,status,conclusion --jq '.[0]'
