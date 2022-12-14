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

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Objects;

import org.apache.geode.cache.wan.GatewayTransportFilter;

public class MyGatewayTransportFilter1 implements GatewayTransportFilter, Serializable {

  String Id = "MyGatewayTransportFilter1";

  @Override
  public InputStream getInputStream(InputStream stream) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public OutputStream getOutputStream(OutputStream stream) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void close() {
    // TODO Auto-generated method stub

  }

  @Override
  public String toString() {
    return Id;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof MyGatewayTransportFilter1)) {
      return false;
    }
    MyGatewayTransportFilter1 filter = (MyGatewayTransportFilter1) obj;
    return Id.equals(filter.Id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(Id);
  }

}
