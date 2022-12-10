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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestName;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;


/**
 * Testing recovery from failures writing OverflowOplog entries
 */
public class OverflowOplogFlushTest extends DiskRegionTestingBase {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  // How many times to fake the write failures
  private int nFakeChannelWrites = 0;
  private OverflowOplog ol = null;
  private ByteBuffer bb1 = null;
  private ByteBuffer bb2 = null;
  private final ByteBuffer[] bbArray = new ByteBuffer[2];
  private FileChannel ch;
  private FileChannel spyCh;

  @Rule
  public TestName name = new TestName();

  class FakeChannelWriteBB implements Answer<Integer> {

    @Override
    public Integer answer(InvocationOnMock invocation) throws Throwable {
      return fakeWriteBB(ol, bb1);
    }
  }

  private int fakeWriteBB(OverflowOplog ol, ByteBuffer bb) throws IOException {
    if (nFakeChannelWrites > 0) {
      bb.position(bb.limit());
      --nFakeChannelWrites;
      return 0;
    }
    doCallRealMethod().when(spyCh).write(bb);
    return spyCh.write(bb);
  }

  private void verifyBB(ByteBuffer bb, byte[] src) {
    bb.flip();
    for (int i = 0; i < src.length; ++i) {
      assertEquals("Channel contents does not match expected at index " + i, src[i], bb.get());
    }
  }

  class FakeChannelWriteArrayBB implements Answer<Long> {
    @Override
    public Long answer(InvocationOnMock invocation) throws Throwable {
      System.out.println("### in FakeChannelWriteArrayBB.answer :");
      return fakeWriteArrayBB(bbArray);
    }
  }

  /**
   * This method tries to write half of the byte buffer to the channel.
   */
  private long fakeWriteArrayBB(ByteBuffer[] bbArray) throws IOException {
    nFakeChannelWrites++;
    for (ByteBuffer b : bbArray) {
      int numFakeWrite = b.limit() / 2;
      if (b.position() <= 0) {
        b.position(numFakeWrite);
        return numFakeWrite;
      } else if (b.position() == numFakeWrite) {
        b.position(b.limit());
        return b.limit() - numFakeWrite;
      }
    }
    return 0;
  }

  private void doChannelFlushWithFailures(OverflowOplog oplog, int numFailures) throws IOException {
    nFakeChannelWrites = numFailures;
    ol = oplog;
    ch = ol.getFileChannel();
    spyCh = spy(ch);
    ol.testSetCrfChannel(spyCh);

    byte[] entry1 = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19};
    byte[] entry2 = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115,
        116, 117, 118, 119};

    bb1 = ol.getWriteBuf();
    try {
      // Force channel.write() failures when writing the first entry
      doAnswer(new FakeChannelWriteBB()).when(spyCh).write(bb1);
      long chStartPos = ol.getFileChannel().position();
      bb1.clear();
      bb1.put(entry1);
      ol.flush();

      // Write the 2nd entry without forced channel failures
      nFakeChannelWrites = 0;
      bb1 = ol.getWriteBuf();
      bb1.clear();
      bb1.put(entry2);
      ol.flush();
      long chEndPos = ol.getFileChannel().position();
      assertEquals("Change in channel position does not equal the size of the data flushed",
          entry1.length + entry2.length, chEndPos - chStartPos);
      ByteBuffer dst = ByteBuffer.allocateDirect(entry1.length);
      ol.getFileChannel().position(chStartPos);
      ol.getFileChannel().read(dst);
      verifyBB(dst, entry1);
    } finally {
      region.destroyRegion();
    }
  }

  @Test
  public void testAsyncChannelWriteRetriesOnFailureDuringFlush() throws Exception {
    DiskRegionProperties props = new DiskRegionProperties();
    props.setOverFlowCapacity(1);
    region = DiskRegionHelperFactory.getSyncOverFlowOnlyRegion(cache, props);
    region.put("K1", "v1"); // add two entries to make it overflow
    region.put("K2", "v2");
    DiskRegion dr = ((LocalRegion) region).getDiskRegion();
    OverflowOplog oplog = dr.getDiskStore().overflowOplogs.getActiveOverflowOplog();
    assertNotNull("Unexpected null Oplog for " + dr.getName(), oplog);
    doChannelFlushWithFailures(oplog, 1 /* write failure */);
  }

  @Test
  public void testChannelWriteRetriesOnFailureDuringFlush() throws Exception {
    DiskRegionProperties props = new DiskRegionProperties();
    props.setOverFlowCapacity(1);
    region = DiskRegionHelperFactory.getSyncOverFlowOnlyRegion(cache, props);
    region.put("K1", "v1"); // add two entries to make it overflow
    region.put("K2", "v2");
    DiskRegion dr = ((LocalRegion) region).getDiskRegion();
    OverflowOplog oplog = dr.getDiskStore().overflowOplogs.getActiveOverflowOplog();
    assertNotNull("Unexpected null Oplog for " + dr.getName(), oplog);
    doChannelFlushWithFailures(oplog, 1 /* write failure */);
  }

  @Test
  public void testChannelRecoversFromWriteFailureRepeatedRetriesDuringFlush() throws Exception {
    DiskRegionProperties props = new DiskRegionProperties();
    props.setOverFlowCapacity(1);
    region = DiskRegionHelperFactory.getSyncOverFlowOnlyRegion(cache, props);
    region.put("K1", "v1"); // add two entries to make it overflow
    region.put("K2", "v2");
    DiskRegion dr = ((LocalRegion) region).getDiskRegion();
    OverflowOplog oplog = dr.getDiskStore().overflowOplogs.getActiveOverflowOplog();
    assertNotNull("Unexpected null Oplog for " + dr.getName(), oplog);

    doChannelFlushWithFailures(oplog, 3 /* write failures */);
  }

  @Test
  public void testOplogFlushThrowsIOExceptioniWhenNumberOfChannelWriteRetriesExceedsLimit()
      throws Exception {
    expectedException.expect(IOException.class);
    DiskRegionProperties props = new DiskRegionProperties();
    props.setOverFlowCapacity(1);
    region = DiskRegionHelperFactory.getSyncOverFlowOnlyRegion(cache, props);
    region.put("K1", "v1"); // add two entries to make it overflow
    region.put("K2", "v2");
    DiskRegion dr = ((LocalRegion) region).getDiskRegion();
    OverflowOplog oplog = dr.getDiskStore().overflowOplogs.getActiveOverflowOplog();
    assertNotNull("Unexpected null Oplog for " + dr.getName(), oplog);

    doChannelFlushWithFailures(oplog, 6 /* exceeds the retry limit in Oplog */);
  }

  private void doPartialChannelByteArrayFlushForOverflowOpLog(OverflowOplog oplog)
      throws IOException {
    OverflowOplog ol = oplog;
    FileChannel ch = ol.getFileChannel();
    FileChannel spyCh = spy(ch);
    ol.testSetCrfChannel(spyCh);

    byte[] entry1 = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19};
    byte[] entry2 = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115,
        116, 117, 118, 119};

    bbArray[0] = bb1 = ByteBuffer.allocate(entry1.length).put(entry1);
    bbArray[1] = bb2 = ByteBuffer.allocate(entry2.length).put(entry2);

    try {
      // Set fake channel, that pretends to write partial data.
      doAnswer(new FakeChannelWriteArrayBB()).when(spyCh).write(bbArray);

      bb2.flip();
      ol.flush(bb1, bb2);
      assertEquals("Incomplete flush calls.", 4, nFakeChannelWrites);

    } finally {
      region.destroyRegion();
    }
  }

  @Test
  public void testOverflowOplogByteArrayFlush() throws Exception {
    DiskRegionProperties props = new DiskRegionProperties();
    props.setOverFlowCapacity(1);
    region = DiskRegionHelperFactory.getSyncOverFlowOnlyRegion(cache, props);
    region.put("K1", "v1");
    region.put("K2", "v2");

    DiskRegion dr = ((LocalRegion) region).getDiskRegion();
    OverflowOplog oplog = dr.getDiskStore().overflowOplogs.getActiveOverflowOplog();
    assertNotNull("Unexpected null Oplog", oplog);

    doPartialChannelByteArrayFlushForOverflowOpLog(oplog);
  }
}
