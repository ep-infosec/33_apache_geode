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

import org.apache.geode.cache.RegionRoleListener;
import org.apache.geode.cache.RoleEvent;

/**
 * Utility class that implements all methods in <code>RegionRoleListener</code> with empty
 * implementations. Applications can subclass this class and only override the methods for the
 * events of interest.
 *
 * @deprecated this feature is scheduled to be removed
 */
@Deprecated
public abstract class RegionRoleListenerAdapter<K, V> extends RegionMembershipListenerAdapter<K, V>
    implements RegionRoleListener<K, V> {

  @Override
  public void afterRoleGain(RoleEvent<K, V> event) {}

  @Override
  public void afterRoleLoss(RoleEvent<K, V> event) {}

}
