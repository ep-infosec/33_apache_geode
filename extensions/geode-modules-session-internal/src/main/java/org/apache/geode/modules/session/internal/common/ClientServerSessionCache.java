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

package org.apache.geode.modules.session.internal.common;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.GemFireCache;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientRegionFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.execute.Execution;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.modules.util.BootstrappingFunction;
import org.apache.geode.modules.util.CreateRegionFunction;
import org.apache.geode.modules.util.RegionConfiguration;
import org.apache.geode.modules.util.RegionStatus;
import org.apache.geode.modules.util.SessionCustomExpiry;

/**
 * Class which defines a client/server cache.
 */
public class ClientServerSessionCache extends AbstractSessionCache {

  private static final Logger LOG = LoggerFactory.getLogger(PeerToPeerSessionCache.class.getName());

  private final ClientCache cache;

  protected static final String DEFAULT_REGION_ATTRIBUTES_ID =
      RegionShortcut.PARTITION_REDUNDANT.toString();

  protected static final Boolean DEFAULT_ENABLE_LOCAL_CACHE = true;

  /**
   * Constructor
   *
   */
  public ClientServerSessionCache(ClientCache cache, Map<CacheProperty, Object> properties) {
    super();
    this.cache = cache;

    /**
     * Set some default properties for this cache if they haven't already been set
     */
    this.properties.put(CacheProperty.REGION_ATTRIBUTES_ID, DEFAULT_REGION_ATTRIBUTES_ID);
    this.properties.put(CacheProperty.ENABLE_LOCAL_CACHE, DEFAULT_ENABLE_LOCAL_CACHE);
    this.properties.putAll(properties);
  }

  @Override
  public void initialize() {
    // Bootstrap the servers
    bootstrapServers();

    // Create or retrieve the region
    createOrRetrieveRegion();

    // Set the session region directly as the operating region since there is no difference
    // between the local cache region and the session region.
    operatingRegion = sessionRegion;

    // Create or retrieve the statistics
    createStatistics();
  }

  @Override
  public GemFireCache getCache() {
    return cache;
  }

  @Override
  public boolean isClientServer() {
    return true;
  }


  ////////////////////////////////////////////////////////////////////////
  // Private methods

  private void bootstrapServers() {
    Execution execution = FunctionService.onServers(cache);
    ResultCollector collector = execution.execute(new BootstrappingFunction());
    // Get the result. Nothing is being done with it.
    try {
      collector.getResult();
    } catch (Exception e) {
      // If an exception occurs in the function, log it.
      LOG.warn("Caught unexpected exception:", e);
    }
  }

  private void createOrRetrieveRegion() {
    // Retrieve the local session region
    sessionRegion = cache.getRegion((String) properties.get(CacheProperty.REGION_NAME));

    // If necessary, create the regions on the server and client
    if (sessionRegion == null) {
      // Create the PR on the servers
      createSessionRegionOnServers();

      // Create the region on the client
      sessionRegion = createLocalSessionRegion();
      LOG.debug("Created session region: " + sessionRegion);
    } else {
      LOG.debug("Retrieved session region: " + sessionRegion);

      // Register interest in case users provide their own client cache region
      if (sessionRegion.getAttributes().getDataPolicy() != DataPolicy.EMPTY) {
        sessionRegion.registerInterestForAllKeys(InterestResultPolicy.KEYS);
      }
    }
  }

  private void createSessionRegionOnServers() {
    // Create the RegionConfiguration
    RegionConfiguration configuration = createRegionConfiguration();

    // Send it to the server tier
    Execution execution = FunctionService.onServer(cache).setArguments(configuration);
    ResultCollector collector = execution.execute(CreateRegionFunction.ID);

    // Verify the region was successfully created on the servers
    List<RegionStatus> results = (List<RegionStatus>) collector.getResult();
    for (RegionStatus status : results) {
      if (status == RegionStatus.INVALID) {
        final String builder =
            "An exception occurred on the server while attempting to create or validate region named "
                + properties.get(CacheProperty.REGION_NAME)
                + ". See the server log for additional details.";
        throw new IllegalStateException(builder);
      }
    }
  }

  private Region<String, HttpSession> createLocalSessionRegion() {
    ClientRegionFactory<String, HttpSession> factory = null;
    boolean enableLocalCache = (Boolean) properties.get(CacheProperty.ENABLE_LOCAL_CACHE);

    String regionName = (String) properties.get(CacheProperty.REGION_NAME);
    if (enableLocalCache) {
      // Create the region factory with caching and heap LRU enabled
      factory = cache
          .<String, HttpSession>createClientRegionFactory(
              ClientRegionShortcut.CACHING_PROXY_HEAP_LRU)
          .setCustomEntryIdleTimeout(new SessionCustomExpiry());
      LOG.info("Created new local client session region: {}", regionName);
    } else {
      // Create the region factory without caching enabled
      factory = cache.createClientRegionFactory(ClientRegionShortcut.PROXY);
      LOG.info("Created new local client (uncached) session region: {} without any session expiry",
          regionName);
    }

    // Create the region
    Region region = factory.create(regionName);

    if (enableLocalCache) {
      region.registerInterestForAllKeys(InterestResultPolicy.KEYS);
    }
    return region;
  }
}
