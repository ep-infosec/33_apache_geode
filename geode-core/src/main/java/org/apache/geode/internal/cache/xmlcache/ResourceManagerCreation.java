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
package org.apache.geode.internal.cache.xmlcache;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.geode.cache.control.RebalanceFactory;
import org.apache.geode.cache.control.RebalanceOperation;
import org.apache.geode.cache.control.ResourceManager;
import org.apache.geode.cache.control.RestoreRedundancyOperation;
import org.apache.geode.internal.cache.control.MemoryThresholds;
import org.apache.geode.management.runtime.RestoreRedundancyResults;

/**
 * @since GemFire 6.0
 */
public class ResourceManagerCreation implements ResourceManager {

  private volatile float criticalHeapPercentage;
  private boolean criticalHeapSet = false;

  private volatile float evictionHeapPercentage;
  private boolean evictionHeapSet = false;

  private volatile float criticalOffHeapPercentage;
  private boolean criticalOffHeapSet = false;

  private volatile float evictionOffHeapPercentage;
  private boolean evictionOffHeapSet = false;

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#createRebalanceFactory()
   */
  @Override
  public RebalanceFactory createRebalanceFactory() {
    throw new IllegalArgumentException("Unused");
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#getRebalanceOperations()
   */
  @Override
  public Set<RebalanceOperation> getRebalanceOperations() {
    throw new IllegalArgumentException("Unused");
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#createRestoreRedundancyOperation()
   */
  @Override
  public RestoreRedundancyOperation createRestoreRedundancyOperation() {
    throw new IllegalArgumentException("Unused");
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#getRestoreRedundancyFutures()
   */
  @Override
  public Set<CompletableFuture<RestoreRedundancyResults>> getRestoreRedundancyFutures() {
    throw new IllegalArgumentException("Unused");
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#getCriticalHeapPercentage()
   */
  @Override
  public float getCriticalHeapPercentage() {
    return criticalHeapPercentage;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#setCriticalHeapPercentage(int)
   */
  @Override
  public void setCriticalHeapPercentage(float heapPercentage) {
    criticalHeapSet = true;
    criticalHeapPercentage = heapPercentage;
  }

  public void setCriticalHeapPercentageToDefault() {
    criticalHeapPercentage = MemoryThresholds.DEFAULT_CRITICAL_PERCENTAGE;
  }

  /**
   * Determine if the critical heap was configured
   *
   * @return true if it was configured
   */
  public boolean hasCriticalHeap() {
    return criticalHeapSet;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#getCriticalOffHeapPercentage()
   */
  @Override
  public float getCriticalOffHeapPercentage() {
    return criticalOffHeapPercentage;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#setCriticalOffHeapPercentage(int)
   */
  @Override
  public void setCriticalOffHeapPercentage(final float offHeapPercentage) {
    criticalOffHeapSet = true;
    criticalOffHeapPercentage = offHeapPercentage;
  }

  public void setCriticalOffHeapPercentageToDefault() {
    criticalOffHeapPercentage = MemoryThresholds.DEFAULT_CRITICAL_PERCENTAGE;
  }

  /**
   * Determine if the critical off-heap was configured
   *
   * @return true if it was configured
   */
  public boolean hasCriticalOffHeap() {
    return criticalOffHeapSet;
  }

  public void configure(ResourceManager r) {
    if (hasCriticalHeap()) {
      r.setCriticalHeapPercentage(criticalHeapPercentage);
    }
    if (hasCriticalOffHeap()) {
      r.setCriticalOffHeapPercentage(criticalOffHeapPercentage);
    }
    if (hasEvictionHeap()) {
      r.setEvictionHeapPercentage(evictionHeapPercentage);
    }
    if (hasEvictionOffHeap()) {
      r.setEvictionOffHeapPercentage(evictionOffHeapPercentage);
    }
  }

  /**
   * @param other the other ResourceManager with which to compare
   */
  public void sameAs(ResourceManager other) {
    if (getCriticalHeapPercentage() != other.getCriticalHeapPercentage()) {
      throw new RuntimeException("Resource Manager critical heap percentages differ: "
          + getCriticalHeapPercentage() + " != " + other.getCriticalHeapPercentage());
    }
    if (getCriticalOffHeapPercentage() != other.getCriticalOffHeapPercentage()) {
      throw new RuntimeException("Resource Manager critical off-heap percentages differ: "
          + getCriticalOffHeapPercentage() + " != " + other.getCriticalOffHeapPercentage());
    }
    if (hasEvictionHeap()) {
      // If we don't have it set don't compare since other may have been set to
      // a smart default.
      if (getEvictionHeapPercentage() != other.getEvictionHeapPercentage()) {
        throw new RuntimeException("Resource Manager eviction heap percentages differ: "
            + getEvictionHeapPercentage() + " != " + other.getEvictionHeapPercentage());
      }
    }
    if (hasEvictionOffHeap()) {
      // If we don't have it set don't compare since other may have been set to
      // a smart default.
      if (getEvictionOffHeapPercentage() != other.getEvictionOffHeapPercentage()) {
        throw new RuntimeException("Resource Manager eviction off-heap percentages differ: "
            + getEvictionOffHeapPercentage() + " != " + other.getEvictionOffHeapPercentage());
      }
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#getEvictionHeapPercentage()
   */
  @Override
  public float getEvictionHeapPercentage() {
    return evictionHeapPercentage;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#setEvictionHeapPercentage(int)
   */
  @Override
  public void setEvictionHeapPercentage(float heapPercentage) {
    evictionHeapSet = true;
    evictionHeapPercentage = heapPercentage;
  }

  public void setEvictionHeapPercentageToDefault() {
    evictionHeapPercentage = MemoryThresholds.DEFAULT_EVICTION_PERCENTAGE;
  }

  /**
   * Determine if the eviction heap was configured
   *
   * @return true if the eviction heap was configured
   */
  public boolean hasEvictionHeap() {
    return evictionHeapSet;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#getEvictionOffHeapPercentage()
   */
  @Override
  public float getEvictionOffHeapPercentage() {
    return evictionOffHeapPercentage;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.geode.cache.control.ResourceManager#setEvictionOffHeapPercentage(int)
   */
  @Override
  public void setEvictionOffHeapPercentage(final float offHeapPercentage) {
    evictionOffHeapSet = true;
    evictionOffHeapPercentage = offHeapPercentage;
  }

  public void setEvictionOffHeapPercentageToDefault() {
    evictionOffHeapPercentage = MemoryThresholds.DEFAULT_EVICTION_PERCENTAGE;
  }

  /**
   * Determine if the eviction off-heap was configured
   *
   * @return true if the eviction off-heap was configured
   */
  public boolean hasEvictionOffHeap() {
    return evictionOffHeapSet;
  }
}
