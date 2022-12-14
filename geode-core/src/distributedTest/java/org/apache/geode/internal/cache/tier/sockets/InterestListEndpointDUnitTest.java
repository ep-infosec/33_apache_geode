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

import static org.apache.geode.cache.Region.Entry;
import static org.apache.geode.cache.Region.SEPARATOR;
import static org.apache.geode.cache.client.PoolManager.find;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.internal.AvailablePortHelper.getRandomAvailableTCPPort;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Iterator;
import java.util.Properties;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.CacheWriterException;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.client.internal.Connection;
import org.apache.geode.cache.client.internal.PoolImpl;
import org.apache.geode.cache.client.internal.QueueConnectionImpl;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.cache30.CacheSerializableRunnable;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.internal.cache.CacheServerImpl;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.Invoke;
import org.apache.geode.test.dunit.NetworkUtils;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.Wait;
import org.apache.geode.test.dunit.WaitCriterion;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;
import org.apache.geode.test.junit.categories.ClientSubscriptionTest;

@Category({ClientSubscriptionTest.class})
public class InterestListEndpointDUnitTest extends JUnit4DistributedTestCase {

  VM server1 = null;

  static VM server2 = null;

  static VM client1 = null;

  protected static Cache cache = null;
  private int PORT1;
  private int PORT2;

  private static Connection conn1;
  private static PoolImpl pool;
  private static final String REGION_NAME = "InterestListEndpointDUnitTest_region";

  private static final String k1 = "k1";
  private static final String k2 = "k2";
  private static final String client_k1 = "client-k1";
  private static final String client_k2 = "client-k2";
  private static final String server_k1 = "server-k1";
  private static final String server_k2 = "server-k2";

  static InterestListEndpointDUnitTest impl;

  @Override
  public final void postSetUp() throws Exception {
    disconnectAllFromDS();
    Wait.pause(5000);
    final Host host = Host.getHost(0);
    server1 = host.getVM(0);
    server2 = host.getVM(1);
    client1 = host.getVM(2);

    createImpl();
    for (int i = 0; i < 4; i++) {
      host.getVM(i).invoke(getClass(), "createImpl", null);
    }

    // create servers first
    PORT1 = initServerCache(server1);
    PORT2 = initServerCache(server2);

    // then create client
    Wait.pause(5000); // [bruce] avoid ConnectException
    client1.invoke(() -> createClientCache(NetworkUtils.getServerHostName(server1.getHost()),
        PORT1, PORT2));
  }

  /** subclass support */
  public static void createImpl() {
    impl = new InterestListEndpointDUnitTest();
  }

  private void createCache(Properties props) throws Exception {
    DistributedSystem ds = getSystem(props);
    cache = CacheFactory.create(ds);
    assertNotNull(cache);

  }

  /**
   * tests whetheer upadets are sent to clients if put on server directly
   *
   */
  @Test
  public void testDirectPutOnServer() {
    client1.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2);
    server1.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2);
    server2.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2);

    client1.invoke(InterestListEndpointDUnitTest::registerKey1);
    // directly put on server
    server1.invoke(InterestListEndpointDUnitTest::put);
    client1.invoke(InterestListEndpointDUnitTest::verifyPut);
  }

  /**
   * put on non interest list ep and verify updates
   *
   */
  @Test
  public void testInterestListEndpoint() {
    client1.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2);
    server2.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2); // server
    server1.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2); // server

    client1.invoke(InterestListEndpointDUnitTest::registerKey1);

    server1.invoke(InterestListEndpointDUnitTest::verifyIfNotInterestListEndpointAndThenPut);
    server2.invoke(InterestListEndpointDUnitTest::verifyIfNotInterestListEndpointAndThenPut);
    client1.invoke(InterestListEndpointDUnitTest::verifyPut);
  }

  @Test
  public void testInterestListEndpointAfterFailover() throws Exception {
    final long maxWaitTime = 20000;
    client1.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2);
    server2.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2);
    server1.invoke(InterestListEndpointDUnitTest::createEntriesK1andK2);

    client1.invoke(InterestListEndpointDUnitTest::registerKey1);

    boolean firstIsPrimary = isVm0Primary();
    VM primary = firstIsPrimary ? server1 : server2;

    primary.invoke(InterestListEndpointDUnitTest::stopILEndpointServer);
    Wait.pause(5000);

    // Since the loadbalancing policy is roundrobin & there are two servers so
    // do two dumb puts, which will ensure that fail over happens from the
    // interest list end point in case Live ServerMonitor is not working
    client1.invoke(new CacheSerializableRunnable("Ensure that the failover from ILEP occurs") {
      @Override
      public void run2() throws CacheException {
        Region r = cache.getRegion(SEPARATOR + REGION_NAME);

        String poolName = r.getAttributes().getPoolName();
        assertNotNull(poolName);
        final PoolImpl pool = (PoolImpl) find(poolName);
        assertNotNull(pool);
        pool.acquireConnection();
        try {
          r.put("ping", "pong1"); // Used in the case where we don't have a LiveServerMonitorThread

        } catch (CacheWriterException ignored) {
        }

        try {
          r.put("ping", "pong2"); // Used in the case where we don't have a LiveServerMonitorThread

        } catch (CacheWriterException ignored) {
        }

        WaitCriterion ev = new WaitCriterion() {
          @Override
          public boolean done() {
            return pool.getConnectedServerCount() != 2;
          }

          @Override
          public String description() {
            return null;
          }
        };
        GeodeAwaitility.await().untilAsserted(ev);
      }
    });

    // put on stopped server
    primary.invoke(InterestListEndpointDUnitTest::put);
    client1.invoke(InterestListEndpointDUnitTest::verifyPut);
  }


  public boolean isVm0Primary() throws Exception {
    int port = client1.invoke(InterestListEndpointDUnitTest::getPrimaryPort);
    return port == PORT1;
  }

  public static int getPrimaryPort() throws Exception {
    Region r1 = cache.getRegion(SEPARATOR + REGION_NAME);
    String poolName = r1.getAttributes().getPoolName();
    assertNotNull(poolName);
    pool = (PoolImpl) PoolManager.find(poolName);
    assertNotNull(pool);
    assertTrue(pool.getPrimaryName() != null);
    return pool.getPrimaryPort();
  }


  @Test
  public void testUpdaterThreadIsAliveForFailedEndPoint() {
    client1.invoke(InterestListEndpointDUnitTest::acquirePoolConnection);
    client1.invoke(InterestListEndpointDUnitTest::processException);
    client1.invoke(InterestListEndpointDUnitTest::verifyUpdaterThreadIsAlive);
  }

  public static void acquirePoolConnection() {
    try {
      Region r1 = cache.getRegion(SEPARATOR + REGION_NAME);
      assertNotNull(r1);
      String poolName = r1.getAttributes().getPoolName();
      assertNotNull(poolName);
      pool = (PoolImpl) PoolManager.find(poolName);
      assertNotNull(pool);
      conn1 = pool.getPrimaryConnection();
      assertNotNull(conn1);
    } catch (Exception ex) {
      throw new RuntimeException("Exception while setting acquireConnections  ", ex);
    }
  }

  public static void processException() {
    try {
      pool.processException(new IOException(), conn1);
    } catch (Exception ex) {
      throw new RuntimeException("Exception while setting processException  ", ex);
    }
  }

  public static void verifyUpdaterThreadIsAlive() throws InterruptedException {
    QueueConnectionImpl conn2 = (QueueConnectionImpl) pool.getPrimaryConnection();
    assertNotSame(conn1, conn2);
    assertFalse(conn1.getServer().equals(conn2.getServer()));
    assertNull(((QueueConnectionImpl) conn1).getUpdater());
    assertTrue((conn2).getUpdater().isAlive());
  }

  public static void stopILEndpointServer() {
    try {
      Cache c = CacheFactory.getAnyInstance();
      assertEquals("More than one BridgeServer", 1, c.getCacheServers().size());
      CacheServerImpl bs = (CacheServerImpl) c.getCacheServers().iterator().next();
      assertNotNull(bs);
      assertNotNull(bs.getAcceptor());
      assertNotNull(bs.getAcceptor().getCacheClientNotifier());
      Iterator iter_prox = bs.getAcceptor().getCacheClientNotifier().getClientProxies().iterator();
      if (iter_prox.hasNext()) {
        CacheClientProxy proxy = (CacheClientProxy) iter_prox.next();
        if (proxy.isPrimary()) {
          Iterator iter = cache.getCacheServers().iterator();
          if (iter.hasNext()) {
            CacheServer server = (CacheServer) iter.next();
            cache.getLogger().fine("stopping server " + server);
            server.stop();
          }
        }
      }
    } catch (Exception ex) {
      throw new RuntimeException("Exception while setting stopServer  ", ex);
    }
  }

  public static void createEntriesK1andK2() {
    try {
      Region r1 = cache.getRegion(SEPARATOR + REGION_NAME);
      assertNotNull(r1);
      if (!r1.containsKey(k1)) {
        r1.create(k1, client_k1);
      }
      if (!r1.containsKey(k2)) {
        r1.create(k2, client_k2);
      }
      if (r1.getAttributes().getPartitionAttributes() == null) {
        assertEquals(r1.getEntry(k1).getValue(), client_k1);
        assertEquals(r1.getEntry(k2).getValue(), client_k2);
      }
    } catch (Exception ex) {
      throw new RuntimeException("failed while createEntries()", ex);
    }
  }

  public static void createClientCache(String host, Integer port1, Integer port2) throws Exception {
    CacheServerTestUtil.disableShufflingOfEndpoints();
    Properties props = new Properties();
    props.setProperty(MCAST_PORT, "0");
    props.setProperty(LOCATORS, "");
    new InterestListEndpointDUnitTest().createCache(props);
    Pool p;
    try {
      p = PoolManager.createFactory().addServer(host, port1)
          .addServer(host, port2).setSubscriptionEnabled(true)
          .setSubscriptionRedundancy(-1).setMinConnections(6).setSocketBufferSize(32768)
          .setReadTimeout(2000)
          // .setRetryInterval(1000)
          // .setRetryAttempts(5)
          .create("InterestListEndpointDUnitTestPool");
    } finally {
      CacheServerTestUtil.enableShufflingOfEndpoints();
    }

    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setPoolName(p.getName());
    RegionAttributes attrs = factory.create();
    cache.createRegion(REGION_NAME, attrs);

  }

  protected int getMaxThreads() {
    return 0;
  }

  private int initServerCache(VM server) {
    Object[] args = new Object[] {getMaxThreads()};
    return (Integer) server.invoke(InterestListEndpointDUnitTest.class, "createServerCache", args);
  }

  public static Integer createServerCache(Integer maxThreads) throws Exception {
    new InterestListEndpointDUnitTest().createCache(new Properties());
    RegionAttributes attrs = impl.createServerCacheAttributes();
    cache.createRegion(REGION_NAME, attrs);
    CacheServer server1 = cache.addCacheServer();
    int port = getRandomAvailableTCPPort();
    server1.setPort(port);
    server1.setMaxThreads(maxThreads);
    server1.setNotifyBySubscription(true);
    server1.start();
    return server1.getPort();
  }

  protected RegionAttributes createServerCacheAttributes() {
    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setDataPolicy(DataPolicy.REPLICATE);
    return factory.create();
  }

  public static void put() {
    try {
      Region r = cache.getRegion(SEPARATOR + REGION_NAME);
      assertNotNull(r);
      r.put(k1, server_k1);
      r.put(k2, server_k2);
    } catch (Exception ex) {
      throw new RuntimeException("failed while region.put()", ex);
    }
  }

  public static void verifyIfNotInterestListEndpointAndThenPut() {
    try {
      Cache c = CacheFactory.getAnyInstance();
      assertEquals("More than one CacheServer", 1, c.getCacheServers().size());
      CacheServerImpl bs = (CacheServerImpl) c.getCacheServers().iterator().next();
      assertNotNull(bs);
      assertNotNull(bs.getAcceptor());
      assertNotNull(bs.getAcceptor().getCacheClientNotifier());
      Iterator iter = bs.getAcceptor().getCacheClientNotifier().getClientProxies().iterator();
      // only one server thats why if and not while
      if (iter.hasNext()) {
        CacheClientProxy proxy = (CacheClientProxy) iter.next();
        if (!proxy.isPrimary()) {
          Region r = cache.getRegion(SEPARATOR + REGION_NAME);
          r.put(k1, server_k1);
          r.put(k2, server_k2);
        }
      }
    } catch (Exception ex) {
      org.apache.geode.test.dunit.Assert
          .fail("failed while verifyIfNotInterestListEndpointAndThenPut()", ex);
    }
  }

  public static void registerKey1() {
    try {
      Region r = cache.getRegion(SEPARATOR + REGION_NAME);
      assertNotNull(r);
      r.registerInterest(k1, InterestResultPolicy.KEYS);
    } catch (Exception ex) {
      org.apache.geode.test.dunit.Assert.fail("failed while region.registerInterest()", ex);
    }
  }

  public static void verifyPut() {
    try {
      final Region r = cache.getRegion(SEPARATOR + REGION_NAME);
      assertNotNull(r);
      WaitCriterion ev = new WaitCriterion() {
        @Override
        public boolean done() {
          Entry e1 = r.getEntry(k1);
          if (e1 == null) {
            return false;
          }
          Entry e2 = r.getEntry(k2);
          if (e2 == null) {
            return false;
          }
          Object v1 = e1.getValue();
          if (!server_k1.equals(v1)) {
            return false;
          }
          Object v2 = e2.getValue();
          return client_k2.equals(v2);
          // our state is ready for the assertions
        }

        @Override
        public String description() {
          Entry e1 = r.getEntry(k1);
          if (e1 == null) {
            return "Entry for " + k1 + " is null";
          }
          Entry e2 = r.getEntry(k2);
          if (e2 == null) {
            return "Entry for " + k2 + " is null";
          }
          Object v1 = e1.getValue();
          if (!server_k1.equals(v1)) {
            return "v1 supposed to be " + server_k1 + " but is " + v1;
          }
          Object v2 = e2.getValue();
          if (!client_k2.equals(v2)) {
            return "v2 supposed to be " + client_k2 + " but is " + v2;
          }
          return "Test missed a success";
        }
      };
      GeodeAwaitility.await().untilAsserted(ev);

      // yes update
      assertEquals(server_k1, r.getEntry(k1).getValue());
      // no update
      assertEquals(client_k2, r.getEntry(k2).getValue());
    } catch (Exception ex) {
      throw new RuntimeException("failed while region.verifyPut()", ex);
    }
  }

  public static void closeCache() {
    if (cache != null && !cache.isClosed()) {
      cache.close();
      cache.getDistributedSystem().disconnect();
    }
  }

  @Override
  public final void preTearDown() throws Exception {
    // Close client cache first, then server caches
    client1.invoke(InterestListEndpointDUnitTest::closeCache);
    server2.invoke(InterestListEndpointDUnitTest::closeCache);
    server1.invoke(InterestListEndpointDUnitTest::closeCache);
    CacheServerTestUtil.resetDisableShufflingOfEndpointsFlag();
    cache = null;
    Invoke.invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        cache = null;
      }
    });
  }
}
