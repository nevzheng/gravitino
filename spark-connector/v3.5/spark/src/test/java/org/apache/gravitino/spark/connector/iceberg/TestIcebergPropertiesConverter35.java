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
package org.apache.gravitino.spark.connector.iceberg;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.iceberg.rest.RESTCatalogWithEncryption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestIcebergPropertiesConverter35 {

  @Test
  void testRestBackendUsesEncryptionAwareCatalog() {
    Map<String, String> converted =
        IcebergPropertiesConverter35.getInstance()
            .toSparkCatalogProperties(
                ImmutableMap.of(
                    IcebergPropertiesConstants.GRAVITINO_ICEBERG_CATALOG_BACKEND,
                    IcebergPropertiesConstants.ICEBERG_CATALOG_BACKEND_REST,
                    IcebergPropertiesConstants.GRAVITINO_ICEBERG_CATALOG_URI,
                    "http://localhost:9001/iceberg"));

    Assertions.assertEquals(
        RESTCatalogWithEncryption.class.getName(),
        converted.get(IcebergPropertiesConstants.ICEBERG_CATALOG_IMPL));
    Assertions.assertFalse(converted.containsKey(IcebergPropertiesConstants.ICEBERG_CATALOG_TYPE));
  }
}
