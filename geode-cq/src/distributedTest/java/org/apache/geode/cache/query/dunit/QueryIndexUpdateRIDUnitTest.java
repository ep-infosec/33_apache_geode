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
package org.apache.geode.cache.query.dunit;

import static org.apache.geode.cache.Region.SEPARATOR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.MirrorType;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.query.Index;
import org.apache.geode.cache.query.Query;
import org.apache.geode.cache.query.QueryService;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.cq.dunit.CqQueryUsingPoolDUnitTest;
import org.apache.geode.cache.query.data.Portfolio;
import org.apache.geode.cache.query.internal.QueryObserverAdapter;
import org.apache.geode.cache.query.internal.QueryObserverHolder;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.cache30.CacheSerializableRunnable;
import org.apache.geode.cache30.CertifiableTestCacheListener;
import org.apache.geode.cache30.ClientServerTestCase;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.NetworkUtils;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.Wait;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.junit.categories.OQLIndexTest;

/**
 * This class tests register interest behavior on client at startup given that client has already
 * created a Index on region on which it registers interest. Then client run a query on region in
 * local cache (Not on server) using the Index.
 *
 *
 */
@Category({OQLIndexTest.class})
public class QueryIndexUpdateRIDUnitTest extends JUnit4CacheTestCase {

  /** The port on which the cache server was started in this VM */
  private static int bridgeServerPort;

  private final String region = "regionA";
  private final int KEYS = 1;
  private final int REGEX = 2;

  private final String rootQ = "SELECT ALL * FROM " + SEPARATOR + "root p where p.ID > 0";
  private final String incompleteQ =
      "SELECT ALL * FROM " + SEPARATOR + "root" + SEPARATOR + region + " p where "; // User needs to
  // append where
  // cond.

  public static final String KEY = "key-";
  public static final String REGULAR_EXPRESSION = ".*1+?.*";

  private static final String ROOT = "root";

  public QueryIndexUpdateRIDUnitTest() {
    super();
  }

  /*
   * Test creates 1 Client and 1 Server. Client and Server create same region in their cache. Client
   * creates index and registers interest in region on server and runs a query. Query must fail as
   * registerInterest does not update indexes on client.
   */

  @Test
  public void testClientIndexUpdateWithRIOnKeys() throws Exception {

    CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest();

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    createServer(server, 0, false);

    final int port = server.invoke(QueryIndexUpdateRIDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    // Init values at server.
    final int size = 10;
    createValues(server, cqDUnitTest.regions[0], size);

    String poolName = "testClientIndexUpdateWithRegisterInterest";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create client.
    cqDUnitTest.createClient(client, port, host0);
    // Create Index on client
    cqDUnitTest.createFunctionalIndex(client, "IdIndex", "p.ID",
        SEPARATOR + "root" + SEPARATOR + "regionA p");

    // Register Interest in all Keys on server
    registerInterestList(client, cqDUnitTest.regions[0], 4, KEYS);

    // Wait for Index to get updated.
    Wait.pause(5 * 1000);

    // This query execution should fail as it will run on client index and index are not updated
    // just by registerInterest.
    // Validate query results.
    validateQueryOnIndex(client, cqDUnitTest.cqs[0], 4);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  /**
   * Tests overlap keys between client region and server region to verify the server region values
   * are synched with client region on register interest.
   *
   */
  @Test
  public void testClientIndexUpdateWithRIOnOverlapKeys() throws Exception {

    CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest();

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    createServer(server, 0, false);

    final int port = server.invoke(QueryIndexUpdateRIDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    String poolName = "testClientIndexUpdateWithRegisterInterest";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create client.
    cqDUnitTest.createClient(client, port, host0);
    // Create Index on client
    cqDUnitTest.createFunctionalIndex(client, "IdIndex", "p.ID",
        SEPARATOR + "root" + SEPARATOR + "regionA p");

    final int size = 10;
    // Init values at client
    createValues(client, cqDUnitTest.regions[0], size, 1);

    // wait for index to get updated.
    Wait.pause(5 * 1000);
    // this.validateQueryOnIndex(client, incompleteQ+"p.getID() > 0", 10);

    validateQueryOnIndex(client, incompleteQ + "p.ID > 0", 10);

    // Init values at server.
    createValues(server, cqDUnitTest.regions[0], size, 4 /* start index */);

    // Register Interest in all Keys on server
    registerInterestList(client, cqDUnitTest.regions[0], size, KEYS, 4 /* start index */);

    // Wait for Index to get updated.
    Wait.pause(5 * 1000);

    // This query execution should fail as it will run on client index and index are not updated
    // just by registerInterest.
    // Validate query results.
    validateQueryOnIndex(client, incompleteQ + "p.ID < " + 4 * 4, 3);
    validateQueryOnIndex(client, incompleteQ + "p.ID >= 16", 7);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  @Test
  public void testClientIndexUpdateWithRIOnRegion() throws Exception {

    CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest();

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    createServer(server, 0, false);

    final int port = server.invoke(QueryIndexUpdateRIDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    // Init values at server.
    final int size = 10;
    createValues(server, cqDUnitTest.regions[0], size);

    String poolName = "testClientIndexUpdateWithRegisterInterest";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create client.
    cqDUnitTest.createClient(client, port, host0);
    // Create Index on client
    cqDUnitTest.createFunctionalIndex(client, "IdIndex", "p.ID",
        SEPARATOR + "root" + SEPARATOR + "regionA p");

    // Register Interest in all Keys on server
    cqDUnitTest.registerInterestListCQ(client, cqDUnitTest.regions[0], size, true);

    // Wait for Index to get updated.
    Wait.pause(5 * 1000);

    // This query execution should fail as it will run on client index and index are not updated
    // just by registerInterest.
    // Validate query results.
    validateQueryOnIndex(client, cqDUnitTest.cqs[0], size);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  @Test
  public void testClientIndexUpdateWithRIOnRegEx() throws Exception {

    CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest();

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    createServer(server, 0, false);

    final int port = server.invoke(QueryIndexUpdateRIDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    // Init values at server.
    final int size = 10;
    createValues(server, cqDUnitTest.regions[0], size);

    String poolName = "testClientIndexUpdateWithRegisterInterest";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create client.
    cqDUnitTest.createClient(client, port, host0);
    // Create Index on client
    cqDUnitTest.createFunctionalIndex(client, "IdIndex", "p.ID",
        SEPARATOR + "root" + SEPARATOR + "regionA p");

    // Register Interest in all Keys on server
    registerInterestList(client, cqDUnitTest.regions[0], 2, REGEX);

    // Wait for Index to get updated.
    Wait.pause(5 * 1000);

    // This query execution should fail as it will run on client index and index are not updated
    // just by registerInterest.
    // Validate query results.
    validateQueryOnIndex(client, cqDUnitTest.cqs[0], 2);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  /**
   * This test tests the RegionClearedException Path in AbsractRegionMap while doing
   * initialImagePut() during registerInterest on client.
   *
   */
  @Test
  public void testClientIndexUpdateWithRIOnClearedRegion() throws Exception {

    CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest();

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    createServer(server, 0, false);

    final int port = server.invoke(QueryIndexUpdateRIDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    // Init values at server.
    final int size = 1000;
    createValues(server, cqDUnitTest.regions[0], size);

    String poolName = "testClientIndexUpdateWithRegisterInterest";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create client.
    cqDUnitTest.createClient(client, port, host0);
    // Create Index on client
    cqDUnitTest.createFunctionalIndex(client, "IdIndex", "p.ID",
        SEPARATOR + "root" + SEPARATOR + "regionA p");

    // Create entries on client to clear region later
    createValues(client, cqDUnitTest.regions[0], size);

    // Register Interest in all Keys on server
    // client.invoke(this.getSRRegisterInterestList(cqDUnitTest.regions[0], size, -1 /* Default ALL
    // KEYS */, 0));
    // this.asyncRegisterInterestList(client, cqDUnitTest.regions[0], size, -1 /* Default ALL KEYS
    // */, 0);
    registerInterestList(client, cqDUnitTest.regions[0], size, -1);

    // Wait for Index to get updated.
    // pause(500);

    // Start clearing region on client asynchronously.
    // this.asyncClearRegion(client, cqDUnitTest.regions[0]);
    client.invoke(getSRClearRegion(cqDUnitTest.regions[0]));

    // Let register interest finish during region clearance
    // pause(5*1000);

    // This query execution should fail as it will run on client index and region has been cleared.
    // Validate query results.
    validateQueryOnIndexWithRegion(client, cqDUnitTest.cqs[0], 0, region);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  /*
   * Same tests as above using Partitioned Regions.
   */

  @Test
  public void testClientIndexUpdateWithRIOnPRRegion() throws Exception {

    CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest();

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    createServer(server, 0, true);

    final int port = server.invoke(QueryIndexUpdateRIDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    // Init values at server.
    final int size = 10;
    createValues(server, ROOT, size);

    String poolName = "testClientIndexUpdateWithRegisterInterest";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create client.
    createClient(client, port, host0);
    // Create Index on client
    cqDUnitTest.createFunctionalIndex(client, "IdIndex", "p.ID", SEPARATOR + "root p");

    // Register Interest in all Keys on server
    registerInterestList(client, ROOT, size, 0);

    // Wait for Index to get updated.
    Wait.pause(5 * 1000);

    // This query execution should fail as it will run on client index and index are not updated
    // just by registerInterest.
    // Validate query results.
    validateQueryOnIndex(client, rootQ, size);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  @Test
  public void testClientIndexUpdateWithRIOnPRKeys() throws Exception {

    CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest();

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    createServer(server, 0, true);

    final int port = server.invoke(QueryIndexUpdateRIDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    // Init values at server.
    final int size = 10;
    createValues(server, ROOT, size);

    String poolName = "testClientIndexUpdateWithRegisterInterest";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create client.
    createClient(client, port, host0);
    // Create Index on client
    cqDUnitTest.createFunctionalIndex(client, "IdIndex", "p.ID", SEPARATOR + "root p");

    // Register Interest in all Keys on server
    registerInterestList(client, ROOT, 4, KEYS);

    // Wait for Index to get updated.
    Wait.pause(5 * 1000);

    // This query execution should fail as it will run on client index and index are not updated
    // just by registerInterest.
    // Validate query results.
    validateQueryOnIndex(client, rootQ, 4);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  @Test
  public void testClientIndexUpdateWithRIOnPRRegEx() throws Exception {

    CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest();

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    createServer(server, 0, true);

    final int port = server.invoke(QueryIndexUpdateRIDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    // Init values at server.
    final int size = 10;
    createValues(server, ROOT, size);

    String poolName = "testClientIndexUpdateWithRegisterInterest";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create client.
    createClient(client, port, host0);
    // Create Index on client
    cqDUnitTest.createFunctionalIndex(client, "IdIndex", "p.ID", SEPARATOR + "root p");

    // Register Interest in all Keys on server
    registerInterestList(client, "root", 2, REGEX);

    // Wait for Index to get updated.
    Wait.pause(5 * 1000);

    // This query execution should fail as it will run on client index and index are not updated
    // just by registerInterest.
    // Validate query results.
    validateQueryOnIndex(client, rootQ, 2);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  /* Register Interest on data on server */
  public void registerInterestList(VM vm, final String regionName, final int keySize,
      final int policy) {
    registerInterestList(vm, regionName, keySize, policy, 0);
  }

  /* Register Interest on data on server */
  public void registerInterestList(VM vm, final String regionName, final int keySize,
      final int policy, final int start) {
    vm.invoke(new CacheSerializableRunnable("Register InterestList") {
      @Override
      public void run2() throws CacheException {

        // Get Query Service.
        Region region = null;
        try {
          if ("root".equals(regionName)) {
            region = getRootRegion();
          } else {
            region = getRootRegion().getSubregion(regionName);
          }
          region.getAttributesMutator()
              .addCacheListener(new CertifiableTestCacheListener());
        } catch (Exception cqe) {
          AssertionError err = new AssertionError("Failed to get Region.", cqe);
          throw err;
        }
        try {
          switch (policy) {
            case REGEX:
              region.registerInterestRegex(REGULAR_EXPRESSION);
              break;
            case KEYS:
              List list = new ArrayList();
              for (int i = start != 0 ? start : 1; i <= keySize; i++) {
                list.add(KEY + i);
              }
              region.registerInterest(list);
              break;
            default:
              region.registerInterest("ALL_KEYS");
          }
        } catch (Exception ex) {
          AssertionError err = new AssertionError("Failed to Register InterestList", ex);
          throw err;
        }
      }
    });
  }

  /* Register Interest on data on server */
  public void asyncRegisterInterestList(VM vm, final String regionName, final int keySize,
      final int policy, final int start) {
    vm.invokeAsync(new CacheSerializableRunnable("Register InterestList") {
      @Override
      public void run2() throws CacheException {

        // Get Query Service.
        Region region = null;
        try {
          if ("root".equals(regionName)) {
            region = getRootRegion();
          } else {
            region = getRootRegion().getSubregion(regionName);
          }
          region.getAttributesMutator()
              .addCacheListener(new CertifiableTestCacheListener());
        } catch (Exception cqe) {
          AssertionError err = new AssertionError("Failed to get Region.", cqe);
          throw err;
        }
        try {
          switch (policy) {
            case REGEX:
              region.registerInterestRegex(REGULAR_EXPRESSION);
              break;
            case KEYS:
              List list = new ArrayList();
              for (int i = start != 0 ? start : 1; i <= keySize; i++) {
                list.add(KEY + i);
              }
              region.registerInterest(list);
              break;
            default:
              region.registerInterest("ALL_KEYS");
          }
        } catch (Exception ex) {
          AssertionError err = new AssertionError("Failed to Register InterestList", ex);
          throw err;
        }
      }
    });
  }

  public void createServer(VM server, final int thePort, final boolean partitioned) {
    SerializableRunnable createServer = new CacheSerializableRunnable("Create Cache Server") {
      @Override
      public void run2() throws CacheException {
        LogWriterUtils.getLogWriter().info("### Create Cache Server. ###");
        AttributesFactory factory = new AttributesFactory();
        factory.setMirrorType(MirrorType.KEYS_VALUES);

        // setting the eviction attributes.
        if (partitioned) {
          factory.setDataPolicy(DataPolicy.PARTITION);
          createRootRegion(factory.createRegionAttributes());
        } else {
          factory.setScope(Scope.DISTRIBUTED_ACK);
          createRegion(region, factory.createRegionAttributes());
        }



        Wait.pause(2000);

        try {
          startBridgeServer(thePort, true);
        }

        catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
        Wait.pause(2000);

      }
    };

    server.invoke(createServer);
  }

  /**
   * Starts a cache server on the given port, using the given deserializeValues and
   * notifyBySubscription to serve up the given region.
   *
   * @since GemFire 6.6
   */
  public void startBridgeServer(int port, boolean notifyBySubscription) throws IOException {

    Cache cache = getCache();
    CacheServer bridge = cache.addCacheServer();
    bridge.setPort(port);
    bridge.setNotifyBySubscription(notifyBySubscription);
    bridge.start();
    bridgeServerPort = bridge.getPort();
  }

  /* Create Init values */
  public void createValues(VM vm, final String regionName, final int size) {
    createValues(vm, regionName, size, 0);
  }


  /**
   * Creates Init Values. start specifies the start index from which key no would start.
   *
   */
  public void createValues(VM vm, final String regionName, final int size, final int start) {
    vm.invoke(new CacheSerializableRunnable("Create values") {
      @Override
      public void run2() throws CacheException {
        Region region1;
        if (!"root".equals(regionName)) {
          region1 = getRootRegion().getSubregion(regionName);
        } else {
          region1 = getRootRegion();
        }
        for (int i = ((start != 0) ? start : 1); i <= size; i++) {
          // getLogWriter().info("### puting '"+KEY+i+"' in region " + region1);
          region1.put(KEY + i, new Portfolio((start != 0 ? start : 1) * i, i));
        }
        LogWriterUtils.getLogWriter()
            .info("### Number of Entries in Region :" + region1.keySet().size());
      }
    });
  }

  /* Returns Cache Server Port */
  static int getCacheServerPort() {
    return bridgeServerPort;
  }

  /* Create Client */
  public void createClient(VM client, final int serverPort, final String serverHost) {
    int[] serverPorts = new int[] {serverPort};
    createClient(client, serverPorts, serverHost, null, null);
  }

  /* Create Client */
  public void createClient(VM client, final int[] serverPorts, final String serverHost,
      final String redundancyLevel, final String poolName) {
    SerializableRunnable createQService = new CacheSerializableRunnable("Create Client") {
      @Override
      public void run2() throws CacheException {
        LogWriterUtils.getLogWriter().info("### Create Client. ###");
        // Region region1 = null;
        // Initialize CQ Service.
        try {
          getCache().getQueryService();
        } catch (Exception cqe) {
          Assert.fail("Failed to getCQService.", cqe);
        }

        AttributesFactory regionFactory = new AttributesFactory();
        regionFactory.setScope(Scope.LOCAL);

        if (poolName != null) {
          regionFactory.setPoolName(poolName);
        } else {
          if (redundancyLevel != null) {
            ClientServerTestCase.configureConnectionPool(regionFactory, serverHost, serverPorts,
                true, Integer.parseInt(redundancyLevel), -1, null);
          } else {
            ClientServerTestCase.configureConnectionPool(regionFactory, serverHost, serverPorts,
                true, -1, -1, null);
          }
        }

        createRootRegion(regionFactory.createRegionAttributes());
        LogWriterUtils.getLogWriter().info("### Successfully Created Root Region on Client");
      }
    };

    client.invoke(createQService);
  }

  public void validateQueryOnIndex(VM vm, final String query, final int resultSize) {
    validateQueryOnIndexWithRegion(vm, query, resultSize, null);
  }

  /**
   * Validates a query result with client region values if region is not null, otherwise verifies
   * the size only.
   *
   */
  public void validateQueryOnIndexWithRegion(VM vm, final String query, final int resultSize,
      final String region) {
    vm.invoke(new CacheSerializableRunnable("Validate Query") {
      @Override
      public void run2() throws CacheException {
        LogWriterUtils.getLogWriter().info("### Validating Query. ###");
        QueryService qs = getCache().getQueryService();

        Query q = qs.newQuery(query);
        // Set the index observer
        QueryObserverImpl observer = new QueryObserverImpl();
        QueryObserverHolder.setInstance(observer);
        try {
          Object r = q.execute();
          if (r instanceof SelectResults) {
            int rSize = ((SelectResults) r).asSet().size();
            LogWriterUtils.getLogWriter().info("### Result Size is :" + rSize);

            if (region == null) {
              assertEquals(resultSize, rSize);
            } else {
              Region reg;
              if (region != null && (reg =
                  getCache().getRegion(SEPARATOR + "root" + SEPARATOR + region)) != null) {
                assertEquals(rSize, reg.size());
                for (Object value : reg.values()) {
                  if (!((SelectResults) r).asSet().contains(value)) {
                    fail("Query resultset mismatch with region values for value: " + value);
                  }
                }
              }
            }
          }
        } catch (Exception e) {
          Assert.fail("Failed to execute the query.", e);
        }
        if (!observer.isIndexesUsed) {
          fail("Index not used for query");
        }
      }
    });
  }

  public void asyncClearRegion(VM vm, final String regionName) {
    vm.invokeAsync(new CacheSerializableRunnable("Destroy entries") {
      @Override
      public void run2() throws CacheException {
        LogWriterUtils.getLogWriter().info("### Clearing Region. ###");
        Region region1;
        if (!"root".equals(regionName)) {
          region1 = getRootRegion().getSubregion(regionName);
        } else {
          region1 = getRootRegion();
        }
        region1.clear();
        LogWriterUtils.getLogWriter()
            .info("### Number of Entries in Region :" + region1.keySet().size());
      }
    });
  }

  private SerializableRunnable getSRClearRegion(final String regionName) {
    SerializableRunnable sr = new CacheSerializableRunnable("Destroy entries") {
      @Override
      public void run2() throws CacheException {
        LogWriterUtils.getLogWriter().info("### Clearing Region. ###");
        Region region1;
        if (!"root".equals(regionName)) {
          region1 = getRootRegion().getSubregion(regionName);
        } else {
          region1 = getRootRegion();
        }
        region1.clear();
        LogWriterUtils.getLogWriter()
            .info("### Number of Entries in Region :" + region1.keySet().size());
      }
    };
    return sr;
  }

  private SerializableRunnable getSRRegisterInterestList(final String regionName, final int keySize,
      final int policy, final int start) {
    SerializableRunnable sr = new CacheSerializableRunnable("Register InterestList") {
      @Override
      public void run2() throws CacheException {

        // Get Query Service.
        Region region = null;
        try {
          if ("root".equals(regionName)) {
            region = getRootRegion();
          } else {
            region = getRootRegion().getSubregion(regionName);
          }
          region.getAttributesMutator()
              .addCacheListener(new CertifiableTestCacheListener());
        } catch (Exception cqe) {
          AssertionError err = new AssertionError("Failed to get Region.", cqe);
          throw err;
        }
        try {
          switch (policy) {
            case REGEX:
              region.registerInterestRegex(REGULAR_EXPRESSION);
              break;
            case KEYS:
              List list = new ArrayList();
              for (int i = start != 0 ? start : 1; i <= keySize; i++) {
                list.add(KEY + i);
              }
              region.registerInterest(list);
              break;
            default:
              region.registerInterest("ALL_KEYS");
          }
        } catch (Exception ex) {
          AssertionError err = new AssertionError("Failed to Register InterestList", ex);
          throw err;
        }
      }
    };
    return sr;
  }

  public static class QueryObserverImpl extends QueryObserverAdapter {
    boolean isIndexesUsed = false;
    ArrayList indexesUsed = new ArrayList();

    @Override
    public void beforeIndexLookup(Index index, int oper, Object key) {
      indexesUsed.add(index.getName());
    }

    @Override
    public void afterIndexLookup(Collection results) {
      if (results != null) {
        isIndexesUsed = true;
      }
    }
  }

}
