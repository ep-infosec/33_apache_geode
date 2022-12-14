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
package org.apache.geode.management.internal.configuration.functions;

import java.util.Set;

import org.apache.logging.log4j.Logger;

import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.distributed.internal.InternalConfigurationPersistenceService;
import org.apache.geode.distributed.internal.InternalLocator;
import org.apache.geode.internal.cache.execute.InternalFunction;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.management.internal.configuration.messages.ConfigurationResponse;

public class GetClusterConfigurationFunction implements InternalFunction {
  private static final Logger logger = LogService.getLogger();
  private static final long serialVersionUID = 6332908511113951823L;

  @Override
  public void execute(FunctionContext context) {
    Set<String> groups = (Set<String>) context.getArguments();
    InternalLocator internalLocator = getInternalLocator();
    logger.info("Received request for configuration: {}", groups);

    // Return exception to the caller so startup fails fast.
    if (!internalLocator.isSharedConfigurationEnabled()) {
      String errorMessage = "The cluster configuration service is not enabled on this member.";
      logger.warn(errorMessage);
      context.getResultSender().lastResult(new IllegalStateException(errorMessage));
      return;
    }

    // Shared configuration enabled.
    if (internalLocator.isSharedConfigurationRunning()) {
      // Cluster configuration is up and running already.
      InternalConfigurationPersistenceService clusterConfigurationService =
          internalLocator.getConfigurationPersistenceService();

      try {
        ConfigurationResponse response =
            clusterConfigurationService.createConfigurationResponse(groups);
        context.getResultSender().lastResult(response);
      } catch (Exception exception) {
        logger.warn("Unable to retrieve the cluster configuration", exception);
        context.getResultSender().lastResult(exception);
      }
    } else {
      // Cluster configuration service is starting up. Return null so callers can decide whether
      // to fail fast, or wait and retry later.
      context.getResultSender().lastResult(null);
    }
  }

  InternalLocator getInternalLocator() {
    return InternalLocator.getLocator();
  }
}
