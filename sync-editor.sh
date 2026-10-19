#!/bin/sh
# Bundles the web app's note editor (Markdown + TeX) into the app: ./sync-editor.sh
# Builds ../web's embeddable editor and copies it to app/src/main/assets/editor/ (committed, so
# building the app doesn't need Node). Run it again when the editor changes.
set -eu
cd "$(dirname "$0")"
web=../web
[ -d "$web/node_modules" ] || (cd "$web" && npm ci)
(cd "$web" && npm run -s build:embed)
rm -rf app/src/main/assets/editor
mkdir -p app/src/main/assets
cp -R "$web/dist/embed" app/src/main/assets/editor
echo "Editor bundled: $(du -sh app/src/main/assets/editor | cut -f1)"
