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

package org.apache.geode.management.internal.cli.util;

import org.apache.geode.GemFireException;

/**
 * The VisualVmNotFoundException class is a GemFireException (RuntimeException) indicating that the
 * JDK jvisualvm tool could not be found on the system.
 * <p>
 *
 * @see org.apache.geode.GemFireException
 * @since GemFire 7.0
 */
@SuppressWarnings("unused")
public class VisualVmNotFoundException extends GemFireException {
  private static final long serialVersionUID = -8491645604829510102L;

  public VisualVmNotFoundException() {}

  public VisualVmNotFoundException(final String message) {
    super(message);
  }

  public VisualVmNotFoundException(final Throwable cause) {
    super(cause);
  }

  public VisualVmNotFoundException(final String message, final Throwable cause) {
    super(message, cause);
  }

}
