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
package org.apache.geode.cache.client.internal.pooling;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.geode.InternalGemFireException;
import org.apache.geode.cache.client.internal.Connection;
import org.apache.geode.cache.client.internal.ConnectionStats;
import org.apache.geode.cache.client.internal.Endpoint;
import org.apache.geode.cache.client.internal.Op;
import org.apache.geode.distributed.internal.ServerLocation;
import org.apache.geode.internal.cache.tier.sockets.ServerQueueStatus;

/**
 * A connection managed by the connection manager. Keeps track of the current state of the
 * connection.
 *
 * @since GemFire 5.7
 *
 */
public class PooledConnection implements Connection {

  /*
   * connection is volatile because we may asynchronously destroy the pooled connection while
   * shutting down.
   */
  private volatile Connection connection;
  private volatile Endpoint endpoint;
  private volatile long birthDate;
  private long lastAccessed; // read & written while synchronized
  private boolean active = true; // read and write while synchronized on this
  private final AtomicBoolean shouldDestroy = new AtomicBoolean();
  private boolean waitingToSwitch = false;

  public PooledConnection(ConnectionManagerImpl manager, Connection connection) {
    this.connection = connection;
    endpoint = connection.getEndpoint();
    birthDate = System.nanoTime();
    lastAccessed = birthDate;
  }

  @Override
  public ServerLocation getServer() {
    return getEndpoint().getLocation();
  }

  @Override
  public boolean isActive() {
    synchronized (this) {
      return active;
    }
  }

  /**
   * @return true if internal connection was destroyed by this call; false if already destroyed
   */
  public boolean internalDestroy() {
    boolean result = false;
    shouldDestroy.set(true); // probably already set but make sure
    synchronized (this) {
      active = false;
      notifyAll();
      Connection myCon = connection;
      if (myCon != null) {
        myCon.destroy();
        connection = null;
        result = true;
      }
    }
    return result;
  }

  /**
   * When a pooled connection is destroyed, it's not destroyed right away, but when it is returned
   * to the pool.
   */
  @Override
  public void destroy() {
    shouldDestroy.set(true);
  }

  public void internalClose(boolean keepAlive) throws Exception {
    try {
      Connection con = connection;
      if (con != null) {
        con.close(keepAlive);
      }
    } finally {
      internalDestroy();
    }
  }

  @Override
  public void close(boolean keepAlive) throws Exception {
    internalClose(keepAlive);
  }

  @Override
  public void emergencyClose() {
    Connection con = connection;
    if (con != null) {
      connection.emergencyClose();
    }
    connection = null;

  }

  Connection getConnection() {
    Connection result = connection;
    if (result == null) {
      throw new ConnectionDestroyedException();
    }
    return result;
  }

  @Override
  public Connection getWrappedConnection() {
    return getConnection();
  }

  /**
   * Set the destroy bit if it is not already set.
   *
   * @return true if we were able to set to bit; false if someone else already did
   */
  public boolean setShouldDestroy() {
    return shouldDestroy.compareAndSet(false, true);
  }

  public boolean shouldDestroy() {
    return shouldDestroy.get();
  }

  @Override
  public boolean isDestroyed() {
    return connection == null;
  }

  @Override
  public void passivate(final boolean accessed) {
    long now = 0L;
    if (accessed) {
      // do this outside the sync
      now = System.nanoTime();
    }
    synchronized (this) {
      if (isDestroyed()) {
        return;
      }
      if (!active) {
        throw new InternalGemFireException("Connection not active");
      }
      active = false;
      if (waitingToSwitch) {
        notifyAll();
      }
      if (accessed) {
        lastAccessed = now; // do this while synchronized
      }
    }
  }


  public synchronized boolean switchConnection(Connection newCon) throws InterruptedException {
    Connection oldCon = null;
    synchronized (this) {
      if (shouldDestroy()) {
        return false;
      }

      if (active && !shouldDestroy()) {
        waitingToSwitch = true;
        try {
          while (active && !shouldDestroy()) {
            wait();
          }
        } finally {
          waitingToSwitch = false;
          notifyAll();
        }
      }
      if (shouldDestroy()) {
        return false;
      }
      assert !active;
      final long now = System.nanoTime();
      oldCon = connection;
      connection = newCon;
      endpoint = newCon.getEndpoint();
      birthDate = now;
    }
    if (oldCon != null) {
      try {
        // do this outside of sync
        oldCon.close(false);
      } catch (Exception e) {
        // ignore
      }
    }
    return true;
  }

  @Override
  public boolean activate() {
    synchronized (this) {
      try {
        while (waitingToSwitch) {
          wait();
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      if (isDestroyed() || shouldDestroy()) {
        return false;
      }
      if (active) {
        throw new InternalGemFireException("Connection already active");
      }
      active = true;
      return true;
    }
  }

  private synchronized long getLastAccessed() {
    return lastAccessed;
  }

  @Override
  public long getBirthDate() {
    return birthDate;
  }

  @Override
  public void setBirthDate(long ts) {
    birthDate = ts;
  }

  /**
   * Returns the number of nanos remaining is this connection's life.
   */
  public long remainingLife(long now, long timeoutNanos) {
    return (getBirthDate() - now) + timeoutNanos;
  }

  private long remainingIdle(long now, long timeoutNanos) {
    return (getLastAccessed() - now) + timeoutNanos;
  }

  /**
   * If we were able to idle timeout this connection then return -1. If this connection has already
   * been destroyed return 0. Otherwise return the amount of idle time remaining. If the connection
   * is active we can't time it out now and a hint is returned as when we should check again.
   */
  public long doIdleTimeout(long now, long timeoutNanos) {
    if (shouldDestroy()) {
      return 0;
    }
    synchronized (this) {
      if (isActive()) {
        // this is a reasonable value to return since odds are that
        // when the connection goes inactive it will be resetting its access time.
        return timeoutNanos;
      } else {
        long idleRemaining = remainingIdle(now, timeoutNanos);
        if (idleRemaining <= 0) {
          if (setShouldDestroy()) {
            // we were able to set the destroy bit
            return -1;
          } else {
            // someone else already destroyed it
            return 0;
          }
        } else {
          return idleRemaining;
        }
      }
    }
  }

  /**
   * Return true if the connection has been idle long enough to expire.
   */
  public boolean hasIdleExpired(long now, long timeoutNanos) {
    synchronized (this) {
      if (isActive()) {
        return false;
      } else {
        return remainingIdle(now, timeoutNanos) <= 0;
      }
    }
  }

  @Override
  public ByteBuffer getCommBuffer() throws SocketException {
    return getConnection().getCommBuffer();
  }

  @Override
  public Socket getSocket() {
    return getConnection().getSocket();
  }

  @Override
  public OutputStream getOutputStream() {
    return getConnection().getOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return getConnection().getInputStream();
  }

  @Override
  public ConnectionStats getStats() {
    return getEndpoint().getStats();
  }

  @Override
  public Endpoint getEndpoint() {
    return endpoint;
  }

  @Override
  public ServerQueueStatus getQueueStatus() {
    return getConnection().getQueueStatus();
  }

  @Override
  public String toString() {
    Connection myCon = connection;
    if (myCon != null) {
      return "Pooled Connection to " + endpoint + ": " + myCon;
    } else {
      return "Pooled Connection to " + endpoint + ": Connection[DESTROYED]";
    }
  }

  @Override
  public Object execute(Op op) throws Exception {
    return getConnection().execute(op);
  }

  @Override
  public short getWanSiteVersion() {
    return getConnection().getWanSiteVersion();
  }

  @Override
  public int getDistributedSystemId() {
    return getConnection().getDistributedSystemId();
  }

  @Override
  public void setWanSiteVersion(short wanSiteVersion) {
    getConnection().setWanSiteVersion(wanSiteVersion);
  }

  public void setConnection(Connection newConnection) {
    connection = newConnection;
  }

  @Override
  public void setConnectionID(long id) {
    connection.setConnectionID(id);
  }

  @Override
  public long getConnectionID() {
    return connection.getConnectionID();
  }
}
