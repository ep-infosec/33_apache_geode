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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializable;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.CacheTransactionManager;
import org.apache.geode.cache.TransactionDataNodeHasDepartedException;
import org.apache.geode.cache.TransactionId;
import org.apache.geode.cache.execute.Execution;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.distributed.DistributedMember;

/**
 * This function can be used by GemFire clients and peers to rollback an existing transaction. A
 * {@link TransactionId} corresponding to the transaction to be rolledback must be provided as an
 * argument while invoking this function.<br>
 *
 * This function should execute only on one server. If the transaction is not hosted on the server
 * where the function is invoked then this function decides to invoke a
 * {@link NestedTransactionFunction} which executes on the member where transaction is hosted.<br>
 *
 * This function returns a single Boolean as result, whose value is <code>Boolean.TRUE</code> if the
 * transaction rolled back successfully otherwise the return value is
 * <code>Boolean.FALSE</code>.<br>
 *
 * To execute this function, it is recommended to use the {@link Execution} obtained by using
 * TransactionFunctionService. <br>
 *
 * To summarize, this function should be used as follows:
 *
 * <pre>
 * Execution exe = TransactionFunctionService.onTransaction(txId);
 * List l = (List) exe.execute(rollbackFunction).getResult();
 * Boolean result = (Boolean) l.get(0);
 * </pre>
 *
 * This function is <b>not</b> registered on the cache servers by default, and it is the user's
 * responsibility to register this function. see {@link FunctionService#registerFunction(Function)}
 *
 * @since GemFire 6.6.1
 */
public class RollbackFunction implements Function, DataSerializable {
  private static final Logger logger = LogManager.getLogger();

  private static final long serialVersionUID = 1377183180063184795L;

  public RollbackFunction() {}

  @Override
  public boolean hasResult() {
    return true;
  }

  @Override
  public void execute(FunctionContext context) {
    Cache cache = CacheFactory.getAnyInstance();
    TXId txId = null;
    try {
      txId = (TXId) context.getArguments();
    } catch (ClassCastException e) {
      logger.info(
          "RollbackFunction should be invoked with a TransactionId as an argument i.e. setArguments(txId).execute(function)");
      throw e;
    }
    DistributedMember member = txId.getMemberId();
    Boolean result = false;
    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (cache.getDistributedSystem().getDistributedMember().equals(member)) {
      if (isDebugEnabled) {
        logger.debug("RollbackFunction: for transaction: {} rolling back locally", txId);
      }
      CacheTransactionManager txMgr = cache.getCacheTransactionManager();
      if (txMgr.tryResume(txId)) {
        if (isDebugEnabled) {
          logger.debug("RollbackFunction: resumed transaction: {}", txId);
        }
        txMgr.rollback();
        result = true;
      }
    } else {
      ArrayList args = new ArrayList();
      args.add(txId);
      args.add(NestedTransactionFunction.ROLLBACK);
      Execution ex = FunctionService.onMember(member).setArguments(args);
      if (isDebugEnabled) {
        logger.debug(
            "RollbackFunction: for transaction: {} executing NestedTransactionFunction on member: {}",
            txId, member);
      }
      try {
        List list = (List) ex.execute(new NestedTransactionFunction()).getResult();
        result = (Boolean) list.get(0);
      } catch (FunctionException fe) {
        throw new TransactionDataNodeHasDepartedException("Could not Rollback on member:" + member);
      }
    }
    if (isDebugEnabled) {
      logger.debug("RollbackFunction: for transaction: {} returning result: {}", txId, result);
    }
    context.getResultSender().lastResult(result);
  }

  @Override
  public String getId() {
    return getClass().getName();
  }

  @Override
  public boolean optimizeForWrite() {
    return true;
  }

  @Override
  public boolean isHA() {
    // GEM-207
    return true;
  }

  @Override
  public void toData(DataOutput out) throws IOException {

  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {

  }
}
