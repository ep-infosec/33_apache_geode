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
package org.apache.geode.internal.cache.control;

import org.apache.logging.log4j.Logger;

import org.apache.geode.logging.internal.log4j.api.LogService;

public class TestMemoryThresholdListener implements ResourceListener<MemoryEvent> {
  private static final Logger logger = LogService.getLogger();
  private int normalCalls = 0;
  private int criticalThresholdCalls = 0;
  private int evictionThresholdCalls = 0;
  private int evictionDisabledCalls = 0;
  private int criticalDisabledCalls = 0;
  private long bytesFromThreshold = 0;
  private int currentHeapPercentage = 0;
  private int allCalls = 0;
  private final boolean logOnEventCalls;

  public TestMemoryThresholdListener() {
    this(false);
  }

  public TestMemoryThresholdListener(boolean log) {
    logOnEventCalls = log;
  }

  public long getBytesFromThreshold() {
    synchronized (this) {
      return bytesFromThreshold;
    }
  }

  public int getCurrentHeapPercentage() {
    synchronized (this) {
      return currentHeapPercentage;
    }
  }

  public int getAllCalls() {
    synchronized (this) {
      return allCalls;
    }
  }

  public int getNormalCalls() {
    synchronized (this) {
      return normalCalls;
    }
  }

  public int getCriticalThresholdCalls() {
    synchronized (this) {
      return criticalThresholdCalls;
    }
  }

  public int getCriticalDisabledCalls() {
    synchronized (this) {
      return criticalDisabledCalls;
    }
  }

  public int getEvictionThresholdCalls() {
    synchronized (this) {
      return evictionThresholdCalls;
    }
  }

  public int getEvictionDisabledCalls() {
    synchronized (this) {
      return evictionDisabledCalls;
    }
  }

  public void resetThresholdCalls() {
    synchronized (this) {
      normalCalls = 0;
      criticalThresholdCalls = 0;
      evictionThresholdCalls = 0;
      bytesFromThreshold = 0;
      currentHeapPercentage = 0;
      evictionDisabledCalls = 0;
      criticalDisabledCalls = 0;
      allCalls = 0;
    }
  }

  @Override
  public String toString() {
    return "TestListenerStatus:" + " normalCalls :" + normalCalls
        + " allCalls :" + allCalls
        + " criticalThresholdCalls :" + criticalThresholdCalls
        + " evictionThresholdCalls :" + evictionThresholdCalls
        + " previousNormalCalls :" + normalCalls
        + " bytesFromThreshold :" + bytesFromThreshold
        + " currentHeapPercentage :" + currentHeapPercentage
        + " evictionDisabledCalls :" + evictionDisabledCalls
        + " criticalDisabledCalls :" + criticalDisabledCalls;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceListener#onEvent(java.lang.Object)
   */
  @Override
  public void onEvent(MemoryEvent event) {
    if (logOnEventCalls) {
      logger.info("TestMemoryThresholdListener onEvent " + event);
    }
    synchronized (this) {
      if (event.getState().isNormal()) {
        normalCalls++;
      }
      if (event.getState().isCritical() && event.getState() != event.getPreviousState()) {
        criticalThresholdCalls++;
      }
      if (event.getState().isEviction() && event.getState() != event.getPreviousState()) {
        evictionThresholdCalls++;
      }
      if (event.getState().isCriticalDisabled() && event.getState() != event.getPreviousState()) {
        criticalDisabledCalls++;
      }
      if (event.getState().isEvictionDisabled() && event.getState() != event.getPreviousState()) {
        evictionDisabledCalls++;
      }

      allCalls++;

      if (event.getState().isCritical()) {
        bytesFromThreshold =
            event.getBytesUsed() - event.getThresholds().getCriticalThresholdBytes();
      } else if (event.getState().isEviction()) {
        if (event.getPreviousState().isCritical()) {
          bytesFromThreshold =
              event.getThresholds().getCriticalThresholdBytes() - event.getBytesUsed();
        } else {
          bytesFromThreshold =
              event.getBytesUsed() - event.getThresholds().getEvictionThresholdBytes();
        }
      } else {
        bytesFromThreshold =
            event.getThresholds().getEvictionThresholdBytes() - event.getBytesUsed();
      }

      if (event.getThresholds().getMaxMemoryBytes() == 0) {
        currentHeapPercentage = 0;
      } else if (event.getBytesUsed() > event.getThresholds().getMaxMemoryBytes()) {
        currentHeapPercentage = 1;
      } else {
        currentHeapPercentage = convertToIntPercent(
            (double) event.getBytesUsed() / event.getThresholds().getMaxMemoryBytes());
      }
    }
  }

  /**
   * Convert a percentage as a double to an integer e.g. 0.09 => 9 also legal is 0.095 => 9
   *
   * @param percentHeap a percentage value expressed as a double e.g. 9.5% => 0.095
   * @return the calculated percent as an integer >= 0 and <= 100
   */
  private int convertToIntPercent(final double percentHeap) {
    assert percentHeap >= 0.0 && percentHeap <= 1.0;
    int ret = (int) Math.ceil(percentHeap * 100.0);
    assert ret >= 0 && ret <= 100;
    return ret;
  }
}
