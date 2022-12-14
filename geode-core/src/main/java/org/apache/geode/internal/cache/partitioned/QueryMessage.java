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
package org.apache.geode.internal.cache.partitioned;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.query.QueryException;
import org.apache.geode.cache.query.QueryExecutionLowMemoryException;
import org.apache.geode.cache.query.Struct;
import org.apache.geode.cache.query.internal.DefaultQuery;
import org.apache.geode.cache.query.internal.ExecutionContext;
import org.apache.geode.cache.query.internal.IndexTrackingQueryObserver;
import org.apache.geode.cache.query.internal.PRQueryTraceInfo;
import org.apache.geode.cache.query.internal.QueryExecutionContext;
import org.apache.geode.cache.query.internal.QueryMonitor;
import org.apache.geode.cache.query.internal.QueryObserver;
import org.apache.geode.cache.query.internal.types.ObjectTypeImpl;
import org.apache.geode.cache.query.types.ObjectType;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.ReplyException;
import org.apache.geode.distributed.internal.ReplyProcessor21;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.distributed.internal.streaming.StreamingOperation.StreamingReplyMessage;
import org.apache.geode.internal.NanoTimer;
import org.apache.geode.internal.cache.ForceReattemptException;
import org.apache.geode.internal.cache.PRQueryProcessor;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.Token;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.logging.internal.log4j.api.LogService;

public class QueryMessage extends StreamingPartitionOperation.StreamingPartitionMessage {
  private static final Logger logger = LogService.getLogger();

  private volatile String queryString;
  private volatile boolean cqQuery;
  private volatile Object[] parameters;
  private volatile List buckets;
  private volatile boolean isPdxSerialized;
  private volatile boolean traceOn;

  private final List<Collection> resultCollector = new ArrayList<>();
  private Iterator currentResultIterator;
  private Iterator<Collection> currentSelectResultIterator;
  private boolean isTraceInfoIteration = false;
  private boolean isStructType = false;

  /**
   * Empty constructor to satisfy {@link DataSerializer} requirements
   */
  public QueryMessage() {
    // do nothing
  }

  public QueryMessage(InternalDistributedMember recipient, int regionId, ReplyProcessor21 processor,
      DefaultQuery query, Object[] parameters, final List buckets) {
    super(recipient, regionId, processor);
    queryString = query.getQueryString();
    this.buckets = buckets;
    this.parameters = parameters;
    cqQuery = query.isCqQuery();
    traceOn = query.isTraced() || DefaultQuery.QUERY_VERBOSE;
  }

  /**
   * Provide results to send back to requestor. terminate by returning END_OF_STREAM token object
   */
  @Override
  protected Object getNextReplyObject(PartitionedRegion pr)
      throws CacheException, ForceReattemptException, InterruptedException {
    final boolean isDebugEnabled = logger.isDebugEnabled();

    if (QueryMonitor.isLowMemory()) {
      String reason = String.format(
          "Query execution canceled due to memory threshold crossed in system, memory used: %s bytes.",
          QueryMonitor.getMemoryUsedBytes());
      throw new QueryExecutionLowMemoryException(reason);
    }
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    while (currentResultIterator == null || !currentResultIterator.hasNext()) {
      if (currentSelectResultIterator.hasNext()) {
        if (isTraceInfoIteration && currentResultIterator != null) {
          isTraceInfoIteration = false;
        }
        Collection results = currentSelectResultIterator.next();
        if (isDebugEnabled) {
          logger.debug("Query result size: {}", results.size());
        }
        currentResultIterator = results.iterator();
      } else {
        return Token.END_OF_STREAM;
      }
    }
    Object data = currentResultIterator.next();
    boolean isPostGFE_8_1 =
        getSender().getVersion().isNewerThan(KnownVersion.GFE_81);

    // There is a bug in older versions of GFE such that the query node expects the structs to have
    // type as ObjectTypes only & not specific types. So the new version needs to send the
    // inaccurate struct type for backward compatibility.
    if (isStructType && !isTraceInfoIteration && isPostGFE_8_1) {
      return ((Struct) data).getFieldValues();
    } else if (isStructType && !isTraceInfoIteration) {
      Struct struct = (Struct) data;
      ObjectType[] fieldTypes = struct.getStructType().getFieldTypes();
      for (int i = 0; i < fieldTypes.length; ++i) {
        fieldTypes[i] = new ObjectTypeImpl(Object.class);
      }
      return data;
    } else {
      return data;
    }
  }

  @Override
  protected boolean operateOnPartitionedRegion(ClusterDistributionManager dm, PartitionedRegion pr,
      long startTime)
      throws CacheException, QueryException, ForceReattemptException, InterruptedException {
    // calculate trace start time if trace is on this is because the start time is only set if
    // enableClock stats is on in this case we still want to see trace time even if clock is not
    // enabled
    long traceStartTime = 0;
    if (traceOn) {
      traceStartTime = NanoTimer.getTime();
    }
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
      logger.trace(LogMarker.DM_VERBOSE, "QueryMessage operateOnPartitionedRegion: {} buckets {}",
          pr.getFullPath(), buckets);
    }

    pr.waitOnInitialization();

    if (QueryMonitor.isLowMemory()) {
      String reason = String.format(
          "Query execution canceled due to memory threshold crossed in system, memory used: %s bytes.",
          QueryMonitor.getMemoryUsedBytes());
      // throw query exception to piggyback on existing error handling as qp.executeQuery also
      // throws the same error for low memory
      throw new QueryExecutionLowMemoryException(reason);
    }

    DefaultQuery query = new DefaultQuery(queryString, pr.getCache(), false);
    final ExecutionContext executionContext = new QueryExecutionContext(null, pr.getCache(), query);
    // Remote query, use the PDX types in serialized form.
    Boolean initialPdxReadSerialized = pr.getCache().getPdxReadSerializedOverride();
    pr.getCache().setPdxReadSerializedOverride(true);
    // In case of "select *" queries we can keep the results in serialized form and send
    query.setRemoteQuery(true);
    QueryObserver indexObserver = query.startTrace();
    boolean isQueryTraced = false;
    List queryTraceList = null;

    try {
      query.setIsCqQuery(cqQuery);
      PRQueryProcessor qp = new PRQueryProcessor(pr, query, parameters, buckets);
      if (logger.isDebugEnabled()) {
        logger.debug("Started executing query from remote node: {}", query.getQueryString());
      }
      isQueryTraced =
          query.isTraced()
              && sender.getVersion().isNotOlderThan(KnownVersion.GFE_81);

      // Adds a query trace info object to the results list for remote queries
      PRQueryTraceInfo queryTraceInfo = null;
      if (isQueryTraced) {
        isTraceInfoIteration = true;
        if (DefaultQuery.testHook != null) {
          DefaultQuery.testHook
              .doTestHook(DefaultQuery.TestHook.SPOTS.CREATE_PR_QUERY_TRACE_INFO_FOR_REMOTE_QUERY,
                  null, null);
        }
        queryTraceInfo = new PRQueryTraceInfo();
        queryTraceList = Collections.singletonList(queryTraceInfo);
      }

      isStructType = qp.executeQuery(resultCollector);
      // Add the trace info list object after the NWayMergeResults is created so as to exclude it
      // from the sorted collection of NWayMergeResults
      if (isQueryTraced) {
        resultCollector.add(0, queryTraceList);
      }
      currentSelectResultIterator = resultCollector.iterator();

      // If trace is enabled, we will generate a trace object to send back. The time info will be
      // slightly different than the one logged on this node due to generating the trace object
      // information here rather than the finally block.
      if (isQueryTraced) {
        if (DefaultQuery.testHook != null) {
          DefaultQuery.testHook
              .doTestHook(DefaultQuery.TestHook.SPOTS.POPULATING_TRACE_INFO_FOR_REMOTE_QUERY, null,
                  null);
        }

        // calculate the number of rows being sent
        int traceSize = queryTraceInfo.calculateNumberOfResults(resultCollector);
        // subtract the query trace info object
        traceSize -= 1;
        queryTraceInfo.setTimeInMillis((NanoTimer.getTime() - traceStartTime) / 1.0e6f);
        queryTraceInfo.setNumResults(traceSize);

        // created the indexes used string
        if (indexObserver instanceof IndexTrackingQueryObserver) {
          Map indexesUsed = ((IndexTrackingQueryObserver) indexObserver).getUsedIndexes();
          StringBuilder sb = new StringBuilder();
          sb.append(" indexesUsed(").append(indexesUsed.size()).append(")");
          if (indexesUsed.size() > 0) {
            sb.append(":");
            for (Iterator itr = indexesUsed.entrySet().iterator(); itr.hasNext();) {
              Map.Entry entry = (Map.Entry) itr.next();
              sb.append(entry.getKey()).append(entry.getValue());
              if (itr.hasNext()) {
                sb.append(",");
              }
            }
          }
          queryTraceInfo.setIndexesUsed(sb.toString());
        }
      }

      if (QueryMonitor.isLowMemory()) {
        String reason = String.format(
            "Query execution canceled due to memory threshold crossed in system, memory used: %s bytes.",
            QueryMonitor.getMemoryUsedBytes());
        throw new QueryExecutionLowMemoryException(reason);
      } else if (executionContext.isCanceled()) {
        throw executionContext.getQueryCanceledException();
      }
      super.operateOnPartitionedRegion(dm, pr, startTime);
    } finally {
      // remove trace info so that it is not included in the num results when logged
      if (isQueryTraced) {
        resultCollector.remove(queryTraceList);
      }
      pr.getCache().setPdxReadSerializedOverride(initialPdxReadSerialized);
      query.setRemoteQuery(false);
      query.endTrace(indexObserver, traceStartTime, resultCollector);
    }

    // Unless there was an exception thrown, this message handles sending the response
    return false;
  }

  @Override
  protected void appendFields(StringBuilder buff) {
    super.appendFields(buff);
    buff.append("; query=").append(queryString).append("; bucketids=").append(buckets);
  }

  @Override
  public int getDSFID() {
    return PR_QUERY_MESSAGE;
  }

  /**
   * send a reply message. This is in a method so that subclasses can override the reply message
   * type
   *
   * @see PutMessage#sendReply
   */
  @Override
  protected void sendReply(InternalDistributedMember member, int procId, DistributionManager dm,
      ReplyException ex, PartitionedRegion pr, long startTime) {
    // if there was an exception, then throw out any data
    if (ex != null) {
      outStream = null;
      replyMsgNum = 0;
      replyLastMsg = true;
    }
    if (replyLastMsg) {
      if (pr != null && startTime > 0) {
        pr.getPrStats().endPartitionMessagesProcessing(startTime);
      }
    }
    StreamingReplyMessage.send(member, procId, ex, dm, outStream, numObjectsInChunk,
        replyMsgNum, replyLastMsg, isPdxSerialized);
  }

  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    super.fromData(in, context);
    queryString = DataSerializer.readString(in);
    buckets = DataSerializer.readArrayList(in);
    parameters = DataSerializer.readObjectArray(in);
    cqQuery = DataSerializer.readBoolean(in);
    isPdxSerialized = DataSerializer.readBoolean(in);
    traceOn = DataSerializer.readBoolean(in);
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    super.toData(out, context);
    DataSerializer.writeString(queryString, out);
    DataSerializer.writeArrayList((ArrayList) buckets, out);
    DataSerializer.writeObjectArray(parameters, out);
    DataSerializer.writeBoolean(cqQuery, out);
    DataSerializer.writeBoolean(true, out);
    DataSerializer.writeBoolean(traceOn, out);
  }

}
