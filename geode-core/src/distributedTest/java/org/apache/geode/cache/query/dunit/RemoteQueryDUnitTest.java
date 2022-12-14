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
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Comparator;
import java.util.Properties;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.DataSerializable;
import org.apache.geode.DataSerializer;
import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.query.QueryInvocationTargetException;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.Struct;
import org.apache.geode.cache.query.internal.QueryObserverAdapter;
import org.apache.geode.cache.query.internal.QueryObserverHolder;
import org.apache.geode.cache.query.internal.ResultsBag;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.cache30.CacheSerializableRunnable;
import org.apache.geode.cache30.ClientServerTestCase;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.DistributedTestUtils;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.NetworkUtils;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.Wait;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.junit.categories.OQLQueryTest;

/**
 * Tests remote (client/server) query execution.
 *
 * @since GemFire 5.0.1
 */
@Category({OQLQueryTest.class})
public class RemoteQueryDUnitTest extends JUnit4CacheTestCase {

  /** The port on which the cache server was started in this VM */
  private static int bridgeServerPort;

  @Override
  public final void postSetUp() throws Exception {
    disconnectAllFromDS();
  }

  @Override
  public final void postTearDownCacheTestCase() throws Exception {
    disconnectAllFromDS();
  }

  /**
   * Tests remote predicate query execution.
   */
  @Test
  public void testRemotePredicateQueries() throws CacheException {

    final String name = getName();
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    final int numberOfEntries = 100;

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(LOCATORS,
            "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
        InternalDistributedSystem system = getSystem(config);
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        createRegion(name, factory.create());
        Wait.pause(1000);
        try {
          startBridgeServer(0, false);
        } catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
      }
    });

    // Initialize server region
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        for (int i = 0; i < numberOfEntries; i++) {
          region.put("key-" + i, new TestObject(i, "ibm"));
        }
      }
    });

    // Create client region
    final int port = vm0.invoke(RemoteQueryDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(vm0.getHost());
    vm1.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        ClientServerTestCase.configureConnectionPool(factory, host0, port, -1, true, -1, -1, null);
        createRegion(name, factory.create());
      }
    });

    // Execute client queries
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String queryString = null;
        SelectResults results = null;

        queryString = "ticker = 'ibm'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries, results.size());
        assertTrue(results.getClass() == ResultsBag.class);
        assertTrue(results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());

        queryString = "ticker = 'IBM'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(0, results.size());
        assertTrue(results.getClass() == ResultsBag.class);
        assertTrue(results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());

        queryString = "price > 49";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries / 2, results.size());
        assertTrue(results.getClass() == ResultsBag.class);
        assertTrue(results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());

        queryString = "price = 50";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(results.getClass() == ResultsBag.class);
        assertTrue(results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());

        queryString = "ticker = 'ibm' and price = 50";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(results.getClass() == ResultsBag.class);
        assertTrue(results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());

        /*
         * Non-distinct order by query not yet supported queryString = "id < 101 ORDER BY id"; try {
         * results = region.query(queryString); } catch (Exception e) { fail("Failed executing " +
         * queryString, e); } assertIndexDetailsEquals(100, results.size()); assertTrue(results
         * instanceof ResultsCollectionWrapper); IdComparator comparator = new IdComparator();
         * Object[] resultsArray = results.toArray(); for (int i=0; i<resultsArray.length; i++) { if
         * (i+1 != resultsArray.length) { // The id of the current element in the result set must be
         * less // than the id of the next one to pass. assertTrue("The id for " + resultsArray[i] +
         * " should be less than the id for " + resultsArray[i+1],
         * comparator.compare(resultsArray[i], resultsArray[i+1]) == -1); } }
         */
      }
    });


    // Stop server
    vm0.invoke(new SerializableRunnable("Stop CacheServer") {
      @Override
      public void run() {
        stopBridgeServer(getCache());
      }
    });
  }

  /**
   * Tests remote import query execution.
   */
  @Test
  public void testRemoteImportQueries() throws CacheException {

    final String name = getName();
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    final int numberOfEntries = 100;

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(LOCATORS,
            "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
        getSystem(config);
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        createRegion(name, factory.create());
        Wait.pause(1000);
        try {
          startBridgeServer(0, false);
        } catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
      }
    });

    // Initialize server region
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        for (int i = 0; i < numberOfEntries; i++) {
          region.put("key-" + i, new TestObject(i, "ibm"));
        }
      }
    });

    // Create client region
    final int port = vm0.invoke(RemoteQueryDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(vm0.getHost());
    vm1.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);

        ClientServerTestCase.configureConnectionPool(factory, host0, port, -1, true, -1, -1, null);
        createRegion(name, factory.create());
      }
    });

    // Execute client queries
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String queryString = null;
        SelectResults results = null;

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct * from "
                + region.getFullPath();
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct * from "
                + region.getFullPath() + " where ticker = 'ibm'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct * from "
                + region.getFullPath() + " where ticker = 'IBM'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(0, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct * from "
                + region.getFullPath() + " where price > 49";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries / 2, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct * from "
                + region.getFullPath() + " where price = 50";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct * from "
                + region.getFullPath() + " where ticker = 'ibm' and price = 50";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates());
      }
    });


    // Stop server
    vm0.invoke(new SerializableRunnable("Stop CacheServer") {
      @Override
      public void run() {
        stopBridgeServer(getCache());
      }
    });
  }

  /**
   * Tests remote struct query execution.
   */
  @Test
  public void testRemoteStructQueries() throws CacheException {

    final String name = getName();
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    final int numberOfEntries = 100;

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(LOCATORS,
            "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
        getSystem(config);
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        createRegion(name, factory.create());
        Wait.pause(1000);
        try {
          startBridgeServer(0, false);
        } catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
      }
    });

    // Initialize server region
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        for (int i = 0; i < numberOfEntries; i++) {
          region.put("key-" + i, new TestObject(i, "ibm"));
        }
      }
    });

    // Create client region
    final int port = vm0.invoke(RemoteQueryDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(vm0.getHost());
    vm1.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        ClientServerTestCase.configureConnectionPool(factory, host0, port, -1, true, -1, -1, null);
        createRegion(name, factory.create());
      }
    });

    // Execute client queries
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String queryString = null;
        SelectResults results = null;

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct ticker, price from "
                + region.getFullPath();
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && results.getCollectionType().getElementType().isStructType());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct ticker, price from "
                + region.getFullPath() + " where ticker = 'ibm'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && results.getCollectionType().getElementType().isStructType());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct ticker, price from "
                + region.getFullPath() + " where ticker = 'IBM'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(0, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && results.getCollectionType().getElementType().isStructType());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct ticker, price from "
                + region.getFullPath() + " where price > 49";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries / 2, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && results.getCollectionType().getElementType().isStructType());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct ticker, price from "
                + region.getFullPath() + " where price = 50";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && results.getCollectionType().getElementType().isStructType());

        queryString =
            "import org.apache.geode.admin.RemoteQueryDUnitTest.TestObject; select distinct ticker, price from "
                + region.getFullPath() + " where ticker = 'ibm' and price = 50";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && results.getCollectionType().getElementType().isStructType());
      }
    });

    // Stop server
    vm0.invoke(new SerializableRunnable("Stop CacheServer") {
      @Override
      public void run() {
        stopBridgeServer(getCache());
      }
    });
  }

  /**
   * Tests remote complex query execution.
   */
  @Ignore("GEODE-1837: rewrite this test using Portfolio and Position in package org.apache.geode.cache.query.data")
  @Test
  public void testRemoteComplexQueries() throws CacheException {
    /*
     * final String name = this.getName(); final Host host = Host.getHost(0); VM vm0 =
     * host.getVM(0); VM vm1 = host.getVM(1); // final int numberOfEntries = 100;
     *
     * // Start server vm0.invoke(new CacheSerializableRunnable("Create cache server") { public
     * void run2() throws CacheException { Properties config = new Properties();
     * config.setProperty(LOCATORS, "localhost[" + DistributedTestUtils.getDUnitLocatorPort() +
     * "]"); getSystem(config); AttributesFactory factory = new AttributesFactory();
     * factory.setScope(Scope.LOCAL); createRegion(name, factory.create()); Wait.pause(1000); try {
     * startBridgeServer(0, false); } catch (Exception ex) {
     * Assert.fail("While starting CacheServer", ex); } } });
     *
     * // Initialize server region vm0.invoke(new CacheSerializableRunnable("Create cache server")
     * { public void run2() throws CacheException { Region region =
     * getRootRegion().getSubregion(name); Portfolio portfolio = null; Position position1 = null;
     * Position position2 = null; Properties portfolioProperties= null; Properties
     * position1Properties = null; Properties position2Properties = null;
     *
     * // Create portfolio 1 portfolio = new Portfolio(); portfolioProperties = new Properties();
     * portfolioProperties.put("id", new Integer(1)); portfolioProperties.put("type", "type1");
     * portfolioProperties.put("status", "active");
     *
     * position1 = new Position(); position1Properties = new Properties();
     * position1Properties.put("secId", "SUN"); position1Properties.put("qty", new Double(34000.0));
     * position1Properties.put("mktValue", new Double(24.42)); position1.init(position1Properties);
     * portfolioProperties.put("position1", position1);
     *
     * position2 = new Position(); position2Properties = new Properties();
     * position2Properties.put("secId", "IBM"); position2Properties.put("qty", new Double(8765.0));
     * position2Properties.put("mktValue", new Double(34.29)); position2.init(position2Properties);
     * portfolioProperties.put("position2", position2);
     *
     * portfolio.init(portfolioProperties); region.put(new Integer(1), portfolio);
     *
     * // Create portfolio 2 portfolio = new Portfolio(); portfolioProperties = new Properties();
     * portfolioProperties.put("id", new Integer(2)); portfolioProperties.put("type", "type2");
     * portfolioProperties.put("status", "inactive");
     *
     * position1 = new Position(); position1Properties = new Properties();
     * position1Properties.put("secId", "YHOO"); position1Properties.put("qty", new Double(9834.0));
     * position1Properties.put("mktValue", new Double(12.925)); position1.init(position1Properties);
     * portfolioProperties.put("position1", position1);
     *
     * position2 = new Position(); position2Properties = new Properties();
     * position2Properties.put("secId", "GOOG"); position2Properties.put("qty", new
     * Double(12176.0)); position2Properties.put("mktValue", new Double(21.972));
     * position2.init(position2Properties); portfolioProperties.put("position2", position2);
     *
     * portfolio.init(portfolioProperties); region.put(new Integer(2), portfolio);
     *
     * // Create portfolio 3 portfolio = new Portfolio(); portfolioProperties = new Properties();
     * portfolioProperties.put("id", new Integer(3)); portfolioProperties.put("type", "type3");
     * portfolioProperties.put("status", "active");
     *
     * position1 = new Position(); position1Properties = new Properties();
     * position1Properties.put("secId", "MSFT"); position1Properties.put("qty", new
     * Double(98327.0)); position1Properties.put("mktValue", new Double(23.32));
     * position1.init(position1Properties); portfolioProperties.put("position1", position1);
     *
     * position2 = new Position(); position2Properties = new Properties();
     * position2Properties.put("secId", "AOL"); position2Properties.put("qty", new Double(978.0));
     * position2Properties.put("mktValue", new Double(40.373)); position2.init(position2Properties);
     * portfolioProperties.put("position2", position2);
     *
     * portfolio.init(portfolioProperties); region.put(new Integer(3), portfolio);
     *
     * // Create portfolio 4 portfolio = new Portfolio(); portfolioProperties = new Properties();
     * portfolioProperties.put("id", new Integer(4)); portfolioProperties.put("type", "type1");
     * portfolioProperties.put("status", "inactive");
     *
     * position1 = new Position(); position1Properties = new Properties();
     * position1Properties.put("secId", "APPL"); position1Properties.put("qty", new Double(90.0));
     * position1Properties.put("mktValue", new Double(67.356572));
     * position1.init(position1Properties); portfolioProperties.put("position1", position1);
     *
     * position2 = new Position(); position2Properties = new Properties();
     * position2Properties.put("secId", "ORCL"); position2Properties.put("qty", new Double(376.0));
     * position2Properties.put("mktValue", new Double(101.34)); position2.init(position2Properties);
     * portfolioProperties.put("position2", position2);
     *
     * portfolio.init(portfolioProperties); region.put(new Integer(4), portfolio); } });
     *
     * // Create client region final int port = vm0.invoke(() ->
     * RemoteQueryDUnitTest.getCacheServerPort()); final String host0 =
     * NetworkUtils.getServerHostName(vm0.getHost()); vm1.invoke(new
     * CacheSerializableRunnable("Create region") { public void run2() throws CacheException {
     * Properties config = new Properties(); config.setProperty(MCAST_PORT, "0"); getSystem(config);
     * getCache(); AttributesFactory factory = new AttributesFactory();
     * factory.setScope(Scope.LOCAL); ClientServerTestCase.configureConnectionPool(factory, host0,
     * port,-1, true, -1, -1, null); createRegion(name, factory.create()); } });
     *
     * // Execute client queries vm1.invoke(new CacheSerializableRunnable("Execute queries") {
     * public void run2() throws CacheException { Region region =
     * getRootRegion().getSubregion(name); String queryString = null; SelectResults results = null;
     *
     * queryString = "IMPORT Position; " + "SELECT DISTINCT id, status FROM " + region.getFullPath()
     * + "WHERE NOT (SELECT DISTINCT * FROM positions.values posnVal TYPE Position " +
     * "WHERE posnVal.secId='AOL' OR posnVal.secId='SAP').isEmpty"; try { results =
     * region.query(queryString); } catch (Exception e) { Assert.fail("Failed executing " +
     * queryString, e); } LogWriterUtils.getLogWriter().fine("size: " + results.size());
     * //assertIndexDetailsEquals(numberOfEntries, results.size());
     * assertTrue(!results.getCollectionType().allowsDuplicates() &&
     * results.getCollectionType().getElementType().isStructType()); } });
     *
     *
     * // Stop server vm0.invoke(new SerializableRunnable("Stop CacheServer") { public void run() {
     * stopBridgeServer(getCache()); } });
     */
  }

  /**
   * Tests remote full region query execution.
   */
  @Test
  public void testRemoteFullRegionQueries() throws CacheException {

    final String name = getName();
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    final int numberOfEntries = 100;

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(LOCATORS,
            "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
        getSystem(config);
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        createRegion(name, factory.create());
        Wait.pause(1000);
        try {
          startBridgeServer(0, false);
        } catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
      }
    });

    // Initialize server region
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        for (int i = 0; i < numberOfEntries; i++) {
          region.put("key-" + i, new TestObject(i, "ibm"));
        }
      }
    });

    // Create client region
    final int port = vm0.invoke(RemoteQueryDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(vm0.getHost());
    vm1.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        ClientServerTestCase.configureConnectionPool(factory, host0, port, -1, true, -1, -1, null);
        createRegion(name, factory.create());
      }
    });

    // Execute client queries
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String queryString = null;
        SelectResults results = null;
        Comparator comparator = null;
        Object[] resultsArray = null;

        // value query
        queryString = "SELECT DISTINCT itr.value FROM " + region.getFullPath()
            + ".entries itr where itr.key = 'key-1'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates());
        assertTrue(results.asList().get(0) instanceof TestObject);

        // key query
        queryString = "SELECT DISTINCT itr.key FROM " + region.getFullPath()
            + ".entries itr where itr.key = 'key-1'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates());
        assertEquals("key-1", results.asList().get(0));

        // order by value query
        queryString =
            "SELECT DISTINCT * FROM " + region.getFullPath() + " WHERE id < 101 ORDER BY id";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries, results.size());
        // All order-by query results are stored in a ResultsCollectionWrapper
        // wrapping a list, so the assertion below is not correct even though
        // it should be.
        // assertTrue(!results.getCollectionType().allowsDuplicates());
        assertTrue(results.getCollectionType().isOrdered());
        comparator = new IdComparator();
        resultsArray = results.toArray();
        for (int i = 0; i < resultsArray.length; i++) {
          if (i + 1 != resultsArray.length) {
            // The id of the current element in the result set must be less
            // than the id of the next one to pass.
            assertTrue(
                "The id for " + resultsArray[i] + " should be less than the id for "
                    + resultsArray[i + 1],
                comparator.compare(resultsArray[i], resultsArray[i + 1]) == -1);
          }
        }

        // order by struct query
        queryString = "SELECT DISTINCT id, ticker, price FROM " + region.getFullPath()
            + " WHERE id < 101 ORDER BY id";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries, results.size());
        // All order-by query results are stored in a ResultsCollectionWrapper
        // wrapping a list, so the assertion below is not correct even though
        // it should be.
        // assertTrue(!results.getCollectionType().allowsDuplicates());
        assertTrue(results.getCollectionType().isOrdered());
        comparator = new StructIdComparator();
        resultsArray = results.toArray();
        for (int i = 0; i < resultsArray.length; i++) {
          if (i + 1 != resultsArray.length) {
            // The id of the current element in the result set must be less
            // than the id of the next one to pass.
            assertTrue(
                "The id for " + resultsArray[i] + " should be less than the id for "
                    + resultsArray[i + 1],
                comparator.compare(resultsArray[i], resultsArray[i + 1]) == -1);
          }
        }

        // size query
        queryString = "(SELECT DISTINCT * FROM " + region.getFullPath() + " WHERE id < 101).size";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        Object result = results.iterator().next();
        assertTrue(result instanceof Integer);
        int resultInt = (Integer) result;
        assertEquals(resultInt, 100);

        // query with leading/trailing spaces
        queryString = " SELECT DISTINCT itr.key FROM " + region.getFullPath()
            + ".entries itr where itr.key = 'key-1' ";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertEquals("key-1", results.asList().get(0));
      }
    });

    // Stop server
    vm0.invoke(new SerializableRunnable("Stop CacheServer") {
      @Override
      public void run() {
        stopBridgeServer(getCache());
      }
    });
  }

  /**
   * Tests remote join query execution.
   */
  @Test
  public void testRemoteJoinRegionQueries() throws CacheException {

    final String name = getName();
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    final int numberOfEntries = 100;

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(LOCATORS,
            "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
        getSystem(config);
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        createRegion(name + "1", factory.create());
        createRegion(name + "2", factory.create());
        Wait.pause(1000);
        try {
          startBridgeServer(0, false);
        } catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
      }
    });

    // Initialize server region
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Region region1 = getRootRegion().getSubregion(name + "1");
        for (int i = 0; i < numberOfEntries; i++) {
          region1.put("key-" + i, new TestObject(i, "ibm"));
        }
        Region region2 = getRootRegion().getSubregion(name + "2");
        for (int i = 0; i < numberOfEntries; i++) {
          region2.put("key-" + i, new TestObject(i, "ibm"));
        }
      }
    });

    // Create client region
    final int port = vm0.invoke(RemoteQueryDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(vm0.getHost());
    vm1.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        ClientServerTestCase.configureConnectionPool(factory, host0, port, -1, true, -1, -1, null);
        createRegion(name + "1", factory.create());
        createRegion(name + "2", factory.create());
      }
    });

    // Execute client queries
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region1 = getRootRegion().getSubregion(name + "1");
        Region region2 = getRootRegion().getSubregion(name + "2");
        String queryString = null;
        SelectResults results = null;

        queryString = "select distinct a, b.price from " + region1.getFullPath() + " a, "
            + region2.getFullPath() + " b where a.price = b.price";
        try {
          results = region1.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(numberOfEntries, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && results.getCollectionType().getElementType().isStructType());

        queryString = "select distinct a, b.price from " + region1.getFullPath() + " a, "
            + region2.getFullPath() + " b where a.price = b.price and a.price = 50";
        try {
          results = region1.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && results.getCollectionType().getElementType().isStructType());
      }
    });

    // Stop server
    vm0.invoke(new SerializableRunnable("Stop CacheServer") {
      @Override
      public void run() {
        stopBridgeServer(getCache());
      }
    });
  }

  /**
   * Tests remote query execution using a BridgeClient as the CacheWriter and CacheLoader.
   */
  @Test
  public void testRemoteBridgeClientQueries() throws CacheException {

    final String name = getName();
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    VM vm2 = host.getVM(2);
    final int numberOfEntries = 100;

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(LOCATORS,
            "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
        getSystem(config);
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        createRegion(name, factory.create());
        try {
          startBridgeServer(0, false);
        } catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
      }
    });

    // Initialize server region
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        for (int i = 0; i < numberOfEntries; i++) {
          region.put("key-" + i, new TestObject(i, "ibm"));
        }
      }
    });

    final int port = vm0.invoke(RemoteQueryDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(vm0.getHost());

    // Create client region in VM1
    vm1.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        PoolManager.createFactory().addServer(host0, port).setSubscriptionEnabled(true)
            .create("clientPool");
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        factory.setPoolName("clientPool");
        createRegion(name, factory.create());
      }
    });

    // Create client region in VM2
    vm2.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        PoolManager.createFactory().addServer(host0, port).setSubscriptionEnabled(true)
            .create("clientPool");
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        factory.setPoolName("clientPool");
        createRegion(name, factory.create());
      }
    });

    // Execute client queries in VM1
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String queryString = null;
        SelectResults results = null;

        queryString = "SELECT DISTINCT itr.value FROM " + region.getFullPath()
            + ".entries itr where itr.key = 'key-1'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());
        assertTrue(results.asList().get(0) instanceof TestObject);

        queryString = "SELECT DISTINCT itr.key FROM " + region.getFullPath()
            + ".entries itr where itr.key = 'key-1'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());
        assertEquals("key-1", results.asList().get(0));
      }
    });

    // Execute client queries in VM2
    vm2.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String queryString = null;
        SelectResults results = null;

        queryString = "SELECT DISTINCT itr.value FROM " + region.getFullPath()
            + ".entries itr where itr.key = 'key-1'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());
        assertTrue(results.asList().get(0) instanceof TestObject);

        queryString = "SELECT DISTINCT itr.key FROM " + region.getFullPath()
            + ".entries itr where itr.key = 'key-1'";
        try {
          results = region.query(queryString);
        } catch (Exception e) {
          Assert.fail("Failed executing " + queryString, e);
        }
        assertEquals(1, results.size());
        assertTrue(!results.getCollectionType().allowsDuplicates()
            && !results.getCollectionType().getElementType().isStructType());
        assertEquals("key-1", results.asList().get(0));
      }
    });

    // Close client VM1
    vm1.invoke(new CacheSerializableRunnable("Close client") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        region.close();
        PoolManager.find("clientPool").destroy();
      }
    });

    // Close client VM2
    vm2.invoke(new CacheSerializableRunnable("Close client") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        region.close();
        PoolManager.find("clientPool").destroy();
      }
    });

    // Stop server
    vm0.invoke(new SerializableRunnable("Stop CacheServer") {
      @Override
      public void run() {
        stopBridgeServer(getCache());
      }
    });
  }

  /**
   * This the dunit test for the bug no : 36434
   */
  @Test
  public void testBug36434() throws Exception {
    final String name = getName();
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    final int numberOfEntries = 100;

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(LOCATORS,
            "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
        getSystem(config);
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        createRegion(name, factory.createRegionAttributes());
        try {
          startBridgeServer(0, false);
        } catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
      }
    });

    // Initialize server region
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        for (int i = 0; i < numberOfEntries; i++) {
          region.put("key-" + i, new TestObject(i, "ibm"));
        }
      }
    });

    final int port = vm0.invoke(RemoteQueryDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(vm0.getHost());

    // Create client region in VM1
    vm1.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        PoolManager.createFactory().addServer(host0, port).setSubscriptionEnabled(true)
            .create("clientPool");
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        factory.setPoolName("clientPool");
        createRegion(name, factory.createRegionAttributes());
      }
    });


    // Execute client queries in VM1
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String[] queryStrings = {"id<9", "selection<9", "important<9", "\"select\"<9"};
        for (final String queryString : queryStrings) {
          SelectResults results = null;

          try {
            results = region.query(queryString);
          } catch (Exception e) {
            Assert.fail("Failed executing " + queryString, e);
          }
          assertEquals(9, results.size());
          String msg = "results expected to be instance of ResultsBag,"
              + " but was found to be is instance of '";
          assertTrue(msg + results.getClass().getName() + "'", results instanceof ResultsBag);
          assertTrue(results.asList().get(0) instanceof TestObject);
        }
      }
    });

    // Close client VM1
    vm1.invoke(new CacheSerializableRunnable("Close client") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        region.close();
        PoolManager.find("clientPool").destroy();
      }
    });


    // Stop server
    vm0.invoke(new SerializableRunnable("Stop CacheServer") {
      @Override
      public void run() {
        stopBridgeServer(getCache());
      }
    });
  }

  /**
   * This the dunit test for the bug no : 36969
   */
  @Test
  public void testBug36969() throws Exception {
    final String name = getName();
    final Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    final int numberOfEntries = 100;

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(LOCATORS,
            "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
        getSystem(config);
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        final Region region = createRegion(name, factory.createRegionAttributes());
        QueryObserverHolder.setInstance(new QueryObserverAdapter() {
          @Override
          public void afterQueryEvaluation(Object result) {
            // Destroy the region in the test
            region.close();
          }

        });
        try {
          startBridgeServer(0, false);
        } catch (Exception ex) {
          Assert.fail("While starting CacheServer", ex);
        }
      }
    });

    // Initialize server region
    vm0.invoke(new CacheSerializableRunnable("Create cache server") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        for (int i = 0; i < numberOfEntries; i++) {
          region.put("key-" + i, new TestObject(i, "ibm"));
        }
      }
    });

    final int port = vm0.invoke(RemoteQueryDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(vm0.getHost());

    // Create client region in VM1
    vm1.invoke(new CacheSerializableRunnable("Create region") {
      @Override
      public void run2() throws CacheException {
        Properties config = new Properties();
        config.setProperty(MCAST_PORT, "0");
        getSystem(config);
        PoolManager.createFactory().addServer(host0, port).setSubscriptionEnabled(true)
            .create("clientPool");
        getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        factory.setPoolName("clientPool");
        createRegion(name, factory.createRegionAttributes());
      }
    });


    // Execute client queries in VM1
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String queryStrings = "id<9";
        // SelectResults results = null;
        try {
          region.query(queryStrings);
          fail("The query should have experienced RegionDestroyedException");
        } catch (QueryInvocationTargetException qte) {
          // Ok test passed
        } catch (Exception e) {
          Assert.fail("Failed executing query " + queryStrings + " due  to unexpected Excecption",
              e);
        }
      }
    });

    // Start server
    vm0.invoke(new CacheSerializableRunnable("Create two regions") {
      @Override
      public void run2() throws CacheException {
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        final Region region1 = createRegion(name, factory.createRegionAttributes());
        final Region region2 = createRegion(name + "_2", factory.createRegionAttributes());
        QueryObserverHolder.setInstance(new QueryObserverAdapter() {
          @Override
          public void afterQueryEvaluation(Object result) {
            // Destroy the region in the test
            region1.close();
          }

        });
        for (int i = 0; i < numberOfEntries; i++) {
          region1.put("key-" + i, new TestObject(i, "ibm"));
          region2.put("key-" + i, new TestObject(i, "ibm"));
        }

      }
    });

    // Execute client queries in VM1
    vm1.invoke(new CacheSerializableRunnable("Execute queries") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        String queryString = "select distinct * from " + SEPARATOR + name;
        // SelectResults results = null;
        try {
          region.query(queryString);
          fail("The query should have experienced RegionDestroyedException");
        } catch (QueryInvocationTargetException qte) {
          // Ok test passed
        } catch (Exception e) {
          Assert.fail("Failed executing query " + queryString + " due  to unexpected Excecption",
              e);
        }
      }
    });

    // Close client VM1
    vm1.invoke(new CacheSerializableRunnable("Close client") {
      @Override
      public void run2() throws CacheException {
        Region region = getRootRegion().getSubregion(name);
        region.close();
        PoolManager.find("clientPool").destroy();
      }
    });


    // Stop server
    vm0.invoke(new SerializableRunnable("Stop CacheServer") {
      @Override
      public void run() {
        QueryObserverHolder.setInstance(new QueryObserverAdapter());
        stopBridgeServer(getCache());
      }
    });



  }



  /**
   * Starts a cache server on the given port, using the given deserializeValues and
   * notifyBySubscription to serve up the given region.
   */
  protected void startBridgeServer(int port, boolean notifyBySubscription) throws IOException {

    Cache cache = getCache();
    CacheServer bridge = cache.addCacheServer();
    bridge.setPort(port);
    bridge.setNotifyBySubscription(notifyBySubscription);
    bridge.start();
    bridgeServerPort = bridge.getPort();
  }

  /**
   * Stops the cache server that serves up the given cache.
   */
  protected void stopBridgeServer(Cache cache) {
    CacheServer bridge = cache.getCacheServers().iterator().next();
    bridge.stop();
    assertFalse(bridge.isRunning());
  }

  private static int getCacheServerPort() {
    return bridgeServerPort;
  }

  public static class TestObject implements DataSerializable {
    protected String _ticker;
    protected int _price;
    public int id;
    public int important;
    public int selection;
    public int select;

    public TestObject() {}

    public TestObject(int id, String ticker) {
      this.id = id;
      _ticker = ticker;
      _price = id;
      important = id;
      selection = id;
      select = id;
    }

    public int getId() {
      return id;
    }

    public String getTicker() {
      return _ticker;
    }

    public int getPrice() {
      return _price;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      // System.out.println("Is serializing in WAN: " + GatewayEventImpl.isSerializingValue());
      out.writeInt(id);
      DataSerializer.writeString(_ticker, out);
      out.writeInt(_price);
    }

    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      // System.out.println("Is deserializing in WAN: " + GatewayEventImpl.isDeserializingValue());
      id = in.readInt();
      _ticker = DataSerializer.readString(in);
      _price = in.readInt();
    }

    public String toString() {
      return "TestObject [" + "id=" + id + "; ticker="
          + _ticker + "; price=" + _price + "]";
    }
  }

  public static class IdComparator implements Comparator {

    @Override
    public int compare(Object obj1, Object obj2) {
      int obj1Id = ((TestObject) obj1).getId();
      int obj2Id = ((TestObject) obj2).getId();
      if (obj1Id > obj2Id) {
        return 1;
      } else if (obj1Id < obj2Id) {
        return -1;
      } else {
        return 0;
      }
    }
  }

  public static class StructIdComparator implements Comparator {

    @Override
    public int compare(Object obj1, Object obj2) {
      int obj1Id = (Integer) ((Struct) obj1).get("id");
      int obj2Id = (Integer) ((Struct) obj2).get("id");
      if (obj1Id > obj2Id) {
        return 1;
      } else if (obj1Id < obj2Id) {
        return -1;
      } else {
        return 0;
      }
    }
  }
}

/*
 * String queryString = "ticker = 'ibm'"; SelectResults results = region.query(queryString);
 *
 * String queryString = "ticker = 'ibm' and price = 50";
 *
 * String queryString = "select distinct * from /trade";
 *
 * String queryString = "import TestObject; select distinct * from /trade";
 *
 * String queryString = "IMPORT Position; " + "SELECT DISTINCT id, status FROM /root/portfolios " +
 * "WHERE NOT (SELECT DISTINCT * FROM positions.values posnVal TYPE Position " +
 * "WHERE posnVal.secId='AOL' OR posnVal.secId='SAP').isEmpty";
 *
 * queryString = "SELECT DISTINCT itr.value FROM /trade.entries itr where itr.key = 'key-1'";
 *
 * queryString = "SELECT DISTINCT itr.key FROM /trade.entries itr where itr.key = 'key-1'";
 *
 * String queryString =
 * "select distinct a, b.price from /generic/app1/Trade a, /generic/app1/Trade b where a.price = b.price"
 * ;
 *
 * String queryString =
 * "SELECT DISTINCT a, b.unitPrice from /newegg/arinv a, /newegg/itemPriceSetting b where a.item = b.itemNumber and a.item = '26-106-934'"
 * ;
 *
 * String queryString =
 * "SELECT DISTINCT a, UNDEFINED from /newegg/arinv a, /newegg/itemPriceSetting b where a.item = b.itemNumber and a.item = '26-106-934'"
 * ;
 *
 */
