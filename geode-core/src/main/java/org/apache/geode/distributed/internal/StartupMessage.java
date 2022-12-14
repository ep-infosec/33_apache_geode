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
package org.apache.geode.distributed.internal;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializer;
import org.apache.geode.Instantiator;
import org.apache.geode.SystemConnectException;
import org.apache.geode.distributed.internal.membership.api.StopShunningMarker;
import org.apache.geode.internal.GemFireVersion;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.InternalDataSerializer.SerializerAttributesHolder;
import org.apache.geode.internal.InternalInstantiator;
import org.apache.geode.internal.InternalInstantiator.InstantiatorAttributesHolder;
import org.apache.geode.internal.inet.LocalHostUtil;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * A message that is sent to all other distribution manager when a distribution manager starts up.
 */
public class StartupMessage extends DistributionMessage implements AdminMessageType,
    StopShunningMarker {
  private static final Logger logger = LogService.getLogger();

  private String version = GemFireVersion.getGemFireVersion(); // added for bug 29005
  private int replyProcessorId;
  private boolean isMcastEnabled;
  private boolean isTcpDisabled;
  private Set<InetAddress> interfaces;
  private int distributedSystemId;
  private String redundancyZone;
  private boolean enforceUniqueZone;

  // additional fields using StartupMessageData below here...
  private Collection<String> hostedLocatorsAll;
  boolean isSharedConfigurationEnabled;
  private int mcastPort;
  private String mcastHostAddress; // see InetAddress.getHostAddress() for the format of this string

  /**
   * Determine all of the addresses that this host represents. An empty list will be regarded as an
   * error by all who see it.
   *
   * @return list of addresses for this host
   * @since GemFire 5.7
   */
  public static Set<InetAddress> getMyAddresses(ClusterDistributionManager dm) {
    try {
      return LocalHostUtil.getMyAddresses();
    } catch (IllegalArgumentException e) {
      logger.fatal(e.getMessage(), e);
      return Collections.emptySet();
    }
  }

  /**
   * A list of errors that occurs while deserializing this message. See bug 31573.
   */
  private transient StringBuilder fromDataProblems;

  /**
   * Creates new instance for DataSerializer.
   */
  public StartupMessage() {}

  /**
   * Creates new instance for StartupOperation.
   *
   * @param isSharedConfigurationEnabled true if cluster configuration is enabled
   */
  StartupMessage(Collection<String> hostedLocators, boolean isSharedConfigurationEnabled) {
    hostedLocatorsAll = hostedLocators;
    this.isSharedConfigurationEnabled = isSharedConfigurationEnabled;
  }

  /////////////////////// Instance Methods ///////////////////////

  /**
   * Sets the reply processor for this message
   */
  void setReplyProcessorId(int proc) {
    replyProcessorId = proc;
  }

  /**
   * Sets the mcastEnabled flag for this message
   *
   * @since GemFire 5.0
   */
  void setMcastEnabled(boolean flag) {
    isMcastEnabled = flag;
  }

  int getMcastPort() {
    return mcastPort;
  }

  void setMcastPort(int port) {
    mcastPort = port;
  }

  String getMcastHostAddress() {
    return mcastHostAddress;
  }

  void setMcastHostAddress(InetAddress addr) {
    String hostAddr = null;
    if (addr != null) {
      hostAddr = addr.getHostAddress();
    }
    mcastHostAddress = hostAddr;
  }

  @Override
  public boolean sendViaUDP() {
    return true;
  }

  /**
   * Sets the tcpDisabled flag for this message
   *
   * @since GemFire 5.0
   */
  void setTcpDisabled(boolean flag) {
    isTcpDisabled = flag;
  }

  void setInterfaces(Set<InetAddress> interfaces) {
    this.interfaces = interfaces;
    if (interfaces == null || interfaces.size() == 0) {
      throw new SystemConnectException("Unable to examine network card");
    }
  }

  public void setDistributedSystemId(int distributedSystemId) {
    this.distributedSystemId = distributedSystemId;
  }

  public void setRedundancyZone(String redundancyZone) {
    this.redundancyZone = redundancyZone;
  }

  public void setEnforceUniqueZone(boolean enforceUniqueZone) {
    this.enforceUniqueZone = enforceUniqueZone;
  }

  /**
   * Adds the distribution manager that is started up to the current DM's list of members.
   *
   * This method is invoked on the receiver side
   */
  @Override
  protected void process(ClusterDistributionManager dm) {
    String rejectionMessage = null;
    boolean isAdminDM = false;
    boolean replySent = false;
    try {
      isAdminDM =
          dm.getId().getVmKind() == ClusterDistributionManager.ADMIN_ONLY_DM_TYPE
              || dm.getId().getVmKind() == ClusterDistributionManager.LOCATOR_DM_TYPE;

      if (dm.getTransport().isMcastEnabled() != isMcastEnabled) {
        rejectionMessage =
            String.format(
                "Rejected new system node %s because mcast was %s which does not match the distributed system it is attempting to join. To fix this make sure the mcast-port gemfire property is set the same on all members of the same distributed system.",

                getSender(), isMcastEnabled ? "enabled" : "disabled");
      } else if (isMcastEnabled
          && dm.getSystem().getOriginalConfig().getMcastPort() != getMcastPort()) {
        rejectionMessage =
            String.format(
                "Rejected new system node %s because its mcast-port %s does not match the mcast-port %s of the distributed system it is attempting to join. To fix this make sure the mcast-port gemfire property is set the same on all members of the same distributed system.",
                getSender(), getMcastPort(),
                dm.getSystem().getOriginalConfig().getMcastPort());
      } else if (isMcastEnabled
          && !checkMcastAddress(dm.getSystem().getOriginalConfig().getMcastAddress(),
              getMcastHostAddress())) {
        rejectionMessage =
            String.format(
                "Rejected new system node %s because its mcast-address %s does not match the mcast-address %s of the distributed system it is attempting to join. To fix this make sure the mcast-address gemfire property is set the same on all members of the same distributed system.",
                getSender(), getMcastHostAddress(),
                dm.getSystem().getOriginalConfig().getMcastAddress());
      } else if (dm.getTransport().isTcpDisabled() != isTcpDisabled) {
        rejectionMessage =
            String.format(
                "Rejected new system node %s because isTcpDisabled=%s does not match the distributed system it is attempting to join.",
                getSender(), isTcpDisabled);
      } else if (dm.getDistributedSystemId() != DistributionConfig.DEFAULT_DISTRIBUTED_SYSTEM_ID
          && distributedSystemId != DistributionConfig.DEFAULT_DISTRIBUTED_SYSTEM_ID
          && distributedSystemId != dm.getDistributedSystemId()) {

        String distributedSystemListener =
            System.getProperty(GeodeGlossary.GEMFIRE_PREFIX + "DistributedSystemListener");
        // this check is specific for Jayesh's use case of WAN BootStraping
        if (distributedSystemListener != null) {
          if (-distributedSystemId != dm.getDistributedSystemId()) {
            rejectionMessage =
                String.format(
                    "Rejected new system node %s because distributed-system-id=%s does not match the distributed system %s it is attempting to join.",
                    getSender(),
                    distributedSystemId, dm.getDistributedSystemId());
          }
        } else {
          rejectionMessage =
              String.format(
                  "Rejected new system node %s because distributed-system-id=%s does not match the distributed system %s it is attempting to join.",
                  getSender(), distributedSystemId,
                  dm.getDistributedSystemId());
        }
      }

      if (fromDataProblems != null) {
        if (logger.isDebugEnabled()) {
          logger.debug(fromDataProblems);
        }
      }

      if (rejectionMessage == null) { // change state only if there's no rejectionMessage yet
        if (interfaces == null || interfaces.size() == 0) {
          String msg = "Rejected new system node %s because peer has no network interfaces";
          rejectionMessage = String.format(msg, getSender());
        } else {
          dm.setEquivalentHosts(interfaces);
        }
      }

      if (rejectionMessage != null) {
        logger.warn(rejectionMessage);
      }

      if (rejectionMessage == null) { // change state only if there's no rejectionMessage yet
        dm.setRedundancyZone(getSender(), redundancyZone);
        dm.setEnforceUniqueZone(enforceUniqueZone);

        if (hostedLocatorsAll != null) {
          // boolean isSharedConfigurationEnabled = false;
          // if (this.hostedLocatorsWithSharedConfiguration != null) {
          // isSharedConfigurationEnabled = true;
          // }
          dm.addHostedLocators(getSender(), hostedLocatorsAll,
              isSharedConfigurationEnabled);
        }
      }

      StartupResponseMessage m =
          new StartupResponseWithVersionMessage(dm, replyProcessorId, getSender(), rejectionMessage,
              isAdminDM);
      if (logger.isDebugEnabled()) {
        logger.debug("Received StartupMessage from a member with version: {}, my version is:{}",
            version, GemFireVersion.getGemFireVersion());
      }
      dm.putOutgoing(m);
      replySent = true;
      if (rejectionMessage != null) {
        dm.getDistribution().startupMessageFailed(getSender(), rejectionMessage);
      }

      // We need to discard this member if they aren't a peer.
      if (rejectionMessage != null) {
        dm.handleManagerDeparture(getSender(), false, rejectionMessage);
      }

    } catch (RuntimeException e) {
      ReplyMessage.send(getSender(), replyProcessorId, new ReplyException(e), dm);
      replySent = true;
    } finally {
      if (!replySent && !dm.shutdownInProgress()) {
        ReplyMessage.send(getSender(), replyProcessorId, new ReplyException(
            new IllegalStateException("Unknown cause for response not being sent")), dm);
      }
    }
  }

  private static boolean checkMcastAddress(InetAddress myMcastAddr, String otherMcastHostAddr) {
    String myMcastHostAddr = null;
    if (myMcastAddr != null) {
      myMcastHostAddr = myMcastAddr.getHostAddress();
    }
    if (StringUtils.equals(myMcastHostAddr, otherMcastHostAddr)) {
      return true;
    }
    if (myMcastHostAddr == null) {
      return false;
    }
    return myMcastHostAddr.equals(otherMcastHostAddr);
  }

  @Override
  public int getProcessorType() {
    return OperationExecutors.WAITING_POOL_EXECUTOR;
  }

  @Override
  public int getDSFID() {
    return STARTUP_MESSAGE;
  }

  @Override
  public KnownVersion[] getSerializationVersions() {
    return null;
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    super.toData(out, context);

    DataSerializer.writeString(version, out);
    out.writeInt(replyProcessorId);
    out.writeBoolean(isMcastEnabled);
    out.writeBoolean(isTcpDisabled);

    // Send a description of all of the DataSerializers and
    // Instantiators that have been registered
    SerializerAttributesHolder[] sahs = InternalDataSerializer.getSerializersForDistribution();
    out.writeInt(sahs.length);
    for (final SerializerAttributesHolder sah : sahs) {
      DataSerializer.writeNonPrimitiveClassName(sah.getClassName(), out);
      out.writeInt(sah.getId());
    }

    Object[] insts = InternalInstantiator.getInstantiatorsForSerialization();
    out.writeInt(insts.length);
    for (final Object inst : insts) {
      String instantiatorClassName, instantiatedClassName;
      int id;
      if (inst instanceof Instantiator) {
        instantiatorClassName = ((Instantiator) inst).getClass().getName();
        instantiatedClassName = ((Instantiator) inst).getInstantiatedClass().getName();
        id = ((Instantiator) inst).getId();
      } else {
        instantiatorClassName =
            ((InstantiatorAttributesHolder) inst).getInstantiatorClassName();
        instantiatedClassName =
            ((InstantiatorAttributesHolder) inst).getInstantiatedClassName();
        id = ((InstantiatorAttributesHolder) inst).getId();
      }
      DataSerializer.writeNonPrimitiveClassName(instantiatorClassName, out);
      DataSerializer.writeNonPrimitiveClassName(instantiatedClassName, out);
      out.writeInt(id);
    }
    context.getSerializer().writeObject(interfaces, out);
    out.writeInt(distributedSystemId);
    DataSerializer.writeString(redundancyZone, out);
    out.writeBoolean(enforceUniqueZone);

    StartupMessageData data = new StartupMessageData();
    data.writeHostedLocators(hostedLocatorsAll);
    data.writeIsSharedConfigurationEnabled(isSharedConfigurationEnabled);
    data.writeMcastPort(mcastPort);
    data.writeMcastHostAddress(mcastHostAddress);
    data.writeTo(out);
  }

  /**
   * Notes a problem that occurs while invoking
   * {@link DataSerializableFixedID#fromData(DataInput, DeserializationContext)}.
   */
  private void recordFromDataProblem(String s) {
    if (fromDataProblems == null) {
      fromDataProblems = new StringBuilder();
    }

    fromDataProblems.append(s);
    fromDataProblems.append("\n\n");
  }

  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    super.fromData(in, context);

    version = DataSerializer.readString(in);
    replyProcessorId = in.readInt();
    isMcastEnabled = in.readBoolean();
    isTcpDisabled = in.readBoolean();

    int serializerCount = in.readInt();
    for (int i = 0; i < serializerCount; i++) {
      String cName = DataSerializer.readNonPrimitiveClassName(in);

      int id = in.readInt(); // id
      try {
        if (cName != null) {
          InternalDataSerializer.register(cName, false, null, null, id);
        }
      } catch (IllegalArgumentException ex) {
        recordFromDataProblem(
            String.format("IllegalArgumentException while registering a DataSerializer: %s",
                ex));
      }
    }

    int instantiatorCount = in.readInt();
    for (int i = 0; i < instantiatorCount; i++) {
      String instantiatorClassName = DataSerializer.readNonPrimitiveClassName(in);
      String instantiatedClassName = DataSerializer.readNonPrimitiveClassName(in);
      int id = in.readInt();

      try {
        if (instantiatorClassName != null && instantiatedClassName != null) {
          InternalInstantiator.register(instantiatorClassName, instantiatedClassName, id, false);
        }
      } catch (IllegalArgumentException ex) {
        recordFromDataProblem(
            String.format("IllegalArgumentException while registering an Instantiator: %s",
                ex));
      }
    } // for

    interfaces = context.getDeserializer().readObject(in);
    distributedSystemId = in.readInt();
    redundancyZone = DataSerializer.readString(in);
    enforceUniqueZone = in.readBoolean();

    StartupMessageData data = new StartupMessageData();
    data.readFrom(in);
    hostedLocatorsAll = data.readHostedLocators();
    isSharedConfigurationEnabled = data.readIsSharedConfigurationEnabled();
    mcastPort = data.readMcastPort();
    mcastHostAddress = data.readMcastHostAddress();
  }

  @Override
  public String toString() {
    return String.format(
        "StartupMessage DM %s has started. processor, %s. with distributed system id : %s",
        getSender(), replyProcessorId,
        distributedSystemId);
  }
}
