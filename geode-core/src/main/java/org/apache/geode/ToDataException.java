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
 * A <code>ToDataException</code> is thrown during serialization if {@link DataSerializable#toData}
 * throws an exception or if {@link DataSerializer#toData} is called and returns false.
 *
 * @since GemFire 6.5
 */
public class ToDataException extends SerializationException {
  private static final long serialVersionUID = -2329606027453879918L;

  /**
   * Creates a new <code>ToDataException</code> with the given message
   *
   * @param message the detail message
   */
  public ToDataException(String message) {
    super(message);
  }

  /**
   * Creates a new <code>ToDataException</code> with the given message and cause.
   *
   * @param message the detail message
   * @param cause the cause
   */
  public ToDataException(String message, Throwable cause) {
    super(message, cause);
  }
}
