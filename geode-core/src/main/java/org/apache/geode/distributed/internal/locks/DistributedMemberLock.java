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

import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import org.apache.geode.distributed.DistributedLockService;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.locks.DLockService.ThreadRequestState;
import org.apache.geode.internal.Assert;

/**
 * Distributed lock which is owned by a member rather than a single thread. Any thread within the
 * {@link org.apache.geode.distributed.DistributedMember} may unlock a held
 * <code>DistributedMemberLock</code>.
 *
 * While this member holds the lock, another member will not be able to acquire it. Any thread
 * within this member may reenter or unlock the lock.
 *
 * Operations delegate to {@link org.apache.geode.distributed.DistributedLockService} and may throw
 * LockNotHeldException or LockServiceDestroyedException.
 *
 * @since GemFire 5.1
 */
public class DistributedMemberLock implements Lock {

  /** Lock lease timeout value that never expires. */
  public static final long NON_EXPIRING_LEASE = -1;

  /**
   * Defines the behavior when attempting to reenter a held lock.
   *
   */
  public enum LockReentryPolicy {
    /** Allows lock reentry */
    ALLOW,
    /** Throws error if lock reentry is attempted */
    THROW_ERROR,
    /** Silently returns without doing anything if lock reentry is attempted */
    PREVENT_SILENTLY;

    /**
     * Returns true if lock reentry should be rejected.
     *
     * @param lock the lock that reentry is being attempted on
     * @return true if lock reentry should be rejected
     * @throws IllegalStateException if reentry policy is NONREENTRANT_ERROR
     */
    boolean preventReentry(DistributedMemberLock lock) {
      switch (this) {
        case ALLOW:
          return false; // allow
        case THROW_ERROR:
          throw new IllegalStateException("Attempted to reenter held lock " + lock);
        case PREVENT_SILENTLY:
          return true; // reject
      }
      throw new AssertionError("Unknown LockReentryPolicy: " + this);
    }

    @Override
    public String toString() {
      String myToString = "Unknown";
      switch (this) {
        case ALLOW:
          myToString = "ALLOW";
          break;
        case THROW_ERROR:
          myToString = "THROW_ERROR";
          break;
        case PREVENT_SILENTLY:
          myToString = "PREVENT_SILENTLY";
          break;
        default:
          // leave as "Unknown"
      }
      return myToString;
    }
  }

  /** Underlying distributed lock service to use */
  final DLockService dls;

  /** The name of the key for this lock */
  final Serializable key;

  /** The lease in milliseconds to hold the lock */
  final long leaseTimeout;

  /** Defines the behavior if lock reentry is attempted */
  final LockReentryPolicy reentryPolicy;

  /** Thread identity so that all caller threads appear as the same to dlock */
  final ThreadRequestState threadState;

  /**
   * Constructs a new <code>DistributedMemberLock</code>.
   *
   * @param dls the instance of <code>DistributedLockService</code> to use
   * @param key name of the key for this lock
   * @throws NullPointerException if dls or key is null
   */
  public DistributedMemberLock(DistributedLockService dls, Serializable key) {
    this(dls, key, NON_EXPIRING_LEASE, LockReentryPolicy.ALLOW);
  }

  /**
   * Constructs a new <code>DistributedMemberLock</code>.
   *
   * @param dls the instance of <code>DistributedLockService</code> to use
   * @param key name of the key for this lock
   * @param leaseTimeout number of milliseconds to hold a lock before automatically releasing it
   * @param reentryPolicy defines behavior for lock reentry
   * @throws NullPointerException if dls or key is null
   */
  public DistributedMemberLock(DistributedLockService dls, Serializable key, long leaseTimeout,
      LockReentryPolicy reentryPolicy) {
    if (dls == null || key == null) {
      throw new NullPointerException();
    }
    this.dls = (DLockService) dls;
    this.key = key;
    this.leaseTimeout = leaseTimeout;
    this.reentryPolicy = reentryPolicy;
    RemoteThread rThread = new RemoteThread(getDM().getId(), this.dls.incThreadSequence());
    threadState = new ThreadRequestState(rThread.getThreadId(), true);
  }

  @Override
  public synchronized void lock() {
    executeOperation(new Operation() {
      @Override
      public boolean operate() {
        if (holdsLock() && reentryPolicy.preventReentry(DistributedMemberLock.this)) {
          return true;
        }
        boolean locked = dls.lock(key, -1, leaseTimeout);
        Assert.assertTrue(locked, "Failed to lock " + this);
        return locked;
      }
    });
  }

  @Override
  public synchronized void lockInterruptibly() throws InterruptedException {
    executeOperationInterruptibly(new Operation() {
      @Override
      public boolean operate() throws InterruptedException {
        if (holdsLock() && reentryPolicy.preventReentry(DistributedMemberLock.this)) {
          return true;
        }
        boolean locked = dls.lockInterruptibly(key, -1, leaseTimeout);
        Assert.assertTrue(locked, "Failed to lockInterruptibly " + this);
        return locked;
      }
    });
  }

  @Override
  public synchronized boolean tryLock() {
    return executeOperation(() -> {
      if (holdsLock() && reentryPolicy.preventReentry(DistributedMemberLock.this)) {
        return true;
      }
      return dls.lock(key, 0, leaseTimeout);
    });
  }

  @Override
  public synchronized boolean tryLock(final long time, final TimeUnit unit)
      throws InterruptedException {
    return executeOperationInterruptibly(() -> {
      if (holdsLock() && reentryPolicy.preventReentry(DistributedMemberLock.this)) {
        return true;
      }
      return dls.lockInterruptibly(key, getLockTimeoutForLock(time, unit), leaseTimeout);
    });
  }

  @Override
  public synchronized void unlock() {
    executeOperation(() -> {
      dls.unlock(key);
      return true;
    });
  }

  public synchronized boolean holdsLock() {
    return executeOperation(() -> dls.isHeldByThreadId(key, threadState.threadId));
  }

  private boolean executeOperationInterruptibly(Operation lockOp) throws InterruptedException {
    return doExecuteOperation(lockOp, true);
  }

  private boolean executeOperation(Operation lockOp) {
    for (;;) {
      dls.getCancelCriterion().checkCancelInProgress(null);
      boolean interrupted = Thread.interrupted();
      try {
        return doExecuteOperation(lockOp, false);
      } catch (InterruptedException e) {
        interrupted = true;
        continue; // keep trying
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    } // for
  }

  private boolean doExecuteOperation(Operation lockOp, boolean interruptible)
      throws InterruptedException {

    ThreadRequestState oldThreadState = dls.getThreadRequestState().get();

    try {
      threadState.interruptible = interruptible;
      dls.getThreadRequestState().set(threadState);
      return lockOp.operate();
    } finally {
      threadState.interruptible = false;
      dls.getThreadRequestState().set(oldThreadState);
    }
  }

  private DistributionManager getDM() {
    return dls.getDistributionManager();
  }

  long getLockTimeoutForLock(long time, TimeUnit unit) {
    if (time == -1) {
      return -1;
    }
    return TimeUnit.MILLISECONDS.convert(time, unit);
  }

  @Override
  public String toString() {
    String identity = super.toString();
    identity = identity.substring(identity.lastIndexOf(".") + 1);
    return "[" + identity + ": " + "dls=" + dls.getName()
        + "key=" + key
        + "]";
  }

  private interface Operation {
    boolean operate() throws InterruptedException;
  }

  @Override
  public Condition newCondition() {
    throw new UnsupportedOperationException(
        "not implemented");
  }

}
