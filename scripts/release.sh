#!/usr/bin/env bash
# scripts/release.sh — Script maestro de compilación, versionado y publicación de APK en GitHub Releases (latest)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

BUMP_MODE="none" # none | patch | minor | major | set
CUSTOM_VERSION=""
PUBLISH_GITHUB=false
RELEASE_DESC=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --patch|--bump) BUMP_MODE="patch"; shift ;;
    --minor) BUMP_MODE="minor"; shift ;;
    --major) BUMP_MODE="major"; shift ;;
    --set) BUMP_MODE="set"; CUSTOM_VERSION="${2:-}"; shift 2 ;;
    --github|--publish) PUBLISH_GITHUB=true; shift ;;
    -m|--desc) RELEASE_DESC="${2:-}"; shift 2 ;;
    *) shift ;;
  esac
done

CURRENT_VER="$(grep -E 'versionName = "' android/app/build.gradle.kts | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
CURRENT_CODE="$(grep -E 'versionCode = ' android/app/build.gradle.kts | head -n1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')"

IFS='.' read -r MAJ MIN PAT <<< "${CURRENT_VER}"
NEXT_VER="${CURRENT_VER}"
NEXT_CODE="${CURRENT_CODE}"

case "${BUMP_MODE}" in
  patch)
    NEXT_VER="${MAJ}.${MIN}.$((PAT + 1))"
    NEXT_CODE="$((CURRENT_CODE + 1))"
    ;;
  minor)
    NEXT_VER="${MAJ}.$((MIN + 1)).0"
    NEXT_CODE="$((CURRENT_CODE + 1))"
    ;;
  major)
    NEXT_VER="$((MAJ + 1)).0.0"
    NEXT_CODE="$((CURRENT_CODE + 1))"
    ;;
  set)
    NEXT_VER="${CUSTOM_VERSION}"
    NEXT_CODE="$((CURRENT_CODE + 1))"
    ;;
esac

if [[ "${NEXT_VER}" != "${CURRENT_VER}" ]]; then
  echo "==> Actualizando versión: ${CURRENT_VER} (code ${CURRENT_CODE}) -> ${NEXT_VER} (code ${NEXT_CODE})"
  sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"${NEXT_VER}\"/" android/app/build.gradle.kts
  sed -i -E "s/versionCode = [0-9]+/versionCode = ${NEXT_CODE}/" android/app/build.gradle.kts
  sed -i -E "s/^version = \"[^\"]+\"/version = \"${NEXT_VER}\"/" rust/Cargo.toml
fi

echo "==> Compilando APK StandBy v${NEXT_VER}..."
"${SCRIPT_DIR}/build-apk.sh"

APK_VER="dist/StandBy-v${NEXT_VER}-arm64-v8a.apk"
APK_LATEST="dist/StandBy-latest-arm64-v8a.apk"
cp -f dist/standby-debug.apk "${APK_VER}"
cp -f dist/standby-debug.apk "${APK_LATEST}"
(cd dist && sha256sum "$(basename "${APK_VER}")" > "$(basename "${APK_VER}").sha256")
(cd dist && sha256sum "$(basename "${APK_LATEST}")" > "$(basename "${APK_LATEST}").sha256")

echo "==> Artefactos listos en dist/:"
ls -lh "${APK_VER}" "${APK_LATEST}"

if [[ "${PUBLISH_GITHUB}" == "true" ]]; then
  TAG="v${NEXT_VER}"
  MSG="${RELEASE_DESC:-Release ${TAG}}"
  git add -A
  git commit -m "chore(release): ${TAG} — ${MSG}" || true
  git tag -f "${TAG}" -m "${MSG}"
  git push -u origin main --tags

  if command -v gh >/dev/null 2>&1; then
    echo "==> Publicando APK directamente en GitHub Release ${TAG} (marked as latest)..."
    gh release create "${TAG}" \
      "${APK_VER}" \
      "${APK_VER}.sha256" \
      "${APK_LATEST}" \
      "${APK_LATEST}.sha256" \
      --title "StandBy ${TAG}" \
      --notes "${MSG}" \
      --latest || \
    gh release upload "${TAG}" \
      "${APK_VER}" \
      "${APK_VER}.sha256" \
      "${APK_LATEST}" \
      "${APK_LATEST}.sha256" \
      --clobber
  fi
fi
