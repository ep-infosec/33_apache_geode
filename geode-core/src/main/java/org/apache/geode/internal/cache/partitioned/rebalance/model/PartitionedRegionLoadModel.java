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
package org.apache.geode.internal.cache.partitioned.rebalance.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.logging.log4j.Logger;

import org.apache.geode.annotations.Immutable;
import org.apache.geode.cache.partition.PartitionMemberInfo;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.cache.FixedPartitionAttributesImpl;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.partitioned.InternalPartitionDetails;
import org.apache.geode.internal.cache.partitioned.OfflineMemberDetails;
import org.apache.geode.internal.cache.partitioned.PRLoad;
import org.apache.geode.internal.cache.partitioned.PartitionMemberInfoImpl;
import org.apache.geode.internal.cache.partitioned.rebalance.BucketOperator;
import org.apache.geode.internal.cache.persistence.PersistentMemberID;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * A model of the load on all of the members for a partitioned region. This model is used to find
 * the best members to create buckets on or move buckets or primaries too. All of the actual work of
 * creating a copy, moving a primary, etc. Is performed by the BucketOperator that is passed to the
 * constructor.
 *
 * To use, create a model and populate it using the addMember method. addMember takes a region
 * argument, to indicate which region the data is for. All of the regions added to a single model
 * are assumed to be colocated, and the model adds together the load from each of the individual
 * regions to balance all of the regions together.
 *
 * Rebalancing operations are performed by repeatedly calling model.nextStep until it returns false.
 * Each call to nextStep should perform another operation. The model will make callbacks to the
 * BucketOperator you provide to the contructor perform the actual create or move.
 *
 * While creating redundant copies our moving buckets, this model tries to minimize the standard
 * deviation in the weighted loads for the members. The weighted load for the member is the sum of
 * the load for all of the buckets on the member divided by that members weight.
 *
 * This model is not threadsafe.
 *
 * @since GemFire 6.0
 */
@SuppressWarnings("synthetic-access")
public class PartitionedRegionLoadModel {
  private static final Logger logger = LogService.getLogger();

  /**
   * A comparator that is used to sort buckets in the order that we should satisfy redundancy - most
   * needy buckets first.
   */
  @Immutable
  private static final Comparator<Bucket> REDUNDANCY_COMPARATOR = (o1, o2) -> {
    // put the buckets with the lowest redundancy first
    int result = o1.getRedundancy() - o2.getRedundancy();
    if (result == 0) {
      // put the bucket with the largest load first. This should give us a
      // better chance of finding a place to put it
      result = Float.compare(o2.getLoad(), o1.getLoad());
    }
    if (result == 0) {
      // finally, just use the id so the comparator doesn't swallow buckets
      // with the same load
      result = o1.getId() - o2.getId();
    }

    return result;
  };

  private static final long MEGABYTES = 1024 * 1024;

  /**
   * A member to represent inconsistent data. For example, if two members think they are the primary
   * for a bucket, we will set the primary to invalid, so it won't be a candidate for rebalancing.
   */
  @Immutable
  public static final MemberRollup INVALID_MEMBER = new MemberRollup(null, null, false, false);

  private final BucketRollup[] buckets;

  /**
   * A map of all members that host this partitioned region
   */
  private final Map<InternalDistributedMember, MemberRollup> members = new HashMap<>();

  /**
   * The set of all regions that are colocated in this model.
   */
  private final Set<String> allColocatedRegions = new HashSet<>();

  /**
   * The list of buckets that have low redundancy
   */
  private final SortedSet<BucketRollup> lowRedundancyBuckets = new TreeSet<>(REDUNDANCY_COMPARATOR);
  private SortedSet<BucketRollup> overRedundancyBuckets = null;
  private final Collection<Move> attemptedPrimaryMoves = new HashSet<>();
  private final Collection<Move> attemptedBucketMoves = new HashSet<>();
  private final Collection<Move> attemptedBucketCreations = new HashSet<>();
  private final Collection<Move> attemptedBucketRemoves = new HashSet<>();

  private final BucketOperator operator;
  private final int requiredRedundancy;

  /**
   * The average primary load on a member
   */
  private float primaryAverage = -1;
  /**
   * The average bucket load on a member
   */
  private float averageLoad = -1;
  /**
   * The minimum improvement in variance that we'll consider worth moving a primary
   */
  private double minPrimaryImprovement = -1;
  /**
   * The minimum improvement in variance that we'll consider worth moving a bucket
   */
  private double minImprovement = -1;

  private final AddressComparor addressComparor;

  private final Set<InternalDistributedMember> criticalMembers;

  private final PartitionedRegion partitionedRegion;

  /**
   * Create a new model
   *
   * @param operator the operator which performs the actual creates/moves for buckets
   * @param redundancyLevel The expected redundancy level for the region
   */
  public PartitionedRegionLoadModel(BucketOperator operator, int redundancyLevel, int numBuckets,
      AddressComparor addressComparor,
      Set<InternalDistributedMember> criticalMembers,
      PartitionedRegion region) {
    this.operator = operator;
    requiredRedundancy = redundancyLevel;
    buckets = new BucketRollup[numBuckets];
    this.addressComparor = addressComparor;
    this.criticalMembers = criticalMembers;
    partitionedRegion = region;
  }

  /**
   * Add a region to the model. All regions that are added are assumed to be colocated. The first
   * region added to the model should be the parent region. The parent region is expected to have at
   * least as many members as child regions; it may have more. If the parent has more members than
   * child regions those members will be considered invalid.
   *
   * @param memberDetailSet A set of details about each member.
   */
  public void addRegion(String region,
      Collection<? extends InternalPartitionDetails> memberDetailSet,
      OfflineMemberDetails offlineDetails, boolean enforceLocalMaxMemory) {
    allColocatedRegions.add(region);
    // build up a list of members and an array of buckets for this
    // region. Each bucket has a reference to all of the members
    // that host it and each member has a reference to all of the buckets
    // it hosts
    Map<InternalDistributedMember, Member> regionMember = new HashMap<>();
    Bucket[] regionBuckets = new Bucket[buckets.length];
    for (InternalPartitionDetails memberDetails : memberDetailSet) {
      InternalDistributedMember memberId =
          (InternalDistributedMember) memberDetails.getDistributedMember();

      boolean isCritical = criticalMembers.contains(memberId);
      Member member = new Member(addressComparor, memberId, memberDetails.getPRLoad().getWeight(),
          memberDetails.getConfiguredMaxMemory(), isCritical, enforceLocalMaxMemory);
      regionMember.put(memberId, member);

      PRLoad load = memberDetails.getPRLoad();
      for (int i = 0; i < regionBuckets.length; i++) {
        if (load.getReadLoad(i) > 0) {
          Bucket bucket = regionBuckets[i];
          if (bucket == null) {
            Set<PersistentMemberID> offlineMembers = offlineDetails.getOfflineMembers(i);
            bucket =
                new Bucket(i, load.getReadLoad(i), memberDetails.getBucketSize(i), offlineMembers);
            regionBuckets[i] = bucket;
          }
          bucket.addMember(member);
          if (load.getWriteLoad(i) > 0) {
            if (bucket.getPrimary() == null) {
              bucket.setPrimary(member, load.getWriteLoad(i));
            } else if (!bucket.getPrimary().equals(member)) {
              bucket.setPrimary(INVALID_MEMBER, 1);
            }
          }
        }
      }
    }

    // add each member for this region to a rollup of all colocated
    // regions
    for (Member member : regionMember.values()) {
      InternalDistributedMember memberId = member.getDistributedMember();
      MemberRollup memberSum = members.get(memberId);
      boolean isCritical = criticalMembers.contains(memberId);
      if (memberSum == null) {
        memberSum = new MemberRollup(addressComparor, memberId, isCritical, enforceLocalMaxMemory);
        members.put(memberId, memberSum);
      }

      memberSum.addColocatedMember(region, member);
    }

    // Now, add the region to the rollups of the colocated
    // regions and buckets
    for (int i = 0; i < buckets.length; i++) {
      if (regionBuckets[i] == null) {
        // do nothing, this bucket is not hosted for this region.
        // [sumedh] remove from buckets array too to be consistent since
        // this method will be invoked repeatedly for all colocated regions,
        // and then we may miss some colocated regions for a bucket leading
        // to all kinds of issues later
        buckets[i] = null;
        continue;
      }
      if (buckets[i] == null) {
        // If this is the first region we have seen that is hosting this bucket, create a bucket
        // rollup
        buckets[i] = new BucketRollup(i);
      }

      // Add all of the members hosting the bucket to the rollup
      for (Member member : regionBuckets[i].getMembersHosting()) {
        InternalDistributedMember memberId = member.getDistributedMember();
        buckets[i].addMember(members.get(memberId));
      }

      // set the primary for the rollup
      if (regionBuckets[i].getPrimary() != null) {
        if (buckets[i].getPrimary() == null) {
          InternalDistributedMember memberId = regionBuckets[i].getPrimary().getDistributedMember();
          buckets[i].setPrimary(members.get(memberId), 0);
        } else {
          if (!(buckets[i].getPrimary() == INVALID_MEMBER)) {
            if (!buckets[i].getPrimary().getDistributedMember()
                .equals(regionBuckets[i].getPrimary().getDistributedMember())) {
              if (logger.isDebugEnabled()) {
                logger.debug(
                    "PartitionedRegionLoadModel - Setting bucket {} to INVALID because it is the primary on two members.This could just be a race in the collocation of data. member1={} member2={}",
                    buckets[i], buckets[i].getPrimary(), regionBuckets[i].getPrimary());
              }
              buckets[i].setPrimary(INVALID_MEMBER, 0);
            }
          }
        }
      }
      buckets[i].addColocatedBucket(region, regionBuckets[i]);
    }

    // TODO rebalance - there is a possibility of adding members
    // back here, which I don't like. I think maybe all of the regions should be in the
    // constructor for the load model, and then when the constructor is done
    // we can do with validation.
    // If any members don't have this new region, remove them.
    for (Iterator<Entry<InternalDistributedMember, MemberRollup>> itr =
        members.entrySet().iterator(); itr.hasNext();) {
      MemberRollup memberRollup = itr.next().getValue();
      if (!memberRollup.getColocatedMembers().keySet().equals(allColocatedRegions)) {
        itr.remove();
        if (logger.isDebugEnabled()) {
          logger.debug(
              "PartitionedRegionLoadModel - removing member {} from the consideration because it doesn't have all of the colocated regions. Expected={}, was={}",
              memberRollup, allColocatedRegions, memberRollup.getColocatedMembers());
        }
        // This state should never happen
        if (!memberRollup.getBuckets().isEmpty()) {
          logger.warn(
              "PartitionedRegionLoadModel - member {} has incomplete colocation, but it has buckets for some regions. Should have colocated regions {} but had {} and contains buckets {}",
              new Object[] {memberRollup, allColocatedRegions,
                  memberRollup.getColocatedMembers().keySet(), memberRollup.getBuckets()});
        }
        for (Bucket bucket : new HashSet<>(memberRollup.getBuckets())) {
          bucket.removeMember(memberRollup);
        }
      }
    }
  }

  public void initialize() {
    resetAverages();
    identifyOverRedundantBuckets();
    initLowRedundancyBuckets();
  }

  public SortedSet<BucketRollup> getLowRedundancyBuckets() {
    return lowRedundancyBuckets;
  }

  public SortedSet<BucketRollup> getOverRedundancyBuckets() {
    return overRedundancyBuckets;
  }

  public boolean enforceUniqueZones() {
    return addressComparor.enforceUniqueZones();
  }

  public void ignoreLowRedundancyBucket(BucketRollup first) {
    lowRedundancyBuckets.remove(first);
  }

  public void ignoreOverRedundancyBucket(BucketRollup first) {
    overRedundancyBuckets.remove(first);
  }

  public MemberRollup getMember(InternalDistributedMember target) {
    return members.get(target);
  }

  public BucketRollup[] getBuckets() {
    return buckets;
  }

  public String getName() {
    return getPartitionedRegion().getFullPath();
  }

  public PartitionedRegion getPartitionedRegion() {
    // TODO - this model really should not have
    // a reference to the partitioned region object.
    // The fixed PR code currently depends on this
    // partitioned region object and needs
    // refactoring.
    return partitionedRegion;
  }

  private Map<String, Long> getColocatedRegionSizes(BucketRollup bucket) {
    Map<String, Long> colocatedRegionSizes = new HashMap<>();

    for (Map.Entry<String, Bucket> entry : bucket.getColocatedBuckets().entrySet()) {
      colocatedRegionSizes.put(entry.getKey(), entry.getValue().getBytes());
    }
    return colocatedRegionSizes;
  }

  /**
   * Trigger the creation of a redundant bucket, potentially asynchronously.
   *
   * This method will find the best node to create a redundant bucket and invoke the bucket operator
   * to create a bucket on that node. Because the bucket operator is asynchronous, the bucket may
   * not be created immediately, but the model will be updated regardless. Invoke
   * {@link #waitForOperations()} to wait for those operations to actually complete
   *
   * @param bucket the bucket for which a redundant copy should be made
   * @param targetMember the member on which a redundant copy of a bucket should be made
   */
  public void createRedundantBucket(final BucketRollup bucket, final Member targetMember) {
    Map<String, Long> colocatedRegionSizes = getColocatedRegionSizes(bucket);
    final Move move = new Move(null, targetMember, bucket);

    lowRedundancyBuckets.remove(bucket);
    bucket.addMember(targetMember);
    // put the bucket back into the list if we still need to satisfy redundancy for
    // this bucket
    if (bucket.getRedundancy() < requiredRedundancy) {
      lowRedundancyBuckets.add(bucket);
    }
    resetAverages();

    operator.createRedundantBucket(targetMember.getMemberId(), bucket.getId(),
        colocatedRegionSizes, new BucketOperator.Completion() {
          @Override
          public void onSuccess() {}

          @Override
          public void onFailure() {
            // If the bucket creation failed, we need to undo the changes
            // we made to the model
            attemptedBucketCreations.add(move);
            // remove the bucket from lowRedundancyBuckets before mutating the state
            lowRedundancyBuckets.remove(bucket);
            bucket.removeMember(targetMember);
            if (bucket.getRedundancy() < requiredRedundancy) {
              lowRedundancyBuckets.add(bucket);
            }
            resetAverages();
          }
        });
  }

  public void remoteOverRedundancyBucket(BucketRollup bucket, Member targetMember) {
    Move bestMove = new Move(null, targetMember, bucket);
    Map<String, Long> colocatedRegionSizes = getColocatedRegionSizes(bucket);

    if (!operator.removeBucket(targetMember.getMemberId(), bucket.getId(),
        colocatedRegionSizes)) {
      attemptedBucketRemoves.add(bestMove);
    } else {
      overRedundancyBuckets.remove(bucket);
      bucket.removeMember(targetMember);
      // put the bucket back into the list if we still need to satisfy redundancy for
      // this bucket
      if (bucket.getOnlineRedundancy() > requiredRedundancy) {
        overRedundancyBuckets.add(bucket);
      }
      resetAverages();
    }
  }

  private void initLowRedundancyBuckets() {
    for (BucketRollup b : buckets) {
      if (b != null && b.getRedundancy() >= 0 && b.getRedundancy() < requiredRedundancy) {
        lowRedundancyBuckets.add(b);
      }
    }
  }


  /**
   * Original functionality if bucket's redundancy is greater than what is necessary add it to the
   * over redundancy bucket list, so it can be cleared
   * <p>
   * Newly added functionality is to make this so that we don't have a bucket in the same redundancy
   * zone twice if zones are in use.
   */
  private void identifyOverRedundantBuckets() {
    overRedundancyBuckets = new TreeSet<>(REDUNDANCY_COMPARATOR);

    // For every bucket
    for (BucketRollup b : buckets) {
      if (b != null) {
        // check to see if the existing redundancy is greater than required
        if (b.getOnlineRedundancy() > requiredRedundancy) {
          // if so, add the bucket to the over redundancy list
          overRedundancyBuckets.add(b);
        } else {

          // figure out if we have over redundancy in a zone by having two members hosting a
          // bucket that should be spread across zones.
          determineOverRedundancyInZones(b);

        }
      }
    }
  }

  /**
   * Determine if the passed in bucket is on more than one member in a zone and mark it as
   * overredundant. If by marking a bucket over redundant, that would make the redundancy
   * insufficient, add the bucket to lowRedundancy as well so a member in a different zone
   * can host it.
   *
   * @param bucketRollup the BucketRollup that we are checking
   */
  private void determineOverRedundancyInZones(
      BucketRollup bucketRollup) {
    Set<String> redundancyZonesFound = new HashSet<>();

    // for each member that is hosting the bucket
    for (Member member : bucketRollup.getMembersHosting()) {

      // get the redundancy zone of the member
      String redundancyZone = getRedundancyZone(member.getDistributedMember());
      if (redundancyZone != null) {
        // if the redundancy zone is already in the list
        if (redundancyZonesFound.contains(redundancyZone)) {
          // add the bucket to the over redundancy list because we have more than one member
          // with this bucket in the same zone. something we don't prefer with multiple zones
          overRedundancyBuckets.add(bucketRollup);
          if (bucketRollup.getOnlineRedundancy() - 1 < bucketRollup.getRedundancy()) {
            lowRedundancyBuckets.add(bucketRollup);
          }
        } else {
          // otherwise add the redundancy zone to the list of redundancy zones
          redundancyZonesFound.add(redundancyZone);
        }
      }
    }
  }

  /**
   * Find the best member to put a new bucket on.
   *
   * @param bucket the bucket we want to create
   * @param checkIPAddress true if we should only consider members that do not have the same IP
   *        Address as a member that already hosts the bucket
   */
  public Move findBestTarget(Bucket bucket, boolean checkIPAddress) {
    float leastCost = Float.MAX_VALUE;
    Move bestMove = null;

    for (Member member : members.values()) {
      if (member.willAcceptBucket(bucket, null, checkIPAddress).willAccept()) {
        float cost = (member.getTotalLoad() + bucket.getLoad()) / member.getWeight();
        if (cost < leastCost) {
          Move move = new Move(null, member, bucket);
          if (!attemptedBucketCreations.contains(move)) {
            leastCost = cost;
            bestMove = move;
          }
        }
      }
    }
    return bestMove;
  }

  String getRedundancyZone(InternalDistributedMember memberID) {
    return partitionedRegion.getDistributionManager().getRedundancyZone(memberID);
  }

  /**
   * Look for a zone that has more than one member represented. That is where we want to try to
   * delete first.
   *
   * @param members set of members that are hosting this bucket
   * @return null or a string containing the zone to delete a member from
   */
  Set<String> getPreferredDeletionZone(Set<Member> members) {
    Set<String> distributionSet = new HashSet<>();
    Set<String> zonesToDeleteFrom = new HashSet<>();

    // for each member
    for (Member member : members) {
      // get the redundancy zone
      String zoneName = getRedundancyZone(member.getMemberId());
      // See if the zoneName is in the list

      if (distributionSet.contains(zoneName)) {
        // We have two members so that means it is preferred
        zonesToDeleteFrom.add(zoneName);
      } else {
        // first occurrence of the zone name in the set
        distributionSet.add(zoneName);
      }
    }
    // return nothing if this is the first member
    // because there is no preferred deletion zone yet.
    return zonesToDeleteFrom;
  }

  /**
   * Find the best member to remove a bucket from
   *
   * @param bucket the bucket we want to create
   */
  public Move findBestRemove(Bucket bucket) {
    float mostLoaded = Float.MIN_VALUE;
    Move bestMove = null;
    Set<Member> members = bucket.getMembersHosting();
    // Check to see if we have a preferred redundancy zone to delete from for this bucket.
    // We prefer deleting copies in the same redundancy zone
    Set<String> zones = getPreferredDeletionZone(members);

    for (Member member : members) {

      // if this load is lower than then highest load, we prefer the deleting from high
      // load servers so move on. If this member is the bucket primary, we prefer not to move
      // primaries, so move on.
      float newLoad = (member.getTotalLoad() - bucket.getLoad()) / member.getWeight();
      if (newLoad <= mostLoaded || member.equals(bucket.getPrimary())) {
        continue;
      }

      // if we have a preferred redundancy zone to delete from
      if (!zones.isEmpty()) {
        // leave the bucket on this member whose zone is not in the list
        if (!zones.contains(getRedundancyZone(member.getMemberId()))) {
          continue;
        }
      }

      // Since this bucket is an extra copy for the zone, we should remove it.
      // if the attemptedBucketRemovesList contains this move, then we don't need to add it
      // again.
      Move move = new Move(null, member, bucket);
      if (attemptedBucketRemoves.contains(move)) {
        continue;
      }

      mostLoaded = newLoad;
      bestMove = move;
    }
    return bestMove;
  }

  public Move findBestTargetForFPR(Bucket bucket, boolean checkIPAddress) {
    InternalDistributedMember targetMemberID;
    Member targetMember;
    List<FixedPartitionAttributesImpl> fpas =
        partitionedRegion.getFixedPartitionAttributesImpl();

    if (fpas != null) {
      for (FixedPartitionAttributesImpl fpaImpl : fpas) {
        if (fpaImpl.hasBucket(bucket.getId())) {
          targetMemberID =
              partitionedRegion.getDistributionManager().getDistributionManagerId();
          if (members.containsKey(targetMemberID)) {
            targetMember = members.get(targetMemberID);
            if (targetMember.willAcceptBucket(bucket, null, checkIPAddress).willAccept()) {
              // We should have just one move for creating
              // all the buckets for a FPR on this node.
              return new Move(null, targetMember, bucket);
            }
          }
        }
      }
    }

    return null;
  }

  public boolean movePrimary(Move bestMove) {
    Member bestSource = bestMove.getSource();
    Member bestTarget = bestMove.getTarget();
    Bucket bestBucket = bestMove.getBucket();
    boolean successfulMove = operator.movePrimary(bestSource.getDistributedMember(),
        bestTarget.getDistributedMember(), bestBucket.getId());

    if (successfulMove) {
      bestBucket.setPrimary(bestTarget, bestBucket.getPrimaryLoad());
    }

    boolean entryAdded = attemptedPrimaryMoves.add(bestMove);
    Assert.assertTrue(entryAdded,
        "PartitionedRegionLoadModel.movePrimarys - excluded set is not growing, so we probably would have an infinite loop here");

    return successfulMove;
  }

  public Move findBestPrimaryMove() {
    Move bestMove = null;
    double bestImprovement = 0;
    for (Member source : members.values()) {
      for (Bucket bucket : source.getPrimaryBuckets()) {
        for (Member target : bucket.getMembersHosting()) {
          if (source.equals(target)) {
            continue;
          }
          double improvement =
              improvement(source.getPrimaryLoad(), source.getWeight(), target.getPrimaryLoad(),
                  target.getWeight(), bucket.getPrimaryLoad(), getPrimaryAverage());
          if (improvement > bestImprovement && improvement > getMinPrimaryImprovement()) {
            Move move = new Move(source, target, bucket);
            if (!attemptedPrimaryMoves.contains(move)) {
              bestImprovement = improvement;
              bestMove = move;
            }
          }
        }
      }
    }
    return bestMove;
  }

  /**
   * Calculate the target weighted number of primaries on each node.
   */
  private float getPrimaryAverage() {
    if (primaryAverage == -1) {
      float totalWeight = 0;
      float totalPrimaryCount = 0;
      for (Member member : members.values()) {
        totalPrimaryCount += member.getPrimaryLoad();
        totalWeight += member.getWeight();
      }

      primaryAverage = totalPrimaryCount / totalWeight;
    }

    return primaryAverage;
  }

  /**
   * Calculate the target weighted amount of data on each node.
   */
  private float getAverageLoad() {
    if (averageLoad == -1) {
      float totalWeight = 0;
      float totalLoad = 0;
      for (Member member : members.values()) {
        totalLoad += member.getTotalLoad();
        totalWeight += member.getWeight();
      }

      averageLoad = totalLoad / totalWeight;
    }

    return averageLoad;
  }

  /**
   * Calculate the minimum improvement in variance that will we consider worth while. Currently this
   * is calculated as the improvement in variance that would occur by removing the smallest bucket
   * from the member with the largest weight.
   */
  private double getMinPrimaryImprovement() {
    if ((minPrimaryImprovement + 1.0) < .0000001) { // i.e. == -1
      float largestWeight = 0;
      float smallestBucket = 0;
      for (Member member : members.values()) {
        if (member.getWeight() > largestWeight) {
          largestWeight = member.getWeight();
        }
        for (Bucket bucket : member.getPrimaryBuckets()) {
          if (bucket.getPrimaryLoad() < smallestBucket || smallestBucket == 0) {
            smallestBucket = bucket.getPrimaryLoad();
          }
        }
      }
      double before = variance(getPrimaryAverage() * largestWeight + smallestBucket, largestWeight,
          getPrimaryAverage());
      double after =
          variance(getPrimaryAverage() * largestWeight, largestWeight, getPrimaryAverage());
      minPrimaryImprovement = (before - after) / smallestBucket;
    }
    return minPrimaryImprovement;
  }

  /**
   * Calculate the minimum improvement in variance that will we consider worth while. Currently this
   * is calculated as the improvement in variance that would occur by removing the smallest bucket
   * from the member with the largest weight.
   */
  private double getMinImprovement() {
    if ((minImprovement + 1.0) < .0000001) { // i.e. == -1
      float largestWeight = 0;
      float smallestBucket = 0;
      for (Member member : members.values()) {
        if (member.getWeight() > largestWeight) {
          largestWeight = member.getWeight();
        }
        // find the smallest bucket, ignoring empty buckets.
        for (Bucket bucket : member.getBuckets()) {
          if (smallestBucket == 0 || (bucket.getLoad() < smallestBucket && bucket.getBytes() > 0)) {
            smallestBucket = bucket.getLoad();
          }
        }
      }
      double before = variance(getAverageLoad() * largestWeight + smallestBucket, largestWeight,
          getAverageLoad());
      double after = variance(getAverageLoad() * largestWeight, largestWeight, getAverageLoad());
      minImprovement = (before - after) / smallestBucket;
    }
    return minImprovement;
  }

  private void resetAverages() {
    primaryAverage = -1;
    averageLoad = -1;
    minPrimaryImprovement = -1;
    minImprovement = -1;
  }

  /**
   * Calculate how much the variance in load will decrease for a given move.
   *
   * @param sLoad the current load on the source member
   * @param sWeight the weight of the source member
   * @param tLoad the current load on the target member
   * @param tWeight the weight of the target member
   * @param bucketSize the size of the bucket we're considering moving
   * @param average the target weighted load for all members.
   * @return the change in variance that would occur by making this move. Essentially
   *         variance_before - variance_after, so a positive change is a means the variance is
   *         decreasing.
   */
  private double improvement(float sLoad, float sWeight, float tLoad, float tWeight,
      float bucketSize, float average) {

    double vSourceBefore = variance(sLoad, sWeight, average);
    double vSourceAfter = variance(sLoad - bucketSize, sWeight, average);
    double vTargetBefore = variance(tLoad, tWeight, average);
    double vTargetAfter = variance(tLoad + bucketSize, tWeight, average);

    double improvement = vSourceBefore - vSourceAfter + vTargetBefore - vTargetAfter;
    return improvement / bucketSize;
  }

  private double variance(double load, double weight, double average) {
    double deviation = (load / weight - average);
    return deviation * deviation;
  }

  public Move findBestBucketMove() {
    Move bestMove = null;
    double bestImprovement = 0;
    for (Member source : members.values()) {
      for (Bucket bucket : source.getBuckets()) {
        for (Member target : members.values()) {
          if (bucket.getMembersHosting().contains(target)) {
            continue;
          }
          if (!target.willAcceptBucket(bucket, source, true).willAccept()) {
            continue;
          }
          double improvement = improvement(source.getTotalLoad(), source.getWeight(),
              target.getTotalLoad(), target.getWeight(), bucket.getLoad(), getAverageLoad());
          if (improvement > bestImprovement && improvement > getMinImprovement()) {
            Move move = new Move(source, target, bucket);
            if (!attemptedBucketMoves.contains(move)) {
              bestImprovement = improvement;
              bestMove = move;
            }
          }
        }
      }
    }
    return bestMove;
  }

  public boolean moveBucket(Move bestMove) {
    Member bestSource = bestMove.getSource();
    Member bestTarget = bestMove.getTarget();
    BucketRollup bestBucket = (BucketRollup) bestMove.getBucket();

    Map<String, Long> colocatedRegionSizes = getColocatedRegionSizes(bestBucket);

    boolean successfulMove = operator.moveBucket(bestSource.getDistributedMember(),
        bestTarget.getDistributedMember(), bestBucket.getId(), colocatedRegionSizes);

    if (successfulMove) {
      bestBucket.addMember(bestTarget);
      if (bestSource.equals(bestBucket.getPrimary())) {
        bestBucket.setPrimary(bestTarget, bestBucket.getPrimaryLoad());
      }
      bestBucket.removeMember(bestSource);
    }

    boolean entryAdded = attemptedBucketMoves.add(bestMove);
    Assert.assertTrue(entryAdded,
        "PartitionedRegionLoadModel.moveBuckets - excluded set is not growing, so we probably would have an infinite loop here");

    return successfulMove;
  }

  /**
   * Return a snapshot of what the partitioned member details look like.
   *
   * @return a set of partitioned member details.
   */
  public Set<PartitionMemberInfo> getPartitionedMemberDetails(String region) {
    TreeSet<PartitionMemberInfo> result = new TreeSet<>();
    for (MemberRollup member : members.values()) {
      Member colocatedMember = member.getColocatedMember(region);
      if (colocatedMember != null) {
        result.add(new PartitionMemberInfoImpl(colocatedMember.getDistributedMember(),
            colocatedMember.getConfiguredMaxMemory(), colocatedMember.getSize(),
            colocatedMember.getBucketCount(), colocatedMember.getPrimaryCount()));
      }
    }
    return result;
  }

  /**
   * For testing only, calculate the total variance of the members
   */
  public double getVarianceForTest() {
    double variance = 0;

    for (Member member : members.values()) {
      variance += variance(member.getTotalLoad(), member.getWeight(), getAverageLoad());
    }

    return variance;
  }

  /**
   * For testing only, calculate the total variance of the members
   */
  public double getPrimaryVarianceForTest() {
    double variance = 0;

    for (Member member : members.values()) {
      variance += variance(member.getPrimaryLoad(), member.getWeight(), getPrimaryAverage());
    }

    return variance;
  }

  /**
   * Wait for the bucket operator to complete any pending asynchronous operations.
   */
  public void waitForOperations() {
    operator.waitForOperations();
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    TreeSet<Bucket> allBucketIds = new TreeSet<>(Comparator.comparingInt(Bucket::getId));
    if (members.isEmpty()) {
      return "";
    }
    int longestMemberId = 0;
    for (Member member : members.values()) {
      allBucketIds.addAll(member.getBuckets());
      int memberIdLength = member.getDistributedMember().toString().length();
      if (longestMemberId < memberIdLength) {
        longestMemberId = memberIdLength;
      }
    }
    result
        .append(String.format("%" + longestMemberId + "s primaries size(MB)  max(MB)", "MemberId"));
    for (Bucket bucket : allBucketIds) {
      result.append(String.format("%4s", bucket.getId()));
    }
    for (Member member : members.values()) {
      result.append(String.format("\n%" + longestMemberId + "s %9.0f %8.2f %8.2f",
          member.getDistributedMember(), member.getPrimaryLoad(),
          member.getSize() / (float) MEGABYTES,
          member.getConfiguredMaxMemory() / (float) MEGABYTES));
      for (Bucket bucket : allBucketIds) {
        char symbol;
        if (member.getPrimaryBuckets().contains(bucket)) {
          symbol = 'P';
        } else if (member.getBuckets().contains(bucket)) {
          symbol = 'R';
        } else {
          symbol = 'X';
        }
        result.append("   ").append(symbol);
      }
    }

    result.append(String.format("\n%" + longestMemberId + "s                            ",
        "#offline"));
    for (Bucket bucket : allBucketIds) {
      result.append(String.format("%4s", bucket.getOfflineMembers().size()));
    }

    return result.toString();
  }

}
