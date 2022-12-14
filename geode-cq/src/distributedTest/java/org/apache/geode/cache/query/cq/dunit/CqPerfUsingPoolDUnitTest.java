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
package org.apache.geode.cache.query.cq.dunit;

import static org.apache.geode.test.awaitility.GeodeAwaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.query.CqAttributes;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqListener;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.query.QueryService;
import org.apache.geode.cache.query.cq.internal.CqServiceImpl;
import org.apache.geode.cache.query.cq.internal.ServerCQImpl;
import org.apache.geode.cache.query.internal.DefaultQueryService;
import org.apache.geode.cache.query.internal.cq.CqService;
import org.apache.geode.cache.query.internal.cq.InternalCqQuery;
import org.apache.geode.cache30.CacheSerializableRunnable;
import org.apache.geode.internal.AvailablePortHelper;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.Invoke;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.NetworkUtils;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.Wait;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.junit.categories.ClientSubscriptionTest;

/**
 * This class tests the ContiunousQuery mechanism in GemFire. This includes the test with diffetent
 * data activities.
 */
@Category({ClientSubscriptionTest.class})
public class CqPerfUsingPoolDUnitTest extends JUnit4CacheTestCase {

  protected CqQueryUsingPoolDUnitTest cqDUnitTest = new CqQueryUsingPoolDUnitTest(); // TODO: get
                                                                                     // rid of this!

  public CqPerfUsingPoolDUnitTest() {
    super();
  }

  @Override
  public final void postSetUp() throws Exception {
    // avoid IllegalStateException from HandShake by connecting all vms tor
    // system before creating connection pools
    getSystem();
    Invoke.invokeInEveryVM(new SerializableRunnable("getSystem") {
      @Override
      public void run() {
        getSystem();
      }
    });
  }

  /**
   * Tests the cq performance.
   *
   */
  @Ignore("perf")
  @Test
  public void testCQPerf() throws Exception {

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    cqDUnitTest.createServer(server);

    final int port = server.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());

    // Create client.
    cqDUnitTest.createClient(client, port, host0);
    final String cqName = "testCQPerf_0";

    client.invoke(new CacheSerializableRunnable("Create CQ :" + cqName) {
      @Override
      public void run2() throws CacheException {
        LogWriterUtils.getLogWriter().info("### Create CQ. ###" + cqName);
        // Get CQ Service.
        QueryService cqService = null;
        try {
          cqService = getCache().getQueryService();
        } catch (Exception cqe) {
          cqe.printStackTrace();
          fail("Failed to getCQService.");
        }
        // Create CQ Attributes.
        CqAttributesFactory cqf = new CqAttributesFactory();
        CqListener[] cqListeners = {new CqTimeTestListener(LogWriterUtils.getLogWriter())};
        ((CqTimeTestListener) cqListeners[0]).cqName = cqName;

        cqf.initCqListeners(cqListeners);
        CqAttributes cqa = cqf.create();

        // Create and Execute CQ.
        try {
          CqQuery cq1 = cqService.newCq(cqName, cqDUnitTest.cqs[0], cqa);
          assertTrue("newCq() state mismatch", cq1.getState().isStopped());
          cq1.execute();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("CqService is :" + cqService);
          ex.printStackTrace();
          AssertionError err = new AssertionError("Failed to create CQ " + cqName + " . ", ex);
          throw err;
        }
      }
    });

    final int size = 50;

    // Create values.
    cqDUnitTest.createValuesWithTime(client, cqDUnitTest.regions[0], size);
    Wait.pause(5000);

    // Update values
    cqDUnitTest.createValuesWithTime(client, cqDUnitTest.regions[0], size);

    client.invoke(new CacheSerializableRunnable("Validate CQs") {
      @Override
      public void run2() throws CacheException {
        LogWriterUtils.getLogWriter().info("### Validating CQ. ### " + cqName);
        // Get CQ Service.
        QueryService cqService = null;
        try {
          cqService = getCache().getQueryService();
        } catch (Exception cqe) {
          cqe.printStackTrace();
          fail("Failed to getCqService.");
        }

        CqQuery cQuery = cqService.getCq(cqName);
        if (cQuery == null) {
          fail("Failed to get CqQuery for CQ : " + cqName);
        }

        // CqAttributes cqAttr = cQuery.getCqAttributes();
        // CqListener cqListeners[] = cqAttr.getCqListeners();
        // CqTimeTestListener listener = (CqTimeTestListener) cqListeners[0];

        // Wait for all the create to arrive.
        // for (int i=1; i <= size; i++) {
        // listener.waitForCreated(cqDUnitTest.KEY+i);
        // }

        // Wait for all the update to arrive.
        // for (int i=1; i <= size; i++) {
        // listener.waitForUpdated(cqDUnitTest.KEY+i);
        // }
        // getLogWriter().info("### Time taken for Creation of " + size + " events is :" +
        // listener.getTotalQueryCreateTime());

        // getLogWriter().info("### Time taken for Update of " + size + " events is :" +
        // listener.getTotalQueryUpdateTime());
      }
    });

    Wait.pause(10 * 60 * 1000);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);

  }

  /**
   * Test for maintaining keys for update optimization.
   *
   */
  @Test
  public void testKeyMaintenance() throws Exception {

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    cqDUnitTest.createServer(server);
    final int port = server.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());
    // cqDUnitTest.createClient(client, port, host0);

    String poolName = "testKeyMaintainance";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // HashSet for caching purpose will be created for cqs.
    final int cqSize = 2;

    // Cq1
    cqDUnitTest.createCQ(client, poolName, "testKeyMaintainance_0", cqDUnitTest.cqs[0]);
    cqDUnitTest.executeCQ(client, "testKeyMaintainance_0", false, null);

    // Cq2
    cqDUnitTest.createCQ(client, poolName, "testKeyMaintainance_1", cqDUnitTest.cqs[10]);
    cqDUnitTest.executeCQ(client, "testKeyMaintainance_1", false, null);

    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], 1);
    cqDUnitTest.waitForCreated(client, "testKeyMaintainance_0", CqQueryUsingPoolDUnitTest.KEY + 1);

    // Entry is made into the CQs cache hashset.
    // testKeyMaintainance_0 with 1 entry and testKeyMaintainance_1 with 0
    server.invoke(new CacheSerializableRunnable("LookForCachedEventKeys1") {
      @Override
      public void run2() throws CacheException {
        CqService cqService = null;
        try {
          cqService = ((DefaultQueryService) getCache().getQueryService()).getCqService();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("Failed to get the internal CqService.", ex);
          Assert.fail("Failed to get the internal CqService.", ex);
        }

        Collection<? extends InternalCqQuery> cqs = cqService.getAllCqs();
        for (InternalCqQuery cq : cqs) {
          ServerCQImpl cqQuery = (ServerCQImpl) cq;

          String serverCqName = cqQuery.getServerCqName();
          if (serverCqName.startsWith("testKeyMaintainance_0")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_0 is wrong.", 1,
                cqQuery.getCqResultKeysSize());
          } else if (serverCqName.startsWith("testKeyMaintainance_1")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_1 is wrong.", 0,
                cqQuery.getCqResultKeysSize());
          }
        }
      }
    });

    // Update 1.
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], 10);
    cqDUnitTest.waitForCreated(client, "testKeyMaintainance_0", CqQueryDUnitTest.KEY + 10);

    // Entry/check is made into the CQs cache hashset.
    // testKeyMaintainance_0 with 1 entry and testKeyMaintainance_1 with 1
    server.invoke(new CacheSerializableRunnable("LookForCachedEventKeysAfterUpdate1") {
      @Override
      public void run2() throws CacheException {
        CqService cqService = null;
        try {
          cqService = ((DefaultQueryService) getCache().getQueryService()).getCqService();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("Failed to get the internal CqService.", ex);
          Assert.fail("Failed to get the internal CqService.", ex);
        }
        Collection<? extends InternalCqQuery> cqs = cqService.getAllCqs();
        for (InternalCqQuery cq : cqs) {
          ServerCQImpl cqQuery = (ServerCQImpl) cq;

          String serverCqName = cqQuery.getServerCqName();
          if (serverCqName.startsWith("testKeyMaintainance_0")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_0 is wrong.", 10,
                cqQuery.getCqResultKeysSize());
          } else if (serverCqName.startsWith("testKeyMaintainance_1")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_1 is wrong.", 5,
                cqQuery.getCqResultKeysSize());
          }
        }
      }
    });

    // Update.
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], 12);
    cqDUnitTest.waitForCreated(client, "testKeyMaintainance_0", CqQueryDUnitTest.KEY + 12);

    // Entry/check is made into the CQs cache hashset.
    // testKeyMaintainance_0 with 1 entry and testKeyMaintainance_1 with 1
    server.invoke(new CacheSerializableRunnable("LookForCachedEventKeysAfterUpdate2") {
      @Override
      public void run2() throws CacheException {
        CqService cqService = null;
        try {
          cqService = ((DefaultQueryService) getCache().getQueryService()).getCqService();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("Failed to get the internal CqService.", ex);
          Assert.fail("Failed to get the internal CqService.", ex);
        }

        Collection<? extends InternalCqQuery> cqs = cqService.getAllCqs();
        for (InternalCqQuery cq : cqs) {
          ServerCQImpl cqQuery = (ServerCQImpl) cq;

          String serverCqName = cqQuery.getServerCqName();
          if (serverCqName.startsWith("testKeyMaintainance_0")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_0 is wrong.", 12,
                cqQuery.getCqResultKeysSize());
          } else if (serverCqName.startsWith("testKeyMaintainance_1")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_1 is wrong.", 6,
                cqQuery.getCqResultKeysSize());
          }
        }

      }
    });

    // Delete.
    cqDUnitTest.deleteValues(server, cqDUnitTest.regions[0], 6);
    cqDUnitTest.waitForDestroyed(client, "testKeyMaintainance_0", CqQueryDUnitTest.KEY + 6);

    // Entry/check is made into the CQs cache hashset.
    // testKeyMaintainance_0 with 1 entry and testKeyMaintainance_1 with 1
    server.invoke(new CacheSerializableRunnable("LookForCachedEventKeysAfterUpdate2") {
      @Override
      public void run2() throws CacheException {
        CqService cqService = null;
        try {
          cqService = ((DefaultQueryService) getCache().getQueryService()).getCqService();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("Failed to get the internal CqService.", ex);
          Assert.fail("Failed to get the internal CqService.", ex);
        }
        Collection<? extends InternalCqQuery> cqs = cqService.getAllCqs();
        for (InternalCqQuery cq : cqs) {
          ServerCQImpl cqQuery = (ServerCQImpl) cq;

          String serverCqName = cqQuery.getServerCqName();
          if (serverCqName.startsWith("testKeyMaintainance_0")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_0 is wrong.", 6,
                cqQuery.getCqResultKeysSize());
          } else if (serverCqName.startsWith("testKeyMaintainance_1")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_1 is wrong.", 3,
                cqQuery.getCqResultKeysSize());
          }
        }
      }
    });

    // This should still needs to process the events so that Results are uptodate.
    cqDUnitTest.stopCQ(client, "testKeyMaintainance_1");
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], 12);

    server.invoke(new CacheSerializableRunnable("LookForCachedEventKeysAfterUpdate2") {
      @Override
      public void run2() throws CacheException {
        CqService cqService = null;
        try {
          cqService = ((DefaultQueryService) getCache().getQueryService()).getCqService();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("Failed to get the internal CqService.", ex);
          Assert.fail("Failed to get the internal CqService.", ex);
        }

        Collection<? extends InternalCqQuery> cqs = cqService.getAllCqs();
        for (InternalCqQuery cq : cqs) {
          ServerCQImpl cqQuery = (ServerCQImpl) cq;
          String serverCqName = cqQuery.getServerCqName();
          if (serverCqName.startsWith("testKeyMaintainance_0")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_0 is wrong.", 12,
                cqQuery.getCqResultKeysSize());
          } else if (serverCqName.startsWith("testKeyMaintainance_1")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_1 is wrong.", 6,
                cqQuery.getCqResultKeysSize());
          }
        }

      }
    });

    // This should re-start the caching for this CQ.
    cqDUnitTest.executeCQ(client, "testKeyMaintainance_1", false, null);

    // This will remove the caching for this CQ.
    cqDUnitTest.closeCQ(client, "testKeyMaintainance_1");
    server.invoke(new CacheSerializableRunnable("LookForCachedEventKeysAfterUpdate2") {
      @Override
      public void run2() throws CacheException {
        CqService cqService = null;
        try {
          cqService = ((DefaultQueryService) getCache().getQueryService()).getCqService();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("Failed to get the internal CqService.", ex);
          Assert.fail("Failed to get the internal CqService.", ex);
        }
        Collection<? extends InternalCqQuery> cqs = cqService.getAllCqs();
        for (InternalCqQuery cq : cqs) {
          ServerCQImpl cqQuery = (ServerCQImpl) cq;
          String serverCqName = cqQuery.getServerCqName();
          if (serverCqName.startsWith("testKeyMaintainance_0")) {
            assertEquals("The number of keys cached for cq testKeyMaintainance_0 is wrong.", 12,
                cqQuery.getCqResultKeysSize());
          } else if (serverCqName.startsWith("testKeyMaintainance_1")) {
            fail("The key maintainance should not be present for this CQ.");
          }
        }

      }
    });

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }

  /**
   * Test for common CQs. To test the changes relating to, executing CQ only once for all similar
   * CQs.
   *
   */
  @Test
  public void testMatchingCqs() throws Exception {

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client = host.getVM(1);

    cqDUnitTest.createServer(server);
    final int port = server.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort); // TODO:
                                                                                   // move
                                                                                   // static
                                                                                   // methods
                                                                                   // from
                                                                                   // other
                                                                                   // dunit
                                                                                   // into
                                                                                   // util
                                                                                   // class
    final String host0 = NetworkUtils.getServerHostName(server.getHost());
    // cqDUnitTest.createClient(client, port, host0);

    String poolName = "testMatchingCqs";
    cqDUnitTest.createPool(client, poolName, host0, port);

    // Create and Execute same kind of CQs.
    for (int i = 0; i < 4; i++) {
      cqDUnitTest.createCQ(client, poolName, "testMatchingCqs_" + i, cqDUnitTest.cqs[0]);
      cqDUnitTest.executeCQ(client, "testMatchingCqs_" + i, false, null);
    }

    validateMatchingCqs(server, 1, cqDUnitTest.cqs[0], 4);

    int size = 1;

    // Create.
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);
    cqDUnitTest.waitForCreated(client, "testMatchingCqs_0", CqQueryUsingPoolDUnitTest.KEY + size);

    // Close one of the CQ.
    cqDUnitTest.closeCQ(client, "testMatchingCqs_0");
    validateMatchingCqs(server, 1, cqDUnitTest.cqs[0], 3);

    // Update.
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);
    cqDUnitTest.waitForUpdated(client, "testMatchingCqs_3", CqQueryUsingPoolDUnitTest.KEY + size);
    cqDUnitTest.waitForUpdated(client, "testMatchingCqs_1", CqQueryUsingPoolDUnitTest.KEY + size);

    // Stop one of the CQ.
    cqDUnitTest.stopCQ(client, "testMatchingCqs_1");

    validateMatchingCqs(server, 1, cqDUnitTest.cqs[0], 2);

    // Update - 2.
    cqDUnitTest.clearCQListenerEvents(client, "testMatchingCqs_3");
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);
    cqDUnitTest.waitForUpdated(client, "testMatchingCqs_3", CqQueryUsingPoolDUnitTest.KEY + size);

    // stopped CQ should not receive 2nd/previous updates.
    cqDUnitTest.validateCQ(client, "testMatchingCqs_1",
        /* resultSize: */ CqQueryUsingPoolDUnitTest.noTest, /* creates: */ size,
        /* updates: once */ size, /* deletes; */ 0, /* queryInserts: */ size,
        /* queryUpdates: */ size, /* queryDeletes: */ 0, /* totalEvents: */ size * 2);

    // Execute the stopped CQ.
    cqDUnitTest.executeCQ(client, "testMatchingCqs_1", false, null);


    // Update - 3.
    cqDUnitTest.clearCQListenerEvents(client, "testMatchingCqs_3");
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);
    cqDUnitTest.waitForUpdated(client, "testMatchingCqs_3", CqQueryUsingPoolDUnitTest.KEY + size);

    cqDUnitTest.validateCQ(client, "testMatchingCqs_1",
        /* resultSize: */ CqQueryUsingPoolDUnitTest.noTest, /* creates: */ size,
        /* updates: 2 */ size * 2, /* deletes; */ 0, /* queryInserts: */ size,
        /* queryUpdates: */ size * 2, /* queryDeletes: */ 0, /* totalEvents: */ size * 3);

    // Create different kind of CQs.
    cqDUnitTest.createCQ(client, poolName, "testMatchingCqs_4", cqDUnitTest.cqs[1]);
    cqDUnitTest.executeCQ(client, "testMatchingCqs_4", false, null);

    cqDUnitTest.createCQ(client, poolName, "testMatchingCqs_5", cqDUnitTest.cqs[1]);
    cqDUnitTest.executeCQ(client, "testMatchingCqs_5", false, null);

    cqDUnitTest.createCQ(client, poolName, "testMatchingCqs_6", cqDUnitTest.cqs[2]);
    cqDUnitTest.executeCQ(client, "testMatchingCqs_6", false, null);

    validateMatchingCqs(server, 3, cqDUnitTest.cqs[1], 2);

    cqDUnitTest.closeCQ(client, "testMatchingCqs_6");
    validateMatchingCqs(server, 2, cqDUnitTest.cqs[1], 2);

    cqDUnitTest.closeCQ(client, "testMatchingCqs_5");
    cqDUnitTest.closeCQ(client, "testMatchingCqs_4");
    cqDUnitTest.closeCQ(client, "testMatchingCqs_3");
    cqDUnitTest.closeCQ(client, "testMatchingCqs_2");
    cqDUnitTest.closeCQ(client, "testMatchingCqs_1");

    validateMatchingCqs(server, 0, null, 0);

    // update 4
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);

    // Close.
    cqDUnitTest.closeClient(client);
    cqDUnitTest.closeServer(server);
  }


  /**
   * Test for common CQs. To test the changes relating to, executing CQ only once for all similar
   * CQs.
   *
   */
  @Test
  public void testMatchingCQWithMultipleClients() throws Exception {

    final Host host = Host.getHost(0);
    VM server = host.getVM(0);
    VM client1 = host.getVM(1);
    VM client2 = host.getVM(2);
    VM client3 = host.getVM(3);

    VM[] clients = new VM[] {client1, client2, client3};

    cqDUnitTest.createServer(server);
    final int port = server.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server.getHost());
    String poolName = "testMatchingCQWithMultipleClients";
    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      String cPoolName = "testMatchingCQWithMultipleClients" + clientIndex;
      cqDUnitTest.createPool(clients[clientIndex], cPoolName, host0, port);

      // cqDUnitTest.createClient(clients[clientIndex], port, host0);
      // Create and Execute same kind of CQs.
      for (int i = 0; i < 4; i++) {
        cqDUnitTest.createCQ(clients[clientIndex], cPoolName,
            "testMatchingCQWithMultipleClients_" + i, cqDUnitTest.cqs[0]);
        cqDUnitTest.executeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_" + i, false,
            null);
      }
    }

    validateMatchingCqs(server, 1, cqDUnitTest.cqs[0], clients.length * 4);

    int size = 1;

    // Create.
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);
    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      cqDUnitTest.waitForCreated(clients[clientIndex], "testMatchingCQWithMultipleClients_0",
          CqQueryUsingPoolDUnitTest.KEY + size);
      cqDUnitTest.waitForCreated(clients[clientIndex], "testMatchingCQWithMultipleClients_3",
          CqQueryUsingPoolDUnitTest.KEY + size);
    }

    // Close one of the CQ.
    cqDUnitTest.closeCQ(client1, "testMatchingCQWithMultipleClients_0");
    validateMatchingCqs(server, 1, cqDUnitTest.cqs[0], (clients.length * 4) - 1);

    // Update.
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);

    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      cqDUnitTest.waitForUpdated(clients[clientIndex], "testMatchingCQWithMultipleClients_3",
          CqQueryUsingPoolDUnitTest.KEY + size);
    }

    // Stop one of the CQ.
    cqDUnitTest.stopCQ(client2, "testMatchingCQWithMultipleClients_1");

    validateMatchingCqs(server, 1, cqDUnitTest.cqs[0], (clients.length * 4) - 2);

    // Update - 2.
    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      cqDUnitTest.clearCQListenerEvents(clients[clientIndex],
          "testMatchingCQWithMultipleClients_3");
    }

    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);

    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      cqDUnitTest.waitForUpdated(clients[clientIndex], "testMatchingCQWithMultipleClients_3",
          CqQueryUsingPoolDUnitTest.KEY + size);
    }

    // stopped CQ should not receive 2nd/previous updates.
    cqDUnitTest.validateCQ(client2, "testMatchingCQWithMultipleClients_1",
        /* resultSize: */ CqQueryUsingPoolDUnitTest.noTest, /* creates: */ size,
        /* updates: once */ size, /* deletes; */ 0, /* queryInserts: */ size,
        /* queryUpdates: */ size, /* queryDeletes: */ 0, /* totalEvents: */ size * 2);

    // Execute the stopped CQ.
    cqDUnitTest.executeCQ(client2, "testMatchingCQWithMultipleClients_1", false, null);


    // Update - 3.
    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      cqDUnitTest.clearCQListenerEvents(clients[clientIndex],
          "testMatchingCQWithMultipleClients_3");
    }

    validateMatchingCqs(server, 1, cqDUnitTest.cqs[0], (clients.length * 4) - 1);

    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);

    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      cqDUnitTest.waitForUpdated(clients[clientIndex], "testMatchingCQWithMultipleClients_3",
          CqQueryUsingPoolDUnitTest.KEY + size);
    }

    cqDUnitTest.validateCQ(client2, "testMatchingCQWithMultipleClients_1",
        /* resultSize: */ CqQueryUsingPoolDUnitTest.noTest, /* creates: */ size,
        /* updates: 2 */ size * 2, /* deletes; */ 0, /* queryInserts: */ size,
        /* queryUpdates: */ size * 2, /* queryDeletes: */ 0, /* totalEvents: */ size * 3);

    // Create different kind of CQs.
    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      String cPoolName = poolName + clientIndex;
      cqDUnitTest.createCQ(clients[clientIndex], cPoolName, "testMatchingCQWithMultipleClients_4",
          cqDUnitTest.cqs[1]);
      cqDUnitTest.executeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_4", false,
          null);

      cqDUnitTest.createCQ(clients[clientIndex], cPoolName, "testMatchingCQWithMultipleClients_5",
          cqDUnitTest.cqs[1]);
      cqDUnitTest.executeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_5", false,
          null);

      cqDUnitTest.createCQ(clients[clientIndex], cPoolName, "testMatchingCQWithMultipleClients_6",
          cqDUnitTest.cqs[2]);
      cqDUnitTest.executeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_6", false,
          null);
    }

    validateMatchingCqs(server, 3, cqDUnitTest.cqs[1], 2 * clients.length);

    for (int clientIndex = 0; clientIndex < 3; clientIndex++) {
      cqDUnitTest.closeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_6");
    }

    validateMatchingCqs(server, 2, cqDUnitTest.cqs[1], 2 * clients.length);


    // update 4
    cqDUnitTest.createValues(server, cqDUnitTest.regions[0], size);

    // Close.
    cqDUnitTest.closeClient(client3);
    validateMatchingCqs(server, 2, cqDUnitTest.cqs[1], 2 * (clients.length - 1));

    for (int clientIndex = 0; clientIndex < 2; clientIndex++) {
      cqDUnitTest.closeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_5");
      cqDUnitTest.closeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_4");
      cqDUnitTest.closeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_3");
      cqDUnitTest.closeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_2");
      cqDUnitTest.closeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_1");
      if (clientIndex != 0) {
        cqDUnitTest.closeCQ(clients[clientIndex], "testMatchingCQWithMultipleClients_0");
      }
    }

    validateMatchingCqs(server, 0, null, 0);

    cqDUnitTest.closeClient(client2);
    cqDUnitTest.closeClient(client1);

    cqDUnitTest.closeServer(server);
  }

  /**
   * Test for CQ Fail over.
   *
   */
  @Test
  public void testMatchingCQsWithMultipleServers() throws Exception {
    final Host host = Host.getHost(0);
    VM server1 = host.getVM(0);
    VM server2 = host.getVM(1);
    VM client1 = host.getVM(2);
    VM client2 = host.getVM(3);

    cqDUnitTest.createServer(server1);

    VM[] clients = new VM[] {client1, client2};

    final int port1 = server1.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server1.getHost());
    // Create client.

    // Create client with redundancyLevel -1

    final int[] ports = AvailablePortHelper.getRandomAvailableTCPPorts(1);
    String poolName1 = "testClientWithFeederAndCQ1";
    String poolName2 = "testClientWithFeederAndCQ2";

    cqDUnitTest.createPool(client1, poolName1, new String[] {host0, host0},
        new int[] {port1, ports[0]});
    cqDUnitTest.createPool(client2, poolName2, new String[] {host0, host0},
        new int[] {port1, ports[0]});

    int numCQs = 3;

    for (int i = 0; i < numCQs; i++) {
      cqDUnitTest.createCQ(client1, poolName1, "testMatchingCQsWithMultipleServers_" + i,
          cqDUnitTest.cqs[i]);
      cqDUnitTest.executeCQ(client1, "testMatchingCQsWithMultipleServers_" + i, false, null);

      cqDUnitTest.createCQ(client2, poolName2, "testMatchingCQsWithMultipleServers_" + i,
          cqDUnitTest.cqs[i]);
      cqDUnitTest.executeCQ(client2, "testMatchingCQsWithMultipleServers_" + i, false, null);
    }

    validateMatchingCqs(server1, numCQs, cqDUnitTest.cqs[0], clients.length);
    validateMatchingCqs(server1, numCQs, cqDUnitTest.cqs[1], clients.length);

    cqDUnitTest.createServer(server2, ports[0]);


    final int port2 = server2.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort);
    System.out
        .println("### Port on which server1 running : " + port1 + " Server2 running : " + port2);


    // Close server1.
    cqDUnitTest.closeServer(server1);
    // Close.
    cqDUnitTest.closeClient(client1);

    validateMatchingCqs(server2, numCQs, cqDUnitTest.cqs[0], (clients.length - 1));

    cqDUnitTest.closeClient(client2);
    cqDUnitTest.closeServer(server2);
  }

  @Test
  public void testFailOverForMatchingCQsWithMultipleServers() throws Exception {
    final Host host = Host.getHost(0);
    VM server1 = host.getVM(0);
    VM server2 = host.getVM(1);
    VM client1 = host.getVM(2);
    VM client2 = host.getVM(3);
    VM[] clients = new VM[] {client1, client2};


    cqDUnitTest.createServer(server1);
    final String host0 = NetworkUtils.getServerHostName(server1.getHost());
    final int port1 = server1.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort);
    final int[] ports = AvailablePortHelper.getRandomAvailableTCPPorts(1);

    // Create client with redundancyLevel -1
    String poolName1 = "testClientWithFeederAndCQ1";
    String poolName2 = "testClientWithFeederAndCQ2";

    cqDUnitTest.createPool(client1, poolName1, new String[] {host0, host0},
        new int[] {port1, ports[0]});
    cqDUnitTest.createPool(client2, poolName2, new String[] {host0, host0},
        new int[] {port1, ports[0]});


    int numCQs = 3;
    for (int i = 0; i < numCQs; i++) {
      cqDUnitTest.createCQ(client1, poolName1, "testMatchingCQsWithMultipleServers_" + i,
          cqDUnitTest.cqs[i]);
      cqDUnitTest.executeCQ(client1, "testMatchingCQsWithMultipleServers_" + i, false, null);

      cqDUnitTest.createCQ(client2, poolName2, "testMatchingCQsWithMultipleServers_" + i,
          cqDUnitTest.cqs[i]);
      cqDUnitTest.executeCQ(client2, "testMatchingCQsWithMultipleServers_" + i, false, null);
    }

    cqDUnitTest.createServer(server2, ports[0]);

    // Close server1.
    cqDUnitTest.closeServer(server1);

    // Fail over should happen.
    validateMatchingCqs(server2, numCQs, cqDUnitTest.cqs[0], clients.length);
    cqDUnitTest.closeClient(client1);

    // server2 cq's should still be in the matching map
    validateMatchingCqs(server2, numCQs, cqDUnitTest.cqs[0], 1 * (clients.length - 1));

    cqDUnitTest.closeClient(client2);
    cqDUnitTest.closeServer(server2);
  }

  /**
   * Performance test for Matching CQ optimization changes.
   *
   */
  @Ignore("perf")
  @Test
  public void testPerformanceForMatchingCQs() throws Exception {
    final Host host = Host.getHost(0);
    VM server1 = host.getVM(0);
    VM server2 = host.getVM(1);
    VM client1 = host.getVM(2);
    VM client2 = host.getVM(3);

    cqDUnitTest.createServer(server1);
    cqDUnitTest.createServer(server2);

    // VM clients[] = new VM[]{client1, client2};

    final int port1 = server1.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort);
    final int port2 = server2.invoke(CqQueryUsingPoolDUnitTest::getCacheServerPort);
    final String host0 = NetworkUtils.getServerHostName(server1.getHost());

    // Create client.
    // final int[] ports = AvailablePortHelper.getRandomAvailableTCPPorts(1);

    // Client1 connects to server1.
    cqDUnitTest.createClient(client1, new int[] {port1}, host0, "-1", null);

    // Client2 connects to server2.
    cqDUnitTest.createClient(client2, new int[] {port2}, host0, "-1", null);

    // Client1 registers matching CQs on server1.
    boolean uniqueQueries = false;
    String[] matchingCqs = generateCqQueries(uniqueQueries);
    for (int i = 0; i < matchingCqs.length; i++) {
      cqDUnitTest.createCQ(client1, "testPerformanceForMatchingCQs_" + i, matchingCqs[i]);
      cqDUnitTest.executeCQ(client1, "testPerformanceForMatchingCQs_" + i, false, null);
    }

    // Client2 registers non-matching CQs on server2.
    uniqueQueries = true;
    matchingCqs = generateCqQueries(uniqueQueries);
    for (int i = 0; i < matchingCqs.length; i++) {
      cqDUnitTest.createCQ(client2, "testPerformanceForMatchingCQs_" + i, matchingCqs[i]);
      cqDUnitTest.executeCQ(client2, "testPerformanceForMatchingCQs_" + i, false, null);
    }

    Wait.pause(1 * 1000);

    // CREATE.
    int size = 1000;
    cqDUnitTest.createValues(server1, cqDUnitTest.regions[0], size);
    cqDUnitTest.createValues(server2, cqDUnitTest.regions[1], size);

    // Update couple of times;
    for (int j = 0; j < 5; j++) {
      cqDUnitTest.createValues(server1, cqDUnitTest.regions[0], size - 1);
      cqDUnitTest.createValues(server2, cqDUnitTest.regions[1], size - 1);
    }

    for (int j = 0; j < 4; j++) {
      cqDUnitTest.createValues(server2, cqDUnitTest.regions[0], size - 1);
      cqDUnitTest.createValues(server1, cqDUnitTest.regions[1], size - 1);
    }

    // Update the last key.
    cqDUnitTest.createValues(server2, cqDUnitTest.regions[0], size);
    cqDUnitTest.createValues(server1, cqDUnitTest.regions[1], size);

    for (int k = 1; k <= size; k++) {
      cqDUnitTest.waitForUpdated(client1, "testPerformanceForMatchingCQs_0",
          CqQueryUsingPoolDUnitTest.KEY + k);
    }

    Wait.pause(1 * 1000);
    printCqQueryExecutionTime(server1);
    printCqQueryExecutionTime(server2);

    // Close.
    cqDUnitTest.closeClient(client1);
    cqDUnitTest.closeClient(client2);
    cqDUnitTest.closeServer(server2);
    cqDUnitTest.closeServer(server1);

  }

  public void validateMatchingCqs(VM server, final int mapSize, final String query,
      final int numCqSize) {
    server.invoke(new CacheSerializableRunnable("validateMatchingCqs") {
      @Override
      public void run2() throws CacheException {
        CqServiceImpl cqService = null;
        try {
          cqService =
              (CqServiceImpl) ((DefaultQueryService) getCache().getQueryService()).getCqService();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("Failed to get the internal CqService.", ex);
          Assert.fail("Failed to get the internal CqService.", ex);
        }

        Map matchedCqMap = cqService.getMatchingCqMap();
        await()
            .untilAsserted(
                () -> assertEquals("The number of matched cq is not as expected.", mapSize,
                    matchedCqMap.size()));

        if (query != null) {
          if (!matchedCqMap.containsKey(query)) {
            fail("Query not found in the matched cq map. Query:" + query);
          }
          Collection cqs = (Collection) matchedCqMap.get(query);
          await()
              .untilAsserted(() -> assertEquals(
                  "Number of matched cqs are not equal to the expected matched cqs", numCqSize,
                  cqs.size()));
        }
      }
    });
  }

  public void printCqQueryExecutionTime(VM server) {
    server.invoke(new CacheSerializableRunnable("printCqQueryExecutionTime") {
      @Override
      public void run2() throws CacheException {
        CqServiceImpl cqService = null;
        try {
          cqService =
              (CqServiceImpl) ((DefaultQueryService) getCache().getQueryService()).getCqService();
        } catch (Exception ex) {
          LogWriterUtils.getLogWriter().info("Failed to get the internal CqService.", ex);
          Assert.fail("Failed to get the internal CqService.", ex);
        }

        long timeTaken = cqService.getCqServiceVsdStats().getCqQueryExecutionTime();
        LogWriterUtils.getLogWriter().info("Total Time taken to Execute CQ Query :" + timeTaken);
        System.out.println("Total Time taken to Execute CQ Query :" + timeTaken);
      }
    });
  }

  public String[] generateCqQueries(boolean uniqueQueries) {
    ArrayList<String> initQueries = new ArrayList<>();
    // From Portfolio object.
    String[] names = {"aaa", "bbb", "ccc", "ddd"};
    int nameIndex = 0;

    // Construct few unique Queries.
    for (int i = 0; i < 3; i++) {
      for (int cnt = 0; cnt < 5; cnt++) {
        String query = cqDUnitTest.cqs[i];
        if (cnt > 0) {
          nameIndex = (cnt % names.length);
          query += " or p.names[" + nameIndex + "] = '" + names[nameIndex] + cnt + "'";
        }
        initQueries.add(query);
      }
    }

    int numMatchedQueries = 10;
    ArrayList<String> cqQueries = new ArrayList<>();
    Iterator iter = initQueries.iterator();
    while (iter.hasNext()) {
      String query = (String) iter.next();
      for (int cnt = 0; cnt < numMatchedQueries; cnt++) {
        if (uniqueQueries) {
          // Append blank string, so that query string is different but the
          // Query constraint remains same.
          query += " ";
        }
        cqQueries.add(query);
      }
    }
    String[] queries = new String[cqQueries.size()];
    cqQueries.toArray(queries);
    return queries;
  }
}
