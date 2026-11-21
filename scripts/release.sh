#!/usr/bin/env bash
# Release a version: date its changelog section, commit, tag v<version> and push.
# The tag starts .github/workflows/release.yml, which builds, tests and publishes the GitHub Release.
#
#   scripts/release.sh 0.1.0
#
# Checks first: clean working tree on main, mod_version in gradle.properties equals <version>,
# CHANGELOG.md has a "## <version>" section, and the tag does not exist yet.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=${1:?usage: scripts/release.sh <version>}
TAG="v$VERSION"

[ "$(git branch --show-current)" = main ] || { echo "not on main"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree is not clean"; exit 1; }
grep -q "^mod_version=$VERSION\$" gradle.properties || { echo "gradle.properties mod_version is not $VERSION"; exit 1; }
python scripts/changelog_section.py "$VERSION" > /dev/null
git fetch --tags --quiet
! git rev-parse -q --verify "refs/tags/$TAG" > /dev/null || { echo "tag $TAG already exists"; exit 1; }

DATE=$(date +%Y-%m-%d)
# "## 0.1.0 - unreleased (first public release)" -> "## 0.1.0 - 2026-11-21 (first public release)"
python - "$VERSION" "$DATE" <<'EOF'
import re, sys
from pathlib import Path
version, date = sys.argv[1], sys.argv[2]
f = Path("CHANGELOG.md")
text = f.read_text(encoding="utf-8")
new, n = re.subn(rf"^## {re.escape(version)} - unreleased", f"## {version} - {date}", text, count=1, flags=re.M)
f.write_text(new, encoding="utf-8")
print(f"changelog: {version} dated {date}" if n else f"changelog: {version} already dated")
EOF

git add CHANGELOG.md
git diff --cached --quiet || git commit -q -m "Release $VERSION"
git tag -a "$TAG" -m "Chunk Express $VERSION"
git push --quiet origin main "$TAG"
echo "pushed $TAG; the release workflow builds and publishes it"
