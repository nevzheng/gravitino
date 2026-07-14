/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg.integration.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.io.FileUtils;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.IcebergBuild;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotChanges;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.spark.SparkConf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/** Executes the day-one Iceberg encryption compatibility audit tracked by DAT-152. */
@TestInstance(Lifecycle.PER_CLASS)
public class IcebergEncryptionCompatibilityAuditIT extends IcebergRESTServiceBaseIT {

  private static final String NAMESPACE = "dat152_audit";
  private static final String ENCRYPTED_TABLE = "encrypted_v3";
  private static final String V2_TABLE = "encrypted_v2";
  private static final String TRANSITION_TABLE = "transition_v3";
  private static final String KEY_ALIAS = "audit-master-key";
  private static final String KEYSTORE_PASSWORD = "audit-password";
  private static final String FIRST_MARKER = "DAT152_FIRST_KNOWN_PLAINTEXT_7c91a2";
  private static final String SECOND_MARKER = "DAT152_SECOND_KNOWN_PLAINTEXT_4e83bd";
  private static final String BASELINE_MARKER = "DAT152_BASELINE_PLAINTEXT_b18f30";
  private static final String TRANSITION_MARKER = "DAT152_TRANSITION_PLAINTEXT_c442d1";
  private static final String PARQUET_COMPRESSION_PROPERTY = "write.parquet.compression-codec";

  private final ObjectMapper objectMapper =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private Path warehouse;
  private Path keyStorePath;
  private Path artifactDirectory;
  private CapturingHttpProxy proxy;

  @Override
  void initEnv() {
    try {
      warehouse = Files.createTempDirectory("dat152-iceberg-warehouse-");
      keyStorePath = warehouse.resolve("audit.jceks");
      artifactDirectory =
          Path.of(System.getProperty("audit.output.dir", "build/reports/iceberg-encryption-audit"))
              .toAbsolutePath();
      Files.createDirectories(artifactDirectory);
      createKeyStore(keyStorePath);
      KeystoreKeyManagementClient.resetInitializationCount();
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("Unable to initialize DAT-152 audit environment", e);
    }
  }

  @Override
  Map<String, String> getCatalogConfig() {
    Map<String, String> config = new LinkedHashMap<>();
    config.put(
        IcebergConfig.ICEBERG_CONFIG_PREFIX + IcebergConfig.CATALOG_BACKEND.getKey(),
        IcebergCatalogBackend.MEMORY.toString().toLowerCase());
    config.put(
        IcebergConfig.ICEBERG_CONFIG_PREFIX + IcebergConfig.CATALOG_WAREHOUSE.getKey(),
        warehouse.toString());
    return config;
  }

  /** Configures Spark to send every Iceberg REST request through the evidence-capture proxy. */
  @Override
  protected void customizeSparkConf(SparkConf sparkConf) {
    try {
      proxy = CapturingHttpProxy.start(getServerPort());
    } catch (IOException e) {
      throw new IllegalStateException("Unable to start audit request capture", e);
    }

    sparkConf
        .set("spark.sql.catalog.rest.uri", proxy.catalogUri())
        .set(
            "spark.sql.catalog.rest.encryption.kms-impl",
            KeystoreKeyManagementClient.class.getName())
        .set(
            "spark.sql.catalog.rest." + KeystoreKeyManagementClient.KEYSTORE_PATH_PROPERTY,
            keyStorePath.toString())
        .set(
            "spark.sql.catalog.rest." + KeystoreKeyManagementClient.KEYSTORE_PASSWORD_PROPERTY,
            KEYSTORE_PASSWORD);
  }

  /** Stops the capture proxy and removes the isolated warehouse after artifacts are written. */
  @Override
  protected void afterIcebergRESTServerStopped() {
    if (proxy != null) {
      proxy.close();
    }
    if (warehouse != null) {
      try {
        FileUtils.deleteDirectory(warehouse.toFile());
      } catch (IOException e) {
        LOG.warn("Unable to remove DAT-152 audit warehouse {}", warehouse, e);
      }
    }
  }

  @Test
  void executeDayOneCompatibilityAudit() throws Exception {
    String namespace = "rest." + NAMESPACE;
    String encryptedTableName = namespace + "." + ENCRYPTED_TABLE;
    String transitionTableName = namespace + "." + TRANSITION_TABLE;

    String configResponse = getJson("v1/config");
    sql("CREATE NAMESPACE IF NOT EXISTS %s", namespace);

    sql(
        "CREATE TABLE %s (id BIGINT, marker STRING) USING iceberg "
            + "TBLPROPERTIES ('format-version'='3', 'encryption.key-id'='%s', "
            + "'%s'='uncompressed')",
        encryptedTableName, KEY_ALIAS, PARQUET_COMPRESSION_PROPERTY);
    Table encryptedTable = loadTable(encryptedTableName);
    TableMetadata metadataBeforeWrite = metadata(encryptedTable);
    String loadBeforeWrite = loadTableResponse(ENCRYPTED_TABLE);

    sql("INSERT INTO %s VALUES (1, '%s')", encryptedTableName, FIRST_MARKER);
    List<Object[]> firstRead = sql("SELECT id, marker FROM %s ORDER BY id", encryptedTableName);
    encryptedTable = loadTable(encryptedTableName);
    TableMetadata metadataAfterFirstWrite = metadata(encryptedTable);
    Snapshot firstSnapshot = encryptedTable.currentSnapshot();
    DataFile firstDataFile = onlyElement(addedDataFiles(encryptedTable, firstSnapshot));
    ManifestFile firstManifest = firstSnapshot.allManifests(encryptedTable.io()).get(0);

    Map<String, Object> firstDataProbe =
        probeFile("encrypted-v3-data", firstDataFile.location(), FIRST_MARKER);
    Map<String, Object> firstManifestProbe =
        probeFile("encrypted-v3-manifest", firstManifest.path(), FIRST_MARKER);
    Map<String, Object> firstManifestListProbe =
        probeFile("encrypted-v3-manifest-list", firstSnapshot.manifestListLocation(), FIRST_MARKER);

    String loadAfterFirstWrite = loadTableResponse(ENCRYPTED_TABLE);
    String loadWarm = loadTableResponse(ENCRYPTED_TABLE);
    EncryptionState firstLoadState = encryptionState(loadAfterFirstWrite);
    EncryptionState warmLoadState = encryptionState(loadWarm);

    long firstSnapshotId = firstSnapshot.snapshotId();
    sql("INSERT INTO %s VALUES (2, '%s')", encryptedTableName, SECOND_MARKER);
    List<Object[]> secondRead = sql("SELECT id, marker FROM %s ORDER BY id", encryptedTableName);
    encryptedTable = loadTable(encryptedTableName);
    TableMetadata metadataAfterSecondWrite = metadata(encryptedTable);
    String loadAfterInvalidation = loadTableResponse(ENCRYPTED_TABLE);
    EncryptionState invalidatedLoadState = encryptionState(loadAfterInvalidation);

    sql(
        "ALTER TABLE %s SET TBLPROPERTIES ('audit.unrelated-property'='retained')",
        encryptedTableName);
    encryptedTable = loadTable(encryptedTableName);
    boolean encryptionPropertyRetained =
        KEY_ALIAS.equals(encryptedTable.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));

    Throwable v2Failure = null;
    try {
      sql(
          "CREATE TABLE %s.%s (id BIGINT) USING iceberg "
              + "TBLPROPERTIES ('format-version'='2', 'encryption.key-id'='%s')",
          namespace, V2_TABLE, KEY_ALIAS);
    } catch (Throwable throwable) {
      v2Failure = throwable;
    }
    Set<String> listedTables = convertToStringSet(sql("SHOW TABLES IN %s", namespace), 1);
    boolean v2TableExists = listedTables.contains(V2_TABLE);

    sql(
        "CREATE TABLE %s (id BIGINT, marker STRING) USING iceberg "
            + "TBLPROPERTIES ('format-version'='3', '%s'='uncompressed')",
        transitionTableName, PARQUET_COMPRESSION_PROPERTY);
    sql("INSERT INTO %s VALUES (1, '%s')", transitionTableName, BASELINE_MARKER);
    Table transitionTable = loadTable(transitionTableName);
    DataFile baselineDataFile =
        onlyElement(addedDataFiles(transitionTable, transitionTable.currentSnapshot()));
    Map<String, Object> baselineDataProbe =
        probeFile("transition-baseline-data", baselineDataFile.location(), BASELINE_MARKER);

    sql(
        "ALTER TABLE %s SET TBLPROPERTIES ('encryption.key-id'='%s')",
        transitionTableName, KEY_ALIAS);
    sql("INSERT INTO %s VALUES (2, '%s')", transitionTableName, TRANSITION_MARKER);
    List<Object[]> transitionRead =
        sql("SELECT id, marker FROM %s ORDER BY id", transitionTableName);
    transitionTable = loadTable(transitionTableName);
    TableMetadata transitionMetadata = metadata(transitionTable);
    DataFile transitionedDataFile =
        onlyElement(addedDataFiles(transitionTable, transitionTable.currentSnapshot()));
    Map<String, Object> transitionedDataProbe =
        probeFile("transition-new-data", transitionedDataFile.location(), TRANSITION_MARKER);

    CapturingHttpProxy.CapturedExchange createExchange =
        findExchange(
            exchange ->
                exchange.method().equals("POST")
                    && exchange.uri().endsWith("/namespaces/" + NAMESPACE + "/tables")
                    && exchange.requestBody().contains("\"name\":\"" + ENCRYPTED_TABLE + "\""));
    CapturingHttpProxy.CapturedExchange firstCommitExchange =
        findExchange(
            exchange ->
                exchange.method().equals("POST")
                    && exchange
                        .uri()
                        .endsWith("/namespaces/" + NAMESPACE + "/tables/" + ENCRYPTED_TABLE)
                    && exchange.requestBody().contains("\"add-snapshot\""));

    List<Map<String, Object>> rawObjectEvidence =
        List.of(
            firstDataProbe,
            firstManifestProbe,
            firstManifestListProbe,
            baselineDataProbe,
            transitionedDataProbe);
    writeArtifacts(
        configResponse,
        metadataBeforeWrite,
        metadataAfterFirstWrite,
        metadataAfterSecondWrite,
        transitionMetadata,
        rawObjectEvidence,
        createExchange,
        firstCommitExchange,
        loadBeforeWrite,
        loadAfterFirstWrite,
        loadWarm,
        loadAfterInvalidation,
        v2Failure,
        v2TableExists,
        encryptionPropertyRetained);

    Assertions.assertEquals(1L, firstRead.get(0)[0]);
    Assertions.assertEquals(FIRST_MARKER, firstRead.get(0)[1]);
    Assertions.assertEquals(2, secondRead.size());
    Assertions.assertEquals(2, transitionRead.size());

    Assertions.assertEquals(3, metadataAfterFirstWrite.formatVersion());
    Assertions.assertEquals(
        KEY_ALIAS, metadataAfterFirstWrite.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    Assertions.assertTrue(metadataAfterFirstWrite.encryptionKeys().isEmpty());
    Assertions.assertNull(firstSnapshot.keyId());
    Assertions.assertEquals(0, KeystoreKeyManagementClient.initializationCount());

    Assertions.assertEquals("PAR1", firstDataProbe.get("magicAscii"));
    Assertions.assertEquals(true, firstDataProbe.get("containsMarker"));
    Assertions.assertNotEquals("AGS1", firstManifestProbe.get("magicAscii"));
    Assertions.assertNotEquals("AGS1", firstManifestListProbe.get("magicAscii"));

    Assertions.assertEquals(firstLoadState, warmLoadState);
    Assertions.assertTrue(firstLoadState.encryptionKeys().isEmpty());
    Assertions.assertTrue(firstLoadState.snapshotKeyIds().isEmpty());
    Assertions.assertNotEquals(firstSnapshotId, invalidatedLoadState.currentSnapshotId());
    Assertions.assertTrue(invalidatedLoadState.encryptionKeys().isEmpty());
    Assertions.assertTrue(invalidatedLoadState.snapshotKeyIds().isEmpty());

    Assertions.assertTrue(createExchange.requestBody().contains("encryption.key-id"));
    Assertions.assertFalse(firstCommitExchange.requestBody().contains("add-encryption-key"));
    Assertions.assertFalse(firstCommitExchange.responseBody().contains("encryption-keys"));

    Assertions.assertNotNull(v2Failure);
    Assertions.assertTrue(rootCauseMessage(v2Failure).contains("Invalid properties for v2"));
    Assertions.assertFalse(v2TableExists);
    Assertions.assertTrue(encryptionPropertyRetained);

    Assertions.assertEquals("PAR1", baselineDataProbe.get("magicAscii"));
    Assertions.assertEquals(true, baselineDataProbe.get("containsMarker"));
    Assertions.assertEquals("PAR1", transitionedDataProbe.get("magicAscii"));
    Assertions.assertEquals(true, transitionedDataProbe.get("containsMarker"));
    Assertions.assertTrue(transitionMetadata.encryptionKeys().isEmpty());
    Assertions.assertNull(transitionTable.currentSnapshot().keyId());
  }

  private Table loadTable(String tableName) throws Exception {
    Table table = Spark3Util.loadIcebergTable(sparkSession, tableName);
    table.refresh();
    return table;
  }

  private static TableMetadata metadata(Table table) {
    return ((BaseTable) table).operations().current();
  }

  private String getJson(String relativePath) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(proxy.catalogUri() + relativePath)).GET().build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    Assertions.assertEquals(200, response.statusCode());
    return response.body();
  }

  private String loadTableResponse(String tableName) throws IOException, InterruptedException {
    return getJson("v1/namespaces/" + NAMESPACE + "/tables/" + tableName);
  }

  private EncryptionState encryptionState(String loadTableResponse) throws IOException {
    JsonNode metadata = objectMapper.readTree(loadTableResponse).path("metadata");
    List<String> encryptionKeys = new ArrayList<>();
    metadata
        .path("encryption-keys")
        .forEach(key -> encryptionKeys.add(key.path("encrypted-key-metadata").asText()));

    List<String> snapshotKeyIds = new ArrayList<>();
    metadata
        .path("snapshots")
        .forEach(
            snapshot -> {
              if (snapshot.hasNonNull("key-id")) {
                snapshotKeyIds.add(snapshot.path("key-id").asText());
              }
            });

    return new EncryptionState(
        encryptionKeys, snapshotKeyIds, metadata.path("current-snapshot-id").asLong(-1));
  }

  private Map<String, Object> probeFile(String name, String location, String marker)
      throws Exception {
    Path path = pathFromLocation(location);
    byte[] bytes = Files.readAllBytes(path);
    int magicLength = Math.min(4, bytes.length);
    byte[] magic = Arrays.copyOf(bytes, magicLength);

    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("name", name);
    evidence.put("location", location);
    evidence.put("size", bytes.length);
    evidence.put(
        "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    evidence.put("magicHex", HexFormat.of().formatHex(magic));
    evidence.put("magicAscii", new String(magic, StandardCharsets.ISO_8859_1));
    evidence.put("containsMarker", contains(bytes, marker.getBytes(StandardCharsets.UTF_8)));
    return evidence;
  }

  private void writeArtifacts(
      String configResponse,
      TableMetadata metadataBeforeWrite,
      TableMetadata metadataAfterFirstWrite,
      TableMetadata metadataAfterSecondWrite,
      TableMetadata transitionMetadata,
      List<Map<String, Object>> rawObjectEvidence,
      CapturingHttpProxy.CapturedExchange createExchange,
      CapturingHttpProxy.CapturedExchange firstCommitExchange,
      String loadBeforeWrite,
      String loadAfterFirstWrite,
      String loadWarm,
      String loadAfterInvalidation,
      Throwable v2Failure,
      boolean v2TableExists,
      boolean encryptionPropertyRetained)
      throws Exception {
    Files.writeString(artifactDirectory.resolve("config-response.json"), configResponse);
    Files.writeString(
        artifactDirectory.resolve("metadata-before-write.json"),
        TableMetadataParser.toJson(metadataBeforeWrite));
    Files.writeString(
        artifactDirectory.resolve("metadata-after-first-write.json"),
        TableMetadataParser.toJson(metadataAfterFirstWrite));
    Files.writeString(
        artifactDirectory.resolve("metadata-after-second-write.json"),
        TableMetadataParser.toJson(metadataAfterSecondWrite));
    Files.writeString(
        artifactDirectory.resolve("metadata-existing-table-transition.json"),
        TableMetadataParser.toJson(transitionMetadata));
    Files.writeString(artifactDirectory.resolve("load-before-write.json"), loadBeforeWrite);
    Files.writeString(
        artifactDirectory.resolve("load-after-first-write.json"), loadAfterFirstWrite);
    Files.writeString(artifactDirectory.resolve("load-warm.json"), loadWarm);
    Files.writeString(
        artifactDirectory.resolve("load-after-invalidation.json"), loadAfterInvalidation);

    objectMapper.writeValue(
        artifactDirectory.resolve("rest-exchanges.json").toFile(), proxy.exchanges());
    objectMapper.writeValue(
        artifactDirectory.resolve("raw-object-evidence.json").toFile(), rawObjectEvidence);

    Map<String, Object> environment = new LinkedHashMap<>();
    environment.put("gravitinoRevision", gitRevision());
    environment.put("sparkVersion", sparkSession.version());
    environment.put("icebergVersion", IcebergBuild.version());
    environment.put("icebergFullVersion", IcebergBuild.fullVersion());
    environment.put("catalogBackend", "memory");
    environment.put("warehouse", warehouse.toString());
    environment.put("tableMetadataCache", "LocalTableMetadataCache");
    environment.put("kmsImplementation", KeystoreKeyManagementClient.class.getName());
    environment.put("keystoreType", "JCEKS");
    environment.put("keystorePath", keyStorePath.toString());
    environment.put("masterKeyAlias", KEY_ALIAS);
    environment.put("kmsInitializationCount", KeystoreKeyManagementClient.initializationCount());
    objectMapper.writeValue(artifactDirectory.resolve("environment.json").toFile(), environment);

    Map<String, Object> semanticDiff = new LinkedHashMap<>();
    semanticDiff.put(
        "encryptionKeyIdBeforeWrite",
        metadataBeforeWrite.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    semanticDiff.put(
        "encryptionKeyIdAfterWrite",
        metadataAfterFirstWrite.properties().get(TableProperties.ENCRYPTION_TABLE_KEY));
    semanticDiff.put("encryptionKeysBeforeWrite", metadataBeforeWrite.encryptionKeys());
    semanticDiff.put("encryptionKeysAfterWrite", metadataAfterFirstWrite.encryptionKeys());
    semanticDiff.put("snapshotKeyIdAfterWrite", metadataAfterFirstWrite.currentSnapshot().keyId());
    semanticDiff.put("createRequest", objectMapper.readTree(createExchange.requestBody()));
    semanticDiff.put(
        "firstCommitRequest", objectMapper.readTree(firstCommitExchange.requestBody()));
    semanticDiff.put(
        "firstCommitResponse", objectMapper.readTree(firstCommitExchange.responseBody()));
    objectMapper.writeValue(
        artifactDirectory.resolve("semantic-encryption-diff.json").toFile(), semanticDiff);

    Files.writeString(
        artifactDirectory.resolve("audit-report.md"),
        auditReport(
            environment, rawObjectEvidence, v2Failure, v2TableExists, encryptionPropertyRetained));
    LOG.info("DAT-152 audit artifacts written to {}", artifactDirectory);
  }

  private String auditReport(
      Map<String, Object> environment,
      List<Map<String, Object>> rawObjectEvidence,
      Throwable v2Failure,
      boolean v2TableExists,
      boolean encryptionPropertyRetained) {
    String rawEvidence =
        rawObjectEvidence.stream()
            .map(
                evidence ->
                    String.format(
                        "* `%s`: magic `%s`, marker present `%s`, SHA-256 `%s`",
                        evidence.get("name"),
                        evidence.get("magicAscii"),
                        evidence.get("containsMarker"),
                        evidence.get("sha256")))
            .collect(Collectors.joining("\n"));

    return String.format(
        "# DAT-152 day-one Iceberg encryption compatibility audit\n\n"
            + "## Final classification\n\n"
            + "**Outcome 3: Silent corruption / unsafe success semantics.**\n\n"
            + "The format-v3 create and both writes reported success, and reads returned the "
            + "expected rows. However, the Iceberg REST client never initialized the configured "
            + "keystore-backed KMS client. The commit omitted `add-encryption-key`, the returned "
            + "table metadata had no `encryption-keys`, snapshot `key-id` was absent, and the raw "
            + "Parquet/manifest objects were plaintext.\n\n"
            + "## Environment\n\n"
            + "* Gravitino revision: `%s`\n"
            + "* Spark: `%s`\n"
            + "* Iceberg: `%s`\n"
            + "* Backend: memory catalog with `LocalTableMetadataCache`\n"
            + "* KMS: `%s`, JCEKS alias `%s`\n"
            + "* KMS initialization count after all operations: `%s`\n\n"
            + "## Verification matrix\n\n"
            + "| Scenario | Result | Evidence / justification |\n"
            + "| --- | --- | --- |\n"
            + "| Environment baseline | PASS | Exact versions and configuration are in `environment.json`. |\n"
            + "| Encrypted table creation | PASS (accepted) | Create request contains format v3 and `encryption.key-id`. |\n"
            + "| Encrypted write | FAIL | Commit succeeded without `add-encryption-key`; KMS initialization count stayed zero. |\n"
            + "| Encrypted read | PASS functionally | Both inserted rows round-tripped, but from plaintext objects. |\n"
            + "| Ciphertext at rest | FAIL | Parquet magic is `PAR1`, manifests are not `AGS1`, and known plaintext is present. |\n"
            + "| Commit update acceptance | FAIL | `rest-exchanges.json` shows success without encryption-key updates. |\n"
            + "| Metadata round-trip | FAIL | `encryption.key-id` survives, but required encryption-key and snapshot-key fields are never produced. |\n"
            + "| `loadTable` response | FAIL | Captured responses omit required encryption metadata after successful commits. |\n"
            + "| Server cache path | FAIL | Repeated warm loads are value-identical but identically incomplete. |\n"
            + "| Dynamic configuration path | N/A | The client never activates encryption even with local KMS config; no encrypted state exists to compare through this path. Route configuration delivery to DAT-160. |\n"
            + "| Cache invalidation | FAIL | The new snapshot becomes visible, but it also has no key reference or encryption-key list. |\n"
            + "| Property fidelity: IRC | %s | `encryption.key-id` remains after an unrelated property alter. |\n"
            + "| Property fidelity: Gravitino API | N/A | Standalone static IRC run has no native Gravitino table object; property parity belongs to DAT-161 after fail-closed safety is restored. |\n"
            + "| Format v3 behavior | FAIL | Accepted operations silently write plaintext. |\n"
            + "| Format v2 behavior | %s | Rejected by Iceberg compatibility validation; table exists after failure: `%s`; error: `%s`. |\n"
            + "| Existing-table transition | FAIL | Setting `encryption.key-id` is accepted, while old and new files both remain plaintext. |\n"
            + "| Outcome classification | PASS | Exactly Outcome 3. |\n\n"
            + "## Raw-object evidence\n\n%s\n\n"
            + "## Gap routing\n\n"
            + "* Commit update evidence maps to DAT-158.\n"
            + "* `loadTable`, cache, and invalidation evidence maps to DAT-159.\n"
            + "* KMS configuration delivery maps to DAT-160, but local KMS configuration also "
            + "fails to activate encryption, so delivery alone is insufficient.\n"
            + "* The unsafe-success behavior requires a separate urgent patch-candidate defect "
            + "covering REST client encryption-manager wiring or fail-closed rejection.\n",
        environment.get("gravitinoRevision"),
        environment.get("sparkVersion"),
        environment.get("icebergFullVersion"),
        environment.get("kmsImplementation"),
        environment.get("masterKeyAlias"),
        environment.get("kmsInitializationCount"),
        encryptionPropertyRetained ? "PASS" : "FAIL",
        v2Failure != null && !v2TableExists ? "PASS" : "FAIL",
        v2TableExists,
        v2Failure == null ? "no rejection" : rootCauseMessage(v2Failure),
        rawEvidence);
  }

  private CapturingHttpProxy.CapturedExchange findExchange(
      Predicate<CapturingHttpProxy.CapturedExchange> predicate) {
    return proxy.exchanges().stream()
        .filter(predicate)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected REST exchange was not captured"));
  }

  private static Path pathFromLocation(String location) {
    return location.startsWith("file:") ? Path.of(URI.create(location)) : Path.of(location);
  }

  private static <T> T onlyElement(Iterable<T> elements) {
    Iterator<T> iterator = elements.iterator();
    if (!iterator.hasNext()) {
      throw new IllegalArgumentException("Expected one element, found none");
    }
    T element = iterator.next();
    if (iterator.hasNext()) {
      throw new IllegalArgumentException("Expected one element, found multiple");
    }
    return element;
  }

  private static Iterable<DataFile> addedDataFiles(Table table, Snapshot snapshot) {
    return SnapshotChanges.builderFor(table).snapshot(snapshot).build().addedDataFiles();
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private static String rootCauseMessage(Throwable throwable) {
    Throwable cause = throwable;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());
  }

  private String gitRevision() {
    String root = System.getenv("GRAVITINO_ROOT_DIR");
    if (root == null) {
      return "unknown";
    }
    try {
      Process process =
          new ProcessBuilder("git", "rev-parse", "HEAD").directory(Path.of(root).toFile()).start();
      String revision =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return process.waitFor() == 0 ? revision : "unknown";
    } catch (IOException e) {
      return "unknown";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }

  private void createKeyStore(Path path) throws GeneralSecurityException, IOException {
    byte[] masterKey = new byte[32];
    Arrays.fill(masterKey, (byte) 11);

    KeyStore keyStore = KeyStore.getInstance("JCEKS");
    keyStore.load(null, KEYSTORE_PASSWORD.toCharArray());
    keyStore.setEntry(
        KEY_ALIAS,
        new KeyStore.SecretKeyEntry(new SecretKeySpec(masterKey, "AES")),
        new KeyStore.PasswordProtection(KEYSTORE_PASSWORD.toCharArray()));
    try (OutputStream output = Files.newOutputStream(path)) {
      keyStore.store(output, KEYSTORE_PASSWORD.toCharArray());
    }
  }

  private static final class EncryptionState {
    private final List<String> encryptionKeys;
    private final List<String> snapshotKeyIds;
    private final long currentSnapshotId;

    private EncryptionState(
        List<String> encryptionKeys, List<String> snapshotKeyIds, long currentSnapshotId) {
      this.encryptionKeys = encryptionKeys;
      this.snapshotKeyIds = snapshotKeyIds;
      this.currentSnapshotId = currentSnapshotId;
    }

    private List<String> encryptionKeys() {
      return encryptionKeys;
    }

    private List<String> snapshotKeyIds() {
      return snapshotKeyIds;
    }

    private long currentSnapshotId() {
      return currentSnapshotId;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof EncryptionState that)) {
        return false;
      }
      return currentSnapshotId == that.currentSnapshotId
          && encryptionKeys.equals(that.encryptionKeys)
          && snapshotKeyIds.equals(that.snapshotKeyIds);
    }

    @Override
    public int hashCode() {
      return Objects.hash(encryptionKeys, snapshotKeyIds, currentSnapshotId);
    }
  }
}
