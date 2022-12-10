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
package org.apache.geode.internal.cache.locks;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.distributed.internal.locks.LockGrantorId;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;

/**
 * Identifies a group of transaction locks.
 */
public class TXLockIdImpl implements TXLockId, DataSerializableFixedID {

  private static final long serialVersionUID = 8579214625084490134L;

  /** DistributionManager id for this member */
  private InternalDistributedMember memberId;

  /** Increments for each txLockId that is generated in this vm */
  @MakeNotStatic
  private static int txCount = 0;

  /** Unique identifier within this member's vm */
  private int id;

  /** Identifies the lock grantor that granted the lock */
  private transient LockGrantorId grantedBy;

  /** Creates new instance of TXLockIdImpl */
  public TXLockIdImpl(InternalDistributedMember memberId) {
    this.memberId = memberId;
    synchronized (TXLockIdImpl.class) {
      id = txCount++;
    }
  }

  @Override
  public int getCount() {
    return id;
  }

  @Override
  public InternalDistributedMember getMemberId() {
    return memberId;
  }

  @Override
  public void setLockGrantorId(LockGrantorId lockGrantorId) {
    grantedBy = lockGrantorId;
  }

  @Override
  public LockGrantorId getLockGrantorId() {
    return grantedBy;
  }

  @Override
  public int hashCode() {
    int result = 17;
    final int mult = 37;

    result = mult * result + (memberId == null ? 0 : memberId.hashCode());
    result = mult * result + id;

    return result;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (other == null) {
      return false;
    }
    if (!(other instanceof TXLockIdImpl)) {
      return false;
    }
    final TXLockIdImpl that = (TXLockIdImpl) other;

    if (memberId != that.memberId
        && !(memberId != null && memberId.equals(that.memberId))) {
      return false;
    }
    return id == that.id;
  }

  @Override
  public String toString() {
    return "TXLockId: " + memberId + "-" + id;
  }

  // -------------------------------------------------------------------------
  // DataSerializable support
  // -------------------------------------------------------------------------

  public TXLockIdImpl() {}

  @Override
  public int getDSFID() {
    return TRANSACTION_LOCK_ID;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    toData(out, InternalDataSerializer.createSerializationContext(out));
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    fromData(in, InternalDataSerializer.createDeserializationContext(in));
  }

  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    memberId = context.getDeserializer().readObject(in);
    id = in.readInt();
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    context.getSerializer().writeObject(memberId, out);
    out.writeInt(id);
  }

  public static TXLockIdImpl createFromData(DataInput in)
      throws IOException, ClassNotFoundException {
    TXLockIdImpl result = new TXLockIdImpl();
    result.fromData(in, InternalDataSerializer.createDeserializationContext(in));
    return result;
  }

  @Override
  public KnownVersion[] getSerializationVersions() {
    return null;
  }
}