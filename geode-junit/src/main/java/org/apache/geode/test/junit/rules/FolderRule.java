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
package org.apache.geode.test.junit.rules;

import static org.apache.geode.test.awaitility.GeodeAwaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class FolderRule implements TestRule {

  public enum Policy {
    DELETE_ON_PASS,
    KEEP_ALWAYS
  }

  private final Policy policy;

  private Folder folder;

  public FolderRule() {
    this(Policy.DELETE_ON_PASS);
  }

  public FolderRule(Policy policy) {
    this.policy = policy;
  }

  public Folder getFolder() {
    return folder;
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        folder = FolderFactory.create(description);
        base.evaluate();
        if (policy == Policy.DELETE_ON_PASS) {
          await()
              .ignoreExceptions()
              .untilAsserted(() -> {
                Throwable thrown = catchThrowable(() -> folder.delete());
                assertThat(thrown).isNull();
              });
        }
      }
    };
  }
}
