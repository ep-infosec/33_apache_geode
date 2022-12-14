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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Logger;

import org.apache.geode.cache.DiskAccessException;
import org.apache.geode.internal.cache.entries.DiskEntry;
import org.apache.geode.internal.cache.entries.DiskEntry.Helper.ValueWrapper;
import org.apache.geode.logging.internal.log4j.api.LogService;

public class OverflowOplogSet implements OplogSet {
  private static final Logger logger = LogService.getLogger();

  private final AtomicInteger overflowOplogId = new AtomicInteger(0);
  private OverflowOplog lastOverflowWrite;
  private final ConcurrentMap<Integer, OverflowOplog> overflowMap = new ConcurrentHashMap<>();
  private final Map<Integer, OverflowOplog> compactibleOverflowMap = new LinkedHashMap<>();

  private int lastOverflowDir = 0;

  private final DiskStoreImpl parent;

  public OverflowOplogSet(DiskStoreImpl parent) {
    this.parent = parent;
  }

  OverflowOplog getActiveOverflowOplog() {
    return lastOverflowWrite;
  }

  @Override
  public void modify(InternalRegion region, DiskEntry entry, ValueWrapper value, boolean async) {
    DiskRegion dr = region.getDiskRegion();
    synchronized (overflowMap) {
      if (lastOverflowWrite != null) {
        if (lastOverflowWrite.modify(dr, entry, value, async)) {
          return;
        }
      }
      // Create a new one and put it on the front of the list.
      OverflowOplog oo = createOverflowOplog(value.getLength());
      addOverflow(oo);
      lastOverflowWrite = oo;
      boolean didIt = oo.modify(dr, entry, value, async);
      assert didIt;
    }
  }

  private long getMaxOplogSizeInBytes() {
    return parent.getMaxOplogSizeInBytes();
  }

  private DirectoryHolder[] getDirectories() {
    return parent.directories;
  }

  /**
   * @param minSize the minimum size this oplog can be
   */
  private OverflowOplog createOverflowOplog(long minSize) {
    lastOverflowDir++;
    if (lastOverflowDir >= getDirectories().length) {
      lastOverflowDir = 0;
    }
    int idx = -1;
    long maxOplogSizeParam = getMaxOplogSizeInBytes();
    if (maxOplogSizeParam < minSize) {
      maxOplogSizeParam = minSize;
    }

    // first look for a directory that has room for maxOplogSize
    for (int i = lastOverflowDir; i < getDirectories().length; i++) {
      long availableSpace = getDirectories()[i].getAvailableSpace();
      if (availableSpace >= maxOplogSizeParam) {
        idx = i;
        break;
      }
    }
    if (idx == -1 && lastOverflowDir != 0) {
      for (int i = 0; i < lastOverflowDir; i++) {
        long availableSpace = getDirectories()[i].getAvailableSpace();
        if (availableSpace >= maxOplogSizeParam) {
          idx = i;
          break;
        }
      }
    }

    if (idx == -1) {
      // if we couldn't find one big enough for the max look for one
      // that has min room
      for (int i = lastOverflowDir; i < getDirectories().length; i++) {
        long availableSpace = getDirectories()[i].getAvailableSpace();
        if (availableSpace >= minSize) {
          idx = i;
          break;
        }
      }
      if (idx == -1 && lastOverflowDir != 0) {
        for (int i = 0; i < lastOverflowDir; i++) {
          long availableSpace = getDirectories()[i].getAvailableSpace();
          if (availableSpace >= minSize) {
            idx = i;
            break;
          }
        }
      }
    }

    if (idx == -1) {
      if (parent.isCompactionEnabled()) { // fix for bug 41835
        idx = lastOverflowDir;
        if (getDirectories()[idx].getAvailableSpace() < minSize) {
          logger.warn(
              "Even though the configured directory size limit has been exceeded a new oplog will be created because compaction is enabled. The configured limit is {}. The current space used in the directory by this disk store is {}.",
              new Object[] {getDirectories()[idx].getUsedSpace(),
                  getDirectories()[idx].getCapacity()});
        }
      } else {
        throw new DiskAccessException(
            String.format(
                "Directories are full, not able to accommodate this operation.Switching problem for entry having DiskID= %s",
                "needed " + minSize + " bytes"),
            parent);
      }
    }
    int id = overflowOplogId.incrementAndGet();
    lastOverflowDir = idx;
    return new OverflowOplog(id, this, getDirectories()[idx], minSize);
  }

  void addOverflow(OverflowOplog oo) {
    overflowMap.put(oo.getOplogId(), oo);
  }

  void removeOverflow(OverflowOplog oo) {
    if (!basicRemoveOverflow(oo)) {
      synchronized (compactibleOverflowMap) {
        compactibleOverflowMap.remove(oo.getOplogId());
      }
    }
  }

  boolean basicRemoveOverflow(OverflowOplog oo) {
    if (lastOverflowWrite == oo) {
      lastOverflowWrite = null;
    }
    return overflowMap.remove(oo.getOplogId(), oo);
  }

  public void closeOverflow() {
    for (OverflowOplog oo : overflowMap.values()) {
      oo.destroy();
    }
    synchronized (compactibleOverflowMap) {
      for (OverflowOplog oo : compactibleOverflowMap.values()) {
        oo.destroy();
      }
    }
  }

  private void removeOverflow(DiskRegion dr, DiskEntry entry) {
    // find the overflow oplog that it is currently in and remove the entry from it
    DiskId id = entry.getDiskId();
    synchronized (id) {
      long oplogId = id.setOplogId(-1);
      if (oplogId != -1) {
        synchronized (overflowMap) { // to prevent concurrent remove see bug 41646
          OverflowOplog oplog = getChild((int) oplogId);
          if (oplog != null) {
            oplog.remove(dr, entry);
          }
        }
      }
    }
  }

  void copyForwardForOverflowCompact(DiskEntry de, byte[] valueBytes, int length, byte userBits) {
    synchronized (overflowMap) {
      if (lastOverflowWrite != null) {
        if (lastOverflowWrite.copyForwardForOverflowCompact(de, valueBytes, length,
            userBits)) {
          return;
        }
      }
      OverflowOplog oo = createOverflowOplog(length);
      lastOverflowWrite = oo;
      addOverflow(oo);
      boolean didIt = oo.copyForwardForOverflowCompact(de, valueBytes, length, userBits);
      assert didIt;
    }
  }

  @Override
  public OverflowOplog getChild(long oplogId) {
    // the oplog id is cast to an integer because the overflow
    // map uses integer oplog ids.
    return getChild((int) oplogId);
  }

  public OverflowOplog getChild(int oplogId) {
    OverflowOplog result = overflowMap.get(oplogId);
    if (result == null) {
      synchronized (compactibleOverflowMap) {
        result = compactibleOverflowMap.get(oplogId);
      }
    }
    return result;
  }

  @Override
  public void create(InternalRegion region, DiskEntry entry, ValueWrapper value, boolean async) {
    modify(region, entry, value, async);
  }

  @Override
  public void remove(InternalRegion region, DiskEntry entry, boolean async, boolean isClear) {
    removeOverflow(region.getDiskRegion(), entry);
  }

  void addOverflowToBeCompacted(OverflowOplog oplog) {
    synchronized (compactibleOverflowMap) {
      compactibleOverflowMap.put(oplog.getOplogId(), oplog);
    }
    basicRemoveOverflow(oplog);
    parent.scheduleCompaction();
  }

  public void getCompactableOplogs(List<CompactableOplog> l, int max) {
    synchronized (compactibleOverflowMap) {
      Iterator<OverflowOplog> itr = compactibleOverflowMap.values().iterator();
      while (itr.hasNext() && l.size() < max) {
        OverflowOplog oplog = itr.next();
        if (oplog.needsCompaction()) {
          l.add(oplog);
        }
      }
    }
  }

  void testHookCloseAllOverflowChannels() {
    synchronized (overflowMap) {
      for (OverflowOplog oo : overflowMap.values()) {
        FileChannel oplogFileChannel = oo.getFileChannel();
        try {
          oplogFileChannel.close();
        } catch (IOException ignore) {
        }
      }
    }
    synchronized (compactibleOverflowMap) {
      for (OverflowOplog oo : compactibleOverflowMap.values()) {
        FileChannel oplogFileChannel = oo.getFileChannel();
        try {
          oplogFileChannel.close();
        } catch (IOException ignore) {
        }
      }
    }
  }

  ArrayList<OverflowOplog> testHookGetAllOverflowOplogs() {
    ArrayList<OverflowOplog> result = new ArrayList<>();
    synchronized (overflowMap) {
      for (OverflowOplog oo : overflowMap.values()) {
        result.add(oo);
      }
    }
    synchronized (compactibleOverflowMap) {
      for (OverflowOplog oo : compactibleOverflowMap.values()) {
        result.add(oo);
      }
    }

    return result;
  }

  void testHookCloseAllOverflowOplogs() {
    synchronized (overflowMap) {
      for (OverflowOplog oo : overflowMap.values()) {
        oo.close();
      }
    }
    synchronized (compactibleOverflowMap) {
      for (OverflowOplog oo : compactibleOverflowMap.values()) {
        oo.close();
      }
    }
  }

  public DiskStoreImpl getParent() {
    return parent;
  }
}
