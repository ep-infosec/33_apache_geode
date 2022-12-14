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
package org.apache.geode.cache.execute;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionService;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.internal.cache.execute.FunctionExecutionService;
import org.apache.geode.internal.cache.execute.InternalFunctionExecutionServiceImpl;

/**
 * Provides the entry point into execution of user defined {@linkplain Function}s.
 * <p>
 * Function execution provides a means to route application behaviour to {@linkplain Region data} or
 * more generically to peers in a {@link DistributedSystem} or servers in a {@link Pool}.
 * <p>
 *
 * @since GemFire 6.0
 */
public class FunctionService {

  @MakeNotStatic("The FunctionService requires a cache. We need to have an instance per cache.")
  private static final FunctionService INSTANCE =
      new FunctionService(new InternalFunctionExecutionServiceImpl());

  private final FunctionExecutionService functionExecutionService;

  /*
   * Protected visibility to allow InternalFunctionService to extend FunctionService.
   */
  protected FunctionService(FunctionExecutionService functionExecutionService) {
    this.functionExecutionService = functionExecutionService;
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data dependent function on
   * the specified Region.<br>
   * When invoked from a GemFire client, the method returns an Execution instance that sends a
   * message to one of the connected servers as specified by the {@link Pool} for the region. <br>
   * Depending on the filters setup on the {@link Execution}, the function is executed on all
   * GemFire members that define the data region, or a subset of members.
   * {@link Execution#withFilter(Set)}).
   *
   * For DistributedRegions with DataPolicy.NORMAL, it throws UnsupportedOperationException. For
   * DistributedRegions with DataPolicy.EMPTY, execute the function on any random member which has
   * DataPolicy.REPLICATE <br>
   * . For DistributedRegions with DataPolicy.REPLICATE, execute the function locally. For Regions
   * with DataPolicy.PARTITION, it executes on members where the data resides as specified by the
   * filter.
   *
   * @param region the {@link Region} on which the returned {@link Execution} will execute functions
   * @return an {@link Execution} object that can be used to execute a data dependent function on
   *         the specified {@link Region}
   * @throws FunctionException if the region passed in is null
   * @since GemFire 6.0
   */
  public static Execution onRegion(Region region) {
    return getFunctionExecutionService().onRegion(region);
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data independent function on
   * a server in the provided {@link Pool}.
   * <p>
   * If the server goes down while dispatching or executing the function, an Exception will be
   * thrown.
   *
   * @param pool from which to chose a server for execution
   * @return an {@link Execution} object that can be used to execute a data independent function on
   *         a server in the provided {@link Pool}
   * @throws FunctionException if Pool instance passed in is null
   * @since GemFire 6.0
   */
  public static Execution onServer(Pool pool) {
    return getFunctionExecutionService().onServer(pool);
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data independent function on
   * all the servers in the provided {@link Pool}. If one of the servers goes down while dispatching
   * or executing the function on the server, an Exception will be thrown.
   *
   * @param pool the set of servers to execute the function
   * @return an {@link Execution} object that can be used to execute a data independent function on
   *         all the servers in the provided {@link Pool}
   * @throws FunctionException if Pool instance passed in is null
   * @since GemFire 6.0
   */
  public static Execution onServers(Pool pool) {
    return getFunctionExecutionService().onServers(pool);
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data independent function on
   * a server that the given cache is connected to.
   * <p>
   * If the server goes down while dispatching or executing the function, an Exception will be
   * thrown.
   *
   * @param regionService obtained from {@link ClientCacheFactory#create} or
   *        {@link ClientCache#createAuthenticatedView(Properties)}.
   * @return an {@link Execution} object that can be used to execute a data independent function on
   *         a server that the given cache is connected to
   * @throws FunctionException if cache is null, is not on a client, or it does not have a default
   *         pool
   * @since GemFire 6.5
   */
  public static Execution onServer(RegionService regionService) {
    return getFunctionExecutionService().onServer(regionService);
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data independent function on
   * all the servers that the given cache is connected to. If one of the servers goes down while
   * dispatching or executing the function on the server, an Exception will be thrown.
   *
   * @param regionService obtained from {@link ClientCacheFactory#create} or
   *        {@link ClientCache#createAuthenticatedView(Properties)}.
   * @return an {@link Execution} object that can be used to execute a data independent function on
   *         all the servers that the given cache is connected to
   * @throws FunctionException if cache is null, is not on a client, or it does not have a default
   *         pool
   * @since GemFire 6.5
   */
  public static Execution onServers(RegionService regionService) {
    return getFunctionExecutionService().onServers(regionService);
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data independent function on
   * a {@link DistributedMember}. If the member is not found, executing the function will throw an
   * Exception. If the member goes down while dispatching or executing the function on the member,
   * an Exception will be thrown.
   *
   * @param distributedMember defines a member in the distributed system
   * @return an {@link Execution} object that can be used to execute a data independent function on
   *         a {@link DistributedMember}
   * @throws FunctionException if distributedMember is null
   * @since GemFire 7.0
   */
  public static Execution onMember(DistributedMember distributedMember) {
    return getFunctionExecutionService().onMember(distributedMember);
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data independent function on
   * all peer members. If the optional groups parameter is provided, function is executed on all
   * members that belong to the provided groups.
   * <p>
   * If one of the members goes down while dispatching or executing the function on the member, an
   * Exception will be thrown.
   *
   * @param groups optional list of GemFire configuration property "groups" (see
   *        <a href="../../distributed/DistributedSystem.html#groups"> <code>groups</code></a>) on
   *        which to execute the function. Function will be executed on all members of each group
   * @return an {@link Execution} object that can be used to execute a data independent function on
   *         all peer members
   *
   * @throws FunctionException if no members are found belonging to the provided groups
   * @since GemFire 7.0
   */
  public static Execution onMembers(String... groups) {
    return getFunctionExecutionService().onMembers(groups);
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data independent function on
   * the set of {@link DistributedMember}s. If one of the members goes down while dispatching or
   * executing the function, an Exception will be thrown.
   *
   * @param distributedMembers set of distributed members on which {@link Function} to be executed
   * @return an {@link Execution} object that can be used to execute a data independent function on
   *         the set of {@link DistributedMember}s provided
   * @throws FunctionException if distributedMembers is null
   * @since GemFire 7.0
   */
  public static Execution onMembers(Set<DistributedMember> distributedMembers) {
    return getFunctionExecutionService().onMembers(distributedMembers);
  }

  /**
   * Returns an {@link Execution} object that can be used to execute a data independent function on
   * one member of each group provided.
   *
   * @param groups list of GemFire configuration property "groups" (see
   *        <a href="../../distributed/DistributedSystem.html#groups"> <code>groups</code></a>) on
   *        which to execute the function. Function will be executed on one member of each group
   * @return an {@link Execution} object that can be used to execute a data independent function on
   *         one member of each group provided
   *
   * @throws FunctionException if no members are found belonging to the provided groups
   * @since GemFire 7.0
   */
  public static Execution onMember(String... groups) {
    return getFunctionExecutionService().onMember(groups);
  }

  /**
   * Returns the {@link Function} defined by the functionId, returns null if no function is found
   * for the specified functionId
   *
   * @param functionId a functionId
   * @return the {@link Function} defined by the functionId or null if no function is found for the
   *         specified functionId
   *
   * @throws FunctionException if functionID passed is null
   * @since GemFire 6.0
   */
  public static Function getFunction(String functionId) {
    return getFunctionExecutionService().getFunction(functionId);
  }

  /**
   * Registers the given {@link Function} with the {@link FunctionService} using
   * {@link Function#getId()}.
   * <p>
   * Registering a function allows execution of the function using
   * {@link Execution#execute(String)}. Every member that could execute a function using its
   * {@link Function#getId()} should register the function.
   * <p>
   *
   * @param function the {@link Function} to register
   *
   * @throws FunctionException if function instance passed is null or Function.getId() returns null
   * @since GemFire 6.0
   */
  public static void registerFunction(Function function) {
    getFunctionExecutionService().registerFunction(function);
  }

  /**
   * Unregisters the given {@link Function} with the {@link FunctionService} using
   * {@link Function#getId()}.
   * <p>
   *
   * @param functionId the ID of the function
   *
   * @throws FunctionException if function instance passed is null or Function.getId() returns null
   * @since GemFire 6.0
   */
  public static void unregisterFunction(String functionId) {
    getFunctionExecutionService().unregisterFunction(functionId);
  }

  /**
   * Returns true if the function is registered to FunctionService
   *
   * @param functionId the ID of the function
   * @return whether the function is registered to FunctionService
   *
   * @throws FunctionException if function instance passed is null or Function.getId() returns null
   * @since GemFire 6.0
   */
  public static boolean isRegistered(String functionId) {
    return getFunctionExecutionService().isRegistered(functionId);
  }

  /**
   * Returns all locally registered functions
   *
   * @return A view of registered functions as a Map of {@link Function#getId()} to {@link Function}
   * @since GemFire 6.0
   */
  public static Map<String, Function> getRegisteredFunctions() {
    return getFunctionExecutionService().getRegisteredFunctions();
  }

  private static FunctionExecutionService getFunctionExecutionService() {
    return INSTANCE.functionExecutionService;
  }
}
