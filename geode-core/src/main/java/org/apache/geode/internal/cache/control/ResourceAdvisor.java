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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Logger;

import org.apache.geode.CancelException;
import org.apache.geode.SystemFailure;
import org.apache.geode.cache.control.ResourceManager;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.DistributionAdvisee;
import org.apache.geode.distributed.internal.DistributionAdvisor;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.HighPriorityDistributionMessage;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.UpdateAttributesProcessor;
import org.apache.geode.internal.cache.control.InternalResourceManager.ResourceType;
import org.apache.geode.internal.cache.control.MemoryThresholds.MemoryState;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * The advisor associated with a {@link ResourceManager}. Allows knowledge of remote
 * {@link ResourceManager} state and distribution of local {@link ResourceManager} state.
 *
 * @since GemFire 6.0
 */
public class ResourceAdvisor extends DistributionAdvisor {
  private static final Logger logger = LogService.getLogger();

  /**
   * Message used to push event updates to remote VMs
   */
  public static class ResourceProfileMessage extends HighPriorityDistributionMessage {
    // As of 9.0 this message will only ever have a single profile.
    // But to be compatible with previous releases we still support
    // multiple profiles so that we can handle these messages during
    // a rolling upgrade.
    private volatile ResourceManagerProfile[] profiles;
    private volatile int processorId;

    /**
     * Default constructor used for de-serialization (used during receipt)
     */
    public ResourceProfileMessage() {}

    /**
     * Constructor used to send profiles to other members.
     *
     * @param recips Members to send the profile to.
     * @param profile Profile to send.
     */
    private ResourceProfileMessage(final Set<InternalDistributedMember> recips,
        final ResourceManagerProfile profile) {
      setRecipients(recips);
      processorId = 0;
      profiles = new ResourceManagerProfile[] {profile};
    }

    @Override
    protected void process(ClusterDistributionManager dm) {
      Throwable thr = null;
      ResourceManagerProfile p = null;
      try {
        final InternalCache cache = dm.getCache();
        if (cache != null && !cache.isClosed()) {
          final ResourceAdvisor ra = cache.getInternalResourceManager().getResourceAdvisor();
          if (profiles != null) {
            // Early reply to avoid waiting for the following putProfile call
            // to fire (remote) listeners so that the origin member can proceed with
            // firing its (local) listeners

            for (final ResourceManagerProfile profile : profiles) {
              ra.putProfile(profile);
            }
          }
        } else {
          if (logger.isDebugEnabled()) {
            logger.debug("No cache: {}", this);
          }
        }
      } catch (CancelException ignore) {
        if (logger.isDebugEnabled()) {
          logger.debug("Cache closed: {}", this);
        }
      } catch (VirtualMachineError err) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      } catch (Throwable t) {
        // Whenever you catch Error or Throwable, you must also
        // catch VirtualMachineError (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        thr = t;
      } finally {
        if (thr != null) {
          dm.getCancelCriterion().checkCancelInProgress(null);
          logger.info(String.format("This member caught exception processing profile %s %s",
              p, this),
              thr);
        }
      }
    }

    @Override
    public int getDSFID() {
      return RESOURCE_PROFILE_MESSAGE;
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {
      super.fromData(in, context);
      processorId = in.readInt();
      final int l = in.readInt();
      if (l != -1) {
        profiles = new ResourceManagerProfile[l];
        for (int i = 0; i < profiles.length; i++) {
          final ResourceManagerProfile r = new ResourceManagerProfile();
          InternalDataSerializer.invokeFromData(r, in);
          profiles[i] = r;
        }
      } else {
        profiles = null;
      }
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      super.toData(out, context);
      out.writeInt(processorId);
      if (profiles != null) {
        out.writeInt(profiles.length);
        for (final ResourceManagerProfile profile : profiles) {
          InternalDataSerializer.invokeToData(profile, out);
        }
      } else {
        out.writeInt(-1);
      }
    }

    /**
     * Send profiles to the provided members
     *
     * @param irm The resource manager which is requesting distribution
     * @param recips The recipients of the message
     * @param profile Profile to send in this message
     */
    public static void send(final InternalResourceManager irm,
        Set<InternalDistributedMember> recips, ResourceManagerProfile profile) {
      final DistributionManager dm = irm.getResourceAdvisor().getDistributionManager();
      ResourceProfileMessage r = new ResourceProfileMessage(recips, profile);
      dm.putOutgoing(r);
    }

    @Override
    public String getShortClassName() {
      return "ResourceProfileMessage";
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(getShortClassName()).append(" (processorId=").append(processorId)
          .append("; profiles=[");
      for (int i = 0; i < profiles.length; i++) {
        sb.append(profiles[i]);
        if (i < profiles.length - 1) {
          sb.append(", ");
        }
      }
      sb.append("]");
      sb.append(")");
      return sb.toString();
    }
  }

  /**
   * @param advisee The owner of this advisor
   * @see ResourceManager
   */
  private ResourceAdvisor(DistributionAdvisee advisee) {
    super(advisee);
  }

  public static ResourceAdvisor createResourceAdvisor(DistributionAdvisee advisee) {
    ResourceAdvisor advisor = new ResourceAdvisor(advisee);
    advisor.initialize();
    return advisor;
  }

  @Override
  protected Profile instantiateProfile(InternalDistributedMember memberId, int version) {
    return new ResourceManagerProfile(memberId, version);
  }

  private InternalResourceManager getResourceManager() {
    return ((InternalCache) getAdvisee()).getInternalResourceManager(false);
  }

  @SuppressWarnings("synthetic-access")
  @Override
  protected boolean evaluateProfiles(final Profile newProfile, final Profile oldProfile) {

    ResourceManagerProfile oldRMProfile = (ResourceManagerProfile) oldProfile;
    ResourceManagerProfile newRMProfile = (ResourceManagerProfile) newProfile;

    List<ResourceEvent> eventsToDeliver = new ArrayList<>();

    if (oldRMProfile == null) {
      eventsToDeliver.add(new MemoryEvent(ResourceType.HEAP_MEMORY, MemoryState.DISABLED,
          newRMProfile.heapState, newRMProfile.getDistributedMember(), newRMProfile.heapBytesUsed,
          false, newRMProfile.heapThresholds));
      eventsToDeliver.add(new MemoryEvent(ResourceType.OFFHEAP_MEMORY, MemoryState.DISABLED,
          newRMProfile.offHeapState, newRMProfile.getDistributedMember(),
          newRMProfile.offHeapBytesUsed, false, newRMProfile.offHeapThresholds));

    } else {
      if (oldRMProfile.heapState != newRMProfile.heapState) {
        eventsToDeliver.add(new MemoryEvent(ResourceType.HEAP_MEMORY, oldRMProfile.heapState,
            newRMProfile.heapState, newRMProfile.getDistributedMember(), newRMProfile.heapBytesUsed,
            false, newRMProfile.heapThresholds));
      }

      if (newRMProfile.heapState == MemoryState.DISABLED) {
        newRMProfile.setHeapData(oldRMProfile.heapBytesUsed, oldRMProfile.heapState,
            oldRMProfile.heapThresholds);
      }

      if (oldRMProfile.offHeapState != newRMProfile.offHeapState) {
        eventsToDeliver.add(new MemoryEvent(ResourceType.OFFHEAP_MEMORY, oldRMProfile.offHeapState,
            newRMProfile.offHeapState, newRMProfile.getDistributedMember(),
            newRMProfile.offHeapBytesUsed, false, newRMProfile.offHeapThresholds));
      }

      if (newRMProfile.offHeapState == MemoryState.DISABLED) {
        newRMProfile.setOffHeapData(oldRMProfile.offHeapBytesUsed, oldRMProfile.offHeapState,
            oldRMProfile.offHeapThresholds);
      }
    }

    for (ResourceEvent event : eventsToDeliver) {
      getResourceManager().deliverEventFromRemote(event);
    }

    return true;
  }

  @Override
  public String toString() {
    return "ResourceAdvisor for ResourceManager " + getAdvisee();
  }

  /**
   * Profile which shares state with other ResourceManagers. The data available in this profile
   * should be enough to deliver a {@link MemoryEvent} for any of the CRITICAL {@link MemoryState}s
   *
   * @since GemFire 6.0
   */
  public static class ResourceManagerProfile extends Profile {
    // Resource manager related fields
    private long heapBytesUsed;
    private MemoryState heapState;
    private MemoryThresholds heapThresholds;

    private long offHeapBytesUsed;
    private MemoryState offHeapState;
    private MemoryThresholds offHeapThresholds;

    // Constructor for de-serialization
    public ResourceManagerProfile() {}

    // Constructor for sending purposes
    public ResourceManagerProfile(InternalDistributedMember memberId, int version) {
      super(memberId, version);
    }

    public synchronized ResourceManagerProfile setHeapData(final long heapBytesUsed,
        final MemoryState heapState, final MemoryThresholds heapThresholds) {
      this.heapBytesUsed = heapBytesUsed;
      this.heapState = heapState;
      this.heapThresholds = heapThresholds;
      return this;
    }

    public synchronized ResourceManagerProfile setOffHeapData(final long offHeapBytesUsed,
        final MemoryState offHeapState, final MemoryThresholds offHeapThresholds) {
      this.offHeapBytesUsed = offHeapBytesUsed;
      this.offHeapState = offHeapState;
      this.offHeapThresholds = offHeapThresholds;
      return this;
    }

    public synchronized MemoryEvent createDisabledMemoryEvent(ResourceType resourceType) {
      if (resourceType == ResourceType.HEAP_MEMORY) {
        return new MemoryEvent(ResourceType.HEAP_MEMORY, heapState, MemoryState.DISABLED,
            getDistributedMember(), heapBytesUsed, false, heapThresholds);
      }

      return new MemoryEvent(ResourceType.OFFHEAP_MEMORY, offHeapState, MemoryState.DISABLED,
          getDistributedMember(), offHeapBytesUsed, false, offHeapThresholds);
    }

    /**
     * Used to process incoming Resource Manager profiles. A reply is expected to contain a profile
     * with state of the local Resource Manager.
     *
     * @since GemFire 6.0
     */
    @Override
    public void processIncoming(ClusterDistributionManager dm, String adviseePath,
        boolean removeProfile, boolean exchangeProfiles, final List<Profile> replyProfiles) {
      final InternalCache cache = dm.getCache();
      if (cache != null && !cache.isClosed()) {
        handleDistributionAdvisee((DistributionAdvisee) cache, removeProfile, exchangeProfiles,
            replyProfiles);
      }
    }

    @Override
    public StringBuilder getToStringHeader() {
      return new StringBuilder("ResourceAdvisor.ResourceManagerProfile");
    }

    @Override
    public void fillInToString(StringBuilder sb) {
      super.fillInToString(sb);
      synchronized (this) {
        sb.append("; heapState=").append(heapState).append("; heapBytesUsed=")
            .append(heapBytesUsed).append("; heapThresholds=").append(heapThresholds)
            .append("; offHeapState=").append(offHeapState).append("; offHeapBytesUsed=")
            .append(offHeapBytesUsed).append("; offHeapThresholds=")
            .append(offHeapThresholds);
      }
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException, ClassNotFoundException {
      super.fromData(in, context);

      final long heapBytesUsed = in.readLong();
      MemoryState heapState = MemoryState.fromData(in);
      MemoryThresholds heapThresholds = MemoryThresholds.fromData(in);
      setHeapData(heapBytesUsed, heapState, heapThresholds);

      final long offHeapBytesUsed = in.readLong();
      MemoryState offHeapState = MemoryState.fromData(in);
      MemoryThresholds offHeapThresholds = MemoryThresholds.fromData(in);
      setOffHeapData(offHeapBytesUsed, offHeapState, offHeapThresholds);
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      final long heapBytesUsed;
      final MemoryState heapState;
      final MemoryThresholds heapThresholds;
      final long offHeapBytesUsed;
      final MemoryState offHeapState;
      final MemoryThresholds offHeapThresholds;
      synchronized (this) {
        heapBytesUsed = this.heapBytesUsed;
        heapState = this.heapState;
        heapThresholds = this.heapThresholds;

        offHeapBytesUsed = this.offHeapBytesUsed;
        offHeapState = this.offHeapState;
        offHeapThresholds = this.offHeapThresholds;
      }
      super.toData(out, context);

      out.writeLong(heapBytesUsed);
      heapState.toData(out);
      heapThresholds.toData(out);

      out.writeLong(offHeapBytesUsed);
      offHeapState.toData(out);
      offHeapThresholds.toData(out);
    }

    @Override
    public int getDSFID() {
      return RESOURCE_MANAGER_PROFILE;
    }

    public synchronized MemoryState getHeapState() {
      return heapState;
    }

    public synchronized MemoryState getoffHeapState() {
      return offHeapState;
    }
  }

  /**
   * Get set of members whose {@linkplain ResourceManager#setCriticalHeapPercentage(float) critical
   * heap threshold} has been met or exceeded. The set does not include the local VM. The mutability
   * of this set only effects the elements in the set, not the state of the members.
   *
   * @return a mutable set of members in the critical state otherwise {@link Collections#EMPTY_SET}
   */
  public Set<InternalDistributedMember> adviseCriticalMembers() {
    return adviseFilter(profile -> {
      ResourceManagerProfile rmp = (ResourceManagerProfile) profile;
      return rmp.getHeapState().isCritical();
    });
  }

  public boolean isHeapCritical(final InternalDistributedMember member) {
    ResourceManagerProfile rmp = (ResourceManagerProfile) getProfile(member);
    return rmp != null && rmp.getHeapState().isCritical();
  }

  public synchronized void updateRemoteProfile() {
    Set<InternalDistributedMember> recips = adviseGeneric();
    ResourceManagerProfile profile =
        new ResourceManagerProfile(getDistributionManager().getId(), incrementAndGetVersion());
    getResourceManager().fillInProfile(profile);
    ResourceProfileMessage.send(getResourceManager(), recips, profile);
  }

  @Override
  protected void profileRemoved(Profile profile) {
    ResourceManagerProfile oldp = (ResourceManagerProfile) profile;
    getResourceManager()
        .deliverEventFromRemote(oldp.createDisabledMemoryEvent(ResourceType.HEAP_MEMORY));
    getResourceManager()
        .deliverEventFromRemote(oldp.createDisabledMemoryEvent(ResourceType.OFFHEAP_MEMORY));
  }

  @Override
  public void close() {
    new UpdateAttributesProcessor(getAdvisee(), true).distribute(false);
    super.close();
  }
}
