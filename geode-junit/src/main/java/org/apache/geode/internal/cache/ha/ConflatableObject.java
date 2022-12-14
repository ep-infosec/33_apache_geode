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
package org.apache.geode.internal.cache.ha;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.geode.DataSerializable;
import org.apache.geode.DataSerializer;
import org.apache.geode.internal.cache.Conflatable;
import org.apache.geode.internal.cache.EventID;

/**
 * Implementing class for <code>Conflatable</code> interface. Objects of this class will be add to
 * the queue
 *
 *
 */
public class ConflatableObject implements Conflatable, DataSerializable {

  /** The key for this entry */
  private Object key;

  /** The value for this entry */
  private Object value;

  /** The unique <code>EventID</code> object for this entry */
  private EventID id;

  /** boolean to indicate whether this entry should be conflated or not */
  private boolean conflate;

  /** The region to which this entry belongs */
  private String regionname;

  public ConflatableObject() {

  }

  /**
   * Constructor
   *
   * @param key - The key for this entry
   * @param value - The value for this entry
   * @param eventId - eventID object for this entry
   * @param conflate - conflate it true
   * @param regionname - The region to which this entry belongs
   */
  public ConflatableObject(Object key, Object value, EventID eventId, boolean conflate,
      String regionname) {
    this.key = key;
    this.value = value;
    id = eventId;
    this.conflate = conflate;
    this.regionname = regionname;
  }

  /**
   * Returns whether the object should be conflated
   *
   * @return whether the object should be conflated
   */
  @Override
  public boolean shouldBeConflated() {
    return conflate;
  }

  /**
   * Returns the name of the region for this <code>Conflatable</code>
   *
   * @return the name of the region for this <code>Conflatable</code>
   */
  @Override
  public String getRegionToConflate() {
    return regionname;
  }

  /**
   * Returns the key for this <code>Conflatable</code>
   *
   * @return the key for this <code>Conflatable</code>
   */
  @Override
  public Object getKeyToConflate() {
    return key;
  }

  /**
   * Returns the value for this <code>Conflatable</code>
   *
   * @return the value for this <code>Conflatable</code>
   */
  @Override
  public Object getValueToConflate() {
    return value;
  }

  /**
   * Sets the latest value for this <code>Conflatable</code>
   *
   * @param value The latest value
   */
  @Override
  public void setLatestValue(Object value) {
    throw new UnsupportedOperationException("setLatestValue should not be used");
  }

  /**
   * Return this event's identifier
   *
   * @return this event's identifier
   */
  @Override
  public EventID getEventId() {
    return id;
  }

  /**
   * @return Returns the conflate.
   */
  boolean isConflate() {
    return conflate;
  }

  /**
   * @param conflate The conflate to set.
   */
  void setConflate(boolean conflate) {
    this.conflate = conflate;
  }

  /**
   * @return Returns the id.
   */
  EventID getId() {
    return id;
  }

  /**
   * @param id The id to set.
   */
  void setId(EventID id) {
    this.id = id;
  }

  /**
   * @return Returns the key.
   */
  Object getKey() {
    return key;
  }

  /**
   * @param key The key to set.
   */
  void setKey(Object key) {
    this.key = key;
  }

  /**
   * @return Returns the regionname.
   */
  String getRegionname() {
    return regionname;
  }

  /**
   * @param regionname The regionname to set.
   */
  void setRegionname(String regionname) {
    this.regionname = regionname;
  }

  /**
   * @return Returns the value.
   */
  Object getValue() {
    return value;
  }

  /**
   * @param value The value to set.
   */
  void setValue(Object value) {
    this.value = value;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    DataSerializer.writeObject(key, out);
    DataSerializer.writeObject(value, out);
    DataSerializer.writeObject(id, out);
    out.writeBoolean(conflate);
    out.writeUTF(regionname);
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    key = DataSerializer.readObject(in);
    value = DataSerializer.readObject(in);
    id = DataSerializer.readObject(in);
    conflate = in.readBoolean();
    regionname = in.readUTF();
  }
}
