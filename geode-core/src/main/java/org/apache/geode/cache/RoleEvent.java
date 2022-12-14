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

package org.apache.geode.cache;

import java.util.Set;

/**
 * Contains information about an event affecting a region reliability, including its identity and
 * the circumstances of the event. This is passed in to {@link RegionRoleListener}.
 *
 * @deprecated this feature is scheduled to be removed
 * @see RegionRoleListener
 */
@Deprecated
public interface RoleEvent<K, V> extends RegionEvent<K, V> {

  /**
   * Returns the required roles that were lost or gained because of this event.
   *
   * @return the required roles that were lost or gained because of this event
   */
  Set<String> getRequiredRoles();

}
