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
package org.apache.geode.internal.statistics;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.geode.GemFireIOException;
import org.apache.geode.internal.ExitCode;


/**
 * ArchiveSplitter provides APIs to read statistic snapshots from an archive file.
 */
public class ArchiveSplitter implements StatArchiveFormat {
  private final File archiveName;
  private final InputStream is;
  private final MyFilterInputStream myIs;
  private final DataInputStream dataIn;
  private DataOutputStream dataOut;
  private OutputStream output;
  private static final int BUFFER_SIZE = 1024 * 1024;
  private final long splitDuration; // in millis
  private byte[][] resourceTypes = new byte[256][];
  private byte[][] resourceInstanceTypeCodes = new byte[256][];
  private byte[][] resourceInstanceTokens = new byte[256][];
  private long[][] resourceInstanceBits = new long[256][];
  private static final long DEFAULT_SPLIT_DURATION = 24 * 60 * 60 * 1000; // one day
  private long currentDuration = 0; // in millis
  private int splitCount = 0;
  private byte[][] globalTokens = new byte[256][];
  private int globalTokenCount = 0;

  // header info
  private byte archiveVersion;
  private long startTimeStamp;
  private long systemId;
  private long systemStartTimeStamp;
  private int timeZoneOffset;
  private String timeZoneName;
  private String systemDirectory;
  private String productVersion;
  private String os;
  private String machine;


  public ArchiveSplitter(File archiveName) throws IOException {
    this(archiveName, DEFAULT_SPLIT_DURATION);
  }

  public ArchiveSplitter(File archiveName, long splitDuration) throws IOException {
    this.archiveName = archiveName;
    this.splitDuration = splitDuration;
    is = new FileInputStream(archiveName);
    boolean compressed = archiveName.getPath().endsWith(".gz");
    if (compressed) {
      myIs = new MyFilterInputStream(
          new BufferedInputStream(new GZIPInputStream(is, BUFFER_SIZE), BUFFER_SIZE));
    } else {
      myIs = new MyFilterInputStream(new BufferedInputStream(is, BUFFER_SIZE));
    }
    dataIn = new DataInputStream(myIs);
  }

  private void readHeaderToken() throws IOException {
    byte archiveVersion = dataIn.readByte();
    if (archiveVersion <= 1) {
      throw new GemFireIOException(
          String.format("Archive version: %s is no longer supported.",
              archiveVersion),
          null);
    }
    if (archiveVersion > ARCHIVE_VERSION) {
      throw new GemFireIOException(
          String.format("Unsupported archive version: %s .  The supported version is: %s .",

              archiveVersion, ARCHIVE_VERSION),
          null);
    }

    this.archiveVersion = archiveVersion;
    startTimeStamp = dataIn.readLong();
    systemId = dataIn.readLong();
    systemStartTimeStamp = dataIn.readLong();
    timeZoneOffset = dataIn.readInt();
    timeZoneName = dataIn.readUTF();
    systemDirectory = dataIn.readUTF();
    productVersion = dataIn.readUTF();
    os = dataIn.readUTF();
    machine = dataIn.readUTF();
  }

  private void skipBytes(int count) throws IOException {
    int skipped = dataIn.skipBytes(count);
    while (skipped != count) {
      count -= skipped;
      skipped = dataIn.skipBytes(count);
    }
  }

  private void skipBoolean() throws IOException {
    // dataIn.readBoolean();
    skipBytes(1);
  }

  // private void skipByte() throws IOException {
  // //dataIn.readByte();
  // skipBytes(1);
  // }
  // private void skipUByte() throws IOException {
  // //dataIn.readUnsignedByte();
  // skipBytes(1);
  // }
  // private void skipShort() throws IOException {
  // //dataIn.readShort();
  // skipBytes(2);
  // }
  // private void skipUShort() throws IOException {
  // //dataIn.readUnsignedShort();
  // skipBytes(2);
  // }
  // private void skipInt() throws IOException {
  // //dataIn.readInt();
  // skipBytes(4);
  // }
  private void skipLong() throws IOException {
    // dataIn.readLong();
    skipBytes(8);
  }

  private void skipUTF() throws IOException {
    // dataIn.readUTF();
    skipBytes(dataIn.readUnsignedShort());
  }

  private void addGlobalToken(byte[] token) {
    if (globalTokenCount >= globalTokens.length) {
      byte[][] tmp = new byte[globalTokenCount + 128][];
      System.arraycopy(globalTokens, 0, tmp, 0, globalTokens.length);
      globalTokens = tmp;
    }
    globalTokens[globalTokenCount] = token;
    globalTokenCount += 1;
  }

  private void readResourceTypeToken() throws IOException {
    int resourceTypeId = dataIn.readInt();
    skipUTF(); // String resourceTypeName = dataIn.readUTF();
    skipUTF(); // String resourceTypeDesc = dataIn.readUTF();
    int statCount = dataIn.readUnsignedShort();
    byte[] typeCodes = new byte[statCount];

    for (int i = 0; i < statCount; i++) {
      skipUTF(); // String statName = dataIn.readUTF();
      typeCodes[i] = dataIn.readByte();
      skipBoolean(); // boolean isCounter = dataIn.readBoolean();
      if (archiveVersion >= 4) {
        skipBoolean(); // boolean largerBetter = dataIn.readBoolean();
      }
      skipUTF(); // String units = dataIn.readUTF();
      skipUTF(); // String desc = dataIn.readUTF();
    }
    if (resourceTypeId >= resourceTypes.length) {
      byte[][] tmp = new byte[resourceTypeId + 128][];
      System.arraycopy(resourceTypes, 0, tmp, 0, resourceTypes.length);
      resourceTypes = tmp;
    }
    resourceTypes[resourceTypeId] = typeCodes;

    addGlobalToken(myIs.getBytes());
  }

  private void readResourceInstanceCreateToken(boolean initialize) throws IOException {
    int resourceInstId = dataIn.readInt();
    skipUTF(); // String name = dataIn.readUTF();
    skipLong(); // long id = dataIn.readLong();
    int resourceTypeId = dataIn.readInt();

    if (resourceInstId >= resourceInstanceBits.length) {
      long[][] tmpBits = new long[resourceInstId + 128][];
      System.arraycopy(resourceInstanceBits, 0, tmpBits, 0, resourceInstanceBits.length);
      resourceInstanceBits = tmpBits;

      byte[][] tmpTypeCodes = new byte[resourceInstId + 128][];
      System.arraycopy(resourceInstanceTypeCodes, 0, tmpTypeCodes, 0,
          resourceInstanceTypeCodes.length);
      resourceInstanceTypeCodes = tmpTypeCodes;

      byte[][] tmpTokens = new byte[resourceInstId + 128][];
      System.arraycopy(resourceInstanceTokens, 0, tmpTokens, 0, resourceInstanceTokens.length);
      resourceInstanceTokens = tmpTokens;
    }
    byte[] instTypeCodes = resourceTypes[resourceTypeId];
    resourceInstanceTypeCodes[resourceInstId] = instTypeCodes;
    resourceInstanceTokens[resourceInstId] = myIs.getBytes();
    resourceInstanceTokens[resourceInstId][0] = RESOURCE_INSTANCE_INITIALIZE_TOKEN;
    long[] instBits = new long[instTypeCodes.length];
    resourceInstanceBits[resourceInstId] = instBits;
    if (initialize) {
      for (int i = 0; i < instBits.length; i++) {
        switch (instTypeCodes[i]) {
          case BOOLEAN_CODE:
          case BYTE_CODE:
          case CHAR_CODE:
            instBits[i] = dataIn.readByte();
            break;
          case WCHAR_CODE:
            instBits[i] = dataIn.readUnsignedShort();
            break;
          case SHORT_CODE:
            instBits[i] = dataIn.readShort();
            break;
          case INT_CODE:
          case FLOAT_CODE:
          case LONG_CODE:
          case DOUBLE_CODE:
            instBits[i] = readCompactValue();
            break;
          default:
            throw new IOException(String.format("unexpected typeCode value %s",
                instTypeCodes[i]));
        }
      }
    }
  }

  private void readResourceInstanceDeleteToken() throws IOException {
    int id = dataIn.readInt();
    resourceInstanceTypeCodes[id] = null;
    resourceInstanceBits[id] = null;
    resourceInstanceTokens[id] = null;
  }

  private int readResourceInstId() throws IOException {
    int token = dataIn.readUnsignedByte();
    if (token <= MAX_BYTE_RESOURCE_INST_ID) {
      return token;
    } else if (token == ILLEGAL_RESOURCE_INST_ID_TOKEN) {
      return ILLEGAL_RESOURCE_INST_ID;
    } else if (token == SHORT_RESOURCE_INST_ID_TOKEN) {
      return dataIn.readUnsignedShort();
    } else { /* token == INT_RESOURCE_INST_ID_TOKEN */
      return dataIn.readInt();
    }
  }

  private void readTimeDelta() throws IOException {
    int millisSinceLastSample = dataIn.readUnsignedShort();
    if (millisSinceLastSample == INT_TIMESTAMP_TOKEN) {
      millisSinceLastSample = dataIn.readInt();
    }
    currentDuration += millisSinceLastSample;
    startTimeStamp += millisSinceLastSample;
  }

  private long readCompactValue() throws IOException {
    long v = dataIn.readByte();
    if (v < MIN_1BYTE_COMPACT_VALUE) {
      if (v == COMPACT_VALUE_2_TOKEN) {
        v = dataIn.readShort();
      } else {
        int bytesToRead = ((byte) v - COMPACT_VALUE_2_TOKEN) + 2;
        v = dataIn.readByte(); // note the first byte will be a signed byte.
        bytesToRead--;
        while (bytesToRead > 0) {
          v <<= 8;
          v |= dataIn.readUnsignedByte();
          bytesToRead--;
        }
      }
    }
    return v;
  }

  private void readSampleToken() throws IOException {
    readTimeDelta();
    int resourceInstId = readResourceInstId();
    while (resourceInstId != ILLEGAL_RESOURCE_INST_ID) {
      byte[] typeCodes = resourceInstanceTypeCodes[resourceInstId];
      long[] bits = resourceInstanceBits[resourceInstId];
      int statOffset = dataIn.readUnsignedByte();
      while (statOffset != ILLEGAL_STAT_OFFSET) {
        long statDeltaBits;
        switch (typeCodes[statOffset]) {
          case BOOLEAN_CODE:
          case BYTE_CODE:
          case CHAR_CODE:
            statDeltaBits = dataIn.readByte();
            break;
          case WCHAR_CODE:
            statDeltaBits = dataIn.readUnsignedShort();
            break;
          case SHORT_CODE:
            statDeltaBits = dataIn.readShort();
            break;
          case INT_CODE:
          case FLOAT_CODE:
          case LONG_CODE:
          case DOUBLE_CODE:
            statDeltaBits = readCompactValue();
            break;
          default:
            throw new IOException(String.format("unexpected typeCode value %s",
                typeCodes[statOffset]));
        }
        bits[statOffset] += statDeltaBits;
        statOffset = dataIn.readUnsignedByte();
      }
      resourceInstId = readResourceInstId();
    }
  }

  /**
   * Returns true if token read, false if eof.
   */
  private boolean readToken() throws IOException {
    byte token;
    try {
      token = dataIn.readByte();
      switch (token) {
        case HEADER_TOKEN:
          readHeaderToken();
          myIs.putBytes(dataOut);
          break;
        case RESOURCE_TYPE_TOKEN:
          readResourceTypeToken();
          myIs.putBytes(dataOut);
          break;
        case RESOURCE_INSTANCE_CREATE_TOKEN:
        case RESOURCE_INSTANCE_INITIALIZE_TOKEN:
          readResourceInstanceCreateToken(token == RESOURCE_INSTANCE_INITIALIZE_TOKEN);
          myIs.putBytes(dataOut);
          break;
        case RESOURCE_INSTANCE_DELETE_TOKEN:
          readResourceInstanceDeleteToken();
          myIs.putBytes(dataOut);
          break;
        case SAMPLE_TOKEN:
          readSampleToken();
          myIs.putBytes(dataOut);
          break;
        default:
          throw new IOException(String.format("Unexpected token byte value: %s",
              token));
      }
      return true;
    } catch (EOFException ignore) {
      return false;
    }
  }

  private File getOutputName() {
    String inName = archiveName.getPath();
    StringBuilder buf = new StringBuilder(inName.length() + 4);
    int idx = inName.lastIndexOf('.');
    if (idx == -1) {
      buf.append(inName);
    } else {
      buf.append(inName, 0, idx);
    }
    buf.append('-').append(splitCount);
    if (idx != -1) {
      buf.append(inName.substring(idx));
    }
    return new File(buf.toString());
  }

  private void startSplit() throws IOException {
    currentDuration = 0;
    splitCount++;
    if (archiveName.getPath().endsWith(".gz")) {
      output = new GZIPOutputStream(new FileOutputStream(getOutputName()), BUFFER_SIZE);
    } else {
      output = new BufferedOutputStream(new FileOutputStream(getOutputName()), BUFFER_SIZE);
    }
    dataOut = new DataOutputStream(output);


    if (splitCount > 1) {
      dataOut.writeByte(HEADER_TOKEN);
      dataOut.writeByte(ARCHIVE_VERSION);
      dataOut.writeLong(startTimeStamp);
      dataOut.writeLong(systemId);
      dataOut.writeLong(systemStartTimeStamp);
      dataOut.writeInt(timeZoneOffset);
      dataOut.writeUTF(timeZoneName);
      dataOut.writeUTF(systemDirectory);
      dataOut.writeUTF(productVersion);
      dataOut.writeUTF(os);
      dataOut.writeUTF(machine);
    }

    for (int i = 0; i < globalTokenCount; i++) {
      dataOut.write(globalTokens[i]);
    }
    for (int i = 0; i < resourceInstanceTokens.length; i++) {
      if (resourceInstanceTokens[i] != null) {
        dataOut.write(resourceInstanceTokens[i]);
        byte[] instTypeCodes = resourceInstanceTypeCodes[i];
        long[] instBits = resourceInstanceBits[i];
        for (int j = 0; j < instBits.length; j++) {
          StatArchiveWriter.writeStatValue(instTypeCodes[j], instBits[j], dataOut);
        }
      }
    }
  }

  private void endSplit() {
    try {
      dataOut.flush();
    } catch (IOException ignored) {
    }
    try {
      output.close();
    } catch (IOException ex) {
      System.err.println("[warning] could not close " + getOutputName());
    }
  }

  public void split() throws IOException {
    boolean done = false;
    do {
      done = true;
      startSplit();
      while (readToken()) {
        if (currentDuration >= splitDuration) {
          done = false;
          break;
        }
      }
      endSplit();
    } while (!done);
  }

  private static class MyFilterInputStream extends FilterInputStream {
    private byte[] readBytes = new byte[32000];
    private int idx = 0;

    public MyFilterInputStream(InputStream in) {
      super(in);
    }

    /**
     * Returns all the bytes, read or skipped, since the last reset.
     */
    public byte[] getBytes() {
      byte[] result = new byte[idx];
      System.arraycopy(readBytes, 0, result, 0, result.length);
      return result;
    }

    /**
     * Writes all the bytes, read or skipped, since the last reset to the specified output stream.
     * Does a mark and reset after the write.
     */
    public void putBytes(DataOutputStream dataOut) throws IOException {
      dataOut.write(readBytes, 0, idx);
      idx = 0;
    }

    @Override
    public void close() throws IOException {
      readBytes = null;
      super.close();
    }

    @Override
    public synchronized void reset() throws IOException {
      idx = 0;
      super.reset();
    }

    @Override
    public long skip(long n) throws IOException {
      makeRoom((int) n);
      int result = super.read(readBytes, idx, (int) n);
      if (result == -1) {
        return 0;
      } else {
        idx += result;
        return result;
      }
    }

    private void makeRoom(int n) {
      if (idx + n > readBytes.length) {
        byte[] tmp = new byte[readBytes.length + n + 1024];
        System.arraycopy(readBytes, 0, tmp, 0, readBytes.length);
        readBytes = tmp;
      }
    }

    @Override
    public int read() throws IOException {
      int result = super.read();
      if (result != -1) {
        makeRoom(1);
        readBytes[idx] = (byte) result;
        idx += 1;
      }
      return result;
    }

    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int result = super.read(b, off, len);
      if (result != -1) {
        makeRoom(result);
        System.arraycopy(b, off, readBytes, idx, result);
        idx += result;
      }
      return result;
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err
          .println("Usage: org.apache.geode.internal.statistics.ArchiveSplitter <archive.gfs>");
      ExitCode.FATAL.doSystemExit();
    }
    ArchiveSplitter as = new ArchiveSplitter(new File(args[0]));
    as.split();
  }

}
