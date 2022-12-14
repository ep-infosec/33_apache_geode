/*
 *
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
 *
 */

package org.apache.geode.management.internal.cli.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import org.apache.geode.InternalGemFireError;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.ResultSender;
import org.apache.geode.management.cli.CliFunction;
import org.apache.geode.management.internal.functions.CliFunctionResult;

public class CliFunctionTest {

  private FunctionContext<Object[]> context;
  private ResultSender<Object> resultSender;

  @SuppressWarnings("unchecked")
  @Before
  public void before() {
    context = mock(FunctionContext.class);
    resultSender = mock(ResultSender.class);
    when(context.getResultSender()).thenReturn(resultSender);
  }

  @Test
  public void executeShouldSendCliFunctionResultIfErrorHappens() throws Exception {
    CliFunction<Object[]> function = new CliFunction<Object[]>() {
      @Override
      public CliFunctionResult executeFunction(FunctionContext<Object[]> context) {
        throw new InternalGemFireError("test");
      }
    };
    function.execute(context);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(resultSender).lastResult(captor.capture());
    assertThat(captor.getValue()).isInstanceOf(CliFunctionResult.class);
  }
}
