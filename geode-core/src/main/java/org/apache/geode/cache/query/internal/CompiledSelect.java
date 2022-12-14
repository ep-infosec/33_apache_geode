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
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheClosedException;
import org.apache.geode.cache.EntryDestroyedException;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.query.AmbiguousNameException;
import org.apache.geode.cache.query.FunctionDomainException;
import org.apache.geode.cache.query.Index;
import org.apache.geode.cache.query.NameNotFoundException;
import org.apache.geode.cache.query.NameResolutionException;
import org.apache.geode.cache.query.Query;
import org.apache.geode.cache.query.QueryInvalidException;
import org.apache.geode.cache.query.QueryInvocationTargetException;
import org.apache.geode.cache.query.QueryService;
import org.apache.geode.cache.query.RegionNotFoundException;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.Struct;
import org.apache.geode.cache.query.TypeMismatchException;
import org.apache.geode.cache.query.internal.index.AbstractIndex;
import org.apache.geode.cache.query.internal.index.PartitionedIndex;
import org.apache.geode.cache.query.internal.types.ObjectTypeImpl;
import org.apache.geode.cache.query.internal.types.StructTypeImpl;
import org.apache.geode.cache.query.internal.types.TypeUtils;
import org.apache.geode.cache.query.types.CollectionType;
import org.apache.geode.cache.query.types.ObjectType;
import org.apache.geode.cache.query.types.StructType;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.pdx.PdxInstance;
import org.apache.geode.pdx.internal.PdxString;

public class CompiledSelect extends AbstractCompiledValue {

  protected List<CompiledSortCriterion> orderByAttrs; // order by attributes: list of CompiledValue
  private final CompiledValue whereClause; // can be null if there isn't one
  private final List iterators; // fromClause: list of CompiledIteratorDefs
  protected List projAttrs; // projection attributes: list of Object[2]:
  // 0 is projection name, 1 is the CompiledValue for the expression
  private boolean distinct;
  private boolean count;
  // limits the SelectResults by the number specified.
  private final CompiledValue limit;
  // counts the no of results satisfying where condition for
  // count(*) non-distinct queries where no indexes are used.
  private int countStartQueryResult = 0;

  protected List<CompiledValue> groupBy = null;
  // Are not serialized and are recreated when compiling the query
  private final List<String> hints;
  protected boolean transformationDone = false;
  protected ObjectType cachedElementTypeForOrderBy = null;
  private boolean hasUnmappedOrderByCols = false;

  // used as a key in a context to identify the scope of this CompiledSelect
  private final Object scopeID = new Object();

  /*
   * Set in context for the where clause to signify that it has been evaluated at least one time for
   * any other CompiledValue that may use precalculated indexes we want to mark this as Evaluated so
   * that we don't unlock locks that don't belong to this iteration of evaluate. This is similar to
   * how CompiledComparisons store their IndexInfo in the context but for example a CompiledJunction
   * that uses 2 Comparisons would have unlocked the readlocks because we check to see if the clause
   * has a mapped value in the context. Because CompiledJunctions did not, we unlocked the read
   * locks. Now we set a value so that it will not do this. See where we use this value to see how
   * unlock is determined
   */
  private static final String CLAUSE_EVALUATED = "Evaluated";

  public CompiledSelect(boolean distinct, boolean count, CompiledValue whereClause, List iterators,
      List projAttrs, List<CompiledSortCriterion> orderByAttrs, CompiledValue limit,
      List<String> hints, List<CompiledValue> groupByClause) {
    this.orderByAttrs = orderByAttrs;
    this.whereClause = whereClause;
    this.iterators = iterators;
    this.projAttrs = projAttrs;
    this.distinct = distinct;
    this.count = count;
    this.limit = limit;
    this.hints = hints;
    groupBy = groupByClause;

  }

  @Override
  public List getChildren() {
    List list = new ArrayList();
    if (whereClause != null) {
      list.add(whereClause);
    }

    list.addAll(iterators);

    if (projAttrs != null) {
      // extract the CompiledValues out of the projAttrs (each of which are Object[2])
      for (final Object projAttr : projAttrs) {
        list.add(((Object[]) projAttr)[1]);
      }
    }

    if (orderByAttrs != null) {
      list.addAll(orderByAttrs);
    }

    return list;
  }

  public boolean isDistinct() {
    return distinct;
  }

  public boolean isGroupBy() {
    return groupBy != null;
  }

  public boolean isOrderBy() {
    return orderByAttrs != null;
  }

  public void setDistinct(boolean distinct) {
    this.distinct = distinct;
  }

  public boolean isCount() {
    return count;
  }

  public void setCount(boolean count) {
    this.count = count;
  }

  @Override
  public int getType() {
    return LITERAL_select;
  }

  public CompiledValue getWhereClause() {
    return whereClause;
  }

  public List getIterators() {
    return iterators;
  }

  public List getProjectionAttributes() {
    return projAttrs;
  }

  public List<CompiledSortCriterion> getOrderByAttrs() {
    return orderByAttrs;
  }

  @Override
  public Set computeDependencies(ExecutionContext context)
      throws TypeMismatchException, NameResolutionException {
    // bind iterators in new scope in order to determine dependencies
    context.cachePut(scopeID, context.associateScopeID());
    context.newScope((Integer) context.cacheGet(scopeID));
    context.pushExecCache((Integer) context.cacheGet(scopeID));
    try {
      for (final Object iterator : iterators) {

        CompiledIteratorDef iterDef = (CompiledIteratorDef) iterator;
        // compute dependencies on this iter first before adding its
        // RuntimeIterator to the current scope.
        // this makes sure it doesn't bind attributes to itself
        context.addDependencies(this, iterDef.computeDependencies(context));
        RuntimeIterator rIter = iterDef.getRuntimeIterator(context);
        context.addToIndependentRuntimeItrMap(iterDef);
        context.bindIterator(rIter);
      }

      // is the where clause dependent on itr?
      if (whereClause != null) {
        context.addDependencies(this, whereClause.computeDependencies(context));
      }
      // are any projections dependent on itr?
      if (projAttrs != null) {
        Set totalDependencySet = null;
        for (Iterator iter = projAttrs.iterator(); iter.hasNext();) {
          // unwrap the projection expressions, they are in 2-element Object[]
          // with first element the fieldName and second the projection
          // expression
          Object[] prj = (Object[]) TypeUtils.checkCast(iter.next(), Object[].class);
          CompiledValue prjExpr = (CompiledValue) TypeUtils.checkCast(prj[1], CompiledValue.class);
          totalDependencySet = context.addDependencies(this, prjExpr.computeDependencies(context));
        }
        doTreeTransformation(context);

        return totalDependencySet;
      } else {
        doTreeTransformation(context);
        return context.getDependencySet(this, true);
      }
      // is the where clause dependent on itr?
      /*
       * if (this.whereClause != null) { return context.addDependencies(this,
       * this.whereClause.computeDependencies(context)); } else { return
       * context.getDependencySet(this, true); }
       */
    } finally {
      context.popExecCache();
      context.popScope();
    }
  }

  protected void doTreeTransformation(ExecutionContext context)
      throws TypeMismatchException, NameResolutionException {
    if (!transformationDone) {
      cachedElementTypeForOrderBy = prepareResultType(context);
      mapOrderByColumns(context);
      transformGroupByIfPossible(context);
    }
    transformationDone = true;
  }

  /**
   * Transforms the group by clause into distinct order by clause, if possible
   */
  private void transformGroupByIfPossible(ExecutionContext context)
      throws TypeMismatchException, NameResolutionException {
    // for time being assume that the group by cols are explicitly mentioned in proj
    if (groupBy != null) {
      List projAttribs = projAttrs;
      if (projAttribs == null) {
        projAttribs = new ArrayList();
        List currentIters = context.getCurrentIterators();
        for (Object o : currentIters) {
          RuntimeIterator rIter = (RuntimeIterator) o;
          String name = rIter.getName();
          projAttribs.add(new Object[] {name, rIter});
        }
      }

      if (projAttribs != null && projAttribs.size() != groupBy.size()) {
        throw new QueryInvalidException(
            "Query contains projected column not present in group by clause or "
                + "Query contains group by columns not present in projected fields");
      }

      boolean shouldTransform = true;
      StringBuilder lhsBuffer = new StringBuilder();
      StringBuilder rhsBuffer = new StringBuilder();

      outer: for (int i = 0; i < projAttribs.size(); ++i) {
        Object[] prj = (Object[]) TypeUtils.checkCast(projAttribs.get(i), Object[].class);
        CompiledValue groupByAttr = groupBy.get(i);
        if (prj[0] != null) {
          if (groupByAttr instanceof CompiledID) {
            if (prj[0].equals(((CompiledID) groupByAttr).getId())) {
              lhsBuffer.delete(0, lhsBuffer.length());
              rhsBuffer.delete(0, rhsBuffer.length());
              continue;
            }
          }
        }
        CompiledValue cvProj = (CompiledValue) TypeUtils.checkCast(prj[1], CompiledValue.class);
        cvProj.generateCanonicalizedExpression(lhsBuffer, context);
        groupByAttr.generateCanonicalizedExpression(rhsBuffer, context);
        if (lhsBuffer.length() == rhsBuffer.length()) {
          for (int indx = 0; indx < lhsBuffer.length(); ++indx) {
            if (lhsBuffer.charAt(indx) != rhsBuffer.charAt(indx)) {
              shouldTransform = false;
              break outer;
            }
          }
        } else {
          shouldTransform = false;
          break;
        }

        lhsBuffer.delete(0, lhsBuffer.length());
        rhsBuffer.delete(0, rhsBuffer.length());

      }
      // check if the order by clause is null or order by clause is same as proj.
      // for now check if order by is null
      if (shouldTransform && orderByAttrs == null) {
        modifyGroupByToOrderBy(true, context);
      } else {
        throw new QueryInvalidException(
            "Query contains projected column not present in group by clause or "
                + "Query contains group by columns not present in projected fields");
      }
    }
  }

  protected void modifyGroupByToOrderBy(boolean setDistinct, ExecutionContext context)
      throws TypeMismatchException, NameResolutionException {
    if (setDistinct) {
      distinct = setDistinct;
    }
    orderByAttrs = new ArrayList<>(groupBy.size());
    int colIndex = 0;
    for (CompiledValue cv : groupBy) {
      CompiledSortCriterion csc = new CompiledSortCriterion(false, cv);
      csc.mapExpressionToProjectionField(projAttrs, context);
      orderByAttrs.add(csc);
    }
    groupBy = null;
  }

  private void mapOrderByColumns(ExecutionContext context)
      throws TypeMismatchException, NameResolutionException {
    if (orderByAttrs != null) {
      for (final CompiledSortCriterion csc : orderByAttrs) {
        // Ideally for replicated regions, the requirement that
        // projected columns should
        // contain order by fields ( directly or derivable on it),
        // is not needed. But for PR , the query gathers only projected
        // columns, so applying order by on the query node
        // will need order by values ( which we dont send). So this
        // restriction is needed.
        // Also if this restriction is assumed to be correct, then the order
        // by comparator can be optimized as
        // it does not need to keep the mapping of evaluated order by clause,
        // for comparison
        if (!csc.mapExpressionToProjectionField(projAttrs, context)) {
          hasUnmappedOrderByCols = true;
        }
      }
    }
  }

  private void evalCanonicalizedExpressionForCSC(CompiledSortCriterion csc,
      ExecutionContext context, StringBuilder buffer)
      throws TypeMismatchException, NameResolutionException {
    csc.getExpr().generateCanonicalizedExpression(buffer, context);
  }

  /*
   * Gets the appropriate empty results set when outside of actual query evalutaion.
   *
   * @param parameters the parameters that will be passed into the query when evaluated
   *
   * @param cache the cache the query will be executed in the context of
   *
   * @return the empty result set of the appropriate type
   */
  public SelectResults getEmptyResultSet(Object[] parameters, InternalCache cache, Query query)
      throws FunctionDomainException, TypeMismatchException, NameResolutionException,
      QueryInvocationTargetException {
    ExecutionContext context = new QueryExecutionContext(parameters, cache, query);
    computeDependencies(context);
    context.newScope((Integer) context.cacheGet(scopeID));
    context.pushExecCache((Integer) context.cacheGet(scopeID));
    SelectResults results = null;
    try {
      for (final Object iterator : iterators) {
        CompiledIteratorDef iterDef = (CompiledIteratorDef) iterator;
        RuntimeIterator rIter = iterDef.getRuntimeIterator(context);
        context.bindIterator(rIter);
      }
      results = prepareEmptyResultSet(context, false);
    } finally {
      context.popScope();
      context.popExecCache();
    }
    return results;
  }

  public ObjectType getElementTypeForOrderByQueries() {
    return cachedElementTypeForOrderBy;
  }

  @Override
  public SelectResults evaluate(ExecutionContext context) throws FunctionDomainException,
      TypeMismatchException, NameResolutionException, QueryInvocationTargetException {
    context.newScope((Integer) context.cacheGet(scopeID));
    context.pushExecCache((Integer) context.cacheGet(scopeID));
    boolean prevDistinctState = context.isDistinct();
    context.setDistinct(distinct);
    if (hasUnmappedOrderByCols && context.getBucketList() != null) {
      throw new QueryInvalidException(
          "Query contains atleast one order by field which is not present in projected fields.");
    }
    if (hints != null) {
      context.cachePut(QUERY_INDEX_HINTS, hints);
    }

    try {
      // set flag to keep objects serialized for "select *" queries
      if (context.getQuery() != null) {
        ((DefaultQuery) context.getQuery()).keepResultsSerialized(this, context);
      }
      for (final Object iterator : iterators) {
        CompiledIteratorDef iterDef = (CompiledIteratorDef) iterator;
        RuntimeIterator rIter = iterDef.getRuntimeIterator(context);
        context.bindIterator(rIter);
        // Ideally the function below should always be called after binding has occurred
        // So that the internal ID gets set during binding to the scope. If not so then chances
        // are that internal_id is still null causing index_internal_id to be null.
        // Though in our case it may not be an issue as the compute dependency phase must have
        // already set the index id
      }
      Integer limitValue = evaluateLimitValue(context, limit);
      SelectResults result = null;
      boolean evalAsFilters = false;
      if (whereClause == null) {
        result = doIterationEvaluate(context, false);
      } else {
        if (!whereClause.isDependentOnCurrentScope(context)) { // independent
                                                               // where
                                                               // @todo
                                                               // check
                                                               // for
                                                               // dependency
                                                               // on
                                                               // current
                                                               // scope
                                                               // only?
          // clause
          Object b = whereClause.evaluate(context);
          if (b == null || b == QueryService.UNDEFINED) {
            // treat as if all elements are undefined
            result = prepareEmptyResultSet(context, false);
            // ResultsSet.emptyResultsSet(resultSet, 0);
            // return result;
          } else if (!(b instanceof Boolean)) {
            throw new TypeMismatchException(
                String.format("The WHERE clause was type ' %s ' instead of boolean",
                    b.getClass().getName()));
          } else if ((Boolean) b) {
            result = doIterationEvaluate(context, false);
          } else {
            result = prepareEmptyResultSet(context, false);
            // ResultsSet.emptyResultsSet(resultSet, 0);
            // return result;
          }
        } else {
          // Check the numer of independent iterators
          int numInd = context.getAllIndependentIteratorsOfCurrentScope().size();
          // If order by clause is defined, then the first column should be the preferred index
          if (orderByAttrs != null && numInd == 1) {
            CompiledSortCriterion csc = orderByAttrs.get(0);
            StringBuilder preferredIndexCondn = new StringBuilder();
            evalCanonicalizedExpressionForCSC(csc, context, preferredIndexCondn);
            context.cachePut(PREF_INDEX_COND, preferredIndexCondn.toString());
          }
          boolean unlock = true;
          Object obj = context.cacheGet(whereClause);
          if (obj != null && (obj instanceof IndexInfo[] || obj.equals(CLAUSE_EVALUATED))) {
            // if indexinfo is cached means the read lock
            // is not being taken this time, so releasing
            // the lock is not required
            unlock = false;
          }
          // see if we should evaluate as filters,
          // and count how many actual index lookups will be performed
          PlanInfo planInfo = whereClause.getPlanInfo(context);
          if (context.cacheGet(whereClause) == null) {
            context.cachePut(whereClause, CLAUSE_EVALUATED);
          }
          try {
            evalAsFilters = planInfo.evalAsFilter;
            // let context know if there is exactly one index lookup
            context.setOneIndexLookup(planInfo.indexes.size() == 1);
            if (evalAsFilters) {
              ((QueryExecutionContext) context).setIndexUsed(true);
              // Ignore order by attribs for a while

              boolean canApplyOrderByAtIndex = false;
              if (limitValue >= 0 && numInd == 1
                  && ((Filter) whereClause).isLimitApplicableAtIndexLevel(context)) {
                context.cachePut(CAN_APPLY_LIMIT_AT_INDEX, Boolean.TRUE);
              }
              StringBuilder temp = null;
              if (orderByAttrs != null) {
                temp = new StringBuilder();
                CompiledSortCriterion csc = orderByAttrs.get(0);
                evalCanonicalizedExpressionForCSC(csc, context, temp);
              }

              boolean needsTopLevelOrdering = true;
              if (temp != null && numInd == 1 && ((Filter) whereClause)
                  .isOrderByApplicableAtIndexLevel(context, temp.toString())) {
                context.cachePut(CAN_APPLY_ORDER_BY_AT_INDEX, Boolean.TRUE);
                context.cachePut(ORDERBY_ATTRIB, orderByAttrs);
                canApplyOrderByAtIndex = true;
                if (orderByAttrs.size() == 1) {
                  needsTopLevelOrdering = false;
                  // If there is a limit present and we are executing on a partitioned region
                  // we should use a sorted set
                  if (limit != null) {
                    // Currently check bucket list to determine if it's a pr query
                    if (context.getBucketList() != null && context.getBucketList().size() > 0) {
                      needsTopLevelOrdering = true;
                    }
                  }
                }
              } else if (temp != null) {
                // If order by is present but cannot be applied at index level,
                // then limit also cannot be applied
                // at index level
                context.cachePut(CAN_APPLY_LIMIT_AT_INDEX, Boolean.FALSE);
              }

              context.cachePut(RESULT_LIMIT, limitValue);
              if (numInd == 1
                  && ((Filter) whereClause).isProjectionEvaluationAPossibility(context)
                  && (orderByAttrs == null
                      || (canApplyOrderByAtIndex && !needsTopLevelOrdering))
                  && projAttrs != null) {
                // Possibility of evaluating the resultset as filter itself
                ObjectType resultType = cachedElementTypeForOrderBy != null
                    ? cachedElementTypeForOrderBy : prepareResultType(context);
                context.cachePut(RESULT_TYPE, resultType);
                context.cachePut(PROJ_ATTRIB, projAttrs);
              }


              result = ((Filter) whereClause).filterEvaluate(context, null);
              if (!(context.cacheGet(RESULT_TYPE) instanceof Boolean)) {
                QueryObserverHolder.getInstance()
                    .beforeApplyingProjectionOnFilterEvaluatedResults(result);
                result = applyProjectionOnCollection(result, context, !needsTopLevelOrdering);
              }
            } else {
              // otherwise iterate over the single from var to evaluate
              result = doIterationEvaluate(context, true);
            }
          } finally {
            // The Read lock is acquired in {@link
            // IndexManager#getBestMatchIndex()},
            // because we need to select index which can be read-locked.
            if (unlock) {
              releaseReadLockOnUsedIndex(planInfo);
            }
          }
        }
      }
      // TODO: It does not appear that results would be null ever.
      // if (result == null) { return QueryService.UNDEFINED; }
      assert result != null;
      // drop duplicates if this is DISTINCT
      if (result instanceof SelectResults) {
        SelectResults sr = result;
        CollectionType colnType = sr.getCollectionType();
        // if (this.distinct && colnType.allowsDuplicates()) {
        if (distinct) {
          Collection r;
          // Set s = sr.asSet();
          if (colnType.allowsDuplicates()) {
            // don't just convert to a ResultsSet (or StructSet), since
            // the bags can convert themselves to a Set more efficiently
            r = sr.asSet();
          } else {
            r = sr;
          }

          result = new ResultsCollectionWrapper(colnType.getElementType(), r, limitValue);
          if (r instanceof Bag.SetView) {
            ((ResultsCollectionWrapper) result).setModifiable(false);
          }
        } else {
          // SelectResults is of type
          if (limitValue > -1) {
            ((Bag) sr).applyLimit(limitValue);
          }
        }

        /*
         * We still have to get size of SelectResults in some cases like, if index was used OR query
         * is a distinct query.
         *
         * If SelectResult size is zero then we need to put Integer for 0 count.
         */
        if (count) {
          SelectResults res = result;

          if ((distinct || evalAsFilters || countStartQueryResult == 0)) {
            // Retrun results as it is as distinct is applied
            // at coordinator node for PR queries.
            if (context.getBucketList() != null && distinct) {
              return result;
            }
            // Take size and empty the results
            int resultCount = res.size();
            res.clear();

            ResultsBag countResult =
                new ResultsBag(new ObjectTypeImpl(Integer.class), context.getCachePerfStats());
            countResult.addAndGetOccurence(resultCount);
            result = countResult;

          } else {
            ((Bag) res).addAndGetOccurence(countStartQueryResult);
          }
        }
      }
      return result;
    } finally {
      context.setDistinct(prevDistinctState);
      context.popScope();
      context.popExecCache();
    }
  }

  /**
   * The index is locked during query to prevent it from being removed by another thread. So we have
   * to release the lock only after whole query is finished as one query can use an index multiple
   * times.
   */
  private void releaseReadLockOnUsedIndex(PlanInfo planInfo) {
    List inds = planInfo.indexes;
    for (Object obj : inds) {
      Index index = (Index) obj;
      Index prIndex = ((AbstractIndex) index).getPRIndex();
      if (prIndex != null) {
        ((PartitionedIndex) prIndex).releaseIndexReadLockForRemove();
      } else {
        ((AbstractIndex) index).releaseIndexReadLockForRemove();
      }
    }
  }

  /**
   * Returns the size of region iterator for count(*) on a region without whereclause.
   *
   * @since GemFire 6.6.2
   */
  private int getRegionIteratorSize(ExecutionContext context, CompiledValue collExpr)
      throws RegionNotFoundException {
    Region region;
    String regionPath = ((CompiledRegion) collExpr).getRegionPath();
    if (context.getBucketRegion() == null) {
      region = context.getCache().getRegion(regionPath);
    } else {
      region = context.getBucketRegion();
    }
    if (region != null) {
      return region.size();
    } else {
      // if we couldn't find the region because the cache is closed, throw
      // a CacheClosedException
      Cache cache = context.getCache();
      if (cache.isClosed()) {
        throw new CacheClosedException();
      }
      throw new RegionNotFoundException(
          String.format("Region not found: %s", regionPath));
    }
  }

  public int getLimitValue(Object[] bindArguments) throws FunctionDomainException,
      TypeMismatchException, NameResolutionException, QueryInvocationTargetException {
    // if evaluation of the limit fails, we default to no limit
    return evaluateLimitValue(bindArguments);
  }

  // returns null if result is UNDEFINED
  private SelectResults doIterationEvaluate(ExecutionContext context, boolean evaluateWhereClause)
      throws TypeMismatchException, FunctionDomainException, NameResolutionException,
      QueryInvocationTargetException {

    SelectResults results = prepareEmptyResultSet(context, false);

    // TODO: SELF : Work on limit implementation on bulk get
    // check for bulk get optimization
    if (evaluateWhereClause) {
      List tmpResults = optimizeBulkGet(context);
      if (tmpResults != null) {
        // (has only one iterator)
        RuntimeIterator rIter = (RuntimeIterator) context.getCurrentIterators().get(0);
        for (Object currObj : tmpResults) {
          rIter.setCurrent(currObj);
          QueryObserver observer = QueryObserverHolder.getInstance();
          observer.beforeIterationEvaluation(rIter, currObj);
          applyProjectionAndAddToResultSet(context, results, orderByAttrs == null);
        }
        return results;
      }
    }
    int numElementsInResult = 0;
    try {
      doNestedIterations(0, results, context, evaluateWhereClause, numElementsInResult);
    } catch (CompiledSelect.NullIteratorException ignore) {
      return null;
    }
    return results;
  }

  // TODO: make this more general to work for any kind of map, not just regions
  /**
   * Check for the bulk-get pattern and if it applies do an optimized execution. The pattern is:
   * SELECT ?? FROM <Region>.entrySet e WHERE e.key IN <Collection>.
   *
   * @return a List of entries if optimization was executed, or null if it wasn't because the
   *         optimization pattern didn't match
   */
  private List optimizeBulkGet(ExecutionContext context) throws TypeMismatchException,
      FunctionDomainException, NameResolutionException, QueryInvocationTargetException {
    List iterList = context.getCurrentIterators();
    // must be one iterator
    if (iterList.size() != 1) {
      return null;
    }

    // where clause must be an IN operation
    if (!(whereClause instanceof CompiledIn)) {
      return null;
    }

    RuntimeIterator rIter = (RuntimeIterator) iterList.get(0);
    CompiledIteratorDef cIterDef = rIter.getCmpIteratorDefn();
    CompiledValue colnExpr = cIterDef.getCollectionExpr();

    // check for region.entrySet or region.entrySet()
    boolean match = false;
    CompiledRegion rgn = null;
    if (colnExpr instanceof CompiledPath) {
      CompiledPath cPath = (CompiledPath) colnExpr;
      CompiledValue rcvr = cPath.getReceiver();
      if (rcvr instanceof CompiledRegion) {
        rgn = (CompiledRegion) rcvr;
        String attr = cPath.getTailID();
        match = attr.equals("entrySet");
      }
    }
    if (!match && (colnExpr instanceof CompiledOperation)) {
      CompiledOperation cOp = (CompiledOperation) colnExpr;
      CompiledValue rcvr = cOp.getReceiver(context);
      if (rcvr instanceof CompiledRegion) {
        rgn = (CompiledRegion) rcvr;
        match = cOp.getMethodName().equals("entrySet");
      }
    }
    if (!match) {
      return null;
    }

    // check for IN expression
    CompiledIn cIn = (CompiledIn) whereClause;
    // defer to the CompiledIn for rest of pattern match and
    // evaluation
    return cIn.optimizeBulkGet(rgn, context);
  }

  // returns the number of elements added in the return ResultSet
  private int doNestedIterations(int level, SelectResults results, ExecutionContext context,
      boolean evaluateWhereClause, int numElementsInResult)
      throws TypeMismatchException, FunctionDomainException, NameResolutionException,
      QueryInvocationTargetException, CompiledSelect.NullIteratorException {
    List iterList = context.getCurrentIterators();
    if (level == iterList.size()) {
      boolean addToResults = true;
      if (evaluateWhereClause) {
        Object result = whereClause.evaluate(context);
        QueryObserver observer = QueryObserverHolder.getInstance();
        observer.afterIterationEvaluation(result);
        if (result == null) {
          addToResults = false;
        } else if (result instanceof Boolean) {
          addToResults = (Boolean) result;
        } else if (result == QueryService.UNDEFINED) {
          // add UNDEFINED to results only for NOT EQUALS queries
          if (whereClause.getType() == COMPARISON) {
            int operator = ((Filter) whereClause).getOperator();
            if ((operator != TOK_NE && operator != TOK_NE_ALT)) {
              addToResults = false;
            }
          } else {
            addToResults = false;
          }
        } else {
          throw new TypeMismatchException(
              String.format("The WHERE clause was type ' %s ' instead of boolean",
                  result.getClass().getName()));
        }
      }
      if (addToResults) {
        int occurrence =
            applyProjectionAndAddToResultSet(context, results, orderByAttrs == null);
        // If the occurrence is greater than 1, then only in case of
        // non distinct query should it be treated as contributing to size
        // else duplication will be eliminated when making it distinct using
        // ResultsCollectionWrapper and we will fall short of limit
        if (occurrence == 1 || (occurrence > 1 && !distinct)) {
          // (Unique i.e first time occurrence) or subsequent occurrence
          // for non distinct query
          ++numElementsInResult;
        }
      }
    } else {
      RuntimeIterator rIter = (RuntimeIterator) iterList.get(level);
      SelectResults sr = rIter.evaluateCollection(context);
      if (sr == null) {
        return 0; // continue iteration if a collection evaluates to UNDEFINED
      }

      // Check if its a non-distinct count(*) query without where clause, in that case,
      // we can size directly on the region.
      if (whereClause == null && iterators.size() == 1 && isCount() && !isDistinct()
          && sr instanceof QRegion) {
        QRegion qr = (QRegion) sr;
        countStartQueryResult = qr.getRegion().size();
        return 1;
      }

      // #44807: select * query should not deserialize objects
      // In case of "select *" queries we can keep the results in serialized
      // form and send it to the client.
      if (context.getQuery() != null && ((DefaultQuery) context.getQuery()).isKeepSerialized()
          && sr instanceof QRegion) {
        ((QRegion) sr).setKeepSerialized(true);
      }

      // Iterate through the data set.
      for (Object aSr : sr) {
        // Check if query execution on this thread is canceled.
        QueryMonitor.throwExceptionIfQueryOnCurrentThreadIsCanceled();

        rIter.setCurrent(aSr);
        QueryObserver observer = QueryObserverHolder.getInstance();
        observer.beforeIterationEvaluation(rIter, aSr);
        numElementsInResult = doNestedIterations(level + 1, results, context, evaluateWhereClause,
            numElementsInResult);
        Integer limitValue = evaluateLimitValue(context, limit);
        if (orderByAttrs == null && limitValue > -1 && numElementsInResult == limitValue) {
          break;
        }
      }
    }
    return numElementsInResult;
  }

  private SelectResults applyProjectionOnCollection(SelectResults resultSet,
      ExecutionContext context, boolean ignoreOrderBy) throws TypeMismatchException,
      FunctionDomainException, NameResolutionException, QueryInvocationTargetException {
    List iterators = context.getCurrentIterators();
    if (projAttrs == null && (orderByAttrs == null || ignoreOrderBy)) {
      // If the projection attribute is null (ie specified as *) & there is only one Runtime
      // Iterator we can return the set as it is. But if the projection attribute is null & multiple
      // Iterators are defined we need to rectify the StructBag that is returned. It is to be noted
      // that in case of single from clause where the from clause itself is defined as nested select
      // query with multiple from clauses, the result set returned will be a StructBag which we have
      // to return as it is.
      if (iterators.size() > 1) {
        StructType type = createStructTypeForNullProjection(iterators, context);
        resultSet.setElementType(type);
      }

      return resultSet;
    } else {
      int numElementsAdded = 0;
      SelectResults pResultSet = prepareEmptyResultSet(context, ignoreOrderBy);
      boolean isStructType = resultSet.getCollectionType().getElementType() != null
          && resultSet.getCollectionType().getElementType().isStructType();
      if (isStructType) {
        Iterator resultsIter = resultSet.iterator();
        // Apply limit if there is no order by
        Integer limitValue = evaluateLimitValue(context, limit);
        while (((orderByAttrs != null && !ignoreOrderBy) || limitValue < 0
            || (numElementsAdded < limitValue)) && resultsIter.hasNext()) {
          // Check if query execution on this thread is canceled
          QueryMonitor.throwExceptionIfQueryOnCurrentThreadIsCanceled();

          Object[] values = ((Struct) resultsIter.next()).getFieldValues();
          for (int i = 0; i < values.length; i++) {
            ((RuntimeIterator) iterators.get(i)).setCurrent(values[i]);
          }
          int occurrence = applyProjectionAndAddToResultSet(context, pResultSet, ignoreOrderBy);
          if (occurrence == 1 || (occurrence > 1 && !distinct)) {
            // (Unique i.e first time occurrence) or subsequent occurrence
            // for non distinct query
            ++numElementsAdded;
          }
        }
        // return pResultSet;
      } else if (iterators.size() == 1) {
        RuntimeIterator rIter = (RuntimeIterator) iterators.get(0);
        Iterator resultsIter = resultSet.iterator();
        // Apply limit if there is no order by.
        Integer limitValue = evaluateLimitValue(context, limit);
        while (((orderByAttrs != null && !ignoreOrderBy) || limitValue < 0
            || (numElementsAdded < limitValue)) && resultsIter.hasNext()) {
          rIter.setCurrent(resultsIter.next());
          int occurrence = applyProjectionAndAddToResultSet(context, pResultSet, ignoreOrderBy);
          if (occurrence == 1 || (occurrence > 1 && !distinct)) {
            // (Unique i.e first time occurrence) or subsequent occurrence
            // for non distinct query
            ++numElementsAdded;
          }
        }
      } else {
        throw new RuntimeException(
            "Result Set does not match with iterator definitions in from clause");
      }
      return pResultSet;
    }
  }

  public enum DataContainerType {
    // isOrdered, distinct, elementType.isStructType(), ignoreOrderBy
    UNORDERED_DISTINCT_STRUCT(false, true, true, true),
    UNORDERED_DISTINCT_RESULTS(false, true, false, true),
    UNORDERED_INDISTINCT_STRUCT(false, false, true, true),
    UNORDERED_INDISTINCT_RESULTS(false, false, false, true),

    ORDERED_DISTINCT_STRUCT_IGNORED(true, true, true, true),
    ORDERED_INDISTINCT_STRUCT_IGNORED(true, false, true, true),
    ORDERED_DISTINCT_STRUCT_UNIGNORED(true, true, true, false),
    ORDERED_INDISTINCT_STRUCT_UNIGNORED(true, false, true, false),
    ORDERED_DISTINCT_RESULTS_IGNORED(true, true, false, true),
    ORDERED_INDISTINCT_RESULTS_IGNORED(true, false, false, true),
    ORDERED_DISTINCT_RESULTS_UNIGNORED(true, true, false, false),
    ORDERED_INDISTINCT_RESULTS_UNIGNORED(true, false, false, false);

    public static DataContainerType determineDataContainerType(boolean getOrdered,
        boolean getDistinct, boolean getStructType, boolean getIgnoreOrderBy)
        throws TypeMismatchException {
      // if not isOrdered, then isIgnoreOrderBy is irrelevant
      return Arrays.stream(DataContainerType.values()).filter(type -> type.isOrdered == getOrdered)
          .filter(type -> type.isDistinct == getDistinct)
          .filter(type -> type.isStructType == getStructType)
          .filter(type -> type.isIgnoreOrderBy == getIgnoreOrderBy || !type.isOrdered).findFirst()
          .orElseThrow(() -> new TypeMismatchException("Logical inconsistency in CompiledSelect"));
    }

    DataContainerType(boolean isOrdered, boolean isDistinct, boolean isStructType,
        boolean isIgnoreOrderBy) {
      this.isOrdered = isOrdered;
      this.isDistinct = isDistinct;
      this.isStructType = isStructType;
      this.isIgnoreOrderBy = isIgnoreOrderBy;
    }

    private final boolean isOrdered, isDistinct, isStructType, isIgnoreOrderBy;
  }

  private SelectResults prepareEmptyResultSet(ExecutionContext context, boolean ignoreOrderBy)
      throws TypeMismatchException, AmbiguousNameException {
    // If no projection attributes or '*' as projection attribute & more than one/RunTimeIterator
    // then create a StructSet.
    // If attribute is null or '*' & only one RuntimeIterator then create a ResultSet.
    // If single attribute is present without alias name, then create ResultSet.
    // Else if more than on attribute or single attribute with alias is present then return a
    // StructSet.
    // Create StructSet which will contain root objects of all iterators in from clause.
    ObjectType elementType = cachedElementTypeForOrderBy != null
        ? cachedElementTypeForOrderBy : prepareResultType(context);
    SelectResults results;

    if (!distinct && count) {
      // Shobhit: If it's a 'COUNT' query and no End processing required Like for 'DISTINCT'
      // we can directly keep count in ResultSet and ResultBag is good enough for that.
      results = new ResultsBag(new ObjectTypeImpl(Integer.class), 1, context.getCachePerfStats());
      countStartQueryResult = 0;
      return results;
    }

    // Potential edge-case: Could this be non-null but empty?
    boolean nullValuesAtStart = orderByAttrs != null && !orderByAttrs.get(0).getCriterion();
    OrderByComparator comparator;
    boolean isOrdered = orderByAttrs != null;

    switch (DataContainerType.determineDataContainerType(isOrdered, distinct,
        elementType.isStructType(), ignoreOrderBy)) {
      case UNORDERED_DISTINCT_STRUCT:
        return new StructSet((StructType) elementType);
      case UNORDERED_DISTINCT_RESULTS:
        return new ResultsSet(elementType);
      case UNORDERED_INDISTINCT_STRUCT:
        return new StructBag((StructType) elementType, context.getCachePerfStats());
      case UNORDERED_INDISTINCT_RESULTS:
        return new ResultsBag(elementType, context.getCachePerfStats());

      case ORDERED_DISTINCT_STRUCT_IGNORED:
        return new LinkedStructSet((StructTypeImpl) elementType);
      case ORDERED_INDISTINCT_STRUCT_IGNORED:
        return new SortedResultsBag(elementType, nullValuesAtStart);
      case ORDERED_DISTINCT_STRUCT_UNIGNORED:
        comparator = hasUnmappedOrderByCols
            ? new OrderByComparatorMapped(orderByAttrs, elementType, context)
            : new OrderByComparator(orderByAttrs, elementType, context);
        return new SortedStructSet(comparator, (StructTypeImpl) elementType);
      case ORDERED_INDISTINCT_STRUCT_UNIGNORED:
        comparator = hasUnmappedOrderByCols
            ? new OrderByComparatorMapped(orderByAttrs, elementType, context)
            : new OrderByComparator(orderByAttrs, elementType, context);
        return new SortedStructBag(comparator, (StructType) elementType, nullValuesAtStart);
      case ORDERED_DISTINCT_RESULTS_IGNORED:
        results = new LinkedResultSet();
        results.setElementType(elementType);
        return results;
      case ORDERED_INDISTINCT_RESULTS_IGNORED:
        results = new SortedResultsBag(nullValuesAtStart);
        results.setElementType(elementType);
        return results;
      case ORDERED_DISTINCT_RESULTS_UNIGNORED:
        comparator = hasUnmappedOrderByCols
            ? new OrderByComparatorMapped(orderByAttrs, elementType, context)
            : new OrderByComparator(orderByAttrs, elementType, context);
        results = new SortedResultSet(comparator);
        results.setElementType(elementType);
        return results;
      case ORDERED_INDISTINCT_RESULTS_UNIGNORED:
        comparator = hasUnmappedOrderByCols
            ? new OrderByComparatorMapped(orderByAttrs, elementType, context)
            : new OrderByComparator(orderByAttrs, elementType, context);
        results = new SortedResultsBag(comparator, nullValuesAtStart);
        results.setElementType(elementType);
        return results;
    }
    throw new TypeMismatchException("Logical inconsistency in CompiledSelect");
  }

  protected ObjectType prepareResultType(ExecutionContext context)
      throws TypeMismatchException, AmbiguousNameException {
    // if no projection attributes or '*'as projection attribute
    // & more than one/RunTimeIterator then create a StrcutSet.
    // If attribute is null or '*' & only one RuntimeIterator then create a
    // ResultSet.
    // If single attribute is present without alias name , then create
    // ResultSet
    // Else if more than on attribute or single attribute with alias is
    // present then return a StrcutSet
    // create StructSet which will contain root objects of all iterators in
    // from clause

    ObjectType elementType = null;
    SelectResults sr = null;

    List currentIterators = context.getCurrentIterators();
    if (projAttrs == null) {
      if (currentIterators.size() == 1) {
        RuntimeIterator iter = (RuntimeIterator) currentIterators.get(0);
        elementType = iter.getElementType();
      } else {
        elementType = createStructTypeForNullProjection(currentIterators, context);
      }
    } else {
      // Create StructType for projection attributes
      int projCount = projAttrs.size();
      String[] fieldNames = new String[projCount];
      ObjectType[] fieldTypes = new ObjectType[projCount];
      boolean createStructSet = false;
      String fldName = null;
      for (int i = 0; i < projCount; i++) {
        Object[] projDef = (Object[]) projAttrs.get(i);
        fldName = (String) projDef[0];
        if (!createStructSet) {
          if (fldName != null || projCount > 1) {
            createStructSet = true;
          }
        }
        fieldNames[i] = (fldName == null && createStructSet)
            ? generateProjectionName((CompiledValue) projDef[1], context) : fldName;
        fieldTypes[i] = getFieldTypeOfProjAttrib(context, (CompiledValue) projDef[1]);
        // fieldTypes[i] = TypeUtils.OBJECT_TYPE;
      }
      if (createStructSet) {
        elementType = new StructTypeImpl(fieldNames, fieldTypes);
      } else {
        elementType = fieldTypes[0];
      }
    }
    return elementType;
  }

  /**
   * This function should be used to create a StructType for those queries which have * as
   * projection attribute (implying null projection attribute) & multiple from clauses
   */
  private StructTypeImpl createStructTypeForNullProjection(List currentIterators,
      ExecutionContext context) {
    int len = currentIterators.size();
    String[] fieldNames = new String[len];
    ObjectType[] fieldTypes = new ObjectType[len];
    String fldName = null;
    for (int i = 0; i < len; i++) {
      RuntimeIterator iter = (RuntimeIterator) currentIterators.get(i);
      // fieldNames[i] = iter.getName();
      if ((fldName = iter.getName()) == null) {
        fldName = generateProjectionName(iter, context);
      }
      fieldNames[i] = fldName;
      fieldTypes[i] = iter.getElementType();
    }
    return new StructTypeImpl(fieldNames, fieldTypes);
  }

  private ObjectType getFieldTypeOfProjAttrib(ExecutionContext context, CompiledValue cv)
      throws TypeMismatchException, AmbiguousNameException {
    // Identify the RuntimeIterator for the compiled value
    ObjectType retType = TypeUtils.OBJECT_TYPE;
    try {
      RuntimeIterator rit = context.findRuntimeIterator(cv);
      List pathOnItr = cv.getPathOnIterator(rit, context);
      if (pathOnItr != null) {
        String[] path = (String[]) pathOnItr.toArray(new String[pathOnItr.size()]);
        ObjectType[] ot = PathUtils.calculateTypesAlongPath(context, rit.getElementType(), path);
        retType = ot[ot.length - 1];
      }
    } catch (NameNotFoundException ignore) {
      // Unable to determine the type Of attribute.It will default to
      // ObjectType
    }
    return retType;
  }

  // resultSet could be a set or a bag (we have set constructor, or there
  // could be a distinct subquery)
  // in future, it would be good to simplify this to always work with a bag
  // (converting all sets to bags) until the end when we enforce distinct
  // The number returned indicates the occurrence of the data in the SelectResults
  // Thus if the SelectResults is of type ResultsSet or StructSet
  // then 1 will indicate that data was added to the results & that was the
  // first occurrence. For this 0 will indicate that the data was not added
  // because it was a duplicate
  // If the SelectResults is an instance ResultsBag or StructsBag , the number will
  // indicate the occurrence. Thus 1 will indicate it being added for first time
  // Currently orderBy is present only for StructSet & ResultSet which are
  // unique object holders. So the occurrence for them can be either 0 or 1 only

  private int applyProjectionAndAddToResultSet(ExecutionContext context, SelectResults resultSet,
      boolean ignoreOrderBy) throws FunctionDomainException, TypeMismatchException,
      NameResolutionException, QueryInvocationTargetException {
    List currrentRuntimeIters = context.getCurrentIterators();

    int occurrence = 0;
    ObjectType elementType = resultSet.getCollectionType().getElementType();
    boolean isStruct = elementType != null && elementType.isStructType();

    // TODO: Optimize this condition in some clean way
    boolean isLinkedStructure =
        resultSet instanceof Ordered && ((Ordered) resultSet).dataPreordered();

    ArrayList evaluatedOrderByClause = null;
    OrderByComparator comparator = null;
    boolean applyOrderBy = false;
    if (orderByAttrs != null && !ignoreOrderBy) {
      // In case PR order-by will get applied on the coordinator node
      // on the cumulative results. Apply the order-by on PR only if
      // limit is specified.
      Integer limitValue = evaluateLimitValue(context, limit);
      if (context.getPartitionedRegion() != null && limitValue < 0) {
        applyOrderBy = false;
      }
      applyOrderBy = true;
    }

    if (orderByAttrs != null && !ignoreOrderBy) {
      comparator = (OrderByComparator) ((Ordered) resultSet).comparator();
    }
    if (projAttrs == null) {
      int len = currrentRuntimeIters.size();
      Object[] values = new Object[len];
      for (int i = 0; i < len; i++) {
        RuntimeIterator iter = (RuntimeIterator) currrentRuntimeIters.get(i);
        values[i] = iter.evaluate(context);
        // For local queries with distinct, deserialize all PdxInstances
        // as we do not have a way to compare Pdx and non Pdx objects in case
        // the cache has a mix of pdx and non pdx objects.
        // We still have to honor the cache level readserialized flag in
        // case of all Pdx objects in cache
        if (distinct && !((DefaultQuery) context.getQuery()).isRemoteQuery()
            && !context.getCache().getPdxReadSerialized() && (values[i] instanceof PdxInstance)) {
          values[i] = ((PdxInstance) values[i]).getObject();
        }
      }

      // Shobhit: Add count value to the counter for this select expression.
      // Don't care about Order By for count(*).
      if (isCount() && !distinct) {
        // Counter is local to CompileSelect and not available in ResultSet
        // until
        // the end of evaluate call to this CompiledSelect object.
        countStartQueryResult++;
        occurrence = 1;
      } else {
        // if order by is present
        if (applyOrderBy) {
          StructImpl structImpl;
          if (distinct) {
            if (isStruct) {
              if (values.length == 1 && values[0] instanceof StructImpl) {
                structImpl = (StructImpl) values[0];
                comparator.addEvaluatedSortCriteria(structImpl.getFieldValues(), context);
                occurrence = resultSet.add(structImpl) ? 1 : 0;
              } else {
                comparator.addEvaluatedSortCriteria(values, context);
                occurrence = ((StructFields) resultSet).addFieldValues(values) ? 1 : 0;
              }
              // TODO:Instead of a normal Map containing which holds
              // StructImpl object
              // use a THashObject with Object[] array hashing stragtegy as we
              // are unnnecessarily
              // creating objects of type Object[]
            } else {
              comparator.addEvaluatedSortCriteria(values[0], context);
              occurrence = resultSet.add(values[0]) ? 1 : 0;
            }
          } else {
            if (isStruct) {
              if (values.length == 1 && values[0] instanceof StructImpl) {
                structImpl = (StructImpl) values[0];
                comparator.addEvaluatedSortCriteria(structImpl.getFieldValues(), context);
                occurrence = ((Bag) resultSet).addAndGetOccurence(structImpl.getFieldValues());
              } else {
                comparator.addEvaluatedSortCriteria(values, context);
                occurrence = ((Bag) resultSet).addAndGetOccurence(values);

              }
            } else {
              comparator.addEvaluatedSortCriteria(values[0], context);
              occurrence = ((Bag) resultSet).addAndGetOccurence(values[0]);
            }
          }
        } else {
          if (isLinkedStructure) {
            if (isStruct) {
              StructImpl structImpl;
              if (values.length == 1 && values[0] instanceof StructImpl) {
                structImpl = (StructImpl) values[0];
              } else {
                structImpl = new StructImpl((StructTypeImpl) elementType, values);
              }
              if (distinct) {
                occurrence = resultSet.add(structImpl) ? 1 : 0;
              } else {
                occurrence = ((Bag) resultSet).addAndGetOccurence(structImpl);
              }
            } else {
              if (distinct) {
                occurrence = resultSet.add(values[0]) ? 1 : 0;
              } else {
                occurrence = ((Bag) resultSet).addAndGetOccurence(values[0]);
              }

            }
          } else {
            if (distinct) {
              if (isStruct) {
                occurrence = ((StructFields) resultSet).addFieldValues(values) ? 1 : 0;
              } else {
                occurrence = resultSet.add(values[0]) ? 1 : 0;
              }
            } else {
              if (isStruct) {
                occurrence = ((Bag) resultSet).addAndGetOccurence(values);
              } else {
                boolean add = true;
                if (context.isCqQueryContext()) {
                  if (values[0] instanceof Region.Entry) {
                    Region.Entry e = (Region.Entry) values[0];
                    if (!e.isDestroyed()) {
                      try {
                        values[0] = new CqEntry(e.getKey(), e.getValue());
                      } catch (EntryDestroyedException ignore) {
                        // Even though isDestory() check is made, the entry could throw
                        // EntryDestroyedException if the value becomes null.
                        add = false;
                      }
                    } else {
                      add = false;
                    }
                  }
                }
                if (add) {
                  occurrence = ((Bag) resultSet).addAndGetOccurence(values[0]);
                }
              }
            }
          }
        }
      }
    } else { // One or more projection attributes
      int projCount = projAttrs.size();
      Object[] values = new Object[projCount];
      for (int i = 0; i < projCount; i++) {
        Object[] projDef = (Object[]) projAttrs.get(i);
        values[i] = ((CompiledValue) projDef[1]).evaluate(context);
        // For local queries with distinct, deserialize all PdxInstances
        // as we do not have a way to compare Pdx and non Pdx objects in case
        // the cache has a mix of pdx and non pdx objects.
        // We still have to honor the cache level readserialized flag in
        // case of all Pdx objects in cache.
        // Also always convert PdxString to String before adding to resultset
        // for remote queries
        if (!((DefaultQuery) context.getQuery()).isRemoteQuery()) {
          if (distinct && values[i] instanceof PdxInstance
              && !context.getCache().getPdxReadSerialized()) {
            values[i] = ((PdxInstance) values[i]).getObject();
          } else if (values[i] instanceof PdxString) {
            values[i] = values[i].toString();
          }
        } else if (values[i] instanceof PdxString) {
          values[i] = values[i].toString();
        }
      }
      // if order by is present
      if (applyOrderBy) {
        if (distinct) {
          if (isStruct) {
            comparator.addEvaluatedSortCriteria(values, context);
            // Occurrence field is used to identify the corrcet number of
            // iterations
            // required to implement the limit based on the presence or absence
            // of distinct clause
            occurrence = ((StructFields) resultSet).addFieldValues(values) ? 1 : 0;
          } else {
            comparator.addEvaluatedSortCriteria(values[0], context);
            occurrence = resultSet.add(values[0]) ? 1 : 0;
          }
        } else {
          if (isStruct) {
            comparator.addEvaluatedSortCriteria(values, context);
            occurrence = ((Bag) resultSet).addAndGetOccurence(values);
          } else {
            comparator.addEvaluatedSortCriteria(values[0], context);
            occurrence = ((Bag) resultSet).addAndGetOccurence(values[0]);
          }
        }
      } else {
        if (isLinkedStructure) {
          if (isStruct) {
            StructImpl structImpl = new StructImpl((StructTypeImpl) elementType, values);
            if (distinct) {
              occurrence = resultSet.add(structImpl) ? 1 : 0;
            } else {
              occurrence = ((Bag) resultSet).addAndGetOccurence(structImpl);
            }

          } else {
            if (distinct) {
              occurrence = resultSet.add(values[0]) ? 1 : 0;
            } else {
              occurrence = ((Bag) resultSet).addAndGetOccurence(values[0]);
            }
          }
        } else {
          if (distinct) {
            if (isStruct) {
              occurrence = ((StructFields) resultSet).addFieldValues(values) ? 1 : 0;
            } else {
              occurrence = resultSet.add(values[0]) ? 1 : 0;
            }
          } else {
            if (isStruct) {
              occurrence = ((Bag) resultSet).addAndGetOccurence(values);
            } else {
              occurrence = ((Bag) resultSet).addAndGetOccurence(values[0]);
            }
          }
        }
      }
    }
    return occurrence;
  }

  private String generateProjectionName(CompiledValue projExpr, ExecutionContext context) {
    String name = null;
    if (projExpr instanceof RuntimeIterator) {
      RuntimeIterator rIter = (RuntimeIterator) projExpr;
      name = rIter.getDefinition();
      int index = name.lastIndexOf('.');
      if (index > 0) {
        name = name.substring(index + 1);
      } else if (name.charAt(0) == '/') {
        index = name.lastIndexOf('/');
        name = name.substring(index + 1);
      } else {
        name = rIter.getInternalId();
      }
    } else {
      int type = projExpr.getType();
      if (type == PATH) {
        name = ((CompiledPath) projExpr).getTailID();
      } else if (type == Identifier) {
        name = ((CompiledID) projExpr).getId();
      } else if (type == LITERAL) {
        name = (((CompiledLiteral) projExpr)._obj).toString();
      } else if (type == METHOD_INV) {
        name = ((CompiledOperation) projExpr).getMethodName();
      } else {
        name = "field$" + context.nextFieldNum();
        // name = projExpr.toString();
      }
    }
    return name;
  }

  /**
   * Optimized evaluate for CQ execution.
   */
  public boolean evaluateCq(ExecutionContext context) throws FunctionDomainException,
      TypeMismatchException, NameResolutionException, QueryInvocationTargetException {
    if (whereClause == null) {
      return true;
    }

    context.newScope((Integer) context.cacheGet(scopeID));
    context.pushExecCache((Integer) context.cacheGet(scopeID));
    try {
      CompiledIteratorDef iterDef = (CompiledIteratorDef) iterators.get(0);
      RuntimeIterator rIter = iterDef.getRuntimeIterator(context);
      context.bindIterator(rIter);


      Collection coll;
      {
        Object evalResult = iterDef.getCollectionExpr().evaluate(context);
        if (evalResult == null || evalResult == QueryService.UNDEFINED) {
          return false;
        }
        coll = (Collection) evalResult;
      }
      if (coll.isEmpty()) {
        return false;
      }

      if (whereClause.isDependentOnCurrentScope(context)) {
        Iterator cIter = coll.iterator();
        Object currObj = cIter.next();
        rIter.setCurrent(currObj);
      }
      Object b = whereClause.evaluate(context);
      if (b == null) {
        return false;
      } else if (b == QueryService.UNDEFINED) {
        // add UNDEFINED to results only for NOT EQUALS queries
        if (whereClause.getType() == COMPARISON) {
          int operator = ((Filter) whereClause).getOperator();
          return operator == TOK_NE || operator == TOK_NE_ALT;
        } else {
          return false;
        }
      } else {
        return (Boolean) b;
      }
    } finally {
      context.popExecCache();
      context.popScope();
    }
  }

  /*
   * A special evaluation of limit for when limit needs to be evaluated before an execution context
   * is created.
   *
   * It assumes the limit is either a CompiledBindArgument or a CompiledLiteral
   *
   *
   *
   *
   *
   *
   */
  private Integer evaluateLimitValue(Object[] bindArguments) throws FunctionDomainException,
      TypeMismatchException, NameResolutionException, QueryInvocationTargetException {
    Integer limitValue = -1;
    if (limit != null) {
      if (limit instanceof CompiledBindArgument) {
        limitValue = (Integer) ((CompiledBindArgument) limit).evaluate(bindArguments);
      } else {
        // Assume limit is a compiled literal which does not need a context
        limitValue = (Integer) limit.evaluate(null);
      }
    }
    return limitValue;
  }

  protected static Integer evaluateLimitValue(ExecutionContext context, CompiledValue limit)
      throws FunctionDomainException, TypeMismatchException, NameResolutionException,
      QueryInvocationTargetException {
    Integer limitValue = -1;
    if (limit != null) {
      limitValue = (Integer) limit.evaluate(context);
      if (limitValue == null) {
        // This is incase an object array was passed in but no param was set for the limit
        limitValue = -1;
      }
    }
    return limitValue;
  }

  private static class NullIteratorException extends Exception {

  }

}
