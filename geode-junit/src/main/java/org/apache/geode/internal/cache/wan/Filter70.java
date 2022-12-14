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
package org.apache.geode.internal.cache.wan;

import java.io.Serializable;
import java.util.Objects;

import org.apache.geode.cache.wan.GatewayEventFilter;
import org.apache.geode.cache.wan.GatewayQueueEvent;

public class Filter70 implements GatewayEventFilter, Serializable {
  String Id = "Filter70";
  public int eventEnqued = 0;

  public int eventTransmitted = 0;

  @Override
  public boolean beforeEnqueue(GatewayQueueEvent event) {
    return (Long) event.getKey() < 0 || (Long) event.getKey() >= 500;
  }

  @Override
  public boolean beforeTransmit(GatewayQueueEvent event) {
    eventEnqued++;
    return true;
  }

  @Override
  public void close() {

  }

  @Override
  public String toString() {
    return Id;
  }

  @Override
  public void afterAcknowledgement(GatewayQueueEvent event) {}

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Filter70)) {
      return false;
    }
    Filter70 filter = (Filter70) obj;
    return Id.equals(filter.Id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(Id);
  }
}
