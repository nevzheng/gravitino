#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)
BUILD_DIR="$SCRIPT_DIR/build"
ICEBERG_VERSION=1.11.0
ICEBERG_ARTIFACT=iceberg-spark-runtime-3.5_2.12
SQLITE_VERSION=3.42.0.0
SQLITE_ARTIFACT=sqlite-jdbc
GRADLE_CACHE=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

grep -Fq "sqlite-jdbc = \"$SQLITE_VERSION\"" "$REPO_ROOT/gradle/libs.versions.toml" \
  || fail "SQLite JDBC $SQLITE_VERSION is not the repository-pinned version."

printf '[1/4] Building the current Gravitino server distribution...\n'
cd "$REPO_ROOT"
# The IRC Gradle copy tasks are additive, so remove old runtime jars before dependency changes are
# restaged. Otherwise a KMS jar from an earlier build can survive after its dependency is removed.
rm -rf \
  "$REPO_ROOT/iceberg/iceberg-rest-server/build/libs" \
  "$REPO_ROOT/distribution/gravitino-iceberg-rest-server/libs"
./gradlew compileDistribution compileIcebergRESTServer \
  -PskipWeb=true -x test --console=plain

printf '[2/4] Building the current Spark 3.5 connector runtime...\n'
./gradlew :spark-connector:spark-runtime-3.5:shadowJar -x test --console=plain

# Resolving the test runtime classpath downloads the matching Iceberg runtime without introducing
# another package manager or maintaining a second dependency version in the demo.
./gradlew :spark-connector:spark-3.5:dependencies \
  --configuration testRuntimeClasspath \
  --console=plain >/dev/null
# The repository pins sqlite-jdbc in gradle/libs.versions.toml. Resolve that existing dependency so
# the demo can stage the driver without adding it to the production IRC runtime dependencies.
./gradlew :iceberg:iceberg-rest-server:dependencies \
  --configuration testRuntimeClasspath \
  --console=plain >/dev/null

connector_jar=$(find "$REPO_ROOT/spark-connector/v3.5/spark-runtime/build/libs" \
  -maxdepth 1 -type f \
  -name 'gravitino-spark-connector-runtime-3.5_2.12-*.jar' \
  ! -name '*-empty.jar' -print -quit)
[[ -n "$connector_jar" ]] || fail "The shaded Gravitino Spark 3.5 connector was not produced."

iceberg_jar=$(find \
  "$GRADLE_CACHE/org.apache.iceberg/$ICEBERG_ARTIFACT/$ICEBERG_VERSION" \
  -type f -name "$ICEBERG_ARTIFACT-$ICEBERG_VERSION.jar" -print -quit 2>/dev/null || true)
[[ -n "$iceberg_jar" ]] || fail "Iceberg Spark runtime $ICEBERG_VERSION was not resolved."

sqlite_jar=$(find \
  "$GRADLE_CACHE/org.xerial/$SQLITE_ARTIFACT/$SQLITE_VERSION" \
  -type f -name "$SQLITE_ARTIFACT-$SQLITE_VERSION.jar" -print -quit 2>/dev/null || true)
[[ -n "$sqlite_jar" ]] || fail "SQLite JDBC driver $SQLITE_VERSION was not resolved."

printf '[3/4] Staging a small Docker build context...\n'
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gravitino" "$BUILD_DIR/iceberg-rest" "$BUILD_DIR/spark"
cp -R "$REPO_ROOT/distribution/package/." "$BUILD_DIR/gravitino/"
cp -R "$REPO_ROOT/distribution/gravitino-iceberg-rest-server/." "$BUILD_DIR/iceberg-rest/"
cp "$connector_jar" "$BUILD_DIR/spark/"
cp "$iceberg_jar" "$BUILD_DIR/spark/"
cp "$sqlite_jar" "$BUILD_DIR/iceberg-rest/libs/"

jar tf "$connector_jar" \
  | grep -q 'org/apache/gravitino/iceberg/kms/OpenBaoKeyManagementClient.class' \
  || fail "The Spark runtime does not contain the Iceberg KMS adapter."
jar tf "$connector_jar" \
  | grep -q 'org/apache/iceberg/rest/RESTCatalogWithEncryption.class' \
  || fail "The Spark runtime does not contain the REST encryption bridge."
jar tf "$BUILD_DIR/iceberg-rest/libs/$(basename "$sqlite_jar")" \
  | grep -q 'org/sqlite/JDBC.class' \
  || fail "The standalone IRC package does not contain the SQLite JDBC driver."

if find "$BUILD_DIR/gravitino" "$BUILD_DIR/iceberg-rest" \
    -type f -name 'gravitino-iceberg-kms-*.jar' -print -quit | grep -q .; then
  fail "KMS runtime code must be packaged only in Spark, not in Gravitino or IRC."
fi

printf '[4/4] Demo artifacts are ready in %s.\n' "$BUILD_DIR"
