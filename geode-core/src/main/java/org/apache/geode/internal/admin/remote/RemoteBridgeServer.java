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
package org.apache.geode.internal.admin.remote;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.geode.DataSerializable;
import org.apache.geode.DataSerializer;
import org.apache.geode.cache.ClientSession;
import org.apache.geode.cache.InterestRegistrationListener;
import org.apache.geode.cache.server.ClientSubscriptionConfig;
import org.apache.geode.cache.server.ServerLoad;
import org.apache.geode.cache.server.ServerLoadProbe;
import org.apache.geode.cache.server.ServerLoadProbeAdapter;
import org.apache.geode.cache.server.ServerMetrics;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.internal.admin.AdminBridgeServer;
import org.apache.geode.internal.cache.AbstractCacheServer;
import org.apache.geode.internal.cache.CacheServerImpl;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.tier.Acceptor;
import org.apache.geode.internal.cache.tier.OverflowAttributes;
import org.apache.geode.internal.cache.tier.sockets.CacheClientNotifier;
import org.apache.geode.internal.cache.tier.sockets.ClientHealthMonitor;
import org.apache.geode.internal.cache.tier.sockets.ConnectionListener;
import org.apache.geode.internal.net.SocketCreator;
import org.apache.geode.internal.security.SecurityService;
import org.apache.geode.internal.statistics.StatisticsClock;

/**
 * A remote (serializable) implementation of <code>BridgeServer</code> that is passed between
 * administration VMs and VMs that host caches with cache servers.
 *
 * @since GemFire 4.0
 */
public class RemoteBridgeServer extends AbstractCacheServer
    implements AdminBridgeServer, DataSerializable {

  private static final long serialVersionUID = 8417391824652384959L;

  /** Is this cache server running? */
  private boolean isRunning;

  /** The id of this cache server */
  private int id;


  // /**
  // * The name of the directory in which to store overflowed files for client ha
  // * queue
  // */
  // private String overflowDirectory=null;
  ////////////////////// Constructors //////////////////////

  /**
   * A "copy constructor" that creates a <code>RemoteBridgeServer</code> from the contents of the
   * given <code>BridgeServerImpl</code>.
   */
  RemoteBridgeServer(CacheServerImpl impl) {
    super(null);
    port = impl.getPort();
    bindAddress = impl.getBindAddress();
    hostnameForClients = impl.getHostnameForClients();
    if (CacheServerImpl.ENABLE_NOTIFY_BY_SUBSCRIPTION_FALSE) {
      notifyBySubscription = impl.getNotifyBySubscription();
    }
    socketBufferSize = impl.getSocketBufferSize();
    maximumTimeBetweenPings = impl.getMaximumTimeBetweenPings();
    isRunning = impl.isRunning();
    maxConnections = impl.getMaxConnections();
    maxThreads = impl.getMaxThreads();
    id = System.identityHashCode(impl);
    maximumMessageCount = impl.getMaximumMessageCount();
    messageTimeToLive = impl.getMessageTimeToLive();
    groups = impl.getGroups();
    loadProbe = getProbe(impl.getLoadProbe());
    loadPollInterval = impl.getLoadPollInterval();
    tcpNoDelay = impl.getTcpNoDelay();
    // added for configuration of ha overflow
    ClientSubscriptionConfig cscimpl = impl.getClientSubscriptionConfig();
    clientSubscriptionConfig.setEvictionPolicy(cscimpl.getEvictionPolicy());
    clientSubscriptionConfig.setCapacity(cscimpl.getCapacity());
    String diskStoreName = cscimpl.getDiskStoreName();
    if (diskStoreName != null) {
      clientSubscriptionConfig.setDiskStoreName(diskStoreName);
    } else {
      clientSubscriptionConfig.setOverflowDirectory(cscimpl.getOverflowDirectory());
    }
  }

  private ServerLoadProbe getProbe(ServerLoadProbe probe) {
    if (probe == null) {
      return new RemoteLoadProbe("");
    }
    if (probe instanceof Serializable) {
      return probe;
    } else {
      return new RemoteLoadProbe(probe.toString());
    }
  }

  /**
   * Constructor for de-serialization
   */
  public RemoteBridgeServer() {
    super(null);
  }

  //////////////////// Instance Methods ////////////////////

  @Override
  public void start() throws IOException {
    throw new UnsupportedOperationException(
        "A remote BridgeServer cannot be started.");
  }

  @Override
  public boolean isRunning() {
    return isRunning;
  }

  @Override
  public void stop() {
    throw new UnsupportedOperationException(
        "A remote BridgeServer cannot be stopped.");
  }

  /**
   * Returns the cache that is served by this cache server or <code>null</code> if this server is
   * not running.
   */
  @Override
  public InternalCache getCache() {
    throw new UnsupportedOperationException(
        "Cannot get the Cache of a remote BridgeServer.");
  }

  @Override
  public ConnectionListener getConnectionListener() {
    throw new UnsupportedOperationException("Unsupported in RemoteBridgeServer");
  }

  @Override
  public long getTimeLimitMillis() {
    throw new UnsupportedOperationException("Unsupported in RemoteBridgeServer");
  }

  @Override
  public SecurityService getSecurityService() {
    throw new UnsupportedOperationException("Unsupported in RemoteBridgeServer");
  }

  @Override
  public Supplier<SocketCreator> getSocketCreatorSupplier() {
    throw new UnsupportedOperationException("Unsupported in RemoteBridgeServer");
  }

  @Override
  public CacheClientNotifier.CacheClientNotifierProvider getCacheClientNotifierProvider() {
    throw new UnsupportedOperationException("Unsupported in RemoteBridgeServer");
  }

  @Override
  public ClientHealthMonitor.ClientHealthMonitorProvider getClientHealthMonitorProvider() {
    throw new UnsupportedOperationException("Unsupported in RemoteBridgeServer");
  }

  @Override
  public String[] getCombinedGroups() {
    throw new UnsupportedOperationException("Unsupported in RemoteBridgeServer");
  }

  @Override
  public StatisticsClock getStatisticsClock() {
    throw new UnsupportedOperationException("Unsupported in RemoteBridgeServer");
  }

  @Override
  public ClientSession getClientSession(String durableClientId) {
    String s = "Cannot get a client session for a remote BridgeServer";
    throw new UnsupportedOperationException(s);
  }

  @Override
  public ClientSession getClientSession(DistributedMember member) {
    String s = "Cannot get a client session for a remote BridgeServer";
    throw new UnsupportedOperationException(s);
  }

  @Override
  public Set getAllClientSessions() {
    String s =
        "Cannot get all client sessions for a remote BridgeServer";
    throw new UnsupportedOperationException(s);
  }

  @Override
  public ClientSubscriptionConfig getClientSubscriptionConfig() {
    return clientSubscriptionConfig;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    out.writeInt(port);
    out.writeBoolean(notifyBySubscription);
    out.writeBoolean(isRunning);
    out.writeInt(maxConnections);
    out.writeInt(id);
    out.writeInt(maximumTimeBetweenPings);
    out.writeInt(maximumMessageCount);
    out.writeInt(messageTimeToLive);
    out.writeInt(maxThreads);
    DataSerializer.writeString(bindAddress, out);
    DataSerializer.writeStringArray(groups, out);
    DataSerializer.writeString(hostnameForClients, out);
    DataSerializer.writeObject(loadProbe, out);
    DataSerializer.writePrimitiveLong(loadPollInterval, out);
    out.writeInt(socketBufferSize);
    out.writeBoolean(tcpNoDelay);
    out.writeInt(getClientSubscriptionConfig().getCapacity());
    DataSerializer.writeString(getClientSubscriptionConfig().getEvictionPolicy(), out);
    DataSerializer.writeString(getClientSubscriptionConfig().getDiskStoreName(), out);
    if (getClientSubscriptionConfig().getDiskStoreName() == null) {
      DataSerializer.writeString(getClientSubscriptionConfig().getOverflowDirectory(), out);
    }
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {

    port = in.readInt();
    notifyBySubscription = in.readBoolean();
    isRunning = in.readBoolean();
    maxConnections = in.readInt();
    id = in.readInt();
    maximumTimeBetweenPings = in.readInt();
    maximumMessageCount = in.readInt();
    messageTimeToLive = in.readInt();
    maxThreads = in.readInt();
    setBindAddress(DataSerializer.readString(in));
    setGroups(DataSerializer.readStringArray(in));
    setHostnameForClients(DataSerializer.readString(in));
    setLoadProbe(DataSerializer.readObject(in));
    setLoadPollInterval(DataSerializer.readPrimitiveLong(in));
    socketBufferSize = in.readInt();
    tcpNoDelay = in.readBoolean();
    getClientSubscriptionConfig().setCapacity(in.readInt());
    getClientSubscriptionConfig().setEvictionPolicy(DataSerializer.readString(in));
    String diskStoreName = DataSerializer.readString(in);
    if (diskStoreName != null) {
      getClientSubscriptionConfig().setDiskStoreName(diskStoreName);
    } else {
      getClientSubscriptionConfig().setOverflowDirectory(DataSerializer.readString(in));
    }
  }

  @Override
  public Acceptor getAcceptor() {
    throw new UnsupportedOperationException("not implemented on " + getClass().getSimpleName());
  }

  @Override
  public Acceptor createAcceptor(OverflowAttributes overflowAttributes) throws IOException {
    throw new UnsupportedOperationException("not implemented on " + getClass().getSimpleName());
  }

  @Override
  public String getExternalAddress() {
    throw new UnsupportedOperationException("not implemented on " + getClass().getSimpleName());
  }

  private static class RemoteLoadProbe extends ServerLoadProbeAdapter {
    /** The description of this callback */
    private final String desc;

    public RemoteLoadProbe(String desc) {
      this.desc = desc;
    }

    @Override
    public ServerLoad getLoad(ServerMetrics metrics) {
      return null;
    }

    @Override
    public String toString() {
      return desc;
    }
  }

  /**
   * Registers a new <code>InterestRegistrationListener</code> with the set of
   * <code>InterestRegistrationListener</code>s.
   *
   * @param listener The <code>InterestRegistrationListener</code> to register
   *
   * @since GemFire 5.8Beta
   */
  @Override
  public void registerInterestRegistrationListener(InterestRegistrationListener listener) {
    final String s =
        "InterestRegistrationListeners cannot be registered on a remote BridgeServer";
    throw new UnsupportedOperationException(s);
  }

  /**
   * Unregisters an existing <code>InterestRegistrationListener</code> from the set of
   * <code>InterestRegistrationListener</code>s.
   *
   * @param listener The <code>InterestRegistrationListener</code> to unregister
   *
   * @since GemFire 5.8Beta
   */
  @Override
  public void unregisterInterestRegistrationListener(InterestRegistrationListener listener) {
    final String s =
        "InterestRegistrationListeners cannot be unregistered from a remote BridgeServer";
    throw new UnsupportedOperationException(s);
  }

  /**
   * Returns a read-only set of <code>InterestRegistrationListener</code>s registered with this
   * notifier.
   *
   * @return a read-only set of <code>InterestRegistrationListener</code>s registered with this
   *         notifier
   *
   * @since GemFire 5.8Beta
   */
  @Override
  public Set getInterestRegistrationListeners() {
    final String s =
        "InterestRegistrationListeners cannot be retrieved from a remote BridgeServer";
    throw new UnsupportedOperationException(s);
  }
}
