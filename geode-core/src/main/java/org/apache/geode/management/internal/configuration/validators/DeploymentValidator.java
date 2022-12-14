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

package org.apache.geode.management.internal.configuration.validators;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.NotImplementedException;

import org.apache.geode.management.configuration.Deployment;
import org.apache.geode.management.internal.CacheElementOperation;
import org.apache.geode.management.internal.utils.JarFileUtils;

public class DeploymentValidator implements ConfigurationValidator<Deployment> {
  @Override
  public void validate(CacheElementOperation operation, Deployment config)
      throws IllegalArgumentException {
    switch (operation) {
      case UPDATE:
        throw new NotImplementedException("Not implemented");
      case CREATE:
        validateCreate(config);
        break;
      case DELETE:
      default:
    }
  }

  private void validateCreate(Deployment config) {
    // verify jar content
    List<String> invalidFileNames = new ArrayList<>();
    File file = config.getFile();
    if (!JarFileUtils.hasValidJarContent(file)) {
      invalidFileNames.add(file.getName());
    }

    if (!invalidFileNames.isEmpty()) {
      throw new IllegalArgumentException(
          "File does not contain valid JAR content: " + config.getFileName());
    }
  }
}
