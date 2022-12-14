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
package org.apache.geode.cache.query.internal.index;

import java.util.Collection;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.query.IndexStatistics;
import org.apache.geode.internal.cache.BucketRegion;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.RegionEntry;

public class MapRangeIndex extends AbstractMapIndex {
  protected final RegionEntryToValuesMap entryToMapKeysMap;

  MapRangeIndex(InternalCache cache, String indexName, Region region, String fromClause,
      String indexedExpression, String projectionAttributes, String origFromClause,
      String origIndxExpr, String[] defintions, boolean isAllKeys,
      String[] multiIndexingKeysPattern, Object[] mapKeys, IndexStatistics stats) {
    super(cache, indexName, region, fromClause, indexedExpression, projectionAttributes,
        origFromClause, origIndxExpr, defintions, isAllKeys, multiIndexingKeysPattern, mapKeys,
        stats);
    RegionAttributes ra = region.getAttributes();
    entryToMapKeysMap =
        new RegionEntryToValuesMap(
            new java.util.concurrent.ConcurrentHashMap(ra.getInitialCapacity(), ra.getLoadFactor(),
                ra.getConcurrencyLevel()),
            true /* user target list as the map keys will be unique */);
  }

  @Override
  void recreateIndexData() throws IMQException {
    /*
     * Asif : Mark the data maps to null & call the initialization code of index
     */
    // TODO:Asif : The statistics data needs to be modified appropriately
    // for the clear operation
    mapKeyToValueIndex.clear();
    entryToMapKeysMap.clear();
    initializeIndex(true);
  }

  @Override
  public boolean containsEntry(RegionEntry entry) {
    // TODO:Asif: take care of null mapped entries
    /*
     * return (this.entryToValuesMap.containsEntry(entry) ||
     * this.nullMappedEntries.containsEntry(entry) || this.undefinedMappedEntries
     * .containsEntry(entry));
     */
    return entryToMapKeysMap.containsEntry(entry);
  }

  @Override
  void addMapping(RegionEntry entry) throws IMQException {
    evaluator.evaluate(entry, true);
    addSavedMappings(entry);
    clearCurrState();
  }

  public void clearCurrState() {
    for (Object rangeInd : mapKeyToValueIndex.values()) {
      ((RangeIndex) rangeInd).clearCurrState();
    }
  }

  private void addSavedMappings(RegionEntry entry) throws IMQException {
    for (Object rangeInd : mapKeyToValueIndex.values()) {
      ((RangeIndex) rangeInd).addSavedMappings(entry);
    }
  }

  @Override
  protected void removeMapping(RegionEntry entry, int opCode) throws IMQException {
    // this implementation has a reverse map, so it doesn't handle
    // BEFORE_UPDATE_OP
    if (opCode == BEFORE_UPDATE_OP || opCode == CLEAN_UP_THREAD_LOCALS) {
      return;
    }

    Object values = entryToMapKeysMap.remove(entry);
    // Values in reverse coould be null if map in region value does not
    // contain any key which matches to index expression keys.
    if (values == null) {
      return;
    }
    if (values instanceof Collection) {
      for (final Object key : (Collection) values) {
        RangeIndex ri = (RangeIndex) mapKeyToValueIndex.get(key);
        long start = System.nanoTime();
        internalIndexStats.incUpdatesInProgress(1);
        ri.removeMapping(entry, opCode);
        internalIndexStats.incUpdatesInProgress(-1);
        long end = -start;
        internalIndexStats.incUpdateTime(end);
      }
    } else {
      RangeIndex ri = (RangeIndex) mapKeyToValueIndex.get(values);
      long start = System.nanoTime();
      internalIndexStats.incUpdatesInProgress(1);
      ri.removeMapping(entry, opCode);
      internalIndexStats.incUpdatesInProgress(-1);
      long end = System.nanoTime() - start;
      internalIndexStats.incUpdateTime(end);
    }
  }

  @Override
  protected void doIndexAddition(Object mapKey, Object indexKey, Object value, RegionEntry entry)
      throws IMQException {
    boolean isPr = region instanceof BucketRegion;
    // Get RangeIndex for it or create it if absent
    RangeIndex rg = (RangeIndex) mapKeyToValueIndex.get(mapKey);
    if (rg == null) {
      // use previously created MapRangeIndexStatistics
      IndexStatistics stats = internalIndexStats;
      PartitionedIndex prIndex = null;
      if (isPr) {
        prIndex = (PartitionedIndex) getPRIndex();
        prIndex.incNumMapKeysStats(mapKey);
      }
      rg = new RangeIndex(cache, indexName + "-" + mapKey, region, fromClause, indexedExpression,
          projectionAttributes, originalFromClause, originalIndexedExpression,
          canonicalizedDefinitions, stats);
      // Shobhit: We need evaluator to verify RegionEntry and IndexEntry inconsistency.
      rg.evaluator = evaluator;
      mapKeyToValueIndex.put(mapKey, rg);
      if (!isPr) {
        internalIndexStats.incNumMapIndexKeys(1);
      }
    }
    internalIndexStats.incUpdatesInProgress(1);
    long start = System.nanoTime();
    rg.addMapping(indexKey, value, entry);
    // This call is skipped when addMapping is called from MapRangeIndex
    // rg.internalIndexStats.incNumUpdates();
    internalIndexStats.incUpdatesInProgress(-1);
    long end = System.nanoTime() - start;
    internalIndexStats.incUpdateTime(end);
    entryToMapKeysMap.add(entry, mapKey);
  }

  @Override
  protected void saveIndexAddition(Object mapKey, Object indexKey, Object value, RegionEntry entry)
      throws IMQException {
    boolean isPr = region instanceof BucketRegion;
    // Get RangeIndex for it or create it if absent
    RangeIndex rg = (RangeIndex) mapKeyToValueIndex.get(mapKey);
    if (rg == null) {
      // use previously created MapRangeIndexStatistics
      IndexStatistics stats = internalIndexStats;
      PartitionedIndex prIndex = null;
      if (isPr) {
        prIndex = (PartitionedIndex) getPRIndex();
        prIndex.incNumMapKeysStats(mapKey);
      }
      rg = new RangeIndex(cache, indexName + "-" + mapKey, region, fromClause, indexedExpression,
          projectionAttributes, originalFromClause, originalIndexedExpression,
          canonicalizedDefinitions, stats);
      rg.evaluator = evaluator;
      mapKeyToValueIndex.put(mapKey, rg);
      if (!isPr) {
        internalIndexStats.incNumMapIndexKeys(1);
      }
    }
    // rg.internalIndexStats.incUpdatesInProgress(1);
    long start = System.nanoTime();
    rg.saveMapping(indexKey, value, entry);
    // This call is skipped when addMapping is called from MapRangeIndex
    // rg.internalIndexStats.incNumUpdates();
    internalIndexStats.incUpdatesInProgress(-1);
    long end = System.nanoTime() - start;
    internalIndexStats.incUpdateTime(end);
    entryToMapKeysMap.add(entry, mapKey);
  }
}
