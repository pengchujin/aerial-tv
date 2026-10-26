#!/usr/bin/env bash
# Deploy the website (site/) to Cloudflare, with copies of the latest release's APKs and of the
# install scripts, so people in China download them from aerial.qust.me rather than GitHub.
#
#   tools/deploy-site.sh
#
# Needs gh (signed in) and npx. The scripts are copied from this checkout: deploy from main.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=pengchujin/aerial-tv

git diff --quiet HEAD -- tools || echo "warning: tools/ has uncommitted changes; they will be published" >&2

rm -rf site/download site/tools site/install.sh
mkdir -p site/download site/tools
gh release download --repo "$REPO" --pattern 'aerial-*.apk' --dir site/download
gh release view --repo "$REPO" --json tagName -q .tagName > site/download/version.txt
cp tools/get.sh site/install.sh
cp tools/install.sh tools/restore.sh tools/tcast-push.py tools/report.sh site/tools/
echo "Publishing $(cat site/download/version.txt): $(ls site/download | tr '\n' ' ')"

# The tile pictures TVs download (IconLibrary). They are not ours to put in the public repo, so
# they come from local/ (handed over privately, like the signing key). Deploying without them
# would take them off the site.
ICONS=local/dangbei-icons/icons
[ -d "$ICONS" ] || { echo "error: $ICONS is missing; the icon library would be removed from the site" >&2; exit 1; }
python3 tools/icon-library.py "$ICONS" site/icons

npx wrangler@4 deploy
