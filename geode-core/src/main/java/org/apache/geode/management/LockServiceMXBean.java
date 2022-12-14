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
package org.apache.geode.management;

import java.util.Map;

import org.apache.geode.distributed.internal.locks.DLockService;
import org.apache.geode.management.internal.security.ResourceOperation;
import org.apache.geode.security.ResourcePermission.Operation;
import org.apache.geode.security.ResourcePermission.Resource;

/**
 * MBean that provides access to information and management functionality for a
 * {@link DLockService}. Since any number of DLockService objects can be created by a member there
 * may be 0 or more instances of this MBean available.
 *
 * @since GemFire 7.0
 *
 */
@ResourceOperation(resource = Resource.CLUSTER, operation = Operation.READ)
public interface LockServiceMXBean {

  /**
   * Returns the name of the lock service.
   *
   * @return the name of the lock service
   */
  String getName();

  /**
   * Returns whether this is a distributed LockService.
   *
   * @return True is this is a distributed LockService, false otherwise.
   */
  boolean isDistributed();

  /**
   * Returns the number of members using this LockService.
   *
   * @return the number of members using this LockService
   */
  int getMemberCount();

  /**
   * Returns the name of the member which grants the lock.
   *
   * @return the name of the member which grants the lock
   */
  String fetchGrantorMember();

  /**
   * Returns a list of names of the members using this LockService.
   *
   * @return an array of names of the members using this LockService
   */
  String[] getMemberNames();

  /**
   * Returns whether this member is the granter.
   *
   * @return True if this member is the granter, false otherwise.
   */
  boolean isLockGrantor();


  /**
   * Requests that this member become the granter.
   */
  @ResourceOperation(resource = Resource.CLUSTER, operation = Operation.MANAGE)
  void becomeLockGrantor();

  /**
   * Returns a map of the names of the objects being locked on and the names of the threads holding
   * the locks.
   *
   * @return a map of the names of the objects being locked on and the names of the threads holding
   *         the locks
   */
  Map<String, String> listThreadsHoldingLock();

  /**
   * Returns a list of names of the locks held by this member's threads.
   *
   * @return an array of names of the locks held by this member's threads
   */
  String[] listHeldLocks();

}
