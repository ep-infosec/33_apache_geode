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
package org.apache.geode.internal.cache.diskPerf;

import java.util.Arrays;

import org.junit.Test;

import org.apache.geode.LogWriter;
import org.apache.geode.cache.Region;
import org.apache.geode.internal.cache.CacheObserverAdapter;
import org.apache.geode.internal.cache.CacheObserverHolder;
import org.apache.geode.internal.cache.DiskRegionHelperFactory;
import org.apache.geode.internal.cache.DiskRegionProperties;
import org.apache.geode.internal.cache.DiskRegionTestingBase;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.eviction.EvictionCounters;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.WaitCriterion;

/**
 * 1) Performance of Get Operation for Entry faulting in from current Op Log 2) Performance of Get
 * operation for Entry faulting in from previous Op Log 3) Performance of Get operation for Entry
 * faulting in from H Tree
 *
 */
public class DiskRegionOverflowSyncRollingOpLogJUnitTest extends DiskRegionTestingBase {

  LogWriter log = null;

  private static final int ENTRY_SIZE = 1024;

  DiskRegionProperties diskProps = new DiskRegionProperties();

  @Override
  protected final void postSetUp() throws Exception {
    diskProps.setDiskDirs(dirs);
    log = ds.getLogWriter();
    diskProps.setRolling(true);
    diskProps.setMaxOplogSize(10485760l);
    diskProps.setCompactionThreshold(100);
    region = DiskRegionHelperFactory.getSyncOverFlowOnlyRegion(cache, diskProps);
  }

  @Test
  public void testGetPerfRollingOpog() {
    populateFirst0k_10Kbwrites();
    populateSecond10kto20kwrites();

  }

  public void populateFirst0k_10Kbwrites() {
    // RegionAttributes ra = region.getAttributes();

    // EvictionStatistics lruStats = getLRUStats(region);

    // put first 0-9999 entries
    // final String key = "K";
    final byte[] value = new byte[ENTRY_SIZE];
    Arrays.fill(value, (byte) 77);

    for (int i = 0; i < 10000; i++) {
      region.put("" + i, value);
    }

    // Now get 0-9999 entries
    long startTimeGet = System.currentTimeMillis();
    for (int i = 0; i < 10000; i++) {
      region.get("" + i);
    }
    long endTimeGet = System.currentTimeMillis();
    System.out.println(" done with getting 0-9999 entries fuatling in from current oplog");

    // Perf stats for get op
    float etGet = endTimeGet - startTimeGet;
    float etSecsGet = etGet / 1000f;
    float opPerSecGet = etSecsGet == 0 ? 0 : (10000 / (etGet / 1000f));
    float bytesPerSecGet = etSecsGet == 0 ? 0 : ((10000 * ENTRY_SIZE) / (etGet / 1000f));

    String statsGet =
        "etGet=" + etGet + "ms gets/sec=" + opPerSecGet + " bytes/sec=" + bytesPerSecGet;
    log.info(statsGet);
    System.out.println("Perf Stats of get which is fauting in from current Oplog :" + statsGet);

  }

  protected volatile boolean afterHavingCompacted = false;

  public void populateSecond10kto20kwrites() {
    afterHavingCompacted = false;
    // RegionAttributes ra = region.getAttributes();

    // EvictionStatistics lruStats = getLRUStats(region);

    setCacheObserverCallBack();

    CacheObserverHolder.setInstance(new CacheObserverAdapter() {
      @Override
      public void afterHavingCompacted() {
        afterHavingCompacted = true;
      }
    });

    // put another 10000-19999 entries
    // final String key = "K";
    final byte[] value = new byte[ENTRY_SIZE];
    Arrays.fill(value, (byte) 77);

    for (int i = 10000; i < 20000; i++) {
      region.put("" + i, value);
    }
    // Now get 10000-19999 which will fault in from second oplog
    long startTimeGet2 = System.currentTimeMillis();
    for (int i = 10000; i < 20000; i++) {
      region.get("" + i);
    }
    long endTimeGet2 = System.currentTimeMillis();
    System.out.println(" done with getting 10000-19999 which will fault in from second oplog");

    if (((LocalRegion) region).getDiskRegion().isBackup()) {
      WaitCriterion ev = new WaitCriterion() {
        @Override
        public boolean done() {
          return afterHavingCompacted;
        }

        @Override
        public String description() {
          return null;
        }
      };
      GeodeAwaitility.await().untilAsserted(ev);
    }

    // Now get 0-9999 entries
    long startTimeGet1 = System.currentTimeMillis();
    for (int i = 0; i < 10000; i++) {
      region.get("" + i);
    }
    long endTimeGet1 = System.currentTimeMillis();
    System.out.println(" done with getting 0-9999 entries from H-tree");

    region.close(); // closes disk file which will flush all buffers

    // Perf stats for get op (fauting in from H-tree)
    float etGet1 = endTimeGet1 - startTimeGet1;
    float etSecsGet1 = etGet1 / 1000f;
    float opPerSecGet1 = etSecsGet1 == 0 ? 0 : (10000 / (etGet1 / 1000f));
    float bytesPerSecGet1 = etSecsGet1 == 0 ? 0 : ((10000 * ENTRY_SIZE) / (etGet1 / 1000f));

    String statsGet1 =
        "etGet=" + etGet1 + "ms gets/sec=" + opPerSecGet1 + " bytes/sec=" + bytesPerSecGet1;
    log.info(statsGet1);
    System.out.println("Perf Stats of get which is fauting in from H-tree  :" + statsGet1);

    // Perf stats for get op (fauting in from second op log)
    float etGet2 = endTimeGet2 - startTimeGet2;
    float etSecsGet2 = etGet2 / 1000f;
    float opPerSecGet2 = etSecsGet2 == 0 ? 0 : (10000 / (etGet2 / 1000f));
    float bytesPerSecGet2 = etSecsGet2 == 0 ? 0 : ((10000 * ENTRY_SIZE) / (etGet2 / 1000f));

    String statsGet2 =
        "etGet=" + etGet2 + "ms gets/sec=" + opPerSecGet2 + " bytes/sec=" + bytesPerSecGet2;
    log.info(statsGet2);
    System.out.println("Perf Stats of get which is fauting in from Second OpLog  :" + statsGet2);

    unSetCacheObserverCallBack();

  }

  protected EvictionCounters getLRUStats(Region region1) {
    return ((LocalRegion) region1).getEvictionController().getCounters();

  }

}
