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

import java.io.Serializable;
import java.util.Objects;

import org.apache.geode.cache.EntryOperation;
import org.apache.geode.cache.PartitionResolver;
import org.apache.geode.internal.cache.execute.data.CustId;
import org.apache.geode.internal.cache.execute.data.OrderId;
import org.apache.geode.internal.cache.execute.data.ShipmentId;

public class CustomerIDPartitionResolver implements PartitionResolver<Object, Object> {

  private String id;

  public CustomerIDPartitionResolver() {}

  public CustomerIDPartitionResolver(String resolverID) {
    id = resolverID;
  }

  @Override
  public String getName() {
    return id;
  }

  @Override
  public Serializable getRoutingObject(EntryOperation<Object, Object> opDetails) {

    Serializable routingbject = null;

    if (opDetails.getKey() instanceof ShipmentId) {
      ShipmentId shipmentId = (ShipmentId) opDetails.getKey();
      routingbject = shipmentId.getOrderId().getCustId();
    }
    if (opDetails.getKey() instanceof OrderId) {
      OrderId orderId = (OrderId) opDetails.getKey();
      routingbject = orderId.getCustId();
    } else if (opDetails.getKey() instanceof CustId) {
      CustId custId = (CustId) opDetails.getKey();
      routingbject = custId.getCustId();
    }
    return routingbject;
  }

  @Override
  public void close() {}

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof CustomerIDPartitionResolver)) {
      return false;
    }

    CustomerIDPartitionResolver otherCustomerIDPartitionResolver = (CustomerIDPartitionResolver) o;
    return otherCustomerIDPartitionResolver.id.equals(id);

  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
