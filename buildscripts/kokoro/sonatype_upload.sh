#!/bin/bash
set -veux -o pipefail

if [[ -f /VERSION ]]; then
  cat /VERSION
fi

readonly GRPC_JAVA_DIR=$(cd $(dirname $0)/../.. && pwd)

echo "Verifying that all artifacts are here..."
find $KOKORO_GFILE_DIR

# verify that files from all 3 grouped jobs are present.
# platform independent artifacts, from linux job:
if [[ $(find $KOKORO_GFILE_DIR -type f -iname "grpc-core-*.jar" | wc -l) == "0" ]]; then
  exit 1
fi
# from linux job:
if [[ $(find $KOKORO_GFILE_DIR -type f -iname "protoc-gen-grpc-java-*-linux-x86_64.exe" | wc -l) == "0" ]]; then
  exit 1
fi
if [[ $(find $KOKORO_GFILE_DIR -type f -iname "protoc-gen-grpc-java-*-linux-x86_32.exe" | wc -l) == "0" ]]; then
  exit 1
fi
# from macos job:
if [[ $(find $KOKORO_GFILE_DIR -type f -iname "protoc-gen-grpc-java-*-osx-x86_64.exe" | wc -l) == "0" ]]; then
  exit 1
fi
# from windows job:
if [[ $(find $KOKORO_GFILE_DIR -type f -iname "protoc-gen-grpc-java-*-windows-x86_64.exe" | wc -l) == "0" ]]; then
  exit 1
fi
if [[ $(find $KOKORO_GFILE_DIR -type f -iname "protoc-gen-grpc-java-*-windows-x86_32.exe" | wc -l) == "0" ]]; then
  exit 1
fi


mkdir -p ~/.config/
gsutil cp gs://grpc-testing-secrets/sonatype_credentials/sonatype-upload ~/.config/sonatype-upload

mkdir -p ~/java_signing/
gsutil cp -r gs://grpc-testing-secrets/java_signing/ ~/
gpg --batch  --import ~/java_signing/grpc-java-team-sonatype.asc

# gpg commands changed between v1 and v2 are different.
gpg --version

# Tested manually using these two versions.
# This is the version found on kokoro.
if [[ $(gpg --version | grep 'gpg (GnuPG) 1.4.16') ]]; then
  echo "Detected GPG version v1.x.x, running command that was verified on 1.4.16"
  find "$KOKORO_GFILE_DIR" -type f -exec \
    bash -c \
    'set -x; cat ~/java_signing/passphrase | gpg --batch --passphrase-fd 0 --detach-sign -o {}.asc {}' \;
fi

set +x
# This is the version found on my workstation.
if [[ $(gpg --version | grep 'gpg (GnuPG) 2.2.2') ]]; then
  echo "Detected GPG version v2.x.x, running command that was verified on 2.2.2"
  find "$KOKORO_GFILE_DIR" -type f -exec \
    gpg --batch --passphrase $(cat ~/java_signing/passphrase) --pinentry-mode loopback \
    --detach-sign -o {}.asc {} \;
fi
set -x

STAGING_REPO=a93898609ef848
find $KOKORO_GFILE_DIR -name 'mvn-artifacts' -type d -exec \
  $GRPC_JAVA_DIR/buildscripts/sonatype-upload-util.sh \
  $STAGING_REPO {} \;
