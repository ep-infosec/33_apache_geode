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
import java.util.Collections;
import java.util.Set;

import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.CacheWriterException;
import org.apache.geode.cache.EntryExistsException;
import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.Operation;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.DirectReplyProcessor;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.ReplyException;
import org.apache.geode.distributed.internal.ReplyMessage;
import org.apache.geode.distributed.internal.ReplyProcessor21;
import org.apache.geode.distributed.internal.ReplySender;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.NanoTimer;
import org.apache.geode.internal.cache.DataLocationException;
import org.apache.geode.internal.cache.EntryEventImpl;
import org.apache.geode.internal.cache.EnumListenerEvent;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.FilterRoutingInfo;
import org.apache.geode.internal.cache.ForceReattemptException;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.PartitionedRegionDataStore;
import org.apache.geode.internal.cache.PartitionedRegionHelper;
import org.apache.geode.internal.cache.PrimaryBucketException;
import org.apache.geode.internal.cache.tier.sockets.ClientProxyMembershipID;
import org.apache.geode.internal.cache.versions.DiskVersionTag;
import org.apache.geode.internal.cache.versions.VersionTag;
import org.apache.geode.internal.logging.log4j.LogMarker;
import org.apache.geode.internal.offheap.annotations.Released;
import org.apache.geode.internal.offheap.annotations.Retained;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * A class that specifies a destroy operation.
 *
 * Note: The reason for different classes for Destroy and Invalidate is to prevent sending an extra
 * bit for every DestroyMessage to differentiate an invalidate versus a destroy. The assumption is
 * that these operations are used frequently, if they are not then it makes sense to fold the
 * destroy and the invalidate into the same message and use an extra bit to differentiate
 *
 * @since GemFire 5.0
 *
 */
public class DestroyMessage extends PartitionMessageWithDirectReply {

  private static final Logger logger = LogService.getLogger();

  /** The key associated with the value that must be sent */
  private Object key;

  /** The callback arg */
  private Object cbArg;

  /** The operation performed on the sender */
  private Operation op;

  /**
   * An additional object providing context for the operation, e.g., for BridgeServer notification
   */
  ClientProxyMembershipID bridgeContext;

  /** event identifier */
  EventID eventId;

  /** for relayed messages, this is the original sender of the message */
  InternalDistributedMember originalSender;

  /** expectedOldValue used for PartitionedRegion#remove(key, value) */
  private Object expectedOldValue;

  /** client routing information for notificationOnly=true messages */
  protected FilterRoutingInfo filterInfo;

  protected VersionTag versionTag;

  private static final byte HAS_VERSION_TAG = 0x01;
  private static final byte PERSISTENT_TAG = 0x02;

  // additional bitmask flags used for serialization/deserialization

  protected static final short CACHE_WRITE = UNRESERVED_FLAGS_START;

  /**
   * Empty constructor to satisfy {@link DataSerializer} requirements
   */
  public DestroyMessage() {}

  protected DestroyMessage(Set recipients, boolean notifyOnly, int regionId,
      DirectReplyProcessor processor, EntryEventImpl event, Object expectedOldValue) {
    super(recipients, regionId, processor, event);
    this.expectedOldValue = expectedOldValue;
    key = event.getKey();
    cbArg = event.getRawCallbackArgument();
    op = event.getOperation();
    notificationOnly = notifyOnly;
    bridgeContext = event.getContext();
    eventId = event.getEventId();
    versionTag = event.getVersionTag();
  }

  /** a cloning constructor for relaying the message to listeners */
  DestroyMessage(DestroyMessage original, EntryEventImpl event, Set members) {
    this(original);
    if (event != null) {
      posDup = event.isPossibleDuplicate();
      versionTag = event.getVersionTag();
    }
  }

  /** a cloning constructor for relaying the message to listeners */
  DestroyMessage(DestroyMessage original) {
    expectedOldValue = original.expectedOldValue;
    regionId = original.regionId;
    processorId = original.processorId;
    key = original.key;
    cbArg = original.cbArg;
    op = original.op;
    notificationOnly = true;
    bridgeContext = original.bridgeContext;
    originalSender = original.getSender();
    // Assert.assertTrue(original.eventId != null); bug #47235 - region invalidation has no event
    // id, so this fails
    eventId = original.eventId;
    posDup = original.posDup;
    versionTag = original.versionTag;
  }

  @Override
  public boolean isSevereAlertCompatible() {
    // allow forced-disconnect processing for all cache op messages
    return true;
  }

  /**
   * send a notification-only message to a set of listeners. The processor id is passed with the
   * message for reply message processing. This method does not wait on the processor.
   *
   * @param cacheOpReceivers receivers of associated bucket CacheOperationMessage
   * @param adjunctRecipients receivers that must get the event
   * @param filterRoutingInfo client routing information
   * @param r the region affected by the event
   * @param event the event that prompted this action
   * @param processor the processor to reply to
   * @return members that could not be notified
   */
  public static Set notifyListeners(Set cacheOpReceivers, Set adjunctRecipients,
      FilterRoutingInfo filterRoutingInfo, PartitionedRegion r, EntryEventImpl event,
      DirectReplyProcessor processor) {
    DestroyMessage msg =
        new DestroyMessage(Collections.EMPTY_SET, true, r.getPRId(), processor, event, null);
    msg.setTransactionDistributed(r.getCache().getTxManager().isDistributed());
    msg.versionTag = event.getVersionTag();
    return msg.relayToListeners(cacheOpReceivers, adjunctRecipients, filterRoutingInfo, event, r,
        processor);
  }


  /**
   * Sends a DestroyMessage {@link org.apache.geode.cache.Region#destroy(Object)}message to the
   * recipient
   *
   * @param recipient the recipient of the message
   * @param r the PartitionedRegion for which the destroy was performed
   * @param event the event causing this message
   * @return the processor used to await the potential {@link org.apache.geode.cache.CacheException}
   * @throws ForceReattemptException if the peer is no longer available
   */
  public static DestroyResponse send(DistributedMember recipient, PartitionedRegion r,
      EntryEventImpl event, Object expectedOldValue) throws ForceReattemptException {
    // Assert.assertTrue(recipient != null, "DestroyMessage NULL recipient"); recipient may be null
    // for event notification
    Set recipients = Collections.singleton(recipient);
    DestroyResponse p = new DestroyResponse(r.getSystem(), recipients, false);
    p.requireResponse();
    DestroyMessage m =
        new DestroyMessage(recipients, false, r.getPRId(), p, event, expectedOldValue);
    m.setTransactionDistributed(r.getCache().getTxManager().isDistributed());
    Set failures = r.getDistributionManager().putOutgoing(m);
    if (failures != null && failures.size() > 0) {
      throw new ForceReattemptException(
          String.format("Failed sending < %s >", m));
    }
    return p;
  }

  @Override
  public PartitionMessage getMessageForRelayToListeners(EntryEventImpl event, Set members) {
    DestroyMessage msg = new DestroyMessage(this, event, members);
    // Fix for 43000 - don't send the expected old value to listeners.
    msg.expectedOldValue = null;
    return msg;
  }


  /**
   * This method is called upon receipt and make the desired changes to the PartitionedRegion Note:
   * It is very important that this message does NOT cause any deadlocks as the sender will wait
   * indefinitely for the acknowledgement
   */
  @Override
  protected boolean operateOnPartitionedRegion(ClusterDistributionManager dm, PartitionedRegion r,
      long startTime) throws EntryExistsException, DataLocationException {
    InternalDistributedMember eventSender = originalSender;
    if (eventSender == null) {
      eventSender = getSender();
    }
    @Released
    EntryEventImpl event = null;
    try {
      if (bridgeContext != null) {
        event = EntryEventImpl.create(r, getOperation(), key, null/* newValue */,
            getCallbackArg(), false/* originRemote */, eventSender, true/* generateCallbacks */);
        event.setContext(bridgeContext);
      } // bridgeContext != null
      else {
        event = EntryEventImpl.create(r, getOperation(), key, null, /* newValue */
            getCallbackArg(), false/* originRemote - false to force distribution in buckets */,
            eventSender, true/* generateCallbacks */, false/* initializeId */);
      }
      if (versionTag != null) {
        versionTag.replaceNullIDs(getSender());
        event.setVersionTag(versionTag);
      }
      event.setInvokePRCallbacks(!notificationOnly);
      Assert.assertTrue(eventId != null);
      event.setEventId(eventId);
      event.setPossibleDuplicate(posDup);

      PartitionedRegionDataStore ds = r.getDataStore();
      boolean sendReply = true;

      if (!notificationOnly) {
        Assert.assertTrue(ds != null,
            "This process should have storage for an item in " + this);
        try {
          Integer bucket = PartitionedRegionHelper.getHashKey(r, null, key, null, cbArg);
          event.setCausedByMessage(this);
          r.getDataView().destroyOnRemote(event, true/* cacheWrite */, expectedOldValue);
          if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
            logger.trace(LogMarker.DM_VERBOSE, "{} updated bucket: {} with key: {}",
                getClass().getName(), bucket, key);
          }
        } catch (CacheWriterException cwe) {
          sendReply(getSender(), processorId, dm, new ReplyException(cwe), r, startTime);
          return false;
        } catch (EntryNotFoundException eee) {
          logger.trace(LogMarker.DM_VERBOSE, "{}: operateOnRegion caught EntryNotFoundException",
              getClass().getName());
          ReplyMessage.send(getSender(), getProcessorId(), new ReplyException(eee),
              getReplySender(dm), r.isInternalRegion());
          sendReply = false; // this prevents us from acking later
        } catch (PrimaryBucketException pbe) {
          sendReply(getSender(), getProcessorId(), dm, new ReplyException(pbe), r, startTime);
          sendReply = false;

        } finally {
          versionTag = event.getVersionTag();
        }
      } else {
        @Released
        EntryEventImpl e2 = createListenerEvent(event, r, dm.getDistributionManagerId());
        try {
          r.invokeDestroyCallbacks(EnumListenerEvent.AFTER_DESTROY, e2, r.isInitialized(), true);
        } finally {
          // if e2 == ev then no need to free it here. The outer finally block will get it.
          if (e2 != event) {
            e2.release();
          }
        }
      }

      return sendReply;
    } finally {
      if (event != null) {
        event.release();
      }
    }
  }

  @Override
  protected void sendReply(InternalDistributedMember member, int procId, DistributionManager dm,
      ReplyException ex, PartitionedRegion pr, long startTime) {
    if (pr != null && startTime > 0) {
      pr.getPrStats().endPartitionMessagesProcessing(startTime);
    }
    if (ex == null) {
      DestroyReplyMessage.send(getSender(), getReplySender(dm), processorId, versionTag,
          pr != null && pr.isInternalRegion());
    } else {
      ReplyMessage.send(getSender(), processorId, ex, getReplySender(dm),
          pr != null && pr.isInternalRegion());
    }
  }

  @Override
  public int getDSFID() {
    return PR_DESTROY;
  }

  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    super.fromData(in, context);
    setKey(DataSerializer.readObject(in));
    cbArg = DataSerializer.readObject(in);
    op = Operation.fromOrdinal(in.readByte());
    notificationOnly = in.readBoolean();
    bridgeContext = ClientProxyMembershipID.readCanonicalized(in);
    originalSender = DataSerializer.readObject(in);
    eventId = DataSerializer.readObject(in);
    expectedOldValue = DataSerializer.readObject(in);

    final boolean hasFilterInfo = ((flags & HAS_FILTER_INFO) != 0);
    if (hasFilterInfo) {
      filterInfo = new FilterRoutingInfo();
      InternalDataSerializer.invokeFromData(filterInfo, in);
    }

    versionTag = DataSerializer.readObject(in);
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    super.toData(out, context);
    DataSerializer.writeObject(getKey(), out);
    DataSerializer.writeObject(cbArg, out);
    out.writeByte(op.ordinal);
    out.writeBoolean(notificationOnly);
    DataSerializer.writeObject(bridgeContext, out);
    DataSerializer.writeObject(originalSender, out);
    DataSerializer.writeObject(eventId, out);
    DataSerializer.writeObject(expectedOldValue, out);

    if (filterInfo != null) {
      InternalDataSerializer.invokeToData(filterInfo, out);
    }
    DataSerializer.writeObject(versionTag, out);
  }

  @Override
  protected short computeCompressedShort(short s) {
    s = super.computeCompressedShort(s);
    if (filterInfo != null) {
      s |= HAS_FILTER_INFO;
    }
    return s;
  }

  @Override
  public EventID getEventID() {
    return eventId;
  }

  /**
   * create a new EntryEvent to be used in notifying listeners, cache servers, etc. Caller must
   * release result if it is != to sourceEvent
   */
  @Retained
  EntryEventImpl createListenerEvent(EntryEventImpl sourceEvent, PartitionedRegion r,
      InternalDistributedMember member) {
    final EntryEventImpl e2;
    if (notificationOnly && bridgeContext == null) {
      e2 = sourceEvent;
    } else {
      e2 = new EntryEventImpl(sourceEvent);
      if (bridgeContext != null) {
        e2.setContext(bridgeContext);
      }
    }
    e2.setRegion(r);
    e2.setOriginRemote(true);
    e2.setInvokePRCallbacks(!notificationOnly);
    if (filterInfo != null) {
      e2.setLocalFilterInfo(filterInfo.getFilterInfo(member));
    }
    if (versionTag != null) {
      versionTag.replaceNullIDs(getSender());
      e2.setVersionTag(versionTag);
    }
    return e2;
  }

  /**
   * Assists the toString method in reporting the contents of this message
   *
   * @see PartitionMessage#toString()
   */
  @Override
  protected void appendFields(StringBuilder buff) {
    super.appendFields(buff);
    buff.append("; key=").append(getKey());
    if (originalSender != null) {
      buff.append("; originalSender=").append(originalSender);
    }
    if (bridgeContext != null) {
      buff.append("; bridgeContext=").append(bridgeContext);
    }
    if (eventId != null) {
      buff.append("; eventId=").append(eventId);
    }
    if (versionTag != null) {
      buff.append("; version=").append(versionTag);
    }
    if (filterInfo != null) {
      buff.append("; ").append(filterInfo);
    }
  }

  protected Object getKey() {
    return key;
  }

  private void setKey(Object key) {
    this.key = key;
  }

  public Operation getOperation() {
    return op;
  }

  protected Object getCallbackArg() {
    return cbArg;
  }

  @Override
  public void setFilterInfo(FilterRoutingInfo filterInfo) {
    if (filterInfo != null) {
      this.filterInfo = filterInfo;
    }
  }

  @Override
  protected boolean mayNotifySerialGatewaySender(ClusterDistributionManager dm) {
    return notifiesSerialGatewaySender(dm);
  }

  public static class DestroyReplyMessage extends ReplyMessage {
    private VersionTag versionTag;

    /** DSFIDFactory constructor */
    public DestroyReplyMessage() {}

    static void send(InternalDistributedMember recipient, ReplySender dm, int procId,
        VersionTag versionTag, boolean internal) {
      Assert.assertTrue(recipient != null, "DestroyReplyMessage NULL recipient");
      DestroyReplyMessage m = new DestroyReplyMessage(recipient, procId, versionTag);
      m.internal = internal;
      dm.putOutgoing(m);
    }

    DestroyReplyMessage(InternalDistributedMember recipient, int procId, VersionTag versionTag) {
      setProcessorId(procId);
      setRecipient(recipient);
      this.versionTag = versionTag;
    }

    @Override
    public int getDSFID() {
      return PR_DESTROY_REPLY_MESSAGE;
    }

    @Override
    public void process(final DistributionManager dm, final ReplyProcessor21 rp) {
      final long startTime = getTimestamp();
      if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
        logger.trace(LogMarker.DM_VERBOSE,
            "DestroyReplyMessage process invoking reply processor with processorId: {}",
            processorId);
      }
      // dm.getLogger().warning("RemotePutResponse processor is " +
      // ReplyProcessor21.getProcessor(this.processorId));
      if (rp == null) {
        if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
          logger.trace(LogMarker.DM_VERBOSE, "DestroyReplyMessage processor not found");
        }
        return;
      }
      if (versionTag != null) {
        versionTag.replaceNullIDs(getSender());
      }
      if (rp instanceof DestroyResponse) {
        DestroyResponse processor = (DestroyResponse) rp;
        if (versionTag != null) {
          versionTag.replaceNullIDs(getSender());
        }
        processor.setResponse(versionTag);
      }
      rp.process(this);

      if (logger.isTraceEnabled(LogMarker.DM_VERBOSE)) {
        logger.trace(LogMarker.DM_VERBOSE, "{} processed {} ", rp, this);
      }
      dm.getStats().incReplyMessageTime(NanoTimer.getTime() - startTime);
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      super.toData(out, context);
      byte b = versionTag != null ? HAS_VERSION_TAG : 0;
      b |= versionTag instanceof DiskVersionTag ? PERSISTENT_TAG : 0;
      out.writeByte(b);
      if (versionTag != null) {
        InternalDataSerializer.invokeToData(versionTag, out);
      }
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {
      super.fromData(in, context);
      byte b = in.readByte();
      boolean hasTag = (b & HAS_VERSION_TAG) != 0;
      boolean persistentTag = (b & PERSISTENT_TAG) != 0;
      if (hasTag) {
        versionTag = VersionTag.create(persistentTag, in);
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = super.getStringBuilder();
      if (versionTag != null) {
        sb.append(" version=").append(versionTag);
      }
      sb.append(" from ");
      sb.append(getSender());
      ReplyException ex = getException();
      if (ex != null) {
        sb.append(" with exception ");
        sb.append(ex);
      }
      return sb.toString();
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.geode.distributed.internal.ReplyMessage#getInlineProcess()
     */
    @Override
    public boolean getInlineProcess() {
      return true;
    }

  }
  public static class DestroyResponse extends PartitionResponse {
    VersionTag versionTag;

    DestroyResponse(InternalDistributedSystem ds, Set recipients, Object key) {
      super(ds, recipients, false);
    }

    void setResponse(VersionTag versionTag) {
      this.versionTag = versionTag;
    }

    public VersionTag getVersionTag() {
      return versionTag;
    }
  }

}
