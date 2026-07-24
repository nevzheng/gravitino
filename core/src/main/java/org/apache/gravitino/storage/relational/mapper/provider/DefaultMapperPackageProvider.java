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
package org.apache.gravitino.storage.relational.mapper.provider;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.storage.relational.mapper.CatalogMetaMapper;
import org.apache.gravitino.storage.relational.mapper.CatalogRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.EntityChangeLogMapper;
import org.apache.gravitino.storage.relational.mapper.EntityDeletionMapper;
import org.apache.gravitino.storage.relational.mapper.FilesetMetaMapper;
import org.apache.gravitino.storage.relational.mapper.FilesetRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.FilesetVersionMapper;
import org.apache.gravitino.storage.relational.mapper.FunctionMetaMapper;
import org.apache.gravitino.storage.relational.mapper.FunctionRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.FunctionVersionMetaMapper;
import org.apache.gravitino.storage.relational.mapper.GroupMetaMapper;
import org.apache.gravitino.storage.relational.mapper.GroupRoleRelMapper;
import org.apache.gravitino.storage.relational.mapper.IdentityRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.JobMetaMapper;
import org.apache.gravitino.storage.relational.mapper.JobTemplateMetaMapper;
import org.apache.gravitino.storage.relational.mapper.JobTemplateRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeMetaMapper;
import org.apache.gravitino.storage.relational.mapper.MetalakeRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.ModelMetaMapper;
import org.apache.gravitino.storage.relational.mapper.ModelRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.ModelVersionAliasRelMapper;
import org.apache.gravitino.storage.relational.mapper.ModelVersionMetaMapper;
import org.apache.gravitino.storage.relational.mapper.OwnerMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetaMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyMetadataObjectRelMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.PolicyVersionMapper;
import org.apache.gravitino.storage.relational.mapper.RoleMetaMapper;
import org.apache.gravitino.storage.relational.mapper.SchemaMetaMapper;
import org.apache.gravitino.storage.relational.mapper.SchemaRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.SecurableObjectMapper;
import org.apache.gravitino.storage.relational.mapper.StatisticMetaMapper;
import org.apache.gravitino.storage.relational.mapper.TableColumnMapper;
import org.apache.gravitino.storage.relational.mapper.TableMetaMapper;
import org.apache.gravitino.storage.relational.mapper.TableRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.TableVersionMapper;
import org.apache.gravitino.storage.relational.mapper.TagMetaMapper;
import org.apache.gravitino.storage.relational.mapper.TagMetadataObjectRelMapper;
import org.apache.gravitino.storage.relational.mapper.TagRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.TopicMetaMapper;
import org.apache.gravitino.storage.relational.mapper.TopicRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.UserMetaMapper;
import org.apache.gravitino.storage.relational.mapper.UserRoleRelMapper;
import org.apache.gravitino.storage.relational.mapper.ViewMetaMapper;
import org.apache.gravitino.storage.relational.mapper.ViewRecoveryMapper;
import org.apache.gravitino.storage.relational.mapper.ViewVersionInfoMapper;

/** The default provider that supplies the primary mapper package for Gravitino. */
public class DefaultMapperPackageProvider implements MapperPackageProvider {

  @Override
  public List<Class<?>> getMapperClasses() {
    return ImmutableList.of(
        CatalogMetaMapper.class,
        CatalogRecoveryMapper.class,
        EntityChangeLogMapper.class,
        EntityDeletionMapper.class,
        FilesetMetaMapper.class,
        FilesetRecoveryMapper.class,
        FilesetVersionMapper.class,
        FunctionMetaMapper.class,
        FunctionRecoveryMapper.class,
        FunctionVersionMetaMapper.class,
        GroupMetaMapper.class,
        GroupRoleRelMapper.class,
        IdentityRecoveryMapper.class,
        JobMetaMapper.class,
        JobTemplateMetaMapper.class,
        JobTemplateRecoveryMapper.class,
        MetalakeMetaMapper.class,
        MetalakeRecoveryMapper.class,
        ModelMetaMapper.class,
        ModelRecoveryMapper.class,
        ModelVersionAliasRelMapper.class,
        ModelVersionMetaMapper.class,
        OwnerMetaMapper.class,
        PolicyMetadataObjectRelMapper.class,
        PolicyMetaMapper.class,
        PolicyRecoveryMapper.class,
        PolicyVersionMapper.class,
        RoleMetaMapper.class,
        SchemaMetaMapper.class,
        SchemaRecoveryMapper.class,
        SecurableObjectMapper.class,
        StatisticMetaMapper.class,
        TableColumnMapper.class,
        TableRecoveryMapper.class,
        TableMetaMapper.class,
        TagMetadataObjectRelMapper.class,
        TagMetaMapper.class,
        TagRecoveryMapper.class,
        TopicMetaMapper.class,
        TopicRecoveryMapper.class,
        UserMetaMapper.class,
        UserRoleRelMapper.class,
        TableVersionMapper.class,
        ViewMetaMapper.class,
        ViewRecoveryMapper.class,
        ViewVersionInfoMapper.class);
  }
}
