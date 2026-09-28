#!/usr/bin/env bash
#
# push-to-github.sh
#
# Helper script: creates an empty GitHub repo on the user's account and pushes
# the local Auto Clicker project to it. Requires:
#   • `gh` CLI installed (https://cli.github.com) and authenticated via
#     `gh auth login`
#     -- OR --
#   • A pre-existing empty GitHub repository plus a HTTPS+PAT or SSH remote
#     configured below.
#
# Usage:
#   ./push-to-github.sh                       # interactive
#   REPO_NAME=android-autoclicker ./push-to-github.sh
#   REPO_NAME=android-autoclicker PRIVATE=0 ./push-to-github.sh
#
# If `gh` is not installed the script will print instructions on how to push
# manually using plain git + a personal access token.

set -e

REPO_NAME="${REPO_NAME:-android-autoclicker}"
PRIVATE="${PRIVATE:-1}"   # 1 = private repo, 0 = public
DESCRIPTION="Android auto clicker written in Kotlin (AccessibilityService + floating control)"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$PROJECT_DIR"

# Make sure we are inside a git repo with at least one commit.
if ! git rev-parse --verify HEAD >/dev/null 2>&1; then
  echo "No git commit found. Running initial commit..."
  git add -A
  git commit -m "Initial commit: Android Auto Clicker app in Kotlin"
fi

# Make sure `main` is the current branch.
if [ "$(git symbolic-ref --short HEAD)" != "main" ]; then
  git branch -M main || true
fi

# ---- Try the `gh` CLI first ------------------------------------------------
if command -v gh >/dev/null 2>&1; then
  echo "Using gh CLI..."
  if ! gh auth status >/dev/null 2>&1; then
    echo "Not logged in to gh. Run: gh auth login"
    exit 1
  fi
  echo "Creating repository '$REPO_NAME' (private=$PRIVATE)..."
  if [ "$PRIVATE" = "1" ]; then
    gh repo create "$REPO_NAME" --private --source=. --description="$DESCRIPTION" --push
  else
    gh repo create "$REPO_NAME" --public  --source=. --description="$DESCRIPTION" --push
  fi
  echo "✅ Done. View your repo at: https://github.com/$(gh api user --jq .login)/$REPO_NAME"
  echo "Watch the Actions build at: https://github.com/$(gh api user --jq .login)/$REPO_NAME/actions"
  echo "Once the build is green, the APK will be at: https://github.com/$(gh api user --jq .login)/$REPO_NAME/releases/latest"
  exit 0
fi

# ---- Fall back to plain git + a PAT ----------------------------------------
echo "⚠️  'gh' CLI not installed."
echo
echo "Manual push instructions:"
echo "  1) Create an empty repository at https://github.com/new"
echo "     Name: $REPO_NAME"
echo "     Do NOT initialize with README/license/gitignore (the project already has those)."
echo
echo "  2) Get a Personal Access Token with the 'repo' scope from:"
echo "     https://github.com/settings/tokens"
echo
echo "  3) Push the project (replace YOUR_USER and YOUR_TOKEN):"
echo
echo "     git remote add origin https://YOUR_USER:YOUR_TOKEN@github.com/YOUR_USER/$REPO_NAME.git"
echo "     git branch -M main"
echo "     git push -u origin main"
echo
echo "  4) Watch the build at: https://github.com/YOUR_USER/$REPO_NAME/actions"
echo "  5) Download the APK from: https://github.com/YOUR_USER/$REPO_NAME/releases/latest"
