#!/bin/sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$project_root"
mode=${1:-web}
if [ "$#" -gt 0 ]; then shift; fi
runtime_root="$project_root/.runtime"
mkdir -p "$runtime_root"
verify_sha() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$1" "$2" | sha256sum -c - >/dev/null
  else
    printf '%s  %s\n' "$1" "$2" | shasum -a 256 -c - >/dev/null
  fi
}
download() {
  if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 "$1" -o "$2";
  else wget -O "$2" "$1"; fi
}
if [ "${PARLIAMENT_MANAGED_JAVA:-0}" != 1 ] && command -v java >/dev/null 2>&1 && command -v javac >/dev/null 2>&1; then
  java_major=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p')
else java_major=0; fi
case "${java_major:-0}" in 1.*) java_major=${java_major#1.} ;; esac
if [ "${java_major:-0}" -lt 17 ]; then
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64) platform=x64_linux; checksum=3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e ;;
    Linux:aarch64) platform=aarch64_linux; checksum=457b57af8f9c93ec39080bb8c764f559dc8c89a6da1a39d718a400b7890d3e41 ;;
    Darwin:x86_64) platform=x64_mac; checksum=c01975da12ed4235250ff891fe8bba73a9e73037d444b269c9d0922b5dbc8e0a ;;
    Darwin:arm64) platform=aarch64_mac; checksum=196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8 ;;
    *) echo 'Unsupported managed Java platform. Install a Java 17+ JDK.' >&2; exit 1 ;;
  esac
  jdk_root="$runtime_root/jdk-17.0.20.1+1"
  if [ ! -x "$jdk_root/bin/java" ]; then
    archive="$runtime_root/jdk-17.0.20.1+1.tar.gz"
    if [ ! -f "$archive" ] || ! verify_sha "$checksum" "$archive"; then
      echo 'Downloading pinned Java 17 (first launch only)...'
      download "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_${platform}_hotspot_17.0.20.1_1.tar.gz" "$archive.part"
      verify_sha "$checksum" "$archive.part"
      mv "$archive.part" "$archive"
    fi
    staging=$(mktemp -d "$runtime_root/java-extract.XXXXXX")
    tar -xzf "$archive" -C "$staging"
    extracted=$(find "$staging" -type f -path '*/bin/javac' | head -n 1)
    [ -n "$extracted" ] || { echo 'Downloaded archive contains no JDK.' >&2; exit 1; }
    mv "$(dirname "$(dirname "$extracted")")" "$jdk_root"
  fi
  JAVA_HOME="$jdk_root"; export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"; export PATH
fi
MAVEN_USER_HOME="$runtime_root/maven"; export MAVEN_USER_HOME
jar="$project_root/target/virtual-parliament.jar"
if [ ! -f "$jar" ] || [ -n "$(find src pom.xml -type f -newer "$jar" -print -quit)" ]; then
  echo 'Building Virtual Parliament...'
  sh ./mvnw -B "-Dmaven.repo.local=$project_root/.maven-cache" verify
fi
exec java -jar "$jar" "$mode" "$@"
