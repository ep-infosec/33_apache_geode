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
package org.apache.geode.internal.cache.tx;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.transaction.Status;

import org.apache.logging.log4j.Logger;

import org.apache.geode.GemFireException;
import org.apache.geode.annotations.internal.MutableForTesting;
import org.apache.geode.cache.CommitConflictException;
import org.apache.geode.cache.TransactionDataNodeHasDepartedException;
import org.apache.geode.cache.TransactionException;
import org.apache.geode.cache.TransactionInDoubtException;
import org.apache.geode.cache.client.internal.ServerRegionDataAccess;
import org.apache.geode.cache.client.internal.ServerRegionProxy;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.ServerLocation;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.InternalRegion;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.TXCommitMessage;
import org.apache.geode.internal.cache.TXLockRequest;
import org.apache.geode.internal.cache.TXRegionLockRequestImpl;
import org.apache.geode.internal.cache.TXStateProxy;
import org.apache.geode.internal.cache.TXStateStub;
import org.apache.geode.internal.cache.locks.TXRegionLockRequest;
import org.apache.geode.internal.cache.tx.TransactionalOperation.ServerRegionOperation;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.util.internal.GeodeGlossary;

public class ClientTXStateStub extends TXStateStub {
  private static final Logger logger = LogService.getLogger();

  /** test hook - used to find out what operations were performed in the last tx */
  @MutableForTesting
  private static ThreadLocal<List<TransactionalOperation>> recordedTransactionalOperations = null;

  /**
   * System property to disable conflict checks on clients.
   */
  private static final boolean DISABLE_CONFLICT_CHECK_ON_CLIENT =
      Boolean.getBoolean(GeodeGlossary.GEMFIRE_PREFIX + "disableConflictChecksOnClient");

  /**
   * @return true if transactional operation recording is enabled (test hook)
   */
  public static boolean transactionRecordingEnabled() {
    return !DISABLE_CONFLICT_CHECK_ON_CLIENT || recordedTransactionalOperations != null;
  }

  private final ServerRegionProxy firstProxy;
  private final InternalCache cache;
  private final DistributionManager dm;

  /** the operations performed in the current transaction are held in this list */
  private final List<TransactionalOperation> recordedOperations =
      Collections.synchronizedList(new LinkedList<>());

  private ServerLocation serverAffinityLocation;

  /** lock request for obtaining local locks */
  private TXLockRequest lockReq;

  private Runnable internalAfterLocalLocks;

  private boolean txRolledback = false;

  /**
   * test hook
   *
   * @param t a ThreadLocal to hold lists of TransactionalOperations
   */
  public static void setTransactionalOperationContainer(
      ThreadLocal<List<TransactionalOperation>> t) {
    recordedTransactionalOperations = t;
  }

  public ClientTXStateStub(InternalCache cache, DistributionManager dm, TXStateProxy stateProxy,
      DistributedMember target, InternalRegion firstRegion) {
    super(stateProxy, target);
    this.cache = cache;
    this.dm = dm;
    firstProxy = firstRegion.getServerProxy();
    firstProxy.getPool().setupServerAffinity(true);
    if (recordedTransactionalOperations != null) {
      recordedTransactionalOperations.set(recordedOperations);
    }
  }

  @Override
  public void commit() throws CommitConflictException {
    obtainLocalLocks();
    try {
      TXCommitMessage txcm = null;
      try {
        txcm = firstProxy.commit(proxy.getTxId().getUniqId());
      } finally {
        firstProxy.getPool().releaseServerAffinity();
      }
      afterServerCommit(txcm);
    } catch (TransactionDataNodeHasDepartedException e) {
      throw new TransactionInDoubtException(e);
    } finally {
      lockReq.releaseLocal();
    }
  }

  TXLockRequest createTXLockRequest() {
    return new TXLockRequest();
  }

  TXRegionLockRequestImpl createTXRegionLockRequestImpl(InternalCache cache, LocalRegion region) {
    return new TXRegionLockRequestImpl(cache, region);
  }

  /**
   * Lock the keys in a local transaction manager
   *
   * @throws CommitConflictException if the key is already locked by some other transaction
   */
  private void obtainLocalLocks() {
    lockReq = createTXLockRequest();
    for (TransactionalOperation txOp : recordedOperations) {
      if (ServerRegionOperation.lockKeyForTx(txOp.getOperation())) {
        TXRegionLockRequest rlr = lockReq.getRegionLockRequest(txOp.getRegionName());
        if (rlr == null) {
          rlr = createTXRegionLockRequestImpl(cache,
              (LocalRegion) cache.getInternalRegionByPath(txOp.getRegionName()));
          lockReq.addLocalRequest(rlr);
        }
        if (txOp.getOperation() == ServerRegionOperation.PUT_ALL
            || txOp.getOperation() == ServerRegionOperation.REMOVE_ALL) {
          for (final Object o : txOp.getKeys()) {
            rlr.addEntryKey(o, true);
          }
        } else {
          rlr.addEntryKey(txOp.getKey(), ServerRegionOperation.lockKeyForTx(txOp.getOperation()));
        }
      }
    }
    if (logger.isDebugEnabled()) {
      logger.debug("TX: client localLockRequest: {}", lockReq);
    }
    try {
      lockReq.obtain(cache.getInternalDistributedSystem());
    } catch (CommitConflictException e) {
      rollback(); // cleanup tx artifacts on server
      throw e;
    }
    if (internalAfterLocalLocks != null) {
      internalAfterLocalLocks.run();
    }
  }

  /** perform local cache modifications using the server's TXCommitMessage */
  private void afterServerCommit(TXCommitMessage txcm) {
    if (internalAfterSendCommit != null) {
      internalAfterSendCommit.run();
    }

    if (cache == null) {
      // we can probably delete this block because cache is now a final var
      // fixes bug 42933
      return;
    }
    cache.getCancelCriterion().checkCancelInProgress(null);

    txcm.setDM(dm);
    txcm.setAckRequired(false);
    txcm.setDisableListeners(true);
    cache.getTxManager().setTXState(null);
    txcm.hookupRegions(dm);
    txcm.basicProcess();
  }

  @Override
  protected TXRegionStub generateRegionStub(InternalRegion region) {
    return new ClientTXRegionStub(region);
  }

  @Override
  protected void validateRegionCanJoinTransaction(InternalRegion region)
      throws TransactionException {
    if (!region.hasServerProxy()) {
      throw new TransactionException("Region " + region.getName()
          + " is local to this client and cannot be used in a transaction.");
    } else if (firstProxy != null
        && firstProxy.getPool() != region.getServerProxy().getPool()) {
      throw new TransactionException("Region " + region.getName()
          + " is using a different server pool than other regions in this transaction.");
    }
  }

  @Override
  public void rollback() {
    if (internalAfterSendRollback != null) {
      internalAfterSendRollback.run();
    }
    try {
      txRolledback = true;
      firstProxy.rollback(proxy.getTxId().getUniqId());
    } finally {
      firstProxy.getPool().releaseServerAffinity();
    }
  }

  @Override
  public void afterCompletion(int status) {
    try {
      if (txRolledback) {
        return;
      }
      TXCommitMessage txcm = firstProxy.afterCompletion(status, proxy.getTxId().getUniqId());
      if (status == Status.STATUS_COMMITTED) {
        if (txcm == null) {
          throw new TransactionInDoubtException(
              "Commit failed on cache server");
        } else {
          afterServerCommit(txcm);
        }
      } else if (status == Status.STATUS_ROLLEDBACK) {
        if (internalAfterSendRollback != null) {
          internalAfterSendRollback.run();
        }
        firstProxy.getPool().releaseServerAffinity();
      }
    } finally {
      if (status == Status.STATUS_COMMITTED) {
        // rollback does not grab locks
        lockReq.releaseLocal();
      }
      firstProxy.getPool().releaseServerAffinity();
    }
  }

  @Override
  public void beforeCompletion() {
    obtainLocalLocks();
    try {
      firstProxy.beforeCompletion(proxy.getTxId().getUniqId());
    } catch (GemFireException e) {
      lockReq.releaseLocal();
      firstProxy.getPool().releaseServerAffinity();
      throw e;
    }
  }

  @Override
  public InternalDistributedMember getOriginatingMember() {
    /*
     * Client member id is implied from the connection so we don't need this
     */
    return null;
  }

  @Override
  public boolean isMemberIdForwardingRequired() {
    /*
     * Client member id is implied from the connection so we don't need this Forwarding will occur
     * on the server-side stub
     */
    return false;
  }

  @Override
  public TXCommitMessage getCommitMessage() {
    /* client gets the txcommit message during Op processing and doesn't need it here */
    return null;
  }

  @Override
  public void suspend() {
    serverAffinityLocation = firstProxy.getPool().getServerAffinityLocation();
    firstProxy.getPool().releaseServerAffinity();
    if (logger.isDebugEnabled()) {
      logger.debug("TX: suspending transaction: {} server delegate: {}", getTransactionId(),
          serverAffinityLocation);
    }
  }

  @Override
  public void resume() {
    firstProxy.getPool().setupServerAffinity(true);
    firstProxy.getPool().setServerAffinityLocation(serverAffinityLocation);
    if (logger.isDebugEnabled()) {
      logger.debug("TX: resuming transaction: {} server delegate: {}", getTransactionId(),
          serverAffinityLocation);
    }
  }

  /**
   * test hook - maintain a list of tx operations
   */
  @Override
  public void recordTXOperation(ServerRegionDataAccess region, ServerRegionOperation op, Object key,
      Object[] arguments) {
    if (ClientTXStateStub.transactionRecordingEnabled()) {
      recordedOperations
          .add(new TransactionalOperation(this, region.getRegionName(), op, key, arguments));
    }
  }

  /**
   * Add an internal callback which is run after the the local locks are obtained
   */
  public void setAfterLocalLocks(Runnable afterLocalLocks) {
    internalAfterLocalLocks = afterLocalLocks;
  }

  public ServerLocation getServerAffinityLocation() {
    return serverAffinityLocation;
  }
}
