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

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.spark.connector.PropertiesConverter;
import org.apache.iceberg.rest.RESTCatalogWithEncryption;

/** Converts Iceberg catalog properties for Spark 3.5 and Iceberg 1.11. */
public final class IcebergPropertiesConverter35 implements PropertiesConverter {

  private static final IcebergPropertiesConverter35 INSTANCE = new IcebergPropertiesConverter35();

  private final IcebergPropertiesConverter delegate = IcebergPropertiesConverter.getInstance();

  private IcebergPropertiesConverter35() {}

  /**
   * Returns the singleton Spark 3.5 Iceberg property converter.
   *
   * @return the singleton converter
   */
  public static IcebergPropertiesConverter35 getInstance() {
    return INSTANCE;
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, String> toSparkCatalogProperties(Map<String, String> properties) {
    Map<String, String> converted = new HashMap<>(delegate.toSparkCatalogProperties(properties));
    String backend = properties.get(IcebergPropertiesConstants.GRAVITINO_ICEBERG_CATALOG_BACKEND);
    if (backend != null
        && IcebergPropertiesConstants.ICEBERG_CATALOG_BACKEND_REST.equalsIgnoreCase(backend)) {
      converted.remove(IcebergPropertiesConstants.ICEBERG_CATALOG_TYPE);
      converted.put(
          IcebergPropertiesConstants.ICEBERG_CATALOG_IMPL,
          RESTCatalogWithEncryption.class.getName());
    }
    return converted;
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, String> toGravitinoTableProperties(Map<String, String> properties) {
    return delegate.toGravitinoTableProperties(properties);
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, String> toSparkTableProperties(Map<String, String> properties) {
    return delegate.toSparkTableProperties(properties);
  }
}
