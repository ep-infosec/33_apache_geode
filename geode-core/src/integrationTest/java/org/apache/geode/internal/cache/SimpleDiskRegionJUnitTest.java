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
/*
 * Created on Feb 15, 2006
 *
 * TODO To change the template for this generated file go to Window - Preferences - Java - Code
 * Style - Code Templates
 */
package org.apache.geode.internal.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import org.apache.geode.StatisticsFactory;
import org.apache.geode.test.dunit.ThreadUtils;

/**
 * Testing methods for SimpleDiskRegion.java api's
 *
 * @since GemFire 5.1
 */
public class SimpleDiskRegionJUnitTest extends DiskRegionTestingBase {

  private final Set keyIds = Collections.synchronizedSet(new HashSet());

  private final DiskRegionProperties diskProps = new DiskRegionProperties();

  @Override
  protected final void postSetUp() throws Exception {
    diskProps.setDiskDirs(dirs);
  }

  /*
   * Test method for 'org.apache.geode.internal.cache.SimpleDiskRegion.basicClose()'
   */
  @Test
  public void testBasicClose() {
    {
      forceDeleteFiles();
      try {
        region = DiskRegionHelperFactory.getAsyncOverFlowAndPersistRegion(cache, diskProps);
      } catch (Exception e) {
        logWriter.error("Exception occurred", e);
        fail(" Exception in createOverflowandPersist due to " + e);
      }
      region.close();
      closeDiskStores();
      checkIfContainsFileWithExt("lk");
    }
    {
      forceDeleteFiles();
      try {
        region = DiskRegionHelperFactory.getAsyncOverFlowOnlyRegion(cache, diskProps);
      } catch (Exception e) {
        logWriter.error("Exception occurred", e);
        fail(" Exception in createOverflowOnly due to " + e);
      }
      region.close();
      closeDiskStores();
      checkIfContainsFileWithExt("lk");
    }
    {
      forceDeleteFiles();
      try {
        region = DiskRegionHelperFactory.getAsyncPersistOnlyRegion(cache, diskProps);
      } catch (Exception e) {
        logWriter.error("Exception occurred", e);
        fail(" Exception in createOverflowandPersist due to " + e);
      }
      region.close();
      closeDiskStores();
      checkIfContainsFileWithExt("lk");
    }
    // Asif: Recreate the region so that it will be destroyed
    try {
      region = DiskRegionHelperFactory.getAsyncPersistOnlyRegion(cache, diskProps);
    } catch (Exception e) {
      logWriter.error("Exception occurred", e);
      fail(" Exception in createOverflowandPersist due to " + e);
    }

  }

  void checkIfContainsFileWithSubstring(String substr) {
    for (final File dir : dirs) {
      File[] files = dir.listFiles();
      for (final File file : files) {
        if (file.getAbsolutePath().contains(substr)) {
          fail("file \"" + file.getAbsolutePath() + "\" still exists");
        }
      }
    }
  }

  void expectContainsFileWithSubstring(String substr) {
    for (final File dir : dirs) {
      File[] files = dir.listFiles();
      for (final File file : files) {
        if (file.getAbsolutePath().contains(substr)) {
          return; // found one
        }
      }
    }
    fail("did not find a file with the substring " + substr);
  }

  void checkIfContainsFileWithExt(String fileExtension) {
    for (final File dir : dirs) {
      File[] files = dir.listFiles();
      for (final File file : files) {
        if (file.getAbsolutePath().endsWith(fileExtension)) {
          fail("file \"" + file.getAbsolutePath() + "\" still exists");
        }
      }
    }
  }

  /*
   * Test method for 'org.apache.geode.internal.cache.SimpleDiskRegion.basicDestroy()'
   */
  @Test
  public void testBasicDestroy() {
    {
      deleteFiles();
      try {
        region = DiskRegionHelperFactory.getAsyncOverFlowAndPersistRegion(cache, diskProps);
      } catch (Exception e) {
        logWriter.error("Exception occurred", e);
        fail(" Exception in createOverflowandPersist due to " + e);
      }
      region.destroyRegion();
      closeDiskStores();
      checkIfContainsFileWithExt("lk");
      // note that this only passes because the test never forced us to create the following files
      checkIfContainsFileWithExt("crf");
      checkIfContainsFileWithExt("drf");
      checkIfContainsFileWithSubstring("OVERFLOW");
    }
    {
      deleteFiles();
      try {
        region = DiskRegionHelperFactory.getAsyncOverFlowOnlyRegion(cache, diskProps);
      } catch (Exception e) {
        logWriter.error("Exception occurred", e);
        fail(" Exception in createOverflowOnly due to " + e);
      }
      region.destroyRegion();
      closeDiskStores();
      checkIfContainsFileWithExt("lk");
      // note that this only passes because the test never forced us to create the following files
      checkIfContainsFileWithExt("crf");
      checkIfContainsFileWithExt("drf");
      checkIfContainsFileWithSubstring("OVERFLOW");
    }
    {
      deleteFiles();
      try {
        region = DiskRegionHelperFactory.getAsyncPersistOnlyRegion(cache, diskProps);
      } catch (Exception e) {
        logWriter.error("Exception occurred", e);
        fail(" Exception in createOverflowandPersist due to " + e);
      }
      region.destroyRegion();
      closeDiskStores();
      checkIfContainsFileWithExt("lk");
      // note that this only passes because the test never forced us to create the following files
      checkIfContainsFileWithExt("crf");
      checkIfContainsFileWithExt("drf");
      checkIfContainsFileWithSubstring("OVERFLOW");
    }

  }

  /*
   * Test method for 'org.apache.geode.internal.cache.SimpleDiskRegion.getChild()'
   */
  @Test
  public void testGetChild() {
    deleteFiles();
    region = DiskRegionHelperFactory.getAsyncPersistOnlyRegion(cache, diskProps);
    DiskRegion dr = ((LocalRegion) region).getDiskRegion();
    Oplog oplog = dr.testHook_getChild();
    long id = oplog.getOplogId();

    StatisticsFactory factory = region.getCache().getDistributedSystem();
    Oplog newOplog =
        new Oplog(id, dr.getOplogSet(), new DirectoryHolder(factory, dirs[0], 1000000, 0));
    dr.getDiskStore().getPersistentOplogs().setChild(newOplog);
    assertEquals(newOplog, dr.testHook_getChild());
    dr.setChild(oplog);
    assertEquals(oplog, dr.testHook_getChild());
    newOplog.close();
    newOplog = null;
    closeDown();
  }

  /*
   * Test method for 'org.apache.geode.internal.cache.SimpleDiskRegion.getNextDir()'
   */
  @Test
  public void testGetNextDir() {
    deleteFiles();

    File file1 = new File("SimpleDiskRegionJUnitTestDir1");
    file1.mkdir();
    file1.deleteOnExit();

    File file2 = new File("SimpleDiskRegionJUnitTestDir2");
    file2.mkdir();
    file2.deleteOnExit();

    File file3 = new File("SimpleDiskRegionJUnitTestDir3");
    file3.mkdir();
    file3.deleteOnExit();

    File file4 = new File("SimpleDiskRegionJUnitTestDir4");
    file4.mkdir();
    file4.deleteOnExit();

    File[] oldDirs = new File[4];
    oldDirs = dirs;
    dirs[0] = file1;
    dirs[1] = file2;
    dirs[2] = file3;
    dirs[3] = file4;
    closeDiskStores();
    deleteFiles();
    DiskRegionProperties diskProps = new DiskRegionProperties();
    diskProps.setDiskDirs(dirs);

    region = DiskRegionHelperFactory.getAsyncPersistOnlyRegion(cache, diskProps);
    DiskRegion dr = ((LocalRegion) region).getDiskRegion();
    assertEquals(file2, dr.getNextDir().getDir());
    assertEquals(file3, dr.getNextDir().getDir());
    assertEquals(file4, dr.getNextDir().getDir());
    assertEquals(file1, dr.getNextDir().getDir());
    closeDown();
    deleteFiles();
    dirs = oldDirs;
  }

  /*
   * Test method for 'org.apache.geode.internal.cache.SimpleDiskRegion.newDiskId()'
   */
  @Test
  public void testNewDiskId() {
    deleteFiles();
    region = DiskRegionHelperFactory.getAsyncPersistOnlyRegion(cache, diskProps);

    TestNewDiskId newDiskId = new TestNewDiskId();
    Thread thread1 = new Thread(newDiskId);
    Thread thread2 = new Thread(newDiskId);
    Thread thread3 = new Thread(newDiskId);
    Thread thread4 = new Thread(newDiskId);
    Thread thread5 = new Thread(newDiskId);

    thread1.setDaemon(true);
    thread2.setDaemon(true);
    thread3.setDaemon(true);
    thread4.setDaemon(true);
    thread5.setDaemon(true);

    thread1.start();
    thread2.start();
    thread3.start();
    thread4.start();
    thread5.start();

    ThreadUtils.join(thread1, 30 * 1000);
    ThreadUtils.join(thread2, 30 * 1000);
    ThreadUtils.join(thread3, 30 * 1000);
    ThreadUtils.join(thread4, 30 * 1000);
    ThreadUtils.join(thread5, 30 * 1000);

    if (keyIds.size() != 50000) {
      fail("Size not equal to 5000 as expected but is " + keyIds.size());
    }

    closeDown();
  }

  class TestNewDiskId implements Runnable {
    @Override
    public void run() {
      long keyId = 0;
      for (int i = 0; i < 10000; i++) {
        keyId = ((LocalRegion) region).getDiskRegion().newOplogEntryId();
        keyIds.add(keyId);
      }
    }
  }
}
