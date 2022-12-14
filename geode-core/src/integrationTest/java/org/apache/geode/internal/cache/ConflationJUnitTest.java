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
/*
 * Created on Feb 20, 2006
 *
 * TODO To change the template for this generated file go to Window - Preferences - Java - Code
 * Style - Code Templates
 */
package org.apache.geode.internal.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Collection;

import org.junit.Test;

import org.apache.geode.internal.cache.entries.DiskEntry;

/**
 * This test does a check that conflation in the buffer happen correctly
 *
 * Conflation cases tested include:
 * <ul>
 * <li>create, modify
 * <li>create, destroy
 * <li>create, destroy, create
 * <li>create, invalidate
 * <li>create, invalidate
 * <li>create, invalidate, modify
 * </ul>
 * The test is done for persist only, overflow only and persist + overflow only (async modes).
 */
public class ConflationJUnitTest extends DiskRegionTestingBase {

  private final DiskRegionProperties diskProps = new DiskRegionProperties();

  private long flushCount;

  @Override
  protected final void postSetUp() throws Exception {
    diskProps.setDiskDirs(dirs);
    diskProps.setBytesThreshold(100000000);
    diskProps.setTimeInterval(100000000);
    diskProps.setSynchronous(false);
  }

  private void createPersistOnly() {
    region = DiskRegionHelperFactory.getAsyncPersistOnlyRegion(cache, diskProps);
  }

  private void createOverflowAndPersist() {
    region = DiskRegionHelperFactory.getAsyncOverFlowAndPersistRegion(cache, diskProps);
  }

  /**
   * do a put followed by a put
   */
  private void putAndPut() {
    region.put(1, 1);
    region.put(1, 2);
  }

  /**
   * do a put followed by a destroy on the same entry
   */
  private void putAndDestroy() {
    region.put(1, 1);
    try {
      region.destroy(1);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      fail(" failed to destory Integer");
    }
  }

  /**
   * do a put destroy the same entry and put it again
   */
  private void putDestroyPut() {
    putAndDestroy();
    region.put(1, 2);
  }

  /**
   * put a key and then invalidate it
   */
  private void putAndInvalidate() {
    region.put(1, 1);
    try {
      region.invalidate(1);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      throw new AssertionError(" failed to invalidate Integer", e);
    }
  }

  /**
   * put a key, invalidate it and the perform a put on it
   */
  private void putInvalidatePut() {
    putAndInvalidate();
    region.put(1, 2);
  }

  /**
   * do a create and then a put on the same key
   */
  private void createAndPut() {
    try {
      region.create(1, 1);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      throw new AssertionError(" failed in trying to create", e);
    }
    region.put(1, 2);
  }

  /**
   * do a create and then a destroy
   */
  private void createAndDestroy() {
    try {
      region.create(1, 1);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      throw new AssertionError("failed in trying to create", e);
    }
    try {
      region.destroy(1);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      throw new AssertionError("failed to destroy Integer", e);
    }
  }

  /**
   * do a create then destroy the entry and create it again
   */
  private void createDestroyCreate() {
    createAndDestroy();
    try {
      region.create(1, 2);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      throw new AssertionError("failed in trying to create", e);
    }
  }

  /**
   * create an entry and then invalidate it
   */
  private void createAndInvalidate() {
    try {
      region.create(1, 1);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      throw new AssertionError("failed in trying to create", e);
    }
    try {
      region.invalidate(1);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      throw new AssertionError("failed to invalidate Integer", e);
    }
  }

  /**
   * create an entry, invalidate it and then perform a put on the same key
   */
  private void createInvalidatePut() {
    createAndInvalidate();
    region.put(1, 2);
  }

  /**
   * validate whether a modification of an entry was correctly done
   */
  private void validateModification() {
    Collection entries = ((LocalRegion) region).entries.regionEntries();
    if (entries.size() != 1) {
      fail("expected size to be 1 but is not so");
    }
    RegionEntry entry = (RegionEntry) entries.iterator().next();
    DiskId id = ((DiskEntry) entry).getDiskId();
    Object obj = ((LocalRegion) region).getDiskRegion().get(id);
    if (!(obj.equals(2))) {
      fail("incorrect modification");
    }
  }

  /**
   * validate whether nothing was written
   */
  private void validateNothingWritten() {
    Collection entries = ((LocalRegion) region).entries.regionEntries();
    // We actually will have a tombstone in the region, hence
    // the 1 entry
    if (entries.size() != 1) {
      fail("expected size to be 1 but is " + entries.size());
    }
    assertEquals(flushCount, getCurrentFlushCount());
  }

  /**
   * validate whether invalidate was done
   */
  private void validateTombstone() {
    Collection entries = ((LocalRegion) region).entries.regionEntries();
    if (entries.size() != 1) {
      fail("expected size to be 1 but is " + entries.size());
    }
    RegionEntry entry = (RegionEntry) entries.iterator().next();
    DiskId id = ((DiskEntry) entry).getDiskId();
    Object obj = ((LocalRegion) region).getDiskRegion().get(id);
    assertEquals(Token.TOMBSTONE, obj);
  }

  /**
   * validate whether invalidate was done
   */
  private void validateInvalidate() {
    Collection entries = ((LocalRegion) region).entries.regionEntries();
    if (entries.size() != 1) {
      fail("expected size to be 1 but is " + entries.size());
    }
    RegionEntry entry = (RegionEntry) entries.iterator().next();
    DiskId id = ((DiskEntry) entry).getDiskId();
    Object obj = ((LocalRegion) region).getDiskRegion().get(id);
    if (!(obj.equals(Token.INVALID))) {
      fail(" incorrect invalidation");
    }
  }

  private long getCurrentFlushCount() {
    return ((LocalRegion) region).getDiskStore().getStats().getFlushes();
  }

  private void pauseFlush() {
    ((LocalRegion) region).getDiskRegion().pauseFlusherForTesting();
    flushCount = getCurrentFlushCount();
  }

  /**
   * force a flush on the region
   */
  private void forceFlush() {
    ((LocalRegion) region).getDiskRegion().flushForTesting();
  }

  /**
   * all the operations done here
   */
  private void allTest() {
    pauseFlush();
    createAndPut();
    forceFlush();
    validateModification();
    region.clear();

    pauseFlush();
    createAndDestroy();
    forceFlush();
    validateTombstone();
    region.clear();

    pauseFlush();
    createAndInvalidate();
    forceFlush();
    validateInvalidate();
    region.clear();

    pauseFlush();
    createDestroyCreate();
    forceFlush();
    validateModification();

    pauseFlush();
    putAndPut();
    forceFlush();
    validateModification();
    region.clear();

    pauseFlush();
    putAndDestroy();
    forceFlush();
    validateTombstone();
    region.clear();

    pauseFlush();
    putAndInvalidate();
    forceFlush();
    validateInvalidate();
    region.clear();
  }

  /**
   * test conflation for perist only
   */
  @Test
  public void testPersistOnlyConflation() throws Exception {
    createPersistOnly();
    allTest();
    closeDown();
  }

  /**
   * test conflation for overflow and persist
   */
  @Test
  public void testOverFlowAndPersistOnlyConflation() throws Exception {
    createOverflowAndPersist();
    allTest();
    closeDown();
  }
}
