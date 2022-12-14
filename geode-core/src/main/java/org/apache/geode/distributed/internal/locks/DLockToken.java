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

package org.apache.geode.distributed.internal.locks;

import java.util.WeakHashMap;

import org.apache.logging.log4j.Logger;

import org.apache.geode.distributed.LeaseExpiredException;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * A DistributedLockService contains a collection of DLockToken instances, one for each name in that
 * DistributedLockService for which a lock has ever been requested. The token identifies whether
 * that name is currently locked, and which distribution manager and thread owns the lock.
 *
 */
public class DLockToken {
  private static final Logger logger = LogService.getLogger();

  // -------------------------------------------------------------------------
  // Instance variables
  // -------------------------------------------------------------------------

  /**
   * Lock name for this lock. Logically final but set by fromData.
   */
  private final Object name;

  /**
   * DistributionManager using this lock token. Reference is used to identify local member identity
   * and to {@link DLockService#getLockTimeStamp(DistributionManager)}.
   */
  private final DistributionManager dm;

  /**
   * The reply processor id is used to identify the distinct lease which a thread has used to lease
   * this lock.
   */
  private int leaseId = -1;

  /**
   * The absolute time at which the current lease on this lock will expire. -1 represents a lease
   * which will not expire until explicitly released.
   */
  private long leaseExpireTime = -1;

  /**
   * Remotable identity of thread currently leasing this lock.
   */
  private RemoteThread lesseeThread = null;

  /**
   * Counter that indicates number of times this lock has been re-entered for the current lease.
   */
  private int recursion;

  /**
   * Tracks expired leases so that the leasing thread can report a
   * {@link org.apache.geode.distributed.LeaseExpiredException}. Keys are threads that have had
   * their lease expire on this lock, but may not yet have noticed. Would use weak set if available.
   * Entry is removed upon throwing LeaseExpiredException. Protected by synchronization on this lock
   * token.
   */
  private WeakHashMap expiredLeases;

  /**
   * Actual local thread that currently has a lease on this lock.
   */
  private Thread thread;

  /**
   * Number of usages of this lock token. usageCount = recursion + (# of threads waiting for this
   * lock). It's weird, I know.
   */
  private int usageCount = 0;

  /**
   * True if this lock token has been destroyed to free up resources.
   */
  private boolean destroyed = false;

  /**
   * True if this lock token should be ignored for remote grantor recovery.
   */
  private boolean ignoreForRecovery = false;

  // -------------------------------------------------------------------------
  // Constructors
  // -------------------------------------------------------------------------

  /**
   * Instantiates a new DLockToken for use by {@link DLockService}.
   *
   * @param dm the DistributionManager for this member
   * @param name the identifying name of this lock
   */
  public DLockToken(DistributionManager dm, Object name) {
    this.dm = dm;
    this.name = name;
  }

  // -------------------------------------------------------------------------
  // Public accessors
  // -------------------------------------------------------------------------

  /**
   * Returns the lock re-entry recursion of the current lease or -1 if there is no current lease.
   * Caller must synchronize on this lock token.
   * <p>
   * Public because {@link org.apache.geode.internal.admin.remote.RemoteDLockInfo} is a caller.
   *
   * @return the lock re-entry recursion of the current lease or -1 if none
   */
  public int getRecursion() {
    return recursion;
  }

  /**
   * Returns the name of the actual local thread leasing this lock or null if there is no lease.
   * Caller must synchronize on this lock token.
   * <p>
   * Public because {@link org.apache.geode.internal.admin.remote.RemoteDLockInfo} is a caller.
   *
   * @return the name of the actual local thread leasing this lock or null
   */
  public String getThreadName() {
    return thread == null ? null : thread.getName();
  }

  /**
   * Returns the actual local thread leasing this lock or null if there is no lease.
   */
  public synchronized Thread getThread() {
    return thread;
  }

  /**
   * Returns the absolute time at which the current lease will expire or -1 if there is no lease.
   * Caller must synchronize on this lock token.
   * <p>
   * Public because {@link org.apache.geode.internal.admin.remote.RemoteDLockInfo} is a caller.
   *
   * @return the absolute time at which the current lease will expire or -1
   */
  public long getLeaseExpireTime() {
    return leaseExpireTime;
  }

  public int getUsageCount() {
    return usageCount;
  }

  // -------------------------------------------------------------------------
  // Package accessors
  // -------------------------------------------------------------------------

  /**
   * Returns the identifying name of this lock. Caller must synchronize on this lock token if
   * instance was deserialized.
   *
   * @return the identifying name of this lock
   */
  Object getName() {
    return name;
  }

  /**
   * Returns the lease id currently used to hold a lease on this lock or -1 if no thread currently
   * holds this lock. Caller must synchronize on this token.
   *
   * @return the id of the current lease on this lock or -1 if none
   */
  int getLeaseId() {
    return leaseId;
  }

  /**
   * Returns the remotable identity of the thread currently leasing this lock or null if no thread
   * currently holds this lock. Caller must synchronize on this lock token.
   *
   * @return identity of the thread holding the current lease or null if none
   */
  RemoteThread getLesseeThread() {
    return lesseeThread;
  }

  /**
   * Increment usage count for this lock token. Caller must synchronize on this lock token.
   */
  void incUsage() {
    incUsage(1);
  }

  /**
   * Decrement usage count for this lock token. Caller must synchronize on this lock token.
   */
  void decUsage() {
    incUsage(-1);
  }

  /**
   * Returns true if the usage count for this lock token is greater than zero. Caller must
   * synchronize on this lock token.
   *
   * @return true if the usage count for this lock token is greater than zero
   */
  boolean isBeingUsed() {
    return usageCount > 0;
  }

  // -------------------------------------------------------------------------
  // Package operations
  // -------------------------------------------------------------------------

  /**
   * Destroys this lock token.
   */
  synchronized void destroy() {
    destroyed = true;
  }

  /**
   * Returns the current time in absolute milliseconds for use calculating lease expiration times.
   *
   * @return the current time in absolute milliseconds
   */
  long getCurrentTime() {
    if (dm == null) {
      return -1;
    }
    return DLockService.getLockTimeStamp(dm);
  }

  /**
   * Throws LeaseExpiredException if the calling thread's lease on this lock previously expired. The
   * expired lease will no longer be tracked after throwing LeaseExpiredException. Caller must
   * synchronize on this lock token.
   *
   * @throws LeaseExpiredException if calling thread's lease expired
   */
  void throwIfCurrentThreadHadExpiredLease() throws LeaseExpiredException {
    if (expiredLeases == null) {
      return;
    }
    if (expiredLeases.containsKey(Thread.currentThread())) {
      expiredLeases.remove(Thread.currentThread());
      throw new LeaseExpiredException(
          "This thread's lease expired for this lock");
    }
  }

  /**
   * Checks the current lease for expiration and returns true if it has been marked as expired.
   * Caller must synchronize on this lock token.
   *
   * @return true if the current lease has been marked as expired
   */
  boolean checkForExpiration() {
    boolean expired = false;

    // check if lease exists and lease expire is not MAX_VALUE
    if (leaseId > -1 && leaseExpireTime < Long.MAX_VALUE) {

      long currentTime = getCurrentTime();
      if (currentTime > leaseExpireTime) {
        if (logger.isTraceEnabled(LogMarker.DLS_VERBOSE)) {
          logger.trace(LogMarker.DLS_VERBOSE, "[checkForExpiration] Expiring token at {}: {}",
              currentTime, this);
        }
        noteExpiredLease();
        basicReleaseLock();
        expired = true;
      }
    }

    return expired;
  }

  /**
   * Grants new lease to calling thread for this lock token. Synchronizes on this lock token.
   *
   * @param newLeaseExpireTime absolute expiration in millis or Long.MAX_VALUE
   * @param newLeaseId uniquely identifies the lease for this thread
   * @param newRecursion recursion count if lock has been re-entered
   * @param remoteThread identity of the leasing thread
   */
  synchronized void grantLock(long newLeaseExpireTime, int newLeaseId, int newRecursion,
      RemoteThread remoteThread) {

    Assert.assertTrue(remoteThread != null);
    Assert.assertTrue(newLeaseId > -1, "Invalid attempt to grant lock with leaseId " + newLeaseId);

    checkDestroyed();
    checkForExpiration(); // TODO: this should throw.

    ignoreForRecovery = false;
    leaseExpireTime = newLeaseExpireTime;
    leaseId = newLeaseId;
    lesseeThread = remoteThread;
    recursion = newRecursion;
    thread = Thread.currentThread();

    if (logger.isTraceEnabled(LogMarker.DLS_VERBOSE)) {
      logger.trace(LogMarker.DLS_VERBOSE, "[DLockToken.grantLock.client] granted {}", this);
    }
  }

  /**
   * Returns true if there's currently a lease on this lock token. Synchronizes on this lock token.
   *
   * @return true if there's currently a lease on this lock token
   */
  synchronized boolean isLeaseHeld() {
    return leaseId > -1;
  }

  /**
   * Returns true if lease on this lock token is held by calling thread or the specified remote
   * thread. Caller must synchronize on this lock token.
   *
   * @param remoteThread remotable identity of thread to check for
   * @return true if lease is held by calling thread or remote thread
   */
  boolean isLeaseHeldByCurrentOrRemoteThread(RemoteThread remoteThread) {
    if (isLeaseHeldByCurrentThread()) {
      return true;
    } else {
      return lesseeThread != null && remoteThread != null
          && lesseeThread.equals(remoteThread);
    }
  }

  /**
   * Returns true if lease on this lock token is held by calling thread. Caller must synchronize on
   * this lock token.
   *
   * @return true if lease is held by calling thread
   */
  boolean isLeaseHeldByCurrentThread() {
    return thread == Thread.currentThread();
  }

  /**
   * Returns true if this lock token should be ignored for grantor recovery. Caller must synchronize
   * on this lock token.
   *
   * @return true if this lock token should be ignored for grantor recovery
   */
  synchronized boolean ignoreForRecovery() {
    return ignoreForRecovery;
  }

  /**
   * Sets whether or not this lock token should be ignored for grantor recovery. Caller must
   * synchronize on this lock token.
   *
   * @param value true if this lock token should be ignored for grantor recovery
   */
  void setIgnoreForRecovery(boolean value) {
    ignoreForRecovery = value;
  }

  /**
   * Releases the current lease on this lock token. Synchronizes on this lock token.
   *
   * @param leaseIdToRelease lease id to release
   * @param remoteThread identity of thread holding lease
   * @return true if lock was successfully released
   */
  synchronized boolean releaseLock(int leaseIdToRelease, RemoteThread remoteThread) {
    return releaseLock(leaseIdToRelease, remoteThread, true);
  }

  /**
   * Releases the current lease on this lock token. Synchronizes on this lock token.
   *
   * @param leaseIdToRelease lease id to release
   * @param remoteThread identity of thread holding lease
   * @param decRecursion true if recursion should be decremented
   * @return true if lock was successfully released
   */
  synchronized boolean releaseLock(int leaseIdToRelease, RemoteThread remoteThread,
      boolean decRecursion) {

    if (leaseIdToRelease == -1) {
      return false;
    }
    if (destroyed) {
      return true;
    }

    // return false if not locked by calling thread
    if (!isLeaseHeld(leaseIdToRelease) || !isLeaseHeldByCurrentOrRemoteThread(remoteThread)) {
      return false;
    }

    // reduce recursion if recursion > 0
    else if (decRecursion && getRecursion() > 0) {
      incRecursion(-1);
      decUsage();
      if (logger.isTraceEnabled(LogMarker.DLS_VERBOSE)) {
        logger.trace(LogMarker.DLS_VERBOSE, "[DLockToken.releaseLock] decremented recursion: {}",
            this);
      }
      return true;
    }

    // release lock entirely
    else {
      basicReleaseLock();
      return true;
    }
  }

  /**
   * Nulls out current lease and decrements usage count. Caller must be synchronized on this lock
   * token.
   */
  private void basicReleaseLock() {
    if (logger.isTraceEnabled(LogMarker.DLS_VERBOSE)) {
      logger.trace(LogMarker.DLS_VERBOSE, "[DLockToken.basicReleaseLock] releasing ownership: {}",
          this);
    }

    leaseId = -1;
    lesseeThread = null;
    leaseExpireTime = -1;
    thread = null;
    recursion = 0;
    ignoreForRecovery = false;

    decUsage();
  }

  // -------------------------------------------------------------------------
  // Private implementation methods
  // -------------------------------------------------------------------------

  /**
   * Returns true if lease is held using specified lease id. Caller must synchronize on this lock
   * token.
   *
   * @param memberLeaseId lease id used by member
   * @return true if lease is held using specified lease id
   */
  private boolean isLeaseHeld(int memberLeaseId) {
    return memberLeaseId == leaseId;
  }

  /**
   * Increments or decrements usage count by the specified amount. Caller must synchronize on this
   * lock token.
   *
   * @param amount the amount to inc or dec usage count by
   */
  private void incUsage(int amount) {
    if (amount < 0 && !destroyed) {
      Assert.assertTrue(usageCount - amount >= 0,
          amount + " cannot be subtracted from usageCount " + usageCount);
    }
    usageCount += amount;
  }

  /**
   * Increments or decrements recursion by the specified amount. Caller must synchronize on this
   * lock token.
   *
   * @param amount the amount to inc or dec recursion by
   */
  private void incRecursion(int amount) {
    if (amount < 0) {
      Assert.assertTrue(recursion - amount >= 0,
          amount + " cannot be subtracted from recursion " + recursion);
    }
    recursion += amount;
  }

  /**
   * Throws IllegalStateException if this lock token has been destroyed. Caller must synchronize on
   * this lock token.
   *
   * @throws IllegalStateException if this lock token has been destroyed
   */
  private void checkDestroyed() {
    if (destroyed) {
      IllegalStateException e = new IllegalStateException(
          String.format("Attempting to use destroyed token: %s", this));
      throw e;
    }
  }

  /**
   * Record the token's owning thread as having lost its lease, so it can throw an exception later
   * if it tries to unlock. A weak reference to the thread is used. Caller must synchronize on this
   * lock token.
   */
  private void noteExpiredLease() {
    if (logger.isTraceEnabled(LogMarker.DLS_VERBOSE)) {
      logger.trace(LogMarker.DLS_VERBOSE, "[noteExpiredLease] {}", thread);
    }
    if (expiredLeases == null) {
      expiredLeases = new WeakHashMap();
    }
    expiredLeases.put(thread, null);
  }

  // -------------------------------------------------------------------------
  // java.lang.Object methods
  // -------------------------------------------------------------------------

  /**
   * Returns a string representation of this object.
   */
  @Override
  public String toString() {
    synchronized (this) {
      return "DLockToken" + "@" + Integer.toHexString(hashCode()) + ", name: " + name
          + ", thread: <" + getThreadName() + ">" + ", recursion: " + recursion
          + ", leaseExpireTime: " + leaseExpireTime + ", leaseId: " + leaseId
          + ", ignoreForRecovery: " + ignoreForRecovery + ", lesseeThread: "
          + lesseeThread + ", usageCount: " + usageCount + ", currentTime: "
          + getCurrentTime();
    }
  }
}
