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

package org.apache.geode;

/**
 * An <code>InternalGemFireException</code> is thrown when a low level, internal, operation fails
 * due to no fault of the user. The message often contains an operating system error code.
 *
 *
 */
public class InternalGemFireException extends GemFireException {
  private static final long serialVersionUID = -6912843691545178619L;

  ////////////////////// Constructors //////////////////////

  public InternalGemFireException() {
    super();
  }

  public InternalGemFireException(Throwable cause) {
    super(cause);
  }

  /**
   * Creates a new <code>InternalGemFireException</code>.
   *
   * @param message the detail message
   */
  public InternalGemFireException(String message) {
    super(message);
  }

  /**
   * Creates a new <code>InternalGemFireException</code> that was caused by a given exception
   *
   * @param message the detail message
   * @param thr the cause
   */
  public InternalGemFireException(String message, Throwable thr) {
    super(message, thr);
  }
}
