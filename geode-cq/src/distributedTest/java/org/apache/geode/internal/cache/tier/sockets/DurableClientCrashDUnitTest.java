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
package org.apache.geode.internal.cache.tier.sockets;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.test.dunit.SerializableRunnableIF;
import org.apache.geode.test.junit.categories.ClientSubscriptionTest;

/**
 * Class <code>DurableClientCrashDUnitTest</code> tests durable client functionality when clients
 * crash.
 *
 * @since GemFire 5.2
 */
@Category({ClientSubscriptionTest.class})
public class DurableClientCrashDUnitTest extends DurableClientTestBase {

  @Override
  protected final void postSetUpDurableClientTestBase() {
    durableClientVM.invoke(() -> CacheServerTestUtil.setClientCrash(Boolean.TRUE));
  }

  @Override
  protected void preTearDownDurableClientTestBase() {
    durableClientVM.invoke(() -> CacheServerTestUtil.setClientCrash(Boolean.FALSE));
  }

  /**
   * Test that starting, stopping then restarting a durable client is correctly processed by the
   * server.
   */
  @Test
  public void testDurableClient() {

    startupDurableClientAndServer(VERY_LONG_DURABLE_TIMEOUT_SECONDS);

    verifyDurableClientPresent(VERY_LONG_DURABLE_TIMEOUT_SECONDS, durableClientId, server1VM);

    // Stop the durable client
    disconnectDurableClient(true);

    // Re-start the durable client
    restartDurableClient(VERY_LONG_DURABLE_TIMEOUT_SECONDS, Boolean.TRUE);

    // Verify durable client on server
    verifyDurableClientPresent(VERY_LONG_DURABLE_TIMEOUT_SECONDS, durableClientId, server1VM);

    // Stop the durable client
    closeDurableClient();

    // Stop the server
    server1VM.invoke((SerializableRunnableIF) CacheServerTestUtil::closeCache);


  }
}
