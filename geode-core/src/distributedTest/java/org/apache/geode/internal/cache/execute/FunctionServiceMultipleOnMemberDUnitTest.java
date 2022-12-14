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
package org.apache.geode.internal.cache.execute;

import java.util.HashSet;
import java.util.Set;

import org.junit.Before;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.execute.Execution;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.junit.categories.FunctionServiceTest;

/**
 * Test of the behavior of a custom ResultCollector when handling exceptions
 */
@Category({FunctionServiceTest.class})
public class FunctionServiceMultipleOnMemberDUnitTest extends FunctionServiceBase {

  private final Set<DistributedMember> members = new HashSet<>();

  @Before
  public void createDistributedSystems() {
    getSystem();
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    members.add(vm0.invoke(() -> getCache().getDistributedSystem().getDistributedMember()));
    members.add(vm1.invoke(() -> getCache().getDistributedSystem().getDistributedMember()));
  }

  @Override
  public Execution getExecution() {
    return FunctionService.onMembers(members);
  }

  @Override
  public int numberOfExecutions() {
    return 2;
  }
}
