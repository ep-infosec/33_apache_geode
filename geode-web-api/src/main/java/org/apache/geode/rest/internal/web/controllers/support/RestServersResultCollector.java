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
package org.apache.geode.rest.internal.web.controllers.support;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.distributed.DistributedMember;

public class RestServersResultCollector<String, Object> implements ResultCollector<String, Object> {

  private final ArrayList<String> resultList = new ArrayList<>();

  @Override
  public void addResult(DistributedMember memberID, String result) {
    if (result != null && result.toString().length() > 0) {
      resultList.add(result);
    }
  }

  @Override
  public void endResults() {}

  @SuppressWarnings("unchecked")
  @Override
  public Object getResult() throws FunctionException {
    return (Object) resultList;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object getResult(long timeout, TimeUnit unit) throws FunctionException {
    return (Object) resultList;
  }

  @Override
  public void clearResults() {
    resultList.clear();
  }

}
