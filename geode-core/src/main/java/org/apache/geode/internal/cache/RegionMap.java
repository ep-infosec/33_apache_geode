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
package org.apache.geode.internal.cache;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.geode.cache.CacheCallback;
import org.apache.geode.cache.CacheEvent;
import org.apache.geode.cache.CacheWriterException;
import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.Operation;
import org.apache.geode.cache.TimeoutException;
import org.apache.geode.cache.TransactionId;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.entries.DiskEntry;
import org.apache.geode.internal.cache.eviction.EvictableEntry;
import org.apache.geode.internal.cache.eviction.EvictableMap;
import org.apache.geode.internal.cache.tier.sockets.ClientProxyMembershipID;
import org.apache.geode.internal.cache.versions.RegionVersionVector;
import org.apache.geode.internal.cache.versions.VersionHolder;
import org.apache.geode.internal.cache.versions.VersionSource;
import org.apache.geode.internal.cache.versions.VersionTag;
import org.apache.geode.internal.util.concurrent.ConcurrentMapWithReusableEntries;

/**
 * Internal interface used by {@link LocalRegion} to access the map that holds its entries. Note
 * that the value of every entry in this map will implement {@link RegionEntry}.
 *
 * @since GemFire 3.5.1
 */
public interface RegionMap extends EvictableMap {

  interface ARMLockTestHook {
    void beforeBulkLock(InternalRegion region);

    void afterBulkLock(InternalRegion region);

    void beforeBulkRelease(InternalRegion region);

    void afterBulkRelease(InternalRegion region);

    void beforeLock(InternalRegion region, CacheEvent event);

    void afterLock(InternalRegion region, CacheEvent event);

    void beforeRelease(InternalRegion region, CacheEvent event);

    void afterRelease(InternalRegion region, CacheEvent event);

    void beforeStateFlushWait();
  }

  /**
   * Parameter object used to facilitate construction of an EntriesMap. Modification of fields after
   * the map is constructed has no effect.
   */
  class Attributes {
    /**
     * The initial capacity. The implementation performs internal sizing to accommodate this many
     * elements.
     */
    int initialCapacity = 16;

    /** the load factor threshold, used to control resizing. */
    float loadFactor = 0.75f;

    /**
     * the estimated number of concurrently updating threads. The implementation performs internal
     * sizing to try to accommodate this many threads.
     */
    int concurrencyLevel = 16;

    /** whether "api" statistics are enabled */
    boolean statisticsEnabled = false;
  }

  RegionEntryFactory getEntryFactory();

  /**
   * This method should be called before region is initialized to ensure there is no mix of region
   * entries
   */
  void setEntryFactory(RegionEntryFactory f);

  /**
   * Gets the attributes that this map was created with.
   */
  Attributes getAttributes();

  void changeOwner(LocalRegion r);

  int size();

  boolean isEmpty();

  /**
   * @return number of entries cached in the backing CHM
   */
  int sizeInVM();

  Set keySet();

  /**
   * Returns a collection of RegionEntry instances.
   */
  Collection<RegionEntry> regionEntries();

  /**
   * Returns a collection of RegionEntry instances from memory only.
   */
  Collection<RegionEntry> regionEntriesInVM();

  boolean containsKey(Object key);

  /**
   * fetches the entry from the backing ConcurrentHashMap
   *
   * @return the RegionEntry from memory or disk
   */
  RegionEntry getEntry(Object key);

  RegionEntry putEntryIfAbsent(Object key, RegionEntry re);

  /**
   * fetches the entry from the backing ConcurrentHashMap.
   *
   * @return the RegionEntry from memory
   */
  RegionEntry getEntryInVM(Object key);

  /**
   * fetches the entry from the backing ConcurrentHashMap only if the entry is considered to be in
   * operational data i.e. does not have isMarkedForEviction() bit set.
   *
   * @return the RegionEntry in operational data
   */
  RegionEntry getOperationalEntryInVM(Object key);

  /**
   * Clear the region and, if the parameter rvv is not null, return a collection of the IDs of
   * version sources that are still in the map when the operation completes.
   */
  Set<VersionSource> clear(RegionVersionVector rvv, BucketRegion bucketRegion);

  /**
   * Used by disk regions when recovering data from backup. Currently this "put" is done at a very
   * low level to keep it from generating events or pushing updates to others.
   *
   * @return the created RegionEntry or null if entry already existed
   */
  RegionEntry initRecoveredEntry(Object key, DiskEntry.RecoveredEntry value);

  /**
   * Used by disk regions when recovering data from backup and initRecoveredEntry has already been
   * called for the given key. Currently this "put" is done at a very low level to keep it from
   * generating events or pushing updates to others.
   *
   * @return the updated RegionEntry
   */
  RegionEntry updateRecoveredEntry(Object key, DiskEntry.RecoveredEntry value);

  /**
   * Used to modify an existing RegionEntry or create a new one when processing the values obtained
   * during a getInitialImage.
   *
   * @param wasRecovered true if the current entry in the cache was recovered from disk.
   * @param entryVersion version information from InitialImageOperation or RegisterInterest
   * @param sender the sender of the initial image, if IIO. Not needed on clients
   * @param forceValue TODO
   */
  boolean initialImagePut(Object key, long lastModified, Object newValue, boolean wasRecovered,
      boolean deferLRUCallback, VersionTag entryVersion, InternalDistributedMember sender,
      boolean forceValue);

  /**
   * Destroy an entry the map.
   *
   * @param event indicates entry to destroy as well as data for a <code>CacheCallback</code>
   * @param inTokenMode true if destroy is occurring during region initialization
   * @param duringRI true if destroy is occurring during register interest
   * @param cacheWrite true if a cacheWriter should be called
   * @param isEviction true if destroy was called in the context of an LRU Eviction
   * @param expectedOldValue if non-null, only destroy if key exists and value is is equal to
   *        expectedOldValue
   * @return true if the entry was destroyed, false otherwise
   *
   * @see LocalRegion
   * @see AbstractRegionMap
   * @see CacheCallback
   */
  boolean destroy(EntryEventImpl event, boolean inTokenMode, boolean duringRI, boolean cacheWrite,
      boolean isEviction, Object expectedOldValue, boolean removeRecoveredEntry)
      throws CacheWriterException, EntryNotFoundException, TimeoutException;

  /**
   * @param forceNewEntry used during GII, this forces us to leave an invalid token in the cache,
   *        even if the entry doesn't exist
   * @param forceCallbacks using for PRs with eviction enabled, this forces invalidate callbacks and
   *        events even if the entry doesn't exist in the cache. This differs from the forceNewEntry
   *        mode in that it doesn't leave an Invalid token in the cache.
   * @return true if invalidate was done
   */
  boolean invalidate(EntryEventImpl event, boolean invokeCallbacks, boolean forceNewEntry,
      boolean forceCallbacks) throws EntryNotFoundException;

  void evictValue(Object key);

  /**
   * @param event the event object for this operation, with the exception that the oldValue
   *        parameter is not yet filled in. The oldValue will be filled in by this operation.
   *
   * @param lastModified the lastModified time to set with the value; if 0L, then the lastModified
   *        time will be set to now.
   * @param ifNew true if this operation must not overwrite an existing key
   * @param ifOld true if this operation must not create a new entry
   * @param expectedOldValue only succeed if old value is equal to this value. If null, then doesn't
   *        matter what old value is. If INVALID token, must be INVALID.
   * @param requireOldValue if old value needs to be returned to caller in event (e.g. failed
   *        putIfAbsent)
   * @param overwriteDestroyed true if okay to overwrite the DESTROYED token: when this is true has
   *        the following effect: even when ifNew is true will write over DESTROYED token when
   *        overwriteDestroyed is false and ifNew or ifOld is true then if the put doesn't occur
   *        because there is a DESTROYED token present then the entry flag blockedDestroyed is set.
   * @return null if put was not done; otherwise reference to put entry
   */
  RegionEntry basicPut(EntryEventImpl event, long lastModified, boolean ifNew, boolean ifOld,
      Object expectedOldValue, boolean requireOldValue, boolean overwriteDestroyed)
      throws CacheWriterException, TimeoutException;

  /**
   * Write synchronizes the given entry and invokes the runable while holding the lock. Does nothing
   * if the entry does not exist.
   */
  void writeSyncIfPresent(Object key, Runnable runner);

  /**
   * Remove the entry with the given key if it has been marked as destroyed This is currently used
   * in the cleanup phase of getInitialImage.
   */
  void removeIfDestroyed(Object key);

  /**
   * @param key the key of the entry to destroy
   * @param rmtOrigin true if transaction being applied had a remote origin
   * @param event filled in if operation performed
   * @param inTokenMode true if caller has determined we are in destroy token mode and will keep us
   *        in that mode while this call is executing.
   * @param inRI the region is performing registerInterest so we need a token
   * @param op the destroy operation to apply
   * @param eventId filled in if operation performed
   * @param aCallbackArgument callback argument passed by user
   * @param isOperationRemote whether the operation is remote or originated here
   * @param txEntryState when not null, txEntryState.versionTag is set (used on near-side to pass
   *        versionTag to TXCommitMessage)
   * @param versionTag when not null, it is the tag generated on near-side to be associated with the
   *        entry on far-side
   * @param tailKey when not -1, it is the tailKey generated on near-side to be associated with
   *        entry on far-side for WAN
   */
  void txApplyDestroy(Object key, TransactionId rmtOrigin, TXRmtEvent event, boolean inTokenMode,
      boolean inRI, Operation op, EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks, FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, boolean isOperationRemote, TXEntryState txEntryState,
      VersionTag versionTag, long tailKey);

  /**
   * @param key the key of the entry to invalidate
   * @param newValue the new value of the entry
   * @param didDestroy true if tx destroyed this entry at some point
   * @param rmtOrigin true if transaction being applied had a remote origin
   * @param event filled in if operation performed
   * @param localOp true for localInvalidates, false otherwise
   * @param aCallbackArgument callback argument passed by user
   * @param txEntryState when not null, txEntryState.versionTag is set (used on near-side to pass
   *        versionTag to TXCommitMessage)
   * @param versionTag when not null, it is the tag generated on near-side to be associated with the
   *        entry on far-side
   * @param tailKey when not -1, it is the tailKey generated on near-side to be associated with
   *        entry on far-side for WAN
   */
  void txApplyInvalidate(Object key, Object newValue, boolean didDestroy, TransactionId rmtOrigin,
      TXRmtEvent event, boolean localOp, EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks, FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, TXEntryState txEntryState, VersionTag versionTag,
      long tailKey);

  /**
   * @param putOp describes the operation that did the put
   * @param key the key of the entry to put
   * @param newValue the new value of the entry
   * @param didDestroy true if tx destroyed this entry at some point
   * @param rmtOrigin true if transaction being applied had a remote origin
   * @param event filled in if operation performed
   * @param aCallbackArgument callback argument passed by user
   * @param txEntryState when not null, txEntryState.versionTag is set (used on near-side to pass
   *        versionTag to TXCommitMessage)
   * @param versionTag when not null, it is the tag generated on near-side to be associated with the
   *        entry on far-side
   * @param tailKey when not -1, it is the tailKey generated on near-side to be associated with
   *        entry on far-side for WAN
   */
  void txApplyPut(Operation putOp, Object key, Object newValue, boolean didDestroy,
      TransactionId rmtOrigin, TXRmtEvent event, EventID eventId, Object aCallbackArgument,
      List<EntryEventImpl> pendingCallbacks, FilterRoutingInfo filterRoutingInfo,
      ClientProxyMembershipID bridgeContext, TXEntryState txEntryState, VersionTag versionTag,
      long tailKey);

  /**
   * removes the given key if the enclosing RegionEntry is still in this map
   */
  void removeEntry(Object key, RegionEntry value, boolean updateStats);

  void copyRecoveredEntries(RegionMap rm);

  /**
   * Removes an entry that was previously destroyed and made into a tombstone.
   *
   * @param re the entry that was destroyed
   * @param destroyedVersion the version that was destroyed
   * @return true if the tombstone entry was removed from the entry map
   */
  boolean removeTombstone(RegionEntry re, VersionHolder destroyedVersion);

  /**
   * Checks to see if the given version is still the version in the map
   *
   * @param re the entry that was destroyed
   * @param destroyedVersion the version that was destroyed
   * @return true of the tombstone is no longer needed (entry was resurrected or evicted)
   */
  boolean isTombstoneNotNeeded(RegionEntry re, int destroyedVersion);

  void updateEntryVersion(EntryEventImpl event);

  /**
   * Decrements the transaction reference count. Some features, like eviction and expiration, will
   * not modify an entry while it is referenced by a transaction.
   */
  void decTxRefCount(RegionEntry e);

  void close(BucketRegion bucketRegion);

  default void lockRegionForAtomicTX(InternalRegion r) {}

  default void unlockRegionForAtomicTX(InternalRegion r) {}

  ARMLockTestHook getARMLockTestHook();

  long getEvictions();

  void incRecentlyUsed();

  /**
   * Returns the memory overhead of entries in this map
   */
  int getEntryOverhead();

  boolean beginChangeValueForm(EvictableEntry le, CachedDeserializable vmCachedDeserializable,
      Object v);

  void finishChangeValueForm();

  int centralizedLruUpdateCallback();

  void updateEvictionCounter();

  ConcurrentMapWithReusableEntries<Object, Object> getCustomEntryConcurrentHashMap();

  void setEntryMap(ConcurrentMapWithReusableEntries<Object, Object> map);
}
