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

import static org.apache.geode.SystemFailure.initiateFailure;
import static org.apache.geode.cache.Region.SEPARATOR;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.internal.AvailablePortHelper.getRandomAvailableTCPPort;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.EOFException;
import java.net.SocketException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.LogWriter;
import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.client.internal.PoolImpl;
import org.apache.geode.cache.client.internal.RegisterInterestTracker;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.internal.cache.CacheServerImpl;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.NetworkUtils;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.Wait;
import org.apache.geode.test.dunit.WaitCriterion;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;
import org.apache.geode.test.junit.categories.ClientSubscriptionTest;

/**
 * Test Scenario :
 *
 * one client(c1) two servers(s1,s2) s1,s2 ----> available c1: register k1,k2,k3,k4,k5 s1 ---->
 * unavailable // fail over should happen to server s2 see all keys k1,k2,k3,k4,k5 are registered on
 * s2 c1: unregister k1,k2,k3 see interest list on s1 contains only s4, s5 s2 ----> unavaliable //
 * fail over should to s1 with intrest list s4,s5 see only k4 and k5 are registerd on s1
 */
@Category({ClientSubscriptionTest.class})
public class InterestListRecoveryDUnitTest extends JUnit4DistributedTestCase {

  private static final String REGION_NAME =
      InterestListRecoveryDUnitTest.class.getSimpleName() + "_region";

  private static Cache cache = null;

  VM server1 = null;

  VM server2 = null;

  protected static PoolImpl pool = null;

  private static int PORT1;
  private static int PORT2;

  @Override
  public final void postSetUp() throws Exception {
    disconnectAllFromDS();
    Wait.pause(2000);
    final Host host = Host.getHost(0);
    server1 = host.getVM(0);
    server2 = host.getVM(1);
    // start servers first
    PORT1 = server1.invoke(InterestListRecoveryDUnitTest::createServerCache);
    PORT2 = server2.invoke(InterestListRecoveryDUnitTest::createServerCache);

    org.apache.geode.test.dunit.LogWriterUtils.getLogWriter()
        .info("server1 port is " + PORT1);
    org.apache.geode.test.dunit.LogWriterUtils.getLogWriter()
        .info("server2 port is " + PORT2);

    createClientCache(NetworkUtils.getServerHostName(host), PORT1, PORT2);
  }

  @Ignore("TODO: test is disabled because of #35352: proxy.markServerUnavailable() is not causing interestListEndpoint to change")
  @Test
  public void testKeyInterestRecoveryWhileServerFailover() throws Exception {
    createEntries();
    server1.invoke(InterestListRecoveryDUnitTest::createEntries);
    registerK1toK5();
    setServerUnavailable("localhost" + PORT1);
    Wait.pause(20000);
    unregisterK1toK3();
    setServerAvailable("localhost" + PORT1);
    Wait.pause(20000);
    setServerUnavailable("localhost" + PORT2);
    Wait.pause(20000);
    fail("invoking bad method");
    // This method doesn't exist
    // server1.invoke(() -> InterestListRecoveryDUnitTest.verifyUnregisterK1toK3());

  }

  @Test
  public void testKeyInterestRecoveryWhileProcessException() throws Exception {
    VM serverFirstRegistered = null;
    VM serverSecondRegistered = null;

    LogWriter logger = basicGetSystem().getLogWriter();
    createEntries();
    server2.invoke(InterestListRecoveryDUnitTest::createEntries);
    server1.invoke(InterestListRecoveryDUnitTest::createEntries);

    registerK1toK5();
    logger.fine("After registerK1toK5");

    // Check which server InterestList is registered. Based on it verify
    // Register/Unregister on respective servers.
    if (isInterestListRegisteredToServer1()) {
      serverFirstRegistered = server1;
      serverSecondRegistered = server2;
      logger.fine("serverFirstRegistered is server1 and serverSecondRegistered is server2");
    } else {
      serverFirstRegistered = server2;
      serverSecondRegistered = server1;
      logger.fine("serverFirstRegistered is server2 and serverSecondRegistered is server1");
    }
    verifyDeadAndLiveServers(0, 2);
    serverFirstRegistered
        .invoke(InterestListRecoveryDUnitTest::verifyRegionToProxyMapForFullRegistration);
    logger.fine("After verifyRegionToProxyMapForFullRegistration on serverFirstRegistered");
    logger.info("<ExpectedException action=add>" + SocketException.class.getName()
        + "</ExpectedException>");
    logger.info(
        "<ExpectedException action=add>" + EOFException.class.getName() + "</ExpectedException>");
    killCurrentEndpoint();
    logger.fine("After killCurrentEndpoint1");
    serverSecondRegistered.invoke(
        InterestListRecoveryDUnitTest::verifyRegionToProxyMapForFullRegistrationRetry);
    logger.fine("After verifyRegionToProxyMapForFullRegistration on serverSecondRegistered");
    unregisterK1toK3();
    serverSecondRegistered.invoke(InterestListRecoveryDUnitTest::verifyRegisterK4toK5Retry);
    logger.fine("After verifyRegisterK4toK5Retry on serverSecondRegistered");
  }

  private boolean isInterestListRegisteredToServer1() {
    /*
     * try { server1.invoke(() ->
     * InterestListRecoveryDUnitTest.verifyRegionToProxyMapForFullRegistration()); } catch
     * (Throwable t) { // Means its registered on server2. return false; } return true;
     */
    // check whether the primary endpoint is connected to server1 or server2
    try {
      Region<?, ?> r1 = cache.getRegion(SEPARATOR + REGION_NAME);
      String poolName = r1.getAttributes().getPoolName();
      assertNotNull(poolName);
      pool = (PoolImpl) PoolManager.find(poolName);
      assertNotNull(pool);
      return (pool.getPrimaryPort() == PORT1);
    } catch (Exception ex) {
      Assert.fail("while isInterestListRegisteredToServer1", ex);
    }
    // never reached
    return false;
  }

  private Cache createCache(Properties props) throws Exception {
    DistributedSystem ds = getSystem(props);
    Cache cache = null;
    cache = CacheFactory.create(ds);
    if (cache == null) {
      throw new Exception("CacheFactory.create() returned null ");
    }
    return cache;
  }

  public static void createClientCache(String host, Integer port1, Integer port2) throws Exception {
    InterestListRecoveryDUnitTest test = new InterestListRecoveryDUnitTest();
    Properties props = new Properties();
    props.setProperty(MCAST_PORT, "0");
    props.setProperty(LOCATORS, "");
    cache = test.createCache(props);
    PoolImpl p = (PoolImpl) PoolManager.createFactory().addServer(host, port1)
        .addServer(host, port2).setSubscriptionEnabled(true)
        .setSubscriptionRedundancy(-1).setReadTimeout(250)
        .setSocketBufferSize(32768).setMinConnections(4)
        // .setRetryAttempts(5)
        // .setRetryInterval(1000)
        .create("InterestListRecoveryDUnitTestPool");

    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setPoolName(p.getName());
    RegionAttributes attrs = factory.create();
    cache.createRegion(REGION_NAME, attrs);
    pool = p;

  }

  public static Integer createServerCache() throws Exception {
    InterestListRecoveryDUnitTest test = new InterestListRecoveryDUnitTest();
    cache = test.createCache(new Properties());
    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setDataPolicy(DataPolicy.REPLICATE);
    RegionAttributes attrs = factory.create();
    cache.createRegion(REGION_NAME, attrs);
    int port = getRandomAvailableTCPPort();
    CacheServer server1 = cache.addCacheServer();
    server1.setPort(port);
    server1.setNotifyBySubscription(true);
    server1.start();
    return server1.getPort();
  }

  public static void createEntries() {
    try {
      LocalRegion r1 = (LocalRegion) cache.getRegion(SEPARATOR + REGION_NAME);
      for (int i = 1; i < 6; i++) {
        if (!r1.containsKey("key-" + i)) {
          r1.create("key-" + i, "key-" + i);
        }
        assertEquals(r1.getEntry("key-" + i).getValue(), "key-" + i);
      }
    } catch (Exception ex) {
      Assert.fail("failed while createEntries()", ex);
    }
  }

  public static void registerK1toK5() {
    try {
      LocalRegion r = (LocalRegion) cache.getRegion(SEPARATOR + REGION_NAME);
      for (int i = 1; i < 6; i++) {
        r.registerInterest("key-" + i, InterestResultPolicy.KEYS);
      }
    } catch (Exception ex) {
      Assert.fail("failed while registering keys", ex);
    }
  }

  public static void unregisterK1toK3() {
    try {
      LocalRegion r = (LocalRegion) cache.getRegion(SEPARATOR + REGION_NAME);
      for (int i = 1; i < 4; i++) {
        r.unregisterInterest("key-" + i);
      }
    } catch (Exception ex) {
      Assert.fail("failed while un-registering keys", ex);
    }
  }


  public static void setServerUnavailable(String server) {
    try {
      throw new Exception("nyi");
      // ConnectionProxyImpl.markServerUnavailable(server);
    } catch (Exception ex) {
      Assert.fail("while setting server unavailable  " + server, ex);
    }
  }

  public static void setServerAvailable(String server) {
    try {
      throw new Exception("nyi");
      // ConnectionProxyImpl.markServerAvailable(server);
    } catch (Exception ex) {
      Assert.fail("while setting server available  " + server, ex);
    }
  }

  public static void killCurrentEndpoint() {
    try {
      Region r1 = cache.getRegion(SEPARATOR + REGION_NAME);
      String poolName = r1.getAttributes().getPoolName();
      assertNotNull(poolName);
      pool = (PoolImpl) PoolManager.find(poolName);
      assertNotNull(pool);
      pool.killPrimaryEndpoint();
    } catch (Exception ex) {
      fail("while killCurrentEndpoint  " + ex);
    }
  }

  public static void put(String key) {
    try {
      Region r1 = cache.getRegion(SEPARATOR + REGION_NAME);
      r1.put(key, "server-" + key);
    } catch (Exception ex) {
      Assert.fail("failed while r.put()", ex);
    }
  }

  public static void verifyRegionToProxyMapForFullRegistrationRetry() {
    WaitCriterion ev = new WaitCriterion() {
      @Override
      public boolean done() {
        try {
          verifyRegionToProxyMapForFullRegistration();
          return true;
        } catch (VirtualMachineError e) {
          initiateFailure(e);
          throw e;
        } catch (Error e) {
          return false;
        } catch (RuntimeException re) {
          return false;
        }
      }

      @Override
      public String description() {
        return null;
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
  }

  public static void verifyRegionToProxyMapForFullRegistration() {
    Iterator iter = getCacheClientProxies().iterator();
    if (iter.hasNext()) {
      Set keys = getKeysOfInterestMap((CacheClientProxy) iter.next(), SEPARATOR + REGION_NAME);
      assertNotNull(keys);

      assertTrue(keys.contains("key-1"));
      assertTrue(keys.contains("key-2"));
      assertTrue(keys.contains("key-3"));
      assertTrue(keys.contains("key-4"));
      assertTrue(keys.contains("key-5"));
    }
  }

  public static void verifyRegisterK4toK5Retry() {
    WaitCriterion ev = new WaitCriterion() {
      @Override
      public boolean done() {
        try {
          verifyRegisterK4toK5();
          return true;
        } catch (VirtualMachineError e) {
          initiateFailure(e);
          throw e;
        } catch (Error e) {
          return false;
        } catch (RuntimeException re) {
          return false;
        }
      }

      @Override
      public String description() {
        return "verifyRegisterK4toK5Retry";
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
  }

  public static void verifyRegisterK4toK5() {
    Iterator iter = getCacheClientProxies().iterator();
    if (iter.hasNext()) {
      Set keysMap = getKeysOfInterestMap((CacheClientProxy) iter.next(), SEPARATOR + REGION_NAME);
      assertNotNull(keysMap);

      assertFalse(keysMap.contains("key-1"));
      assertFalse(keysMap.contains("key-2"));
      assertFalse(keysMap.contains("key-3"));
      assertTrue(keysMap.contains("key-4"));
      assertTrue(keysMap.contains("key-5"));
    }
  }

  public static void verifyRegionToProxyMapForNoRegistrationRetry() {
    WaitCriterion ev = new WaitCriterion() {
      @Override
      public boolean done() {
        try {
          verifyRegionToProxyMapForNoRegistration();
          return true;
        } catch (VirtualMachineError e) {
          initiateFailure(e);
          throw e;
        } catch (Error e) {
          return false;
        } catch (RuntimeException re) {
          return false;
        }
      }

      @Override
      public String description() {
        return null;
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
  }

  public static void verifyRegionToProxyMapForNoRegistration() {
    Iterator iter = getCacheClientProxies().iterator();
    if (iter.hasNext()) {
      Set keysMap = getKeysOfInterestMap((CacheClientProxy) iter.next(), SEPARATOR + REGION_NAME);
      if (keysMap != null) { // its ok not to have an empty map, just means there is no registration
        assertFalse(keysMap.contains("key-1"));
        assertFalse(keysMap.contains("key-2"));
        assertFalse(keysMap.contains("key-3"));
        assertFalse(keysMap.contains("key-4"));
        assertFalse(keysMap.contains("key-5"));
      }
    }
  }

  public static Set getCacheClientProxies() {
    Cache c = CacheFactory.getAnyInstance();
    assertEquals("More than one CacheServer", 1, c.getCacheServers().size());
    CacheServerImpl bs = (CacheServerImpl) c.getCacheServers().iterator().next();
    assertNotNull(bs);
    assertNotNull(bs.getAcceptor());
    assertNotNull(bs.getAcceptor().getCacheClientNotifier());
    return new HashSet(bs.getAcceptor().getCacheClientNotifier().getClientProxies());
  }

  public static Set getKeysOfInterestMap(CacheClientProxy proxy, String regionName) {
    // assertNotNull(proxy.cils[RegisterInterestTracker.interestListIndex]);
    // assertNotNull(proxy.cils[RegisterInterestTracker.interestListIndex]._keysOfInterest);
    return proxy.cils[RegisterInterestTracker.interestListIndex].getProfile(regionName)
        .getKeysOfInterestFor(proxy.getProxyID());
  }

  @Override
  public final void preTearDown() throws Exception {
    // close the clients first
    server2.invoke(InterestListRecoveryDUnitTest::closeCache);
    closeCache();
    // then close the servers
    server1.invoke(InterestListRecoveryDUnitTest::closeCache);
  }

  public static void closeCache() {
    if (cache != null && !cache.isClosed()) {
      cache.close();
      cache.getDistributedSystem().disconnect();
    }
  }

  public static void verifyDeadAndLiveServers(final int expectedDeadServers,
      final int expectedLiveServers) {
    WaitCriterion wc = new WaitCriterion() {
      String excuse;

      @Override
      public boolean done() {
        int sz = pool.getConnectedServerCount();
        return sz == expectedLiveServers;
      }

      @Override
      public String description() {
        return excuse;
      }
    };
    GeodeAwaitility.await().untilAsserted(wc);
  }
}
