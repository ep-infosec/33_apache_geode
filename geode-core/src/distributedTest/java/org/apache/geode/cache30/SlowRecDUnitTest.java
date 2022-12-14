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
package org.apache.geode.cache30;

import static java.lang.Runtime.getRuntime;
import static java.lang.System.currentTimeMillis;
import static org.apache.geode.cache.DataPolicy.REPLICATE;
import static org.apache.geode.cache.Scope.DISTRIBUTED_NO_ACK;
import static org.apache.geode.distributed.ConfigurationProperties.ASYNC_DISTRIBUTION_TIMEOUT;
import static org.apache.geode.distributed.ConfigurationProperties.ASYNC_MAX_QUEUE_SIZE;
import static org.apache.geode.distributed.ConfigurationProperties.ASYNC_QUEUE_TIMEOUT;
import static org.apache.geode.internal.tcp.Connection.FORCE_ASYNC_QUEUE;
import static org.apache.geode.test.dunit.LogWriterUtils.getLogWriter;
import static org.apache.geode.test.dunit.Wait.pause;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Properties;
import java.util.Set;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.CacheListener;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.Region.Entry;
import org.apache.geode.cache.RegionEvent;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.util.CacheListenerAdapter;
import org.apache.geode.distributed.internal.DMStats;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.ThreadUtils;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.Wait;
import org.apache.geode.test.dunit.WaitCriterion;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.junit.categories.MembershipTest;

/**
 * Test to make sure slow receiver queuing is working
 *
 * @since GemFire 4.2.1
 */
@Category({MembershipTest.class})
@Ignore("Test was disabled by renaming to DisabledTest")
public class SlowRecDUnitTest extends JUnit4CacheTestCase {

  protected static Object lastCallback = null;

  // this test has special config of its distributed system so
  // the setUp and tearDown methods need to make sure we don't
  // use the ds from previous test and that we don't leave ours around
  // for the next test to use.

  @Override
  public final void preSetUp() throws Exception {
    disconnectAllFromDS();
  }

  @Override
  public final void postTearDownCacheTestCase() throws Exception {
    disconnectAllFromDS();
  }

  private VM getOtherVm() {
    Host host = Host.getHost(0);
    return host.getVM(0);
  }

  private void doCreateOtherVm(final Properties p, final boolean addListener) {
    VM vm = getOtherVm();
    vm.invoke(new CacheSerializableRunnable("create root") {
      @Override
      public void run2() throws CacheException {
        getSystem(p);
        createAckRegion(true, false);
        AttributesFactory af = new AttributesFactory();
        af.setScope(Scope.DISTRIBUTED_NO_ACK);
        af.setDataPolicy(DataPolicy.REPLICATE);
        if (addListener) {
          CacheListener cl = new CacheListenerAdapter() {
            @Override
            public void afterUpdate(EntryEvent event) {
              // make the slow receiver event slower!
              try {
                Thread.sleep(500);
              } catch (InterruptedException shuttingDown) {
                fail("interrupted");
              }
            }
          };
          af.setCacheListener(cl);
        } else {
          CacheListener cl = new CacheListenerAdapter() {
            @Override
            public void afterCreate(EntryEvent event) {
              if (event.getCallbackArgument() != null) {
                lastCallback = event.getCallbackArgument();
              }
              if (event.getKey().equals("sleepkey")) {
                int sleepMs = (Integer) event.getNewValue();
                try {
                  Thread.sleep(sleepMs);
                } catch (InterruptedException ignore) {
                  fail("interrupted");
                }
              }
            }

            @Override
            public void afterUpdate(EntryEvent event) {
              if (event.getCallbackArgument() != null) {
                lastCallback = event.getCallbackArgument();
              }
              if (event.getKey().equals("sleepkey")) {
                int sleepMs = (Integer) event.getNewValue();
                try {
                  Thread.sleep(sleepMs);
                } catch (InterruptedException ignore) {
                  fail("interrupted");
                }
              }
            }

            @Override
            public void afterInvalidate(EntryEvent event) {
              if (event.getCallbackArgument() != null) {
                lastCallback = event.getCallbackArgument();
              }
            }

            @Override
            public void afterDestroy(EntryEvent event) {
              if (event.getCallbackArgument() != null) {
                lastCallback = event.getCallbackArgument();
              }
            }
          };
          af.setCacheListener(cl);
        }
        Region r1 = createRootRegion("slowrec", af.create());
        // place holder so we receive updates
        r1.create("key", "value");
      }
    });
  }

  protected static final String CHECK_INVALID = "CHECK_INVALID";

  private void checkLastValueInOtherVm(final String lastValue, final Object lcb) {
    VM vm = getOtherVm();
    vm.invoke(new CacheSerializableRunnable("check last value") {
      @Override
      public void run2() throws CacheException {
        Region r1 = getRootRegion("slowrec");
        if (lcb != null) {
          WaitCriterion ev = new WaitCriterion() {
            @Override
            public boolean done() {
              return lcb.equals(lastCallback);
            }

            @Override
            public String description() {
              return "waiting for callback";
            }
          };
          GeodeAwaitility.await().untilAsserted(ev);
          assertEquals(lcb, lastCallback);
        }
        if (lastValue == null) {
          final Region r = r1;
          WaitCriterion ev = new WaitCriterion() {
            @Override
            public boolean done() {
              return r.getEntry("key") == null;
            }

            @Override
            public String description() {
              return "waiting for key to become null";
            }
          };
          GeodeAwaitility.await().untilAsserted(ev);
          assertEquals(null, r1.getEntry("key"));
        } else if (CHECK_INVALID.equals(lastValue)) {
          // should be invalid
          {
            final Region r = r1;
            WaitCriterion ev = new WaitCriterion() {
              @Override
              public boolean done() {
                Entry e = r.getEntry("key");
                if (e == null) {
                  return false;
                }
                return e.getValue() == null;
              }

              @Override
              public String description() {
                return "waiting for invalidate";
              }
            };
            GeodeAwaitility.await().untilAsserted(ev);
          }
        } else {
          {
            int retryCount = 1000;
            Region.Entry re = null;
            Object value = null;
            while (retryCount-- > 0) {
              re = r1.getEntry("key");
              if (re != null) {
                value = re.getValue();
                if (value != null && value.equals(lastValue)) {
                  break;
                }
              }
              try {
                Thread.sleep(50);
              } catch (InterruptedException ignore) {
                fail("interrupted");
              }
            }
            assertNotNull(re);
            assertNotNull(value);
            assertEquals(lastValue, value);
          }
        }
      }
    });
  }

  private void forceQueueFlush() {
    FORCE_ASYNC_QUEUE = false;
    final DMStats stats = getSystem().getDistributionManager().getStats();
    WaitCriterion ev = new WaitCriterion() {
      @Override
      public boolean done() {
        return stats.getAsyncThreads() == 0;
      }

      @Override
      public String description() {
        return "Waiting for async threads to disappear";
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
  }

  private void forceQueuing(final Region r) throws CacheException {
    FORCE_ASYNC_QUEUE = true;
    final DMStats stats = getSystem().getDistributionManager().getStats();
    r.put("forcekey", "forcevalue");

    // wait for the flusher to get its first flush in progress
    WaitCriterion ev = new WaitCriterion() {
      @Override
      public boolean done() {
        return stats.getAsyncQueueFlushesInProgress() != 0;
      }

      @Override
      public String description() {
        return "waiting for flushes to start";
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
  }

  /**
   * Make sure that noack puts to a receiver will eventually queue and then catch up.
   */
  @Test
  public void testNoAck() throws Exception {
    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_NO_ACK);
    final Region r = createRootRegion("slowrec", factory.create());
    final DMStats stats = getSystem().getDistributionManager().getStats();

    // create receiver in vm0 with queuing enabled
    Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, "1");
    doCreateOtherVm(p, false);

    int repeatCount = 2;
    int count = 0;
    while (repeatCount-- > 0) {
      forceQueuing(r);
      final Object key = "key";
      long queuedMsgs = stats.getAsyncQueuedMsgs();
      long dequeuedMsgs = stats.getAsyncDequeuedMsgs();
      long queueSize = stats.getAsyncQueueSize();
      String lastValue = "";
      final long intialQueuedMsgs = queuedMsgs;
      long curQueuedMsgs = queuedMsgs - dequeuedMsgs;
      try {
        // loop while we still have queued the initially queued msgs
        // OR the cur # of queued msgs < 6
        while (dequeuedMsgs < intialQueuedMsgs || curQueuedMsgs <= 6) {
          String value = "count=" + count;
          lastValue = value;
          r.put(key, value);
          count++;
          queueSize = stats.getAsyncQueueSize();
          queuedMsgs = stats.getAsyncQueuedMsgs();
          dequeuedMsgs = stats.getAsyncDequeuedMsgs();
          curQueuedMsgs = queuedMsgs - dequeuedMsgs;
        }
        getLogWriter()
            .info("After " + count + " " + " puts slowrec mode kicked in by queuing " + queuedMsgs
                + " for a total size of " + queueSize);
      } finally {
        forceQueueFlush();
      }
      WaitCriterion ev = new WaitCriterion() {
        @Override
        public boolean done() {
          return stats.getAsyncQueueSize() == 0;
        }

        @Override
        public String description() {
          return "Waiting for queues to empty";
        }
      };
      final long start = currentTimeMillis();
      GeodeAwaitility.await().untilAsserted(ev);
      final long finish = currentTimeMillis();
      getLogWriter()
          .info("After " + (finish - start) + " ms async msgs where flushed. A total of "
              + stats.getAsyncDequeuedMsgs() + " were flushed. lastValue=" + lastValue);

      checkLastValueInOtherVm(lastValue, null);
    }
  }

  /**
   * Create a region named AckRegion with ACK scope
   */
  protected Region createAckRegion(boolean mirror, boolean conflate) throws CacheException {
    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    if (mirror) {
      factory.setDataPolicy(DataPolicy.REPLICATE);
    }
    if (conflate) {
      factory.setEnableAsyncConflation(true);
    }
    final Region r = createRootRegion("AckRegion", factory.create());
    return r;
  }

  /**
   * Make sure that noack puts to a receiver will eventually queue and then catch up with conflation
   */
  @Test
  public void testNoAckConflation() throws Exception {
    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_NO_ACK);
    factory.setEnableAsyncConflation(true);
    final Region r = createRootRegion("slowrec", factory.create());
    final DMStats stats = getSystem().getDistributionManager().getStats();

    // create receiver in vm0 with queuing enabled
    Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, "1");
    doCreateOtherVm(p, false);

    forceQueuing(r);
    final Object key = "key";
    int count = 0;
    final long initialConflatedMsgs = stats.getAsyncConflatedMsgs();
    String lastValue = "";
    final long intialDeQueuedMsgs = stats.getAsyncDequeuedMsgs();
    long start = 0;
    try {
      while ((stats.getAsyncConflatedMsgs() - initialConflatedMsgs) < 1000) {
        String value = "count=" + count;
        lastValue = value;
        r.put(key, value);
        count++;
      }
      start = System.currentTimeMillis();
    } finally {
      forceQueueFlush();
    }
    final long finish = System.currentTimeMillis();
    LogWriterUtils.getLogWriter()
        .info("After " + (finish - start) + " ms async msgs where flushed. A total of "
            + (stats.getAsyncDequeuedMsgs() - intialDeQueuedMsgs)
            + " were flushed. Leaving a queue size of " + stats.getAsyncQueueSize()
            + ". The lastValue was " + lastValue);

    checkLastValueInOtherVm(lastValue, null);
  }

  /**
   * make sure ack does not hang make sure two ack updates do not conflate but are both queued
   */
  @Test
  public void testAckConflation() throws Exception {
    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_NO_ACK);
    factory.setEnableAsyncConflation(true);
    final Region r = createRootRegion("slowrec", factory.create());
    final Region ar = createAckRegion(false, true);
    ar.create("ackKey", "ackValue");

    final DMStats stats = getSystem().getDistributionManager().getStats();

    // create receiver in vm0 with queuing enabled
    Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, "2");
    doCreateOtherVm(p, false);

    forceQueuing(r);
    {
      // make sure ack does not hang
      // make sure two ack updates do not conflate but are both queued
      long startQueuedMsgs = stats.getAsyncQueuedMsgs();
      long startConflatedMsgs = stats.getAsyncConflatedMsgs();
      Thread t = new Thread(() -> ar.put("ackKey", "ackValue"));
      t.start();
      Thread t2 = new Thread(() -> ar.put("ackKey", "ackValue"));
      t2.start();
      // give threads a chance to get queued
      try {
        Thread.sleep(100);
      } catch (InterruptedException ignore) {
        fail("interrupted");
      }
      forceQueueFlush();
      ThreadUtils.join(t, 2 * 1000);
      ThreadUtils.join(t2, 2 * 1000);
      long endQueuedMsgs = stats.getAsyncQueuedMsgs();
      long endConflatedMsgs = stats.getAsyncConflatedMsgs();
      assertEquals(startConflatedMsgs, endConflatedMsgs);
      // queue should be flushed by the time we get an ack
      assertEquals(endQueuedMsgs, stats.getAsyncDequeuedMsgs());
      assertEquals(startQueuedMsgs + 2, endQueuedMsgs);
    }
  }

  /**
   * Make sure that only sequences of updates are conflated Also checks that sending to a conflating
   * region and non-conflating region does the correct thing. Test disabled because it
   * intermittently fails due to race conditions in test. This has been fixed in congo's tests. See
   * bug 35357.
   */
  @Test
  public void testConflationSequence() throws Exception {
    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_NO_ACK);
    factory.setEnableAsyncConflation(true);
    final Region r = createRootRegion("slowrec", factory.create());
    factory.setEnableAsyncConflation(false);
    final Region noConflate = createRootRegion("noConflate", factory.create());
    final DMStats stats = getSystem().getDistributionManager().getStats();

    // create receiver in vm0 with queuing enabled
    Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, "1");
    doCreateOtherVm(p, false);
    {
      VM vm = getOtherVm();
      vm.invoke(new CacheSerializableRunnable("create noConflate") {
        @Override
        public void run2() throws CacheException {
          AttributesFactory af = new AttributesFactory();
          af.setScope(Scope.DISTRIBUTED_NO_ACK);
          af.setDataPolicy(DataPolicy.REPLICATE);
          createRootRegion("noConflate", af.create());
        }
      });
    }

    // now make sure update+destroy does not conflate
    final Object key = "key";
    LogWriterUtils.getLogWriter().info("[testConflationSequence] about to force queuing");
    forceQueuing(r);

    int count = 0;
    String value = "";
    String lastValue = value;
    Object mylcb = null;
    long initialConflatedMsgs = stats.getAsyncConflatedMsgs();
    int endCount = count + 60;

    LogWriterUtils.getLogWriter().info("[testConflationSequence] about to build up queue");
    long begin = System.currentTimeMillis();
    while (count < endCount) {
      value = "count=" + count;
      lastValue = value;
      r.create(key, value);
      count++;
      value = "count=" + count;
      lastValue = value;
      r.put(key, value);
      count++;
      mylcb = value;
      r.destroy(key, mylcb);
      count++;
      lastValue = null;
      assertTrue(System.currentTimeMillis() < begin + 1000 * 60 * 2);
    }
    assertEquals(initialConflatedMsgs, stats.getAsyncConflatedMsgs());
    forceQueueFlush();
    checkLastValueInOtherVm(lastValue, mylcb);

    // now make sure create+update+localDestroy does not conflate
    LogWriterUtils.getLogWriter()
        .info("[testConflationSequence] force queuing create-update-destroy");
    forceQueuing(r);
    initialConflatedMsgs = stats.getAsyncConflatedMsgs();
    endCount = count + 40;

    LogWriterUtils.getLogWriter().info("[testConflationSequence] create-update-destroy");
    begin = System.currentTimeMillis();
    while (count < endCount) {
      value = "count=" + count;
      lastValue = value;
      r.create(key, value);
      count++;
      value = "count=" + count;
      lastValue = value;
      r.put(key, value);
      count++;
      r.localDestroy(key);
      assertTrue(System.currentTimeMillis() < begin + 1000 * 60 * 2);
    }
    assertEquals(initialConflatedMsgs, stats.getAsyncConflatedMsgs());
    forceQueueFlush();
    checkLastValueInOtherVm(lastValue, null);

    // now make sure update+invalidate does not conflate
    LogWriterUtils.getLogWriter().info("[testConflationSequence] force queuing update-invalidate");
    forceQueuing(r);
    initialConflatedMsgs = stats.getAsyncConflatedMsgs();
    value = "count=" + count;
    lastValue = value;
    r.create(key, value);
    count++;
    endCount = count + 40;

    LogWriterUtils.getLogWriter().info("[testConflationSequence] update-invalidate");
    begin = System.currentTimeMillis();
    while (count < endCount) {
      value = "count=" + count;
      lastValue = value;
      r.put(key, value);
      count++;
      r.invalidate(key);
      count++;
      lastValue = CHECK_INVALID;
      assertTrue(System.currentTimeMillis() < begin + 1000 * 60 * 2);
    }
    assertEquals(initialConflatedMsgs, stats.getAsyncConflatedMsgs());
    forceQueueFlush();
    LogWriterUtils.getLogWriter().info("[testConflationSequence] assert other vm");
    checkLastValueInOtherVm(lastValue, null);

    r.destroy(key);

    // now make sure updates to a conflating region are conflated even while
    // updates to a non-conflating are not.
    LogWriterUtils.getLogWriter().info("[testConflationSequence] conflate & no-conflate regions");
    forceQueuing(r);
    final long initialAsyncSocketWrites = stats.getAsyncSocketWrites();

    value = "count=" + count;
    lastValue = value;
    long conflatedMsgs = stats.getAsyncConflatedMsgs();
    long queuedMsgs = stats.getAsyncQueuedMsgs();
    r.create(key, value);
    queuedMsgs++;
    assertEquals(queuedMsgs, stats.getAsyncQueuedMsgs());
    assertEquals(conflatedMsgs, stats.getAsyncConflatedMsgs());
    r.put(key, value);
    queuedMsgs++;
    assertEquals(queuedMsgs, stats.getAsyncQueuedMsgs());
    assertEquals(conflatedMsgs, stats.getAsyncConflatedMsgs());
    noConflate.create(key, value);
    queuedMsgs++;
    assertEquals(queuedMsgs, stats.getAsyncQueuedMsgs());
    assertEquals(conflatedMsgs, stats.getAsyncConflatedMsgs());
    noConflate.put(key, value);
    queuedMsgs++;
    assertEquals(queuedMsgs, stats.getAsyncQueuedMsgs());
    assertEquals(conflatedMsgs, stats.getAsyncConflatedMsgs());
    count++;
    endCount = count + 80;

    begin = System.currentTimeMillis();
    LogWriterUtils.getLogWriter()
        .info("[testConflationSequence:DEBUG] count=" + count + " queuedMsgs="
            + stats.getAsyncQueuedMsgs() + " conflatedMsgs=" + stats.getAsyncConflatedMsgs()
            + " dequeuedMsgs=" + stats.getAsyncDequeuedMsgs() + " asyncSocketWrites="
            + stats.getAsyncSocketWrites());
    while (count < endCount) {
      // make sure we continue to have a flush in progress
      assertEquals(1, stats.getAsyncThreads());
      assertEquals(1, stats.getAsyncQueues());
      assertTrue(stats.getAsyncQueueFlushesInProgress() > 0);
      // make sure we are not completing any flushing while this loop is in progress
      assertEquals(initialAsyncSocketWrites, stats.getAsyncSocketWrites());
      value = "count=" + count;
      lastValue = value;
      r.put(key, value);
      count++;
      // make sure it was conflated and not queued
      assertEquals(queuedMsgs, stats.getAsyncQueuedMsgs());
      conflatedMsgs++;
      assertEquals(conflatedMsgs, stats.getAsyncConflatedMsgs());
      noConflate.put(key, value);
      // make sure it was queued and not conflated
      queuedMsgs++;
      assertEquals(queuedMsgs, stats.getAsyncQueuedMsgs());
      assertEquals(conflatedMsgs, stats.getAsyncConflatedMsgs());
      assertTrue(System.currentTimeMillis() < begin + 1000 * 60 * 2);
    }

    forceQueueFlush();
    LogWriterUtils.getLogWriter().info("[testConflationSequence] assert other vm");
    checkLastValueInOtherVm(lastValue, null);
  }

  /**
   * Make sure that exceeding the queue size limit causes a disconnect.
   */
  @Test
  public void testSizeDisconnect() throws Exception {
    final String expected =
        "org.apache.geode.internal.tcp.ConnectionException: Forced disconnect sent to"
            + "||java.io.IOException: Broken pipe";
    final String addExpected = "<ExpectedException action=add>" + expected + "</ExpectedException>";
    final String removeExpected =
        "<ExpectedException action=remove>" + expected + "</ExpectedException>";

    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_NO_ACK);
    final Region r = createRootRegion("slowrec", factory.create());
    final DistributionManager dm = getSystem().getDistributionManager();
    final DMStats stats = dm.getStats();
    // set others before vm0 connects
    final Set others = dm.getOtherDistributionManagerIds();

    // create receiver in vm0 with queuing enabled
    Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, "5");
    p.setProperty(ASYNC_MAX_QUEUE_SIZE, "1"); // 1 meg
    doCreateOtherVm(p, false);

    final Object key = "key";
    final int VALUE_SIZE = 1024 * 100; // .1M async-max-queue-size should give us 10 of these 100K
                                       // msgs before queue full
    final byte[] value = new byte[VALUE_SIZE];
    int count = 0;
    forceQueuing(r);
    long queuedMsgs = stats.getAsyncQueuedMsgs();
    long queueSize = stats.getAsyncQueueSize();

    getCache().getLogger().info(addExpected);
    try {
      while (stats.getAsyncQueueSizeExceeded() == 0 && stats.getAsyncQueueTimeouts() == 0) {
        r.put(key, value);
        count++;
        if (stats.getAsyncQueueSize() > 0) {
          queuedMsgs = stats.getAsyncQueuedMsgs();
          queueSize = stats.getAsyncQueueSize();
        }
        if (count > 100) {
          fail("should have exceeded max-queue-size by now");
        }
      }
      getLogWriter()
          .info("After " + count + " " + VALUE_SIZE
              + " byte puts slowrec mode kicked in but the queue filled when its size reached "
              + queueSize + " with " + queuedMsgs + " msgs");
      // make sure we lost a connection to vm0
      WaitCriterion ev = new WaitCriterion() {
        @Override
        public boolean done() {
          return dm.getOtherDistributionManagerIds().size() <= others.size()
              && stats.getAsyncQueueSize() == 0;
        }

        @Override
        public String description() {
          return "waiting for connection loss";
        }
      };
      GeodeAwaitility.await().untilAsserted(ev);
    } finally {
      forceQueueFlush();
      getCache().getLogger().info(removeExpected);
    }
    assertEquals(others, dm.getOtherDistributionManagerIds());
    assertEquals(0, stats.getAsyncQueueSize());
  }

  /**
   * Make sure that exceeding the async-queue-timeout causes a disconnect.
   * <p>
   * [bruce] This test was disabled when the SlowRecDUnitTest was re-enabled in build.xml in the
   * splitbrainNov07 branch. It had been disabled since June 2006 due to hangs. Some of the tests,
   * like this one, still need work because the periodically (some quite often) fail.
   */
  @Test
  public void testTimeoutDisconnect() throws Exception {
    final String expected =
        "org.apache.geode.internal.tcp.ConnectionException: Forced disconnect sent to"
            + "||java.io.IOException: Broken pipe";
    final String addExpected = "<ExpectedException action=add>" + expected + "</ExpectedException>";
    final String removeExpected =
        "<ExpectedException action=remove>" + expected + "</ExpectedException>";

    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_NO_ACK);
    final Region r = createRootRegion("slowrec", factory.create());
    final DistributionManager dm = getSystem().getDistributionManager();
    final DMStats stats = dm.getStats();
    // set others before vm0 connects
    final Set others = dm.getOtherDistributionManagerIds();

    // create receiver in vm0 with queuing enabled
    Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, "5");
    p.setProperty(ASYNC_QUEUE_TIMEOUT, "500"); // 500 ms
    doCreateOtherVm(p, true);


    final Object key = "key";
    final int VALUE_SIZE = 1024; // 1k
    final byte[] value = new byte[VALUE_SIZE];
    int count = 0;
    long queuedMsgs = stats.getAsyncQueuedMsgs();
    long queueSize = stats.getAsyncQueueSize();
    final long timeoutLimit = System.currentTimeMillis() + 5000;

    getCache().getLogger().info(addExpected);
    try {
      while (stats.getAsyncQueueTimeouts() == 0) {
        r.put(key, value);
        count++;
        if (stats.getAsyncQueueSize() > 0) {
          queuedMsgs = stats.getAsyncQueuedMsgs();
          queueSize = stats.getAsyncQueueSize();
        }
        if (currentTimeMillis() > timeoutLimit) {
          fail("should have exceeded async-queue-timeout by now");
        }
      }
      getLogWriter()
          .info("After " + count + " " + VALUE_SIZE
              + " byte puts slowrec mode kicked in but the queue filled when its size reached "
              + queueSize + " with " + queuedMsgs + " msgs");
      // make sure we lost a connection to vm0
      WaitCriterion ev = new WaitCriterion() {
        @Override
        public boolean done() {
          if (dm.getOtherDistributionManagerIds().size() > others.size()) {
            return false;
          }
          return stats.getAsyncQueueSize() == 0;
        }

        @Override
        public String description() {
          return "waiting for departure";
        }
      };
      GeodeAwaitility.await().untilAsserted(ev);
    } finally {
      getCache().getLogger().info(removeExpected);
    }
    assertEquals(others, dm.getOtherDistributionManagerIds());
    assertEquals(0, stats.getAsyncQueueSize());
  }

  private static final String KEY_SLEEP = "KEY_SLEEP";
  private static final String KEY_WAIT = "KEY_WAIT";
  private static final String KEY_DISCONNECT = "KEY_DISCONNECT";

  protected static final int CALLBACK_CREATE = 0;
  protected static final int CALLBACK_UPDATE = 1;
  protected static final int CALLBACK_INVALIDATE = 2;
  protected static final int CALLBACK_DESTROY = 3;
  protected static final int CALLBACK_REGION_INVALIDATE = 4;

  protected static final Integer CALLBACK_CREATE_INTEGER = CALLBACK_CREATE;
  protected static final Integer CALLBACK_UPDATE_INTEGER = CALLBACK_UPDATE;
  protected static final Integer CALLBACK_INVALIDATE_INTEGER = CALLBACK_INVALIDATE;
  protected static final Integer CALLBACK_DESTROY_INTEGER = CALLBACK_DESTROY;
  protected static final Integer CALLBACK_REGION_INVALIDATE_INTEGER =
      CALLBACK_REGION_INVALIDATE;

  private static class CallbackWrapper {
    public final Object callbackArgument;
    public final int callbackType;

    public CallbackWrapper(Object callbackArgument, int callbackType) {
      this.callbackArgument = callbackArgument;
      this.callbackType = callbackType;
    }

    public String toString() {
      return "CallbackWrapper: " + callbackArgument.toString() + " of type " + callbackType;
    }
  }

  protected static class ControlListener extends CacheListenerAdapter {
    public final LinkedList callbackArguments = new LinkedList();
    public final LinkedList callbackTypes = new LinkedList();
    public final Object CONTROL_LOCK = new Object();

    @Override
    public void afterCreate(EntryEvent event) {
      LogWriterUtils.getLogWriter()
          .info(event.getRegion().getName() + " afterCreate " + event.getKey());
      synchronized (CONTROL_LOCK) {
        if (event.getCallbackArgument() != null) {
          callbackArguments
              .add(new CallbackWrapper(event.getCallbackArgument(), CALLBACK_CREATE));
          callbackTypes.add(CALLBACK_CREATE_INTEGER);
          CONTROL_LOCK.notifyAll();
        }
      }
      processEvent(event);
    }

    @Override
    public void afterUpdate(EntryEvent event) {
      LogWriterUtils.getLogWriter()
          .info(event.getRegion().getName() + " afterUpdate " + event.getKey());
      synchronized (CONTROL_LOCK) {
        if (event.getCallbackArgument() != null) {
          callbackArguments
              .add(new CallbackWrapper(event.getCallbackArgument(), CALLBACK_UPDATE));
          callbackTypes.add(CALLBACK_UPDATE_INTEGER);
          CONTROL_LOCK.notifyAll();
        }
      }
      processEvent(event);
    }

    @Override
    public void afterInvalidate(EntryEvent event) {
      synchronized (CONTROL_LOCK) {
        if (event.getCallbackArgument() != null) {
          callbackArguments
              .add(new CallbackWrapper(event.getCallbackArgument(), CALLBACK_INVALIDATE));
          callbackTypes.add(CALLBACK_INVALIDATE_INTEGER);
          CONTROL_LOCK.notifyAll();
        }
      }
    }

    @Override
    public void afterDestroy(EntryEvent event) {
      synchronized (CONTROL_LOCK) {
        if (event.getCallbackArgument() != null) {
          callbackArguments
              .add(new CallbackWrapper(event.getCallbackArgument(), CALLBACK_DESTROY));
          callbackTypes.add(CALLBACK_DESTROY_INTEGER);
          CONTROL_LOCK.notifyAll();
        }
      }
    }

    @Override
    public void afterRegionInvalidate(RegionEvent event) {
      synchronized (CONTROL_LOCK) {
        if (event.getCallbackArgument() != null) {
          callbackArguments
              .add(new CallbackWrapper(event.getCallbackArgument(), CALLBACK_REGION_INVALIDATE));
          callbackTypes.add(CALLBACK_REGION_INVALIDATE_INTEGER);
          CONTROL_LOCK.notifyAll();
        }
      }
    }

    private void processEvent(EntryEvent event) {
      if (event.getKey().equals(KEY_SLEEP)) {
        processSleep(event);
      } else if (event.getKey().equals(KEY_WAIT)) {
        processWait(event);
      } else if (event.getKey().equals(KEY_DISCONNECT)) {
        processDisconnect(event);
      }
    }

    private void processSleep(EntryEvent event) {
      int sleepMs = (Integer) event.getNewValue();
      LogWriterUtils.getLogWriter().info("[processSleep] sleeping for " + sleepMs);
      try {
        Thread.sleep(sleepMs);
      } catch (InterruptedException ignore) {
        fail("interrupted");
      }
    }

    private void processWait(EntryEvent event) {
      int sleepMs = (Integer) event.getNewValue();
      LogWriterUtils.getLogWriter().info("[processWait] waiting for " + sleepMs);
      synchronized (CONTROL_LOCK) {
        try {
          CONTROL_LOCK.wait(sleepMs);
        } catch (InterruptedException ignore) {
          return;
        }
      }
    }

    private void processDisconnect(EntryEvent event) {
      LogWriterUtils.getLogWriter().info("[processDisconnect] disconnecting");
      disconnectFromDS();
    }
  }

  /**
   * Make sure a multiple no ack regions conflate properly. [bruce] disabled when use of this dunit
   * test class was reenabled in the splitbrainNov07 branch. The class had been disabled since June
   * 2006 r13222 in the trunk. This test is failing because conflation isn't kicking in for some
   * reason.
   */
  @Test
  public void testMultipleRegionConflation() throws Exception {
    try {
      doTestMultipleRegionConflation();
    } finally {
      // make sure other vm was notified even if test failed
      getOtherVm().invoke(new SerializableRunnable("Wake up other vm") {
        @Override
        public void run() {
          synchronized (doTestMultipleRegionConflation_R1_Listener.CONTROL_LOCK) {
            doTestMultipleRegionConflation_R1_Listener.CONTROL_LOCK.notifyAll();
          }
        }
      });
    }
  }

  protected static ControlListener doTestMultipleRegionConflation_R1_Listener;
  protected static ControlListener doTestMultipleRegionConflation_R2_Listener;

  private void doTestMultipleRegionConflation() throws Exception {
    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_NO_ACK);
    factory.setEnableAsyncConflation(true);
    final Region r1 = createRootRegion("slowrec1", factory.create());
    final Region r2 = createRootRegion("slowrec2", factory.create());

    assertTrue(getSystem().isConnected());
    assertNotNull(r1);
    assertFalse(r1.isDestroyed());
    assertNotNull(getCache());
    assertNotNull(getCache().getRegion("slowrec1"));
    assertNotNull(r2);
    assertFalse(r2.isDestroyed());
    assertNotNull(getCache());
    assertNotNull(getCache().getRegion("slowrec2"));

    final DistributionManager dm = getSystem().getDistributionManager();
    final Serializable controllerVM = dm.getDistributionManagerId();
    final DMStats stats = dm.getStats();
    final int millisToWait = 1000 * 60 * 5; // 5 minutes

    // set others before vm0 connects
    long initialQueuedMsgs = stats.getAsyncQueuedMsgs();

    // create receiver in vm0 with queuing enabled
    final Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, "5");
    p.setProperty(ASYNC_QUEUE_TIMEOUT, "86400000"); // max value
    p.setProperty(ASYNC_MAX_QUEUE_SIZE, "1024"); // max value

    getOtherVm().invoke(new CacheSerializableRunnable("Create other vm") {
      @Override
      public void run2() throws CacheException {
        getSystem(p);

        DistributionManager dm = getSystem().getDistributionManager();
        assertTrue(dm.getDistributionManagerIds().contains(controllerVM));

        AttributesFactory af = new AttributesFactory();
        af.setScope(Scope.DISTRIBUTED_NO_ACK);
        af.setDataPolicy(DataPolicy.REPLICATE);

        doTestMultipleRegionConflation_R1_Listener = new ControlListener();
        af.setCacheListener(doTestMultipleRegionConflation_R1_Listener);
        createRootRegion("slowrec1", af.create());

        doTestMultipleRegionConflation_R2_Listener = new ControlListener();
        af.setCacheListener(doTestMultipleRegionConflation_R2_Listener);
        createRootRegion("slowrec2", af.create());
      }
    });

    // put vm0 cache listener into wait
    LogWriterUtils.getLogWriter()
        .info("[doTestMultipleRegionConflation] about to put vm0 into wait");
    r1.put(KEY_WAIT, millisToWait);

    // build up queue size
    LogWriterUtils.getLogWriter()
        .info("[doTestMultipleRegionConflation] building up queue size...");
    final Object key = "key";
    final int socketBufferSize = getSystem().getConfig().getSocketBufferSize();
    final int VALUE_SIZE = socketBufferSize * 3;
    // final int VALUE_SIZE = 1024 * 1024 ; // 1 MB
    final byte[] value = new byte[VALUE_SIZE];

    int count = 0;
    while (stats.getAsyncQueuedMsgs() == initialQueuedMsgs) {
      count++;
      r1.put(key, value);
    }

    LogWriterUtils.getLogWriter()
        .info("[doTestMultipleRegionConflation] After " + count + " puts of size " + VALUE_SIZE
            + " slowrec mode kicked in with queue size=" + stats.getAsyncQueueSize());

    // put values that will be asserted
    final Object key1 = "key1";
    final Object key2 = "key2";
    Object putKey = key1;
    boolean flag = true;
    for (int i = 0; i < 30; i++) {
      if (i == 10) {
        putKey = key2;
      }
      if (flag) {
        if (i == 6) {
          r1.invalidate(putKey, i);
        } else if (i == 24) {
          r1.invalidateRegion(i);
        } else {
          r1.put(putKey, value, i);
        }
      } else {
        if (i == 15) {
          r2.destroy(putKey, i);
        } else {
          r2.put(putKey, value, i);
        }
      }
      flag = !flag;
    }

    // r1: key1, 0, create
    // r1: key1, 4, update
    // r1: key1, 6, invalidate
    // r1: key1, 8, update

    // r1: key2, 10, create
    // r1: 24, invalidateRegion
    // r1: key2, 28, update

    // r2: key1, 1, create
    // r2: key1, 9, update

    // r2: key2, 11, create
    // r2: key2, 13, update
    // r2: key2, 15, destroy
    // r2: key2, 17, create
    // r2: key2, 29, update

    final int[] r1ExpectedArgs = new int[] {0, 4, 6, 8, 10, 24, 28};
    final int[] r1ExpectedTypes = new int[] /* 0, 1, 2, 1, 0, 4, 1 */
    {CALLBACK_CREATE, CALLBACK_UPDATE, CALLBACK_INVALIDATE, CALLBACK_UPDATE, CALLBACK_CREATE,
        CALLBACK_REGION_INVALIDATE, CALLBACK_UPDATE};

    final int[] r2ExpectedArgs = new int[] {1, 9, 11, 13, 15, 17, 29};
    final int[] r2ExpectedTypes = new int[] {CALLBACK_CREATE, CALLBACK_UPDATE, CALLBACK_CREATE,
        CALLBACK_UPDATE, CALLBACK_DESTROY, CALLBACK_CREATE, CALLBACK_UPDATE};

    // send notify to vm0
    LogWriterUtils.getLogWriter().info("[doTestMultipleRegionConflation] wake up vm0");
    getOtherVm().invoke(new SerializableRunnable("Wake up other vm") {
      @Override
      public void run() {
        synchronized (doTestMultipleRegionConflation_R1_Listener.CONTROL_LOCK) {
          doTestMultipleRegionConflation_R1_Listener.CONTROL_LOCK.notifyAll();
        }
      }
    });

    // wait for queue to be flushed
    LogWriterUtils.getLogWriter().info("[doTestMultipleRegionConflation] wait for vm0");
    getOtherVm().invoke(new SerializableRunnable("Wait for other vm") {
      @Override
      public void run() {
        try {
          synchronized (doTestMultipleRegionConflation_R1_Listener.CONTROL_LOCK) {
            while (doTestMultipleRegionConflation_R1_Listener.callbackArguments
                .size() < r1ExpectedArgs.length) {
              doTestMultipleRegionConflation_R1_Listener.CONTROL_LOCK.wait(millisToWait);
            }
          }
          synchronized (doTestMultipleRegionConflation_R2_Listener.CONTROL_LOCK) {
            while (doTestMultipleRegionConflation_R2_Listener.callbackArguments
                .size() < r2ExpectedArgs.length) {
              doTestMultipleRegionConflation_R2_Listener.CONTROL_LOCK.wait(millisToWait);
            }
          }
        } catch (InterruptedException ignore) {
          fail("interrupted");
        }
      }
    });

    // assert values on both listeners
    LogWriterUtils.getLogWriter()
        .info("[doTestMultipleRegionConflation] assert callback arguments");
    getOtherVm().invoke(new SerializableRunnable("Assert callback arguments") {
      @Override
      public void run() {
        synchronized (doTestMultipleRegionConflation_R1_Listener.CONTROL_LOCK) {
          LogWriterUtils.getLogWriter()
              .info("doTestMultipleRegionConflation_R1_Listener.callbackArguments="
                  + doTestMultipleRegionConflation_R1_Listener.callbackArguments);
          LogWriterUtils.getLogWriter()
              .info("doTestMultipleRegionConflation_R1_Listener.callbackTypes="
                  + doTestMultipleRegionConflation_R1_Listener.callbackTypes);
          assertEquals(doTestMultipleRegionConflation_R1_Listener.callbackArguments.size(),
              doTestMultipleRegionConflation_R1_Listener.callbackTypes.size());
          int i = 0;
          for (final Object o : doTestMultipleRegionConflation_R1_Listener.callbackArguments) {
            CallbackWrapper wrapper = (CallbackWrapper) o;
            assertEquals(r1ExpectedArgs[i], wrapper.callbackArgument);
            assertEquals(r1ExpectedTypes[i],
                doTestMultipleRegionConflation_R1_Listener.callbackTypes.get(i));
            i++;
          }
        }
        synchronized (doTestMultipleRegionConflation_R2_Listener.CONTROL_LOCK) {
          LogWriterUtils.getLogWriter()
              .info("doTestMultipleRegionConflation_R2_Listener.callbackArguments="
                  + doTestMultipleRegionConflation_R2_Listener.callbackArguments);
          LogWriterUtils.getLogWriter()
              .info("doTestMultipleRegionConflation_R2_Listener.callbackTypes="
                  + doTestMultipleRegionConflation_R2_Listener.callbackTypes);
          assertEquals(doTestMultipleRegionConflation_R2_Listener.callbackArguments.size(),
              doTestMultipleRegionConflation_R2_Listener.callbackTypes.size());
          int i = 0;
          for (final Object o : doTestMultipleRegionConflation_R2_Listener.callbackArguments) {
            CallbackWrapper wrapper = (CallbackWrapper) o;
            assertEquals(r2ExpectedArgs[i], wrapper.callbackArgument);
            assertEquals(r2ExpectedTypes[i],
                doTestMultipleRegionConflation_R2_Listener.callbackTypes.get(i));
            i++;
          }
        }
      }
    });
  }

  /**
   * Make sure a disconnect causes queue memory to be released.
   */
  @Test
  public void testDisconnectCleanup() throws Exception {
    try {
      doTestDisconnectCleanup();
    } finally {
      // make sure other vm was notified even if test failed
      getOtherVm().invoke(new SerializableRunnable("Wake up other vm") {
        @Override
        public void run() {
          synchronized (doTestDisconnectCleanup_Listener.CONTROL_LOCK) {
            doTestDisconnectCleanup_Listener.CONTROL_LOCK.notifyAll();
          }
        }
      });
    }
  }

  protected static ControlListener doTestDisconnectCleanup_Listener;

  private void doTestDisconnectCleanup() throws Exception {
    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(DISTRIBUTED_NO_ACK);
    final Region r = createRootRegion("slowrec", factory.create());
    final DistributionManager dm = getSystem().getDistributionManager();
    final DMStats stats = dm.getStats();
    // set others before vm0 connects
    final Set others = dm.getOtherDistributionManagerIds();
    long initialQueuedMsgs = stats.getAsyncQueuedMsgs();
    final long initialQueues = stats.getAsyncQueues();

    // create receiver in vm0 with queuing enabled
    final Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, "5");
    p.setProperty(ASYNC_QUEUE_TIMEOUT, "86400000"); // max value
    p.setProperty(ASYNC_MAX_QUEUE_SIZE, "1024"); // max value

    getOtherVm().invoke(new CacheSerializableRunnable("Create other vm") {
      @Override
      public void run2() throws CacheException {
        getSystem(p);
        AttributesFactory af = new AttributesFactory();
        af.setScope(DISTRIBUTED_NO_ACK);
        af.setDataPolicy(REPLICATE);

        doTestDisconnectCleanup_Listener = new ControlListener();
        af.setCacheListener(doTestDisconnectCleanup_Listener);
        createRootRegion("slowrec", af.create());
      }
    });

    // put vm0 cache listener into wait
    getLogWriter().info("[testDisconnectCleanup] about to put vm0 into wait");
    int millisToWait = 1000 * 60 * 5; // 5 minutes
    r.put(KEY_WAIT, millisToWait);
    r.put(KEY_DISCONNECT, KEY_DISCONNECT);

    // build up queue size
    getLogWriter().info("[testDisconnectCleanup] building up queue size...");
    final Object key = "key";
    final int socketBufferSize = getSystem().getConfig().getSocketBufferSize();
    final int VALUE_SIZE = socketBufferSize * 3;
    // final int VALUE_SIZE = 1024 * 1024 ; // 1 MB
    final byte[] value = new byte[VALUE_SIZE];

    int count = 0;
    final long abortMillis = currentTimeMillis() + millisToWait;
    while (stats.getAsyncQueuedMsgs() == initialQueuedMsgs) {
      count++;
      r.put(key, value);
      assertFalse(currentTimeMillis() >= abortMillis);
    }

    getLogWriter().info("[testDisconnectCleanup] After " + count + " puts of size "
        + VALUE_SIZE + " slowrec mode kicked in with queue size=" + stats.getAsyncQueueSize());

    while (stats.getAsyncQueuedMsgs() < 10 || stats.getAsyncQueueSize() < VALUE_SIZE * 10) {
      count++;
      r.put(key, value);
      assertFalse(currentTimeMillis() >= abortMillis);
    }
    assertTrue(stats.getAsyncQueuedMsgs() >= 10);

    while (stats.getAsyncQueues() < 1) {
      pause(100);
      assertFalse(currentTimeMillis() >= abortMillis);
    }

    getLogWriter()
        .info("[testDisconnectCleanup] After " + count + " puts of size " + VALUE_SIZE
            + " queue size has reached " + stats.getAsyncQueueSize()
            + " bytes and number of queues is " + stats.getAsyncQueues() + ".");

    assertTrue(stats.getAsyncQueueSize() >= (VALUE_SIZE * 5));
    assertEquals(initialQueues + 1, stats.getAsyncQueues());

    // assert vm0 is still connected
    assertTrue(dm.getOtherDistributionManagerIds().size() > others.size());

    // send notify to vm0
    getLogWriter().info("[testDisconnectCleanup] wake up vm0");
    getOtherVm().invoke(new SerializableRunnable("Wake up other vm") {
      @Override
      public void run() {
        synchronized (doTestDisconnectCleanup_Listener.CONTROL_LOCK) {
          doTestDisconnectCleanup_Listener.CONTROL_LOCK.notifyAll();
        }
      }
    });

    // make sure we lost a connection to vm0
    getLogWriter().info("[testDisconnectCleanup] wait for vm0 to disconnect");
    WaitCriterion ev = new WaitCriterion() {
      @Override
      public boolean done() {
        return dm.getOtherDistributionManagerIds().size() <= others.size();
      }

      @Override
      public String description() {
        return "waiting for disconnect";
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
    assertEquals(others, dm.getOtherDistributionManagerIds());

    // check free memory... perform wait loop with System.gc
    getLogWriter().info("[testDisconnectCleanup] wait for queue cleanup");
    ev = new WaitCriterion() {
      @Override
      public boolean done() {
        if (stats.getAsyncQueues() <= initialQueues) {
          return true;
        }
        getRuntime().gc();
        return false;
      }

      @Override
      public String description() {
        return "waiting for queue cleanup";
      }
    };
    GeodeAwaitility.await().untilAsserted(ev);
    assertEquals(initialQueues, stats.getAsyncQueues());
  }

  /**
   * Make sure a disconnect causes queue memory to be released.
   * <p>
   * [bruce] This test was disabled when the SlowRecDUnitTest was re-enabled in build.xml in the
   * splitbrainNov07 branch. It had been disabled since June 2006 due to hangs. Some of the tests,
   * like this one, still need work because the periodically (some quite often) fail.
   */
  @Test
  public void testPartialMessage() throws Exception {
    try {
      doTestPartialMessage();
    } finally {
      // make sure other vm was notified even if test failed
      getOtherVm().invoke(new SerializableRunnable("Wake up other vm") {
        @Override
        public void run() {
          synchronized (doTestPartialMessage_Listener.CONTROL_LOCK) {
            doTestPartialMessage_Listener.CONTROL_LOCK.notifyAll();
          }
        }
      });
    }
  }

  protected static ControlListener doTestPartialMessage_Listener;

  private void doTestPartialMessage() throws Exception {
    final AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_NO_ACK);
    factory.setEnableAsyncConflation(true);
    final Region r = createRootRegion("slowrec", factory.create());
    final DistributionManager dm = getSystem().getDistributionManager();
    final DMStats stats = dm.getStats();

    // set others before vm0 connects
    long initialQueuedMsgs = stats.getAsyncQueuedMsgs();

    // create receiver in vm0 with queuing enabled
    final Properties p = new Properties();
    p.setProperty(ASYNC_DISTRIBUTION_TIMEOUT, String.valueOf(1000 * 4)); // 4 sec
    p.setProperty(ASYNC_QUEUE_TIMEOUT, "86400000"); // max value
    p.setProperty(ASYNC_MAX_QUEUE_SIZE, "1024"); // max value

    getOtherVm().invoke(new CacheSerializableRunnable("Create other vm") {
      @Override
      public void run2() throws CacheException {
        getSystem(p);
        AttributesFactory af = new AttributesFactory();
        af.setScope(Scope.DISTRIBUTED_NO_ACK);
        af.setDataPolicy(DataPolicy.REPLICATE);

        doTestPartialMessage_Listener = new ControlListener();
        af.setCacheListener(doTestPartialMessage_Listener);
        createRootRegion("slowrec", af.create());
      }
    });

    // put vm0 cache listener into wait
    LogWriterUtils.getLogWriter().info("[testPartialMessage] about to put vm0 into wait");
    final int millisToWait = 1000 * 60 * 5; // 5 minutes
    r.put(KEY_WAIT, millisToWait);

    // build up queue size
    LogWriterUtils.getLogWriter().info("[testPartialMessage] building up queue size...");
    final Object key = "key";
    final int socketBufferSize = getSystem().getConfig().getSocketBufferSize();
    final int VALUE_SIZE = socketBufferSize * 3;
    // 1024 * 20; // 20 KB
    final byte[] value = new byte[VALUE_SIZE];

    int count = 0;
    while (stats.getAsyncQueuedMsgs() == initialQueuedMsgs) {
      count++;
      r.put(key, value, count);
    }

    final int partialId = count;
    assertEquals(0, stats.getAsyncConflatedMsgs());

    LogWriterUtils.getLogWriter().info("[testPartialMessage] After " + count + " puts of size "
        + VALUE_SIZE + " slowrec mode kicked in with queue size=" + stats.getAsyncQueueSize());

    Wait.pause(2000);

    // conflate 10 times
    while (stats.getAsyncConflatedMsgs() < 10) {
      count++;
      r.put(key, value, count);
      if (count == partialId + 1) {
        assertEquals(initialQueuedMsgs + 2, stats.getAsyncQueuedMsgs());
        assertEquals(0, stats.getAsyncConflatedMsgs());
      } else if (count == partialId + 2) {
        assertEquals(initialQueuedMsgs + 2, stats.getAsyncQueuedMsgs());
        assertEquals(1, stats.getAsyncConflatedMsgs());
      }
    }

    final int conflateId = count;

    final int[] expectedArgs = {partialId, conflateId};

    // send notify to vm0
    LogWriterUtils.getLogWriter().info("[testPartialMessage] wake up vm0");
    getOtherVm().invoke(new SerializableRunnable("Wake up other vm") {
      @Override
      public void run() {
        synchronized (doTestPartialMessage_Listener.CONTROL_LOCK) {
          doTestPartialMessage_Listener.CONTROL_LOCK.notify();
        }
      }
    });

    // wait for queue to be flushed
    LogWriterUtils.getLogWriter().info("[testPartialMessage] wait for vm0");
    getOtherVm().invoke(new SerializableRunnable("Wait for other vm") {
      @Override
      public void run() {
        try {
          synchronized (doTestPartialMessage_Listener.CONTROL_LOCK) {
            boolean done = false;
            while (!done) {
              if (doTestPartialMessage_Listener.callbackArguments.size() > 0) {
                CallbackWrapper last =
                    (CallbackWrapper) doTestPartialMessage_Listener.callbackArguments.getLast();
                Integer lastId = (Integer) last.callbackArgument;
                if (lastId == conflateId) {
                  done = true;
                } else {
                  doTestPartialMessage_Listener.CONTROL_LOCK.wait(millisToWait);
                }
              } else {
                doTestPartialMessage_Listener.CONTROL_LOCK.wait(millisToWait);
              }
            }
          }
        } catch (InterruptedException ignore) {
          fail("interrupted");
        }
      }
    });

    // assert values on both listeners
    LogWriterUtils.getLogWriter().info("[testPartialMessage] assert callback arguments");
    getOtherVm().invoke(new SerializableRunnable("Assert callback arguments") {
      @Override
      public void run() {
        synchronized (doTestPartialMessage_Listener.CONTROL_LOCK) {
          LogWriterUtils.getLogWriter()
              .info("[testPartialMessage] " + "doTestPartialMessage_Listener.callbackArguments="
                  + doTestPartialMessage_Listener.callbackArguments);

          assertEquals(doTestPartialMessage_Listener.callbackArguments.size(),
              doTestPartialMessage_Listener.callbackTypes.size());

          int i = 0;
          Iterator argIter = doTestPartialMessage_Listener.callbackArguments.iterator();
          Iterator typeIter = doTestPartialMessage_Listener.callbackTypes.iterator();

          while (argIter.hasNext()) {
            CallbackWrapper wrapper = (CallbackWrapper) argIter.next();
            Integer arg = (Integer) wrapper.callbackArgument;
            typeIter.next(); // Integer type
            if (arg < partialId) {
              continue;
            }
            assertEquals(new Integer(expectedArgs[i]), arg);
            // assertIndexDetailsEquals(CALLBACK_UPDATE_INTEGER, type);
            i++;
          }
        }
      }
    });

  }
}
