/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.connectors.jdbc.internal;

import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.geode.annotations.Experimental;
import org.apache.geode.cache.Cache;
import org.apache.geode.connectors.jdbc.internal.configuration.FieldMapping;
import org.apache.geode.connectors.jdbc.internal.configuration.RegionMapping;
import org.apache.geode.internal.cache.CacheService;
import org.apache.geode.pdx.internal.PdxType;

@Experimental
public interface JdbcConnectorService extends CacheService {

  void createRegionMapping(RegionMapping mapping)
      throws RegionMappingExistsException;

  void replaceRegionMapping(RegionMapping mapping)
      throws RegionMappingNotFoundException;

  void destroyRegionMapping(String regionName);

  boolean isMappingSynchronous(String regionName, Cache cache)
      throws RegionMappingNotFoundException;

  RegionMapping getMappingForRegion(String regionName);

  Set<RegionMapping> getRegionMappings();

  void validateMapping(RegionMapping regionMapping, DataSource dataSource);

  void validateMapping(RegionMapping regionMapping);

  List<FieldMapping> createFieldMappingUsingPdx(PdxType pdxType,
      TableMetaDataView tableMetaDataView);
}
