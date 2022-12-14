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
package org.apache.geode.cache.util;

/**
 * GatewayConflictHelper is used by an GatewayConflictResolver to decide what to do with an event
 * received from another distributed system that is going to overwrite the current cache state.
 *
 * @since GemFire 7.0
 */
public interface GatewayConflictHelper {
  /** disallow the event */
  void disallowEvent();

  /**
   * Modify the value stored in the cache
   *
   * @param value the new value to be stored in the cache
   */
  void changeEventValue(Object value);
}
