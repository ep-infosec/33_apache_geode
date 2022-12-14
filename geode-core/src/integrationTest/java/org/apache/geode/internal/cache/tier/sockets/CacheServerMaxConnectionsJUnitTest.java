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
package org.apache.geode.internal.cache.tier.sockets;

import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.internal.AvailablePortHelper.getRandomAvailableTCPPort;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Properties;

import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.Statistics;
import org.apache.geode.StatisticsType;
import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.client.NoAvailableServersException;
import org.apache.geode.cache.client.PoolFactory;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.client.internal.Connection;
import org.apache.geode.cache.client.internal.PoolImpl;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.WaitCriterion;
import org.apache.geode.test.junit.categories.ClientServerTest;

/**
 * Make sure max-connections on cache server is enforced
 */
@Category({ClientServerTest.class})
public class CacheServerMaxConnectionsJUnitTest {

  private static final int MAX_CNXS = 100;

  private static int PORT;

  /** name of the region created */
  private final String regionName = "region1";

  /** connection proxy object for the client */
  private PoolImpl proxy = null;

  /** the distributed system instance for the test */
  private DistributedSystem system;

  /** the cache instance for the test */
  private Cache cache;

  /**
   * Close the cache and disconnects from the distributed system
   */
  @After
  public void tearDown() throws Exception {
    cache.close();
    system.disconnect();
  }

  /**
   * Default to 0; override in sub tests to add thread pool
   */
  protected int getMaxThreads() {
    return 0;
  }

  /**
   * Initializes proxy object and creates region for client
   */
  private void createProxyAndRegionForClient() {
    // props.setProperty("retryAttempts", "0");
    PoolFactory pf = PoolManager.createFactory();
    pf.addServer("localhost", PORT);
    pf.setMinConnections(0);
    pf.setPingInterval(10000);
    pf.setReadTimeout(2000);
    pf.setSocketBufferSize(32768);
    proxy = (PoolImpl) pf.create("junitPool");
    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setPoolName("junitPool");
    RegionAttributes attrs = factory.createRegionAttributes();
    cache.createVMRegion(regionName, attrs);
  }

  /**
   * Creates and starts the server instance
   */
  private int createServer() throws IOException {
    CacheServer server = null;
    Properties p = new Properties();
    // make it a loner
    p.put(MCAST_PORT, "0");
    p.put(LOCATORS, "");
    system = DistributedSystem.connect(p);
    cache = CacheFactory.create(system);
    server = cache.addCacheServer();
    int port = getRandomAvailableTCPPort();
    server.setMaxConnections(MAX_CNXS);
    server.setMaxThreads(getMaxThreads());
    server.setPort(port);
    server.start();
    return server.getPort();
  }

  /**
   * This test performs the following:<br>
   * 1)create server<br>
   * 2)initialize proxy object and create region for client<br>
   * 3)perform a PUT on client by acquiring Connection through proxy<br>
   * 4)stop server monitor threads in client to ensure that server treats this as dead client <br>
   * 5)wait for some time to allow server to clean up the dead client artifacts<br>
   * 6)again perform a PUT on client through same Connection and verify after the put that the
   * Connection object used was new one.
   */
  @Test
  public void testMaxCnxLimit() throws Exception {
    PORT = createServer();
    createProxyAndRegionForClient();
    StatisticsType st = system.findType("CacheServerStats");
    final Statistics s = system.findStatisticsByType(st)[0];
    assertEquals(0, s.getInt("currentClients"));
    assertEquals(0, s.getInt("currentClientConnections"));
    Connection[] cnxs = new Connection[MAX_CNXS];
    for (int i = 0; i < MAX_CNXS; i++) {
      cnxs[i] = proxy.acquireConnection();
      system.getLogWriter().info("acquired connection[" + i + "]=" + cnxs[i]);
    }
    WaitCriterion ev = new WaitCriterion() {
      @Override
      public boolean done() {
        return s.getInt("currentClientConnections") == MAX_CNXS;
      }

      @Override
      public String description() {
        return null;
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
    assertEquals(MAX_CNXS, s.getInt("currentClientConnections"));
    assertEquals(1, s.getInt("currentClients"));
    system.getLogWriter().info(
        "<ExpectedException action=add>" + "exceeded max-connections" + "</ExpectedException>");
    try {
      Connection cnx = proxy.acquireConnection();
      if (cnx != null) {
        fail("should not have been able to connect more than " + MAX_CNXS
            + " times but was able to connect " + s.getInt("currentClientConnections")
            + " times. Last connection=" + cnx);
      }
      system.getLogWriter().info("acquire connection returned null which is ok");
    } catch (NoAvailableServersException expected) {
      // This is expected but due to race conditions in server handshake
      // we may get null back from acquireConnection instead.
      system.getLogWriter().info("received expected " + expected.getMessage());
    } catch (Exception ex) {
      fail("expected acquireConnection to throw NoAvailableServersException but instead it threw "
          + ex);
    } finally {
      system.getLogWriter().info("<ExpectedException action=remove>"
          + "exceeded max-connections" + "</ExpectedException>");
    }

    // now lets see what happens we we close our connections
    for (int i = 0; i < MAX_CNXS; i++) {
      cnxs[i].close(false);
    }
    ev = new WaitCriterion() {
      @Override
      public boolean done() {
        return s.getInt("currentClients") == 0;
      }

      @Override
      public String description() {
        return null;
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
    system.getLogWriter().info("currentClients=" + s.getInt("currentClients")
        + " currentClientConnections=" + s.getInt("currentClientConnections"));
    assertEquals(0, s.getInt("currentClientConnections"));
    assertEquals(0, s.getInt("currentClients"));
  }
}
