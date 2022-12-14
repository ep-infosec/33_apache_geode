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

import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.geode.cache.partition.PartitionMemberInfo;
import org.apache.geode.cache.partition.PartitionRebalanceInfo;
import org.apache.geode.internal.cache.PartitionedRegion;

/**
 * Holds the rebalancing details for a single partitioned region.
 *
 * Serializable form is used to allow JMX MBeans to use this as a remotable return type.
 *
 */
public class PartitionRebalanceDetailsImpl
    implements PartitionRebalanceInfo, Serializable, Comparable<PartitionRebalanceDetailsImpl> {
  private static final long serialVersionUID = 5880667005758250156L;
  private long bucketCreateBytes;
  private long bucketCreateTime;
  private int bucketCreatesCompleted;
  private long bucketRemoveBytes;
  private long bucketRemoveTime;
  private int bucketRemovesCompleted;
  private long bucketTransferBytes;
  private long bucketTransferTime;
  private int bucketTransfersCompleted;
  private Set<PartitionMemberInfo> partitionMemberDetailsAfter;
  private Set<PartitionMemberInfo> partitionMemberDetailsBefore;
  private long primaryTransferTime;
  private int primaryTransfersCompleted;
  private final transient PartitionedRegion region;
  private long time;
  private int numOfMembers;

  @Override
  public String toString() {
    return "PartitionRebalanceDetailsImpl{" +
        "bucketCreateBytes=" + bucketCreateBytes +
        ", bucketCreateTime=" + bucketCreateTime +
        ", bucketCreatesCompleted=" + bucketCreatesCompleted +
        ", bucketRemoveBytes=" + bucketRemoveBytes +
        ", bucketRemoveTime=" + bucketRemoveTime +
        ", bucketRemovesCompleted=" + bucketRemovesCompleted +
        ", bucketTransferBytes=" + bucketTransferBytes +
        ", bucketTransferTime=" + bucketTransferTime +
        ", bucketTransfersCompleted=" + bucketTransfersCompleted +
        ", partitionMemberDetailsAfter=" + partitionMemberDetailsAfter +
        ", partitionMemberDetailsBefore=" + partitionMemberDetailsBefore +
        ", primaryTransferTime=" + primaryTransferTime +
        ", primaryTransfersCompleted=" + primaryTransfersCompleted +
        ", region=" + region +
        ", time=" + time +
        '}';
  }

  public PartitionRebalanceDetailsImpl(PartitionedRegion region) {
    this.region = region;
  }

  public synchronized void incCreates(long bytes, long time) {
    bucketCreateBytes += bytes;
    bucketCreateTime += time;
    bucketCreatesCompleted++;
  }

  public synchronized void incRemoves(long bytes, long time) {
    bucketRemoveBytes += bytes;
    bucketRemoveTime += time;
    bucketRemovesCompleted++;

  }

  public synchronized void incTransfers(long bytes, long time) {
    bucketTransferBytes += bytes;
    bucketTransferTime += time;
    bucketTransfersCompleted++;
  }

  public synchronized void incPrimaryTransfers(long time) {
    primaryTransfersCompleted++;
    primaryTransferTime += time;
  }

  public void setPartitionMemberDetailsAfter(Set<PartitionMemberInfo> after) {
    partitionMemberDetailsAfter = after;
  }

  public void setPartitionMemberDetailsBefore(Set<PartitionMemberInfo> before) {
    partitionMemberDetailsBefore = before;
  }

  public void setTime(long time) {
    this.time = time;
  }

  @Override
  public long getBucketCreateBytes() {
    return bucketCreateBytes;
  }

  @Override
  public long getBucketCreateTime() {
    return TimeUnit.NANOSECONDS.toMillis(bucketCreateTime);
  }

  @Override
  public int getBucketCreatesCompleted() {
    return bucketCreatesCompleted;
  }

  @Override
  public long getBucketRemoveBytes() {
    return bucketRemoveBytes;
  }

  @Override
  public long getBucketRemoveTime() {
    return TimeUnit.NANOSECONDS.toMillis(bucketRemoveTime);
  }

  @Override
  public int getBucketRemovesCompleted() {
    return bucketRemovesCompleted;
  }

  @Override
  public long getBucketTransferBytes() {
    return bucketTransferBytes;
  }

  @Override
  public long getBucketTransferTime() {
    return TimeUnit.NANOSECONDS.toMillis(bucketTransferTime);
  }

  @Override
  public int getBucketTransfersCompleted() {
    return bucketTransfersCompleted;
  }

  @Override
  public Set<PartitionMemberInfo> getPartitionMemberDetailsAfter() {
    return partitionMemberDetailsAfter;
  }

  @Override
  public Set<PartitionMemberInfo> getPartitionMemberDetailsBefore() {
    return partitionMemberDetailsBefore;
  }

  @Override
  public long getPrimaryTransferTime() {
    return TimeUnit.NANOSECONDS.toMillis(primaryTransferTime);
  }

  @Override
  public int getNumberOfMembersExecutedOn() {
    return getPartitionMemberDetailsAfter().size();
  }

  @Override
  public int getPrimaryTransfersCompleted() {
    return primaryTransfersCompleted;
  }

  @Override
  public String getRegionPath() {
    return region.getFullPath();
  }

  public PartitionedRegion getRegion() {
    return region;
  }

  @Override
  public long getTime() {
    return TimeUnit.NANOSECONDS.toMillis(time);
  }

  @Override
  public int compareTo(PartitionRebalanceDetailsImpl other) {
    return region.getFullPath().compareTo(other.region.getFullPath());
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof PartitionRebalanceDetailsImpl)) {
      return false;
    }
    PartitionRebalanceDetailsImpl o = (PartitionRebalanceDetailsImpl) other;
    return region.getFullPath().equals(o.region.getFullPath());
  }

  @Override
  public int hashCode() {
    return region.getFullPath().hashCode();
  }
}
