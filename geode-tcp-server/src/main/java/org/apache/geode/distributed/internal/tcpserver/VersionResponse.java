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
package org.apache.geode.distributed.internal.tcpserver;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.geode.internal.serialization.BasicSerializable;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;

/**
 * An internal message sent back to TcpClient from a TcpServer to respond to a
 *
 * {@link VersionRequest}
 */
public class VersionResponse implements BasicSerializable {
  private short versionOrdinal = KnownVersion.TOKEN.ordinal();

  public short getVersionOrdinal() {
    return versionOrdinal;
  }

  public void setVersionOrdinal(short versionOrdinal) {
    this.versionOrdinal = versionOrdinal;
  }

  @Override
  public void toData(final DataOutput out, final SerializationContext context) throws IOException {
    out.writeShort(versionOrdinal);
  }

  @Override
  public void fromData(final DataInput in, final DeserializationContext context)
      throws IOException, ClassNotFoundException {
    versionOrdinal = in.readShort();
  }
}
