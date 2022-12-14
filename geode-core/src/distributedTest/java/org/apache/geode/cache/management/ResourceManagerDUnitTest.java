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
package org.apache.geode.cache.management;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.junit.Test;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.control.ResourceManager;
import org.apache.geode.cache.partition.PartitionMemberInfo;
import org.apache.geode.cache.partition.PartitionRegionHelper;
import org.apache.geode.cache.partition.PartitionRegionInfo;
import org.apache.geode.cache.query.QueryException;
import org.apache.geode.cache.query.QueryInvalidException;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.BucketAdvisor;
import org.apache.geode.internal.cache.BucketRegion;
import org.apache.geode.internal.cache.EntrySnapshot;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.PartitionedRegionDataStore;
import org.apache.geode.internal.cache.PartitionedRegionDataStore.CreateBucketResult;
import org.apache.geode.internal.cache.control.InternalResourceManager;
import org.apache.geode.internal.cache.control.InternalResourceManager.ResourceType;
import org.apache.geode.internal.cache.control.ResourceListener;
import org.apache.geode.internal.cache.partitioned.BecomePrimaryBucketMessage;
import org.apache.geode.internal.cache.partitioned.BecomePrimaryBucketMessage.BecomePrimaryBucketResponse;
import org.apache.geode.internal.cache.partitioned.Bucket;
import org.apache.geode.internal.cache.partitioned.DeposePrimaryBucketMessage;
import org.apache.geode.internal.cache.partitioned.DeposePrimaryBucketMessage.DeposePrimaryBucketResponse;
import org.apache.geode.internal.cache.partitioned.InternalPRInfo;
import org.apache.geode.internal.cache.partitioned.InternalPartitionDetails;
import org.apache.geode.internal.cache.partitioned.PRLoad;
import org.apache.geode.internal.cache.partitioned.RemoveBucketMessage;
import org.apache.geode.internal.cache.partitioned.RemoveBucketMessage.RemoveBucketResponse;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.SerializableCallable;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;

/**
 * Tests org.apache.geode.cache.control.ResourceManager.
 *
 * TODO: javadoc this test properly and cleanup the helper methods to be more flexible and
 * understandable
 */

public class ResourceManagerDUnitTest extends JUnit4CacheTestCase {
  private static final Logger logger = LogService.getLogger();

  private static final int SYSTEM_LISTENERS = 1;

  /**
   * Creates a cache in the controller and exercises all methods on the ResourceManager without
   * having any partitioned regions defined.
   */
  @Test
  public void testResourceManagerBasics() {
    Cache cache = getCache();

    // verify that getResourceManager works
    ResourceManager manager = cache.getResourceManager();
    assertNotNull(manager);

    // verify that getPartitionedRegionDetails returns empty set
    Set<PartitionRegionInfo> detailsSet = PartitionRegionHelper.getPartitionRegionInfo(cache);
    assertNotNull(detailsSet);
    assertEquals(Collections.emptySet(), detailsSet);

    ResourceListener listener = event -> {
    };

    InternalResourceManager internalManager = (InternalResourceManager) manager;
    // verify that addResourceListener works
    internalManager.addResourceListener(listener);
    Set<ResourceListener<?>> listeners =
        internalManager.getResourceListeners(ResourceType.HEAP_MEMORY);
    assertNotNull(listeners);
    assertEquals(1 + SYSTEM_LISTENERS, listeners.size());
    assertTrue(listeners.contains(listener));

    // verify that repeat adds result in only one entry of the listener
    internalManager.addResourceListener(ResourceType.HEAP_MEMORY, listener);
    listeners = internalManager.getResourceListeners(ResourceType.HEAP_MEMORY);
    assertEquals(1 + SYSTEM_LISTENERS, listeners.size());

    // verify that removeResourceListener works
    internalManager.removeResourceListener(listener);
    listeners = internalManager.getResourceListeners(ResourceType.HEAP_MEMORY);
    assertEquals(listeners.size(), SYSTEM_LISTENERS);

  }

  /**
   * Creates partitioned regions in multiple vms and fully exercises the getPartitionedRegionDetails
   * API on ResourceManager.
   */
  @Test
  public void testGetPartitionedRegionDetails() {
    // two regions
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0", getUniqueName() + "-PR-1"};
    // numBuckets config for the two regions
    final int[] numBuckets = new int[] {100, 90};
    // redundantCopies config for the two regions
    final int[] redundantCopies = new int[] {1, 0};

    // localMaxMemory config to use for three members
    final int[] localMaxMemory = new int[] {50, 100, 0};

    // bucketKeys to use for making three buckets in first PR
    final Integer[] bucketKeys =
        new Integer[] {0, 42, 76};

    assertEquals(0, bucketKeys[0].hashCode());
    assertEquals(42, bucketKeys[1].hashCode());
    assertEquals(76, bucketKeys[2].hashCode());

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory[0], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory[1], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(2), regionPath[0], localMaxMemory[2], numBuckets[0],
        redundantCopies[0]);

    createRegion(Host.getHost(0).getVM(0), regionPath[1], localMaxMemory[0], numBuckets[1],
        redundantCopies[1]);

    final byte[] value = new byte[1024 * 1024 * 2]; // 2 MB in size

    createBuckets(0, regionPath[0], bucketKeys, value);

    // identify the members and their config values
    final InternalDistributedMember[] members = new InternalDistributedMember[3];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    // test everything here
    for (int i = 0; i < localMaxMemory.length; i++) {
      final int vm = i;
      Host.getHost(0).getVM(vm).invoke(new SerializableRunnable() {
        @Override
        public void run() {
          Set<PartitionRegionInfo> detailsSet =
              PartitionRegionHelper.getPartitionRegionInfo(getCache());
          if (vm == 0) {
            assertEquals(2, detailsSet.size());
          } else {
            assertEquals(1, detailsSet.size());
          }

          // iterate over each PartitionedRegionDetails
          for (PartitionRegionInfo details : detailsSet) {
            // NOTE: getRegionPath() contains the Region.SEPARATOR + regionPath
            assertTrue("Unknown regionPath=" + details.getRegionPath(),
                details.getRegionPath().contains(regionPath[0])
                    || details.getRegionPath().contains(regionPath[1]));
            if (details.getRegionPath().contains(regionPath[0])) {
              assertEquals(numBuckets[0], details.getConfiguredBucketCount());
              assertEquals(0, details.getLowRedundancyBucketCount());
              assertEquals(redundantCopies[0], details.getConfiguredRedundantCopies());
              assertEquals(redundantCopies[0], details.getActualRedundantCopies());
              assertNull(details.getColocatedWith());
              Set<PartitionMemberInfo> memberDetails = details.getPartitionMemberInfo();
              assertNotNull(memberDetails);
              assertEquals(localMaxMemory.length - 1, memberDetails.size());

              // iterate over each PartitionMemberDetails (datastores only)
              for (PartitionMemberInfo mbrDetails : memberDetails) {
                assertNotNull(mbrDetails);
                DistributedMember mbr = mbrDetails.getDistributedMember();
                assertNotNull(mbr);
                int membersIdx = -1;
                for (int idx = 0; idx < members.length; idx++) {
                  if (mbr.equals(members[idx])) {
                    membersIdx = idx;
                  }
                }

                assertEquals(localMaxMemory[membersIdx] * (1024L * 1024L),
                    mbrDetails.getConfiguredMaxMemory());
                assertEquals(memberSizes[membersIdx], mbrDetails.getSize());
                assertEquals(memberBucketCounts[membersIdx], mbrDetails.getBucketCount());
                assertEquals(memberPrimaryCounts[membersIdx], mbrDetails.getPrimaryCount());

                if (mbr.equals(getSystem().getDistributedMember())) {
                  // PartitionMemberDetails represents the local member
                  PartitionedRegion pr =
                      (PartitionedRegion) getCache().getRegion(details.getRegionPath());
                  assertEquals(pr.getLocalMaxMemory() * (1024L * 1024L),
                      mbrDetails.getConfiguredMaxMemory());
                  PartitionedRegionDataStore ds = pr.getDataStore();
                  assertNotNull(ds);
                  assertEquals(getSize(ds), mbrDetails.getSize());
                  assertEquals(ds.getBucketsManaged(), mbrDetails.getBucketCount());
                  assertEquals(ds.getNumberOfPrimaryBucketsManaged(), mbrDetails.getPrimaryCount());
                }

              }

            } else {
              // found the other PR which has only one datastore and we know
              // this system memberId is the only entry in mbrDetails
              assertEquals(numBuckets[1], details.getConfiguredBucketCount());
              assertEquals(0, details.getLowRedundancyBucketCount());
              assertEquals(redundantCopies[1], details.getConfiguredRedundantCopies());
              assertEquals(redundantCopies[1], details.getActualRedundantCopies());
              assertNull(details.getColocatedWith());

              Set<PartitionMemberInfo> memberDetails = details.getPartitionMemberInfo();
              assertNotNull(memberDetails);
              assertEquals(1, memberDetails.size());
              PartitionMemberInfo mbrDetails = memberDetails.iterator().next();
              assertEquals(getSystem().getDistributedMember(), mbrDetails.getDistributedMember());

              PartitionedRegion pr =
                  (PartitionedRegion) getCache().getRegion(details.getRegionPath());
              assertEquals(pr.getLocalMaxMemory() * (1024L * 1024L),
                  mbrDetails.getConfiguredMaxMemory());
              PartitionedRegionDataStore ds = pr.getDataStore();
              assertNotNull(ds);
              assertEquals(getSize(ds), mbrDetails.getSize());
              assertEquals(ds.getBucketsManaged(), mbrDetails.getBucketCount());
              assertEquals(ds.getNumberOfPrimaryBucketsManaged(), mbrDetails.getPrimaryCount());

            }
          }
        }
      });
    }

    destroyRegions(0, regionPath);
  }

  /**
   * Creates partitioned regions in multiple vms and fully exercises the internal-only
   * getInternalPRDetails API on ResourceManager.
   */
  @Test
  public void testGetInternalPRDetails() {
    // two regions
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0", getUniqueName() + "-PR-1"};
    // numBuckets config for the two regions
    final int[] numBuckets = new int[] {100, 90};
    // redundantCopies config for the two regions
    final int[] redundantCopies = new int[] {1, 0};

    // localMaxMemory config to use for three members
    final int[] localMaxMemory = new int[] {50, 100, 0};

    // bucketKeys to use for making three bckets in first PR
    final Integer[] bucketKeys =
        new Integer[] {0, 42, 76};

    assertEquals(0, bucketKeys[0].hashCode());
    assertEquals(42, bucketKeys[1].hashCode());
    assertEquals(76, bucketKeys[2].hashCode());

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory[0], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory[1], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(2), regionPath[0], localMaxMemory[2], numBuckets[0],
        redundantCopies[0]);

    createRegion(Host.getHost(0).getVM(0), regionPath[1], localMaxMemory[0], numBuckets[1],
        redundantCopies[1]);

    final byte[] value = new byte[1024 * 1024 * 2]; // 2 MB in size

    createBuckets(0, regionPath[0], bucketKeys, value);

    // identify the members and their config values
    final InternalDistributedMember[] members = new InternalDistributedMember[3];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];
    for (int i = 0; i < members.length; i++) {
      final int vm = i;
      members[vm] =
          (InternalDistributedMember) Host.getHost(0).getVM(vm).invoke(new SerializableCallable() {
            @Override
            public Object call() {
              return getSystem().getDistributedMember();
            }
          });
      memberSizes[vm] = (Long) Host.getHost(0).getVM(vm).invoke(new SerializableCallable() {
        @Override
        public Object call() {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);
          PartitionedRegionDataStore ds = pr.getDataStore();
          if (ds == null) {
            return 0L;
          } else {
            return getSize(ds);
          }
        }
      });
      memberBucketCounts[vm] =
          (Integer) Host.getHost(0).getVM(vm).invoke(new SerializableCallable() {
            @Override
            public Object call() {
              PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);
              PartitionedRegionDataStore ds = pr.getDataStore();
              if (ds == null) {
                return 0;
              } else {
                return (int) ds.getBucketsManaged();
              }
            }
          });
      memberPrimaryCounts[vm] =
          (Integer) Host.getHost(0).getVM(vm).invoke(new SerializableCallable() {
            @Override
            public Object call() {
              PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);
              PartitionedRegionDataStore ds = pr.getDataStore();
              if (ds == null) {
                return 0;
              } else {
                return ds.getNumberOfPrimaryBucketsManaged();
              }
            }
          });
    }

    // test everything here
    for (int i = 0; i < localMaxMemory.length; i++) {
      final int vm = i;
      Host.getHost(0).getVM(vm).invoke(new SerializableRunnable() {
        @Override
        public void run() {
          Set<InternalPRInfo> detailsSet = new HashSet<>();
          GemFireCacheImpl cache = (GemFireCacheImpl) getCache();
          for (PartitionedRegion pr : cache.getPartitionedRegions()) {
            InternalPRInfo info = pr.getRedundancyProvider().buildPartitionedRegionInfo(true,
                cache.getInternalResourceManager().getLoadProbe());
            detailsSet.add(info);
          }
          if (vm == 0) {
            assertEquals(2, detailsSet.size());
          } else {
            assertEquals(1, detailsSet.size());
          }

          // iterate over each InternalPRDetails
          for (InternalPRInfo details : detailsSet) {
            // NOTE: getRegionPath() contains the Region.SEPARATOR + regionPath
            assertTrue("Unknown regionPath=" + details.getRegionPath(),
                details.getRegionPath().contains(regionPath[0])
                    || details.getRegionPath().contains(regionPath[1]));
            if (details.getRegionPath().contains(regionPath[0])) {
              assertEquals(numBuckets[0], details.getConfiguredBucketCount());
              assertEquals(0, details.getLowRedundancyBucketCount());
              assertEquals(redundantCopies[0], details.getConfiguredRedundantCopies());
              assertEquals(redundantCopies[0], details.getActualRedundantCopies());
              assertNull(details.getColocatedWith());
              Set<InternalPartitionDetails> memberDetails = details.getInternalPartitionDetails();
              assertNotNull(memberDetails);
              assertEquals(localMaxMemory.length - 1, memberDetails.size());

              // iterate over each InternalPartitionDetails (datastores only)
              for (InternalPartitionDetails mbrDetails : memberDetails) {
                assertNotNull(mbrDetails);
                DistributedMember mbr = mbrDetails.getDistributedMember();
                assertNotNull(mbr);
                int membersIdx = -1;
                for (int idx = 0; idx < members.length; idx++) {
                  if (mbr.equals(members[idx])) {
                    membersIdx = idx;
                  }
                }

                assertEquals(localMaxMemory[membersIdx] * 1024 * 1024,
                    mbrDetails.getConfiguredMaxMemory());
                assertEquals(memberSizes[membersIdx], mbrDetails.getSize());
                assertEquals(memberBucketCounts[membersIdx], mbrDetails.getBucketCount());
                assertEquals(memberPrimaryCounts[membersIdx], mbrDetails.getPrimaryCount());

                PRLoad load = mbrDetails.getPRLoad();
                assertNotNull(load);
                assertEquals((float) localMaxMemory[membersIdx], load.getWeight(), 0);

                int totalBucketBytes = 0;
                int primaryCount = 0;
                for (int bid = 0; bid < numBuckets[0]; bid++) {
                  long bucketBytes = mbrDetails.getBucketSize(bid);
                  assertTrue(bucketBytes >= 0);
                  totalBucketBytes += bucketBytes;

                  // validate against the PRLoad
                  assertEquals((float) bucketBytes, load.getReadLoad(bid), 0);
                  if (load.getWriteLoad(bid) > 0) { // found a primary
                    primaryCount++;
                  }
                }
                // assertIndexDetailsEquals(memberSizes[membersIdx] * (1024* 1024),
                // totalBucketBytes);
                assertEquals(memberPrimaryCounts[membersIdx], primaryCount);

                if (mbr.equals(getSystem().getDistributedMember())) {
                  // PartitionMemberDetails represents the local member
                  PartitionedRegion pr =
                      (PartitionedRegion) getCache().getRegion(details.getRegionPath());
                  assertEquals(pr.getLocalMaxMemory() * (1024L * 1024L),
                      mbrDetails.getConfiguredMaxMemory());
                  PartitionedRegionDataStore ds = pr.getDataStore();
                  assertNotNull(ds);
                  assertEquals(getSize(ds), mbrDetails.getSize());
                  assertEquals(ds.getBucketsManaged(), mbrDetails.getBucketCount());
                  assertEquals(ds.getNumberOfPrimaryBucketsManaged(), mbrDetails.getPrimaryCount());
                }

              }

            } else {
              // found the other PR which has only one datastore and we know
              // this system memberId is the only entry in mbrDetails
              assertEquals(numBuckets[1], details.getConfiguredBucketCount());
              assertEquals(0, details.getLowRedundancyBucketCount());
              assertEquals(redundantCopies[1], details.getConfiguredRedundantCopies());
              assertEquals(redundantCopies[1], details.getActualRedundantCopies());
              assertNull(details.getColocatedWith());

              Set<PartitionMemberInfo> memberDetails = details.getPartitionMemberInfo();
              assertNotNull(memberDetails);
              assertEquals(1, memberDetails.size());
              PartitionMemberInfo mbrDetails = memberDetails.iterator().next();
              assertEquals(getSystem().getDistributedMember(), mbrDetails.getDistributedMember());

              PartitionedRegion pr =
                  (PartitionedRegion) getCache().getRegion(details.getRegionPath());
              assertEquals(pr.getLocalMaxMemory() * (1024L * 1024L),
                  mbrDetails.getConfiguredMaxMemory());
              PartitionedRegionDataStore ds = pr.getDataStore();
              assertNotNull(ds);
              assertEquals(getSize(ds), mbrDetails.getSize());
              assertEquals(ds.getBucketsManaged(), mbrDetails.getBucketCount());
              assertEquals(ds.getNumberOfPrimaryBucketsManaged(), mbrDetails.getPrimaryCount());

            }
          }
        }
      });
    }

    destroyRegions(0, regionPath);
  }

  private void createRegion(final VM whichVm, final String regionPath, final int localMaxMemory,
      final int numBuckets, final int redundantCopies, final String colocatedWith) {
    whichVm.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        {
          AttributesFactory factory = new AttributesFactory();
          factory.setPartitionAttributes(new PartitionAttributesFactory()
              .setLocalMaxMemory(localMaxMemory).setTotalNumBuckets(numBuckets)
              .setRedundantCopies(redundantCopies).setColocatedWith(colocatedWith).create());

          RegionAttributes attrs = factory.create();
          Region pr = createRootRegion(regionPath, attrs);
          assertNotNull(pr);
        }
      }
    });
  }

  private void createRegion(final VM whichVm, final String regionPath, final int localMaxMemory,
      final int numBuckets, final int redundantCopies) {
    createRegion(whichVm, regionPath, localMaxMemory, numBuckets, redundantCopies, null);
  }

  private void createBucket(final int vm, final String regionPath, final Integer bucketKey,
      final byte[] value) {
    createBuckets(vm, regionPath, new Integer[] {bucketKey}, value);
  }

  private void createBuckets(final int vm, final String regionPath, final Integer[] bucketKeys,
      final Object value) {
    // do some puts to create buckets
    performPuts(vm, regionPath, bucketKeys, value);
  }

  private void performPuts(final int vm, final String regionPath, final Integer[] bucketKeys,
      final Object value) {
    Host.getHost(0).getVM(vm).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        Region pr = getCache().getRegion(regionPath);
        for (final Integer bucketKey : bucketKeys) {
          pr.put(bucketKey, value);
        }
      }
    });
  }

  private void fillValidationArrays(final InternalDistributedMember[] members,
      final long[] memberSizes, final int[] memberBucketCounts, final int[] memberPrimaryCounts,
      final String regionPath) {
    for (int i = 0; i < members.length; i++) {
      final int vm = i;
      members[vm] =
          (InternalDistributedMember) Host.getHost(0).getVM(vm).invoke(new SerializableCallable() {
            @Override
            public Object call() {
              return getSystem().getDistributedMember();
            }
          });
      memberSizes[vm] = (Long) Host.getHost(0).getVM(vm).invoke(new SerializableCallable() {
        @Override
        public Object call() {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath);
          PartitionedRegionDataStore ds = pr.getDataStore();
          if (ds == null) {
            return 0L;
          } else {
            return getSize(ds);
          }
        }
      });
      memberBucketCounts[vm] =
          (Integer) Host.getHost(0).getVM(vm).invoke(new SerializableCallable() {
            @Override
            public Object call() {
              PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath);
              PartitionedRegionDataStore ds = pr.getDataStore();
              if (ds == null) {
                return 0;
              } else {
                return (int) ds.getBucketsManaged();
              }
            }
          });
      memberPrimaryCounts[vm] =
          (Integer) Host.getHost(0).getVM(vm).invoke(new SerializableCallable() {
            @Override
            public Object call() {
              PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath);
              PartitionedRegionDataStore ds = pr.getDataStore();
              if (ds == null) {
                return 0;
              } else {
                return ds.getNumberOfPrimaryBucketsManaged();
              }
            }
          });
    }
  }

  private long getSize(PartitionedRegionDataStore ds) {
    long size = 0;
    int totalNumBuckets = ds.getPartitionedRegion().getPartitionAttributes().getTotalNumBuckets();
    for (int i = 0; i < totalNumBuckets; i++) {
      size += ds.getBucketSize(i);
    }
    return size;
  }

  private void destroyRegions(final int vm, final String[] regionPath) {
    // destroy both PRs (note that vm 0 created both)
    Host.getHost(0).getVM(vm).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (final String s : regionPath) {
          Region pr = getCache().getRegion(s);
          pr.destroyRegion();
        }
      }
    });
  }

  @Test
  public void testDeposePrimaryBucketMessage() {
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0"};
    final int[] numBuckets = new int[] {1};
    final int[] redundantCopies = new int[] {1};

    // localMaxMemory config to use for two members
    final int[] localMaxMemory = new int[] {100, 100};

    // bucketKeys to use for making one bucket
    final Integer[] bucketKeys = new Integer[] {0};

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory[0], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory[1], numBuckets[0],
        redundantCopies[0]);

    final byte[] value = new byte[1]; // 2 MB in size

    createBucket(0, regionPath[0], bucketKeys[0], value);

    // identify the members and their config values
    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    boolean deposedPrimary =
        (Boolean) Host.getHost(0).getVM(otherVM).invoke(new SerializableCallable() {
          @Override
          public Object call() {
            PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

            InternalDistributedMember primaryMember =
                pr.getRegionAdvisor().getBucketAdvisor(0).getPrimary();

            DeposePrimaryBucketResponse response =
                DeposePrimaryBucketMessage.send(primaryMember, pr, 0);
            if (response != null) {
              response.waitForRepliesUninterruptibly();
              return true;
            } else {
              return Boolean.FALSE;
            }
          }
        });

    assertTrue(deposedPrimary);
  }

  @Test
  public void testBecomePrimaryBucketMessage() {
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0"};
    final int[] numBuckets = new int[] {1};
    final int[] redundantCopies = new int[] {1};

    // localMaxMemory config to use for two members
    final int[] localMaxMemory = new int[] {100, 100, 0};

    // bucketKeys to use for making one bucket
    final Integer[] bucketKeys = new Integer[] {0};

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory[0], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory[1], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(2), regionPath[0], localMaxMemory[2], numBuckets[0],
        redundantCopies[0]);

    final byte[] value = new byte[1]; // 2 MB in size

    createBucket(0, regionPath[0], bucketKeys[0], value);

    // identify the members and their config values
    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    int accessorVM = 2;
    assertTrue(accessorVM != primaryVM && accessorVM != otherVM);

    final int finalOtherVM = otherVM;

    boolean becamePrimary =
        (Boolean) Host.getHost(0).getVM(accessorVM).invoke(new SerializableCallable() {
          @Override
          public Object call() {
            PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

            BecomePrimaryBucketResponse response =
                BecomePrimaryBucketMessage.send(members[finalOtherVM], pr, 0, false);
            if (response != null) {
              return response.waitForResponse();
            } else {
              return Boolean.FALSE;
            }
          }
        });

    assertTrue(becamePrimary);

    // do a put from all three members to ensure primary is identified
    for (int i = 0; i < localMaxMemory.length; i++) {
      final int vm = i;
      performPuts(vm, regionPath[0], bucketKeys, value);
    }

    // use BucketAdvisor on all three members to assert that otherVM is now the primary
    for (int i = 0; i < localMaxMemory.length; i++) {
      final int vm = i;
      Host.getHost(0).getVM(vm).invoke(new SerializableRunnable() {
        @Override
        public void run() {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);
          BucketAdvisor advisor = pr.getRegionAdvisor().getBucketAdvisor(0);
          assertEquals(members[finalOtherVM], advisor.getPrimary());
        }
      });
    }
  }

  private interface OpDuringBucketRemove extends java.io.Serializable {
    void runit(PartitionedRegion pr, Object key, Object value);
  }

  private void doOpDuringBucketRemove(final OpDuringBucketRemove op) {
    final String[] regionPath = new String[] {getUniqueName() + "_PR_0"};
    final int[] numBuckets = new int[] {1};
    final int[] redundantCopies = new int[] {1};

    // localMaxMemory config to use for three members
    final int[] localMaxMemory = new int[] {100, 100};

    final Integer KEY = 69;
    // bucketKeys to use for making one bucket
    final Integer[] bucketKeys = new Integer[] {KEY};

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory[0], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory[1], numBuckets[0],
        redundantCopies[0]);

    final String VALUE = "doOpDuringBucketRemove.VALUE";

    createBuckets(0, regionPath[0], bucketKeys, VALUE);

    // identify the members and their config values
    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    final int finalOtherVM = otherVM;

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);
        Bucket bucket = pr.getRegionAdvisor().getBucket(0);
        assertTrue("Target member is not hosting bucket to remove", bucket.isHosting());
        assertNotNull("Bucket is null on target member", bucket);
        assertNotNull("BucketRegion is null on target member",
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion());
        EntrySnapshot re = (EntrySnapshot) pr.getEntry(KEY);
        assertEquals(true, re.wasInitiallyLocal());
        assertEquals(false, re.isLocal());
        assertEquals(VALUE, re.getValue());
      }
    });
    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        final PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);
        final boolean[] invoked = new boolean[] {false};
        final PartitionedRegionDataStore prds = pr.getDataStore();
        prds.setBucketReadHook(() -> {
          invoked[0] = true;
          logger.debug("In bucketReadHook");
          assertTrue(prds.removeBucket(0, false));
        });
        try {
          Bucket bucket = pr.getRegionAdvisor().getBucket(0);
          assertTrue("Target member is not hosting bucket to remove", bucket.isHosting());
          assertNotNull("Bucket is null on target member", bucket);
          assertNotNull("BucketRegion is null on target member",
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion());
          assertEquals(false, invoked[0]);
          op.runit(pr, KEY, VALUE);
          assertEquals(true, invoked[0]);
        } finally {
          prds.setBucketReadHook(null);
        }
      }
    });

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

        Bucket bucket = pr.getRegionAdvisor().getBucket(0);
        BucketRegion bucketRegion =
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

        assertFalse("Target member is still hosting removed bucket. Bucket:" + bucket
            + " Advisor state:" + bucket.getBucketAdvisor(), bucket.isHosting());

        assertNull(bucketRegion);
        // assertTrue(bucketRegion == null || bucketRegion.isDestroyed());
        EntrySnapshot re = (EntrySnapshot) pr.getEntry(KEY);
        assertEquals(false, re.wasInitiallyLocal());
        assertEquals(false, re.isLocal());
        assertEquals(VALUE, re.getValue());
      }
    });
  }

  @Test
  public void testRemoveDuringGetEntry() {
    doOpDuringBucketRemove((OpDuringBucketRemove) (pr, key, value) -> {
      Region.Entry re = pr.getEntry(key);
      assertNotNull("region entry should have existed", re);
      assertEquals(false, re.isLocal());
      assertEquals(value, re.getValue());
    });
  }

  @Test
  public void testRemoveDuringGet() {
    doOpDuringBucketRemove(
        (OpDuringBucketRemove) (pr, key, value) -> assertEquals(value, pr.get(key)));
  }

  @Test
  public void testRemoveDuringContainsKey() {
    doOpDuringBucketRemove(
        (OpDuringBucketRemove) (pr, key, value) -> assertEquals(true, pr.containsKey(key)));
  }

  @Test
  public void testRemoveDuringContainsValueForKey() {
    doOpDuringBucketRemove(
        (OpDuringBucketRemove) (pr, key, value) -> assertEquals(true, pr.containsValueForKey(key)));
  }

  @Test
  public void testRemoveDuringKeySet() {
    doOpDuringBucketRemove(
        (OpDuringBucketRemove) (pr, key, value) -> assertEquals(Collections.singleton(key),
            pr.keySet()));
  }

  @Test
  public void testRemoveDuringValues() {
    doOpDuringBucketRemove(
        (OpDuringBucketRemove) (pr, key, value) -> assertEquals(Collections.singleton(value),
            pr.values()));
  }

  @Test
  public void testRemoveDuringEntrySet() {
    doOpDuringBucketRemove((OpDuringBucketRemove) (pr, key, value) -> {
      Iterator it = pr.entrySet(false).iterator();
      Region.Entry re = (Region.Entry) it.next();
      assertEquals(value, re.getValue());
      assertEquals(key, re.getKey());
      assertEquals(false, it.hasNext());
    });
  }

  @Test
  public void testRemoveDuringQuery() {
    doOpDuringBucketRemove((OpDuringBucketRemove) (pr, key, value) -> {
      try {
        SelectResults sr = pr.query("toString()='doOpDuringBucketRemove.VALUE'"); // fetch
                                                                                  // everything
        assertEquals(1, sr.size());
        assertEquals(value, sr.iterator().next());
      } catch (QueryException ex) {
        Assert.fail("didn't expect a QueryException", ex);
      } catch (QueryInvalidException ex2) {
        Assert.fail("didn't expect QueryInvalidException", ex2);
      }
    });
  }

  @Test
  public void testRemoveBucketMessage() {
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0"};
    final int[] numBuckets = new int[] {1};
    final int[] redundantCopies = new int[] {1};

    // localMaxMemory config to use for 2 members
    final int[] localMaxMemory = new int[] {100, 100};

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory[0], numBuckets[0],
        redundantCopies[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory[1], numBuckets[0],
        redundantCopies[0]);

    final Integer bucketKey = 0;
    final byte[] value = new byte[1]; // 2 MB in size

    createBucket(0, regionPath[0], bucketKey, value);

    // identify the members and their config values
    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    final int finalOtherVM = otherVM;

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);
        Bucket bucket = pr.getRegionAdvisor().getBucket(0);
        assertTrue("Target member is not hosting bucket to remove", bucket.isHosting());
        assertNotNull("Bucket is null on target member", bucket);
        assertNotNull("BucketRegion is null on target member",
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion());
      }
    });

    boolean sentRemoveBucket =
        (Boolean) Host.getHost(0).getVM(primaryVM).invoke(new SerializableCallable() {
          @Override
          public Object call() {
            InternalDistributedMember recipient = members[finalOtherVM];

            PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

            RemoveBucketResponse response = RemoveBucketMessage.send(recipient, pr, 0, false);
            if (response != null) {
              response.waitForRepliesUninterruptibly();
              return true;
            } else {
              return Boolean.FALSE;
            }
          }
        });
    assertTrue("Failed to get reply to RemoveBucketMessage", sentRemoveBucket);

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

        Bucket bucket = pr.getRegionAdvisor().getBucket(0);
        BucketRegion bucketRegion =
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

        assertFalse("Target member is still hosting removed bucket", bucket.isHosting());

        assertNull("BucketRegion is not null on target member", bucketRegion);
      }
    });
  }

  /**
   * Creates a chain of three colocated PRs and then calls removeBucket to make sure that all
   * colocated buckets are removed together.
   */
  @Test
  public void testRemoveColocatedBuckets() {
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0", getUniqueName() + "-PR-1",
        getUniqueName() + "-PR-2"};

    final int numBuckets = 1;
    final int redundantCopies = 1;
    final int localMaxMemory = 100;

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);

    createRegion(Host.getHost(0).getVM(0), regionPath[1], localMaxMemory, numBuckets,
        redundantCopies, regionPath[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[1], localMaxMemory, numBuckets,
        redundantCopies, regionPath[0]);

    createRegion(Host.getHost(0).getVM(0), regionPath[2], localMaxMemory, numBuckets,
        redundantCopies, regionPath[1]);
    createRegion(Host.getHost(0).getVM(1), regionPath[2], localMaxMemory, numBuckets,
        redundantCopies, regionPath[1]);

    final Integer bucketKey = 0;
    final byte[] value = new byte[1];

    createBucket(0, regionPath[0], bucketKey, value);
    createBucket(0, regionPath[1], bucketKey, value);
    createBucket(0, regionPath[2], bucketKey, value);

    // identify the members and their config values
    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    final int finalOtherVM = otherVM;

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (final String s : regionPath) {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(s);
          Bucket bucket = pr.getRegionAdvisor().getBucket(0);
          assertTrue("Target member is not hosting bucket to remove for " + s,
              bucket.isHosting());
          assertNotNull("Bucket is null on target member for " + s, bucket);
          assertNotNull("BucketRegion is null on target member for " + s,
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion());
        }
      }
    });

    boolean sentRemoveBucket =
        (Boolean) Host.getHost(0).getVM(primaryVM).invoke(new SerializableCallable() {
          @Override
          public Object call() {
            InternalDistributedMember recipient = members[finalOtherVM];

            PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

            RemoveBucketResponse response = RemoveBucketMessage.send(recipient, pr, 0, false);
            if (response != null) {
              response.waitForRepliesUninterruptibly();
              return true;
            } else {
              return Boolean.FALSE;
            }
          }
        });
    assertTrue("Failed to get reply to RemoveBucketMessage", sentRemoveBucket);

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (final String s : regionPath) {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(s);

          Bucket bucket = pr.getRegionAdvisor().getBucket(0);
          BucketRegion bucketRegion =
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

          assertFalse("Target member is still hosting removed bucket for " + s,
              bucket.isHosting());

          assertNull("BucketRegion is not null on target member for " + s,
              bucketRegion);
        }
      }
    });
  }

  /**
   * Creates a bucket on two members. Then brings up a third member and creates an extra redundant
   * copy of the bucket on it.
   */
  @Test
  public void testCreateRedundantBucket() {
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0"};

    final int numBuckets = 1;
    final int redundantCopies = 1;
    final int localMaxMemory = 100;

    // create the PartitionedRegion on the first two members

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);

    // create the bucket on the first two members

    final Integer bucketKey = 0;
    final byte[] value = new byte[1]; // 2 MB in size

    createBucket(0, regionPath[0], bucketKey, value);

    // identify the primaryVM and otherVM

    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    final int finalOtherVM = otherVM;

    // make sure bucket exists on otherVM

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

        Bucket bucket = pr.getRegionAdvisor().getBucket(0);

        assertNotNull("Bucket is null on SRC member", bucket);

        BucketRegion bucketRegion =
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

        assertTrue("SRC member is not hosting bucket", bucket.isHosting());
        assertNotNull("BucketRegion is null on SRC member", bucketRegion);

        int redundancy = bucket.getBucketAdvisor().getBucketRedundancy();
        assertEquals("SRC member reports redundancy " + redundancy, redundantCopies, redundancy);
      }
    });

    // create newVM to create extra redundant bucket on

    final int finalNewVM = 2;
    createRegion(Host.getHost(0).getVM(finalNewVM), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);

    // create an extra redundant bucket on finalNewVM

    Host.getHost(0).getVM(finalNewVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

        assertEquals(CreateBucketResult.CREATED,
            pr.getDataStore().createRedundantBucket(0, false, new InternalDistributedMember()));

        Bucket bucket = pr.getRegionAdvisor().getBucket(0);

        assertNotNull("Bucket is null on DST member", bucket);

        BucketRegion bucketRegion =
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

        assertTrue("DST member is not hosting bucket", bucket.isHosting());
        assertNotNull("BucketRegion is null on DST member", bucketRegion);
        assertEquals(redundantCopies + 1, bucket.getBucketAdvisor().getBucketRedundancy());
      }
    });
  }

  /**
   * Creates colocated buckets on two members. Then brings up a third member and creates an extra
   * redundant copy of the buckets on it.
   */
  @Test
  public void testCreateRedundantColocatedBuckets() {
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0", getUniqueName() + "-PR-1",
        getUniqueName() + "-PR-2"};

    final int numBuckets = 1;
    final int redundantCopies = 1;
    final int localMaxMemory = 100;

    // create the PartitionedRegion on the first two members

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);

    createRegion(Host.getHost(0).getVM(0), regionPath[1], localMaxMemory, numBuckets,
        redundantCopies, regionPath[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[1], localMaxMemory, numBuckets,
        redundantCopies, regionPath[0]);

    createRegion(Host.getHost(0).getVM(0), regionPath[2], localMaxMemory, numBuckets,
        redundantCopies, regionPath[1]);
    createRegion(Host.getHost(0).getVM(1), regionPath[2], localMaxMemory, numBuckets,
        redundantCopies, regionPath[1]);

    // create the bucket on the first two members

    final Integer bucketKey = 0;
    final byte[] value = new byte[1];

    createBucket(0, regionPath[0], bucketKey, value);
    createBucket(0, regionPath[1], bucketKey, value);
    createBucket(0, regionPath[2], bucketKey, value);

    // identify the primaryVM and otherVM

    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    final int finalOtherVM = otherVM;

    // make sure colocated buckets exists on otherVM

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (final String s : regionPath) {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(s);

          Bucket bucket = pr.getRegionAdvisor().getBucket(0);

          assertNotNull("Bucket is null on SRC member for " + s, bucket);

          BucketRegion bucketRegion =
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

          assertTrue("SRC member is not hosting bucket for " + s, bucket.isHosting());
          assertNotNull("BucketRegion is null on SRC member for " + s, bucketRegion);

          int redundancy = bucket.getBucketAdvisor().getBucketRedundancy();
          assertEquals("SRC member reports redundancy " + redundancy + " for " + s,
              redundantCopies, redundancy);
        }
      }
    });

    // create newVM to create extra redundant buckets on

    final int finalNewVM = 2;
    createRegion(Host.getHost(0).getVM(finalNewVM), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);
    createRegion(Host.getHost(0).getVM(finalNewVM), regionPath[1], localMaxMemory, numBuckets,
        redundantCopies, regionPath[0]);
    createRegion(Host.getHost(0).getVM(finalNewVM), regionPath[2], localMaxMemory, numBuckets,
        redundantCopies, regionPath[1]);

    // create extra redundant buckets on finalNewVM

    Host.getHost(0).getVM(finalNewVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (int i = 0; i < regionPath.length; i++) {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[i]);

          if (i == 0) {
            // only call createRedundantBucket on leader PR
            pr.getDataStore().createRedundantBucket(0, false, new InternalDistributedMember());
          }

          Bucket bucket = pr.getRegionAdvisor().getBucket(0);

          assertNotNull("Bucket is null on DST member", bucket);

          BucketRegion bucketRegion =
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

          assertTrue("DST member is not hosting bucket", bucket.isHosting());
          assertNotNull("BucketRegion is null on DST member", bucketRegion);
          assertEquals(redundantCopies + 1, bucket.getBucketAdvisor().getBucketRedundancy());
        }
      }
    });
  }

  /**
   * Creates a bucket on two members. Then brings up a third member and moves the non-primary bucket
   * to it.
   */
  @Test
  public void testMoveBucket() {
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0"};

    final int numBuckets = 1;
    final int redundantCopies = 1;
    final int localMaxMemory = 100;

    // create the PartitionedRegion on the first two members

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);

    // create the bucket on the first two members

    final Integer bucketKey = 0;
    final byte[] value = new byte[1]; // 2 MB in size

    createBucket(0, regionPath[0], bucketKey, value);

    // identify the primaryVM and otherVM

    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    final int finalOtherVM = otherVM;

    // make sure bucket exists on otherVM

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

        Bucket bucket = pr.getRegionAdvisor().getBucket(0);

        assertNotNull("Bucket is null on SRC member", bucket);

        BucketRegion bucketRegion =
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

        assertTrue("SRC member is not hosting bucket", bucket.isHosting());
        assertNotNull("BucketRegion is null on SRC member", bucketRegion);

        int redundancy = bucket.getBucketAdvisor().getBucketRedundancy();
        assertEquals("SRC member reports redundancy " + redundancy, redundantCopies, redundancy);
      }
    });

    // create newVM to move bucket to

    final int finalNewVM = 2;
    createRegion(Host.getHost(0).getVM(finalNewVM), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);

    // initiate moveBucket to move from otherVM to newVM

    boolean movedBucket =
        (Boolean) Host.getHost(0).getVM(finalNewVM).invoke(new SerializableCallable() {
          @Override
          public Object call() {
            InternalDistributedMember recipient = members[finalOtherVM];

            PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

            return pr.getDataStore().moveBucket(0, recipient, true);
          }
        });
    assertTrue("Failed in call to moveBucket", movedBucket);

    // validate that otherVM no longer hosts bucket

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

        Bucket bucket = pr.getRegionAdvisor().getBucket(0);

        assertFalse("SRC member is still hosting moved bucket", bucket.isHosting());

        BucketRegion bucketRegion =
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

        assertNull("BucketRegion is not null on SRC member", bucketRegion);
      }
    });

    // validate that newVM now hosts bucket

    Host.getHost(0).getVM(finalNewVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

        Bucket bucket = pr.getRegionAdvisor().getBucket(0);

        assertNotNull("Bucket is null on DST member", bucket);

        BucketRegion bucketRegion =
            bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

        assertTrue("DST member is not hosting bucket", bucket.isHosting());
        assertNotNull("BucketRegion is null on DST member", bucketRegion);
        assertEquals(redundantCopies, bucket.getBucketAdvisor().getBucketRedundancy());
      }
    });
  }

  /**
   * Creates colocated buckets on two members. Then brings up a third member and moves the
   * non-primary colocated buckets to it.
   */
  @Test
  public void testMoveColocatedBuckets() {
    final String[] regionPath = new String[] {getUniqueName() + "-PR-0", getUniqueName() + "-PR-1",
        getUniqueName() + "-PR-2"};

    final int numBuckets = 1;
    final int redundantCopies = 1;
    final int localMaxMemory = 100;

    // create the PartitionedRegion on the first two members

    createRegion(Host.getHost(0).getVM(0), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);
    createRegion(Host.getHost(0).getVM(1), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);

    createRegion(Host.getHost(0).getVM(0), regionPath[1], localMaxMemory, numBuckets,
        redundantCopies, regionPath[0]);
    createRegion(Host.getHost(0).getVM(1), regionPath[1], localMaxMemory, numBuckets,
        redundantCopies, regionPath[0]);

    createRegion(Host.getHost(0).getVM(0), regionPath[2], localMaxMemory, numBuckets,
        redundantCopies, regionPath[1]);
    createRegion(Host.getHost(0).getVM(1), regionPath[2], localMaxMemory, numBuckets,
        redundantCopies, regionPath[1]);

    // create the bucket on the first two members

    final Integer bucketKey = 0;
    final byte[] value = new byte[1];

    createBucket(0, regionPath[0], bucketKey, value);
    createBucket(0, regionPath[1], bucketKey, value);
    createBucket(0, regionPath[2], bucketKey, value);

    // identify the primaryVM and otherVM

    final InternalDistributedMember[] members = new InternalDistributedMember[2];
    final long[] memberSizes = new long[members.length];
    final int[] memberBucketCounts = new int[members.length];
    final int[] memberPrimaryCounts = new int[members.length];

    fillValidationArrays(members, memberSizes, memberBucketCounts, memberPrimaryCounts,
        regionPath[0]);

    int primaryVM = -1;
    int otherVM = -1;
    for (int i = 0; i < memberPrimaryCounts.length; i++) {
      if (memberPrimaryCounts[i] == 0) {
        otherVM = i;
      } else if (memberPrimaryCounts[i] == 1) {
        // found the primary
        primaryVM = i;
      }
    }

    assertTrue(primaryVM > -1);
    assertTrue(otherVM > -1);
    assertTrue(primaryVM != otherVM);

    final int finalOtherVM = otherVM;

    // make sure colocated buckets exists on otherVM

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (final String s : regionPath) {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(s);

          Bucket bucket = pr.getRegionAdvisor().getBucket(0);

          assertNotNull("Bucket is null on SRC member", bucket);

          BucketRegion bucketRegion =
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

          assertTrue("SRC member is not hosting bucket", bucket.isHosting());
          assertNotNull("BucketRegion is null on SRC member", bucketRegion);

          int redundancy = bucket.getBucketAdvisor().getBucketRedundancy();
          assertEquals("SRC member reports redundancy " + redundancy, redundantCopies, redundancy);
        }
      }
    });

    // create newVM to create extra redundant buckets on

    final int finalNewVM = 2;
    createRegion(Host.getHost(0).getVM(finalNewVM), regionPath[0], localMaxMemory, numBuckets,
        redundantCopies);
    createRegion(Host.getHost(0).getVM(finalNewVM), regionPath[1], localMaxMemory, numBuckets,
        redundantCopies, regionPath[0]);
    createRegion(Host.getHost(0).getVM(finalNewVM), regionPath[2], localMaxMemory, numBuckets,
        redundantCopies, regionPath[1]);

    // create extra redundant buckets on finalNewVM

    Host.getHost(0).getVM(finalNewVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (int i = 0; i < regionPath.length; i++) {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[i]);

          if (i == 0) {
            // only call createRedundantBucket on leader PR
            assertEquals(CreateBucketResult.CREATED,
                pr.getDataStore().createRedundantBucket(0, false, new InternalDistributedMember()));
          }

          Bucket bucket = pr.getRegionAdvisor().getBucket(0);

          assertNotNull("Bucket is null on DST member", bucket);

          BucketRegion bucketRegion =
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

          assertTrue("DST member is not hosting bucket", bucket.isHosting());
          assertNotNull("BucketRegion is null on DST member", bucketRegion);
          assertEquals(redundantCopies + 1, bucket.getBucketAdvisor().getBucketRedundancy());
        }
      }
    });

    if (true) {
      return;
    }

    // initiate moveBucket to move from otherVM to newVM

    boolean movedBucket =
        (Boolean) Host.getHost(0).getVM(finalNewVM).invoke(new SerializableCallable() {
          @Override
          public Object call() {
            InternalDistributedMember recipient = members[finalOtherVM];

            PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(regionPath[0]);

            return pr.getDataStore().moveBucket(0, recipient, true);
          }
        });
    assertTrue("Failed in call to moveBucket", movedBucket);

    // validate that otherVM no longer hosts colocated buckets

    Host.getHost(0).getVM(otherVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (final String s : regionPath) {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(s);

          Bucket bucket = pr.getRegionAdvisor().getBucket(0);

          assertFalse("SRC member is still hosting moved bucket", bucket.isHosting());

          BucketRegion bucketRegion =
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

          assertNull("BucketRegion is not null on SRC member", bucketRegion);
        }
      }
    });

    // validate that newVM now hosts colocated bucket

    Host.getHost(0).getVM(finalNewVM).invoke(new SerializableRunnable() {
      @Override
      public void run() {
        for (final String s : regionPath) {
          PartitionedRegion pr = (PartitionedRegion) getCache().getRegion(s);

          Bucket bucket = pr.getRegionAdvisor().getBucket(0);

          assertNotNull("Bucket is null on DST member", bucket);

          BucketRegion bucketRegion =
              bucket.getBucketAdvisor().getProxyBucketRegion().getHostedBucketRegion();

          assertTrue("DST member is not hosting bucket", bucket.isHosting());
          assertNotNull("BucketRegion is null on DST member", bucketRegion);
          assertEquals(redundantCopies, bucket.getBucketAdvisor().getBucketRedundancy());
        }
      }
    });
  }
}
