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
package org.apache.geode.internal.util.concurrent;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

/**
 * A copy on write hash map.
 *
 * Note that the entryKey and keySet of this map are unmodifable. Should be easy to make them
 * modifiable at a future time.
 *
 *
 */
public class CopyOnWriteHashMap<K, V> extends AbstractMap<K, V>
    implements ConcurrentMap<K, V>, Serializable {
  private volatile Map<K, V> map = Collections.emptyMap();

  public CopyOnWriteHashMap() {

  }

  public CopyOnWriteHashMap(Map map) {
    putAll(map);
  }


  @Override
  public V get(Object key) {
    return map.get(key);
  }



  @Override
  public synchronized V put(K key, V value) {
    HashMap<K, V> tmp = new HashMap<>(map);
    V result = tmp.put(key, value);
    map = Collections.unmodifiableMap(tmp);
    return result;
  }



  @Override
  public synchronized void putAll(Map<? extends K, ? extends V> m) {
    HashMap<K, V> tmp = new HashMap<>(map);
    tmp.putAll(m);
    map = Collections.unmodifiableMap(tmp);
  }



  @Override
  public synchronized V remove(Object key) {
    HashMap<K, V> tmp = new HashMap<>(map);
    V result = tmp.remove(key);
    map = Collections.unmodifiableMap(tmp);
    return result;
  }



  @Override
  public synchronized void clear() {
    map = Collections.emptyMap();
  }


  @Override
  public Set<java.util.Map.Entry<K, V>> entrySet() {
    return map.entrySet();
  }



  @Override
  public int size() {
    return map.size();
  }



  @Override
  public boolean isEmpty() {
    return map.isEmpty();
  }



  @Override
  public boolean containsValue(Object value) {
    return map.containsValue(value);
  }



  @Override
  public boolean containsKey(Object key) {
    return map.containsKey(key);
  }



  @Override
  public Set<K> keySet() {
    return map.keySet();
  }



  @Override
  public Collection<V> values() {
    return map.values();
  }



  @Override
  public boolean equals(Object o) {
    return map.equals(o);
  }



  @Override
  public int hashCode() {
    return map.hashCode();
  }



  @Override
  public String toString() {
    return map.toString();
  }



  @Override
  protected Object clone() throws CloneNotSupportedException {
    CopyOnWriteHashMap<K, V> clone = new CopyOnWriteHashMap<>();
    clone.map = map;
    return clone;
  }

  public Map<K, V> getSnapshot() {
    return Collections.unmodifiableMap(map);
  }

  @Override
  public synchronized V putIfAbsent(K key, V value) {
    V oldValue = map.get(key);
    if (oldValue == null) {
      put(key, value);
      return null;
    } else {
      return oldValue;
    }
  }

  @Override
  public synchronized boolean remove(Object key, Object value) {
    V oldValue = map.get(key);
    if (oldValue != null && oldValue.equals(value)) {
      remove(key);
      return true;
    }

    return false;
  }

  @Override
  public synchronized boolean replace(K key, V oldValue, V newValue) {
    V existingValue = map.get(key);
    if (existingValue != null && existingValue.equals(oldValue)) {
      put(key, newValue);
      return true;
    }
    return false;
  }

  @Override
  public synchronized V replace(K key, V value) {
    if (map.containsKey(key)) {
      return put(key, value);
    } else {
      return null;
    }
  }
}
