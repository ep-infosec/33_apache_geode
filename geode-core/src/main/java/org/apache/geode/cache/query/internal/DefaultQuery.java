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
package org.apache.geode.cache.query.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.Logger;

import org.apache.geode.annotations.Immutable;
import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.annotations.internal.MutableForTesting;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.internal.ProxyCache;
import org.apache.geode.cache.client.internal.ServerProxy;
import org.apache.geode.cache.client.internal.UserAttributes;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.RegionFunctionContext;
import org.apache.geode.cache.partition.PartitionRegionHelper;
import org.apache.geode.cache.persistence.PartitionOfflineException;
import org.apache.geode.cache.query.FunctionDomainException;
import org.apache.geode.cache.query.NameResolutionException;
import org.apache.geode.cache.query.Query;
import org.apache.geode.cache.query.QueryException;
import org.apache.geode.cache.query.QueryInvalidException;
import org.apache.geode.cache.query.QueryInvocationTargetException;
import org.apache.geode.cache.query.QueryService;
import org.apache.geode.cache.query.QueryStatistics;
import org.apache.geode.cache.query.RegionNotFoundException;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.TypeMismatchException;
import org.apache.geode.cache.query.internal.cq.InternalCqQuery;
import org.apache.geode.internal.NanoTimer;
import org.apache.geode.internal.cache.BucketRegion;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.LocalDataSet;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.TXManagerImpl;
import org.apache.geode.internal.cache.TXStateProxy;
import org.apache.geode.internal.statistics.StatisticsClock;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * Thread-safe implementation of org.apache.persistence.query.Query
 */
public class DefaultQuery implements Query {

  private static final Logger logger = LogService.getLogger();

  private final CompiledValue compiledQuery;

  private final String queryString;

  private final InternalCache cache;

  private ServerProxy serverProxy;

  private final LongAdder numExecutions = new LongAdder();

  private final LongAdder totalExecutionTime = new LongAdder();

  private final QueryStatistics stats;

  private final boolean traceOn;

  @Immutable
  private static final Object[] EMPTY_ARRAY = new Object[0];

  @MutableForTesting
  public static boolean QUERY_VERBOSE =
      Boolean.getBoolean(GeodeGlossary.GEMFIRE_PREFIX + "Query.VERBOSE");

  /**
   * System property to cleanup the compiled query. The compiled query will be removed if it is not
   * used for more than the set value. By default its set to 10 minutes, the time is set in
   * MilliSecs.
   */
  public static final int COMPILED_QUERY_CLEAR_TIME = Integer.getInteger(
      GeodeGlossary.GEMFIRE_PREFIX + "Query.COMPILED_QUERY_CLEAR_TIME", 10 * 60 * 1000);

  @MutableForTesting
  public static int TEST_COMPILED_QUERY_CLEAR_TIME = -1;

  private ProxyCache proxyCache;

  private boolean isCqQuery = false;

  private boolean isQueryWithFunctionContext = false;

  /**
   * Holds the CQ reference. In cases of peer PRs this will be set to null even though isCqQuery is
   * set to true.
   */
  private InternalCqQuery cqQuery = null;

  private volatile boolean lastUsed = true;

  @MakeNotStatic
  public static TestHook testHook;

  /** indicates query executed remotely */
  private boolean isRemoteQuery = false;

  // to prevent objects from getting deserialized
  private boolean keepSerialized = false;

  private final StatisticsClock statisticsClock;

  /**
   * Caches the fields not found in any Pdx version. This threadlocal will be cleaned up after query
   * execution completes in {@linkplain #executeUsingContext(ExecutionContext)}
   */
  private static final ThreadLocal<Map<String, Set<String>>> pdxClassToFieldsMap =
      ThreadLocal.withInitial(HashMap::new);

  static Map<String, Set<String>> getPdxClasstofieldsmap() {
    return pdxClassToFieldsMap.get();
  }


  /**
   * Caches the methods not found in any Pdx version. This threadlocal will be cleaned up after
   * query execution completes in {@linkplain #executeUsingContext(ExecutionContext)}
   */
  private static final ThreadLocal<Map<String, Set<String>>> pdxClassToMethodsMap =
      ThreadLocal.withInitial(HashMap::new);

  public static void setPdxClasstoMethodsmap(Map<String, Set<String>> map) {
    pdxClassToMethodsMap.set(map);
  }

  public static Map<String, Set<String>> getPdxClasstoMethodsmap() {
    return pdxClassToMethodsMap.get();
  }

  /**
   * Should be constructed from DefaultQueryService
   *
   * @see QueryService#newQuery
   */
  public DefaultQuery(String queryString, InternalCache cache, boolean isForRemote) {
    this.queryString = queryString;
    QCompiler compiler = new QCompiler();
    compiledQuery = compiler.compileQuery(queryString);
    CompiledSelect cs = getSimpleSelect();
    if (cs != null && !isForRemote && (cs.isGroupBy() || cs.isOrderBy())) {
      QueryExecutionContext ctx = new QueryExecutionContext(null, cache);
      try {
        cs.computeDependencies(ctx);
      } catch (QueryException qe) {
        throw new QueryInvalidException("", qe);
      }
    }
    traceOn = compiler.isTraceRequested() || QUERY_VERBOSE;
    this.cache = cache;
    statisticsClock = cache.getStatisticsClock();
    stats = new DefaultQueryStatistics();
  }

  /**
   * Get statistics information for this query.
   */
  @Override
  public QueryStatistics getStatistics() {
    return stats;
  }

  @Override
  public String getQueryString() {
    return queryString;
  }

  @Override
  public Object execute() throws FunctionDomainException, TypeMismatchException,
      NameResolutionException, QueryInvocationTargetException {
    return execute(EMPTY_ARRAY);
  }

  /**
   * namespace or parameters can be null
   */
  @Override
  public Object execute(Object[] params) throws FunctionDomainException, TypeMismatchException,
      NameResolutionException, QueryInvocationTargetException {
    // Local Query.
    if (params == null) {
      throw new IllegalArgumentException(
          "'parameters' cannot be null");
    }

    // If pool is associated with the Query; execute the query on pool. ServerSide query.
    if (serverProxy != null) {
      // Execute Query using pool.
      return executeOnServer(params);
    }

    long startTime = 0L;
    if (traceOn && cache != null) {
      startTime = NanoTimer.getTime();
    }

    QueryObserver indexObserver = null;
    QueryMonitor queryMonitor = null;
    QueryExecutor qe = checkQueryOnPR(params);

    Object result = null;
    Boolean initialPdxReadSerialized = cache.getPdxReadSerializedOverride();
    final QueryExecutionContext context = new QueryExecutionContext(params, cache, this);

    try {
      // Setting the readSerialized flag for local queries
      cache.setPdxReadSerializedOverride(true);
      indexObserver = startTrace();
      if (qe != null) {
        if (DefaultQuery.testHook != null) {
          DefaultQuery.testHook.doTestHook(DefaultQuery.TestHook.SPOTS.BEFORE_QUERY_EXECUTION,
              this, context);
        }

        result = qe.executeQuery(this, context, params, null);
        // For local queries returning pdx objects wrap the resultset with
        // ResultsCollectionPdxDeserializerWrapper
        // which deserializes these pdx objects.
        if (needsPDXDeserializationWrapper(true /* is query on PR */)
            && result instanceof SelectResults) {
          // we use copy on read false here because the copying has already taken effect earlier in
          // the PartitionedRegionQueryEvaluator
          result = new ResultsCollectionPdxDeserializerWrapper((SelectResults) result, false);
        }
        return result;
      }

      queryMonitor = cache.getQueryMonitor();

      // If QueryMonitor is enabled add query to be monitored.
      if (queryMonitor != null) {
        // Add current thread to be monitored by QueryMonitor.
        // In case of partitioned region it will be added before the query execution
        // starts on the Local Buckets.
        queryMonitor.monitorQueryExecution(context);
      }

      result = executeUsingContext(context);
      // Only wrap/copy results when copy on read is set and an index is used
      // This is because when an index is used, the results are actual references to values in the
      // cache
      // Currently as 7.0.1 when indexes are not used, iteration uses non tx entries to retrieve the
      // value.
      // The non tx entry already checks copy on read and returns a copy.
      // We only wrap actual results and not UNDEFINED.

      // Takes into consideration that isRemoteQuery is already being checked with the if checks
      // this flag is true if copy on read is set to true and we are copying at the entry level for
      // queries is set to false (default)
      // OR copy on read is true and we used an index where copy on entry level for queries is set
      // to true.
      // Due to bug#46970 index usage does not actually copy at the entry level so that is why we
      // have the OR condition
      boolean needsCopyOnReadWrapper =
          cache.getCopyOnRead() && !DefaultQueryService.COPY_ON_READ_AT_ENTRY_LEVEL
              || (context.isIndexUsed()
                  && DefaultQueryService.COPY_ON_READ_AT_ENTRY_LEVEL);
      // For local queries returning pdx objects wrap the resultset with
      // ResultsCollectionPdxDeserializerWrapper
      // which deserializes these pdx objects.
      if (needsPDXDeserializationWrapper(false /* is query on PR */)
          && result instanceof SelectResults) {
        result = new ResultsCollectionPdxDeserializerWrapper((SelectResults) result,
            needsCopyOnReadWrapper);
      } else if (!isRemoteQuery() && cache.getCopyOnRead()
          && result instanceof SelectResults) {
        if (needsCopyOnReadWrapper) {
          result = new ResultsCollectionCopyOnReadWrapper((SelectResults) result);
        }
      }
      return result;
    } catch (QueryExecutionCanceledException ignore) {
      return context.reinterpretQueryExecutionCanceledException();
    } finally {
      cache.setPdxReadSerializedOverride(initialPdxReadSerialized);
      if (queryMonitor != null) {
        queryMonitor.stopMonitoringQueryExecution(context);
      }
      endTrace(indexObserver, startTime, result);
    }
  }

  /**
   * For Order by queries ,since they are already ordered by the comparator && it takes care of
   * conversion, we do not have to wrap it in a wrapper
   */
  private boolean needsPDXDeserializationWrapper(boolean isQueryOnPR) {
    return !isRemoteQuery() && !cache.getPdxReadSerialized();
  }

  private Object executeOnServer(Object[] parameters) {
    long startTime = statisticsClock.getTime();
    Object result;
    try {
      if (proxyCache != null) {
        if (proxyCache.isClosed()) {
          throw proxyCache.getCacheClosedException("Cache is closed for this user.");
        }
        UserAttributes.userAttributes.set(proxyCache.getUserAttributes());
      }
      result = serverProxy.query(queryString, parameters);
    } finally {
      UserAttributes.userAttributes.set(null);
      long endTime = statisticsClock.getTime();
      updateStatistics(endTime - startTime);
    }
    return result;
  }

  public Object executeUsingContext(ExecutionContext context) throws FunctionDomainException,
      TypeMismatchException, NameResolutionException, QueryInvocationTargetException {
    QueryObserver observer = QueryObserverHolder.getInstance();
    long startTime = statisticsClock.getTime();
    TXStateProxy tx = ((TXManagerImpl) cache.getCacheTransactionManager()).pauseTransaction();
    try {
      observer.startQuery(this);
      observer.beforeQueryEvaluation(compiledQuery, context);

      if (DefaultQuery.testHook != null) {
        DefaultQuery.testHook.doTestHook(TestHook.SPOTS.BEFORE_QUERY_DEPENDENCY_COMPUTATION, this,
            context);
      }
      Object results = null;
      try {
        // two-pass evaluation.
        // first pre-compute dependencies, cached in the context.
        compiledQuery.computeDependencies(context);
        if (testHook != null) {
          testHook.doTestHook(DefaultQuery.TestHook.SPOTS.BEFORE_QUERY_EXECUTION, this, context);
        }
        results = compiledQuery.evaluate(context);
      } catch (QueryExecutionCanceledException ignore) {
        context.reinterpretQueryExecutionCanceledException();
      } finally {
        observer.afterQueryEvaluation(results);
      }
      return results;
    } finally {
      observer.endQuery();
      long endTime = statisticsClock.getTime();
      updateStatistics(endTime - startTime);
      pdxClassToFieldsMap.remove();
      pdxClassToMethodsMap.remove();
      ExecutionContext.isCanceled.remove();
      ((TXManagerImpl) cache.getCacheTransactionManager()).unpauseTransaction(tx);
    }
  }

  QueryExecutor checkQueryOnPR(Object[] parameters)
      throws RegionNotFoundException, PartitionOfflineException {
    // check for PartitionedRegions. If a PartitionedRegion is referred to in the query,
    // then the following restrictions apply:
    // 1) the query must be just a SELECT expression; (preceded by zero or more IMPORT statements)
    // 2) the first FROM clause iterator cannot contain a subquery;
    // 3) PR reference can only be in the first FROM clause

    List<QueryExecutor> prs = new ArrayList<>();
    for (final Object o : getRegionsInQuery(parameters)) {
      String regionPath = (String) o;
      Region rgn = cache.getRegion(regionPath);
      if (rgn == null) {
        cache.getCancelCriterion().checkCancelInProgress(null);
        throw new RegionNotFoundException(
            String.format("Region not found: %s", regionPath));
      }
      if (rgn instanceof QueryExecutor) {
        ((PartitionedRegion) rgn).checkPROffline();
        prs.add((QueryExecutor) rgn);
      }
    }
    if (prs.size() == 1) {
      return prs.get(0);
    } else if (prs.size() > 1) {
      // colocation checks; valid for more the one PRs

      // First query has to be executed in a Function.
      if (!isQueryWithFunctionContext()) {
        throw new UnsupportedOperationException(
            String.format(
                "A query on a Partitioned Region ( %s ) may not reference any other region if query is NOT executed within a Function",
                prs.get(0).getName()));
      }

      // If there are more than one PRs they have to be co-located.
      QueryExecutor other = null;
      for (QueryExecutor eachPR : prs) {
        boolean colocated = false;

        for (QueryExecutor allPRs : prs) {
          if (eachPR == allPRs) {
            continue;
          }
          other = allPRs;
          if (((PartitionedRegion) eachPR).getColocatedByList().contains(allPRs)
              || ((PartitionedRegion) allPRs).getColocatedByList().contains(eachPR)) {
            colocated = true;
            break;
          }
        } // allPrs

        if (!colocated) {
          throw new UnsupportedOperationException(
              String.format(
                  "A query on a Partitioned Region ( %s ) may not reference any other region except Co-located Partitioned Region. PR region %s is not collocated with other PR region in the query.",
                  eachPR.getName(), other.getName()));
        }

      } // eachPR

      // this is a query on a PR, check to make sure it is only a SELECT
      CompiledSelect select = getSimpleSelect();
      if (select == null) {
        throw new UnsupportedOperationException(
            "query must be a simple select when referencing a Partitioned Region");
      }

      // make sure the where clause references no regions
      Set regions = new HashSet();
      CompiledValue whereClause = select.getWhereClause();
      if (whereClause != null) {
        whereClause.getRegionsInQuery(regions, parameters);
        if (!regions.isEmpty()) {
          throw new UnsupportedOperationException(
              "The WHERE clause cannot refer to a region when querying on a Partitioned Region");
        }
      }
      List fromClause = select.getIterators();

      // the first iterator in the FROM clause must be just a reference to the Partitioned Region
      Iterator fromClauseIterator = fromClause.iterator();
      CompiledIteratorDef itrDef = (CompiledIteratorDef) fromClauseIterator.next();

      // By process of elimination, we know that the first iterator contains a reference
      // to the PR. Check to make sure there are no subqueries in this first iterator
      itrDef.visitNodes(node -> {
        if (node instanceof CompiledSelect) {
          throw new UnsupportedOperationException(
              "When querying a PartitionedRegion, the first FROM clause iterator must not contain a subquery");
        }
        return true;
      });

      // the rest of the FROM clause iterators must not reference any regions
      if (!isQueryWithFunctionContext()) {
        while (fromClauseIterator.hasNext()) {
          itrDef = (CompiledIteratorDef) fromClauseIterator.next();
          itrDef.getRegionsInQuery(regions, parameters);
          if (!regions.isEmpty()) {
            throw new UnsupportedOperationException(
                "When querying a Partitioned Region, the FROM clause iterators other than the first one must not reference any regions");
          }
        }

        // check the projections, must not reference any regions
        List projs = select.getProjectionAttributes();
        if (projs != null) {
          for (Object proj1 : projs) {
            Object[] rawProj = (Object[]) proj1;
            CompiledValue proj = (CompiledValue) rawProj[1];
            proj.getRegionsInQuery(regions, parameters);
            if (!regions.isEmpty()) {
              throw new UnsupportedOperationException(
                  "When querying a Partitioned Region, the projections must not reference any regions");
            }
          }
        }
        // check the orderByAttrs, must not reference any regions
        List<CompiledSortCriterion> orderBys = select.getOrderByAttrs();
        if (orderBys != null) {
          for (CompiledSortCriterion orderBy : orderBys) {
            orderBy.getRegionsInQuery(regions, parameters);
            if (!regions.isEmpty()) {
              throw new UnsupportedOperationException(
                  "When querying a Partitioned Region, the order-by attributes must not reference any regions");
            }
          }
        }
      }
      return prs.get(0); // PR query is okay
    }
    return null;
  }

  private void updateStatistics(long executionTime) {
    numExecutions.increment();
    totalExecutionTime.add(executionTime);
    cache.getCachePerfStats().endQueryExecution(executionTime);
  }

  // TODO: Implement the function. Toggle the isCompiled flag accordingly
  @Override
  public void compile() {
    throw new UnsupportedOperationException(
        "not yet implemented");
  }

  @Override
  public boolean isCompiled() {
    return false;
  }

  public boolean isTraced() {
    return traceOn;
  }

  class DefaultQueryStatistics implements QueryStatistics {

    /**
     * Returns the total amount of time (in nanoseconds) spent executing the query.
     */
    @Override
    public long getTotalExecutionTime() {
      return totalExecutionTime.longValue();
    }

    /**
     * Returns the total number of times the query has been executed.
     */
    @Override
    public long getNumExecutions() {
      return numExecutions.longValue();
    }
  }

  /**
   * Returns an unmodifiable Set containing the Region names which are present in the Query. A
   * region which is associated with the query as a bind parameter will not be included in the list.
   * The Region names returned by the query do not indicate anything about the state of the region
   * or whether the region actually exists in the GemfireCache etc.
   *
   * @param parameters the parameters to be passed in to the query when executed
   * @return Unmodifiable List containing the region names.
   */
  public Set<String> getRegionsInQuery(Object[] parameters) {
    Set<String> regions = new HashSet<>();
    compiledQuery.getRegionsInQuery(regions, parameters);
    return Collections.unmodifiableSet(regions);
  }

  /**
   * Returns the CompiledSelect if this query consists of only a SELECT expression (possibly with
   * IMPORTS as well). Otherwise, returns null
   */
  public CompiledSelect getSimpleSelect() {
    if (compiledQuery instanceof CompiledSelect) {
      return (CompiledSelect) compiledQuery;
    }
    return null;
  }

  public CompiledSelect getSelect() {
    return (CompiledSelect) compiledQuery;
  }

  /**
   * @return int identifying the limit. A value of -1 indicates that no limit is imposed or the
   *         query is not a select query
   */
  public int getLimit(Object[] bindArguments) throws FunctionDomainException, TypeMismatchException,
      NameResolutionException, QueryInvocationTargetException {
    return compiledQuery instanceof CompiledSelect
        ? ((CompiledSelect) compiledQuery).getLimitValue(bindArguments) : -1;
  }

  void setServerProxy(ServerProxy serverProxy) {
    this.serverProxy = serverProxy;
  }

  public void setIsCqQuery(boolean isCqQuery) {
    this.isCqQuery = isCqQuery;
  }

  public boolean isCqQuery() {
    return isCqQuery;
  }

  public void setCqQuery(InternalCqQuery cqQuery) {
    this.cqQuery = cqQuery;
  }

  public void setLastUsed(boolean lastUsed) {
    this.lastUsed = lastUsed;
  }

  public boolean getLastUsed() {
    return lastUsed;
  }

  public InternalCqQuery getCqQuery() {
    return cqQuery;
  }

  @Override
  public String toString() {
    return "Query String = " + queryString
        + "; Total Executions = "
        + numExecutions
        + "; Total Execution Time = "
        + totalExecutionTime;
  }

  void setProxyCache(ProxyCache proxyCache) {
    this.proxyCache = proxyCache;
  }

  /**
   * Used for test purpose.
   */
  public static void setTestCompiledQueryClearTime(int val) {
    DefaultQuery.TEST_COMPILED_QUERY_CLEAR_TIME = val;
  }

  private static String getLogMessage(QueryObserver observer, long startTime, int resultSize,
      String query) {
    float time = (NanoTimer.getTime() - startTime) / 1.0e6f;

    String usedIndexesString = null;
    if (observer instanceof IndexTrackingQueryObserver) {
      IndexTrackingQueryObserver indexObserver = (IndexTrackingQueryObserver) observer;
      Map usedIndexes = indexObserver.getUsedIndexes();
      indexObserver.reset();
      StringBuilder sb = new StringBuilder();
      sb.append(" indexesUsed(");
      sb.append(usedIndexes.size());
      sb.append(')');
      if (usedIndexes.size() > 0) {
        sb.append(':');
        for (Iterator itr = usedIndexes.entrySet().iterator(); itr.hasNext();) {
          Map.Entry entry = (Map.Entry) itr.next();
          sb.append(entry.getKey()).append(entry.getValue());
          if (itr.hasNext()) {
            sb.append(',');
          }
        }
      }
      usedIndexesString = sb.toString();
    } else if (DefaultQuery.QUERY_VERBOSE) {
      usedIndexesString = " indexesUsed(NA due to other observer in the way: "
          + observer.getClass().getName() + ')';
    }

    String rowCountString = null;
    if (resultSize != -1) {
      rowCountString = " rowCount = " + resultSize + ';';
    }
    return "Query Executed in " + time + " ms;" + (rowCountString != null ? rowCountString : "")
        + (usedIndexesString != null ? usedIndexesString : "") + " \"" + query + '"';
  }

  private static String getLogMessage(IndexTrackingQueryObserver indexObserver, long startTime,
      String otherObserver, int resultSize, String query, BucketRegion bucket) {
    float time = 0.0f;

    if (startTime > 0L) {
      time = (NanoTimer.getTime() - startTime) / 1.0e6f;
    }

    String usedIndexesString = null;
    if (indexObserver != null) {
      Map usedIndexes = indexObserver.getUsedIndexes(bucket.getFullPath());
      StringBuilder sb = new StringBuilder();
      sb.append(" indexesUsed(");
      sb.append(usedIndexes.size());
      sb.append(')');
      if (!usedIndexes.isEmpty()) {
        sb.append(':');
        for (Iterator itr = usedIndexes.entrySet().iterator(); itr.hasNext();) {
          Map.Entry entry = (Map.Entry) itr.next();
          sb.append(entry.getKey()).append("(Results: ").append(entry.getValue())
              .append(", Bucket: ").append(bucket.getId()).append(")");
          if (itr.hasNext()) {
            sb.append(',');
          }
        }
      }
      usedIndexesString = sb.toString();
    } else if (DefaultQuery.QUERY_VERBOSE) {
      usedIndexesString =
          " indexesUsed(NA due to other observer in the way: " + otherObserver + ')';
    }

    String rowCountString = " rowCount = " + resultSize + ';';
    return "Query Executed" + (startTime > 0L ? " in " + time + " ms;" : ";") + rowCountString
        + (usedIndexesString != null ? usedIndexesString : "") + " \"" + query + '"';
  }

  @Override
  public Object execute(RegionFunctionContext context) throws FunctionDomainException,
      TypeMismatchException, NameResolutionException, QueryInvocationTargetException {
    return execute(context, EMPTY_ARRAY);
  }

  @Override
  public Object execute(RegionFunctionContext context, Object[] params)
      throws FunctionDomainException, TypeMismatchException, NameResolutionException,
      QueryInvocationTargetException {
    // Supported only with RegionFunctionContext
    if (context == null) {
      throw new IllegalArgumentException(
          "'Function Context' cannot be null");
    }
    isQueryWithFunctionContext = true;

    if (params == null) {
      throw new IllegalArgumentException(
          "'parameters' cannot be null");
    }

    long startTime = 0L;
    if (traceOn && cache != null) {
      startTime = NanoTimer.getTime();
    }

    QueryObserver indexObserver = null;
    QueryExecutor qe = checkQueryOnPR(params);

    Object result = null;
    try {
      indexObserver = startTrace();
      if (qe != null) {
        LocalDataSet localDataSet =
            (LocalDataSet) PartitionRegionHelper.getLocalDataForContext(context);
        Set<Integer> buckets = localDataSet.getBucketSet();
        final ExecutionContext executionContext = new ExecutionContext(null, cache);
        result = qe.executeQuery(this, executionContext, params, buckets);
        return result;
      } else {
        // Not supported on regions other than PartitionRegion.
        throw new IllegalArgumentException(
            "This query API can only be used for Partition Region Queries.");
      }

    } finally {
      endTrace(indexObserver, startTime, result);
    }
  }

  /**
   * For queries which are executed from a Function "with a Filter".
   *
   * @return returns if this query is coming from a {@link Function}.
   */
  public boolean isQueryWithFunctionContext() {
    return isQueryWithFunctionContext;
  }

  public QueryObserver startTrace() {
    QueryObserver queryObserver = null;
    if (traceOn && cache != null) {

      QueryObserver qo = QueryObserverHolder.getInstance();
      if (qo instanceof IndexTrackingQueryObserver) {
        queryObserver = qo;
      } else if (!QueryObserverHolder.hasObserver()) {
        queryObserver = new IndexTrackingQueryObserver();
        QueryObserverHolder.setInstance(queryObserver);
      } else {
        queryObserver = qo;
      }
      logger.info("Starting query: " + queryString);
    }
    return queryObserver;
  }

  public void endTrace(QueryObserver indexObserver, long startTime, Object result) {
    if (traceOn && cache != null) {
      int resultSize = -1;

      if (result instanceof Collection) {
        resultSize = ((Collection) result).size();
      }

      String queryVerboseMsg =
          DefaultQuery.getLogMessage(indexObserver, startTime, resultSize, queryString);
      logger.info(queryVerboseMsg);
    }
  }

  public void endTrace(QueryObserver indexObserver, long startTime, Collection<Collection> result) {
    if (logger.isInfoEnabled() && traceOn) {
      int resultSize = 0;

      for (Collection aResult : result) {
        resultSize += aResult.size();
      }

      String queryVerboseMsg =
          DefaultQuery.getLogMessage(indexObserver, startTime, resultSize, queryString);
      if (logger.isInfoEnabled()) {
        logger.info(queryVerboseMsg);
      }
    }
  }

  public boolean isRemoteQuery() {
    return isRemoteQuery;
  }

  public void setRemoteQuery(boolean isRemoteQuery) {
    this.isRemoteQuery = isRemoteQuery;
  }

  /**
   * set keepSerialized flag for remote queries of type 'select *' having independent operators
   */
  void keepResultsSerialized(CompiledSelect cs, ExecutionContext context) {
    if (isRemoteQuery()) {
      // for dependent iterators, deserialization is required
      if (cs.getIterators().size() == context.getAllIndependentIteratorsOfCurrentScope().size()
          && cs.getWhereClause() == null && cs.getProjectionAttributes() == null && !cs.isDistinct()
          && cs.getOrderByAttrs() == null) {
        setKeepSerialized();
      }
    }
  }

  public boolean isKeepSerialized() {
    return keepSerialized;
  }

  private void setKeepSerialized() {
    keepSerialized = true;
  }

  /**
   * Test logic sets DefaultQuery.testHook to an implementation of this interface,
   * to facilitate white-box testing.
   *
   * DefaultQuery and other classes in query* packages invoke doTestHook() at various points
   * during query processing--identifying the location with a SPOT value.
   */
  @FunctionalInterface
  public interface TestHook {
    enum SPOTS {

      /*
       * These spots pass a DefaultQuery
       */
      BEFORE_QUERY_EXECUTION, /* was 1 */
      BEFORE_QUERY_DEPENDENCY_COMPUTATION, /* was 6 */

      /*
       * These spots do not pass a DefaultQuery
       */
      LOW_MEMORY_WHEN_DESERIALIZING_STREAMINGOPERATION, /* was 2 */
      BEFORE_ADD_OR_UPDATE_MAPPING_OR_DESERIALIZING_NTH_STREAMINGOPERATION, /* was 3 */
      BEFORE_BUILD_CUMULATIVE_RESULT, /* was 4 */
      BEFORE_THROW_QUERY_CANCELED_EXCEPTION, /* was 5 */
      BEGIN_TRANSITION_FROM_REGION_ENTRY_TO_ELEMARRAY,
      TRANSITIONED_FROM_REGION_ENTRY_TO_ELEMARRAY,
      COMPLETE_TRANSITION_FROM_REGION_ENTRY_TO_ELEMARRAY,
      BEGIN_TRANSITION_FROM_ELEMARRAY_TO_CONCURRENT_HASH_SET,
      TRANSITIONED_FROM_ELEMARRAY_TO_TOKEN,
      COMPLETE_TRANSITION_FROM_ELEMARRAY_TO_CONCURRENT_HASH_SET,
      ATTEMPT_REMOVE,
      ATTEMPT_RETRY,
      BEGIN_REMOVE_FROM_ELEM_ARRAY,
      REMOVE_CALLED_FROM_ELEM_ARRAY,
      COMPLETE_REMOVE_FROM_ELEM_ARRAY,
      PULL_OFF_PR_QUERY_TRACE_INFO,
      CREATE_PR_QUERY_TRACE_STRING,
      CREATE_PR_QUERY_TRACE_INFO_FROM_LOCAL_NODE,
      CREATE_PR_QUERY_TRACE_INFO_FOR_REMOTE_QUERY,
      POPULATING_TRACE_INFO_FOR_REMOTE_QUERY
    }

    /**
     * Called (for side-effects) at various points in query processing, to facilitate
     * white-box testing.
     *
     * @param spot identifies the (logical) calling code location. Some SPOT values represent
     *        more than one physical location in the query processing code.
     * @param query nullable, DefaultQuery, for SPOTS in the DefaultQuery class
     */
    void doTestHook(SPOTS spot, DefaultQuery query,
        ExecutionContext executionContext);
  }

}
