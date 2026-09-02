#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Download a pinned actionlint release and lint .github/workflows.
# Writes ACTIONLINT_REPORT (default actionlint-report.txt) even on failure
# so CI can upload it as an artifact.

set -euo pipefail

ACTIONLINT_VERSION="${ACTIONLINT_VERSION:-1.7.12}"
ACTIONLINT_REPORT="${ACTIONLINT_REPORT:-actionlint-report.txt}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

os="$(uname -s)"
case "${os}" in
  Linux) os="linux" ;;
  Darwin) os="darwin" ;;
  *)
    echo "unsupported OS: ${os}" >&2
    exit 1
    ;;
esac

arch="$(uname -m)"
case "${arch}" in
  x86_64 | amd64) arch="amd64" ;;
  aarch64 | arm64) arch="arm64" ;;
  *)
    echo "unsupported architecture: ${arch}" >&2
    exit 1
    ;;
esac

# SHA256 from https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_checksums.txt
case "${os}_${arch}" in
  linux_amd64)
    expected_sha="8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"
    ;;
  linux_arm64)
    expected_sha="325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6"
    ;;
  darwin_amd64)
    expected_sha="5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644"
    ;;
  darwin_arm64)
    expected_sha="aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f"
    ;;
  *)
    echo "no pinned checksum for ${os}_${arch}" >&2
    exit 1
    ;;
esac

if ! command -v shellcheck >/dev/null 2>&1; then
  echo "shellcheck is required on PATH (ubuntu-latest images include it)" >&2
  exit 1
fi

archive="actionlint_${ACTIONLINT_VERSION}_${os}_${arch}.tar.gz"
url="https://github.com/rhysd/actionlint/releases/download/v${ACTIONLINT_VERSION}/${archive}"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

checksum() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
  else
    shasum -a 256 "${file}" | awk '{print $1}'
  fi
}

curl --retry 5 --retry-delay 1 -sSLo "${work}/${archive}" "${url}"
got_sha="$(checksum "${work}/${archive}")"
if [[ "${got_sha}" != "${expected_sha}" ]]; then
  echo "actionlint checksum mismatch for ${archive}" >&2
  echo "expected ${expected_sha}" >&2
  echo "got      ${got_sha}" >&2
  exit 1
fi

tar -xzf "${work}/${archive}" -C "${work}" actionlint

set +e
{
  echo "actionlint ${ACTIONLINT_VERSION} ${os}_${arch}"
  echo "generated $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
} > "${ACTIONLINT_REPORT}"
"${work}/actionlint" -color -shellcheck="$(command -v shellcheck)" | tee -a "${ACTIONLINT_REPORT}"
status="${PIPESTATUS[0]}"
if [[ "${status}" -eq 0 ]]; then
  echo "actionlint: clean" | tee -a "${ACTIONLINT_REPORT}"
fi
set -e
exit "${status}"
