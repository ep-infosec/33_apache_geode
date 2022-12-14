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
package org.apache.geode.cache.query.partitioned;

import static org.apache.geode.cache.Region.SEPARATOR;
import static org.apache.geode.test.util.ResourceUtils.createTempFileFromResource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import util.TestException;

import org.apache.geode.CancelException;
import org.apache.geode.LogWriter;
import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheClosedException;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.DiskStore;
import org.apache.geode.cache.EntryExistsException;
import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.PartitionAttributes;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionDestroyedException;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.RegionFunctionContext;
import org.apache.geode.cache.query.Index;
import org.apache.geode.cache.query.IndexExistsException;
import org.apache.geode.cache.query.IndexNameConflictException;
import org.apache.geode.cache.query.IndexType;
import org.apache.geode.cache.query.MultiIndexCreationException;
import org.apache.geode.cache.query.Query;
import org.apache.geode.cache.query.QueryException;
import org.apache.geode.cache.query.QueryInvalidException;
import org.apache.geode.cache.query.QueryInvocationTargetException;
import org.apache.geode.cache.query.QueryService;
import org.apache.geode.cache.query.RegionNotFoundException;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.data.Portfolio;
import org.apache.geode.cache.query.functional.StructSetOrResultsSet;
import org.apache.geode.cache.query.internal.index.PartitionedIndex;
import org.apache.geode.cache.query.types.ObjectType;
import org.apache.geode.cache30.CacheSerializableRunnable;
import org.apache.geode.distributed.internal.ReplyException;
import org.apache.geode.internal.cache.ForceReattemptException;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.SerializableRunnableIF;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;

/**
 * This is a helper class for the various Partitioned Query DUnit Test Cases
 *
 * TODO: inline and then delete class PRQueryDUnitHelper
 */
public class PRQueryDUnitHelper implements Serializable {

  static Cache cache;

  public static void setCache(Cache cache) {
    PRQueryDUnitHelper.cache = cache;
  }

  public static Cache getCache() {
    return cache;
  }

  CacheSerializableRunnable getCacheSerializableRunnableForLocalRegionCreation(
      final String regionName, final Class constraint) {
    return new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        Region localRegion = null;
        try {
          AttributesFactory attr = new AttributesFactory();
          attr.setValueConstraint(constraint);
          attr.setScope(Scope.LOCAL);
          localRegion = cache.createRegion(regionName, attr.create());
        } catch (IllegalStateException ex) {
          LogWriterUtils.getLogWriter().warning(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Creation caught IllegalStateException",
              ex);
        }
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region ref null",
            localRegion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region ref claims to be destroyed",
            !localRegion.isDestroyed());
      }
    };
  }

  CacheSerializableRunnable getCacheSerializableRunnableForLocalRegionWithAsyncIndexCreation(
      final String regionName, final Class constraint) {
    return new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();

        AttributesFactory attr = new AttributesFactory();
        attr.setValueConstraint(constraint);
        attr.setScope(Scope.LOCAL);
        attr.setIndexMaintenanceSynchronous(false);
        Region localRegion = cache.createRegion(regionName, attr.create());

        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region ref null",
            localRegion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region ref claims to be destroyed",
            !localRegion.isDestroyed());
      }
    };
  }

  public CacheSerializableRunnable getCacheSerializableRunnableForReplicatedRegionCreation(
      final String regionName) {
    return new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();

        Region localRegion = cache.createRegionFactory(RegionShortcut.REPLICATE).create(regionName);

        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region ref null",
            localRegion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreate: Partitioned Region ref claims to be destroyed",
            !localRegion.isDestroyed());
      }
    };
  }

  /**
   * This function creates a appropriate region PR given the scope & the redundancy parameters *
   *
   * @return cacheSerializable object
   */
  public CacheSerializableRunnable getCacheSerializableRunnableForPRCreate(final String regionName,
      final int redundancy, final Class constraint) {
    return new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        AttributesFactory attr = new AttributesFactory();
        attr.setValueConstraint(constraint);

        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        PartitionAttributes prAttr = paf.setRedundantCopies(redundancy).create();

        attr.setPartitionAttributes(prAttr);

        Region partitionedregion = cache.createRegion(regionName, attr.create());
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref null",
            partitionedregion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref claims to be destroyed",
            !partitionedregion.isDestroyed());
      }
    };
  }

  /**
   * This function creates a colocated pair of PR's given the scope & the redundancy parameters for
   * the parent *
   *
   * @return cacheSerializable object
   */
  CacheSerializableRunnable getCacheSerializableRunnableForColocatedPRCreate(
      final String regionName, final int redundancy, final Class constraint,
      boolean makePersistent) {
    String childRegionName = regionName + "Child";
    String diskName = "disk";

    return new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        AttributesFactory attr = new AttributesFactory();
        attr.setValueConstraint(constraint);
        if (makePersistent) {
          DiskStore ds = cache.findDiskStore(diskName);
          if (ds == null) {
            ds = cache.createDiskStoreFactory().setDiskDirs(JUnit4CacheTestCase.getDiskDirs())
                .create(diskName);
          }
          attr.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
          attr.setDiskStoreName(diskName);
        } else {
          attr.setDataPolicy(DataPolicy.PARTITION);
          attr.setDiskStoreName(null);
        }

        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(redundancy);
        attr.setPartitionAttributes(paf.create());

        // parent region
        Region partitionedregion = cache.createRegion(regionName, attr.create());
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref null",
            partitionedregion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref claims to be destroyed",
            !partitionedregion.isDestroyed());

        // child region
        attr.setValueConstraint(constraint);
        paf.setColocatedWith(regionName);
        attr.setPartitionAttributes(paf.create());

        cache.createRegion(childRegionName, attr.create());
      }
    };
  }

  /**
   * This function creates the parent region of colocated pair of PR's given the scope & the
   * redundancy parameters for the parent *
   *
   * @return cacheSerializable object
   */
  CacheSerializableRunnable getCacheSerializableRunnableForColocatedParentCreate(
      final String regionName, final int redundancy, final Class constraint,
      boolean makePersistent) {
    String diskName = "disk";

    return new CacheSerializableRunnable(regionName + "-NoChildRegion") {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        AttributesFactory attr = new AttributesFactory();
        attr.setValueConstraint(constraint);
        if (makePersistent) {
          DiskStore ds = cache.findDiskStore(diskName);
          if (ds == null) {
            ds = cache.createDiskStoreFactory().setDiskDirs(JUnit4CacheTestCase.getDiskDirs())
                .create(diskName);
          }
          attr.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
          attr.setDiskStoreName(diskName);
        } else {
          attr.setDataPolicy(DataPolicy.PARTITION);
          attr.setDiskStoreName(null);
        }

        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(redundancy);
        attr.setPartitionAttributes(paf.create());

        // parent region
        Region partitionedregion = cache.createRegion(regionName, attr.create());
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref null",
            partitionedregion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref claims to be destroyed",
            !partitionedregion.isDestroyed());
      }
    };
  }

  /**
   * This function creates the parent region of colocated pair of PR's given the scope & the
   * redundancy parameters for the parent *
   *
   * @return cacheSerializable object
   */
  CacheSerializableRunnable getCacheSerializableRunnableForColocatedChildCreate(
      final String regionName, final int redundancy, final Class constraint, boolean isPersistent) {

    final String childRegionName = regionName + "Child";
    final String diskName = "disk";
    SerializableRunnable createPrRegion =
        new CacheSerializableRunnable(regionName + "-ChildRegion") {
          @Override
          public void run2() throws CacheException {

            Cache cache = getCache();
            Region partitionedregion = null;
            AttributesFactory attr = new AttributesFactory();
            attr.setValueConstraint(constraint);
            if (isPersistent) {
              DiskStore ds = cache.findDiskStore(diskName);
              if (ds == null) {
                // ds = cache.createDiskStoreFactory().setDiskDirs(getDiskDirs())
                ds = cache.createDiskStoreFactory()
                    .setDiskDirs(
                        JUnit4CacheTestCase.getDiskDirs())
                    .create(diskName);
              }
              attr.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
              attr.setDiskStoreName(diskName);
            } else {
              attr.setDataPolicy(DataPolicy.PARTITION);
              attr.setDiskStoreName(null);
            }

            PartitionAttributesFactory paf = new PartitionAttributesFactory();
            paf.setRedundantCopies(redundancy);
            attr.setPartitionAttributes(paf.create());

            // skip parent region creation
            // partitionedregion = cache.createRegion(regionName, attr.create());

            // child region
            attr.setValueConstraint(constraint);
            paf.setColocatedWith(regionName);
            attr.setPartitionAttributes(paf.create());
            Region childRegion = cache.createRegion(childRegionName, attr.create());
          }
        };

    return (CacheSerializableRunnable) createPrRegion;
  }

  CacheSerializableRunnable getCacheSerializableRunnableForPRCreateLimitedBuckets(
      final String regionName, final int redundancy, final int buckets) {

    SerializableRunnable createPrRegion = new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        Region partitionedregion = null;
        try {
          AttributesFactory attr = new AttributesFactory();
          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          PartitionAttributes prAttr =
              paf.setRedundantCopies(redundancy).setTotalNumBuckets(buckets).create();
          attr.setPartitionAttributes(prAttr);
          partitionedregion = cache.createRegion(regionName, attr.create());
        } catch (IllegalStateException ex) {
          LogWriterUtils.getLogWriter().warning(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Creation caught IllegalStateException",
              ex);
        }
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref null",
            partitionedregion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref claims to be destroyed",
            !partitionedregion.isDestroyed());
      }
    };

    return (CacheSerializableRunnable) createPrRegion;
  }

  CacheSerializableRunnable getCacheSerializableRunnableForPersistentPRCreate(
      final String regionName, final int redundancy, final Class constraint) {

    SerializableRunnable createPrRegion = new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        Region partitionedregion = null;
        try {
          cache.createDiskStoreFactory().setDiskDirs(JUnit4CacheTestCase.getDiskDirs())
              .create("diskstore");
          AttributesFactory attr = new AttributesFactory();
          attr.setValueConstraint(constraint);
          attr.setDataPolicy(DataPolicy.PERSISTENT_PARTITION);
          attr.setDiskStoreName("diskstore");

          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          PartitionAttributes prAttr = paf.setRedundantCopies(redundancy).create();

          attr.setPartitionAttributes(prAttr);

          partitionedregion = cache.createRegion(regionName, attr.create());
        } catch (IllegalStateException ex) {
          LogWriterUtils.getLogWriter().warning(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Creation caught IllegalStateException",
              ex);
        }
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref null",
            partitionedregion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref claims to be destroyed",
            !partitionedregion.isDestroyed());
      }
    };

    return (CacheSerializableRunnable) createPrRegion;
  }

  /**
   * This function creates a colocated region PR given the oher colocated region.
   *
   * @return cacheSerializable object
   */

  CacheSerializableRunnable getCacheSerializableRunnableForPRColocatedCreate(
      final String regionName, final int redundancy, final String coloRegionName) {

    SerializableRunnable createPrRegion = new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        Region partitionedregion = null;
        try {
          Region colocatedRegion = cache.getRegion(coloRegionName);
          assertNotNull(colocatedRegion);
          AttributesFactory attr = new AttributesFactory();

          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          PartitionAttributes prAttr = paf.setRedundantCopies(redundancy)
              .setColocatedWith(colocatedRegion.getFullPath()).create();
          attr.setPartitionAttributes(prAttr);

          partitionedregion = cache.createRegion(regionName, attr.create());
        } catch (IllegalStateException ex) {
          LogWriterUtils.getLogWriter().warning(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Creation caught IllegalStateException",
              ex);
        }
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref null",
            partitionedregion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRCreateWithRedundancy: Partitioned Region ref claims to be destroyed",
            !partitionedregion.isDestroyed());
      }
    };

    return (CacheSerializableRunnable) createPrRegion;
  }


  /**
   * This function puts portfolio objects into the created Region (PR or Local) *
   *
   * @return cacheSerializable object
   */
  public CacheSerializableRunnable getCacheSerializableRunnableForPRPuts(final String regionName,
      final Object[] portfolio, final int from, final int to) {
    SerializableRunnable prPuts = new CacheSerializableRunnable("PRPuts") {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        Region region = cache.getRegion(regionName);
        for (int j = from; j < to; j++) {
          region.put(j, portfolio[j]);
        }
      }
    };
    return (CacheSerializableRunnable) prPuts;
  }


  /**
   * This function puts portfolio objects into the created Region (PR or RR). Also, other operation
   * like, invalidate, destroy and create are performed in random manner based on
   * {@link Random#nextInt(int)}.
   *
   * @return cacheSerializable object
   */
  public CacheSerializableRunnable getCacheSerializableRunnableForPRRandomOps(
      final String regionName, final int from, final int to) {
    SerializableRunnable prPuts = new CacheSerializableRunnable("PRPuts") {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        Region region = cache.getRegion(regionName);

        for (int i = 0; i < 3; i++) {
          for (int j = from; j < to; j++) {
            int op = new Random().nextInt(4);
            try {
              switch (op) {
                case 0:
                  // Put operation
                  region.put(j, new Portfolio(j));
                  break;
                case 1:
                  // invalidate
                  if (region.containsKey(j)) {
                    region.invalidate(j);
                  }
                  break;
                case 2:
                  if (region.containsKey(j)) {
                    region.destroy(j);
                  }
                  break;
                case 3:

                  if (!region.containsKey(j)) {
                    region.create(j, null);
                  }

                  break;
                default:
                  break;
              }
            } catch (EntryExistsException e) {
              // Do nothing let it go
              LogWriterUtils.getLogWriter()
                  .info("EntryExistsException was thrown for key " + j);
            } catch (EntryNotFoundException e) {
              // Do nothing let it go
              LogWriterUtils.getLogWriter()
                  .info("EntryNotFoundException was thrown for key " + j);
            }
          }
        }
      }
    };
    return (CacheSerializableRunnable) prPuts;
  }

  /**
   * This function puts portfolio objects into the created Region (PR or Local) *
   *
   * @return cacheSerializable object
   */
  CacheSerializableRunnable getCacheSerializableRunnableForPRDuplicatePuts(
      final String regionName, final Object[] portfolio, final int from, final int to) {
    SerializableRunnable prPuts = new CacheSerializableRunnable("PRPuts") {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        Region region = cache.getRegion(regionName);
        for (int j = from, i = to; j < to; j++, i++) {
          region.put(i, portfolio[j]);
        }
      }
    };
    return (CacheSerializableRunnable) prPuts;
  }

  /**
   * This function puts portfolio objects into the created Region (PR or Local) *
   *
   * @return cacheSerializable object
   */
  CacheSerializableRunnable getCacheSerializableRunnableForPRPutsKeyValue(
      final String regionName, final Object[] portfolio, final int from, final int to) {
    SerializableRunnable prPuts = new CacheSerializableRunnable("PRPuts") {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        Region region = cache.getRegion(regionName);
        for (int j = from; j < to; j++) {
          region.put(portfolio[j], portfolio[j]);
        }
      }
    };
    return (CacheSerializableRunnable) prPuts;
  }

  /**
   * This function <br>
   * 1. Creates & executes a query with Logical Operators on the given PR Region 2. Executes the
   * same query on the local region <br>
   * 3. Compares the appropriate resultSet <br>
   */

  CacheSerializableRunnable getCacheSerializableRunnableForPRQueryAndCompareResults(
      final String regionName, final String localRegion) {
    return getCacheSerializableRunnableForPRQueryAndCompareResults(regionName, localRegion, false);
  }

  CacheSerializableRunnable getCacheSerializableRunnableForPRQueryAndCompareResults(
      final String regionName, final String localRegion,
      final boolean fullQueryOnPortfolioPositions) {

    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the localRegion and the PR region

        String[] queries;

        if (fullQueryOnPortfolioPositions) {
          queries = new String[] {"import org.apache.geode.cache.\"query\".data.Position;"
              + "select distinct r.ID, status, mktValue "
              + "from $1 r, r.positions.values pVal TYPE Position "
              + "where r.status = 'active' AND pVal.mktValue >= 25.00",

              "import org.apache.geode.cache.\"query\".data.Position;" + "select distinct * "
                  + "from $1 r, r.positions.values pVal TYPE Position "
                  + "where r.status = 'active' AND pVal.mktValue >= 25.00",

              "import org.apache.geode.cache.\"query\".data.Position;" + "select distinct ID "
                  + "from $1 r, r.positions.values pVal TYPE Position "
                  + "where r.status = 'active' AND pVal.mktValue >= 25.00",

              "select distinct * " + "from $1 " + "where status = 'active'",

              "import org.apache.geode.cache.\"query\".data.Position;"
                  + "select distinct r from $1 r, "
                  + "r.positions.values pVal TYPE Position where pVal.mktValue < $2",

              "select p.positions.get('acc') from $1 p",

          };
        } else {
          queries = new String[] {"ID = 0 OR ID = 1", "ID > 4 AND ID < 9", "ID = 5", "ID < 5 ",
              "ID <= 5"};
        }

        Object[][] r = new Object[queries.length][2];
        Region local = cache.getRegion(localRegion);
        Region region = cache.getRegion(regionName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String exception : expectedExceptions) {
          getCache().getLogger().info(
              "<ExpectedException action=add>" + exception + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        try {
          for (int j = 0; j < queries.length; j++) {
            synchronized (region) {
              Object[] params;
              if (fullQueryOnPortfolioPositions) {
                params = new Object[] {local, (j % 25) * 1.0 + 1};
                r[j][0] = qs.newQuery(queries[j]).execute(params);
              } else {
                r[j][0] = local.query(queries[j]);
              }
              if (fullQueryOnPortfolioPositions) {
                params = new Object[] {region, (j % 25) * 1.0 + 1};
                r[j][1] = qs.newQuery(queries[j]).execute(params);
              } else {
                r[j][1] = region.query(queries[j]);
              }
            }
          }
          compareTwoQueryResults(r, queries.length);
        } catch (QueryInvocationTargetException e) {
          // If cause is RegionDestroyedException then its ok
          Throwable cause = e.getCause();
          if (!(cause instanceof RegionDestroyedException)) {
            // throw an unchecked exception so the controller can examine the cause and see whether
            // or not it's okay
            throw new TestException(
                "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
                e);
          }
        }

        catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }

    };
    return (CacheSerializableRunnable) PrRegion;
  }


  CacheSerializableRunnable getCacheSerializableRunnableForPROrderByQueryAndCompareResults(
      final String regionName, final String localRegion) {
    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the localRegion and the PR region

        String[] queries =
            new String[] {"p.status from " + SEPARATOR + "REGION_NAME p order by p.status",
                "* from " + SEPARATOR + "REGION_NAME order by status, ID desc",
                "status, ID from " + SEPARATOR + "REGION_NAME order by status",
                "p.status, p.ID from " + SEPARATOR + "REGION_NAME p order by p.status",
                "p.position1.secId, p.ID from " + SEPARATOR
                    + "REGION_NAME p order by p.position1.secId",
                "key from " + SEPARATOR + "REGION_NAME.keys key order by key.status",
                "key.ID from " + SEPARATOR + "REGION_NAME.keys key order by key.ID",
                "key.ID, key.status from " + SEPARATOR + "REGION_NAME.keys key order by key.status",
                "key.ID, key.status from " + SEPARATOR
                    + "REGION_NAME.keys key order by key.status, key.ID",
                "key.ID, key.status from " + SEPARATOR
                    + "REGION_NAME.keys key order by key.status desc, key.ID",
                "key.ID, key.status from " + SEPARATOR
                    + "REGION_NAME.keys key order by key.status, key.ID desc",
                "p.status, p.ID from " + SEPARATOR + "REGION_NAME p order by p.status asc, p.ID",
                "* from " + SEPARATOR + "REGION_NAME p order by p.status, p.ID",
                "p.ID from " + SEPARATOR + "REGION_NAME p, p.positions.values order by p.ID",
                "* from " + SEPARATOR + "REGION_NAME p, p.positions.values order by p.ID",
                "p.ID, p.status from " + SEPARATOR
                    + "REGION_NAME p, p.positions.values order by p.status",
                "pos.secId from " + SEPARATOR
                    + "REGION_NAME p, p.positions.values pos order by pos.secId",
                "p.ID, pos.secId from " + SEPARATOR
                    + "REGION_NAME p, p.positions.values pos order by pos.secId",
                "* from " + SEPARATOR + "REGION_NAME p order by p.iD",
                "p.iD from " + SEPARATOR + "REGION_NAME p order by p.iD",
                "p.iD, p.status from " + SEPARATOR + "REGION_NAME p order by p.iD",
                "iD, status from " + SEPARATOR + "REGION_NAME order by iD",
                "* from " + SEPARATOR + "REGION_NAME p order by p.getID()",
                "p.getID() from " + SEPARATOR + "REGION_NAME p order by p.getID()",
                "* from " + SEPARATOR + "REGION_NAME p order by p.names[1]",
                "* from " + SEPARATOR + "REGION_NAME p order by p.getP1().secId",
                "* from " + SEPARATOR + "REGION_NAME p order by p.getP1().getSecId()",
                "* from " + SEPARATOR + "REGION_NAME p order by p.position1.secId",
                "p.ID, p.position1.secId from " + SEPARATOR
                    + "REGION_NAME p order by p.position1.secId",
                "p.position1.secId, p.ID from " + SEPARATOR
                    + "REGION_NAME p order by p.position1.secId",
                "e.key.ID from " + SEPARATOR + "REGION_NAME.entries e order by e.key.ID",
                "e.key.ID, e.value.status from " + SEPARATOR
                    + "REGION_NAME.entries e order by e.key.ID",
                "e.key.ID, e.value.status from " + SEPARATOR
                    + "REGION_NAME.entrySet e order by e.key.ID, e.value.status desc",
                "e.key, e.value from " + SEPARATOR
                    + "REGION_NAME.entrySet e order by e.key.ID, e.value.status desc",
                "e.key from " + SEPARATOR
                    + "REGION_NAME.entrySet e order by e.key.ID, e.key.pkid desc",
                "p, pos from " + SEPARATOR + "REGION_NAME p, p.positions.values pos order by p.ID",
                "p, pos from " + SEPARATOR
                    + "REGION_NAME p, p.positions.values pos order by pos.secId",
                "p, pos from " + SEPARATOR
                    + "REGION_NAME p, p.positions.values pos order by p.ID, pos.secId",};

        Object[][] r = new Object[queries.length][2];
        Region region = cache.getRegion(regionName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String expectedException : expectedExceptions) {
          getCache().getLogger()
              .info("<ExpectedException action=add>" + expectedException + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        Object[] params;


        try {
          String distinct = "SELECT DISTINCT ";
          for (int j = 0; j < queries.length; j++) {
            synchronized (region) {
              // Execute on local region.
              String qStr = (distinct + queries[j].replace("REGION_NAME", localRegion));
              r[j][0] = qs.newQuery(qStr).execute();

              // Execute on remote region.
              qStr = (distinct + queries[j].replace("REGION_NAME", regionName));
              r[j][1] = qs.newQuery(qStr).execute();
            }
          }

          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Queries Executed successfully on Local region & PR Region");

          StructSetOrResultsSet ssORrs = new StructSetOrResultsSet();
          ssORrs.CompareQueryResultsWithoutAndWithIndexes(r, queries.length, queries);

        } catch (QueryInvocationTargetException e) {
          // throw an unchecked exception so the controller can examine the cause and see whether or
          // not it's okay
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }

    };
    return (CacheSerializableRunnable) PrRegion;
  }

  CacheSerializableRunnable getCacheSerializableRunnableForPROrderByQueryAndVerifyOrder(
      final String regionName, final String localRegion) {
    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the localRegion and the PR region

        Region region = cache.getRegion(regionName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String expectedException : expectedExceptions) {
          getCache().getLogger()
              .info("<ExpectedException action=add>" + expectedException + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        Object[] params;
        StructSetOrResultsSet ssORrs = new StructSetOrResultsSet();

        try {
          String distinct = "SELECT DISTINCT ";
          Object[][] r = new Object[1][2];
          String[] queries =
              new String[] {"p.status from " + SEPARATOR + "REGION_NAME p order by p.status",
                  "status, ID from " + SEPARATOR + "REGION_NAME order by status, ID",
                  "p.status, p.ID from " + SEPARATOR + "REGION_NAME p order by p.status, p.ID",
                  "key.ID from " + SEPARATOR + "REGION_NAME.keys key order by key.ID",
                  "key.ID, key.status from " + SEPARATOR
                      + "REGION_NAME.keys key order by key.status, key.ID",
                  "key.ID, key.status from " + SEPARATOR
                      + "REGION_NAME.keys key order by key.status desc, key.ID",
                  "key.ID, key.status from " + SEPARATOR
                      + "REGION_NAME.keys key order by key.status, key.ID desc",
                  "p.status, p.ID from " + SEPARATOR + "REGION_NAME p order by p.status asc, p.ID",
                  "p.ID, p.status from " + SEPARATOR
                      + "REGION_NAME p order by p.ID desc, p.status asc",
                  "p.ID from " + SEPARATOR + "REGION_NAME p, p.positions.values order by p.ID",
                  "p.ID, p.status from " + SEPARATOR
                      + "REGION_NAME p, p.positions.values order by p.status, p.ID",
                  "pos.secId from " + SEPARATOR
                      + "REGION_NAME p, p.positions.values pos order by pos.secId",
                  "p.ID, pos.secId from " + SEPARATOR
                      + "REGION_NAME p, p.positions.values pos order by pos.secId, p.ID",
                  "p.iD from " + SEPARATOR + "REGION_NAME p order by p.iD",
                  "p.iD, p.status from " + SEPARATOR + "REGION_NAME p order by p.iD",
                  "iD, status from " + SEPARATOR + "REGION_NAME order by iD",
                  "p.getID() from " + SEPARATOR + "REGION_NAME p order by p.getID()",
                  "p.names[1] from " + SEPARATOR + "REGION_NAME p order by p.names[1]",
                  "p.position1.secId, p.ID from " + SEPARATOR
                      + "REGION_NAME p order by p.position1.secId desc, p.ID",
                  "p.ID, p.position1.secId from " + SEPARATOR
                      + "REGION_NAME p order by p.position1.secId, p.ID",
                  "e.key.ID from " + SEPARATOR + "REGION_NAME.entries e order by e.key.ID",
                  "e.key.ID, e.value.status from " + SEPARATOR
                      + "REGION_NAME.entries e order by e.key.ID",
                  "e.key.ID, e.value.status from " + SEPARATOR
                      + "REGION_NAME.entrySet e order by e.key.ID desc , e.value.status desc",
                  "e.key, e.value from " + SEPARATOR
                      + "REGION_NAME.entrySet e order by e.key.ID, e.value.status desc",
                  "e.key from " + SEPARATOR
                      + "REGION_NAME.entrySet e order by e.key.ID desc, e.key.pkid desc",
                  "p.ID, pos.secId from " + SEPARATOR
                      + "REGION_NAME p, p.positions.values pos order by p.ID, pos.secId",
                  "p.ID, pos.secId from " + SEPARATOR
                      + "REGION_NAME p, p.positions.values pos order by p.ID desc, pos.secId desc",
                  "p.ID, pos.secId from " + SEPARATOR
                      + "REGION_NAME p, p.positions.values pos order by p.ID desc, pos.secId",};
          for (final String query : queries) {
            synchronized (region) {
              // Execute on local region.
              String qStr = (distinct + query.replace("REGION_NAME", localRegion));
              r[0][0] = qs.newQuery(qStr).execute();

              // Execute on remote region.
              qStr = (distinct + query.replace("REGION_NAME", regionName));
              r[0][1] = qs.newQuery(qStr).execute();
              ssORrs.CompareQueryResultsWithoutAndWithIndexes(r, 1, true, queries);
            }
          }

          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Queries Executed successfully on Local region & PR Region");
        } catch (QueryInvocationTargetException e) {
          // throw an unchecked exception so the controller can examine the cause and see whether or
          // not it's okay
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }

    };
    return (CacheSerializableRunnable) PrRegion;
  }

  CacheSerializableRunnable getCacheSerializableRunnableForPROrderByQueryWithLimit(
      final String regionName, final String localRegion) {
    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the localRegion and the PR region

        String[] queries =
            new String[] {"status as st from " + SEPARATOR + "REGION_NAME order by status",
                "p.status from " + SEPARATOR + "REGION_NAME p order by p.status",
                "p.position1.secId, p.ID from " + SEPARATOR
                    + "REGION_NAME p order by p.position1.secId, p.ID desc",
                "key from " + SEPARATOR + "REGION_NAME.keys key order by key.status, key.ID",
                "key.ID from " + SEPARATOR + "REGION_NAME.keys key order by key.ID",
                "key.ID, key.status from " + SEPARATOR
                    + "REGION_NAME.keys key order by key.status, key.ID asc",
                "key.ID, key.status from " + SEPARATOR
                    + "REGION_NAME.keys key order by key.status desc, key.ID",
                "p.status, p.ID from " + SEPARATOR + "REGION_NAME p order by p.status asc, p.ID",
                "p.ID from " + SEPARATOR + "REGION_NAME p, p.positions.values order by p.ID",
                "* from " + SEPARATOR
                    + "REGION_NAME p, p.positions.values val order by p.ID, val.secId",
                "p.iD, p.status from " + SEPARATOR + "REGION_NAME p order by p.iD",
                "iD, status from " + SEPARATOR + "REGION_NAME order by iD",
                "* from " + SEPARATOR + "REGION_NAME p order by p.getID()",
                "* from " + SEPARATOR + "REGION_NAME p order by p.getP1().secId, p.ID desc, p.ID",
                " p.position1.secId , p.ID as st from " + SEPARATOR
                    + "REGION_NAME p order by p.position1.secId, p.ID",
                "e.key.ID, e.value.status from " + SEPARATOR
                    + "REGION_NAME.entrySet e order by e.key.ID, e.value.status desc",
                "e.key from " + SEPARATOR
                    + "REGION_NAME.entrySet e order by e.key.ID, e.key.pkid desc",
                "p, pos from " + SEPARATOR
                    + "REGION_NAME p, p.positions.values pos order by p.ID, pos.secId desc",
                "p, pos from " + SEPARATOR
                    + "REGION_NAME p, p.positions.values pos order by pos.secId, p.ID",
                "status , ID as ied from " + SEPARATOR
                    + "REGION_NAME where ID > 0 order by status, ID desc",
                "p.status as st, p.ID as id from " + SEPARATOR
                    + "REGION_NAME p where ID > 0 and status = 'inactive' order by p.status, p.ID desc",
                "p.position1.secId as st, p.ID as ied from " + SEPARATOR
                    + "REGION_NAME p where p.ID > 0 and p.position1.secId != 'IBM' order by p.position1.secId, p.ID",
                " key.status as st, key.ID from " + SEPARATOR
                    + "REGION_NAME.keys key where key.ID > 5 order by key.status, key.ID desc",
                " key.ID, key.status as st from " + SEPARATOR
                    + "REGION_NAME.keys key where key.status = 'inactive' order by key.status desc, key.ID",

            };

        Object[][] r = new Object[queries.length][2];
        Region local = cache.getRegion(localRegion);
        Region region = cache.getRegion(regionName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String expectedException : expectedExceptions) {
          getCache().getLogger()
              .info("<ExpectedException action=add>" + expectedException + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        Object[] params;

        try {
          String distinct = "<TRACE>SELECT DISTINCT ";
          for (int l = 1; l <= 3; l++) {
            String[] rq = new String[queries.length];
            for (int j = 0; j < queries.length; j++) {
              synchronized (region) {
                // Execute on local region.
                String qStr = (distinct + queries[j].replace("REGION_NAME", localRegion));
                qStr += (" LIMIT " + (l * l));
                rq[j] = qStr;
                SelectResults sr = (SelectResults) qs.newQuery(qStr).execute();
                r[j][0] = sr;
                if (sr.asList().size() > l * l) {
                  fail("The resultset size exceeds limit size. Limit size=" + l * l
                      + ", result size =" + sr.asList().size());
                }

                // Execute on remote region.
                qStr = (distinct + queries[j].replace("REGION_NAME", regionName));
                qStr += (" LIMIT " + (l * l));
                rq[j] = qStr;
                SelectResults srr = (SelectResults) qs.newQuery(qStr).execute();
                r[j][1] = srr;
                if (srr.size() > l * l) {
                  fail("The resultset size exceeds limit size. Limit size=" + l * l
                      + ", result size =" + srr.asList().size());
                }
                // assertIndexDetailsEquals("The resultset size is not same as limit size.", l*l,
                // srr.asList().size());

                // getCache().getLogger().info("Finished executing PR query: " + qStr);
              }
            }
            StructSetOrResultsSet ssORrs = new StructSetOrResultsSet();
            ssORrs.CompareQueryResultsWithoutAndWithIndexes(r, queries.length, true, rq);

          }
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Queries Executed successfully on Local region & PR Region");
        } catch (QueryInvocationTargetException e) {
          // throw an unchecked exception so the controller can examine the cause and see whether or
          // not it's okay
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }

    };
    return (CacheSerializableRunnable) PrRegion;
  }

  CacheSerializableRunnable getCacheSerializableRunnableForPRCountStarQueries(
      final String regionName, final String localRegion) {
    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRCountStarQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the localRegion and the PR region

        String[] queries = new String[] {"select COUNT(*) from " + SEPARATOR + regionName,
            "select COUNT(*) from " + SEPARATOR + regionName + " where ID > 0",
            "select COUNT(*) from " + SEPARATOR + regionName + " where ID > 0 AND status='active'",
            "select COUNT(*) from " + SEPARATOR + regionName + " where ID > 0 OR status='active'",
            "select COUNT(*) from " + SEPARATOR + regionName
                + " where ID > 0 AND status LIKE 'act%'",
            "select COUNT(*) from " + SEPARATOR + regionName
                + " where ID > 0 OR status LIKE 'ina%'",
            "select COUNT(*) from " + SEPARATOR + regionName + " where ID IN SET(1, 2, 3, 4, 5)",
            "select COUNT(*) from " + SEPARATOR + regionName + " where NOT (ID > 5)",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName + " where ID > 0",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName
                + " where ID > 0 AND status='active'",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName
                + " where ID > 0 OR status='active'",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName
                + " where ID > 0 AND status LIKE 'act%'",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName
                + " where ID > 0 OR status LIKE 'ina%'",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName
                + " where ID IN SET(1, 2, 3, 4, 5)",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName + " where NOT (ID > 5)",
            "select COUNT(*) from " + SEPARATOR + regionName
                + " p, p.positions.values pos where p.ID > 0 AND pos.secId = 'IBM'",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName
                + " p, p.positions.values pos where p.ID > 0 AND pos.secId = 'IBM'",
            "select COUNT(*) from " + SEPARATOR + regionName
                + " p, p.positions.values pos where p.ID > 0 AND pos.secId = 'IBM' LIMIT 5",
            "select DISTINCT COUNT(*) from " + SEPARATOR + regionName
                + " p, p.positions.values pos where p.ID > 0 AND pos.secId = 'IBM' ORDER BY p.ID",
            "select COUNT(*) from " + SEPARATOR + regionName
                + " p, p.positions.values pos where p.ID > 0 AND p.status = 'active' AND pos.secId = 'IBM'",
            "select COUNT(*) from " + SEPARATOR + regionName
                + " p, p.positions.values pos where p.ID > 0 AND p.status = 'active' OR pos.secId = 'IBM'",
            "select COUNT(*) from " + SEPARATOR + regionName
                + " p, p.positions.values pos where p.ID > 0 OR p.status = 'active' OR pos.secId = 'IBM'",
            "select COUNT(*) from " + SEPARATOR + regionName
                + " p, p.positions.values pos where p.ID > 0 OR p.status = 'active' OR pos.secId = 'IBM' LIMIT 150",
            // "select DISTINCT COUNT(*) from /" + regionName + " p, p.positions.values pos where
            // p.ID > 0 OR p.status = 'active' OR pos.secId = 'IBM' ORDER BY p.ID",
        };

        Object[][] r = new Object[queries.length][2];
        Region region = cache.getRegion(regionName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String expectedException : expectedExceptions) {
          getCache().getLogger()
              .info("<ExpectedException action=add>" + expectedException + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        Object[] params;

        try {
          for (int j = 0; j < queries.length; j++) {
            synchronized (region) {
              // Execute on PR region.
              String qStr = queries[j];
              SelectResults sr = (SelectResults) qs.newQuery(qStr).execute();
              r[j][0] = sr;

              // Execute on local region.
              qStr = queries[j];
              SelectResults srr =
                  (SelectResults) qs.newQuery(qStr.replace(regionName, localRegion)).execute();
              r[j][1] = srr;
            }
          }
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Queries Executed successfully on Local region & PR Region");

          StructSetOrResultsSet ssORrs = new StructSetOrResultsSet();
          ssORrs.CompareCountStarQueryResultsWithoutAndWithIndexes(r, queries.length, true,
              queries);

        } catch (QueryInvocationTargetException e) {
          // throw an unchecked exception so the controller can examine the cause and see whether or
          // not it's okay
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        }

        catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }

    };
    return (CacheSerializableRunnable) PrRegion;
  }

  /**
   * Insure queries on a pr is using index if not fail.
   */
  CacheSerializableRunnable getCacheSerializableRunnableForIndexUsageCheck() {
    SerializableRunnable PrIndexCheck = new CacheSerializableRunnable("PrIndexCheck") {
      @Override
      public void run2() {
        Cache cache = getCache();

        QueryService qs = cache.getQueryService();
        LogWriter logger = cache.getLogger();

        Collection indexes = qs.getIndexes();
        for (final Object index : indexes) {
          PartitionedIndex ind = (PartitionedIndex) index;
          /*
           * List bucketIndex = ind.getBucketIndexes(); int k = 0;
           * logger.info("Total number of bucket index : "+bucketIndex.size()); while ( k <
           * bucketIndex.size() ){ Index bukInd = (Index)bucketIndex.get(k);
           * logger.info("Buket Index "+bukInd+"  usage : "+bukInd.getStatistics().getTotalUses());
           * // if number of quries on pr change in
           * getCacheSerializableRunnableForPRQueryAndCompareResults // literal 6 should change.
           * //Asif : With the optmization of Range Queries a where clause // containing something
           * like ID > 4 AND ID < 9 will be evaluated //using a single index lookup, so accordingly
           * modifying the //assert value from 7 to 6 // Anil : With aquiringReadLock during
           * Index.getSizeEstimate(), the // Index usage in case of "ID = 0 OR ID = 1" is increased
           * by 3. int indexUsageWithSizeEstimation = 3; int expectedUse = 6; long indexUse =
           * bukInd.getStatistics().getTotalUses(); // Anil : With chnages to use single index for
           * PR query evaluation, once the index // is identified the same index is used on other PR
           * buckets, the sieEstimation is // done only once, which adds additional index use for
           * only one bucket index. if (!(indexUse == expectedUse || indexUse == (expectedUse +
           * indexUsageWithSizeEstimation))){ fail
           * ("Index usage is not as expected, expected it to be either " + expectedUse + " or " +
           * (expectedUse + indexUsageWithSizeEstimation) + " it is: " + indexUse);
           * //assertIndexDetailsEquals(6 + indexUsageWithSizeEstimation,
           * bukInd.getStatistics().getTotalUses()); } k++; }
           */
          // Shobhit: Now we dont need to check stats per bucket index,
          // stats are accumulated in single pr index stats.

          // Anil : With aquiringReadLock during Index.getSizeEstimate(), the
          // Index usage in case of "ID = 0 OR ID = 1" is increased by 3.
          int indexUsageWithSizeEstimation = 3;

          logger.info("index uses for " + ind.getNumberOfIndexedBuckets() + " index "
              + ind.getName() + ": " + ind.getStatistics().getTotalUses());
          assertEquals(6, ind.getStatistics().getTotalUses());
        }

      }



    };
    return (CacheSerializableRunnable) PrIndexCheck;
  }

  /**
   * This function <br>
   * 1. Creates & executes a query with Constants on the given PR Region <br>
   * 2. Executes the same query on the local region <br>
   * 3. Compares the appropriate resultSet <br>
   */

  CacheSerializableRunnable getCacheSerializableRunnableForPRQueryWithConstantsAndComparingResults(
      final String regionName, final String localRegion) {
    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the localRegion and the PR region

        String[] query = {"TRUE", "FALSE", "UNDEFINED", "NULL"};
        Object[][] r = new Object[query.length][2];
        Region local = cache.getRegion(localRegion);
        Region region = cache.getRegion(regionName);
        try {

          for (int j = 0; j < query.length; j++) {
            r[j][0] = local.query(query[j]);
            r[j][1] = region.query(query[j]);
          }

          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryWithConstantsAndComparingResults: Queries Executed successfully on Local region & PR Region");

          compareTwoQueryResults(r, query.length);

        } catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryWithConstantsAndComparingResults: Caught an Exception while querying Constants"
                  + e,
              e);
          fail(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryWithConstantsAndComparingResults: Caught Exception while querying Constants. Exception is "
                  + e);
        }
      }

    };
    return (CacheSerializableRunnable) PrRegion;
  }

  /**
   * This function creates a Accessor node region on the given PR given the scope parameter.
   *
   * @return cacheSerializable object
   */

  public CacheSerializableRunnable getCacheSerializableRunnableForPRAccessorCreate(
      final String regionName, final int redundancy, final Class constraint) {
    SerializableRunnable createPrRegion = new CacheSerializableRunnable(regionName) {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        AttributesFactory attr = new AttributesFactory();
        attr.setValueConstraint(constraint);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        int maxMem = 0;
        PartitionAttributes prAttr =
            paf.setLocalMaxMemory(maxMem).setRedundantCopies(redundancy).create();
        attr.setPartitionAttributes(prAttr);
        Region partitionedregion = cache.createRegion(regionName, attr.create());
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRAccessorCreate: Partitioned Region "
                + regionName + " not in cache",
            cache.getRegion(regionName));
        assertNotNull(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRAccessorCreate: Partitioned Region ref null",
            partitionedregion);
        assertTrue(
            "PRQueryDUnitHelper#getCacheSerializableRunnableForPRAccessorCreate: Partitioned Region ref claims to be destroyed",
            !partitionedregion.isDestroyed());
      }
    };

    return (CacheSerializableRunnable) createPrRegion;
  }

  /*
   * This function compares the two result sets passed based on <br> 1. Type <br> 2. Size <br> 3.
   * Contents <br>
   *
   * @param Object[][] @param length @return
   */

  private void compareTwoQueryResults(Object[][] r, int len) {

    for (int j = 0; j < len; j++) {
      if ((r[j][0] != null) && (r[j][1] != null)) {
        ObjectType type1 = ((SelectResults) r[j][0]).getCollectionType().getElementType();
        assertNotNull("PRQueryDUnitHelper#compareTwoQueryResults: Type 1 is NULL " + type1, type1);
        ObjectType type2 = ((SelectResults) r[j][1]).getCollectionType().getElementType();
        assertNotNull("PRQueryDUnitHelper#compareTwoQueryResults: Type 2 is NULL " + type2, type2);
        if ((type1.getClass().getName()).equals(type2.getClass().getName())) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#compareTwoQueryResults: Both Search Results are of the same Type i.e.--> "
                  + ((SelectResults) r[j][0]).getCollectionType().getElementType());

        } else {
          LogWriterUtils.getLogWriter()
              .error("PRQueryDUnitHelper#compareTwoQueryResults: Classes are : "
                  + type1.getClass().getName() + " " + type2.getClass().getName());

          fail(
              "PRQueryDUnitHelper#compareTwoQueryResults: FAILED:Search result Type is different in both the cases");
        }
        int size0 = ((SelectResults) r[j][0]).size();
        int size1 = ((SelectResults) r[j][1]).size();
        if (size0 == size1) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#compareTwoQueryResults: Both Search Results are non-zero and are of Same Size i.e.  Size= "
                  + size1 + ";j=" + j);

        } else {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#compareTwoQueryResults: FAILED:Search resultSet size are different in both cases; size0="
                  + size0 + ";size1=" + size1 + ";j=" + j);
          fail(
              "PRQueryDUnitHelper#compareTwoQueryResults: FAILED:Search resultSet size are different in both cases; size0="
                  + size0 + ";size1=" + size1 + ";j=" + j);
        }
        Set set2 = (((SelectResults) r[j][1]).asSet());
        Set set1 = (((SelectResults) r[j][0]).asSet());

        assertEquals("PRQueryDUnitHelper#compareTwoQueryResults: FAILED: "
            + "result contents are not equal, ", set1, set2);
      }
    }
  }

  /**
   * This function <br>
   * 1. Creates & executes a query with Logical Operators on the given PR Region 2. Executes the
   * same query on the local region <br>
   * 3. Compares the appropriate resultSet <br>
   *
   *
   *
   * @return cacheSerializable object
   */

  CacheSerializableRunnable getCacheSerializableRunnableForPRInvalidQuery(
      final String regionName) {
    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the PR region with an Invalid query string

        Region region = cache.getRegion(regionName);
        try {

          String query = "INVALID QUERY";
          region.query(query);
          fail(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRInvalidQuery: InvalidQueryException expected");
        } catch (QueryInvalidException e) {
          // pass
        } catch (QueryException qe) {

          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRInvalidQuery: Caught another Exception while querying , Exception is "
                  + qe,
              qe);
          fail(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRInvalidQuery: Caught another Exception while querying , Exception is "
                  + qe);

        }
      }

    };
    return (CacheSerializableRunnable) PrRegion;
  }

  /**
   * This function <br>
   * 1. calls the region.close on the VM <br>
   * 2. creates the cache again & also the PR <br>
   *
   * @return cacheSerializable object
   */

  CacheSerializableRunnable getCacheSerializableRunnableForRegionClose(
      final String regionName, final int redundancy, final Class constraint) {
    SerializableRunnable PrRegion = new CacheSerializableRunnable("regionClose") {
      @Override
      public void run2() throws CacheException {
        Cache cache = getCache();
        final String expectedRegionDestroyedException = RegionDestroyedException.class.getName();
        getCache().getLogger().info("<ExpectedException action=add>"
            + expectedRegionDestroyedException + "</ExpectedException>");
        final String expectedReplyException = ReplyException.class.getName();
        getCache().getLogger().info(
            "<ExpectedException action=add>" + expectedReplyException + "</ExpectedException>");

        Region region = cache.getRegion(regionName);
        LogWriterUtils.getLogWriter().info(
            "PROperationWithQueryDUnitTest#getCacheSerializableRunnableForRegionClose: Closing region");
        region.close();
        LogWriterUtils.getLogWriter().info(
            "PROperationWithQueryDUnitTest#getCacheSerializableRunnableForRegionClose: Region Closed on VM ");
        AttributesFactory attr = new AttributesFactory();
        attr.setValueConstraint(constraint);
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        PartitionAttributes prAttr = paf.setRedundantCopies(redundancy).create();
        attr.setPartitionAttributes(prAttr);
        cache.createRegion(regionName, attr.create());
        LogWriterUtils.getLogWriter().info(
            "PROperationWithQueryDUnitTest#getCacheSerializableRunnableForRegionClose: Region Recreated on VM ");
        getCache().getLogger().info(
            "<ExpectedException action=remove>" + expectedReplyException + "</ExpectedException>");
        getCache().getLogger().info("<ExpectedException action=remove>"
            + expectedRegionDestroyedException + "</ExpectedException>");
      }

    };
    return (CacheSerializableRunnable) PrRegion;
  }

  /**
   * This function creates a appropriate index on a PR given the name and other parameters.
   */
  public CacheSerializableRunnable getCacheSerializableRunnableForPRIndexCreate(
      final String prRegionName, final String indexName, final String indexedExpression,
      final String fromClause, final String alias) {

    SerializableRunnable prIndexCreator = new CacheSerializableRunnable("PartitionedIndexCreator") {
      @Override
      public void run2() {
        try {
          Cache cache = getCache();
          QueryService qs = cache.getQueryService();
          Region region = cache.getRegion(prRegionName);
          LogWriter logger = cache.getLogger();
          if (null != fromClause) {
            logger.info("Test Creating index with Name : [ " + indexName + " ] "
                + "IndexedExpression : [ " + indexedExpression + " ] Alias : [ " + alias
                + " ] FromClause : [ " + fromClause + " " + alias + " ] ");
            Index parIndex =
                qs.createIndex(indexName, IndexType.FUNCTIONAL, indexedExpression, fromClause);
            logger.info("Index creted on partitioned region : " + parIndex);
          } else {
            logger.info("Test Creating index with Name : [ " + indexName + " ] "
                + "IndexedExpression : [ " + indexedExpression + " ] Alias : [ " + alias
                + " ] FromClause : [ " + region.getFullPath() + " " + alias + " ] ");
            Index parIndex = qs.createIndex(indexName, IndexType.FUNCTIONAL, indexedExpression,
                region.getFullPath() + " " + alias);
            logger.info("Index creted on partitioned region : " + parIndex);
            logger.info("Number of buckets indexed in the partitioned region locally : " + ""
                + ((PartitionedIndex) parIndex).getNumberOfIndexedBuckets()
                + " and remote buckets indexed : "
                + ((PartitionedIndex) parIndex).getNumRemoteBucketsIndexed());
          }
          /*
           * assertIndexDetailsEquals("Max num of buckets in the partiotion regions and the
           * " + "buckets indexed should be equal",
           * ((PartitionedRegion)region).getTotalNumberOfBuckets(),
           * (((PartionedIndex)parIndex).getNumberOfIndexedBucket()+((PartionedIndex)parIndex).
           * getNumRemtoeBucketsIndexed())); should put all the assetion in a separate function.
           */
        } catch (Exception ex) {
          Assert.fail("Creating Index in this vm failed : ", ex);
        }
      }
    };
    return (CacheSerializableRunnable) prIndexCreator;
  }

  /**
   * This function defines a appropriate index on a PR given the name and other parameters.
   */
  CacheSerializableRunnable getCacheSerializableRunnableForDefineIndex(
      final String prRegionName, final ArrayList<String> indexName,
      final ArrayList<String> indexedExpression) {
    return getCacheSerializableRunnableForDefineIndex(prRegionName, indexName, indexedExpression,
        null);
  }

  public CacheSerializableRunnable getCacheSerializableRunnableForDefineIndex(
      final String prRegionName, final ArrayList<String> indexName,
      final ArrayList<String> indexedExpression, final ArrayList<String> fromClause) {

    SerializableRunnable prIndexCreator = new CacheSerializableRunnable("PartitionedIndexCreator") {
      @Override
      public void run2() {
        List<Index> indexes = null;
        try {
          Cache cache = getCache();
          QueryService qs = cache.getQueryService();
          Region region = cache.getRegion(prRegionName);
          for (int i = 0; i < indexName.size(); i++) {
            qs.defineIndex(indexName.get(i), indexedExpression.get(i),
                fromClause == null ? region.getFullPath() : fromClause.get(i));
          }
          indexes = qs.createDefinedIndexes();
        } catch (Exception ex) {
          if (ex instanceof MultiIndexCreationException) {
            StringBuilder sb = new StringBuilder();
            for (Exception e : ((MultiIndexCreationException) ex).getExceptionsMap().values()) {
              sb.append(e.getMessage()).append("\n");
            }
            fail("Multi index creation failed, " + sb);
          } else {
            Assert.fail("Creating Index in this vm failed : ", ex);
          }
        }
        assertNotNull("Indexes should have been created.", indexes);
      }
    };
    return (CacheSerializableRunnable) prIndexCreator;
  }

  CacheSerializableRunnable getCacheSerializableRunnableForRRIndexCreate(
      final String rrRegionName, final String indexName, final String indexedExpression,
      final String fromClause, final String alias) {

    SerializableRunnable prIndexCreator =
        new CacheSerializableRunnable("ReplicatedRegionIndexCreator") {
          @Override
          public void run2() {
            try {
              Cache cache = getCache();
              QueryService qs = cache.getQueryService();
              Region region = cache.getRegion(rrRegionName);
              LogWriter logger = cache.getLogger();
              if (null != fromClause) {
                logger.info("Test Creating index with Name : [ " + indexName + " ] "
                    + "IndexedExpression : [ " + indexedExpression + " ] Alias : [ " + alias
                    + " ] FromClause : [ " + fromClause + " " + alias + " ] ");
                Index parIndex =
                    qs.createIndex(indexName, IndexType.FUNCTIONAL, indexedExpression, fromClause);
                logger.info("Index creted on replicated region : " + parIndex);

              } else {
                logger.info("Test Creating index with Name : [ " + indexName + " ] "
                    + "IndexedExpression : [ " + indexedExpression + " ] Alias : [ " + alias
                    + " ] FromClause : [ " + region.getFullPath() + " " + alias + " ] ");
                Index parIndex = qs.createIndex(indexName, IndexType.FUNCTIONAL, indexedExpression,
                    region.getFullPath() + " " + alias);
                logger.info("Index creted on replicated region : " + parIndex);
              }


            } catch (Exception ex) {
              Assert.fail("Creating Index in this vm failed : ", ex);
            }
          }
        };
    return (CacheSerializableRunnable) prIndexCreator;
  }

  /**
   * Creats a partiotioned region using an xml file descriptions.
   *
   *
   *
   */
  public CacheSerializableRunnable getCacheSerializableRunnableForPRCreate(
      final String regionName) {
    SerializableRunnable prIndexCreator = new CacheSerializableRunnable("PrRegionCreator") {
      @Override
      public void run2() {
        try {
          Cache cache = getCache();
          LogWriter logger = cache.getLogger();
          PartitionedRegion region = (PartitionedRegion) cache.getRegion(regionName);
          Map indexMap = region.getIndex();
          Set indexSet = indexMap.entrySet();
          for (final Object o : indexSet) {
            Map.Entry entry = (Map.Entry) o;
            Index index = (Index) entry.getValue();
            logger.info("The partitioned index created on this region " + " " + index);
            logger.info("Current number of buckets indexed : " + ""
                + ((PartitionedIndex) index).getNumberOfIndexedBuckets());
          }
        } finally {
          GemFireCacheImpl.testCacheXml = null;
        }

      }
    };
    return (CacheSerializableRunnable) prIndexCreator;
  }


  File findFile(String fileName) {
    return new File(
        createTempFileFromResource(PRQueryDUnitHelper.class, fileName)
            .getAbsolutePath());
  }

  CacheSerializableRunnable getCacheSerializableRunnableForIndexCreationCheck(
      final String name) {
    return new CacheSerializableRunnable("PrIndexCreationCheck") {
      @Override
      public void run2() {
        Cache cache1 = getCache();
        LogWriter logger = cache1.getLogger();
        PartitionedRegion region = (PartitionedRegion) cache1.getRegion(name);
        Map indexMap = region.getIndex();
        Set indexSet = indexMap.entrySet();
        for (final Object o : indexSet) {
          Map.Entry entry = (Map.Entry) o;
          Index index = (Index) entry.getValue();
          logger.info("the partitioned index created on this region " + " " + index);
          logger.info("Current number of buckets indexed : " + ""
              + ((PartitionedIndex) index).getNumberOfIndexedBuckets());
        }

        JUnit4CacheTestCase.closeCache();
        JUnit4DistributedTestCase.disconnectFromDS();

      }
    };
  }

  /**
   * This function creates a duplicate index should throw an IndexNameConflictException and if not
   * the test should fail.
   */
  CacheSerializableRunnable getCacheSerializableRunnableForDuplicatePRIndexCreate(
      final String prRegionName, final String indexName, final String indexedExpression,
      final String fromClause, final String alias) {
    SerializableRunnable prIndexCreator =
        new CacheSerializableRunnable("DuplicatePartitionedIndexCreator") {
          @Override
          public void run2() {
            Cache cache = getCache();
            LogWriter logger = cache.getLogger();
            QueryService qs = cache.getQueryService();
            Region region = cache.getRegion(prRegionName);
            try {
              if (null != fromClause) {
                qs.createIndex(indexName, IndexType.FUNCTIONAL, indexedExpression, fromClause);
                throw new RuntimeException("Should throw an exception because "
                    + "the index with name : " + indexName + " should already exists");
              } else {
                qs.createIndex(indexName, IndexType.FUNCTIONAL, indexedExpression,
                    region.getFullPath() + " " + alias);
                throw new RuntimeException("Should throw an exception because "
                    + "the index with name : " + indexName + " should already exists");
              }
            } catch (IndexExistsException e) {
              logger.info("Index Exists Excetpiont righteously throw ", e);
            } catch (IndexNameConflictException ex) {
              logger.info("Gott the right exception");
            } catch (RegionNotFoundException exx) {
              // TODO Auto-generated catch block
              Assert.fail("Region Not found in this vm ", exx);
            }

          }
        };
    return (CacheSerializableRunnable) prIndexCreator;
  }

  /**
   * Cacheserializable runnable which removes all the index on a partitioned region
   *
   * @param name name of the partitioned regions
   */

  CacheSerializableRunnable getCacheSerializableRunnableForRemoveIndex(final String name,
      final boolean random) {
    return new CacheSerializableRunnable("PrRemoveIndex") {
      @Override
      public void run2() {

        Cache cache1 = getCache();
        LogWriter logger = cache1.getLogger();
        logger.info("Got the following cache : " + cache1);
        Region parRegion = cache1.getRegion(name);
        QueryService qs = cache1.getQueryService();
        if (!random) {
          Collection indexes = qs.getIndexes();
          assertEquals(3, indexes.size());
          for (final Object index : indexes) {
            logger.info("Following indexes found : " + index);
          }
          qs.removeIndexes(parRegion);
          logger.info("Removed all the index on this paritioned regions : " + parRegion);
          indexes = qs.getIndexes();
          assertEquals(0, ((LocalRegion) parRegion).getIndexManager().getIndexes().size());
          assertEquals(0, indexes.size());

          // should not cause any kind of exception just a check.
          qs.removeIndexes(parRegion);
        } else {
          // pick a random index and remvoe it
          Collection indexes = qs.getIndexes(parRegion);
          assertEquals(3, indexes.size());
          assertEquals(3, ((LocalRegion) parRegion).getIndexManager().getIndexes().size());
          synchronized (indexes) {
            for (final Object index : indexes) {
              Index in = (Index) index;
              qs.removeIndex(in);
            }
          }
          indexes = qs.getIndexes(parRegion);
          assertEquals(0, indexes.size());
          assertEquals(0, ((LocalRegion) parRegion).getIndexManager().getIndexes().size());
        }

      } // ends run
    };
  }

  SerializableRunnableIF getCacheSerializableRunnableForPRColocatedDataSetQueryAndCompareResults(
      final String name, final String coloName, final String localName,
      final String coloLocalName) {

    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the PR region

        String[] queries = new String[] {"r1.ID = r2.id", "r1.ID = r2.id AND r1.ID > 5",
            "r1.ID = r2.id AND r1.status = 'active'",
            // "r1.ID = r2.id LIMIT 10",
            "r1.ID = r2.id ORDER BY r1.ID", "r1.ID = r2.id ORDER BY r2.id",
            "r1.ID = r2.id ORDER BY r2.status", "r1.ID = r2.id AND r1.status != r2.status",
            "r1.ID = r2.id AND r1.status = r2.status",
            "r1.ID = r2.id AND r1.positions.size = r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size > r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size < r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size = r2.positions.size AND r2.positions.size > 0",
            "r1.ID = r2.id AND (r1.positions.size > r2.positions.size OR r2.positions.size > 0)",
            "r1.ID = r2.id AND (r1.positions.size < r2.positions.size OR r1.positions.size > 0)",};

        Object[][] r = new Object[queries.length][2];
        Region region = cache.getRegion(name);
        assertNotNull(region);
        region = cache.getRegion(coloName);
        assertNotNull(region);
        region = cache.getRegion(localName);
        assertNotNull(region);
        region = cache.getRegion(coloLocalName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String expectedException : expectedExceptions) {
          getCache().getLogger()
              .info("<ExpectedException action=add>" + expectedException + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        Object[] params;
        try {
          for (int j = 0; j < queries.length; j++) {
            getCache().getLogger().info("About to execute local query: " + queries[j]);
            Function func = new TestQueryFunction("testfunction");

            Object funcResult = FunctionService
                .onRegion((getCache().getRegion(name) instanceof PartitionedRegion)
                    ? getCache().getRegion(name) : getCache().getRegion(coloName))
                .setArguments(
                    "<trace> Select " + (queries[j].contains("ORDER BY") ? "DISTINCT" : "")
                        + " * from " + SEPARATOR + name + " r1, " + SEPARATOR + coloName
                        + " r2 where " + queries[j])
                .execute(func).getResult();

            r[j][0] = ((ArrayList) funcResult).get(0);
            getCache().getLogger().info("About to execute local query: " + queries[j]);

            SelectResults r2 = (SelectResults) qs
                .newQuery(
                    "Select " + (queries[j].contains("ORDER BY") ? "DISTINCT" : "") + " * from "
                        + SEPARATOR + localName + " r1, " + SEPARATOR + coloLocalName + " r2 where "
                        + queries[j])
                .execute();
            r[j][1] = r2.asList();
          }
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Queries Executed successfully on Local region & PR Region");

          // compareTwoQueryResults(r, queries.length);
          StructSetOrResultsSet ssORrs = new StructSetOrResultsSet();
          ssORrs.CompareQueryResultsAsListWithoutAndWithIndexes(r, queries.length, false, false,
              queries);

        } catch (QueryInvocationTargetException e) {
          // throw an unchecked exception so the controller can examine the
          // cause and see whether or not it's okay
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        } catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        } catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }
    };
    return PrRegion;

  }

  SerializableRunnableIF getCacheSerializableRunnableForPRAndRRQueryAndCompareResults(
      final String name, final String coloName, final String localName,
      final String coloLocalName) {

    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the PR region

        String[] queries = new String[] {"r1.ID = r2.id", "r1.ID = r2.id AND r1.ID > 5",
            "r1.ID = r2.id AND r1.status = 'active'",
            // "r1.ID = r2.id LIMIT 10",
            "r1.ID = r2.id ORDER BY r1.ID", "r1.ID = r2.id ORDER BY r2.id",
            "r1.ID = r2.id ORDER BY r2.status", "r1.ID = r2.id AND r1.status != r2.status",
            "r1.ID = r2.id AND r1.status = r2.status",
            "r1.ID = r2.id AND r1.positions.size = r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size > r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size < r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size = r2.positions.size AND r2.positions.size > 0",
            "r1.ID = r2.id AND (r1.positions.size > r2.positions.size OR r2.positions.size > 0)",
            "r1.ID = r2.id AND (r1.positions.size < r2.positions.size OR r1.positions.size > 0)",};

        Object[][] r = new Object[queries.length][2];
        Region region = cache.getRegion(name);
        assertNotNull(region);
        region = cache.getRegion(coloName);
        assertNotNull(region);
        region = cache.getRegion(localName);
        assertNotNull(region);
        region = cache.getRegion(coloLocalName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String expectedException : expectedExceptions) {
          getCache().getLogger()
              .info("<ExpectedException action=add>" + expectedException + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        Object[] params;
        try {
          for (int j = 0; j < queries.length; j++) {
            getCache().getLogger().info("About to execute local query: " + queries[j]);
            Function func = new TestQueryFunction("testfunction");

            Object funcResult = FunctionService
                .onRegion((getCache().getRegion(name) instanceof PartitionedRegion)
                    ? getCache().getRegion(name) : getCache().getRegion(coloName))
                .setArguments("<trace> Select "
                    + (queries[j].contains("ORDER BY") ? "DISTINCT" : "") + " * from " + SEPARATOR
                    + name
                    + " r1, " + SEPARATOR + coloName + " r2, r2.positions.values pos2 where "
                    + queries[j])
                .execute(func).getResult();

            r[j][0] = ((ArrayList) funcResult).get(0);
            getCache().getLogger().info("About to execute local query: " + queries[j]);

            SelectResults r2 = (SelectResults) qs.newQuery("Select "
                + (queries[j].contains("ORDER BY") ? "DISTINCT" : "") + " * from " + SEPARATOR
                + localName
                + " r1, " + SEPARATOR + coloLocalName + " r2, r2.positions.values pos2 where "
                + queries[j])
                .execute();
            r[j][1] = r2.asList();
          }
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Queries Executed successfully on Local region & PR Region");

          // compareTwoQueryResults(r, queries.length);
          StructSetOrResultsSet ssORrs = new StructSetOrResultsSet();
          ssORrs.CompareQueryResultsAsListWithoutAndWithIndexes(r, queries.length, false, false,
              queries);

        } catch (QueryInvocationTargetException e) {
          // throw an unchecked exception so the controller can examine the
          // cause and see whether or not it's okay
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        } catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        } catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }
    };
    return PrRegion;

  }


  SerializableRunnableIF getCacheSerializableRunnableForPRAndRRQueryWithCompactAndRangeIndexAndCompareResults(
      final String name, final String coloName, final String localName,
      final String coloLocalName) {

    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the PR region

        String[] queries = new String[] {"r1.ID = pos2.id", "r1.ID = pos2.id AND r1.ID > 5",
            "r1.ID = pos2.id AND r1.status = 'active'", "r1.ID = pos2.id ORDER BY r1.ID",
            "r1.ID = pos2.id ORDER BY pos2.id", "r1.ID = pos2.id ORDER BY r2.status",
            "r1.ID = pos2.id AND r1.status != r2.status",
            "r1.ID = pos2.id AND r1.status = r2.status",
            "r1.ID = pos2.id AND r1.positions.size = r2.positions.size",
            "r1.ID = pos2.id AND r1.positions.size > r2.positions.size",
            "r1.ID = pos2.id AND r1.positions.size < r2.positions.size",
            "r1.ID = pos2.id AND r1.positions.size = r2.positions.size AND r2.positions.size > 0",
            "r1.ID = pos2.id AND (r1.positions.size > r2.positions.size OR r2.positions.size > 0)",
            "r1.ID = pos2.id AND (r1.positions.size < r2.positions.size OR r1.positions.size > 0)",};

        Object[][] r = new Object[queries.length][2];
        Region region = cache.getRegion(name);
        assertNotNull(region);
        region = cache.getRegion(coloName);
        assertNotNull(region);
        region = cache.getRegion(localName);
        assertNotNull(region);
        region = cache.getRegion(coloLocalName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String expectedException : expectedExceptions) {
          getCache().getLogger()
              .info("<ExpectedException action=add>" + expectedException + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        try {
          for (int j = 0; j < queries.length; j++) {
            getCache().getLogger().info("About to execute local query: " + queries[j]);
            Function func = new TestQueryFunction("testfunction");

            Object funcResult = FunctionService
                .onRegion((getCache().getRegion(name) instanceof PartitionedRegion)
                    ? getCache().getRegion(name) : getCache().getRegion(coloName))
                .setArguments("<trace> Select "
                    + (queries[j].contains("ORDER BY") ? "DISTINCT" : "") + " * from " + SEPARATOR
                    + name
                    + " r1, " + SEPARATOR + coloName + " r2, r2.positions.values pos2 where "
                    + queries[j])
                .execute(func).getResult();

            r[j][0] = ((ArrayList) funcResult).get(0);
            getCache().getLogger().info("About to execute local query: " + queries[j]);

            SelectResults r2 = (SelectResults) qs.newQuery("Select "
                + (queries[j].contains("ORDER BY") ? "DISTINCT" : "") + " * from " + SEPARATOR
                + localName
                + " r1, " + SEPARATOR + coloLocalName + " r2, r2.positions.values pos2 where "
                + queries[j])
                .execute();
            r[j][1] = r2.asList();
          }
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Queries Executed successfully on Local region & PR Region");

          StructSetOrResultsSet ssORrs = new StructSetOrResultsSet();
          ssORrs.CompareQueryResultsAsListWithoutAndWithIndexes(r, queries.length, false, false,
              queries);

        } catch (QueryInvocationTargetException e) {
          // throw an unchecked exception so the controller can examine the
          // cause and see whether or not it's okay
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        } catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        } catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }
    };
    return PrRegion;

  }


  public SerializableRunnableIF getCacheSerializableRunnableForRRAndPRQueryAndCompareResults(
      final String name, final String coloName, final String localName,
      final String coloLocalName) {

    SerializableRunnable PrRegion = new CacheSerializableRunnable("PRQuery") {
      @Override
      public void run2() throws CacheException {

        Cache cache = getCache();
        // Querying the PR region

        String[] queries = new String[] {"r1.ID = r2.id",
            "r1.ID = r2.id AND r1.ID > 5",
            "r1.ID = r2.id AND r1.status = 'active'",
            // "r1.ID = r2.id LIMIT 10",
            "r1.ID = r2.id ORDER BY r1.ID",
            "r1.ID = r2.id ORDER BY r2.id",
            "r1.ID = r2.id ORDER BY r2.status",
            "r1.ID = r2.id AND r1.status != r2.status",
            "r1.ID = r2.id AND r1.status = r2.status",
            "r1.ID = r2.id AND r1.positions.size = r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size > r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size < r2.positions.size",
            "r1.ID = r2.id AND r1.positions.size = r2.positions.size AND r2.positions.size > 0",
            "r1.ID = r2.id AND (r1.positions.size > r2.positions.size OR r2.positions.size > 0)",
            "r1.ID = r2.id AND (r1.positions.size < r2.positions.size OR r1.positions.size > 0)",};

        Object[][] r = new Object[queries.length][2];
        Region region = cache.getRegion(name);
        assertNotNull(region);
        region = cache.getRegion(coloName);
        assertNotNull(region);
        region = cache.getRegion(localName);
        assertNotNull(region);
        region = cache.getRegion(coloLocalName);
        assertNotNull(region);

        final String[] expectedExceptions =
            new String[] {RegionDestroyedException.class.getName(), ReplyException.class.getName(),
                CacheClosedException.class.getName(), ForceReattemptException.class.getName(),
                QueryInvocationTargetException.class.getName()};

        for (final String expectedException : expectedExceptions) {
          getCache().getLogger()
              .info("<ExpectedException action=add>" + expectedException + "</ExpectedException>");
        }

        QueryService qs = getCache().getQueryService();
        try {
          for (int j = 0; j < queries.length; j++) {
            getCache().getLogger().info("About to execute local query: " + queries[j]);
            Function func = new TestQueryFunction("testfunction");

            Object funcResult = FunctionService
                .onRegion((getCache().getRegion(name) instanceof PartitionedRegion)
                    ? getCache().getRegion(name) : getCache().getRegion(coloName))
                .setArguments("<trace> Select "
                    + (queries[j].contains("ORDER BY") ? "DISTINCT" : "") + " * from " + SEPARATOR
                    + name
                    + " r1, r1.positions.values pos1, " + SEPARATOR + coloName + " r2 where "
                    + queries[j])
                .execute(func).getResult();

            r[j][0] = ((ArrayList) funcResult).get(0);
            getCache().getLogger().info("About to execute local query: " + queries[j]);

            SelectResults r2 = (SelectResults) qs.newQuery("Select "
                + (queries[j].contains("ORDER BY") ? "DISTINCT" : "") + " * from " + SEPARATOR
                + localName
                + " r1, r1.positions.values pos1, " + SEPARATOR + coloLocalName + " r2 where "
                + queries[j])
                .execute();
            r[j][1] = r2.asList();
          }
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Queries Executed successfully on Local region & PR Region");

          StructSetOrResultsSet ssORrs = new StructSetOrResultsSet();
          ssORrs.CompareQueryResultsAsListWithoutAndWithIndexes(r, queries.length, false, false,
              queries);

        } catch (QueryInvocationTargetException e) {
          // throw an unchecked exception so the controller can examine the
          // cause and see whether or not it's okay
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        } catch (QueryException e) {
          LogWriterUtils.getLogWriter().error(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught QueryException while querying"
                  + e,
              e);
          throw new TestException(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught unexpected query exception",
              e);
        } catch (RegionDestroyedException rde) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a RegionDestroyedException while querying as expected ",
              rde);

        } catch (CancelException cce) {
          LogWriterUtils.getLogWriter().info(
              "PRQueryDUnitHelper#getCacheSerializableRunnableForPRQueryAndCompareResults: Caught a CancelException while querying as expected ",
              cce);

        } finally {
          for (final String expectedException : expectedExceptions) {
            getCache().getLogger().info(
                "<ExpectedException action=remove>" + expectedException + "</ExpectedException>");
          }
        }

      }
    };
    return PrRegion;
  }

  public static class TestQueryFunction implements Function {

    @Override
    public boolean hasResult() {
      return true;
    }

    @Override
    public boolean isHA() {
      return false;
    }

    private final String id;

    TestQueryFunction(String id) {
      this.id = id;
    }

    @Override
    public void execute(FunctionContext context) {
      Cache cache = context.getCache();
      QueryService queryService = cache.getQueryService();
      String qstr = (String) context.getArguments();
      try {
        Query query = queryService.newQuery(qstr);
        context.getResultSender().sendResult(
            ((SelectResults) query.execute((RegionFunctionContext) context)).asList());
        context.getResultSender().lastResult(null);
      } catch (Exception e) {
        throw new FunctionException(e);
      }
    }

    @Override
    public String getId() {
      return id;
    }
  }

  SerializableRunnable getCacheSerializableRunnableForCloseCache() {
    return new SerializableRunnable() {
      @Override
      public void run() {
        JUnit4CacheTestCase.closeCache();
      }
    };
  }
}
