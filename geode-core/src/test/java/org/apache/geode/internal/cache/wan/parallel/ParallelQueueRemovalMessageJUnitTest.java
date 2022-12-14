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
package org.apache.geode.internal.cache.wan.parallel;

import static org.apache.geode.cache.Region.SEPARATOR;
import static org.apache.geode.internal.statistics.StatisticsClockFactory.disabledClock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.Before;
import org.junit.Test;

import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.Operation;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.internal.cache.AbstractBucketRegionQueue;
import org.apache.geode.internal.cache.BucketRegionQueue;
import org.apache.geode.internal.cache.BucketRegionQueueHelper;
import org.apache.geode.internal.cache.EntryEventImpl;
import org.apache.geode.internal.cache.ForceReattemptException;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.KeyInfo;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.PartitionedRegionHelper;
import org.apache.geode.internal.cache.wan.AbstractGatewaySender;
import org.apache.geode.internal.cache.wan.GatewaySenderEventImpl;
import org.apache.geode.internal.cache.wan.GatewaySenderStats;
import org.apache.geode.internal.statistics.DummyStatisticsFactory;
import org.apache.geode.test.fake.Fakes;

public class ParallelQueueRemovalMessageJUnitTest {

  private static final String GATEWAY_SENDER_ID = "ny";
  private static final int BUCKET_ID = 85;
  private static final long KEY = 198;

  private GemFireCacheImpl cache;
  private PartitionedRegion queueRegion;
  private AbstractGatewaySender sender;
  private PartitionedRegion rootRegion;
  private BucketRegionQueue bucketRegionQueue;
  private BucketRegionQueueHelper bucketRegionQueueHelper;
  private GatewaySenderStats stats;

  @Before
  public void setUpGemFire() {
    createCache();
    createQueueRegion();
    createGatewaySender();
    createRootRegion();
    createBucketRegionQueue();
  }

  private void createCache() {
    // Mock cache
    cache = Fakes.cache();
  }

  private void createQueueRegion() {
    // Mock queue region
    queueRegion =
        ParallelGatewaySenderHelper.createMockQueueRegion(cache,
            ParallelGatewaySenderHelper.getRegionQueueName(GATEWAY_SENDER_ID));
  }

  private void createGatewaySender() {
    // Mock gateway sender
    sender = ParallelGatewaySenderHelper.createGatewaySender(cache);
    when(queueRegion.getParallelGatewaySender()).thenReturn(sender);
    when(sender.getQueues()).thenReturn(null);
    when(sender.getDispatcherThreads()).thenReturn(1);
    stats = new GatewaySenderStats(new DummyStatisticsFactory(), "gatewaySenderStats-", "ln",
        disabledClock());
    when(sender.getStatistics()).thenReturn(stats);
  }

  private void createRootRegion() {
    // Mock root region
    rootRegion = mock(PartitionedRegion.class);
    when(rootRegion.getFullPath())
        .thenReturn(SEPARATOR + PartitionedRegionHelper.PR_ROOT_REGION_NAME);
    when(cache.getRegion(PartitionedRegionHelper.PR_ROOT_REGION_NAME, true))
        .thenReturn(rootRegion);
    when(cache.getRegion(ParallelGatewaySenderHelper.getRegionQueueName(GATEWAY_SENDER_ID)))
        .thenReturn(queueRegion);
  }

  private void createBucketRegionQueue() {
    // Create BucketRegionQueue
    BucketRegionQueue realBucketRegionQueue = ParallelGatewaySenderHelper
        .createBucketRegionQueue(cache, rootRegion, queueRegion, BUCKET_ID);
    bucketRegionQueue = spy(realBucketRegionQueue);
    // (this.queueRegion.getBucketName(BUCKET_ID), attributes, this.rootRegion, this.cache, ira);
    EntryEventImpl entryEvent = EntryEventImpl.create(bucketRegionQueue, Operation.DESTROY,
        KEY, "value", null, false, mock(DistributedMember.class));
    doReturn(entryEvent).when(bucketRegionQueue).newDestroyEntryEvent(any(), any());
    // when(this.bucketRegionQueue.newDestroyEntryEvent(any(), any())).thenReturn();

    bucketRegionQueueHelper =
        new BucketRegionQueueHelper(cache, queueRegion, bucketRegionQueue);
  }

  @Test
  public void ifIsFailedBatchRemovalMessageKeysClearedFlagSetThenAddToFailedBatchRemovalMessageKeysNotCalled()
      throws ForceReattemptException {
    ParallelQueueRemovalMessage pqrm = new ParallelQueueRemovalMessage();
    Object object = new Object();
    PartitionedRegion partitionedRegion = mock(PartitionedRegion.class);
    AbstractBucketRegionQueue brq = mock(AbstractBucketRegionQueue.class);
    doThrow(new EntryNotFoundException("ENTRY NOT FOUND")).when(brq).destroyKey(object);
    when(brq.isFailedBatchRemovalMessageKeysClearedFlag()).thenReturn(true);
    doNothing().when(brq).addToFailedBatchRemovalMessageKeys(object);
    pqrm.destroyKeyFromBucketQueue(brq, object, partitionedRegion);
    verify(brq, times(1)).destroyKey(object);
    verify(brq, times(1)).isFailedBatchRemovalMessageKeysClearedFlag();
    verify(brq, times(0)).addToFailedBatchRemovalMessageKeys(object);

  }

  @Test
  public void validateFailedBatchRemovalMessageKeysInUninitializedBucketRegionQueue()
      throws Exception {
    // Validate initial BucketRegionQueue state
    assertFalse(bucketRegionQueue.isInitialized());
    assertEquals(0, bucketRegionQueue.getFailedBatchRemovalMessageKeys().size());
    stats.setSecondaryQueueSize(1);

    // Create and process a ParallelQueueRemovalMessage (causes the failedBatchRemovalMessageKeys to
    // add a key)
    createAndProcessParallelQueueRemovalMessage();

    // Validate BucketRegionQueue after processing ParallelQueueRemovalMessage
    assertEquals(1, bucketRegionQueue.getFailedBatchRemovalMessageKeys().size());
    // failed BatchRemovalMessage will not modify stats
    assertEquals(1, stats.getSecondaryEventQueueSize());
  }

  @Test
  public void validateDestroyKeyFromBucketQueueInUninitializedBucketRegionQueue() throws Exception {
    // Validate initial BucketRegionQueue state
    assertEquals(0, bucketRegionQueue.size());
    assertFalse(bucketRegionQueue.isInitialized());

    // Add an event to the BucketRegionQueue and verify BucketRegionQueue state
    bucketRegionQueueHelper.addEvent(KEY);
    assertEquals(1, bucketRegionQueue.size());
    assertEquals(1, stats.getSecondaryEventQueueSize());

    // Create and process a ParallelQueueRemovalMessage (causes the value of the entry to be set to
    // DESTROYED)
    when(queueRegion.getKeyInfo(KEY, null, null)).thenReturn(new KeyInfo(KEY, null, null));
    createAndProcessParallelQueueRemovalMessage();

    // Clean up destroyed tokens and validate BucketRegionQueue
    bucketRegionQueueHelper.cleanUpDestroyedTokensAndMarkGIIComplete();
    assertEquals(0, bucketRegionQueue.size());
    assertEquals(0, stats.getSecondaryEventQueueSize());
  }

  @Test
  public void validateDestroyFromTempQueueInUninitializedBucketRegionQueue() throws Exception {
    // Validate initial BucketRegionQueue state
    assertFalse(bucketRegionQueue.isInitialized());

    // Create a real ConcurrentParallelGatewaySenderQueue
    ParallelGatewaySenderEventProcessor processor =
        ParallelGatewaySenderHelper.createParallelGatewaySenderEventProcessor(sender);

    // Add a mock GatewaySenderEventImpl to the temp queue
    BlockingQueue<GatewaySenderEventImpl> tempQueue =
        createTempQueueAndAddEvent(processor, mock(GatewaySenderEventImpl.class));
    assertEquals(1, tempQueue.size());

    // Create and process a ParallelQueueRemovalMessage (causes the failedBatchRemovalMessageKeys to
    // add a key)
    createAndProcessParallelQueueRemovalMessage();

    // Validate temp queue is empty after processing ParallelQueueRemovalMessage
    assertEquals(0, tempQueue.size());
  }

  @Test
  public void validateDestroyFromBucketQueueAndTempQueueInUninitializedBucketRegionQueue() {
    // Validate initial BucketRegionQueue state
    assertFalse(bucketRegionQueue.isInitialized());
    assertEquals(0, bucketRegionQueue.size());

    // Create a real ConcurrentParallelGatewaySenderQueue
    ParallelGatewaySenderEventProcessor processor =
        ParallelGatewaySenderHelper.createParallelGatewaySenderEventProcessor(sender);

    // Add an event to the BucketRegionQueue and verify BucketRegionQueue state
    GatewaySenderEventImpl event = bucketRegionQueueHelper.addEvent(KEY);
    assertEquals(1, bucketRegionQueue.size());
    assertEquals(1, stats.getSecondaryEventQueueSize());

    // Add a mock GatewaySenderEventImpl to the temp queue
    BlockingQueue<GatewaySenderEventImpl> tempQueue = createTempQueueAndAddEvent(processor, event);
    assertEquals(1, tempQueue.size());

    // Create and process a ParallelQueueRemovalMessage (causes the value of the entry to be set to
    // DESTROYED)
    when(queueRegion.getKeyInfo(KEY, null, null)).thenReturn(new KeyInfo(KEY, null, null));
    createAndProcessParallelQueueRemovalMessage();

    // Validate temp queue is empty after processing ParallelQueueRemovalMessage
    assertEquals(0, tempQueue.size());
    assertEquals(0, stats.getSecondaryEventQueueSize());

    // Clean up destroyed tokens
    bucketRegionQueueHelper.cleanUpDestroyedTokensAndMarkGIIComplete();

    // Validate BucketRegionQueue is empty after processing ParallelQueueRemovalMessage
    assertEquals(0, bucketRegionQueue.size());
  }

  private void createAndProcessParallelQueueRemovalMessage() {
    ParallelQueueRemovalMessage message =
        new ParallelQueueRemovalMessage(createRegionToDispatchedKeysMap());
    message.process((ClusterDistributionManager) cache.getDistributionManager());
  }

  private HashMap<String, Map<Integer, List<Object>>> createRegionToDispatchedKeysMap() {
    HashMap<String, Map<Integer, List<Object>>> regionToDispatchedKeys = new HashMap<>();
    Map<Integer, List<Object>> bucketIdToDispatchedKeys = new HashMap<>();
    List<Object> dispatchedKeys = new ArrayList<>();
    dispatchedKeys.add(KEY);
    bucketIdToDispatchedKeys.put(BUCKET_ID, dispatchedKeys);
    regionToDispatchedKeys.put(ParallelGatewaySenderHelper.getRegionQueueName(GATEWAY_SENDER_ID),
        bucketIdToDispatchedKeys);
    return regionToDispatchedKeys;
  }

  private BlockingQueue<GatewaySenderEventImpl> createTempQueueAndAddEvent(
      ParallelGatewaySenderEventProcessor processor, GatewaySenderEventImpl event) {
    ParallelGatewaySenderQueue queue = (ParallelGatewaySenderQueue) processor.getQueue();
    Map<Integer, BlockingQueue<GatewaySenderEventImpl>> tempQueueMap =
        queue.getBucketToTempQueueMap();
    BlockingQueue<GatewaySenderEventImpl> tempQueue = new LinkedBlockingQueue<>();
    when(event.getShadowKey()).thenReturn(KEY);
    tempQueue.add(event);
    tempQueueMap.put(BUCKET_ID, tempQueue);
    return tempQueue;
  }
}
