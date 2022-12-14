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

import static java.util.Map.Entry;
import static org.apache.geode.cache.Region.SEPARATOR;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.internal.AvailablePortHelper.getRandomAvailableTCPPort;
import static org.apache.geode.test.dunit.LogWriterUtils.getLogWriter;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.client.internal.PoolImpl;
import org.apache.geode.cache.client.internal.QueueStateImpl.SequenceIdAndExpirationObject;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.internal.cache.ClientServerObserver;
import org.apache.geode.internal.cache.ClientServerObserverAdapter;
import org.apache.geode.internal.cache.ClientServerObserverHolder;
import org.apache.geode.internal.cache.ha.HAHelper;
import org.apache.geode.internal.cache.ha.HARegionQueue;
import org.apache.geode.internal.cache.ha.ThreadIdentifier;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.IgnoredException;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.NetworkUtils;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.WaitCriterion;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;
import org.apache.geode.test.junit.categories.ClientServerTest;

/**
 * Tests the reliable messaging functionality - Client sends a periodic ack to the primary server
 * for the messages received.
 */
@Category({ClientServerTest.class})
public class ReliableMessagingDUnitTest extends JUnit4DistributedTestCase {

  static VM server1 = null;

  static VM server2 = null;

  /** the cache */
  private static Cache cache = null;

  /** port for the cache server */
  private static int PORT1;

  private static int PORT2;

  static PoolImpl pool = null;

  static ThreadIdentifier tid = null;

  static Long seqid = null;

  static long creationTime = 0;

  static int CLIENT_ACK_INTERVAL = 5000;

  /** name of the test region */
  private static final String REGION_NAME =
      ReliableMessagingDUnitTest.class.getSimpleName() + "_Region";

  /*
   * Test verifies that client is sending periodic ack to the primary server for messages received.
   */
  @Test
  public void testPeriodicAckSendByClient() throws Exception {
    createEntries();
    server1.invoke(ReliableMessagingDUnitTest::putOnServer);
    waitForServerUpdate();
    setCreationTimeTidAndSeq();
    waitForClientAck();
    server1.invoke(ReliableMessagingDUnitTest::checkTidAndSeq);
  }

  /**
   * If the primary fails before receiving an ack from the messages it delivered then it should send
   * an ack to the new primary so that new primary can sends QRM to other redundant servers.
   */
  @Test
  public void testPeriodicAckSendByClientPrimaryFailover() throws Exception {
    IgnoredException.addIgnoredException("java.net.ConnectException");
    createEntries();
    setClientServerObserverForBeforeSendingClientAck();
    server1.invoke(ReliableMessagingDUnitTest::putOnServer);
    LogWriterUtils.getLogWriter().info("Entering waitForServerUpdate");
    waitForServerUpdate();
    LogWriterUtils.getLogWriter().info("Entering waitForCallback");
    waitForCallback();
    LogWriterUtils.getLogWriter().info("Entering waitForClientAck");
    waitForClientAck();
    server2.invoke(ReliableMessagingDUnitTest::checkTidAndSeq);
  }

  /**
   * Wait for acknowledgment from client, verify creation time is correct
   *
   */
  public static void waitForClientAck() throws Exception {
    final long maxWaitTime = 30000;
    final long start = System.currentTimeMillis();
    Iterator iter = pool.getThreadIdToSequenceIdMap().entrySet().iterator();
    SequenceIdAndExpirationObject seo = null;
    if (!iter.hasNext()) {
      fail("map is empty");
    }
    Map.Entry entry = (Map.Entry) iter.next();
    seo = (SequenceIdAndExpirationObject) entry.getValue();

    for (;;) {
      if (seo.getAckSend()) {
        break;
      }
      assertTrue("Waited over " + maxWaitTime + " for client ack ",
          +(System.currentTimeMillis() - start) < maxWaitTime);
      sleep(1000);
    }
    LogWriterUtils.getLogWriter().info("seo = " + seo);
    assertTrue(
        "Creation time " + creationTime + " supposed to be same as seo " + seo.getCreationTime(),
        creationTime == seo.getCreationTime());
  }

  public static void setCreationTimeTidAndSeq() {
    final Map map = pool.getThreadIdToSequenceIdMap();
    WaitCriterion ev = new WaitCriterion() {
      @Override
      public boolean done() {
        synchronized (map) {
          return map.entrySet().size() > 0;
        }
      }

      @Override
      public String description() {
        return null;
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
    Entry entry;
    synchronized (map) {
      Iterator iter = map.entrySet().iterator();
      entry = (Entry) iter.next();
    }

    SequenceIdAndExpirationObject seo = (SequenceIdAndExpirationObject) entry.getValue();
    assertFalse(seo.getAckSend());
    creationTime = seo.getCreationTime();
    getLogWriter().info("seo is " + seo);
    assertTrue("Creation time not set", creationTime != 0);

    Object[] args = new Object[] {((ThreadIdentifier) entry.getKey()).getMembershipID(),
        ((ThreadIdentifier) entry.getKey()).getThreadID(), seo.getSequenceId()};
    server1.invoke(ReliableMessagingDUnitTest.class, "setTidAndSeq", args);
    server2.invoke(ReliableMessagingDUnitTest.class, "setTidAndSeq", args);
  }

  public static void checkEmptyDispatchedMsgs() {
    assertEquals(0, HARegionQueue.getDispatchedMessagesMapForTesting().size());
  }

  public static void checkTidAndSeq() {
    Map map = HARegionQueue.getDispatchedMessagesMapForTesting();
    assertTrue(map.size() > 0);
    Iterator iter = map.entrySet().iterator();
    if (!iter.hasNext()) {
      fail("Dispatched messages is empty");
    }
    Map.Entry entry = (Map.Entry) iter.next();
    Map dispMap = HAHelper.getDispatchMessageMap(entry.getValue());
    assertEquals(seqid, dispMap.get(tid));
  }

  public static void setTidAndSeq(byte[] membershipId, Long threadId, Long sequenceId) {
    tid = new ThreadIdentifier(membershipId, threadId);
    seqid = sequenceId;
  }

  public static void createEntries() throws Exception {
    creationTime = 0;
    Region r1 = cache.getRegion(SEPARATOR + REGION_NAME);
    String keyPrefix = "server-";
    for (int i = 0; i < 5; i++) {
      r1.create(keyPrefix + i, "val");
    }
  }

  public static void putOnServer() throws Exception {
    Region r1 = cache.getRegion(SEPARATOR + REGION_NAME);
    String keyPrefix = "server-";
    for (int i = 0; i < 5; i++) {
      r1.put(keyPrefix + i, "val-" + i);
    }
  }

  private static void sleep(int ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Assert.fail("Interrupted", e);
    }
  }

  public static void checkServerCount(int expectedDeadServers, int expectedLiveServers) {
    final long maxWaitTime = 60000;
    long start = System.currentTimeMillis();
    for (;;) {
      if (pool.getConnectedServerCount() == expectedLiveServers) {
        break; // met
      }
      assertTrue(
          "Waited over " + maxWaitTime + "for active servers to become :" + expectedLiveServers,
          (System.currentTimeMillis() - start) < maxWaitTime);
      sleep(2000);
    }
  }

  public static void stopServer() {
    try {
      Iterator iter = cache.getCacheServers().iterator();
      if (iter.hasNext()) {
        CacheServer server = (CacheServer) iter.next();
        server.stop();
      }
    } catch (Exception e) {
      Assert.fail("failed while stopServer()", e);
    }
  }

  /**
   * Wait for new value on cache server to become visible in this cache
   */
  public static void waitForServerUpdate() {
    Region r1 = cache.getRegion(SEPARATOR + REGION_NAME);
    assertNotNull(r1);
    final long maxWaitTime = 60000;
    final long start = System.currentTimeMillis();
    for (;;) {
      if (r1.getEntry("server-4").getValue().equals("val-4")) {
        break;
      }
      assertTrue("Waited over " + maxWaitTime + " ms for entry to be refreshed",
          (System.currentTimeMillis() - start) < maxWaitTime);
      sleep(1000);
    }
  }

  public static void setClientServerObserverForBeforeSendingClientAck() throws Exception {
    PoolImpl.BEFORE_SENDING_CLIENT_ACK_CALLBACK_FLAG = true;
    origObserver = ClientServerObserverHolder.setInstance(new ClientServerObserverAdapter() {
      @Override
      public void beforeSendingClientAck() {
        LogWriterUtils.getLogWriter().info("beforeSendingClientAck invoked");
        setCreationTimeTidAndSeq();
        server1.invoke(ReliableMessagingDUnitTest::stopServer);
        checkServerCount(1, 1);
        server2.invoke(ReliableMessagingDUnitTest::checkEmptyDispatchedMsgs);
        PoolImpl.BEFORE_SENDING_CLIENT_ACK_CALLBACK_FLAG = false;
        LogWriterUtils.getLogWriter().info("end of beforeSendingClientAck");
      }
    });
  }

  /**
   * Wait for magic callback
   */
  public static void waitForCallback() {
    final long maxWaitTime = 60000;
    final long start = System.currentTimeMillis();
    for (;;) {
      if (!PoolImpl.BEFORE_SENDING_CLIENT_ACK_CALLBACK_FLAG) {
        break;
      }
      assertTrue("Waited over " + maxWaitTime + "to send an ack from client : ",
          (System.currentTimeMillis() - start) < maxWaitTime);
      sleep(2000);
    }
  }

  @Override
  public final void postSetUp() throws Exception {
    final Host host = Host.getHost(0);
    server1 = host.getVM(0);
    server2 = host.getVM(1);

    PORT1 =
        server1.invoke(ReliableMessagingDUnitTest::createServerCache);
    PORT2 =
        server2.invoke(ReliableMessagingDUnitTest::createServerCache);

    CacheServerTestUtil.disableShufflingOfEndpoints();
    createClientCache(PORT1, PORT2);
  }

  private Cache createCache(Properties props) throws Exception {
    DistributedSystem ds = getSystem(props);
    ds.disconnect();
    ds = getSystem(props);
    Cache result = null;
    result = CacheFactory.create(ds);
    if (result == null) {
      throw new Exception("CacheFactory.create() returned null ");
    }
    return result;
  }

  public static Integer createServerCache() throws Exception {
    ReliableMessagingDUnitTest test = new ReliableMessagingDUnitTest();
    Properties props = new Properties();
    cache = test.createCache(props);
    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setDataPolicy(DataPolicy.REPLICATE);

    RegionAttributes attrs = factory.create();

    cache.setMessageSyncInterval(25);
    cache.createRegion(REGION_NAME, attrs);

    CacheServer server = cache.addCacheServer();
    int port = getRandomAvailableTCPPort();
    server.setPort(port);
    server.setNotifyBySubscription(true);
    server.start();
    LogWriterUtils.getLogWriter().info("Server started at PORT = " + port);

    return server.getPort();
  }

  public static void createClientCache(int port1, int port2) throws Exception {
    ReliableMessagingDUnitTest test = new ReliableMessagingDUnitTest();
    Properties props = new Properties();
    props.setProperty(MCAST_PORT, "0");
    props.setProperty(LOCATORS, "");
    cache = test.createCache(props);
    String host = NetworkUtils.getServerHostName(Host.getHost(0));
    PoolImpl p = (PoolImpl) PoolManager.createFactory().addServer(host, PORT1)
        .addServer(host, PORT2).setSubscriptionEnabled(true).setSubscriptionRedundancy(1)
        .setMinConnections(6).setReadTimeout(20000)
        .setPingInterval(10000).setRetryAttempts(5).setSubscriptionAckInterval(CLIENT_ACK_INTERVAL)
        .create("ReliableMessagingDUnitTestPool");


    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setPoolName(p.getName());

    RegionAttributes attrs = factory.create();
    Region region = cache.createRegion(REGION_NAME, attrs);
    region.registerInterest("ALL_KEYS");

    pool = p;
  }

  @Override
  public final void preTearDown() throws Exception {
    creationTime = 0;
    closeCache();
    server1.invoke(ReliableMessagingDUnitTest::closeCache);
    server2.invoke(ReliableMessagingDUnitTest::closeCache);
    CacheServerTestUtil.resetDisableShufflingOfEndpointsFlag();
  }

  public static void closeCache() {
    if (cache != null && !cache.isClosed()) {
      cache.close();
      cache.getDistributedSystem().disconnect();
    }
  }

  private static ClientServerObserver origObserver;

  public static void resetCallBack() {
    ClientServerObserverHolder.setInstance(origObserver);
  }

}
