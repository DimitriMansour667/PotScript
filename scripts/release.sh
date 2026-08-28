#!/usr/bin/env bash
# Builds the mod and publishes a GitHub release for it.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v gh >/dev/null 2>&1; then
	echo "error: gh CLI is required (https://cli.github.com)" >&2
	exit 1
fi

read -rp "Release tag (e.g. v0.0.2): " TAG
if [[ -z "$TAG" ]]; then
	echo "error: tag cannot be empty" >&2
	exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
	echo "error: tag '$TAG' already exists" >&2
	exit 1
fi

echo "Release description (end with an empty line):"
DESC_LINES=()
while IFS= read -r line; do
	[[ -z "$line" ]] && break
	DESC_LINES+=("$line")
done
DESC=$(printf '%s\n' "${DESC_LINES[@]}")

echo "Building..."
./gradlew clean build

JAR=$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' ! -name '*-dev.jar' | head -n1)
if [[ -z "$JAR" ]]; then
	echo "error: could not find built jar in build/libs" >&2
	exit 1
fi
echo "Built: $JAR"

git tag "$TAG"
git push origin "$TAG"

gh release create "$TAG" "$JAR" --title "$TAG" --notes "$DESC"

echo "Released $TAG"
