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
package org.apache.geode.internal.cache;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.geode.DataSerializer;
import org.apache.geode.InternalGemFireException;
import org.apache.geode.annotations.Immutable;
import org.apache.geode.cache.CacheEvent;
import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.Operation;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.OperationExecutors;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.cache.FilterRoutingInfo.FilterInfo;
import org.apache.geode.internal.cache.persistence.DiskStoreID;
import org.apache.geode.internal.cache.versions.VersionSource;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.internal.serialization.SerializationVersions;

public class DistributedTombstoneOperation extends DistributedCacheOperation {
  private enum TOperation {
    GC,
  }

  // private long regionVersion;
  private final Map<VersionSource<?>, Long> regionGCVersions;

  private TOperation op;

  public static DistributedTombstoneOperation gc(DistributedRegion region, EventID eventId) {
    RegionEventImpl rev =
        new RegionEventImpl(region, Operation.REGION_EXPIRE_DESTROY, null, false, region.getMyId());
    rev.setEventID(eventId);
    DistributedTombstoneOperation top = new DistributedTombstoneOperation(rev);
    top.op = TOperation.GC;
    return top;
  }

  private DistributedTombstoneOperation(RegionEventImpl rev) {
    super(rev);
    // this.regionVersion =
    // ((DistributedRegion)rev.getRegion()).getVersionVector().getMaxTombstoneGCVersion();
    regionGCVersions =
        ((DistributedRegion) rev.getRegion()).getVersionVector().getTombstoneGCVector();
  }

  @Override
  protected boolean supportsAdjunctMessaging() {
    return false;
  }

  @Override
  boolean isOperationReliable() {
    return false; // no need to wait for required roles
  }

  @Override
  protected CacheOperationMessage createMessage() {
    TombstoneMessage mssg = new TombstoneMessage();
    // mssg.regionVersion = this.regionVersion;
    mssg.regionGCVersions = regionGCVersions;
    mssg.eventID = event.getEventId();
    mssg.op = op;
    return mssg;
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.geode.internal.cache.DistributedCacheOperation#getLocalFilterRouting(org.apache.
   * geode.internal.cache.FilterRoutingInfo)
   */
  @Override
  protected FilterInfo getLocalFilterRouting(FilterRoutingInfo frInfo) {
    // fix for bug #47494, CQs destroyed by Distributed GC. CQs remove
    // the queries for a region if they see a destroy-region operation, which
    // this message uses in order not to create a customer-visible
    // GC operation
    return null;
  }

  @Override
  protected Set getRecipients() {
    CacheDistributionAdvisor advisor = getRegion().getCacheDistributionAdvisor();
    return advisor.adviseInvalidateRegion();
  }

  /**
   * returns the region versions sent to other members for tombstone collection
   */
  public Map<VersionSource<?>, Long> getRegionGCVersions() {
    return regionGCVersions;
  }

  @Override
  public boolean supportsDirectAck() {
    // Set to false to force TombstoneMessage to use shared connection w/o in-line processing
    return false;
  }

  public static class TombstoneMessage extends CacheOperationMessage
      implements SerializationVersions {
    // protected long regionVersion;
    protected Map<VersionSource<?>, Long> regionGCVersions;
    protected TOperation op;
    protected EventID eventID;

    @Immutable
    private static final KnownVersion[] serializationVersions = null; // new Version[]{ };

    /**
     * for deserialization
     */
    public TombstoneMessage() {}

    @Override
    public int getProcessorType() {
      // Set to STANDARD to keep it from being processed in-line
      return OperationExecutors.STANDARD_EXECUTOR;
    }

    @Override
    protected InternalCacheEvent createEvent(DistributedRegion rgn) throws EntryNotFoundException {
      RegionEventImpl event = createRegionEvent(rgn);
      event.setEventID(eventID);
      return event;
    }

    protected RegionEventImpl createRegionEvent(DistributedRegion rgn) {
      RegionEventImpl event = new RegionEventImpl(rgn, getOperation(), callbackArg,
          true /* originRemote */, getSender());
      event.setEventID(eventID);
      return event;
    }

    @Override
    protected boolean operateOnRegion(CacheEvent event, ClusterDistributionManager dm)
        throws EntryNotFoundException {
      boolean sendReply = true;

      DistributedRegion region = (DistributedRegion) event.getRegion();
      region.getCachePerfStats().incTombstoneGCCount();
      FilterInfo routing = null;
      if (filterRouting != null) {
        routing = filterRouting.getFilterInfo(region.getMyId());
      }

      region.expireTombstones(regionGCVersions, eventID, routing);
      appliedOperation = true;
      return sendReply;
    }

    @Override
    public int getDSFID() {
      return TOMBSTONE_MESSAGE;
    }

    @Override
    public KnownVersion[] getSerializationVersions() {
      return serializationVersions;
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {
      super.fromData(in, context);
      op = TOperation.values()[in.readByte()];
      // this.regionVersion = in.readLong();
      int count = in.readInt();
      regionGCVersions = new HashMap<>(count);
      boolean persistent = in.readBoolean();
      for (int i = 0; i < count; i++) {
        VersionSource mbr;
        if (persistent) {
          DiskStoreID id = new DiskStoreID();
          InternalDataSerializer.invokeFromData(id, in);
          mbr = id;
        } else {
          mbr = InternalDistributedMember.readEssentialData(in);
        }
        regionGCVersions.put(mbr, in.readLong());
      }
      eventID = DataSerializer.readObject(in);
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      super.toData(out, context);
      out.writeByte(op.ordinal());
      // out.writeLong(this.regionVersion);
      out.writeInt(regionGCVersions.size());
      boolean persistent = false;
      String msg = "Found mixed membership ids while serializing Tombstone GC message.";
      if (!regionGCVersions.isEmpty()) {
        VersionSource firstEntry = regionGCVersions.keySet().iterator().next();
        if (firstEntry instanceof DiskStoreID) {
          persistent = true;
        }
      }
      out.writeBoolean(persistent);
      for (Map.Entry<VersionSource<?>, Long> entry : regionGCVersions.entrySet()) {
        VersionSource member = entry.getKey();
        if (member instanceof DiskStoreID) {
          if (!persistent) {
            throw new InternalGemFireException(msg);
          }
          InternalDataSerializer.invokeToData(member, out);
        } else {
          if (persistent) {
            throw new InternalGemFireException(msg);
          }
          member.writeEssentialData(out);
        }
        out.writeLong(entry.getValue());
      }
      DataSerializer.writeObject(eventID, out);
    }

    @Override
    protected void appendFields(StringBuilder buff) {
      super.appendFields(buff);
      buff.append("; op=").append(op);
      buff.append("; eventID=").append(eventID);
      buff.append("; regionGCVersions=").append(regionGCVersions);
    }

  }

}
