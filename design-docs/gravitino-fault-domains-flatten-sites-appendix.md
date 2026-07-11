<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements. See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership. The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied. See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Appendix: Enumerated Error-Flattening Sites

Total annotated sites: **1332** across 15 scopes.

## Rollup by boundary

| Boundary | Sites |
|---|---|
| B3 | 262 |
| B5 | 191 |
| B8 | 181 |
| B1 | 178 |
| B7 | 159 |
| internal | 149 |
| B11 | 63 |
| B9 | 53 |
| B10 | 42 |
| B2 | 40 |
| B4 | 12 |
| B6 | 2 |

## Rollup by proposed canonical code

| Proposed code | Sites |
|---|---|
| keep | 570 |
| INTERNAL_DEP(502) | 303 |
| INTERNAL_BUG(500) | 178 |
| UNKNOWN | 136 |
| FAILED_PRECONDITION | 50 |
| UNAVAILABLE | 33 |
| INVALID_ARGUMENT | 25 |
| ABORTED | 12 |
| NOT_FOUND | 8 |
| TODO | 7 |
| UNAUTHENTICATED | 4 |
| ALREADY_EXISTS | 4 |
| UNIMPLEMENTED | 2 |

## api-common (11 sites; raw rg hits: 11)

> api/src/main contains zero hits for either pattern; all 11 sites are in common/src/main. Notable pairs: CredentialProviderDelegator 66/67 and 103/104, and CredentialFactory 49/50 are catch+throw pairs at the same site (each raw rg hit listed once as required). Specific findings: (1) CredentialProviderDelegator.getCredential's broad catch flattens cloud-credential-vending failures (STS down vs bad cloud creds vs misconfig) into bare RuntimeException — the single biggest dependency-vs-bug flattener in this scope; a cloud outage would surface as 500 instead of 502, and an UNAUTHENTICATED-to-cloud case is also plausible but indistinguishable after the catch. (2) JdbcUrlUtils.recursiveDecode:111-112 drops the cause entirely (GravitinoRuntimeException without e) — it is validating user catalog properties, so it should be an IllegalArgumentException-family code with cause. (3) JsonUtils:1406 wrap is forced by lambda checked-exception rules but should rethrow a typed parse exception (UncheckedIOException at minimum) so the wire layer can map malformed statistic JSON to 400. (4) Config.java:247 preserves cause and rethrows checked IOException with context at process bootstrap — correct as-is. (5) CredentialFactory.java:62 (no impl for type) could alternatively be FAILED_PRECONDITION if the type string originates from stored metadata rather than request input; UNIMPLEMENTED chosen because it presents as an unsupported-credential-type condition.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `common/src/main/java/org/apache/gravitino/Config.java:247` | broadCatch | server config properties file load/parse at startup (rethrown as IOException with context, cause preserved) | B11 | keep |
| `common/src/main/java/org/apache/gravitino/credential/CredentialFactory.java:49` | broadCatch | reflective Credential newInstance + initialize(credentialInfo) (bad info or reflection failure) | internal | INTERNAL_BUG(500) |
| `common/src/main/java/org/apache/gravitino/credential/CredentialFactory.java:50` | wrap | credential instantiation/init failure rethrown as message-less RuntimeException(e) | internal | INTERNAL_BUG(500) |
| `common/src/main/java/org/apache/gravitino/credential/CredentialFactory.java:62` | wrap | ServiceLoader lookup miss: no Credential impl registered for requested credentialType | internal | UNIMPLEMENTED |
| `common/src/main/java/org/apache/gravitino/credential/CredentialFactory.java:64` | wrap | ServiceLoader classpath conflict: multiple Credential impls for one credentialType | internal | INTERNAL_BUG(500) |
| `common/src/main/java/org/apache/gravitino/credential/CredentialProviderDelegator.java:66` | broadCatch | credential generator.generate() call (cloud STS/token vending) | B1 | INTERNAL_DEP(502) |
| `common/src/main/java/org/apache/gravitino/credential/CredentialProviderDelegator.java:67` | wrap | credential generation failure rethrown as bare RuntimeException (cause preserved) | B1 | INTERNAL_DEP(502) |
| `common/src/main/java/org/apache/gravitino/credential/CredentialProviderDelegator.java:103` | broadCatch | reflective Class.forName/newInstance of CredentialGenerator (missing jar/bad class) | internal | INTERNAL_BUG(500) |
| `common/src/main/java/org/apache/gravitino/credential/CredentialProviderDelegator.java:104` | wrap | generator classload/instantiation failure rethrown as bare RuntimeException | internal | INTERNAL_BUG(500) |
| `common/src/main/java/org/apache/gravitino/json/JsonUtils.java:1406` | wrap | checked IOException from recursive StatisticValue JSON parse inside forEachRemaining lambda | B7 | INVALID_ARGUMENT |
| `common/src/main/java/org/apache/gravitino/utils/JdbcUrlUtils.java:111` | broadCatch | URLDecoder.decode of user-supplied JDBC URL during safety validation (cause DROPPED) | internal | INVALID_ARGUMENT |

## authz-mcp (19 sites; raw rg hits: 19)

> Raw hit count: 17 Java (authorizations/*/src/main) + 2 Python (mcp-server/mcp_server) = 19; no test paths matched the globs. Boundary convention used: Ranger-admin REST and JDBC-driver failures caught inside authz plugins are marked B1 (external backend → plugin, the plugin acting as connector to Ranger/JDBC); plugin lifecycle/init sites whose exceptions cross into core's AccessControlManager are B4. Notable specifics: RangerClientExtension wraps everything as bare RuntimeException(e) so core cannot distinguish Ranger-down (should be 502) from reflection bugs — line 99 is pure library-shape reflection setup (bug/fail-fast, INTERNAL_BUG) while lines 135-195 are Ranger backend calls (INTERNAL_DEP). RangerAuthorizationPlugin.java:1096 additionally loses the cause: new AuthorizationPluginException(message) without passing e. ChainedAuthorizationPlugin:83-84 flattens config errors (bad provider name, missing plugin jars) into RuntimeException during catalog creation — should surface as FAILED_PRECONDITION/config error, not 500. JdbcAuthorizationPlugin:104 wraps close-path SQLException in RuntimeException although close() declares IOException; log-and-continue or IOException wrap would be more appropriate; classified INTERNAL_DEP if it must surface. server.py:79 is a deliberate audit tap that re-raises unchanged — correct as-is (keep). server.py:147 is startup CLI config validation correctly narrowed to ValueError (INVALID_ARGUMENT-equivalent for a process boundary); the only wrinkle is it also re-wraps its own inner ValueError from the scheme check, which is harmless.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `authorizations/authorization-chain/src/main/java/org/apache/gravitino/authorization/chain/ChainedAuthorizationPlugin.java:83` | broadCatch | child authz plugin load/instantiation failure (isolated classloader build, BaseAuthorization.createAuthorization, newPlugin) | B4 | FAILED_PRECONDITION |
| `authorizations/authorization-chain/src/main/java/org/apache/gravitino/authorization/chain/ChainedAuthorizationPlugin.java:84` | wrap | same child-plugin init failure rethrown as bare RuntimeException(e) | B4 | FAILED_PRECONDITION |
| `authorizations/authorization-common/src/main/java/org/apache/gravitino/authorization/jdbc/JdbcAuthorizationPlugin.java:104` | wrap | SQLException from dataSource.close() in plugin close() (teardown path; close() already declares IOException) | B4 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerAuthorizationHDFSPlugin.java:281` | wrap | RangerServiceException from rangerClient.updatePolicy during path-policy rename | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerAuthorizationHadoopSQLPlugin.java:266` | wrap | RangerServiceException from rangerClient.updatePolicy during SQL-policy rename | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerAuthorizationHadoopSQLPlugin.java:310` | wrap | RangerServiceException from rangerClient.getPoliciesInService during schema-wildcard removal | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerAuthorizationPlugin.java:1096` | broadCatch | FieldUtils.readField reflection on VXGroup.name in getGroupId (also drops cause exception entirely) | internal | INTERNAL_BUG(500) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:99` | wrap | NoSuchMethodException from reflective lookup of RangerClient.callAPI in constructor (Ranger lib version skew) | internal | INTERNAL_BUG(500) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:135` | wrap | Ranger async createUser returned 204 but user not found on re-search (backend didn't materialize user) | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:140` | wrap | Ranger REST createUser non-204 UniformInterfaceException (HTTP error status from Ranger admin) | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:143` | wrap | reflective callAPI createUser failure with non-HTTP cause (InvocationTargetException/IllegalAccessException) | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:154` | wrap | Ranger REST searchUser reflective call failure | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:163` | wrap | Ranger REST deleteUser failure (reflective call or RangerServiceException) | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:175` | wrap | Ranger REST createGroup reflective call failure | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:186` | wrap | Ranger REST searchGroup reflective call failure | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerClientExtension.java:195` | wrap | Ranger REST deleteGroup failure (reflective call or RangerServiceException) | B1 | INTERNAL_DEP(502) |
| `authorizations/authorization-ranger/src/main/java/org/apache/gravitino/authorization/ranger/RangerHelper.java:209` | wrap | RangerServiceException from deletePolicy/updatePolicy in removeAllGravitinoManagedPolicyItem | B1 | INTERNAL_DEP(502) |
| `mcp-server/mcp_server/server.py:79` | broadCatch | any tool-invocation failure, tapped for audit emit(outcome=deny) then re-raised unchanged | B10 | keep |
| `mcp-server/mcp_server/server.py:147` | broadCatch | urlparse/scheme-validation failure for --mcp-url at startup, re-raised as ValueError with context | B11 | INVALID_ARGUMENT |

## aux-iceberg (113 sites; raw rg hits: 113)

> Scope: iceberg/iceberg-common/src/main + iceberg/iceberg-rest-server/src/main, main sources only (worktree /Users/nlz/datastrato/nevzheng/gravitino/.claude/worktrees/fault-domains-architecture-a72d2d). Raw hits: 29 "throw new RuntimeException" + 84 "catch (Exception" = 113; sites[] has exactly 113 rows, verified per-file counts against rg -c. Boundary conventions used: B10 = REST endpoint catch → IcebergExceptionMapper.toRESTResponse (the aux gateway wire; 25 sites, all 'keep' — the mapper is the single classification point, so the classify-then-preserve fix belongs in IcebergExceptionMapper, not these catches); B1 = Iceberg backend (HMS thrift, JDBC catalog DB, remote REST catalog, FileIO/object storage); B3 = Gravitino EntityStore access from IRC hook dispatchers; B5 = event-bus dispatchers (all catch→failure-event→rethrow, correct pattern); B4 = authorization interceptor; B11 = process/startup lifecycle. Notable genuine flattenings: (1) HiveCatalogWithMetadataLocationSupport:88 and JdbcCatalogWithMetadataLocationSupport:63 swallow backend-down into a null metadata location (dependency failure masquerades as absence — poisons ETag/metadata-location fast paths); (2) BaseMetadataAuthorizationMethodInterceptor:152 turns NoSuchMetalake/NoSuchUser during user validation into a 500 RuntimeException; (3) six hook-dispatcher EntityStore IOException wraps surface metadata-DB outage as 500 instead of 502; (4) CatalogWrapperForREST:458/460 catch-all merges storage faults and bugs into one RuntimeException (UNKNOWN — needs classify-then-preserve); (5) config-driven Class.forName sites (IcebergConfigProviderFactory, IcebergMetricsManager.loadIcebergMetricsStore) flatten operator config errors into bare RuntimeException(e). Interrupt-mid-commit at HiveCatalogWithMetadataLocationSupport:174 proposed ABORTED (commit outcome unknown; nearest canonical code — CommitStateUnknown semantics). IcebergCleanupManager wraps marked 'keep' because the RuntimeException type deliberately drives the transient-retry path of the background worker. Line 88 of ClosableHiveCatalog and 439/458 of CatalogWrapperForREST are catch halves of the wrap throws at 89/441/460 respectively — counted as separate rows per the raw-hit contract.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableHiveCatalog.java:88` | broadCatch | reflection + client pool ctor failure in resetIcebergHiveClientPool at init | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableHiveCatalog.java:89` | wrap | IcebergHiveClientPool reset failure wrapped as RuntimeException | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableHiveCatalog.java:110` | broadCatch | resource.close during catalog close (warn only) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableHiveCatalog.java:151` | broadCatch | HMS delegation-token fetch via UGI doAs (thrift call), rethrown wrapped | B1 | INTERNAL_DEP(502) |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableHiveCatalog.java:152` | wrap | delegation-token failure flattened to RuntimeException (KDC/HMS dep vs config indistinct) | B1 | INTERNAL_DEP(502) |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableHiveCatalog.java:175` | broadCatch | old Hive client pool close (warn only) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableHiveCatalog.java:186` | broadCatch | CachedClientPool scheduler shutdown via reflection (warn only) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableHiveCatalog.java:246` | broadCatch | internal HiveCatalog client pool close via reflection (warn only) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableJdbcCatalog.java:86` | broadCatch | close() failure suppressed onto kerberos-init failure (addSuppressed, rethrows original) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ClosableJdbcCatalog.java:99` | broadCatch | JdbcCatalog super.close failure (warn only) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ops/IcebergCatalogWrapper.java:459` | broadCatch | MySQL AbandonedConnectionCleanupThread shutdown / driver deregister during close (warn only) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/ops/IcebergCatalogWrapper.java:472` | broadCatch | JDBC driver deregistration during close (warn only) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/utils/CaffeineSchedulerExtractorUtils.java:76` | broadCatch | reflection extraction of Caffeine scheduler (best-effort, returns null) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/utils/IcebergCatalogUtil.java:217` | wrap | unsupported catalog-backend enum value from config | internal | INVALID_ARGUMENT |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/utils/IcebergHiveCachedClientPool.java:215` | wrap | unreachable enum-switch default for key element type | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/utils/KerberosCatalogUtils.java:69` | wrap | kerberos keytab fetch + login IOException (KDC down vs bad keytab config indistinct) | B1 | INTERNAL_DEP(502) |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/utils/KerberosCatalogUtils.java:83` | broadCatch | KerberosClient.close (warn only) | internal | keep |
| `iceberg/iceberg-common/src/main/java/org/apache/gravitino/iceberg/common/utils/KerberosCatalogUtils.java:171` | wrap | checked Throwable from catalog op run under UGI doAs (RuntimeExceptions pass through; checked HMS/thrift errors get flattened) | B1 | INTERNAL_DEP(502) |
| `iceberg/iceberg-common/src/main/java/org/apache/iceberg/hive/HiveCatalogWithMetadataLocationSupport.java:88` | broadCatch | HMS getTable failure in metadataLocation() swallowed to null (flattens HMS-down into cache-miss/absent) | B1 | INTERNAL_DEP(502) |
| `iceberg/iceberg-common/src/main/java/org/apache/iceberg/hive/HiveCatalogWithMetadataLocationSupport.java:167` | wrap | HMS TException during register-table overwrite commit | B1 | INTERNAL_DEP(502) |
| `iceberg/iceberg-common/src/main/java/org/apache/iceberg/hive/HiveCatalogWithMetadataLocationSupport.java:174` | wrap | InterruptedException mid-commit of overwrite registration (outcome unknown) | B1 | ABORTED |
| `iceberg/iceberg-common/src/main/java/org/apache/iceberg/hive/HiveCatalogWithMetadataLocationSupport.java:187` | wrap | IllegalAccessException reading 'clients' field via reflection | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-common/src/main/java/org/apache/iceberg/jdbc/JdbcCatalogWithMetadataLocationSupport.java:63` | broadCatch | JDBC loadTable failure in metadataLocation() swallowed to null (flattens DB-down into cache-miss/absent) | B1 | INTERNAL_DEP(502) |
| `iceberg/iceberg-common/src/main/java/org/apache/iceberg/jdbc/JdbcCatalogWithMetadataLocationSupport.java:136` | wrap | IllegalAccessException reading JDBC catalog fields via reflection | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-common/src/main/java/org/apache/iceberg/memory/MemoryCatalogWithMetadataLocationSupport.java:81` | wrap | IllegalAccessException reading 'tables' field via reflection | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/RESTService.java:243` | broadCatch | Jetty server.start failure at service start (cleans up components, rethrows) | B11 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/RESTService.java:247` | wrap | Jetty server.start exception rethrown as RuntimeException (fail-fast startup) | B11 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/server/GravitinoIcebergRESTServer.java:81` | broadCatch | standalone server start failure → log + System.exit(-1) | B11 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/server/GravitinoIcebergRESTServer.java:97` | broadCatch | shutdown-hook cleanup task failure (log only) | B11 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/server/GravitinoIcebergRESTServer.java:107` | broadCatch | server stop failure during shutdown (log only) | B11 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/CatalogWrapperForREST.java:427` | wrap | IOException closing scan-task iterable (manifest/storage IO during plan) | B1 | INTERNAL_DEP(502) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/CatalogWrapperForREST.java:439` | broadCatch | buildCompletedPlanTableScanResponse failure (response assembly bug) | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/CatalogWrapperForREST.java:441` | wrap | scan-plan response build failure wrapped as RuntimeException | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/CatalogWrapperForREST.java:458` | broadCatch | residual scan-planning failures (storage IO + bugs mixed; IAE and NoSuchTable already peeled off) | B1 | UNKNOWN |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/CatalogWrapperForREST.java:460` | wrap | residual scan-planning failure flattened to RuntimeException | B1 | UNKNOWN |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/CatalogWrapperForREST.java:545` | broadCatch | client filter-expression bind failure reclassified to IllegalArgumentException | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/CatalogWrapperForREST.java:559` | broadCatch | client column-projection failure reclassified to IllegalArgumentException | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/CatalogWrapperForREST.java:573` | broadCatch | client stats-fields failure reclassified to IllegalArgumentException | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/FederatedCatalogWrapper.java:184` | broadCatch | authSession.close in finally when fetching remote credentials (warn only) | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/FederatedCatalogWrapper.java:193` | broadCatch | REST client close in finally (warn only) | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/FederatedCatalogWrapper.java:202` | broadCatch | authManager.close in finally (warn only) | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/IcebergCatalogWrapperManager.java:156` | broadCatch | catalog wrapper close on cache eviction (warn only) | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/IcebergRESTUtils.java:390` | wrap | Jackson serialize/deserialize round-trip in cloneIcebergRESTObject | internal | INTERNAL_BUG(500) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/cleanup/IcebergCleanupManager.java:244` | wrap | InterruptedException waiting on bulk-delete future (background job, failure recorded and retried) | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/cleanup/IcebergCleanupManager.java:250` | wrap | ExecutionException from batch file delete against FileIO/object storage (job retried as transient) | B1 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/cleanup/IcebergCleanupManager.java:359` | broadCatch | manifest read failure during cleanup (NotFound handled separately; rethrown for retry) | B1 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/cleanup/IcebergCleanupManager.java:360` | wrap | manifest read failure wrapped as RuntimeException (drives transient-retry path) | B1 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceEventDispatcher.java:104` | broadCatch | createNamespace failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceEventDispatcher.java:139` | broadCatch | updateNamespace failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceEventDispatcher.java:166` | broadCatch | dropNamespace failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceEventDispatcher.java:183` | broadCatch | loadNamespace failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceEventDispatcher.java:208` | broadCatch | listNamespaces failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceEventDispatcher.java:223` | broadCatch | namespaceExists failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceEventDispatcher.java:249` | broadCatch | registerTable failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceEventDispatcher.java:275` | broadCatch | registerView failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceHookDispatcher.java:268` | wrap | EntityStore.exists IOException (view entity check) | B3 | INTERNAL_DEP(502) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergNamespaceHookDispatcher.java:287` | wrap | EntityStore.exists IOException (table entity check) | B3 | INTERNAL_DEP(502) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:104` | broadCatch | createTable failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:137` | broadCatch | updateTable failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:162` | broadCatch | dropTable failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:181` | broadCatch | loadTable failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:203` | broadCatch | listTable failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:218` | broadCatch | tableExists failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:237` | broadCatch | renameTable failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:267` | broadCatch | getTableCredentials failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableEventDispatcher.java:299` | broadCatch | planTableScan failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableHookDispatcher.java:163` | wrap | EntityStore.update IOException renaming table entity | B3 | INTERNAL_DEP(502) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergTableHookDispatcher.java:277` | wrap | EntityStore.delete IOException deleting table entity | B3 | INTERNAL_DEP(502) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewEventDispatcher.java:95` | broadCatch | createView failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewEventDispatcher.java:125` | broadCatch | replaceView failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewEventDispatcher.java:148` | broadCatch | dropView failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewEventDispatcher.java:164` | broadCatch | loadView failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewEventDispatcher.java:186` | broadCatch | listView failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewEventDispatcher.java:201` | broadCatch | viewExists failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewEventDispatcher.java:220` | broadCatch | renameView failure → failure event dispatched, rethrown | B5 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewHookDispatcher.java:166` | wrap | EntityStore.update IOException renaming view entity | B3 | INTERNAL_DEP(502) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewHookDispatcher.java:206` | broadCatch | best-effort view import into Gravitino core via ViewDispatcher (warn only, deliberate) | B2 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/dispatcher/IcebergViewHookDispatcher.java:281` | wrap | EntityStore.delete IOException deleting view entity | B3 | INTERNAL_DEP(502) |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/metrics/IcebergMetricsManager.java:69` | wrap | metrics store init IOException at manager construction (fail-fast startup) | B11 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/metrics/IcebergMetricsManager.java:105` | broadCatch | scheduled metrics-clean task failure (warn only, keeps schedule alive) | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/metrics/IcebergMetricsManager.java:198` | broadCatch | metrics store Class.forName/newInstance failure (bad config class name) | B11 | INVALID_ARGUMENT |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/metrics/IcebergMetricsManager.java:201` | wrap | metrics store instantiation failure flattened to bare RuntimeException(e) | B11 | INVALID_ARGUMENT |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/metrics/IcebergMetricsManager.java:212` | broadCatch | metrics write to store on async writer thread (warn only, deliberate isolation) | internal | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/metrics/JDBCMetricsStore.java:133` | wrap | SQLException/InterruptedException probing metrics tables at store init (fail-fast startup) | B11 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/provider/IcebergConfigProviderFactory.java:48` | broadCatch | config-provider Class.forName/newInstance failure (bad config class name) | B11 | INVALID_ARGUMENT |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/provider/IcebergConfigProviderFactory.java:49` | wrap | provider instantiation failure flattened to bare RuntimeException(e) | B11 | INVALID_ARGUMENT |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergNamespaceOperations.java:137` | broadCatch | listNamespaces dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergNamespaceOperations.java:169` | broadCatch | loadNamespace dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergNamespaceOperations.java:204` | broadCatch | namespaceExists dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergNamespaceOperations.java:235` | broadCatch | dropNamespace dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergNamespaceOperations.java:267` | broadCatch | createNamespace dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergNamespaceOperations.java:304` | broadCatch | updateNamespace dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergNamespaceOperations.java:349` | broadCatch | registerTable dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergNamespaceOperations.java:385` | broadCatch | registerView dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:155` | broadCatch | listTable dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:198` | broadCatch | createTable dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:244` | broadCatch | updateTable dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:287` | broadCatch | dropTable dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:356` | broadCatch | loadTable dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:398` | broadCatch | tableExists dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:436` | broadCatch | reportTableMetrics failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:495` | broadCatch | getTableCredentials dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableOperations.java:551` | broadCatch | planTableScan dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergTableRenameOperations.java:94` | broadCatch | renameTable dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergViewOperations.java:131` | broadCatch | listView dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergViewOperations.java:166` | broadCatch | createView dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergViewOperations.java:211` | broadCatch | loadView dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergViewOperations.java:251` | broadCatch | replaceView dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergViewOperations.java:288` | broadCatch | dropView dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergViewOperations.java:329` | broadCatch | viewExists dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/iceberg/service/rest/IcebergViewRenameOperations.java:92` | broadCatch | renameView dispatcher failure → IcebergExceptionMapper wire mapping | B10 | keep |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/server/web/filter/BaseMetadataAuthorizationMethodInterceptor.java:152` | broadCatch | checkCurrentUser unexpected failure → 500 (flattens NoSuchMetalake/NoSuchUser and store-down into RuntimeException) | B4 | NOT_FOUND |
| `iceberg/iceberg-rest-server/src/main/java/org/apache/gravitino/server/web/filter/BaseMetadataAuthorizationMethodInterceptor.java:199` | broadCatch | authorization pipeline failure; propagate-list mapped via IcebergExceptionMapper, residual flattened to 500 | B4 | keep |

## aux-lance-lineage (31 sites; raw rg hits: 31)

> Raw counts: 5 `throw new RuntimeException` + 26 `catch (Exception` = 31 across lance/lance-common/src/main, lance/lance-rest-server/src/main, lineage/src/main (main sources only; no test paths matched). Key structural findings: (1) All 15 Lance REST resource catches (LanceTableOperations x9, LanceNamespaceOperations x6) are correct dispatcher catches, but they feed lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/LanceExceptionMapper.java whose else branch (toLanceNamespaceException, ~line 85-88) flattens everything unrecognized — including GravitinoClient RESTException/connect failures when the backing Gravitino server is down — into InternalException(500); it needs an UNAVAILABLE/INTERNAL_DEP(502) branch for backend-connectivity exceptions. That mapper contains no raw rg hit itself, so it is not in sites[] but is the highest-value fix in this scope. (2) The two TODO sites (LineageHttpSink.java:69-70) are a shutdown-cleanup close() that rethrows as RuntimeException; the correct fix is log-and-suppress (like GravitinoLanceNamespaceWrapper.close does), for which no canonical wire code applies. (3) LineageOperations.java:72 flattens all dispatch failures to 500 with no classification step; residual should stay INTERNAL_BUG(500) but IllegalArgumentException-style bad-event failures deserve INVALID_ARGUMENT and doAs auth failures UNAUTHENTICATED. (4) LineageHttpSink.java:60 sits on the sink/listener domain (B5); the RuntimeException propagates into the dispatcher's async executor — per-sink isolation should catch it there. (5) LanceRESTService.java:150/152 is a startup config/classpath failure (bad namespace backend) better signaled as FAILED_PRECONDITION-style config error than bare RuntimeException, though fail-fast at boot is acceptable.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `lance/lance-common/src/main/java/org/apache/gravitino/lance/common/ops/gravitino/GravitinoLanceNamespaceWrapper.java:111` | broadCatch | GravitinoClient.close() failure during wrapper shutdown; log-and-swallow | B1 | keep |
| `lance/lance-common/src/main/java/org/apache/gravitino/lance/common/ops/gravitino/LanceDataTypeConverter.java:119` | broadCatch | Jackson deser of stored external-type catalog string into Arrow Field | internal | INTERNAL_BUG(500) |
| `lance/lance-common/src/main/java/org/apache/gravitino/lance/common/ops/gravitino/LanceDataTypeConverter.java:120` | wrap | rethrows external-type parse failure (corrupt stored type metadata) as RuntimeException | internal | INTERNAL_BUG(500) |
| `lance/lance-common/src/main/java/org/apache/gravitino/lance/common/ops/gravitino/LanceDataTypeConverter.java:321` | broadCatch | Jackson serialization of Arrow field to JSON for ExternalType storage | internal | INTERNAL_BUG(500) |
| `lance/lance-common/src/main/java/org/apache/gravitino/lance/common/ops/gravitino/LanceDataTypeConverter.java:322` | wrap | rethrows Arrow-field JSON serialization failure as RuntimeException | internal | INTERNAL_BUG(500) |
| `lance/lance-common/src/main/java/org/apache/gravitino/lance/common/utils/ArrowUtils.java:54` | broadCatch | Arrow allocator/IPC writer failure generating empty IPC stream; rewrapped as IOException with cause | internal | INTERNAL_BUG(500) |
| `lance/lance-common/src/main/java/org/apache/gravitino/lance/common/utils/ArrowUtils.java:65` | broadCatch | parse of client-supplied Arrow IPC bytes; reclassified to IllegalArgumentException which maps to 400 | internal | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/LanceRESTService.java:150` | broadCatch | reflective construction of NamespaceWrapper backend impl at service init (NoSuchMethod/InvocationTarget) | B11 | FAILED_PRECONDITION |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/LanceRESTService.java:152` | wrap | rethrows backend-wrapper load failure as generic RuntimeException, losing config-error signal | B11 | FAILED_PRECONDITION |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/server/GravitinoLanceRESTServer.java:78` | broadCatch | server start failure; logged then System.exit(-1) fail-fast | B11 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/server/GravitinoLanceRESTServer.java:93` | broadCatch | shutdown-hook sleep/cleanup failure; logged, deliberate shutdown isolation | B11 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/server/GravitinoLanceRESTServer.java:103` | broadCatch | lanceRESTService.stop() failure at shutdown; logged and suppressed | B11 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceNamespaceOperations.java:93` | broadCatch | list-namespaces op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceNamespaceOperations.java:109` | broadCatch | describe-namespace op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceNamespaceOperations.java:132` | broadCatch | create-namespace op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceNamespaceOperations.java:155` | broadCatch | drop-namespace op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceNamespaceOperations.java:170` | broadCatch | namespace-exists op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceNamespaceOperations.java:188` | broadCatch | list-tables op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:109` | broadCatch | describe-table op incl. Gravitino backend call failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:149` | broadCatch | create-table op incl. header/property deser and Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:178` | broadCatch | declare-table op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:207` | broadCatch | register-table op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:226` | broadCatch | deregister-table op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:248` | broadCatch | table-exists op incl. deliberate TableNotFoundException control flow; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:266` | broadCatch | drop-table op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:288` | broadCatch | drop-columns alter op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lance/lance-rest-server/src/main/java/org/apache/gravitino/lance/service/rest/LanceTableOperations.java:311` | broadCatch | alter-columns (rename) op incl. Gravitino backend failures; dispatches to LanceExceptionMapper | B10 | keep |
| `lineage/src/main/java/org/apache/gravitino/lineage/sink/LineageHttpSink.java:60` | wrap | Jackson JsonProcessingException converting server RunEvent to client RunEvent before HTTP emit | B5 | INTERNAL_BUG(500) |
| `lineage/src/main/java/org/apache/gravitino/lineage/sink/LineageHttpSink.java:69` | broadCatch | OpenLineage HTTP client close() failure during sink shutdown | B11 | TODO |
| `lineage/src/main/java/org/apache/gravitino/lineage/sink/LineageHttpSink.java:70` | wrap | rethrows client close() failure as RuntimeException from close(), can abort shutdown path | B11 | TODO |
| `lineage/src/main/java/org/apache/gravitino/lineage/source/rest/LineageOperations.java:72` | broadCatch | doAs principal handling + lineage dispatch failures (auth, bad event, sink bugs) all flattened to Utils.internalError 500 | B10 | INTERNAL_BUG(500) |

## catalogs-hive-glue-jdbc (106 sites; raw rg hits: 106)

> Raw rg over the scope dirs returned 129 hits total; 23 were under catalogs-contrib/**/src/test/** (HologresService, OceanBaseService, ClickHouseService, Catalog*IT classes) and are excluded per the MAIN-sources-only rule, leaving totalRawHits=106 (48 'throw new RuntimeException' + 58 'catch (Exception'). One discrepancy vs raw rg line numbers: the rg hit reported at HiveViewCatalogOperations.java:93 is the throw statement continuing from the catch at line 92; recorded as line 92-93 site (listed at 92... actually listed at line 92? No - listed in sites[] at line 92 for the listViews wrap; rg reported 93 because the 'throw new RuntimeException' text starts there — same single site either way). Key patterns: (1) HiveShimV2/V3 and HiveClientFactory route broad catches through HiveExceptionConverter (classify-then-preserve: AlreadyExists/NoSuch/ConnectionFailed typed, remainder falls through to GravitinoRuntimeException) — those catches are marked 'keep', but the converter's fallthrough at HiveExceptionConverter.java:199 (new GravitinoRuntimeException) still flattens thrift transport errors to 500 and is the single highest-leverage fix point for the Hive family. (2) The dominant anti-pattern in catalog-hive is 'catch (InterruptedException e) throw new RuntimeException(e)' around clientPool.run — 20 sites, none restore Thread.interrupt(); proposed UNAVAILABLE. (3) HiveCatalogOperations/HiveViewCatalogOperations then add a trailing 'catch (Exception) -> bare RuntimeException' that re-flattens even the typed exceptions the shim converter produced (e.g. ConnectionFailedException gets wrapped in createSchema), defeating the converter — proposed INTERNAL_DEP(502) with cause preservation. (4) catalog-glue's GlueTableOperations wraps all residual GlueException in RuntimeException instead of using the existing GlueExceptionConverter used by GlueCatalogOperations. (5) Dyn* classes are vendored Iceberg-style reflection utils (internal, INTERNAL_BUG). (6) DataSourceUtils.java:104 drops the cause entirely when rethrowing ('Unable to decode JDBC URL' without e).

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `catalogs-contrib/catalog-jdbc-clickhouse/src/main/java/org/apache/gravitino/catalog/clickhouse/converter/ClickHouseColumnDefaultValueConverter.java:169` | broadCatch | column default-value literal parse, falls back to UnparsedExpression (deliberate lenient parse) | B1 | keep |
| `catalogs-contrib/catalog-jdbc-hologres/src/main/java/org/apache/gravitino/catalog/hologres/converter/HologresColumnDefaultValueConverter.java:81` | broadCatch | column default-value literal parse, falls back to UnparsedExpression (deliberate lenient parse) | B1 | keep |
| `catalogs/catalog-glue/src/main/java/org/apache/gravitino/catalog/glue/GlueCatalogOperations.java:381` | broadCatch | Iceberg metadata enrichment during loadTable, warn + degrade to base Glue table | B1 | keep |
| `catalogs/catalog-glue/src/main/java/org/apache/gravitino/catalog/glue/GlueTableOperations.java:102` | wrap | GlueException listing partition names (throttling/auth/unavailable all flattened) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-glue/src/main/java/org/apache/gravitino/catalog/glue/GlueTableOperations.java:124` | wrap | GlueException listing partitions | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-glue/src/main/java/org/apache/gravitino/catalog/glue/GlueTableOperations.java:144` | wrap | GlueException on getPartition (non-NotFound residual) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-glue/src/main/java/org/apache/gravitino/catalog/glue/GlueTableOperations.java:184` | wrap | GlueException on createPartition (non-AlreadyExists residual) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-glue/src/main/java/org/apache/gravitino/catalog/glue/GlueTableOperations.java:213` | wrap | GlueException on deletePartition (non-NotFound residual) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:221` | wrap | clientPool.run InterruptedException during listSchemas | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:264` | broadCatch | createSchema residual HMS failures (incl. already-converted Gravitino exceptions like ConnectionFailed) | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:265` | wrap | createSchema residual failure re-flattened to bare RuntimeException | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:286` | wrap | clientPool.run InterruptedException during loadSchema | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:335` | wrap | clientPool.run InterruptedException during alterSchema | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:361` | wrap | clientPool.run InterruptedException during dropSchema | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:412` | wrap | clientPool.run InterruptedException during listTables | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:497` | wrap | clientPool.run InterruptedException during loadHiveTable | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:669` | wrap | clientPool.run InterruptedException during createTable | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:767` | wrap | clientPool.run InterruptedException during alterTable | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:1036` | broadCatch | testConnection getAllDatabases failure, converted to ConnectionFailedException (deliberate contract) | B2 | keep |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveCatalogOperations.java:1066` | wrap | clientPool.run InterruptedException during dropHiveTable | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveTableOperations.java:59` | wrap | clientPool.run InterruptedException during listPartitionNames | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveTableOperations.java:73` | wrap | clientPool.run InterruptedException during listPartitions | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveTableOperations.java:84` | wrap | clientPool.run InterruptedException during getPartition | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveTableOperations.java:131` | wrap | clientPool.run InterruptedException during addPartition (bare, no message) | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveTableOperations.java:175` | wrap | clientPool.run InterruptedException during dropPartition | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:92` | wrap | clientPool.run InterruptedException during listViews | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:160` | wrap | clientPool.run InterruptedException during createView | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:161` | broadCatch | createView residual HMS failures after typed rethrows | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:162` | wrap | createView residual failure flattened to RuntimeException | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:267` | wrap | clientPool.run InterruptedException during alterView | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:268` | broadCatch | alterView residual HMS failures after typed rethrows | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:269` | wrap | alterView residual failure flattened to RuntimeException | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:314` | wrap | clientPool.run InterruptedException during dropView | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:315` | broadCatch | dropView residual HMS failures | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:316` | wrap | dropView residual failure flattened to RuntimeException | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:352` | wrap | clientPool.run InterruptedException during loadView | B1 | UNAVAILABLE |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:353` | broadCatch | loadView residual HMS failures | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-hive/src/main/java/org/apache/gravitino/catalog/hive/HiveViewCatalogOperations.java:354` | wrap | loadView residual failure flattened to RuntimeException | B2 | INTERNAL_DEP(502) |
| `catalogs/catalog-jdbc-common/src/main/java/org/apache/gravitino/catalog/jdbc/MySQLProtocolCompatibleCatalogOperations.java:57` | wrap | unsupported MySQL JDBC driver major version (<8) | B2 | FAILED_PRECONDITION |
| `catalogs/catalog-jdbc-common/src/main/java/org/apache/gravitino/catalog/jdbc/MySQLProtocolCompatibleCatalogOperations.java:74` | broadCatch | AbandonedConnectionCleanupThread shutdown on close, warn + swallow (deliberate cleanup isolation) | internal | keep |
| `catalogs/catalog-jdbc-common/src/main/java/org/apache/gravitino/catalog/jdbc/utils/DataSourceUtils.java:61` | broadCatch | DBCP datasource creation from user JDBC config (bad driver/url/props) | B1 | INVALID_ARGUMENT |
| `catalogs/catalog-jdbc-common/src/main/java/org/apache/gravitino/catalog/jdbc/utils/DataSourceUtils.java:104` | broadCatch | URLDecoder.decode of user JDBC URL (cause dropped in rethrow) | B1 | INVALID_ARGUMENT |
| `catalogs/catalog-jdbc-doris/src/main/java/org/apache/gravitino/catalog/doris/operation/DorisTableOperations.java:207` | broadCatch | JDBC 'show backends' query during createTable property defaulting | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-jdbc-doris/src/main/java/org/apache/gravitino/catalog/doris/operation/DorisTableOperations.java:208` | wrap | 'show backends' failure flattened to RuntimeException | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-jdbc-doris/src/main/java/org/apache/gravitino/catalog/doris/utils/DorisUtils.java:121` | broadCatch | parse of Doris SHOW CREATE TABLE partition clause, warn + Optional.empty (best-effort degrade) | B1 | keep |
| `catalogs/catalog-jdbc-doris/src/main/java/org/apache/gravitino/catalog/doris/utils/DorisUtils.java:220` | wrap | distribution-clause regex did not match Doris SHOW CREATE TABLE output | B1 | INTERNAL_BUG(500) |
| `catalogs/catalog-jdbc-postgresql/src/main/java/org/apache/gravitino/catalog/postgresql/converter/PostgreSqlColumnDefaultValueConverter.java:67` | broadCatch | column default-value literal parse, falls back to UnparsedExpression (deliberate lenient parse) | B1 | keep |
| `catalogs/catalog-jdbc-starrocks/src/main/java/org/apache/gravitino/catalog/starrocks/utils/StarRocksUtils.java:156` | broadCatch | parse of StarRocks SHOW CREATE TABLE partition clause, warn + Optional.empty (best-effort degrade) | B1 | keep |
| `catalogs/catalog-jdbc-starrocks/src/main/java/org/apache/gravitino/catalog/starrocks/utils/StarRocksUtils.java:193` | wrap | distribution-clause regex did not match StarRocks SHOW CREATE TABLE output | B1 | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/HiveClientPool.java:54` | broadCatch | pool newClient HMS connect, wrapped into GravitinoRuntimeException | B1 | UNAVAILABLE |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientClassLoader.java:143` | broadCatch | jar Path→URL conversion while building isolated classloader | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:83` | broadCatch | factory ctor: config build + kerberos init, routed to HiveExceptionConverter (classify-then-preserve) | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:107` | broadCatch | HMS connect with cached classloader/Hive version | B1 | UNAVAILABLE |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:112` | wrap | HMS connect failure flattened to RuntimeException | B1 | UNAVAILABLE |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:154` | broadCatch | Hive2 fallback attempt failure; logs and rethrows original typed GravitinoRuntimeException | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:158` | broadCatch | Hive3 connect failure, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:226` | broadCatch | createHiveClientInternal (proxy/kerberos/plain client creation), routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:248` | broadCatch | Kerberos client init/login (KDC, keytab, config) | B1 | INTERNAL_DEP(502) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:249` | wrap | Kerberos init failure flattened to RuntimeException | B1 | INTERNAL_DEP(502) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientFactory.java:267` | broadCatch | factory close (kerberos client + classloader), warn + swallow (deliberate cleanup isolation) | internal | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientImpl.java:182` | broadCatch | shim.close() failure on client close | B1 | INTERNAL_DEP(502) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientImpl.java:183` | wrap | shim.close() failure flattened to RuntimeException | B1 | INTERNAL_DEP(502) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientImpl.java:191` | broadCatch | UGI.getCurrentUser IOException (local Hadoop security context) | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveClientImpl.java:192` | wrap | UGI.getCurrentUser failure flattened to RuntimeException | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:65` | broadCatch | reflective RetryingMetaStoreClient creation, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:76` | broadCatch | HMS thrift createDatabase, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:86` | broadCatch | HMS thrift getDatabase, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:96` | broadCatch | HMS thrift alterDatabase, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:105` | broadCatch | HMS thrift dropDatabase, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:114` | broadCatch | HMS thrift getAllTables, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:124` | broadCatch | HMS thrift getTables by type, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:134` | broadCatch | HMS thrift listTableNamesByFilter, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:144` | broadCatch | HMS thrift getTable + conversion, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:155` | broadCatch | HMS thrift alter_table, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:169` | broadCatch | HMS thrift dropTable, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:179` | broadCatch | HMS thrift createTable, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:189` | broadCatch | HMS thrift listPartitionNames, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:200` | broadCatch | HMS thrift listPartitions, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:213` | broadCatch | HMS thrift listPartitions with filter values, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:225` | broadCatch | HMS thrift getPartition, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:238` | broadCatch | HMS thrift add_partition, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:254` | broadCatch | HMS thrift dropPartition, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:264` | broadCatch | HMS thrift getDelegationToken, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV2.java:277` | broadCatch | HMS thrift getTableObjectsByName, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV3.java:162` | broadCatch | Hive3 reflective method/class setup in shim ctor, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV3.java:180` | broadCatch | reflective RetryingMetaStoreClient creation, routed to HiveExceptionConverter | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV3.java:484` | broadCatch | reflective HMS method invoke, routed to HiveExceptionConverter with target context | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/HiveShimV3.java:500` | broadCatch | reflective ctor newInstance, routed to HiveExceptionConverter with target context | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/ProxyHiveClientImpl.java:69` | wrap | ugi.doAs Kerberos HiveClient creation IOException/InterruptedException | B1 | INTERNAL_DEP(502) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/Util.java:55` | broadCatch | Hadoop Configuration build from user catalog properties (hive.config.resources paths) | internal | INVALID_ARGUMENT |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/Util.java:56` | wrap | config build failure flattened to RuntimeException | internal | INVALID_ARGUMENT |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/client/Util.java:90` | broadCatch | metastore URI host DNS resolution, warn + fall back to original URI (deliberate best-effort) | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/converter/HiveDatabaseConverter.java:47` | broadCatch | reflective getCatalogName probe, absent on Hive2 (deliberate version-compat swallow) | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/converter/HiveTableConverter.java:86` | broadCatch | reflective getCatName probe, absent on Hive2 (deliberate version-compat swallow) | B1 | keep |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynConstructors.java:125` | wrap | non-Exception cause of reflective ctor InvocationTargetException | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynConstructors.java:133` | broadCatch | checked exception from newInstanceChecked (checked-to-unchecked shim) | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynConstructors.java:135` | wrap | checked ctor exception flattened to RuntimeException | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynFields.java:59` | wrap | IllegalAccessException on reflective field get | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynFields.java:68` | wrap | IllegalAccessException on reflective field set | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynFields.java:378` | wrap | no matching field among candidates (Hive version mismatch) | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynMethods.java:103` | wrap | non-Exception cause of reflective invoke InvocationTargetException | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynMethods.java:111` | broadCatch | checked exception from invokeChecked (checked-to-unchecked shim) | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynMethods.java:113` | wrap | checked invoke exception flattened to RuntimeException | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/dyn/DynMethods.java:446` | wrap | no matching method among candidates (Hive version mismatch) | internal | INTERNAL_BUG(500) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/kerberos/HmsKerberosClient.java:110` | broadCatch | proxy-user creation + HMS delegation token fetch | B1 | INTERNAL_DEP(502) |
| `catalogs/hive-metastore-common/src/main/java/org/apache/gravitino/hive/kerberos/HmsKerberosClient.java:111` | wrap | proxy-user/delegation-token failure flattened to RuntimeException | B1 | INTERNAL_DEP(502) |

## catalogs-lakehouse-misc (97 sites; raw rg hits: 97)

> Raw hit breakdown: 71 `throw new RuntimeException` + 26 `catch (Exception` = 97, main sources only (no test paths matched the scope globs). Per-module: kafka 15+3, model 14+0, hudi 4+2, iceberg 5+8 (incl. IcebergView), fileset 19+0, generic/lance 9+7, paimon 5+6. Patterns: (1) the dominant flattening is checked IOException from the core EntityStore (B3) and backend SDK residuals (B1) both collapsing to bare RuntimeException -> HTTP 500, indistinguishable from bugs; proposed residual is INTERNAL_DEP(502). (2) Two genuine user-conflict flattenings: ModelCatalogOperations.java:333 and FilesetCatalogOperations.java:656 turn rename-target-exists into RuntimeException instead of ALREADY_EXISTS. (3) Kafka doPartitionCountIncrement/doAlterTopicConfig broad catches (539/553) flatten InvalidPartitionsException/InvalidConfigurationException (INVALID_ARGUMENT-class user errors) into 500s; note KafkaCatalogOperations.java:551 catch of UnknownTopicOrPartitionException is dead since the future wraps it in ExecutionException. (4) InterruptedException wraps (kafka, hudi HMS ops, fileset FS-future) classified ABORTED; none besides fileset:1421 restore the interrupt flag. (5) Deliberate keeps: testConnection->ConnectionFailedException catches (kafka 196, hudi 106, iceberg 646, paimon 168), close() log-warn isolation (hudi 117, iceberg 148), IcebergView metadata-degradation catches (151/172), Lance load-path degradation (218). Paimon close (563/564) rethrows instead of logging - inconsistent with the other connectors, marked TODO because the right fix is swallow-and-log rather than a taxonomy code. (6) GenericCatalogOperations.java:351-364 unwraps guava cache ExecutionException but a direct RuntimeException from the loader yields t=null and a cause-less RuntimeException at 362, losing the original stack.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:324` | wrap | store.exists/store.list filesets IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:346` | wrap | store.get fileset IOException (message has stray %s) | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:393` | wrap | fs.listStatus IOException in listFiles (method already declares throws IOException) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:421` | wrap | store.exists fileset IOException in create precheck | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:431` | wrap | store.get schema IOException in create | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:510` | wrap | fs.mkdirs returned false for fileset storage location | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:534` | wrap | IOException formalizing/creating fileset storage locations | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:568` | wrap | store.put fileset entity IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:631` | wrap | store.exists IOException in alterFileset precheck (message says 'load') | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:651` | wrap | store.update fileset IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:656` | wrap | AlreadyExistsException on fileset rename to existing name (user conflict flattened to 500) | B3 | ALREADY_EXISTS |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:713` | wrap | IOException in dropFileset (store get/delete plus captured fs.delete of managed locations) | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:737` | wrap | store.exists schema IOException in createSchema precheck | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:759` | wrap | fs.mkdirs returned false for schema location | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:781` | wrap | IOException creating schema location on backend FS | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:798` | wrap | store.exists schema IOException in alterSchema precheck | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:935` | wrap | IOException in dropSchema (store list/delete of fileset entities) | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:1080` | wrap | fs.exists/getFileStatus IOException checking catalog location at catalog init | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-fileset/src/main/java/org/apache/gravitino/catalog/fileset/FilesetCatalogOperations.java:1426` | wrap | InterruptedException getting FileSystem (server shutdown / catalog drop) | B1 | ABORTED |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:160` | wrap | AdminClient.create KafkaException (non-ConfigException residual) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:177` | wrap | Kafka listTopics ExecutionException (broker error) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:183` | wrap | listTopics InterruptedException | B1 | ABORTED |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:196` | broadCatch | testConnection listTopics failure, rethrown as ConnectionFailedException | B1 | keep |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:231` | wrap | describeTopics/describeConfigs ExecutionException residual (non UnknownTopic) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:234` | wrap | loadTopic InterruptedException | B1 | ABORTED |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:310` | wrap | createTopics ExecutionException residual (exists/invalid cases already split out) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:313` | wrap | createTopic InterruptedException | B1 | ABORTED |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:386` | wrap | deleteTopics ExecutionException residual | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:389` | wrap | dropTopic InterruptedException | B1 | ABORTED |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:402` | wrap | store.list schemas IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:431` | wrap | store.get schema IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:539` | broadCatch | createPartitions failure; flattens InvalidPartitionsException (user error -> INVALID_ARGUMENT) with broker faults | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:540` | wrap | createPartitions failure residual | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:553` | broadCatch | incrementalAlterConfigs failure; flattens InvalidConfigurationException (user error) with broker faults; UnknownTopic catch above is dead (arrives wrapped in ExecutionException) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:554` | wrap | incrementalAlterConfigs failure residual | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:595` | wrap | store.exists default-schema IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-kafka/src/main/java/org/apache/gravitino/catalog/kafka/KafkaCatalogOperations.java:622` | wrap | store.put default schema IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/generic/GenericCatalogOperations.java:351` | broadCatch | guava cache loader failure (store.get table format); unwraps cause, but null-cause RuntimeExceptions fall to else branch with t=null | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/generic/GenericCatalogOperations.java:359` | wrap | store.get IOException via table-format cache loader | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/generic/GenericCatalogOperations.java:362` | wrap | unexpected table-ops resolution failure (cause may be null, losing original exception) | internal | INTERNAL_BUG(500) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:218` | broadCatch | Lance dataset open/schema read failure on loadTable; deliberately degrades to stored metadata with LOG.debug | B1 | keep |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:348` | broadCatch | purgeTable residual (entity-store purge + Lance dataset drop) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:349` | wrap | purge Lance dataset failure | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:382` | broadCatch | dropTable residual (entity-store drop + Lance dataset drop) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:383` | wrap | drop Lance dataset failure | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:394` | broadCatch | Dataset.drop native failure; string-matches 'Not found:' to tolerate already-deleted, rethrows rest | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:401` | wrap | Dataset.drop failure (non not-found) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:478` | broadCatch | Dataset.write CREATE failure residual (exists/invalid-arg split out above) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:479` | wrap | create Lance dataset failure | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:559` | wrap | store.update IOException during schema repair (non-CAS-conflict) | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:670` | wrap | store.update IOException recording empty-dataset version sentinel | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:794` | broadCatch | Lance dataset alteration checked failure (RuntimeException rethrown as-is above) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java:795` | wrap | Lance dataset alteration failure | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-hudi/src/main/java/org/apache/gravitino/catalog/lakehouse/hudi/HudiCatalogOperations.java:106` | broadCatch | testConnection listSchemas failure, rethrown as ConnectionFailedException | B1 | keep |
| `catalogs/catalog-lakehouse-hudi/src/main/java/org/apache/gravitino/catalog/lakehouse/hudi/HudiCatalogOperations.java:117` | broadCatch | backend ops close() failure, log-warn cleanup isolation | B1 | keep |
| `catalogs/catalog-lakehouse-hudi/src/main/java/org/apache/gravitino/catalog/lakehouse/hudi/backend/hms/HudiHMSBackendOps.java:103` | wrap | HMS getDatabase clientPool InterruptedException, bare rethrow | B1 | ABORTED |
| `catalogs/catalog-lakehouse-hudi/src/main/java/org/apache/gravitino/catalog/lakehouse/hudi/backend/hms/HudiHMSBackendOps.java:116` | wrap | HMS getAllDatabases InterruptedException, bare rethrow | B1 | ABORTED |
| `catalogs/catalog-lakehouse-hudi/src/main/java/org/apache/gravitino/catalog/lakehouse/hudi/backend/hms/HudiHMSBackendOps.java:155` | wrap | HMS getAllTables/getTableObjectsByName InterruptedException, bare rethrow | B1 | ABORTED |
| `catalogs/catalog-lakehouse-hudi/src/main/java/org/apache/gravitino/catalog/lakehouse/hudi/backend/hms/HudiHMSBackendOps.java:174` | wrap | HMS getTable InterruptedException, bare rethrow | B1 | ABORTED |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:148` | broadCatch | Iceberg catalog close/classloader cleanup failure, log-warn isolation | B1 | keep |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:241` | broadCatch | createNamespace residual backend failure (exists/no-such split out above) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:242` | wrap | createNamespace residual, bare RuntimeException(e) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:337` | broadCatch | updateNamespaceProperties residual backend failure | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:338` | wrap | alterSchema residual, bare RuntimeException(e) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:364` | broadCatch | dropNamespace residual backend failure (not-empty/no-such split out above) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:365` | wrap | dropSchema residual, bare RuntimeException(e) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:623` | broadCatch | purgeTable residual backend failure (no-such-table split out above) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:624` | wrap | purgeTable residual, bare RuntimeException(e) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergCatalogOperations.java:646` | broadCatch | testConnection listNamespace failure, rethrown as ConnectionFailedException | B1 | keep |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergView.java:151` | broadCatch | Iceberg view metadata schema->columns conversion failure, degrades to empty columns with log-warn | B1 | keep |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/IcebergView.java:172` | broadCatch | Iceberg view SQL-representation extraction failure, degrades to empty with log-warn | B1 | keep |
| `catalogs/catalog-lakehouse-iceberg/src/main/java/org/apache/gravitino/catalog/lakehouse/iceberg/ops/IcebergCatalogWrapperHelper.java:259` | wrap | RenameTable routed through tableUpdate interface (internal invariant violation) | internal | INTERNAL_BUG(500) |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:168` | broadCatch | testConnection listDatabases failure, rethrown as ConnectionFailedException | B1 | keep |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:202` | broadCatch | Paimon createDatabase residual backend failure (already-exists split out above) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:203` | wrap | createDatabase residual, bare RuntimeException(e) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:266` | broadCatch | Paimon dropDatabase residual backend failure (not-exist/not-empty split out above) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:267` | wrap | dropDatabase residual, bare RuntimeException(e) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:563` | broadCatch | Paimon catalog close/classloader cleanup failure rethrown; should be log-and-continue like Iceberg/Hudi close | B1 | TODO |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:564` | wrap | close() failure, bare RuntimeException(e); recommended fix is swallow-and-log, not a taxonomy code | B1 | TODO |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:681` | broadCatch | Paimon alterTable residual backend failure (no-such-table/column split out above) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/PaimonCatalogOperations.java:682` | wrap | alterTable residual, bare RuntimeException(e) | B1 | INTERNAL_DEP(502) |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/utils/CatalogUtils.java:90` | broadCatch | Kerberos login + keytab fetch + backend catalog load; flattens bad config with KDC/network faults | B1 | FAILED_PRECONDITION |
| `catalogs/catalog-lakehouse-paimon/src/main/java/org/apache/gravitino/catalog/lakehouse/paimon/utils/CatalogUtils.java:91` | wrap | kerberos login failure for Paimon backend | B1 | FAILED_PRECONDITION |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:109` | wrap | store.list models IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:124` | wrap | store.get model IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:154` | wrap | store.put model IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:171` | wrap | store.delete model IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:188` | wrap | store.list model versions IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:205` | wrap | store.list model version infos IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:262` | wrap | store.put model version IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:314` | wrap | store.exists IOException in alterModel precheck | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:328` | wrap | store.update model IOException (message misleadingly says 'load') | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:333` | wrap | EntityAlreadyExists on model rename to existing name (user conflict flattened to 500) | B3 | ALREADY_EXISTS |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:424` | wrap | store.exists IOException in alterModelVersion precheck | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:438` | wrap | store.update model version IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:554` | wrap | store.get model version IOException | B3 | INTERNAL_DEP(502) |
| `catalogs/catalog-model/src/main/java/org/apache/gravitino/catalog/model/ModelCatalogOperations.java:605` | wrap | store.delete model version IOException | B3 | INTERNAL_DEP(502) |

## clients-java (183 sites; raw rg hits: 183)

> Raw hit count is exact: 26 "throw new RuntimeException" + 157 "catch (Exception" = 183, all in main sources (no test paths matched the globs). Verified per-module: CLI 136 catches, client-java 5 catches, filesystem-hadoop3 16 catches. Key patterns: (1) ErrorHandlers.java has 20 identical wire-boundary sites where the server's INTERNAL_ERROR_CODE (500) error response is rethrown as a bare untyped RuntimeException — the single biggest flattening cluster; a typed GravitinoServerException carrying the canonical code would let callers distinguish INTERNAL_DEP(502) from INTERNAL_BUG(500) once the server splits them. (2) The 130+ CLI sites are one copy-pasted terminal pattern: typed catches for NOT_FOUND/ALREADY_EXISTS cases, then `catch (Exception exp) { exitWithError(exp.getMessage()); }` — flattens network-down vs server-bug vs NPE into a message-only exit (and prints "null" when getMessage() is null); marked UNKNOWN as the residue class, best fixed once in the shared Command base rather than per-site. (3) GVFS's 11 per-operation catch(Exception)->hook.onXxxFailure sites are a deliberate extension point whose default (NoOpHook) rethrows the exception intact — marked keep. (4) BaseGVFSOperations:997/998 flattens Gravitino-server credential-vending failures (NoSuchFileset, network, auth) into bare RuntimeException inside a Hadoop FS op — worst GVFS site; marked UNAVAILABLE for the dominant dependency-call failure mode, though it should classify-then-preserve. (5) keep sites: parseResponse fallbacks to ErrorResponse.unknownError (raw JSON preserved), catch-and-rethrow with state reset (HTTPClient:449), close()-path swallows, Main.exit test seam, hook delegation.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `clients/cli/src/main/java/org/apache/gravitino/cli/AreYouSure.java:45` | broadCatch | stdin Scanner read failure in Y/N confirmation prompt -> print + exit(-1) | B8 | keep |
| `clients/cli/src/main/java/org/apache/gravitino/cli/Main.java:87` | wrap | test-mode exit signal (RuntimeException instead of System.exit for testability) | internal | keep |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/AddColumn.java:133` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/AddRoleToGroup.java:69` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/AddRoleToUser.java:69` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CatalogAudit.java:61` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CatalogDetails.java:63` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ClientVersion.java:44` | broadCatch | client version lookup residue -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ColumnAudit.java:85` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateCatalog.java:83` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateFileset.java:105` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateGroup.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateMetalake.java:55` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateRole.java:62` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateSchema.java:70` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateTable.java:93` | broadCatch | client build / NameIdentifier init residue -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateTable.java:100` | broadCatch | CSV column-file read/parse failure (user-supplied file) -> exitWithError(msg) | B8 | INVALID_ARGUMENT |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateTable.java:112` | broadCatch | createTable client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateTag.java:78` | broadCatch | single-tag create client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateTag.java:97` | broadCatch | multi-tag create client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateTopic.java:83` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/CreateUser.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteCatalog.java:72` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteColumn.java:91` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteFileset.java:86` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteGroup.java:69` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteMetalake.java:64` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteModel.java:84` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteRole.java:75` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteSchema.java:76` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteTable.java:86` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteTag.java:88` | broadCatch | multi-tag delete client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteTag.java:111` | broadCatch | single-tag delete client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteTopic.java:86` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/DeleteUser.java:74` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/FilesetDetails.java:79` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/GrantPrivilegesToRole.java:93` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/GroupAudit.java:61` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/GroupDetails.java:65` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/LinkModel.java:107` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListAllTags.java:54` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListCatalogProperties.java:62` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListCatalogs.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListColumns.java:64` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListEntityTags.java:112` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListFilesetProperties.java:79` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListFilesets.java:74` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListGroups.java:57` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListIndexes.java:59` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListMetalakeProperties.java:55` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListMetalakes.java:50` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListModel.java:72` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListRoles.java:59` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListSchema.java:64` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListSchemaProperties.java:70` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListTableProperties.java:79` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListTables.java:64` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListTagProperties.java:63` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListTopicProperties.java:80` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListTopics.java:68` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ListUsers.java:57` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ManageCatalog.java:90` | broadCatch | enableCatalog client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ManageCatalog.java:106` | broadCatch | disableCatalog client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ManageMetalake.java:87` | broadCatch | enableMetalake client/REST residue -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ManageMetalake.java:101` | broadCatch | disableMetalake client/REST residue -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/MetalakeAudit.java:52` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/MetalakeDetails.java:53` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ModelAudit.java:80` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ModelDetails.java:80` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/OwnerDetails.java:88` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RegisterModel.java:96` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveAllRoles.java:79` | broadCatch | revoke-all-roles-from-group client/REST residue -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveAllRoles.java:98` | broadCatch | revoke-all-roles-from-user client/REST residue -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveAllTags.java:151` | broadCatch | bulk tag-removal client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveCatalogProperty.java:65` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveFilesetProperty.java:87` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveMetalakeProperty.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveModelProperty.java:86` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveModelVersionProperty.java:97` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveRoleFromGroup.java:72` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveRoleFromUser.java:69` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveSchemaProperty.java:73` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveTableProperty.java:87` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveTagProperty.java:65` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RemoveTopicProperty.java:88` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RevokeAllPrivileges.java:88` | broadCatch | bulk privilege-revoke client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RevokePrivilegesFromRole.java:93` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RoleAudit.java:61` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/RoleDetails.java:63` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SchemaAudit.java:68` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SchemaDetails.java:69` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/ServerVersion.java:44` | broadCatch | serverVersion REST call residue -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetCatalogProperty.java:70` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetFilesetProperty.java:92` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetMetalakeProperty.java:63` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetModelProperty.java:89` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetModelVersionProperty.java:100` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetOwner.java:101` | broadCatch | setOwner client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetOwner.java:112` | wrap | CLI arg-validation abort signal: bare messageless RuntimeException after printing UNKNOWN_ENTITY | B8 | INVALID_ARGUMENT |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetSchemaProperty.java:82` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetTableProperty.java:92` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetTagProperty.java:70` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/SetTopicProperty.java:94` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TableAudit.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TableCommand.java:73` | broadCatch | catalog load residue in shared table-command base -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TableDetails.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TableDistribution.java:59` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TablePartition.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TableSortOrder.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TagDetails.java:62` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TagEntity.java:130` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/TopicDetails.java:79` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UntagEntity.java:127` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateCatalogComment.java:66` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateCatalogName.java:65` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateColumnAutoIncrement.java:98` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateColumnComment.java:98` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateColumnDatatype.java:101` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateColumnDefault.java:106` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateColumnName.java:98` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateColumnNullability.java:98` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateColumnPosition.java:101` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateFilesetComment.java:88` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateFilesetName.java:87` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateMetalakeComment.java:58` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateMetalakeName.java:67` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateModelComment.java:89` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateModelName.java:89` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateModelVersionAliases.java:106` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateModelVersionComment.java:102` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateModelVersionUri.java:102` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateTableComment.java:88` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateTableName.java:88` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateTagComment.java:65` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateTagName.java:65` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UpdateTopicComment.java:88` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UserAudit.java:61` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/commands/UserDetails.java:65` | broadCatch | client/REST residue after typed catches -> exitWithError(msg) | B8 | UNKNOWN |
| `clients/cli/src/main/java/org/apache/gravitino/cli/outputs/TableFormat.java:182` | wrap | IOException from OutputStreamWriter over in-memory ByteArrayOutputStream (cannot realistically occur) | internal | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:361` | wrap | server 500 INTERNAL_ERROR_CODE (partition ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:411` | wrap | server 500 INTERNAL_ERROR_CODE (table ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:466` | wrap | server 500 INTERNAL_ERROR_CODE (view ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:541` | wrap | server 500 INTERNAL_ERROR_CODE (schema ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:581` | wrap | server 500 INTERNAL_ERROR_CODE (catalog ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:636` | wrap | server 500 INTERNAL_ERROR_CODE (metalake ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:670` | broadCatch | Jackson deser of OAuth2 error-response JSON -> ErrorResponse.unknownError (raw json kept in msg) | B7 | keep |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:743` | wrap | server 500 INTERNAL_ERROR_CODE (fileset ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:794` | wrap | server 500 INTERNAL_ERROR_CODE (topic ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:848` | wrap | server 500 INTERNAL_ERROR_CODE (user ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:889` | wrap | server 500 INTERNAL_ERROR_CODE (group ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:945` | wrap | server 500 INTERNAL_ERROR_CODE (role ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:998` | wrap | server 500 INTERNAL_ERROR_CODE (permission ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1031` | wrap | server 500 INTERNAL_ERROR_CODE rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1077` | wrap | server 500 INTERNAL_ERROR_CODE (tag ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1123` | wrap | server 500 INTERNAL_ERROR_CODE (policy ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1159` | wrap | server 500 INTERNAL_ERROR_CODE (owner ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1213` | wrap | server 500 INTERNAL_ERROR_CODE (model ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1274` | wrap | server 500 INTERNAL_ERROR_CODE (job ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1337` | wrap | server 500 INTERNAL_ERROR_CODE rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1367` | broadCatch | Jackson deser of REST error-response JSON -> ErrorResponse.unknownError (raw json kept in msg) | B7 | keep |
| `clients/client-java/src/main/java/org/apache/gravitino/client/ErrorHandlers.java:1424` | wrap | server 500 INTERNAL_ERROR_CODE (function ops) rethrown as bare RuntimeException | B7 | INTERNAL_BUG(500) |
| `clients/client-java/src/main/java/org/apache/gravitino/client/GravitinoClientBase.java:194` | broadCatch | restClient.close() failure silently swallowed during client close | internal | keep |
| `clients/client-java/src/main/java/org/apache/gravitino/client/HTTPClient.java:449` | broadCatch | pre-connect auth handler failure; resets handler state then rethrows unchanged | internal | keep |
| `clients/client-java/src/main/java/org/apache/gravitino/client/KerberosTokenProvider.java:84` | broadCatch | Kerberos/GSS token negotiation -> IllegalStateException (cause preserved) | B8 | UNAUTHENTICATED |
| `clients/client-java/src/main/java/org/apache/gravitino/client/KerberosTokenProvider.java:394` | wrap | ReflectiveOperationException invoking Subject.current() (JDK18+ reflection shim) | internal | INTERNAL_BUG(500) |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/BaseGVFSOperations.java:314` | broadCatch | gravitinoClient.close() failure silently swallowed during GVFS close | internal | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/BaseGVFSOperations.java:876` | broadCatch | reflective access to Hadoop FileSystem private statics (SERVICE_FILE_SYSTEMS/FILE_SYSTEMS_LOADED) | internal | INTERNAL_BUG(500) |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/BaseGVFSOperations.java:877` | wrap | same reflection failure rethrown as bare RuntimeException(e) | internal | INTERNAL_BUG(500) |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/BaseGVFSOperations.java:997` | broadCatch | fileset load + credential vending via Gravitino client (NOT_FOUND/network/auth all flattened) | B8 | UNAVAILABLE |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/BaseGVFSOperations.java:998` | wrap | credential-vending failure rethrown as bare RuntimeException(e) | B8 | UNAVAILABLE |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:80` | broadCatch | reflection load/instantiate of configured GVFS hook class -> GravitinoRuntimeException | B8 | INVALID_ARGUMENT |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:93` | broadCatch | reflection load/instantiate of configured operations class (unwraps target RuntimeException) | B8 | INVALID_ARGUMENT |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:149` | broadCatch | setWorkingDirectory failure delegated intact to hook.onSetWorkingDirectoryFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:163` | broadCatch | open failure delegated intact to hook.onOpenFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:190` | broadCatch | create failure delegated intact to hook.onCreateFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:206` | broadCatch | append failure delegated intact to hook.onAppendFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:221` | broadCatch | rename failure delegated intact to hook.onRenameFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:235` | broadCatch | delete failure delegated intact to hook.onDeleteFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:247` | broadCatch | getFileStatus failure delegated intact to hook.onGetFileStatusFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:259` | broadCatch | listStatus failure delegated intact to hook.onListStatusFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:269` | broadCatch | mkdirs failure delegated intact to hook.onMkdirsFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:283` | broadCatch | getDefaultReplication failure delegated intact to hook.onGetDefaultReplicationFailure (default rethrows) | B8 | keep |
| `clients/filesystem-hadoop3/src/main/java/org/apache/gravitino/filesystem/hadoop/GravitinoVirtualFileSystem.java:297` | broadCatch | getDefaultBlockSize failure delegated intact to hook.onGetDefaultBlockSizeFailure (default rethrows) | B8 | keep |

## clients-python (24 sites; raw rg hits: 24)

> Scope: clients/client-python/gravitino main sources (test/, integration-test/, it/ excluded). rg pattern "raise RuntimeError|except Exception" yields exactly 24 hits; all 24 enumerated. Two clean clusters: 1) Nine REST error handlers (gravitino/exceptions/handlers/*_error_handler.py) each map the server's wire ErrorResponse to typed Python exceptions, but on ErrorConstants.INTERNAL_ERROR_CODE they all raise a bare `RuntimeError(error_message)`. This is the client-side terminus of B7 (server→client wire): the server's 500-family error arrives already flattened (no dep-vs-bug distinction on the wire) and the client further degrades it to an untyped RuntimeError, losing catchability. Proposed: a typed client exception carrying INTERNAL_BUG(500) semantics (and INTERNAL_DEP(502) once the server distinguishes it on the wire). Note the Java client sibling handlers raise RESTException here; the Python handlers that do NOT appear in this list (catalog, fileset, metalake, schema, topic, model, job, credential, generic rest_error_handler) fall through to super().handle() which raises RESTException-like defaults — only these nine have the explicit RuntimeError branch. 2) Fifteen broad `except Exception` sites in gravitino/filesystem/gvfs.py (fsspec surface, B8 client→application): each fsspec method (ls/info/exists/cp_file/mv/rm/rm_file/rmdir/open/mkdir/makedirs/created/modified/cat_file/get_file) wraps the whole operation and routes the exception to a pluggable GravitinoVirtualFileSystemHook.on_<op>_failure(). Verified in gravitino/filesystem/gvfs_hook.py that every default on_*_failure implementation ends in `raise exception` (re-raises the original, type preserved) — these are deliberate hook/fallback extension points analogous to listener isolation, hence proposedCode=keep. Residual risk worth noting for the architecture doc: a user-supplied hook can silently swallow any failure including bugs, and the catch also covers the hook's own pre_*/post_* failures.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `clients/client-python/gravitino/exceptions/handlers/group_error_handler.py:62` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/exceptions/handlers/owner_error_handler.py:53` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/exceptions/handlers/partition_error_handler.py:57` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/exceptions/handlers/permission_error_handler.py:76` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/exceptions/handlers/role_error_handler.py:78` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/exceptions/handlers/statistics_error_handler.py:59` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/exceptions/handlers/table_error_handler.py:55` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/exceptions/handlers/tag_error_handler.py:65` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/exceptions/handlers/user_error_handler.py:63` | wrap | server INTERNAL_ERROR_CODE(1002) wire error flattened to bare RuntimeError | B7 | INTERNAL_BUG(500) |
| `clients/client-python/gravitino/filesystem/gvfs.py:143` | broadCatch | gvfs ls (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:163` | broadCatch | gvfs info (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:188` | broadCatch | gvfs exists (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:205` | broadCatch | gvfs cp_file (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:227` | broadCatch | gvfs mv (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:249` | broadCatch | gvfs rm (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:263` | broadCatch | gvfs rm_file (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:279` | broadCatch | gvfs rmdir (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:340` | broadCatch | gvfs open (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:370` | broadCatch | gvfs mkdir (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:394` | broadCatch | gvfs makedirs (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:413` | broadCatch | gvfs created (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:432` | broadCatch | gvfs modified (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:457` | broadCatch | gvfs cat_file (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |
| `clients/client-python/gravitino/filesystem/gvfs.py:484` | broadCatch | gvfs get_file (storage op + metadata lookup + hook pre/post) routed to pluggable hook fallback | B8 | keep |

## core-catalog (77 sites; raw rg hits: 77)

> Scope = core/src/main/java/org/apache/gravitino/catalog, main sources only (no test paths exist under this scope's main tree). Raw counts: 47 `throw new RuntimeException` + 30 `catch (Exception` = 77; sites[] has 77 rows, one per hit. Patterns: (1) The dominant flattening is entity-store (B3) IOException -> bare/plain RuntimeException, appearing ~40 times across ManagedSchema/Table/FunctionOperations, the four entity dispatchers, and CatalogManager; all should classify to INTERNAL_DEP(502) with cause preserved. (2) OperationDispatcher.doWithCatalog/doWithTable (B2) is the central classify-then-rethrow funnel: expected typed exceptions and RuntimeExceptions pass through, but residual checked exceptions from connectors become message-less RuntimeException -> the single highest-leverage fix point for connector-side classification. (3) Nine sites are deliberate degrade/isolation catches (create-then-store fallbacks, operateOnEntity/getEntity, wrapper close, conf-file fallback, decoder->IAE) marked keep. (4) Two genuine misclassifications found: CatalogManager.createCatalogInstance (lines 1193-1200) flattens lookupCatalogProvider's IllegalArgumentException 'No catalog provider found for: X' (a user error, INVALID_ARGUMENT) into RuntimeException and then double-wraps it via the outer catch; and CatalogManager.updateCatalogProperty line 1435 wraps NoSuchCatalogException (NOT_FOUND) into RuntimeException. (5) ManagedSchemaOperations:177 and the capabilities() catches (CatalogManager:1040/1042, CapabilityHelpers:58/59) are can't-happen invariants -> INTERNAL_BUG(500). Mixed-domain catches (CatalogManager 571/577 createCatalog, 911/912 dropCatalog) span B2+B3 in one block and were tagged with the dominant store-side domain; a real fix would split them.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `core/src/main/java/org/apache/gravitino/catalog/CapabilityHelpers.java:58` | broadCatch | connector capability() call via classloader in getCapability | B2 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/catalog/CapabilityHelpers.java:59` | wrap | connector capability() failure | B2 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:255` | broadCatch | connector catalog.close() failure in CatalogWrapper.close (best-effort, LOG.warn only) | B2 | keep |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:455` | wrap | store.list IOException in listCatalogs | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:481` | wrap | store.list IOException in listCatalogsInfo | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:571` | broadCatch | createCatalog residual: store.put IOException + connector wrapper init failures (RTEs rethrown as-is) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:577` | wrap | createCatalog residual checked exception (store.put IO / connector init) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:656` | broadCatch | testConnection: wrapper init + connector testConnection checked failures (backend unreachable/bad config) | B2 | UNAVAILABLE |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:661` | wrap | testConnection residual checked exception from connector | B2 | UNAVAILABLE |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:702` | wrap | store.update IOException in enableCatalog | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:743` | wrap | store.update IOException in disableCatalog | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:788` | broadCatch | connector properties-metadata validation in alterCatalog (IAE rethrown separately; residual = connector/classloader fault) | B2 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:790` | wrap | connector properties-metadata validation residual failure | B2 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:848` | wrap | store.update IOException in alterCatalog | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:889` | broadCatch | connector dropSchema cascade failure during dropCatalog of managed-storage catalog | B2 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:891` | wrap | connector dropSchema cascade failure during dropCatalog | B2 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:911` | broadCatch | dropCatalog residual: store list/delete IOException + connector schemaExists via containsUserCreatedSchemas (GravitinoRuntimeException rethrown) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:912` | wrap | dropCatalog residual checked exception (bare RuntimeException, no message) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1040` | broadCatch | connector capabilities() call in isManagedStorageCatalog (comment: should never throw) | B2 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1042` | wrap | connector capabilities() unexpected failure | B2 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1108` | wrap | store.get IOException in loadCatalogInternal | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1193` | broadCatch | provider ServiceLoader lookup (IAE 'No catalog provider found' flattened!) + reflective newInstance in createCatalogInstance | B2 | INVALID_ARGUMENT |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1195` | wrap | provider lookup IAE / reflective instantiation failure (loses INVALID_ARGUMENT classification) | B2 | INVALID_ARGUMENT |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1198` | broadCatch | double-wrap of inner provider-load RuntimeException propagated through IsolatedClassLoader | B2 | INVALID_ARGUMENT |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1200` | wrap | double-wrap of already-wrapped provider-load failure | B2 | INVALID_ARGUMENT |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1204` | wrap | defensive null-catalog check after classloader load (no cause attached) | internal | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1225` | broadCatch | reading local provider .conf file; warns and falls back to empty config (deliberate best-effort) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1435` | wrap | NoSuchCatalogException during in-use property update wrapped into RuntimeException (hides NOT_FOUND) | B3 | NOT_FOUND |
| `core/src/main/java/org/apache/gravitino/catalog/CatalogManager.java:1445` | wrap | store.update IOException in updateCatalogProperty | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedFunctionOperations.java:101` | wrap | store.list IOException in listFunctionInfos | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedFunctionOperations.java:112` | wrap | store.get IOException in getFunction | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedFunctionOperations.java:142` | wrap | store.update IOException in alterFunction | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedFunctionOperations.java:153` | wrap | store.delete IOException in dropFunction | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedFunctionOperations.java:195` | wrap | store.put IOException in registerFunction | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedSchemaOperations.java:87` | wrap | store.list IOException in listSchemas | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedSchemaOperations.java:99` | wrap | store.exists IOException in createSchema pre-check | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedSchemaOperations.java:121` | wrap | store.put IOException in createSchema | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedSchemaOperations.java:149` | wrap | store.get IOException in loadSchema | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedSchemaOperations.java:173` | wrap | store.update IOException in alterSchema | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedSchemaOperations.java:177` | wrap | AlreadyExistsException on alterSchema (impossible since rename unsupported; invariant violation) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedSchemaOperations.java:191` | wrap | store.delete IOException in dropSchema | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedTableOperations.java:92` | wrap | store.list IOException in listTables | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedTableOperations.java:105` | wrap | store.get IOException in loadTable | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedTableOperations.java:159` | wrap | store.put IOException in createTable | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedTableOperations.java:187` | wrap | store.update IOException in alterTable | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ManagedTableOperations.java:206` | wrap | store.delete IOException in dropTable | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:82` | broadCatch | doWithTable funnel: catalog load + connector partition ops; expected type and RTEs rethrown, residual checked wrapped | B2 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:89` | wrap | residual checked exception from connector partition ops (bare RuntimeException) | B2 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:99` | broadCatch | doWithCatalog funnel (1 expected type): connector op residual checked exceptions | B2 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:106` | wrap | residual checked exception from connector op (bare RuntimeException) | B2 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:119` | broadCatch | doWithCatalog funnel (2 expected types): connector op residual checked exceptions | B2 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:129` | wrap | residual checked exception from connector op (bare RuntimeException) | B2 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:212` | broadCatch | operateOnEntity store get/update failure; logs and returns null so response degrades to catalog-only metadata (deliberate) | B3 | keep |
| `core/src/main/java/org/apache/gravitino/catalog/OperationDispatcher.java:239` | broadCatch | getEntity store.get failure; logs warn and returns null (deliberate metadata degrade) | B3 | keep |
| `core/src/main/java/org/apache/gravitino/catalog/PropertiesMetadataHelpers.java:38` | broadCatch | property value decoder failure, converted to IllegalArgumentException with key/value context and cause | internal | keep |
| `core/src/main/java/org/apache/gravitino/catalog/SchemaOperationDispatcher.java:159` | broadCatch | store.put after successful catalog createSchema; logs + returns catalog-only metadata (deliberate degrade) | B3 | keep |
| `core/src/main/java/org/apache/gravitino/catalog/SchemaOperationDispatcher.java:354` | broadCatch | store.delete(SCHEMA) failure during dropSchema | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/SchemaOperationDispatcher.java:355` | wrap | store.delete(SCHEMA) failure during dropSchema (bare RuntimeException) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/SchemaOperationDispatcher.java:416` | broadCatch | store.put schema entity during importSchema (EntityAlreadyExists rethrown separately) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/SchemaOperationDispatcher.java:418` | wrap | store.put schema entity during importSchema | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TableOperationDispatcher.java:389` | broadCatch | store.delete(TABLE) failure during dropTable | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TableOperationDispatcher.java:390` | wrap | store.delete(TABLE) failure during dropTable (bare RuntimeException, no message) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TableOperationDispatcher.java:446` | broadCatch | store.delete(TABLE) failure during purgeTable | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TableOperationDispatcher.java:447` | wrap | store.delete(TABLE) failure during purgeTable (bare RuntimeException) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TableOperationDispatcher.java:524` | broadCatch | store.put table entity during importTable (EntityAlreadyExists rethrown separately) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TableOperationDispatcher.java:526` | wrap | store.put table entity during importTable | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TableOperationDispatcher.java:690` | broadCatch | store.put after successful catalog createTable; logs + returns catalog-only metadata (deliberate degrade, but silently orphans entity) | B3 | keep |
| `core/src/main/java/org/apache/gravitino/catalog/TopicOperationDispatcher.java:252` | broadCatch | store.delete(TOPIC) failure during dropTopic | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TopicOperationDispatcher.java:253` | wrap | store.delete(TOPIC) failure during dropTopic (bare RuntimeException) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TopicOperationDispatcher.java:308` | broadCatch | store.put topic entity during importTopic | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TopicOperationDispatcher.java:310` | wrap | store.put topic entity during importTopic | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/TopicOperationDispatcher.java:396` | broadCatch | store.put after successful catalog createTopic; logs + returns catalog-only metadata (deliberate degrade) | B3 | keep |
| `core/src/main/java/org/apache/gravitino/catalog/ViewOperationDispatcher.java:314` | broadCatch | store.delete(VIEW) failure during dropView | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ViewOperationDispatcher.java:315` | wrap | store.delete(VIEW) failure during dropView (bare RuntimeException) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ViewOperationDispatcher.java:402` | broadCatch | store.put after successful catalog createView; logs + returns catalog-only metadata (deliberate degrade) | B3 | keep |
| `core/src/main/java/org/apache/gravitino/catalog/ViewOperationDispatcher.java:541` | broadCatch | store.put view entity during importView (EntityAlreadyExists handled separately as multi-catalog conflict) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/catalog/ViewOperationDispatcher.java:543` | wrap | store.put view entity during importView | B3 | INTERNAL_DEP(502) |

## core-other (321 sites; raw rg hits: 321)

> Raw hit counts verified: 117 `throw new RuntimeException` + 204 `catch (Exception` = 321 in core/src/main excluding /storage/ and /catalog/ subpackages (main sources only). Dominant patterns: (1) ~150 broadCatch sites across listener/*EventDispatcher.java + OwnerEventManager are the deliberate catch-emit-failure-event-rethrow pattern (verified: every one of those files has catch count == `throw e;` count) — all 'keep', boundary B5. (2) ~95 wrap sites in the manager layer (Metalake/Tag/Policy/Job/Statistic/Role/Permission/UserGroup/Owner managers) are `throw new RuntimeException(ioe)` flattening entity-store IOException at B3 — the single biggest classify-then-preserve opportunity, all → INTERNAL_DEP(502); today they surface as opaque 500s. (3) Two genuine taxonomy bugs hide among them: JobManager:343 and PolicyManager:193 wrap EntityAlreadyExists (rename target taken) in RuntimeException → should be ALREADY_EXISTS (409), and PolicyManager:193/MetalakeManager:535 drop the cause entirely. (4) Plugin/class-loading sites split into startup fail-fast (keep: EntityStoreFactory, CacheFactory, JobExecutorFactory, EventListenerManager, AuditLogManager, StatisticManager ctor, AuxiliaryServiceManager) vs request/catalog-configured loading (FAILED_PRECONDITION: BaseCatalog loadCustomOps + authz plugin load, BaseAuthorization, CredentialProviderFactory). (5) Notable questionable swallow: AuthorizationUtils:718 silently returns partial location paths to the authz plugin on any load failure (marked TODO). (6) IsolatedClassLoader.withClassLoader(fn, exceptionClass) at :106/:110 is a systemic B2 flattener — any plugin exception not matching the declared type becomes naked RuntimeException (marked TODO; needs classify-then-preserve mechanism). GravitinoAuthorizer.java:191-192 also flattens NoSuchEntity into RuntimeException (role lookup), losing NOT_FOUND. Catch/throw pairs on adjacent lines (e.g. EntityStoreFactory 55/57) are reported as two rows per the completeness contract but are single logical sites.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `core/src/main/java/org/apache/gravitino/EntityStoreFactory.java:55` | broadCatch | reflective EntityStore instantiation (server startup) | internal | keep |
| `core/src/main/java/org/apache/gravitino/EntityStoreFactory.java:57` | wrap | reflective EntityStore instantiation (server startup) | internal | keep |
| `core/src/main/java/org/apache/gravitino/GravitinoEnv.java:592` | broadCatch | entityStore.close during shutdown (log-and-continue) | internal | keep |
| `core/src/main/java/org/apache/gravitino/GravitinoEnv.java:604` | broadCatch | auxServiceManager.serviceStop during shutdown (log-and-continue) | internal | keep |
| `core/src/main/java/org/apache/gravitino/GravitinoEnv.java:625` | broadCatch | jobOperationDispatcher.close during shutdown (log-and-continue) | internal | keep |
| `core/src/main/java/org/apache/gravitino/GravitinoEnv.java:633` | broadCatch | statisticDispatcher.close during shutdown (log-and-continue) | internal | keep |
| `core/src/main/java/org/apache/gravitino/audit/AuditLogManager.java:77` | wrap | audit writer close IOException in listener stop | B5 | keep |
| `core/src/main/java/org/apache/gravitino/audit/AuditLogManager.java:85` | broadCatch | audit log write failure (log-warn swallow, deliberate isolation) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/audit/AuditLogManager.java:105` | broadCatch | audit writer class load/init (startup, rethrown as GravitinoRuntimeException) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/audit/AuditLogManager.java:113` | broadCatch | audit formatter class load (startup) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/auth/GroupMapperFactory.java:60` | broadCatch | custom GroupMapper instantiation (converted to IllegalArgumentException config error) | internal | keep |
| `core/src/main/java/org/apache/gravitino/auth/PrincipalMapperFactory.java:54` | broadCatch | custom PrincipalMapper instantiation (converted to IllegalArgumentException config error) | internal | keep |
| `core/src/main/java/org/apache/gravitino/auth/RegexGroupMapper.java:92` | broadCatch | regex group-mapping failure rethrown as IllegalArgumentException (server-config regex) | internal | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/auth/RegexPrincipalMapper.java:83` | broadCatch | regex principal-mapping failure rethrown as IllegalArgumentException (server-config regex) | internal | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/authorization/AuthorizationRequestContext.java:143` | broadCatch | role-load runnable failure (typically store access) | internal | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/AuthorizationRequestContext.java:144` | wrap | role-load runnable failure (typically store access) | internal | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/AuthorizationUtils.java:718` | broadCatch | entity/fileset load failure while resolving location paths; silent swallow gives authz plugin partial paths | B4 | TODO |
| `core/src/main/java/org/apache/gravitino/authorization/FutureGrantManager.java:146` | wrap | store IOException during future-grant role propagation | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/GravitinoAuthorizer.java:191` | broadCatch | entityStore.get RoleEntity failure (incl. NoSuchEntity flattened) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/GravitinoAuthorizer.java:192` | wrap | entityStore.get RoleEntity failure (incl. NoSuchEntity flattened) | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/OwnerEventManager.java:67` | broadCatch | setOwner failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/authorization/OwnerEventManager.java:99` | broadCatch | batch setOwners failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/authorization/OwnerEventManager.java:125` | broadCatch | getOwner failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/authorization/OwnerManager.java:64` | wrap | non-relational entity store at construction (startup fail-fast) | internal | keep |
| `core/src/main/java/org/apache/gravitino/authorization/OwnerManager.java:137` | wrap | store IOException in setOwner | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/OwnerManager.java:229` | wrap | store IOException in batch setOwners | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/OwnerManager.java:343` | wrap | store IOException in getOwner | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/PermissionManager.java:158` | wrap | store IOException granting roles to user | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/PermissionManager.java:248` | wrap | store IOException granting roles to group | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/PermissionManager.java:338` | wrap | store IOException revoking roles from group | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/PermissionManager.java:427` | wrap | store IOException revoking roles from user | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/PermissionManager.java:494` | wrap | store IOException granting privileges to role | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/PermissionManager.java:605` | wrap | store IOException revoking privileges from role | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/PermissionManager.java:729` | wrap | store IOException overriding privileges in role | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/RoleManager.java:98` | wrap | store IOException creating role | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/RoleManager.java:131` | wrap | store IOException deleting role | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/RoleManager.java:143` | wrap | store IOException listing role names | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/RoleManager.java:172` | wrap | store IOException listing roles by metadata object | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/RoleManager.java:181` | wrap | store IOException getting role entity | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupExternalManager.java:81` | wrap | store IOException adding user with external id | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupExternalManager.java:97` | wrap | store IOException removing user by external id | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupExternalManager.java:116` | wrap | store IOException getting user by external id | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupExternalManager.java:160` | wrap | store IOException enabling user by external id | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupExternalManager.java:189` | wrap | store IOException adding group with external id | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupExternalManager.java:205` | wrap | store IOException removing group by external id | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupExternalManager.java:224` | wrap | store IOException getting group by external id | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupManager.java:86` | wrap | store IOException adding user | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupManager.java:96` | wrap | store IOException removing user | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupManager.java:109` | wrap | store IOException getting user | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupManager.java:147` | wrap | store IOException adding group | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupManager.java:160` | wrap | store IOException removing group | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupManager.java:173` | wrap | store IOException getting group | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupManager.java:198` | wrap | store IOException listing users | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/authorization/UserGroupManager.java:213` | wrap | store IOException listing groups | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:91` | broadCatch | aux service reflective instantiation (lambda shim, startup) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:92` | wrap | aux service reflective instantiation (lambda checked-exception shim, startup) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:147` | broadCatch | aux service registration failure (startup fail-fast) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:149` | wrap | aux service registration failure (startup fail-fast) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:173` | broadCatch | aux service lifecycle call inside isolated classloader (lambda shim) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:174` | wrap | aux service lifecycle call inside isolated classloader (lambda shim) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:180` | broadCatch | checked exception from withClassLoader | B11 | keep |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:181` | wrap | checked exception from withClassLoader rewrapped (RuntimeException passthrough above) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/auxiliary/AuxiliaryServiceManager.java:208` | broadCatch | aux service stop failure (stopQuietly, first exception recorded) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/cache/CacheFactory.java:51` | broadCatch | reflective EntityCache instantiation (server startup) | internal | keep |
| `core/src/main/java/org/apache/gravitino/cache/CacheFactory.java:52` | wrap | reflective EntityCache instantiation (server startup) | internal | keep |
| `core/src/main/java/org/apache/gravitino/cache/SegmentedLock.java:111` | wrap | InterruptedException waiting for segment lock (interrupt restored) | internal | UNAVAILABLE |
| `core/src/main/java/org/apache/gravitino/cache/SegmentedLock.java:137` | wrap | InterruptedException waiting for segment lock (interrupt restored) | internal | UNAVAILABLE |
| `core/src/main/java/org/apache/gravitino/cache/SegmentedLock.java:323` | wrap | InterruptedException waiting for global cache operation | internal | UNAVAILABLE |
| `core/src/main/java/org/apache/gravitino/connector/BaseCatalog.java:294` | broadCatch | authorization plugin load via isolated classloader | B4 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/connector/BaseCatalog.java:296` | wrap | authorization plugin load via isolated classloader (catalog-configured provider) | B4 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/connector/BaseCatalog.java:329` | broadCatch | catalogCredentialManager.close aggregation into firstException (close path) | internal | keep |
| `core/src/main/java/org/apache/gravitino/connector/BaseCatalog.java:382` | broadCatch | custom CatalogOperations class load | B2 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/connector/BaseCatalog.java:384` | wrap | custom CatalogOperations class load (user-configured class name) | B2 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/connector/authorization/BaseAuthorization.java:87` | broadCatch | authorization provider ServiceLoader instantiation | B4 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/connector/authorization/BaseAuthorization.java:88` | wrap | authorization provider ServiceLoader instantiation | B4 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/credential/CredentialOperationDispatcher.java:167` | broadCatch | URI.create failure during path scheme support check (returns false) | internal | keep |
| `core/src/main/java/org/apache/gravitino/credential/CredentialProviderFactory.java:41` | broadCatch | credential provider instantiation/initialize | B2 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/credential/CredentialProviderFactory.java:43` | wrap | credential provider instantiation/initialize (catalog-configured) | B2 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/hook/CatalogHookDispatcher.java:101` | broadCatch | post-create-catalog hook failure; rollback then rethrow original | internal | keep |
| `core/src/main/java/org/apache/gravitino/hook/CatalogHookDispatcher.java:107` | broadCatch | catalog rollback failure (logged, added as suppressed) | internal | keep |
| `core/src/main/java/org/apache/gravitino/job/BuiltInJobTemplateEventListener.java:114` | broadCatch | per-metalake built-in template reconcile failure (log-swallow, listener isolation) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/job/BuiltInJobTemplateEventListener.java:119` | broadCatch | built-in template registration for existing metalakes (log-swallow) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/job/BuiltInJobTemplateEventListener.java:144` | broadCatch | built-in template registration on CreateMetalakeEvent (log-swallow) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/job/BuiltInJobTemplateEventListener.java:238` | wrap | MalformedURLException in URI-to-URL lambda shim during auxlib classloader build (caught by outer fallback) | internal | keep |
| `core/src/main/java/org/apache/gravitino/job/BuiltInJobTemplateEventListener.java:243` | broadCatch | auxlib classloader build failure (falls back to context classloader) | internal | keep |
| `core/src/main/java/org/apache/gravitino/job/BuiltInJobTemplateEventListener.java:344` | wrap | store NoSuchEntity/IOException updating built-in job template (listener context, caller logs) | B3 | keep |
| `core/src/main/java/org/apache/gravitino/job/BuiltInJobTemplateEventListener.java:368` | broadCatch | obsolete built-in template delete failure (log-swallow) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/job/JobExecutorFactory.java:70` | broadCatch | job executor reflective instantiation/init (startup) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/job/JobExecutorFactory.java:71` | wrap | job executor reflective instantiation/init (server startup config) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:202` | wrap | store IOException listing job templates | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:226` | wrap | store IOException registering job template | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:249` | wrap | store IOException getting job template | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:301` | wrap | store IOException deleting job template | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:339` | wrap | store IOException altering job template | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:343` | wrap | EntityAlreadyExists on job template rename (new name taken) | B3 | ALREADY_EXISTS |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:395` | wrap | store IOException listing jobs | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:415` | wrap | store IOException getting job | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:437` | wrap | local staging directory creation IOException | internal | UNAVAILABLE |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:450` | broadCatch | job executor submitJob failure | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:451` | wrap | job executor submitJob failure | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:473` | wrap | store IOException registering job entity | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:498` | broadCatch | job executor cancelJob failure | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:499` | wrap | job executor cancelJob failure | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:528` | wrap | store IOException updating job to CANCELLING | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:582` | broadCatch | job status poll failure in background thread (log-and-continue) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:617` | wrap | store IOException updating job status in background poll | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:817` | broadCatch | job file fetch from URI | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/JobManager.java:818` | wrap | job file fetch from URI (remote/local resource) | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/local/LocalJobExecutor.java:296` | broadCatch | job process monitor error; marks job FAILED (background isolation) | B11 | keep |
| `core/src/main/java/org/apache/gravitino/job/local/ShellProcessBuilder.java:63` | broadCatch | shell job process start failure | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/local/ShellProcessBuilder.java:64` | wrap | shell job process start failure | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/local/SparkProcessBuilder.java:134` | broadCatch | spark-submit process start failure | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/job/local/SparkProcessBuilder.java:135` | wrap | spark-submit process start failure | B11 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:160` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:178` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:195` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:214` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:233` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:252` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:271` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:289` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:307` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:325` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:343` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:361` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:378` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:397` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:416` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:435` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:454` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:473` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:492` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:511` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:530` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:550` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:579` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:599` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:616` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:634` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:654` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:676` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:699` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AccessControlEventDispatcher.java:722` | broadCatch | access-control op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/AsyncQueueListener.java:87` | wrap | init() called after initialization (invariant violation) | B5 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/listener/AsyncQueueListener.java:133` | broadCatch | async listener event-processing failure (log-swallow, listener isolation) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/CatalogEventDispatcher.java:91` | broadCatch | catalog op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/CatalogEventDispatcher.java:109` | broadCatch | catalog op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/CatalogEventDispatcher.java:125` | broadCatch | catalog op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/CatalogEventDispatcher.java:150` | broadCatch | catalog op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/CatalogEventDispatcher.java:171` | broadCatch | catalog op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/CatalogEventDispatcher.java:187` | broadCatch | catalog op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/CatalogEventDispatcher.java:213` | broadCatch | catalog op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/CatalogEventDispatcher.java:226` | broadCatch | catalog op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/EventBus.java:109` | wrap | unknown event type (invariant violation) | B5 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/listener/EventBus.java:157` | broadCatch | listener failure while dispatching failure event (swallow to preserve original error) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/EventListenerManager.java:129` | wrap | unexpected listener mode (invariant violation) | B5 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/listener/EventListenerManager.java:161` | broadCatch | user event listener plugin load/init (startup) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/EventListenerManager.java:167` | wrap | user event listener plugin load/init (server startup fail-fast) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/EventListenerPluginWrapper.java:49` | wrap | init() called after initialization (invariant violation) | B5 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/listener/EventListenerPluginWrapper.java:64` | broadCatch | user listener stop failure (log-swallow) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/EventListenerPluginWrapper.java:73` | broadCatch | user listener onPostEvent failure (log-swallow, listener isolation) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/EventListenerPluginWrapper.java:93` | broadCatch | user listener onPreEvent failure other than sync ForbiddenException (log-swallow) | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FilesetEventDispatcher.java:86` | broadCatch | fileset op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FilesetEventDispatcher.java:108` | broadCatch | fileset op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FilesetEventDispatcher.java:125` | broadCatch | fileset op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FilesetEventDispatcher.java:152` | broadCatch | fileset op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FilesetEventDispatcher.java:174` | broadCatch | fileset op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FilesetEventDispatcher.java:189` | broadCatch | fileset op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FilesetEventDispatcher.java:219` | broadCatch | fileset op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FunctionEventDispatcher.java:84` | broadCatch | function op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FunctionEventDispatcher.java:102` | broadCatch | function op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FunctionEventDispatcher.java:116` | broadCatch | function op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FunctionEventDispatcher.java:139` | broadCatch | function op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FunctionEventDispatcher.java:156` | broadCatch | function op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/FunctionEventDispatcher.java:170` | broadCatch | function op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:86` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:105` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:126` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:146` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:172` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:195` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:214` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:238` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/JobEventDispatcher.java:257` | broadCatch | job op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/MetalakeEventDispatcher.java:86` | broadCatch | metalake op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/MetalakeEventDispatcher.java:101` | broadCatch | metalake op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/MetalakeEventDispatcher.java:127` | broadCatch | metalake op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/MetalakeEventDispatcher.java:147` | broadCatch | metalake op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/MetalakeEventDispatcher.java:163` | broadCatch | metalake op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/MetalakeEventDispatcher.java:176` | broadCatch | metalake op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/MetalakeEventDispatcher.java:189` | broadCatch | metalake op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:117` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:147` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:166` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:182` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:199` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:222` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:241` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:259` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:275` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:292` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:309` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:325` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:344` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:366` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:389` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:406` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ModelEventDispatcher.java:424` | broadCatch | model op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PartitionEventDispatcher.java:84` | broadCatch | partition op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PartitionEventDispatcher.java:104` | broadCatch | partition op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PartitionEventDispatcher.java:122` | broadCatch | partition op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PartitionEventDispatcher.java:141` | broadCatch | partition op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PartitionEventDispatcher.java:160` | broadCatch | partition op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PartitionEventDispatcher.java:175` | broadCatch | partition op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PartitionEventDispatcher.java:193` | broadCatch | partition op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:95` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:114` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:136` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:169` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:198` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:218` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:234` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:256` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:281` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:307` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:341` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/PolicyEventDispatcher.java:368` | broadCatch | policy op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/SchemaEventDispatcher.java:84` | broadCatch | schema op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/SchemaEventDispatcher.java:108` | broadCatch | schema op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/SchemaEventDispatcher.java:124` | broadCatch | schema op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/SchemaEventDispatcher.java:142` | broadCatch | schema op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/SchemaEventDispatcher.java:157` | broadCatch | schema op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/StatisticEventDispatcher.java:79` | broadCatch | statistic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/StatisticEventDispatcher.java:96` | broadCatch | statistic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/StatisticEventDispatcher.java:114` | broadCatch | statistic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/StatisticEventDispatcher.java:137` | broadCatch | statistic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/StatisticEventDispatcher.java:159` | broadCatch | statistic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/StatisticEventDispatcher.java:184` | broadCatch | statistic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TableEventDispatcher.java:92` | broadCatch | table op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TableEventDispatcher.java:107` | broadCatch | table op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TableEventDispatcher.java:145` | broadCatch | table op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TableEventDispatcher.java:164` | broadCatch | table op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TableEventDispatcher.java:179` | broadCatch | table op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TableEventDispatcher.java:194` | broadCatch | table op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:90` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:106` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:122` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:143` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:166` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:185` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:205` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:228` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:250` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:282` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TagEventDispatcher.java:307` | broadCatch | tag op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TopicEventDispatcher.java:82` | broadCatch | topic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TopicEventDispatcher.java:97` | broadCatch | topic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TopicEventDispatcher.java:115` | broadCatch | topic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TopicEventDispatcher.java:130` | broadCatch | topic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/TopicEventDispatcher.java:154` | broadCatch | topic op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ViewEventDispatcher.java:83` | broadCatch | view op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ViewEventDispatcher.java:98` | broadCatch | view op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ViewEventDispatcher.java:139` | broadCatch | view op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ViewEventDispatcher.java:157` | broadCatch | view op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/listener/ViewEventDispatcher.java:172` | broadCatch | view op failure; failure-event emission then rethrow | B5 | keep |
| `core/src/main/java/org/apache/gravitino/lock/LockManager.java:274` | broadCatch | tree-lock node creation failure; releases refs then rethrows | internal | keep |
| `core/src/main/java/org/apache/gravitino/lock/TreeLock.java:119` | broadCatch | tree-lock acquisition failure; unlocks held locks then rethrows | internal | keep |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:132` | wrap | store IOException reading metalake in-use property | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:160` | wrap | store IOException listing in-use metalakes | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:183` | wrap | store IOException listing metalakes | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:209` | wrap | store IOException loading metalake | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:277` | wrap | store IOException creating metalake | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:327` | wrap | store IOException altering metalake | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:365` | wrap | store IOException dropping metalake | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:405` | wrap | store IOException enabling metalake | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:445` | wrap | store IOException disabling metalake | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:521` | broadCatch | per-catalog setMetalakeInUseStatus failure (collected, continue-then-aggregate) | internal | keep |
| `core/src/main/java/org/apache/gravitino/metalake/MetalakeManager.java:535` | wrap | aggregate per-catalog in-use update failures (causes dropped, no exception chained) | internal | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:77` | wrap | non-relational entity store at construction (startup fail-fast) | internal | keep |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:104` | wrap | store IOException listing policies | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:126` | wrap | store IOException getting policy | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:170` | wrap | store IOException creating policy | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:193` | wrap | EntityAlreadyExists on policy rename (new name taken) | B3 | ALREADY_EXISTS |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:199` | wrap | store IOException altering policy | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:227` | wrap | store IOException deleting policy | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:258` | wrap | store IOException listing metadata objects for policy | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:294` | wrap | store IOException listing policies for metadata object | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:368` | wrap | store IOException associating policies with metadata object | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:407` | wrap | store IOException getting policy for metadata object | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:441` | wrap | store IOException changing policy enabled state | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/policy/PolicyManager.java:475` | wrap | store IOException reading policy enabled flag | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/stats/StatisticManager.java:79` | broadCatch | partition statistics storage factory instantiation (startup config) | internal | keep |
| `core/src/main/java/org/apache/gravitino/stats/StatisticManager.java:84` | wrap | partition statistics storage factory instantiation (startup config) | internal | keep |
| `core/src/main/java/org/apache/gravitino/stats/StatisticManager.java:173` | wrap | store IOException listing statistics | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/stats/StatisticManager.java:222` | wrap | store IOException updating statistics | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/stats/StatisticManager.java:259` | wrap | store IOException dropping statistics | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/stats/StatisticManager.java:284` | wrap | partition statistics storage IOException dropping partition stats | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/stats/StatisticManager.java:313` | wrap | partition statistics storage IOException updating partition stats | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/stats/StatisticManager.java:359` | wrap | partition statistics storage IOException listing partition stats | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:99` | wrap | store IOException listing tags | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:135` | wrap | store IOException creating tag | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:154` | wrap | store IOException getting tag | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:186` | wrap | store IOException altering tag | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:202` | wrap | store IOException deleting tag | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:232` | wrap | store IOException listing metadata objects for tag | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:270` | wrap | store IOException listing tags for metadata object | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:306` | wrap | store IOException getting tag for metadata object | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/tag/TagManager.java:375` | wrap | store IOException associating tags with metadata object | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/utils/ClassUtils.java:26` | broadCatch | reflective instantiation of configured class | internal | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/utils/ClassUtils.java:27` | wrap | reflective instantiation of configured class | internal | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/utils/ClassUtils.java:34` | broadCatch | reflective instantiation with explicit classloader | internal | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/utils/ClassUtils.java:35` | wrap | reflective instantiation with explicit classloader | internal | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/utils/ClientPoolImpl.java:61` | broadCatch | pooled backend client action failure (connection-exception retry logic) | B1 | keep |
| `core/src/main/java/org/apache/gravitino/utils/ClientPoolImpl.java:65` | broadCatch | reconnect failure (original failure rethrown) | B1 | keep |
| `core/src/main/java/org/apache/gravitino/utils/IsolatedClassLoader.java:106` | broadCatch | plugin call exceptions inside isolated classloader (type-filtered rethrow) | B2 | TODO |
| `core/src/main/java/org/apache/gravitino/utils/IsolatedClassLoader.java:110` | wrap | plugin exception not matching declared exceptionClass flattened to RuntimeException | B2 | TODO |
| `core/src/main/java/org/apache/gravitino/utils/IsolatedClassLoader.java:156` | broadCatch | classloader close failure (log-swallow) | internal | keep |
| `core/src/main/java/org/apache/gravitino/utils/IsolatedClassLoader.java:175` | broadCatch | class load failure converted to ClassNotFoundException with cause | internal | keep |
| `core/src/main/java/org/apache/gravitino/utils/PrincipalUtils.java:57` | wrap | PrivilegedActionException with non-Exception cause in doAs | internal | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/utils/PrincipalUtils.java:60` | wrap | Error thrown inside doAs | internal | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/utils/SchemaEntityCleaner.java:88` | broadCatch | orphaned schema entity cleanup failure (best-effort swallow, primary drop already succeeded) | B3 | keep |

## core-storage (79 sites; raw rg hits: 79)

> Scope: core/src/main/java/org/apache/gravitino/storage, main sources only (no test paths exist under this subtree's main). Raw counts verified twice: 71 `throw new RuntimeException` + 8 `catch (Exception` = 79; sites[] has 79 rows. Patterns: (1) 66 of the 71 wraps are the identical Jackson marshaling idiom — `catch (JsonProcessingException) -> throw new RuntimeException("Failed to (de)serialize ...", e)` — in POConverters (52) and the PO classes FunctionPO/JobPO/JobTemplatePO/ViewPO/StatisticPO (14). All sit on the entity<->PO layer at the core->metadata-DB boundary (B3): serialize failures are DTO bugs, deserialize failures are corrupted/incompatible stored JSON; both surface today as bare RuntimeException and would reach clients as an unclassified 500. Proposed: INTERNAL_BUG(500) uniformly; a single shared helper (e.g. POConverters.toJson/fromJson throwing a typed StorageMarshalException) would collapse all 66 sites. (2) Two control-flow throws (FilesetMetaService.java:246, ViewMetaService.java:166) signal a 0-rows-updated optimistic-lock conflict to trigger transaction rollback; they are caught by the enclosing method and flattened into IOException("Failed to update the entity") — a genuine concurrency conflict misreported; proposed ABORTED. (3) Three boot-time fail-fast catch+wrap pairs (RelationalEntityStore.java:125/128, JDBCBackend.java:889/890, H2Database.java:70/72) mix misconfiguration, classloading, script-file, and DB-connect failures into one opaque RuntimeException that aborts startup; proposed FAILED_PRECONDITION (operator-facing config/boot failure), cause chain preserved. (4) H2Database.close() (127/129) wraps the H2 SHUTDOWN failure in RuntimeException, escaping close()'s declared IOException contract; proposed INTERNAL_DEP(502)-flavored dependency failure (or wrap as IOException per contract). (5) Four broad catches are deliberate isolation and correct as-is (proposedCode=keep): EntityChangeLogPoller:163 (poll-loop guard, keeps scheduler alive), :189 (per-listener isolation, B5), :235 (background prune best-effort), RelationalGarbageCollector:109 (GC thread guard). Note EntityChangeLogPoller's guards unwrap InterruptedException before logging — better hygiene than most. Key file paths: /Users/nlz/datastrato/nevzheng/gravitino/.claude/worktrees/fault-domains-architecture-a72d2d/core/src/main/java/org/apache/gravitino/storage/relational/{utils/POConverters.java, RelationalEntityStore.java, JDBCBackend.java, EntityChangeLogPoller.java, RelationalGarbageCollector.java, database/H2Database.java, service/FilesetMetaService.java, service/ViewMetaService.java, po/*.java}.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `core/src/main/java/org/apache/gravitino/storage/relational/EntityChangeLogPoller.java:163` | broadCatch | whole poll cycle incl. metadata-DB selectEntityChanges; logs warn, keeps scheduler alive | B3 | keep |
| `core/src/main/java/org/apache/gravitino/storage/relational/EntityChangeLogPoller.java:189` | broadCatch | per-listener onEntityChange failure; deliberate listener isolation | B5 | keep |
| `core/src/main/java/org/apache/gravitino/storage/relational/EntityChangeLogPoller.java:235` | broadCatch | pruneOldEntityChanges DB call in background cleanup; logs and advances cursor | B3 | keep |
| `core/src/main/java/org/apache/gravitino/storage/relational/JDBCBackend.java:889` | broadCatch | embedded JDBCDatabase (H2) reflective instantiation + initialize at boot | B3 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/storage/relational/JDBCBackend.java:890` | wrap | same boot failure flattened to RuntimeException (fail-fast startup) | B3 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/storage/relational/RelationalEntityStore.java:125` | broadCatch | reflective RelationalBackend instantiation + initialize at boot (classload, config, DB connect) | B3 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/storage/relational/RelationalEntityStore.java:128` | wrap | same boot failure rethrown as opaque RuntimeException (fail-fast startup) | B3 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/storage/relational/RelationalGarbageCollector.java:109` | broadCatch | any unexpected failure in GC sweep over metadata DB; guards scheduled thread | B3 | keep |
| `core/src/main/java/org/apache/gravitino/storage/relational/database/H2Database.java:70` | broadCatch | H2 JDBC connect + schema-DDL script read/exec at boot (mixes missing script/misconfig with DB error) | B3 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/storage/relational/database/H2Database.java:72` | wrap | same H2 schema-creation failure flattened to RuntimeException | B3 | FAILED_PRECONDITION |
| `core/src/main/java/org/apache/gravitino/storage/relational/database/H2Database.java:127` | broadCatch | H2 SHUTDOWN statement at close(); RuntimeException escapes close()'s IOException contract | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/storage/relational/database/H2Database.java:129` | wrap | same H2 shutdown failure flattened to RuntimeException | B3 | INTERNAL_DEP(502) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/FunctionPO.java:154` | wrap | Jackson deser of stored function definitions/audit JSON (fromFunctionPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/FunctionPO.java:183` | wrap | Jackson ser of function-version definitions/audit (initializeFunctionVersionPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/FunctionPO.java:210` | wrap | Jackson ser of function audit info (buildFunctionPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/JobPO.java:125` | wrap | Jackson ser of job entity audit info when building JobPO | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/JobPO.java:141` | wrap | Jackson deser of stored job audit JSON (fromJobPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/JobTemplatePO.java:110` | wrap | Jackson ser of job-template content/audit (initializeJobTemplatePO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/JobTemplatePO.java:135` | wrap | Jackson ser of job-template content/audit (updateJobTemplatePO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/JobTemplatePO.java:155` | wrap | Jackson deser of stored job-template JSON (fromJobTemplatePO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/StatisticPO.java:85` | wrap | Jackson deser of stored statistic value/audit JSON (fromStatisticPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/StatisticPO.java:113` | wrap | Jackson ser of statistic value/audit (initializeStatisticPOs) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/ViewPO.java:146` | wrap | Jackson deser of stored view columns/properties/audit JSON (fromViewPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/ViewPO.java:183` | wrap | Jackson ser of view-version columns/properties/audit (initializeViewVersionInfoPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/po/ViewPO.java:205` | wrap | Jackson ser of view audit info (buildViewPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/service/FilesetMetaService.java:246` | wrap | 0-rows fileset meta update = optimistic-lock conflict used as control-flow throw to roll back tx | B3 | ABORTED |
| `core/src/main/java/org/apache/gravitino/storage/relational/service/ViewMetaService.java:166` | wrap | 0-rows view meta update = optimistic-lock conflict used as control-flow throw to roll back tx | B3 | ABORTED |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:126` | wrap | Jackson ser of metalake PO fields (initializeMetalakePOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:156` | wrap | Jackson ser of metalake PO fields (updateMetalakePOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:181` | wrap | Jackson deser of stored metalake JSON (fromMetalakePO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:221` | wrap | Jackson ser of catalog PO fields (initializeCatalogPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:254` | wrap | Jackson ser of catalog PO fields (updateCatalogPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:280` | wrap | Jackson deser of stored catalog JSON (fromCatalogPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:320` | wrap | Jackson ser of schema PO fields (initializeSchemaPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:349` | wrap | Jackson ser of schema PO fields (updateSchemaPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:372` | wrap | Jackson deser of stored schema JSON (fromSchemaPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:438` | wrap | Jackson ser of table PO fields (initializeTablePOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:501` | wrap | Jackson ser of table PO fields (updateTablePOWithVersionAndSchemaId) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:562` | wrap | Jackson deser of stored table+column JSON (fromTableAndColumnPOs) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:588` | wrap | Jackson deser of stored column JSON (fromColumnPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:624` | wrap | Jackson ser of column PO fields (initializeColumnPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:688` | wrap | Jackson ser of fileset PO fields (initializeFilesetPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:748` | wrap | Jackson ser of fileset PO fields (updateFilesetPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:773` | wrap | Jackson deser of stored fileset-version JSON during change check (checkFilesetVersionNeedUpdate) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:793` | wrap | Jackson deser of stored policy-version JSON during change check (checkPolicyVersionNeedUpdate) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:833` | wrap | Jackson ser of policy PO fields (updatePolicyPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:865` | wrap | Jackson deser of stored fileset JSON (fromFilesetPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:881` | wrap | Jackson deser of stored topic JSON (fromTopicPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:919` | wrap | Jackson ser of topic PO fields (initializeTopicPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:942` | wrap | Jackson ser of topic PO fields (updateTopicPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:967` | wrap | Jackson ser of user PO fields (initializeUserPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:996` | wrap | Jackson ser of user PO fields (updateUserPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1031` | wrap | Jackson deser of stored user JSON (fromUserPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1083` | wrap | Jackson deser of stored extended-user JSON (fromExtendedUserPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1118` | wrap | Jackson deser of stored group JSON (fromGroupPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1170` | wrap | Jackson deser of stored extended-group JSON (fromExtendedGroupPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1200` | wrap | Jackson ser of user-role rel PO fields (initializeUserRoleRelsPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1224` | wrap | Jackson ser of role PO fields (initializeRolePOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1249` | wrap | Jackson ser of group PO fields (initializeGroupPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1277` | wrap | Jackson ser of group PO fields (updateGroupPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1307` | wrap | Jackson ser of group-role rel PO fields (initializeGroupRoleRelsPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1336` | wrap | Jackson deser of stored securable-object JSON (fromSecurableObjectPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1353` | wrap | Jackson deser of stored role JSON (fromRolePO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1384` | wrap | Jackson ser of securable-object PO fields (initializeSecurablePOBuilderWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1405` | wrap | Jackson ser of role PO fields (updateRolePOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1421` | wrap | Jackson deser of stored tag JSON (fromTagPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1442` | wrap | Jackson ser of tag PO fields (initializeTagPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1464` | wrap | Jackson ser of tag PO fields (updateTagPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1487` | wrap | Jackson ser of tag-metadata-object rel PO fields (initializeTagMetadataObjectRelPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1508` | wrap | Jackson deser of stored policy JSON (fromPolicyPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1541` | wrap | Jackson ser of policy PO fields (initializePolicyPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1564` | wrap | Jackson ser of policy-metadata-object rel PO fields (initializePolicyMetadataObjectRelPOWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1593` | wrap | Jackson ser of owner rel PO fields (initializeOwnerRelPOsWithVersion) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1611` | wrap | Jackson deser of stored model JSON (fromModelPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1632` | wrap | Jackson ser of model PO fields (initializeModelPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1665` | wrap | Jackson deser of stored model-version JSON (fromModelVersionPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1692` | wrap | Jackson ser of model PO fields (updateModelPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1725` | wrap | Jackson ser of model-version PO fields (updateModelVersionPO) | B3 | INTERNAL_BUG(500) |
| `core/src/main/java/org/apache/gravitino/storage/relational/utils/POConverters.java:1785` | wrap | Jackson ser of model-version PO fields (initializeModelVersionPO) | B3 | INTERNAL_BUG(500) |

## engine-spark-flink (47 sites; raw rg hits: 47)

> Exact counts: 18 "throw new RuntimeException" + 29 "catch (Exception" = 47 hits in spark-connector/spark-common/src/main + flink-connector/flink-common/src/main. Per contract, catch/throw pairs at the same site appear as two rows (GravitinoCatalogStore 199/200, spark GravitinoCatalogManager 72/74, spark GravitinoIcebergCatalog 70/71, GravitinoGlueCatalog 297/298, IcebergExtendedDataSourceV2Strategy 228/229). Boundary conventions used: B9 = engine-facing catalog API methods that flatten Gravitino-client typed errors into engine exceptions (Flink CatalogException); B8 = connector-internal manager catching Gravitino client exceptions; B1 = catches around embedded backend catalogs (Iceberg SparkCatalog, Glue, Hive delegate); internal = reflection shims, classloading, ServiceLoader, value parsing. For residual catch-alls where typed NOT_FOUND/ALREADY_EXISTS are already peeled off, proposedCode is INTERNAL_DEP(502) as the dominant remaining class (server/dependency failure), though each also conflates UNAVAILABLE (connectivity) and INTERNAL_BUG (converter bugs) — a classify-then-preserve helper would split these three. Spark GravitinoCatalogManager rows marked NOT_FOUND because NoSuchCatalogException is the dominant flattened type, but the same catch also swallows UNAVAILABLE; note caffeine wraps loader failures in CompletionException, adding another layer. Two deliberate-isolation sites marked keep: GravitinoDriverPlugin per-catalog registration (warn-and-continue) and flink BaseCatalog.invalidateTable best-effort cache eviction. The flink BaseCatalog pattern is systematic: 12 of its 14 broad catches are the same trailing "catch (Exception e) -> new CatalogException(e)" template, so one shared translation utility would fix them all.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:221` | broadCatch | Gravitino listTables residual after NoSuchSchema → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:244` | broadCatch | Gravitino listViews residual after Unsupported/NoSuchSchema → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:266` | broadCatch | Gravitino loadTable + toFlinkTable residual after NoSuchTable/Forbidden → warn + flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:284` | broadCatch | Gravitino tableExists residual after Forbidden → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:303` | broadCatch | Gravitino viewExists residual after Unsupported/NoSuchSchema → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:331` | broadCatch | Gravitino dropTable/dropView residual → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:362` | broadCatch | Gravitino alterTable(rename) residual after NoSuchTable/AlreadyExists → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:385` | broadCatch | Gravitino alterView(rename) residual after NoSuchView/AlreadyExists → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:435` | broadCatch | Gravitino createTable residual after NoSuchSchema/AlreadyExists → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:458` | broadCatch | Gravitino createView residual after NoSuchSchema/ViewAlreadyExists → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:526` | broadCatch | Gravitino alterView (replace-view path of alterTable) residual after NoSuchView → warn + flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:580` | broadCatch | Gravitino alterView (tableChanges variant) residual after NoSuchView → warn + flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:761` | broadCatch | best-effort native catalog cache invalidation after successful DDL; debug-log and swallow by design | internal | keep |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/BaseCatalog.java:1005` | broadCatch | Gravitino loadView residual after NoSuchView/Unsupported → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/catalog/GravitinoCatalogManager.java:356` | broadCatch | Hadoop UserGroupInformation.getCurrentUser IOException (Kerberos/OS login resolution) → IllegalStateException | internal | FAILED_PRECONDITION |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/hive/GravitinoHiveCatalog.java:190` | broadCatch | Gravitino createTable (generic table) residual after NoSuchSchema/AlreadyExists → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/hive/GravitinoHiveCatalog.java:211` | broadCatch | Gravitino loadTable + Flink table conversion residual → warn + flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/hive/GravitinoHiveCatalog.java:279` | broadCatch | loadGravitinoTable residual after NoSuchTable → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/hive/GravitinoHiveCatalog.java:318` | broadCatch | Gravitino alterTable (generic table property sync) residual → flat CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/store/GravitinoCatalogStore.java:84` | broadCatch | Gravitino client dropCatalog failure; also re-wraps its own not-dropped CatalogException in another CatalogException | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/store/GravitinoCatalogStore.java:110` | broadCatch | getCatalog residual after NoSuchCatalog: client call, factory lookup, property conversion | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/store/GravitinoCatalogStore.java:119` | broadCatch | Gravitino client listCatalogs failure (connectivity, auth, server errors) | B9 | INTERNAL_DEP(502) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/store/GravitinoCatalogStore.java:199` | broadCatch | unexpected exception in ServiceLoader factory iteration (typed Errors already peeled off) | internal | INTERNAL_BUG(500) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/store/GravitinoCatalogStore.java:200` | wrap | unexpected exception during ServiceLoader factory iteration | internal | INTERNAL_BUG(500) |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/store/GravitinoCatalogStore.java:205` | wrap | no matching Flink catalog factory found (missing connector jar on classpath) | internal | FAILED_PRECONDITION |
| `flink-connector/flink-common/src/main/java/org/apache/gravitino/flink/connector/store/GravitinoCatalogStore.java:209` | wrap | multiple Flink catalog factories matched (classpath conflict) | internal | FAILED_PRECONDITION |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/catalog/BaseCatalog.java:694` | wrap | ReflectiveOperationException instantiating Gravitino-registered function class (class/ctor missing on Spark classpath) | internal | FAILED_PRECONDITION |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/catalog/BaseCatalog.java:704` | wrap | NoSuchTableException from embedded backend Spark catalog loadTable flattened to unchecked RuntimeException | B1 | NOT_FOUND |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/catalog/GravitinoCatalogManager.java:72` | broadCatch | caffeine cache get around Gravitino client loadCatalog: NoSuchCatalogException, connectivity, CompletionException all flattened | B8 | NOT_FOUND |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/catalog/GravitinoCatalogManager.java:74` | wrap | Gravitino client loadCatalog via caffeine cache: NoSuchCatalogException and server connectivity both become bare RuntimeException(e) | B8 | NOT_FOUND |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/glue/GravitinoGlueCatalog.java:164` | wrap | Spark NoSuchTableException from Glue/Hive backend loadTable flattened to unchecked RuntimeException | B1 | NOT_FOUND |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/glue/GravitinoGlueCatalog.java:297` | broadCatch | Iceberg GlueCatalog lazy init (AWS region/credential config, Glue connectivity) | B1 | FAILED_PRECONDITION |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/glue/GravitinoGlueCatalog.java:298` | wrap | Iceberg GlueCatalog lazy init failure (AWS region/credentials config or Glue connectivity) | B1 | FAILED_PRECONDITION |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/hive/SparkHiveView.java:187` | wrap | Spark view-SQL executeCollect failure (AnalysisException/exec errors) re-wrapped, losing Spark error class | B9 | INVALID_ARGUMENT |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/GravitinoIcebergCatalog.java:70` | broadCatch | Class.forName(jdbcDriver) — missing JDBC driver on classpath | internal | FAILED_PRECONDITION |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/GravitinoIcebergCatalog.java:71` | wrap | Class.forName(jdbcDriver) failure — JDBC driver missing from isolated classloader | internal | FAILED_PRECONDITION |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/GravitinoIcebergCatalog.java:149` | wrap | reflective build of Iceberg SparkProcedures (NoSuchMethod/ClassNotFound version shim) | internal | INTERNAL_BUG(500) |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/GravitinoIcebergCatalog.java:207` | wrap | reflective Iceberg BaseCatalog.isSystemNamespace check failure | internal | INTERNAL_BUG(500) |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/GravitinoIcebergCatalog.java:225` | wrap | NoSuchTableException from Iceberg SparkCatalog time-travel loadTable(version), flattened despite caller declaring NoSuchTableException | B1 | NOT_FOUND |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/GravitinoIcebergCatalog.java:238` | wrap | NoSuchTableException from Iceberg SparkCatalog time-travel loadTable(timestamp), flattened despite caller declaring NoSuchTableException | B1 | NOT_FOUND |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/SparkIcebergTable.java:102` | wrap | reflection on Iceberg SparkCatalog private cacheEnabled field (version-shim breakage) | internal | INTERNAL_BUG(500) |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/extensions/IcebergExtendedDataSourceV2Strategy.java:228` | broadCatch | reflective createDistributionAndOrderingExec Iceberg version-shim failure | internal | INTERNAL_BUG(500) |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/iceberg/extensions/IcebergExtendedDataSourceV2Strategy.java:229` | wrap | reflective createDistributionAndOrderingExec (Iceberg 1.6 vs 1.9 API shim) failure | internal | INTERNAL_BUG(500) |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/jdbc/GravitinoJdbcCatalog.java:135` | wrap | user-supplied namespace location property (unsupported for JDBC) wrapped as RuntimeException around Spark AnalysisException | B9 | INVALID_ARGUMENT |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/jdbc/GravitinoJdbcCatalog.java:138` | wrap | unsupported namespace property key on JDBC createNamespace wrapped as RuntimeException | B9 | INVALID_ARGUMENT |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/plugin/GravitinoDriverPlugin.java:153` | broadCatch | per-catalog registration failure during driver plugin init; warn-and-continue so one bad catalog does not kill the Spark driver | B9 | keep |
| `spark-connector/spark-common/src/main/java/org/apache/gravitino/spark/connector/utils/SparkPartitionUtils.java:165` | broadCatch | parse of Hive partition value string to Spark type (NumberFormat/Date parse) rethrown as UnsupportedOperationException with cause | internal | INVALID_ARGUMENT |

## engine-trino (56 sites; raw rg hits: 56)

> Scope: trino-connector/trino-connector/src/main (main sources only; the sibling trino-connector/integration-test module is excluded). Raw counts: 11 `throw new RuntimeException` + 45 `catch (Exception` = 56, all enumerated. Boundary conventions used: B9 = failure surfaces to/through the Trino engine (plugin loading, handle/Block JSON serde for the engine, CREATE/DROP CATALOG via the Trino JDBC loopback); B8 = the connector acting as an application over the Gravitino Java client (REST calls to the Gravitino server); B1 = the wrapped internal Trino connector (hive/mysql/iceberg plugin) acting as backend; internal = wrap immediately recaught in-process or pure bootstrap reflection. Notable flattening defects found while reading: (1) CatalogRegister.registerCatalog L157 catch(Exception) re-wraps its own ALREADY_EXISTS/DUPLICATED TrinoExceptions (L135/139/148) into GRAVITINO_RUNTIME_ERROR; (2) all three stored procedures' final catch(Exception) re-codes nested TrinoExceptions (incl. OPERATION_FAILED and NOT_EXISTS) to GRAVITINO_UNSUPPORTED_OPERATION; (3) CatalogConnectorManager.createCatalogConnectorContext L427 re-codes nested TrinoExceptions to GRAVITINO_OPERATION_FAILED; (4) GravitinoHandle ser/deser failures are coded GRAVITINO_ILLEGAL_ARGUMENT though they are engine-internal round-trip bugs; (5) CatalogConnectorContext.build L264 silently converts real credential-vending failures into "no credentials" (should be narrowed to UnsupportedOperationException); (6) CatalogRegister.checkCatalogExist L193 `throw failedException` can NPE if the first attempt threw SQLException-then-retries-exhausted path never assigned it (failedException initialized null). Sites marked keep are deliberate isolation: background-refresh loop guards (CatalogConnectorManager L188/192/221/241/271), retry loops (CatalogRegister L187/214), readiness probe (L66), best-effort cleanup (GravitinoMetadata L287, GravitinoConnector L306), compensate-then-rethrow (GravitinoMetadata L283), per-bundle dev-mode plugin isolation (PluginManager L237). The large FAILED_PRECONDITION cluster in util/json + plugin manager reflects reflection into Trino internals whose dominant failure mode is Trino-version incompatibility/misdeployment rather than per-request errors.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConfig.java:192` | broadCatch | PatternSyntaxException compiling gravitino.trino.skip-catalog-patterns config | B9 | INVALID_ARGUMENT |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConfig.java:284` | broadCatch | URISyntaxException parsing Trino discovery.uri (cause dropped, not chained) | B9 | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnector.java:306` | broadCatch | GravitinoAdminClient.close during per-user session eviction; warn + swallow | B8 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorFactory.java:114` | broadCatch | connector bootstrap: CatalogRegister init, Trino JDBC connect, manager start -> GRAVITINO_RUNTIME_ERROR | B9 | UNAVAILABLE |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorFactory.java:226` | broadCatch | reflective instantiation of user-configured CatalogConnectorFactory class | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorPluginManager.java:141` | broadCatch | plugin directory scan (jar path resolution, listFiles NPE, per-plugin load) -> GRAVITINO_RUNTIME_ERROR | B9 | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorPluginManager.java:162` | broadCatch | uri.toURL of plugin jar file (paired with wrap at L163) | internal | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorPluginManager.java:163` | wrap | uri.toURL MalformedURLException while listing plugin jar files (recaught at L141) | internal | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorPluginManager.java:205` | broadCatch | reflective PluginClassLoader construction + ServiceLoader load of Trino plugin (engine version incompatibility) | B9 | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorPluginManager.java:237` | broadCatch | per-bundle pom plugin load failure in dev bundle mode; log error + continue (per-plugin isolation) | B9 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorPluginManager.java:248` | wrap | unresolved Maven artifact in plugin.bundles dev mode (recaught at L258) | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorPluginManager.java:258` | broadCatch | Maven artifact canonicalization/URL building for pom-based plugin load -> GRAVITINO_RUNTIME_ERROR | B9 | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoConnectorPluginManager.java:298` | broadCatch | internal Trino connector (hive/mysql/iceberg plugin) factory create with generated config; also re-wraps the plugin-not-found TrinoException from L287 | B1 | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoHandle.java:132` | broadCatch | Jackson deserialization of engine-passed handle JSON -> GRAVITINO_ILLEGAL_ARGUMENT (miscoded; handle round-trip failure is a bug not user input) | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoHandle.java:150` | broadCatch | Jackson serialization of handle to JSON for engine -> GRAVITINO_ILLEGAL_ARGUMENT (miscoded) | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoMetadata.java:283` | broadCatch | internal connector beginInsert/getColumnHandles during CTAS; compensating dropTable then rethrows original exception unchanged | B1 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/GravitinoMetadata.java:287` | broadCatch | best-effort catalogConnectorMetadata.dropTable during CTAS cleanup; warn + swallow, original error still rethrown | B8 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogConnectorContext.java:264` | broadCatch | catalog.supportsCredentials().getCredentials(); intended to absorb UnsupportedOperationException but silently downgrades real credential-vending failures to empty credentials | B8 | UNAVAILABLE |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogConnectorManager.java:188` | broadCatch | per-metalake loadCatalogs failure inside scheduled background refresh; log + continue with other metalakes | B8 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogConnectorManager.java:192` | broadCatch | whole loadMetalake pass (listMetalakes REST etc.); guard so scheduled executor task never dies | B8 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogConnectorManager.java:221` | broadCatch | metalake.listCatalogs REST call in background refresh; log + return | B8 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogConnectorManager.java:241` | broadCatch | unloadCatalog (Trino DROP CATALOG via JDBC) for a server-side-deleted catalog; log + continue | B9 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogConnectorManager.java:271` | broadCatch | per-catalog loadCatalog/reloadCatalog failure in background refresh; log + continue with other catalogs | B8 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogConnectorManager.java:300` | broadCatch | catalogRegister.registerCatalog (Trino CREATE CATALOG via JDBC loopback) -> GRAVITINO_CREATE_INTERNAL_CONNECTOR_ERROR | B9 | INTERNAL_DEP(502) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogConnectorManager.java:427` | broadCatch | catalog JSON parse + metalake retrieve + internal connector build; also re-codes nested TrinoExceptions (e.g. GRAVITINO_UNSUPPORTED_OPERATION at L411, METALAKE_NOT_EXISTS from retrieveMetalake) to GRAVITINO_OPERATION_FAILED | B8 | INTERNAL_DEP(502) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogRegister.java:66` | broadCatch | SELECT 1 readiness probe against Trino JDBC; warn + return false (deliberate startup poll) | B9 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogRegister.java:157` | broadCatch | catalog properties file read / catalog JSON serialize; also re-codes the ALREADY_EXISTS/DUPLICATED TrinoExceptions thrown at L135/L139/L148 to GRAVITINO_RUNTIME_ERROR (flattens ALREADY_EXISTS) | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogRegister.java:187` | broadCatch | transient non-SQL failure of SHOW CATALOGS; retry with 5s backoff up to 6 times (deliberate retry loop) | B9 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogRegister.java:194` | broadCatch | SHOW CATALOGS SQLException or exhausted retries (also NPE if failedException never set) -> GRAVITINO_RUNTIME_ERROR | B9 | UNAVAILABLE |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogRegister.java:214` | broadCatch | transient non-SQL failure executing CREATE/DROP CATALOG SQL; retry with backoff (deliberate retry loop) | B9 | keep |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogRegister.java:221` | broadCatch | CREATE/DROP CATALOG SQLException or exhausted retries -> GRAVITINO_RUNTIME_ERROR (flattens duplicate-catalog vs engine-down) | B9 | INTERNAL_DEP(502) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/CatalogRegister.java:241` | broadCatch | checkCatalogExist + DROP CATALOG via Trino JDBC during unregister -> GRAVITINO_RUNTIME_ERROR | B9 | INTERNAL_DEP(502) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/catalog/jdbc/mysql/MysqlColumnDefaultValueConverter.java:97` | broadCatch | number/date/decimal parse of MySQL column default value from backend metadata -> NOT_SUPPORTED (cause dropped, message kept; also hides ClassCastException bugs) | B1 | UNIMPLEMENTED |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/system/storedprocedure/AlterCatalogStoredProcedure.java:160` | broadCatch | metalake.alterCatalog REST call + reload; re-codes nested GRAVITINO_OPERATION_FAILED TrinoException at L146 to GRAVITINO_UNSUPPORTED_OPERATION (misleading code) | B8 | INTERNAL_DEP(502) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/system/storedprocedure/CreateCatalogStoredProcedure.java:135` | broadCatch | metalake.createCatalog REST call + reload; re-codes nested GRAVITINO_OPERATION_FAILED at L120 to GRAVITINO_UNSUPPORTED_OPERATION; flattens invalid-property errors | B8 | INTERNAL_DEP(502) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/system/storedprocedure/DropCatalogStoredProcedure.java:131` | broadCatch | metalake.dropCatalog REST call + reload; re-codes nested NOT_EXISTS/OPERATION_FAILED TrinoExceptions at L101/L120 to GRAVITINO_UNSUPPORTED_OPERATION | B8 | INTERNAL_DEP(502) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/system/storedprocedure/GravitinoStoredProcedureFactory.java:72` | broadCatch | reflective MethodHandle lookup in createStoredProcedure -> GRAVITINO_UNSUPPORTED_OPERATION (miscoded; this is a bootstrap bug) | internal | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/AbstractTypedJacksonModule.java:115` | broadCatch | Jackson serializeWithType of typed handle (paired with wrap at L116) | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/AbstractTypedJacksonModule.java:116` | wrap | Jackson serializeWithType failure during handle serialization for engine | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/BlockJsonSerde.java:79` | broadCatch | reflective writeBlock invoke (paired with wrap at L80) | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/BlockJsonSerde.java:80` | wrap | reflective BlockSerdeUtil.writeBlock invoke during Block JSON serialization | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/BlockJsonSerde.java:119` | broadCatch | reflective readBlock invoke (paired with wrap at L120) | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/BlockJsonSerde.java:120` | wrap | reflective BlockSerdeUtil.readBlock invoke during Block JSON deserialization | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:68` | broadCatch | buildMapper: plugin manager instance + TypeManager reflection + mapper creation -> GRAVITINO_RUNTIME_ERROR | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:79` | broadCatch | buildJsonType: TypeManager reflection + JSON type lookup -> GRAVITINO_RUNTIME_ERROR | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:99` | broadCatch | reflection into Trino InternalTypeManager/TypeRegistry/FeaturesConfig (paired with wrap at L100) | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:100` | wrap | reflection into io.trino.type.InternalTypeManager/TypeRegistry (Trino version mismatch) | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:132` | broadCatch | reflective pluginName field access in handle nameResolver (paired with wrap at L133) | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:133` | wrap | reflective pluginName field read on plugin classloader in handle nameResolver | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:146` | wrap | ClassNotFoundException resolving handle class from serialized 'loader:class' name | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:230` | broadCatch | createMapper: module registration + BlockEncodingSerde reflection (paired with wrap at L231) | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/JsonCodec.java:231` | wrap | ObjectMapper/module construction incl. reflection into Trino internals | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/TypeSignatureDeserializer.java:44` | broadCatch | classload of TypeSignatureTranslator (paired with wrap at L45) | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/TypeSignatureDeserializer.java:45` | wrap | classload/getDeclaredMethod of io.trino.sql.analyzer.TypeSignatureTranslator (version mismatch) | internal | FAILED_PRECONDITION |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/TypeSignatureDeserializer.java:54` | broadCatch | reflective parseTypeSignature invoke (paired with wrap at L55) | B9 | INTERNAL_BUG(500) |
| `trino-connector/trino-connector/src/main/java/org/apache/gravitino/trino/connector/util/json/TypeSignatureDeserializer.java:55` | wrap | reflective parseTypeSignature invoke during TypeSignature deserialization (note: hardcodes 'varchar(255)' instead of value) | B9 | INTERNAL_BUG(500) |

## server (134 sites; raw rg hits: 134)

> Raw hit breakdown: 1 `throw new RuntimeException` (HealthOperations.java:162) + 133 `catch (Exception` = 134. All 134 appear exactly once in sites[]. 121 of the 133 catches are the uniform REST top-of-route dispatch pattern `catch (Exception e) -> return ExceptionHandlers.handleXxxException(op, name, parent, e)` (some with a LOG line first: StatisticOperations 323/401/458, MetalakeOperations 141/203, CatalogOperations 200/244). These sites are marked proposedCode=keep because the catch-and-dispatch IS the intended B7 classification point; the actual flattening lives in the shared classifier they all delegate to: server/src/main/java/org/apache/gravitino/server/web/rest/ExceptionHandlers.java — its BaseExceptionHandler fallback (line ~1099, Utils.internalError) maps every unrecognized exception to HTTP 500, so backend/dependency outages (HMS thrift down, JDBC refused, Kafka unreachable, metadata DB down surfacing as unwrapped RuntimeException) and genuine server bugs are indistinguishable on the wire. The single high-leverage fix for all 121 sites is one residual-classification tier in that fallback: transport/connection failure signatures -> INTERNAL_DEP(502) or UNAVAILABLE; everything else -> INTERNAL_BUG(500). ExceptionHandlers.java itself produced no raw grep hits (it uses instanceof chains, not catch(Exception)), so it is not a row here, but it is the root cause behind the 121 keep rows. Genuinely problematic sites: AuthnOperations.java:53 (principal-resolution failure -> 500 instead of 401), GravitinoInterceptionService.java:184 (entity-store outage during user validation -> opaque 500; should be INTERNAL_DEP(502)/UNAVAILABLE), and GravitinoInterceptionService.java:227 (outer catch encloses methodInvocation.proceed(), so any exception escaping a resource method is mislabeled "Authorization failed due to system internal error" 500 — should rethrow non-authz exceptions to route-level handlers; own residual -> INTERNAL_BUG(500)). Deliberate/correct isolation catches marked keep: GravitinoInterceptionService.java:269 (event-listener isolation, B5), HealthOperations 162/195/205/217 (probe failures intentionally become health DOWN -> 503), GravitinoServer 238/254/265/280 (process startup/shutdown fail-fast, B11), ConfigServlet.java:113 (residual guard after specific IllegalState/IO catches). Scope verified: server/src/main only, no test paths matched.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `server/src/main/java/org/apache/gravitino/server/GravitinoServer.java:238` | broadCatch | Jetty/GravitinoEnv startup failure in main; log + System.exit(-1) | B11 | keep |
| `server/src/main/java/org/apache/gravitino/server/GravitinoServer.java:254` | broadCatch | gracefulStop failure inside shutdown hook | B11 | keep |
| `server/src/main/java/org/apache/gravitino/server/GravitinoServer.java:265` | broadCatch | gracefulStop failure after server.join | B11 | keep |
| `server/src/main/java/org/apache/gravitino/server/GravitinoServer.java:280` | broadCatch | conf file load/parse (incl. IOException) rewrapped as IllegalArgumentException at startup | B11 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/ConfigServlet.java:113` | broadCatch | residual guard after specific catches (Jackson serialization etc.) -> 500 | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/filter/GravitinoInterceptionService.java:184` | broadCatch | user-existence pre-check against entity store; DB outage flattened to opaque 500 'Failed to validate user' | B6 | INTERNAL_DEP(502) |
| `server/src/main/java/org/apache/gravitino/server/web/filter/GravitinoInterceptionService.java:227` | broadCatch | authz expression eval errors AND any exception escaping methodInvocation.proceed(), mislabeled 'Authorization failed' 500; should not catch proceed() exceptions | B6 | INTERNAL_BUG(500) |
| `server/src/main/java/org/apache/gravitino/server/web/filter/GravitinoInterceptionService.java:269` | broadCatch | event-bus dispatch of authz denial event (deliberate listener isolation) | B5 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/AuthnOperations.java:53` | broadCatch | principal resolution/doAs failure flattened to 500 | B7 | UNAUTHENTICATED |
| `server/src/main/java/org/apache/gravitino/server/web/rest/CatalogOperations.java:128` | broadCatch | catalog LIST dispatcher failures -> handleCatalogException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/CatalogOperations.java:163` | broadCatch | catalog CREATE failures incl. connector init -> handleCatalogException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/CatalogOperations.java:200` | broadCatch | testConnection failures incl. backend connect -> handleTestConnectionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/CatalogOperations.java:244` | broadCatch | catalog ENABLE/DISABLE dispatcher failures -> handleCatalogException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/CatalogOperations.java:275` | broadCatch | catalog LOAD failures incl. connector init -> handleCatalogException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/CatalogOperations.java:312` | broadCatch | catalog ALTER failures incl. connector -> handleCatalogException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/CatalogOperations.java:347` | broadCatch | catalog DROP failures incl. connector -> handleCatalogException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FilesetOperations.java:124` | broadCatch | fileset LIST failures incl. storage connector -> handleFilesetException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FilesetOperations.java:182` | broadCatch | fileset CREATE failures incl. storage connector -> handleFilesetException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FilesetOperations.java:214` | broadCatch | fileset LOAD failures incl. storage connector -> handleFilesetException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FilesetOperations.java:266` | broadCatch | fileset files LIST failures incl. storage connector -> handleFilesetException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FilesetOperations.java:308` | broadCatch | fileset ALTER failures incl. storage connector -> handleFilesetException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FilesetOperations.java:349` | broadCatch | fileset DROP failures incl. storage connector -> handleFilesetException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FilesetOperations.java:402` | broadCatch | fileset file location GET failures incl. storage connector -> handleFilesetException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FunctionOperations.java:140` | broadCatch | function LIST failures incl. connector -> handleFunctionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FunctionOperations.java:195` | broadCatch | function REGISTER failures incl. connector -> handleFunctionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FunctionOperations.java:228` | broadCatch | function LOAD failures incl. connector -> handleFunctionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FunctionOperations.java:271` | broadCatch | function ALTER failures incl. connector -> handleFunctionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/FunctionOperations.java:312` | broadCatch | function DROP failures incl. connector -> handleFunctionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/GroupOperations.java:94` | broadCatch | group GET dispatcher failures -> handleGroupException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/GroupOperations.java:121` | broadCatch | group ADD dispatcher failures -> handleGroupException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/GroupOperations.java:161` | broadCatch | group REMOVE dispatcher failures -> handleGroupException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/GroupOperations.java:190` | broadCatch | group LIST dispatcher failures -> handleGroupException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/HealthOperations.java:162` | wrap | entityStore.exists IOException tunneled through supplyAsync lambda | B3 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/HealthOperations.java:195` | broadCatch | unexpected entity-store probe failure -> health DOWN (503) | B3 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/HealthOperations.java:205` | broadCatch | GravitinoEnv/entityStore not initialized -> null -> DOWN | internal | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/HealthOperations.java:217` | broadCatch | probe-timeout config read failure -> default value | internal | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:138` | broadCatch | job-template LIST dispatcher failures -> handleJobTemplateException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:174` | broadCatch | job-template REGISTER dispatcher failures -> handleJobTemplateException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:205` | broadCatch | job-template GET dispatcher failures -> handleJobTemplateException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:237` | broadCatch | job-template DELETE dispatcher failures -> handleJobTemplateException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:273` | broadCatch | job-template ALTER dispatcher failures -> handleJobTemplateException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:314` | broadCatch | job LIST dispatcher failures -> handleJobException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:341` | broadCatch | job GET dispatcher failures -> handleJobException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:381` | broadCatch | job RUN failures incl. job executor -> handleJobException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/JobOperations.java:407` | broadCatch | job CANCEL failures incl. job executor -> handleJobException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetadataObjectCredentialOperations.java:137` | broadCatch | credential GET dispatcher failures -> handleCredentialException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetadataObjectPolicyOperations.java:151` | broadCatch | object-policy GET dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetadataObjectPolicyOperations.java:235` | broadCatch | object-policy LIST dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetadataObjectPolicyOperations.java:282` | broadCatch | object-policy ASSOCIATE dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetadataObjectRoleOperations.java:89` | broadCatch | object roles LIST dispatcher failures -> handleRoleException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetadataObjectTagOperations.java:155` | broadCatch | object-tag GET dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetadataObjectTagOperations.java:246` | broadCatch | object-tag LIST dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetadataObjectTagOperations.java:288` | broadCatch | object-tag ASSOCIATE dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetalakeOperations.java:104` | broadCatch | metalake LIST dispatcher failures -> handleMetalakeException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetalakeOperations.java:141` | broadCatch | metalake CREATE dispatcher failures -> handleMetalakeException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetalakeOperations.java:169` | broadCatch | metalake LOAD dispatcher failures -> handleMetalakeException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetalakeOperations.java:203` | broadCatch | metalake ENABLE/DISABLE dispatcher failures -> handleMetalakeException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetalakeOperations.java:238` | broadCatch | metalake ALTER dispatcher failures -> handleMetalakeException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/MetalakeOperations.java:269` | broadCatch | metalake DROP dispatcher failures -> handleMetalakeException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:121` | broadCatch | model LIST dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:152` | broadCatch | model GET dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:196` | broadCatch | model REGISTER dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:238` | broadCatch | model DELETE dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:317` | broadCatch | model LIST_VERSIONS dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:355` | broadCatch | model version GET dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:394` | broadCatch | model version-by-alias GET dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:444` | broadCatch | model version LINK dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:492` | broadCatch | model version DELETE dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:542` | broadCatch | model version-by-alias DELETE dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:596` | broadCatch | model version ALTER dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:649` | broadCatch | model version-by-alias ALTER dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:692` | broadCatch | model ALTER dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:731` | broadCatch | model version URI GET dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ModelOperations.java:771` | broadCatch | model version-by-alias URI GET dispatcher failures -> handleModelException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/OwnerOperations.java:100` | broadCatch | owner GET dispatcher failures -> handleOwnerException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/OwnerOperations.java:134` | broadCatch | owner SET dispatcher failures -> handleOwnerException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PartitionOperations.java:121` | broadCatch | partition LIST failures incl. connector -> handlePartitionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PartitionOperations.java:164` | broadCatch | partition GET failures incl. connector -> handlePartitionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PartitionOperations.java:210` | broadCatch | partition CREATE failures incl. connector -> handlePartitionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PartitionOperations.java:267` | broadCatch | partition DROP failures incl. connector -> handlePartitionException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PermissionOperations.java:103` | broadCatch | grant roles to user failures incl. authz plugin -> handleUserPermissionOperationException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PermissionOperations.java:132` | broadCatch | grant roles to group failures incl. authz plugin -> handleGroupPermissionOperationException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PermissionOperations.java:161` | broadCatch | revoke roles from user failures incl. authz plugin -> handleUserPermissionOperationException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PermissionOperations.java:190` | broadCatch | revoke roles from group failures incl. authz plugin -> handleGroupPermissionOperationException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PermissionOperations.java:238` | broadCatch | grant privilege to role failures incl. authz plugin -> handleRolePermissionOperationException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PermissionOperations.java:286` | broadCatch | revoke privilege from role failures incl. authz plugin -> handleRolePermissionOperationException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PermissionOperations.java:337` | broadCatch | update role privileges failures incl. authz plugin -> handleRolePermissionOperationException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PolicyOperations.java:130` | broadCatch | policy LIST dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PolicyOperations.java:164` | broadCatch | policy CREATE dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PolicyOperations.java:191` | broadCatch | policy GET dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PolicyOperations.java:224` | broadCatch | policy ALTER dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PolicyOperations.java:262` | broadCatch | policy ENABLE/DISABLE dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PolicyOperations.java:292` | broadCatch | policy DELETE dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/PolicyOperations.java:331` | broadCatch | policy LIST-info dispatcher failures -> handlePolicyException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/RoleOperations.java:104` | broadCatch | role LIST dispatcher failures -> handleRoleException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/RoleOperations.java:129` | broadCatch | role GET dispatcher failures -> handleRoleException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/RoleOperations.java:199` | broadCatch | role CREATE failures incl. authz plugin -> handleRoleException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/RoleOperations.java:226` | broadCatch | role DELETE failures incl. authz plugin -> handleRoleException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/SchemaOperations.java:126` | broadCatch | schema LIST failures incl. connector -> handleSchemaException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/SchemaOperations.java:159` | broadCatch | schema CREATE failures incl. connector -> handleSchemaException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/SchemaOperations.java:190` | broadCatch | schema LOAD failures incl. connector -> handleSchemaException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/SchemaOperations.java:226` | broadCatch | schema ALTER failures incl. connector -> handleSchemaException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/SchemaOperations.java:262` | broadCatch | schema DROP failures incl. connector -> handleSchemaException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/StatisticOperations.java:136` | broadCatch | statistic LIST dispatcher failures -> handleStatisticException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/StatisticOperations.java:195` | broadCatch | statistic UPDATE dispatcher failures -> handleStatisticException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/StatisticOperations.java:243` | broadCatch | statistic DROP dispatcher failures -> handleStatisticException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/StatisticOperations.java:323` | broadCatch | partition-stats LIST dispatcher failures -> handlePartitionStatsException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/StatisticOperations.java:401` | broadCatch | partition-stats UPDATE dispatcher failures -> handlePartitionStatsException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/StatisticOperations.java:458` | broadCatch | partition-stats DROP dispatcher failures -> handlePartitionStatsException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TableOperations.java:111` | broadCatch | table LIST failures incl. HMS/JDBC connector -> handleTableException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TableOperations.java:157` | broadCatch | table CREATE failures incl. HMS/JDBC connector -> handleTableException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TableOperations.java:194` | broadCatch | table LOAD failures incl. HMS/JDBC connector -> handleTableException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TableOperations.java:231` | broadCatch | table ALTER failures incl. HMS/JDBC connector -> handleTableException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TableOperations.java:284` | broadCatch | table DROP failures incl. HMS/JDBC connector -> handleTableException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TagOperations.java:137` | broadCatch | tag LIST dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TagOperations.java:165` | broadCatch | tag CREATE dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TagOperations.java:193` | broadCatch | tag GET dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TagOperations.java:226` | broadCatch | tag ALTER dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TagOperations.java:256` | broadCatch | tag DELETE dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TagOperations.java:293` | broadCatch | tag objects LIST dispatcher failures -> handleTagException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TopicOperations.java:104` | broadCatch | topic LIST failures incl. Kafka connector -> handleTopicException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TopicOperations.java:152` | broadCatch | topic CREATE failures incl. Kafka connector -> handleTopicException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TopicOperations.java:185` | broadCatch | topic LOAD failures incl. Kafka connector -> handleTopicException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TopicOperations.java:228` | broadCatch | topic ALTER failures incl. Kafka connector -> handleTopicException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/TopicOperations.java:268` | broadCatch | topic DROP failures incl. Kafka connector -> handleTopicException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/UserOperations.java:102` | broadCatch | user GET dispatcher failures -> handleUserException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/UserOperations.java:144` | broadCatch | user LIST dispatcher failures -> handleUserException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/UserOperations.java:174` | broadCatch | user ADD dispatcher failures -> handleUserException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/UserOperations.java:215` | broadCatch | user REMOVE dispatcher failures -> handleUserException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ViewOperations.java:88` | broadCatch | view LIST failures incl. connector -> handleViewException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ViewOperations.java:126` | broadCatch | view CREATE failures incl. connector -> handleViewException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ViewOperations.java:153` | broadCatch | view LOAD failures incl. connector -> handleViewException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ViewOperations.java:186` | broadCatch | view ALTER failures incl. connector -> handleViewException | B7 | keep |
| `server/src/main/java/org/apache/gravitino/server/web/rest/ViewOperations.java:216` | broadCatch | view DROP failures incl. connector -> handleViewException | B7 | keep |

## server-common (34 sites; raw rg hits: 34)

> Raw counts: 9 `throw new RuntimeException` + 25 `catch (Exception` = 34 in server-common/src/main (main sources only; module has no test-path hits in scope). Themes: (1) server-common is dominated by boot-time fail-fast sites (B11: JettyServer, config/factory reflection, key decode) where RuntimeException/IllegalArgumentException wraps are acceptable as-is — marked keep; several bare wraps (JettyServer:355, KerberosAuthenticator:96, JcasbinAuthorizer:227) would benefit from messages but need no taxonomy change. (2) The real flattening risk is the jcasbin authorization path: entity-store failures (metadata DB down) are silently converted into authorization denies (JcasbinAuthorizer:461/:711/:1180) or silently dropped list entries (MetadataAuthzHelper:409) — dependency-down masquerades as PERMISSION_DENIED/empty results instead of INTERNAL_DEP(502); classify-then-preserve needed so only genuine policy misses deny. (3) AuthenticationFilter:120 catches everything escaping the whole downstream filter chain, not just auth failures — the catch should be scoped so authenticator failures map to UNAUTHENTICATED and chain exceptions propagate to the container/Jersey mapper. (4) JwksTokenValidator:173 flattens remote JWKS key-fetch outages into 401 UNAUTHENTICATED; the auth-failure branch is correct but IdP-unreachable should split to INTERNAL_DEP(502)/UNAVAILABLE. Deliberate, correctly-scoped isolation catches (audit event dispatch HttpAuditFilter:187, background poller JcasbinChangeListener:124, Hadoop-derived Kerberos realm helpers, lenient claim parsing) are marked keep.

| File:Line | Kind | Wraps | Boundary | Proposed |
|---|---|---|---|---|
| `server-common/src/main/java/org/apache/gravitino/server/ServerConfig.java:65` | broadCatch | config file load/parse at boot, rethrown as IllegalArgumentException with path | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/AuthenticationFilter.java:120` | broadCatch | any exception from authenticators AND downstream filter chain, flattened to JSON 500 via sendAuthErrorResponse | B7 | UNAUTHENTICATED |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/AuthenticatorFactory.java:63` | broadCatch | ClassNotFound/reflective ctor failure for authenticator (rethrown wrapped at :65) | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/AuthenticatorFactory.java:65` | wrap | reflection instantiation of configured Authenticator class at boot | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/JwksTokenValidator.java:102` | broadCatch | JWKS source construction from configured URI at boot, rethrown as IllegalArgumentException | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/JwksTokenValidator.java:173` | broadCatch | JWT sig/claims validation AND remote JWKS key-fetch outages, both flattened to 401 | B7 | UNAUTHENTICATED |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/JwksTokenValidator.java:193` | broadCatch | claim extraction in diagnostic-logging helper, returns "unknown" | internal | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/JwksTokenValidator.java:225` | broadCatch | groups claim parse, lenient per-field fallback with warn log | internal | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/KerberosAuthenticator.java:96` | wrap | PrivilegedActionException during GSSManager init at boot (bare wrap, no message) | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/KerberosAuthenticator.java:146` | broadCatch | GSS/SPNEGO token validation failure, fail-closed to 401 | B7 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/KerberosServerUtils.java:66` | broadCatch | KerberosPrincipal default-realm lookup, documented deliberate swallow returning null | internal | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/KerberosServerUtils.java:104` | broadCatch | sun.security.krb5 reflective realm mapping, documented deliberate swallow | internal | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/OAuth2TokenAuthenticator.java:85` | broadCatch | unexpected JWT parse/validation errors, fail-closed to UnauthorizedException 401 | B7 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/OAuthTokenValidatorFactory.java:57` | broadCatch | reflective instantiation of OAuth token validator class at boot, rethrown as IllegalArgumentException | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/StaticSignKeyValidator.java:176` | broadCatch | groups claim parse, lenient per-field fallback with warn log | internal | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authentication/StaticSignKeyValidator.java:192` | broadCatch | static sign key decode (KeyFactory/HMAC) at boot, rethrown as IllegalArgumentException | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/GravitinoAuthorizerProvider.java:58` | broadCatch | reflective instantiation of configured GravitinoAuthorizer at boot, rethrown as IllegalArgumentException | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/MetadataAuthzHelper.java:409` | broadCatch | per-entity authz expression eval failure; entity silently dropped from list results (store-down empties listings) | B4 | INTERNAL_DEP(502) |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/MetadataAuthzHelper.java:501` | broadCatch | best-effort owner-relation cache preload, warn and continue | B3 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/MetadataIdConverter.java:84` | wrap | entityStore.get IOException during authz metadata-id resolution | B3 | INTERNAL_DEP(502) |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/expression/AuthorizationExpressionEvaluator.java:160` | wrap | OgnlException evaluating authz expression (may also wrap authorizer-thrown errors) | B4 | INTERNAL_BUG(500) |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/jcasbin/JcasbinAuthorizer.java:227` | wrap | IOException reading bundled jcasbin_model.conf classpath resource at init (bare wrap) | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/jcasbin/JcasbinAuthorizer.java:461` | broadCatch | entity-store role/user id resolution failure flattened to silent deny (return false) | B3 | INTERNAL_DEP(502) |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/jcasbin/JcasbinAuthorizer.java:711` | broadCatch | entity-store user-info load failure flattened to silent deny (return false) | B3 | INTERNAL_DEP(502) |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/jcasbin/JcasbinAuthorizer.java:1162` | broadCatch | entityStore.batchGet of stale role entities; designed fallback trigger to per-role get | B3 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/jcasbin/JcasbinAuthorizer.java:1180` | broadCatch | entityStore.get per-role fallback failure; role policies silently skipped, ends as stale/deny | B3 | INTERNAL_DEP(502) |
| `server-common/src/main/java/org/apache/gravitino/server/authorization/jcasbin/JcasbinChangeListener.java:124` | broadCatch | owner-change DB poll cycle failure, warn and retry next cycle (interrupt-aware) | B3 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/web/HttpAuditFilter.java:187` | broadCatch | audit event dispatch failure, deliberate listener isolation | B5 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/web/JettyServer.java:198` | wrap | Jetty BindException, port already in use at boot | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/web/JettyServer.java:200` | broadCatch | any other Jetty server.start failure (rethrown wrapped at :202) | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/web/JettyServer.java:202` | wrap | generic Jetty server.start failure at boot | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/web/JettyServer.java:242` | broadCatch | Jetty server.stop failure, deliberate swallow during shutdown | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/web/JettyServer.java:338` | wrap | web UI WAR file missing at resolved path (precondition throw, no cause) | B11 | keep |
| `server-common/src/main/java/org/apache/gravitino/server/web/JettyServer.java:355` | wrap | IOException creating temp dir for WAR extraction (bare wrap, no message) | B11 | keep |
