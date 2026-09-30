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

tasks.all {
    enabled = false
}
// Modules that Hive 2 brings onto the Spark connector test runtime classpaths through Spark's own
// path (spark-hive -> hive-metastore / hive-exec / hive-serde -> ...), which the per-dependency
// excludes in the version modules do not cover. Gradle 8.2 did not resolve them there; Gradle 8.14+
// does, and they break the ITs (Jetty 7 fails the embedded server, parquet-hadoop-bundle 1.8
// breaks Spark's Parquet with NoSuchFieldError: BROTLI). The version modules exclude these from
// testRuntimeClasspath, which restores the classpaths resolved under Gradle 8.2.
extra["hive2TestRuntimeExcludes"] =
  listOf(
  "com.jamesmurty.utils:java-xmlbuilder",
  "commons-beanutils:commons-beanutils",
  "commons-beanutils:commons-beanutils-core",
  "commons-configuration:commons-configuration",
  "commons-digester:commons-digester",
  "commons-httpclient:commons-httpclient",
  "commons-net:commons-net",
  "javax.servlet.jsp:jsp-api",
  "javax.transaction:transaction-api",
  "net.java.dev.jets3t:jets3t",
  "org.apache.directory.api:api-asn1-api",
  "org.apache.directory.api:api-util",
  "org.apache.directory.server:apacheds-i18n",
  "org.apache.directory.server:apacheds-kerberos-codec",
  "org.apache.hadoop:hadoop-auth",
  "org.apache.hadoop:hadoop-common",
  "org.apache.logging.log4j:log4j-web",
  "org.apache.parquet:parquet-hadoop-bundle",
  "org.eclipse.jetty.aggregate:jetty-all",
  "org.eclipse.jetty.orbit:javax.servlet",
  "org.htrace:htrace-core",
  "xmlenc:xmlenc"
  )
