#!/bin/bash

# Builds, signs and releases Passdroid++. Run by the Publish workflow, see
# .github/workflows/publish.yml, and can be run locally as well, see help().

# Default values
COMMAND="build"
BRANCH=""
COMMIT=""
MESSAGE=""
VERSION="auto"
VERSION_CODE="auto"
BUMP_TYPE="patch"
PREVIEW="auto"
UNATTENDED="false"
BASE_VER="3"

BUILD_TOOLS_VERSION="36.0.0"
UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"

# Allowed values
_command_values=("build" "package" "release" "version")
_bump_type_values=("major" "minor" "patch")
_preview_values=("auto" "true" "false")
_unattended_values=("true" "false")

# Semantic Versioning 2.0.0, see https://semver.org. app/build.gradle checks
# the version name with the same expression.
_semver_regex='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(\.(0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*)?(\+[0-9a-zA-Z-]+(\.[0-9a-zA-Z-]+)*)?$'

# Colors
Color_Off='\033[0m'
Color_Green='\033[0;32m'
Color_Yellow='\033[0;33m'

function main() {
  read_args "$@"

  case "$COMMAND" in
    "build") build ;;
    "package") package ;;
    "release") release ;;
    "version") version_cmd ;;
  esac
}

function build() {
  ./gradlew testDebugUnitTest lintDebug assembleRelease \
    -PversionCode="$VERSION_CODE" -PversionName="$VERSION"

  if [[ ! $? -eq 0 ]]; then exit 1; fi # exit if build failed or canceled

  echo
  echo -e "${Color_Green}Build OK${Color_Off}"
}

function package() {
  local build_tools="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools/$BUILD_TOOLS_VERSION"
  local apksigner="$build_tools/apksigner"
  local aapt2="$build_tools/aapt2"
  local release_path="release"
  local apk_name="passdroid-${VERSION}.apk"

  if [ ! -x "$apksigner" ]; then
    echo "Error. apksigner not found: $apksigner"
    echo "Set ANDROID_HOME to the Android SDK, with build tools $BUILD_TOOLS_VERSION installed."
    exit 1
  fi
  if [ -z "$KEYSTORE_FILE" ] || [ ! -f "$KEYSTORE_FILE" ]; then
    echo "Error. Set KEYSTORE_FILE to the keystore with the release key."
    exit 1
  fi
  if [ ! -f "$UNSIGNED_APK" ]; then
    echo "Error. $UNSIGNED_APK not found, run the build command first."
    exit 1
  fi

  # The APK must be the one built for this version
  local badging
  badging=$("$aapt2" dump badging "$UNSIGNED_APK" | head -1)
  if [[ "$badging" != *"versionCode='$VERSION_CODE' versionName='$VERSION'"* ]]; then
    echo "Error. $UNSIGNED_APK was not built for version $VERSION ($VERSION_CODE):"
    echo "$badging"
    exit 1
  fi

  # apksigner asks for the passwords if they are not in the environment.
  # It uses the only key of the keystore if no alias is given.
  local sign_args=()
  if [ -n "$KEYSTORE_PASSWORD" ]; then
    export KEYSTORE_PASSWORD
    export KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
    sign_args+=(--ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD)
  fi
  if [ -n "$KEY_ALIAS" ]; then
    sign_args+=(--ks-key-alias "$KEY_ALIAS")
  fi

  rm -rf "$release_path"
  mkdir -p "$release_path"

  "$apksigner" sign \
    --ks "$KEYSTORE_FILE" \
    "${sign_args[@]}" \
    --v4-signing-enabled false \
    --out "$release_path/$apk_name" \
    "$UNSIGNED_APK"
  if [[ ! $? -eq 0 ]]; then exit 1; fi

  "$apksigner" verify --print-certs "$release_path/$apk_name"
  if [[ ! $? -eq 0 ]]; then exit 1; fi

  cd "$release_path" && sha256sum "$apk_name" > checksums.txt && cd - > /dev/null
  cat "$release_path/checksums.txt"

  echo -e "${Color_Green}Package OK${Color_Off}"
}

function release() {
  if [ ! $UNATTENDED = "true" ]
  then
    read -p "Do you want to continue to git tag, push...? (y/n) " yn
    if [ ! $yn = "y" ]; then exit; fi
  fi

  local tag="v$VERSION"
  local tagged
  tagged=$(git rev-parse -q --verify "refs/tags/$tag^{commit}")
  # The tag can exist on this commit when a failed release is run again
  if [ -z "$tagged" ]; then
    git tag "$tag"
    if [[ ! $? -eq 0 ]]; then exit 1; fi
  elif [ "$tagged" != "$COMMIT" ]; then
    echo "Error. Tag $tag already exists on another commit: $tagged"
    exit 1
  fi

  git push origin "$tag"
  if [[ ! $? -eq 0 ]]; then exit 1; fi

  local url="$(git remote get-url origin | sed -E 's/\.git$//')/releases/tag/$tag"
  echo -e "${Color_Yellow}Version $VERSION released${Color_Off}"
  echo -e "URL: $url"
  echo -e "${Color_Green}All done${Color_Off}"
}

function version_cmd() {
  echo "$VERSION"
}

function read_args() {
  while [ "${1:-}" != "" ]; do
    case "$1" in
      "-h" | "--help")
        help
        exit 0
        ;;
      "-v" | "--version")
        shift
        VERSION="$1"
        ;;
      "-c" | "--version_code")
        shift
        VERSION_CODE="$1"
        ;;
      "-b" | "--bump_type")
        shift
        if is_in_array "$1" "${_bump_type_values[@]}"; then
            BUMP_TYPE="$1"
        else
            invalid_argument "$1" "${_bump_type_values[*]}"
            exit 1
        fi
        ;;
      "-p" | "--preview")
        shift
        if is_in_array "$1" "${_preview_values[@]}"; then
            PREVIEW="$1"
        else
            invalid_argument "$1" "${_preview_values[*]}"
            exit 1
        fi
        ;;
      "-u" | "--unattended")
        shift
        if is_in_array "$1" "${_unattended_values[@]}"; then
            UNATTENDED="$1"
        else
            invalid_argument "$1" "${_unattended_values[*]}"
            exit 1
        fi
        ;;
      "-M" | "--base_ver")
        shift
        BASE_VER="$1"
        ;;
      *)
        if is_in_array "$1" "${_command_values[@]}"; then
            COMMAND="$1"
        else
            invalid_argument "$1" "${_command_values[*]}"
            exit 1
        fi
        ;;
    esac
    shift
  done

  if [ -n "$GITHUB_REF_NAME" ]; then
    BRANCH="$GITHUB_REF_NAME"
  else
    BRANCH=$(git branch --show-current)
  fi

  COMMIT=$(git log -n 1 --format="%H")
  MESSAGE=$(git log -n 1 --format="%s")

  if [ "$PREVIEW" = "auto" ]; then
    if [ "$BRANCH" != "master" ]; then
      PREVIEW="true"
    else
      PREVIEW="false"
    fi
  fi

  if [ -z "$VERSION" ] || [ "$VERSION" = "auto" ]; then
    local latest_stable=$(git tag --sort=-version:refname \
      | grep -E "^v${BASE_VER}\.[0-9]+\.[0-9]+$" | head -1 | sed 's/^v//')
    local latest_preview=$(git tag --sort=-version:refname \
      | grep -E "^v${BASE_VER}\.[0-9]+\.[0-9]+-preview\.[0-9]+$" | head -1 | sed 's/^v//')

    if [ "$COMMAND" != "version" ]; then
      echo "BASE_VER:     $BASE_VER"
      echo "STABLE_VER:   $latest_stable"
      echo "PREVIEW_VER:  $latest_preview"
    fi

    if [[ -z "$latest_stable" && -z "$latest_preview" ]]; then
      # No tags exist yet for this major stream — start fresh
      if [[ "$PREVIEW" == "true" ]]; then
        VERSION="${BASE_VER}.0.0-preview.1"
      else
        VERSION="${BASE_VER}.0.0"
      fi
    else
      # Determine current_version: whichever is truly latest overall within this stream
      local current_version="$latest_stable"
      if [[ -n "$latest_preview" ]]; then
        local preview_main="${latest_preview%-preview.*}"
        version_compare "$preview_main" "${latest_stable:-0.0.0}"
        if [[ $? -eq 1 ]]; then
          current_version="$latest_preview"  # preview cycle is ahead of stable
        fi
      fi
      VERSION=$(bump_version "$current_version" "$BUMP_TYPE" "$PREVIEW")
    fi
  else
    BUMP_TYPE="-"
    PREVIEW="-"
  fi

  if [[ ! "$VERSION" =~ $_semver_regex ]]; then
    echo "Error. Invalid version: $VERSION"
    echo "It must be a semantic version, e.g. 3.1.0 or 3.1.0-preview.1"
    exit 1
  fi

  # The release command accepts an existing tag on this commit, see release()
  if [ "$COMMAND" != "release" ] && git rev-parse -q --verify "refs/tags/v$VERSION" > /dev/null; then
    echo "Error. Version $VERSION is already released: tag v$VERSION exists"
    exit 1
  fi

  if [ "$COMMAND" = "build" ] || [ "$COMMAND" = "package" ]; then
    if [ -z "$VERSION_CODE" ] || [ "$VERSION_CODE" = "auto" ]; then
      # The number of the workflow run
      if [ -z "$GITHUB_RUN_NUMBER" ]; then
        echo "Error. Give the version code with -c, it is only set automatically in GitHub Actions"
        exit 1
      fi
      VERSION_CODE="$GITHUB_RUN_NUMBER"
    fi
    if [[ ! "$VERSION_CODE" =~ ^[1-9][0-9]{0,9}$ ]]; then
      echo "Error. Invalid version code: $VERSION_CODE"
      exit 1
    fi
  else
    VERSION_CODE="-"
  fi

  if [ "$COMMAND" != "version" ]; then
    echo "COMMAND:      $COMMAND"
    echo "VERSION:      $VERSION"
    echo "VERSION_CODE: $VERSION_CODE"
    echo "BUMP_TYPE:    $BUMP_TYPE"
    echo "PREVIEW:      $PREVIEW"
    echo "BASE_VER:     $BASE_VER"
    echo "BRANCH:       $BRANCH"
    echo "COMMIT:       $COMMIT"
    echo "MESSAGE:      $MESSAGE"
  fi

  if [ "$COMMAND" = "version" ]; then
    : # no countdown for version query
  elif [ $UNATTENDED = "true" ]
  then
    for i in {3..0..1}; do echo -en "\rContinue in $i" && if [ "$i" -gt "0" ]; then sleep 1; fi; done
    echo -en "\r             "
    echo
  else
    read -p "Press any key to continue..." -n1 -s; echo
    echo
  fi
}

function help() {
  echo "Usage:"
  echo "  ./publish.sh <command> [options]"
  echo ""
  echo "Commands:"
  echo "  build                              Step 1: run the tests and lint, build the release APK"
  echo "  package                            Step 2: sign the APK, write the checksum to release/"
  echo "  release                            Step 3: execute git tag, push..."
  echo "  version                            Print computed version and exit"
  echo ""
  echo "Options:                             Allowed values:"
  echo "  -v, --version <VERSION>            auto*, x.y.z, x.y.z-preview.N"
  echo "  -c, --version_code <VERSION_CODE>  auto* (number of the GitHub Actions run), 1, 2, 3, ..."
  echo "  -b, --bump_type <BUMP_TYPE>        major, minor, patch*"
  echo "  -p, --preview <PREVIEW>            auto*, true, false"
  echo "  -M, --base_ver <BASE_VER>          3*, 4, 5, ..."
  echo "  -u, --unattended <UNATTENDED>      true, false*"
  echo "  -h, --help"
  echo "                                     * Default value"
  echo ""
  echo "The package command signs with the keystore in KEYSTORE_FILE. The"
  echo "passwords are read from KEYSTORE_PASSWORD and KEY_PASSWORD, or asked"
  echo "for, and KEY_ALIAS selects the key if the keystore has several."
  echo ""
  echo "Examples:"
  echo "  ./publish.sh build -c 42"
  echo "  ./publish.sh build -v 3.1.0 -c 42"
  echo "  KEYSTORE_FILE=~/passdroid-release.jks ./publish.sh package -v 3.1.0 -c 42"
  echo "  ./publish.sh version"
  echo "  ./publish.sh version -b minor"
  echo "  ./publish.sh version -M 4"
}

function is_in_array() {
  local value="$1"
  shift
  for element in "$@"; do
    if [[ "$element" == "$value" ]]; then
      return 0
    fi
  done
  return 1
}

function invalid_argument() {
  echo "Error. Invalid argument value: $1"
  echo "Allowed values: ${*:2}"
  echo
  help
}

function version_compare() {
  # split the versions into main version and prerelease parts
  IFS='-' read -r main1 pre1 <<< "$1"
  IFS='-' read -r main2 pre2 <<< "$2"

  # compare prerelease versions if main versions are the same
  if [[ $main1 == $main2 ]]; then
    if [[ -z $pre1 && -n $pre2 ]]; then
      return 1 # no pre1: 1.2.3 > 1.2.3-preview.X
    elif [[ -n $pre1 && -z $pre2 ]]; then
      return 2 # no pre2: 1.2.3-preview.X < 1.2.3
    elif [[ -n $pre1 && -n $pre2 ]]; then
      if [[ $pre1 == $pre2 ]]; then
        return 0 # preview.1 = preview.1
      else
        # extract numeric parts for comparison
        p1="${pre1/'preview.'/}"
        p2="${pre2/'preview.'/}"
        if (($p1 > $p2)); then
          return 1 # preview.10 > preview.1
        else
          return 2 # preview.1 < preview.10
        fi
      fi
    else
      return 0 # 1.2.3 = 1.2.3
    fi
  fi

  # compare the main versions numerically
  # src: https://stackoverflow.com/a/4025065; vercomp
  local IFS=.
  local i ver1=($main1) ver2=($main2)
  # fill empty fields in ver1 with zeros
  for ((i=${#ver1[@]}; i<${#ver2[@]}; i++))
  do
    ver1[i]=0
  done
  for ((i=0; i<${#ver1[@]}; i++))
  do
    if ((10#${ver1[i]:=0} > 10#${ver2[i]:=0}))
    then
      return 1
    fi
    if ((10#${ver1[i]} < 10#${ver2[i]}))
    then
      return 2
    fi
  done
  return 0
}

function bump_version() {
  local current_version="$1"
  local bump_type="$2"
  local preview="$3"

  IFS='-' read -ra chunks <<< $(echo $current_version | sed 's/-preview./-/')
  local current_main_version="${chunks[0]}"
  local current_prev_version="${chunks[1]:-0}"
  local next_version=""

  if [[ "$preview" == "false" ]]; then
    if [[ "$current_prev_version" -eq 0 ]]; then
      # Regular bump
      next_version=$(bump_version_main "$current_main_version" "$bump_type")
    else
      # Unpreview
      next_version="$current_main_version"
    fi
  else
    if [[ "$current_prev_version" -eq 0 ]]; then
      # First preview
      next_version=$(bump_version_main "$current_main_version" "$bump_type")
      next_version+="-preview.1"
    else
      # Bumping existing preview
      next_version="$current_main_version"
      next_version+="-preview.$((current_prev_version + 1))"
    fi
  fi

  echo "$next_version"
}

function bump_version_main() {
  local current_version="$1"
  local bump_type="$2"

  # Split the version into major, minor, and patch
  IFS='.' read -ra chunks <<< "$current_version"
  local major="${chunks[0]}"
  local minor="${chunks[1]:-0}"
  local patch="${chunks[2]:-0}"

  # Increment version based on bump type
  case "$bump_type" in
    major)
      ((major++))
      minor=0
      patch=0
      ;;
    minor)
      ((minor++))
      patch=0
      ;;
    patch)
      ((patch++))
      ;;
    *)
      echo "Invalid bump type: $bump_type"
      return 1
      ;;
  esac

  echo "${major}.${minor}.${patch}"
}

main "$@"
