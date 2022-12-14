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
package org.apache.geode.management;


/**
 * Composite data type used to distribute network related metrics for a member.
 *
 * @since GemFire 7.0
 *
 */
public class NetworkMetrics {
  private float bytesReceivedRate;
  private float bytesSentRate;

  /**
   * Returns the average number of bytes per second received.
   *
   * @return the average number of bytes per second received
   */
  public float getBytesReceivedRate() {
    return bytesReceivedRate;
  }

  /**
   * Returns the average number of bytes per second sent.
   *
   * @return the average number of bytes per second sent
   */
  public float getBytesSentRate() {
    return bytesSentRate;
  }

  /**
   * Sets the average number of bytes per second received.
   *
   * @param bytesReceivedRate the average number of bytes per second received
   */
  public void setBytesReceivedRate(float bytesReceivedRate) {
    this.bytesReceivedRate = bytesReceivedRate;
  }

  /**
   * Sets the average number of bytes per second sent.
   *
   * @param bytesSentRate the average number of bytes per second sent
   */
  public void setBytesSentRate(float bytesSentRate) {
    this.bytesSentRate = bytesSentRate;
  }
}
