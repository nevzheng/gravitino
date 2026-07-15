/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.server.web.rest.v1.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rest.v1.model.TableResource;
import org.apache.gravitino.rest.v1.model.TableUpdateRequest;
import org.apache.gravitino.server.web.ObjectMapperProvider;

/** Maps a V1 full desired-state table PUT into supported internal table changes. */
public final class TableMutationMapper {

  private TableMutationMapper() {}

  /**
   * Builds the internal changes that replace the mutable V1 table state currently supported by
   * Gravitino.
   *
   * <p>Every V1 table PUT contains the entire desired state. The current dispatcher can replace a
   * table comment, but it has no correct replacement primitive for storage, provider options,
   * physical layout, or the complete column/index graph. Those fields must therefore equal the
   * loaded representation; a differing desired state fails before the dispatcher is called rather
   * than risking a partial update.
   *
   * @param current current public V1 table representation.
   * @param desired complete desired V1 mutable table state.
   * @return ordered supported internal changes, potentially empty when the table already matches.
   * @throws UnsupportedOperationException when the requested replacement needs an unavailable
   *     atomic internal capability.
   */
  public static TableChange[] toChanges(TableResource current, TableUpdateRequest desired) {
    Objects.requireNonNull(current, "current cannot be null");
    Objects.requireNonNull(desired, "desired cannot be null");

    requireUnchanged("columns", current.getColumns(), desired.getColumns());
    requireUnchanged("storage", current.getStorage(), desired.getStorage());
    requireUnchanged("icebergOptions", current.getIcebergOptions(), desired.getIcebergOptions());
    requireUnchanged("hiveOptions", current.getHiveOptions(), desired.getHiveOptions());
    requireUnchanged(
        "clickhouseOptions", current.getClickhouseOptions(), desired.getClickhouseOptions());
    requireUnchanged("mysqlOptions", current.getMysqlOptions(), desired.getMysqlOptions());
    requireUnchanged("partitioning", current.getPartitioning(), desired.getPartitioning());
    requireUnchanged("distribution", current.getDistribution(), desired.getDistribution());
    requireUnchanged("sortOrders", current.getSortOrders(), desired.getSortOrders());
    requireUnchanged("indexes", current.getIndexes(), desired.getIndexes());

    List<TableChange> changes = new ArrayList<>();
    if (!Objects.equals(current.getComment(), desired.getComment())) {
      changes.add(TableChange.updateComment(desired.getComment()));
    }
    return changes.toArray(new TableChange[0]);
  }

  private static void requireUnchanged(String field, Object current, Object desired) {
    JsonNode currentJson = ObjectMapperProvider.objectMapper().valueToTree(current);
    JsonNode desiredJson = ObjectMapperProvider.objectMapper().valueToTree(desired);
    if (!currentJson.equals(desiredJson)) {
      throw new UnsupportedOperationException(
          "V1 table " + field + " replacement is not supported by the current dispatcher.");
    }
  }
}
