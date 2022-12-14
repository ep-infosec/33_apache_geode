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
package org.apache.geode.internal.cache.partitioned;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializer;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.DistributionMessage;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.ReplyException;
import org.apache.geode.distributed.internal.ReplyMessage;
import org.apache.geode.distributed.internal.ReplyProcessor21;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.NanoTimer;
import org.apache.geode.internal.cache.ForceReattemptException;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.logging.internal.log4j.api.LogService;

public class FetchPartitionDetailsMessage extends PartitionMessage {

  private static final Logger logger = LogService.getLogger();

  private volatile boolean internal;
  private LoadProbe loadProbe;
  private boolean fetchOfflineMembers;

  /**
   * Empty constructor to satisfy {@link DataSerializer} requirements
   */
  public FetchPartitionDetailsMessage() {}

  private FetchPartitionDetailsMessage(Set<InternalDistributedMember> recipients, int regionId,
      ReplyProcessor21 processor, boolean internal, boolean fetchOfflineMembers, LoadProbe probe) {
    super(recipients, regionId, processor);
    this.internal = internal;
    this.fetchOfflineMembers = fetchOfflineMembers;
    loadProbe = probe;
  }

  /**
   * Sends a message to fetch {@link org.apache.geode.cache.partition.PartitionMemberInfo
   * PartitionMemberDetails} for the specified <code>PartitionedRegion</code>.
   *
   * @param recipients the members to fetch PartitionMemberDetails from
   * @param region the PartitionedRegion to fetch member details for
   * @return the processor used to fetch the PartitionMemberDetails
   */
  public static FetchPartitionDetailsResponse send(Set<InternalDistributedMember> recipients,
      PartitionedRegion region, boolean internal, boolean fetchOfflineMembers, LoadProbe probe) {

    Assert.assertTrue(recipients != null && !recipients.isEmpty(),
        "FetchPartitionDetailsMessage NULL recipient");

    FetchPartitionDetailsResponse response =
        new FetchPartitionDetailsResponse(region.getSystem(), recipients, region);
    FetchPartitionDetailsMessage msg = new FetchPartitionDetailsMessage(recipients,
        region.getPRId(), response, internal, fetchOfflineMembers, probe);
    msg.setTransactionDistributed(region.getCache().getTxManager().isDistributed());

    /* Set<InternalDistributedMember> failures = */
    region.getDistributionManager().putOutgoing(msg);

    region.getPrStats().incPartitionMessagesSent();
    return response;
  }

  public FetchPartitionDetailsMessage(DataInput in) throws IOException, ClassNotFoundException {
    fromData(in, InternalDataSerializer.createDeserializationContext(in));
  }

  @Override
  public boolean isSevereAlertCompatible() {
    // allow forced-disconnect processing for all cache op messages
    return true;
  }

  @Override
  protected boolean operateOnPartitionedRegion(ClusterDistributionManager dm,
      PartitionedRegion region, long startTime) throws ForceReattemptException {

    PartitionMemberInfoImpl details = (PartitionMemberInfoImpl) region.getRedundancyProvider()
        .buildPartitionMemberDetails(internal, loadProbe);
    OfflineMemberDetails offlineDetails;
    if (internal && fetchOfflineMembers) {
      offlineDetails = region.getRedundancyProvider().fetchOfflineMembers();
    } else {
      offlineDetails = new OfflineMemberDetailsImpl(new Set[0]);
    }
    region.getPrStats().endPartitionMessagesProcessing(startTime);
    FetchPartitionDetailsReplyMessage.send(getSender(), getProcessorId(), details, dm,
        offlineDetails, null);

    // Unless there was an exception thrown, this message handles sending the
    // response
    return false;
  }

  @Override
  protected void appendFields(StringBuilder buff) {
    super.appendFields(buff);
    buff.append("; internal=").append(internal);
  }

  @Override
  public int getDSFID() {
    return PR_FETCH_PARTITION_DETAILS_MESSAGE;
  }

  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    super.fromData(in, context);
    internal = in.readBoolean();
    fetchOfflineMembers = in.readBoolean();
    loadProbe = DataSerializer.readObject(in);
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    super.toData(out, context);
    out.writeBoolean(internal);
    out.writeBoolean(fetchOfflineMembers);
    DataSerializer.writeObject(loadProbe, out);
  }

  public static class FetchPartitionDetailsReplyMessage extends ReplyMessage {

    static final byte NO_PARTITION = 0;
    static final byte OK = 1;
    static final byte OK_INTERNAL = 2;

    private long configuredMaxMemory;
    private long size;
    private int bucketCount;
    private int primaryCount;
    private PRLoad prLoad;
    private long[] bucketSizes;
    private OfflineMemberDetails offlineDetails;

    /**
     * Empty constructor to conform to DataSerializable interface
     */
    public FetchPartitionDetailsReplyMessage() {}

    public FetchPartitionDetailsReplyMessage(DataInput in)
        throws IOException, ClassNotFoundException {
      fromData(in, InternalDataSerializer.createDeserializationContext(in));
    }

    private FetchPartitionDetailsReplyMessage(int processorId, PartitionMemberInfoImpl details,
        OfflineMemberDetails offlineDetails, ReplyException re) {
      this.processorId = processorId;

      configuredMaxMemory = details.getConfiguredMaxMemory();
      size = details.getSize();
      bucketCount = details.getBucketCount();
      primaryCount = details.getPrimaryCount();
      prLoad = details.getPRLoad();
      bucketSizes = details.getBucketSizes();
      this.offlineDetails = offlineDetails;

      setException(re);
    }

    /**
     * Send an ack
     *
     */
    public static void send(InternalDistributedMember recipient, int processorId,
        PartitionMemberInfoImpl details, DistributionManager dm,
        OfflineMemberDetails offlineDetails, ReplyException re) {
      Assert.assertTrue(recipient != null, "FetchPartitionDetailsReplyMessage NULL recipient");
      FetchPartitionDetailsReplyMessage m =
          new FetchPartitionDetailsReplyMessage(processorId, details, offlineDetails, re);
      m.setRecipient(recipient);
      dm.putOutgoing(m);
    }

    @Override
    public void process(final DistributionManager dm, final ReplyProcessor21 processor) {
      final long startTime = getTimestamp();
      if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
        logger.trace(LogMarker.DM_VERBOSE,
            "FetchPartitionDetailsReplyMessage process invoking reply processor with processorId: {}",
            processorId);
      }

      if (processor == null) {
        if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
          logger.trace(LogMarker.DM_VERBOSE,
              "FetchPartitionDetailsReplyMessage processor not found");
        }
        return;
      }
      processor.process(this);

      if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
        logger.trace(LogMarker.DM_VERBOSE, "{} processed {}", processor, this);
      }
      dm.getStats().incReplyMessageTime(NanoTimer.getTime() - startTime);
    }

    InternalPartitionDetails unmarshalPartitionMemberDetails() {
      if (configuredMaxMemory == 0) {
        return null;
      } else {
        if (prLoad == null) {
          return new PartitionMemberInfoImpl(getSender(), configuredMaxMemory, size,
              bucketCount, primaryCount);
        } else {
          return new PartitionMemberInfoImpl(getSender(), configuredMaxMemory, size,
              bucketCount, primaryCount, prLoad, bucketSizes);
        }
      }
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      super.toData(out, context);
      if (configuredMaxMemory == 0) {
        out.writeByte(NO_PARTITION);
      } else {
        if (prLoad == null) {
          out.writeByte(OK);
        } else {
          out.writeByte(OK_INTERNAL);
        }

        out.writeLong(configuredMaxMemory);
        out.writeLong(size);
        out.writeInt(bucketCount);
        out.writeInt(primaryCount);
        if (prLoad != null) {
          InternalDataSerializer.invokeToData(prLoad, out);
          DataSerializer.writeLongArray(bucketSizes, out);
          InternalDataSerializer.invokeToData(offlineDetails, out);
        }
      }
    }

    @Override
    public int getDSFID() {
      return PR_FETCH_PARTITION_DETAILS_REPLY;
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {
      super.fromData(in, context);
      byte flag = in.readByte();
      if (flag != NO_PARTITION) {
        configuredMaxMemory = in.readLong();
        size = in.readLong();
        bucketCount = in.readInt();
        primaryCount = in.readInt();
        if (flag == OK_INTERNAL) {
          prLoad = PRLoad.createFromDataInput(in);
          bucketSizes = DataSerializer.readLongArray(in);
          offlineDetails = new OfflineMemberDetailsImpl();
          InternalDataSerializer.invokeFromData(offlineDetails, in);
        }
      }
    }

    @Override
    public String toString() {
      return "FetchPartitionDetailsReplyMessage " + "processorid="
          + processorId + " reply to sender " + getSender()
          + " returning configuredMaxMemory=" + configuredMaxMemory
          + " size=" + size + " bucketCount=" + bucketCount
          + " primaryCount=" + primaryCount + " prLoad=" + prLoad
          + " bucketSizes=" + Arrays.toString(bucketSizes);
    }
  }

  /**
   * A processor to capture the value returned by
   * {@link org.apache.geode.internal.cache.partitioned.FetchPartitionDetailsMessage.FetchPartitionDetailsReplyMessage}
   *
   */
  public static class FetchPartitionDetailsResponse extends PartitionResponse {

    private final Set<InternalPartitionDetails> allDetails =
        new HashSet<>();
    private OfflineMemberDetails offlineDetails;

    final PartitionedRegion partitionedRegion;

    public FetchPartitionDetailsResponse(InternalDistributedSystem ds,
        Set<InternalDistributedMember> recipients, PartitionedRegion theRegion) {
      super(ds, recipients);
      partitionedRegion = theRegion;
    }

    @Override
    public void process(DistributionMessage msg) {
      try {
        if (msg instanceof FetchPartitionDetailsReplyMessage) {
          FetchPartitionDetailsReplyMessage reply = (FetchPartitionDetailsReplyMessage) msg;
          InternalPartitionDetails details = reply.unmarshalPartitionMemberDetails();
          if (details != null) {
            synchronized (allDetails) {
              allDetails.add(details);
              // This just picks the offline details from the last member to return
              offlineDetails = reply.offlineDetails;
            }
            if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
              logger.trace(LogMarker.DM_VERBOSE,
                  "FetchPartitionDetailsResponse return details is {}", details);
            }
          } else if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
            logger.trace(LogMarker.DM_VERBOSE,
                "FetchPartitionDetailsResponse ignoring null details");
          }
        }
      } finally {
        super.process(msg);
      }
    }

    /**
     * Ignore any incoming exception from other VMs, we just want an acknowledgement that the
     * message was processed.
     */
    @Override
    protected synchronized void processException(ReplyException ex) {
      logger.debug("FetchPartitionDetailsResponse ignoring exception {}", ex.getMessage(), ex);
    }

    /**
     * @return set of all PartitionMemberDetails
     */
    public Set<InternalPartitionDetails> waitForResponse() {
      waitForRepliesUninterruptibly();
      synchronized (allDetails) {
        return allDetails;
      }
    }

    public OfflineMemberDetails getOfflineMembers() {
      return offlineDetails;
    }
  }

}
