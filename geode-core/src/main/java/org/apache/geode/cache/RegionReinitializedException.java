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
package org.apache.geode.cache;

/**
 * Indicates that the region has been reinitialized. Further operations on the region object are not
 * allowed using this region reference. A new reference must be acquired from the Cache the region's
 * parent region.
 *
 *
 * @since GemFire 4.0
 */
public class RegionReinitializedException extends RegionDestroyedException {
  private static final long serialVersionUID = 8532904304288670752L;

  /**
   * Constructs a <code>RegionReinitializedException</code> with a message.
   *
   * @param msg the String message
   * @param regionFullPath the path of the region that encountered the exception
   */
  public RegionReinitializedException(String msg, String regionFullPath) {
    super(msg, regionFullPath);
  }

  /**
   * Constructs a <code>RegionDestroyedException</code> with a message and a cause.
   *
   * @param s the String message
   * @param regionFullPath the path of the region that encountered the exception
   * @param ex the Throwable cause
   */
  public RegionReinitializedException(String s, String regionFullPath, Throwable ex) {
    super(s, regionFullPath, ex);
  }
}
