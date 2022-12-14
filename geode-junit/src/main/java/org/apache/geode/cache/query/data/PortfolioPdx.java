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

/******
 * THIS FILE IS ENCODED IN UTF-8 IN ORDER TO TEST UNICODE IN FIELD NAMES. THE ENCODING MUST BE
 * SPECIFIED AS UTF-8 WHEN COMPILED
 *******/

package org.apache.geode.cache.query.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.geode.internal.Assert;
import org.apache.geode.pdx.PdxReader;
import org.apache.geode.pdx.PdxSerializable;
import org.apache.geode.pdx.PdxWriter;


public class PortfolioPdx implements Serializable, PdxSerializable {
  public static boolean DEBUG = false;

  public enum Day {
    Sunday, Monday, Tuesday, Wednesday, Thursday, Friday, Saturday
  }

  public Day aDay;
  public short shortID;
  static transient List dayList;
  private int ID;
  public String pkid;
  public PositionPdx position1;
  public PositionPdx position2;
  public Object[] position3;
  int position3Size;
  public String description;
  public long createTime;
  public HashMap positions = new HashMap();
  public HashMap collectionHolderMap = new HashMap();
  String type;
  public String status;
  public String[] names = {"aaa", "bbb", "ccc", "ddd"};
  public String unicodeṤtring;
  private final long longMinValue = Long.MIN_VALUE;
  private final float floatMinValue = Float.MIN_VALUE;
  private final double doubleMinValue = Double.MIN_VALUE;

  public static int numInstance = 0;

  /*
   * public String getStatus(){ return status;
   */
  public int getID() {
    return ID;
  }

  public long getCreateTime() {
    return createTime;
  }

  public void setCreateTime(long time) {
    createTime = time;
  }

  public String getPk() {
    return pkid;
  }

  public HashMap getPositions() {
    return positions;
  }

  public HashMap getPositions(String str) {
    return positions;
  }

  public HashMap getPositions(Integer i) {
    return positions;
  }

  public HashMap getPositions(int i) {
    return positions;
  }

  public PositionPdx getP1() {
    return position1;
  }

  public PositionPdx getP2() {
    return position2;
  }

  public HashMap getCollectionHolderMap() {
    return collectionHolderMap;
  }

  public ComparableWrapper getCW(int x) {
    return new ComparableWrapper(x);
  }

  public boolean testMethod(boolean booleanArg) {
    return true;
  }

  public boolean isActive() {
    return status.equals("active");
  }

  public static String[] secIds = {"SUN", "IBM", "YHOO", "GOOG", "MSFT", "AOL", "APPL", "ORCL",
      "SAP", "DELL", "RHAT", "NOVL", "HP"};

  static {
    dayList = new ArrayList();
    dayList.addAll(EnumSet.allOf(Day.class));
  }

  /* public no-arg constructor required for Deserializable */
  public PortfolioPdx() {
    numInstance++;
    if (DEBUG) {
      Thread.dumpStack();
    }
    // GemFireCacheImpl.getInstance().getLogger().fine(new Exception("DEBUG"));
  }

  public PortfolioPdx(int i) {
    aDay = (Day) (dayList.get((i % dayList.size())));
    if (DEBUG) {
      Thread.dumpStack();
    }
    numInstance++;
    ID = i;
    if (i % 2 == 0) {
      description = null;
    } else {
      description = "XXXX";
    }
    pkid = "" + i;
    status = i % 2 == 0 ? "active" : "inactive";
    type = "type" + (i % 3);
    position1 = new PositionPdx(secIds[PositionPdx.cnt % secIds.length], PositionPdx.cnt * 1000L);
    if (i % 2 != 0) {
      position2 = new PositionPdx(secIds[PositionPdx.cnt % secIds.length], PositionPdx.cnt * 1000L);
    } else {
      position2 = null;
    }

    positions.put(secIds[PositionPdx.cnt % secIds.length],
        new PositionPdx(secIds[PositionPdx.cnt % secIds.length], PositionPdx.cnt * 1000L));
    positions.put(secIds[PositionPdx.cnt % secIds.length],
        new PositionPdx(secIds[PositionPdx.cnt % secIds.length], PositionPdx.cnt * 1000L));

    collectionHolderMap.put("0", new CollectionHolder());
    collectionHolderMap.put("1", new CollectionHolder());
    collectionHolderMap.put("2", new CollectionHolder());
    collectionHolderMap.put("3", new CollectionHolder());

    unicodeṤtring = i % 2 == 0 ? "ṤṶẐ" : "ṤẐṶ";
    Assert.assertTrue(unicodeṤtring.length() == 3);
    // GemFireCacheImpl.getInstance().getLogger().fine(new Exception("DEBUG"));
  }

  public PortfolioPdx(int i, int j) {
    this(i);
    position1.portfolioId = j;
    position3 = new Object[3];
    for (int k = 0; k < position3.length; k++) {
      PositionPdx p = new PositionPdx(secIds[k], (k + 1) * 1000L);
      p.portfolioId = (k + 1);
      position3[k] = p;
    }
  }

  private boolean eq(Object o1, Object o2) {
    return o1 == null ? o2 == null : o1.equals(o2);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PortfolioPdx)) {
      return false;
    }
    PortfolioPdx p2 = (PortfolioPdx) o;
    return ID == p2.ID;
  }

  @Override
  public int hashCode() {
    return ID;
  }


  public String toString() {
    String out =
        "PortfolioPdx [ID=" + ID + " status=" + status + " type=" + type + " pkid=" + pkid + "\n ";
    for (final Object o : positions.entrySet()) {
      Map.Entry entry = (Map.Entry) o;
      out += entry.getKey() + ":" + entry.getValue() + ", ";
    }
    out += "\n P1:" + position1 + ", P2:" + position2;
    return out + "\n]";
  }

  /**
   * Getter for property type.S
   *
   * @return Value of property type.
   */
  public String getType() {
    return type;
  }

  public boolean boolFunction(String strArg) {
    return "active".equals(strArg);
  } // added by vikramj

  public int intFunction(int j) {
    return j;
  } // added by vikramj

  public String funcReturnSecId(Object o) {
    return ((PositionPdx) o).getSecId();
  }// added by vikramj

  public long longFunction(long j) {
    return j;
  }

  public float getFloatMinValue() {
    return floatMinValue;
  }

  public float getLongMinValue() {
    return longMinValue;
  }

  public double getDoubleMinValue() {
    return doubleMinValue;
  }

  @Override
  public void fromData(PdxReader in) {
    ID = in.readInt("ID");
    shortID = in.readShort("shortID");
    pkid = in.readString("pkid");
    position1 = (PositionPdx) in.readObject("position1");
    position2 = (PositionPdx) in.readObject("position2");
    positions = (HashMap) in.readObject("positions");
    collectionHolderMap = (HashMap) in.readObject("collectionHolderMap");
    type = in.readString("type");
    status = in.readString("status");
    names = in.readStringArray("names");
    description = in.readString("description");
    createTime = in.readLong("createTime");
    // Read Position3
    position3 = in.readObjectArray("position3");
    aDay = (Day) in.readObject("aDay");
  }

  @Override
  public void toData(PdxWriter out) {
    out.writeInt("ID", ID);
    out.writeShort("shortID", shortID);
    out.writeString("pkid", pkid);
    out.writeObject("position1", position1);
    out.writeObject("position2", position2);
    out.writeObject("positions", positions);
    out.writeObject("collectionHolderMap", collectionHolderMap);
    out.writeString("type", type);
    out.writeString("status", status);
    out.writeStringArray("names", names);
    out.writeString("description", description);
    out.writeLong("createTime", createTime);
    // Write Position3.
    out.writeObjectArray("position3", position3);
    out.writeObject("aDay", aDay);
    // Identity Field.
    out.markIdentityField("ID");
  }

}
