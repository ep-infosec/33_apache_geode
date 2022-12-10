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
package org.apache.geode.cache.query.internal;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import org.apache.geode.InternalGemFireError;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.internal.types.CollectionTypeImpl;
import org.apache.geode.cache.query.types.CollectionType;
import org.apache.geode.cache.query.types.ObjectType;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.cache.EntriesSet;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;

/**
 * Implementation of SelectResults that wraps an existing java.util.Collection and optionally adds a
 * specified element type. Considered ordered if the base collection is a List; duplicates allowed
 * unless base collection is a Set. Defaults to modifiable unless set otherwise.
 *
 * @since GemFire 4.0
 */
public class ResultsCollectionWrapper implements SelectResults, DataSerializableFixedID {

  private Collection base;
  private CollectionType collectionType;
  /**
   * Holds value of property modifiable.
   */
  private boolean modifiable = true;

  final Object limitLock = new Object();
  private int limit;

  private final boolean hasLimitIterator;
  private final boolean limitImposed;


  /** no-arg constructor required for DataSerializable */
  public ResultsCollectionWrapper() {
    limit = -1;
    hasLimitIterator = false;
    limitImposed = false;
  }

  public ResultsCollectionWrapper(ObjectType constraint, Collection base, int limit) {
    validateConstraint(constraint);
    this.base = base;
    collectionType = new CollectionTypeImpl(getBaseClass(), constraint);
    this.limit = limit;
    if (this.limit > -1 && this.base.size() > this.limit) {
      if (collectionType.isOrdered()) {
        hasLimitIterator = true;
      } else {
        hasLimitIterator = false;
        // Asif:Take only elements upto the limit so that order is predictable
        // If it is a sorted set it will not come here & so we need not worry
        // as to truncation happens at end or start
        int truncate = this.base.size() - limit;
        synchronized (this.base) {
          Iterator itr = this.base.iterator();
          for (int i = 0; i < truncate; ++i) {
            itr.next();
            itr.remove();
          }
        }
      }
    } else {
      hasLimitIterator = false;
    }

    limitImposed = this.limit > -1;
  }

  public ResultsCollectionWrapper(ObjectType constraint, Collection base) {
    validateConstraint(constraint);
    this.base = base;
    collectionType = new CollectionTypeImpl(getBaseClass(), constraint);
    limit = -1;
    hasLimitIterator = false;
    limitImposed = false;
  }

  private void validateConstraint(ObjectType constraint) {
    if (constraint == null) {
      throw new IllegalArgumentException(
          "constraint cannot be null");
    }
    // must be public
    if (!Modifier.isPublic(constraint.resolveClass().getModifiers())) {
      throw new IllegalArgumentException(
          "constraint class must be public");
    }
  }

  // @todo should we bother taking the performance hit to check the constraint?
  private void checkConstraint(Object obj) {
    ObjectType elementType = collectionType.getElementType();
    if (!elementType.resolveClass().isInstance(obj)) {
      throw new InternalGemFireError(
          String.format("Constraint Violation: %s is not a %s",
              obj.getClass().getName(), elementType));
    }
  }

  // java.lang.Object methods
  @Override
  public String toString() {
    return base.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SelectResults)) {
      return false;
    }
    if (!collectionType.equals(((SelectResults) obj).getCollectionType())) {
      return false;
    }
    return base.equals(obj);
  }

  @Override
  public int hashCode() {
    return base.hashCode();
  }

  /// java.util.Collection interface
  @Override
  public boolean add(Object o) {
    // checkConstraint(o);
    if (limitImposed) {
      throw new UnsupportedOperationException(
          "Addition to the SelectResults not allowed as the query result is constrained by LIMIT");
    }
    return base.add(o);
  }

  @Override
  public boolean addAll(Collection c) {
    if (limitImposed) {
      throw new UnsupportedOperationException(
          "Addition to the SelectResults not allowed as  the query result is constrained by LIMIT");
    }
    return base.addAll(c);
    // boolean changed = false;
    // Iterator i = c.iterator();
    // while (i.hasNext())
    // checkConstraint(i.next());
    // changed |= this.base.addAll(c);
    // return changed;
  }

  @Override
  public int size() {
    // return this.limit == -1 ? this.base.size():this.limit;
    // Asif: If the number of elements in Collection is more than limit, size is
    // governed by limit
    if (hasLimitIterator) {
      synchronized (limitLock) {
        return limit;
      }
    } else {
      return base.size();
    }
  }

  @Override
  public Iterator iterator() {
    if (hasLimitIterator) {
      return new LimitIterator();
    } else {
      return base.iterator();
    }
  }

  @Override
  public void clear() {
    /*
     * if( this.limit > -1) { throw new
     * UnsupportedOperationException("Clearing the SelectResults not allowed as  the query result is constrained by LIMIT"
     * ); }
     */
    base.clear();
  }

  /*
   * Asif: May throw ConcurrentModificationException
   */
  @Override
  public boolean contains(Object obj) {
    if (hasLimitIterator) {
      // Keith: Optimize case where contains is false, avoids iteration
      boolean peak = base.contains(obj);
      if (!peak) {
        return false;
      }
      Iterator itr = iterator();
      boolean found = false;
      while (itr.hasNext()) {
        if (itr.next().equals(obj)) {
          found = true;
          break;
        }
      }
      return found;
    } else {
      return base.contains(obj);
    }
  }

  // Asif :The limit case has a very inefficient implementation
  // May throw ConcurrentModificationException
  @Override
  public boolean containsAll(Collection collection) {
    if (hasLimitIterator) {
      Iterator itr = collection.iterator();
      boolean containsAll = true;
      while (itr.hasNext() && containsAll) {
        containsAll = contains(itr.next());
      }
      return containsAll;
    } else {
      return base.containsAll(collection);
    }
  }

  @Override
  public boolean isEmpty() {
    int size = -1;
    synchronized (limitLock) {
      size = limit;
    }
    return base.isEmpty() || size == 0;
  }

  // Asif: May throw ConucrrentModificationException
  @Override
  public boolean remove(Object obj) {
    /*
     * if( this.limit > -1) { throw new UnsupportedOperationException("Removal from the
     * SelectResults not allowed as the query result is constrained by LIMIT"); }
     */
    if (hasLimitIterator) {
      Iterator itr = iterator();
      boolean removed = false;
      Object element;
      while (itr.hasNext()) {
        element = itr.next();
        if ((obj == null && element == null) || (obj.equals(element))) {
          itr.remove();
          removed = true;
          break;
        }
      }
      return removed;
    } else {
      return base.remove(obj);
    }
  }

  @Override
  public boolean removeAll(Collection collection) {
    /*
     * if( this.limit > -1) { throw new UnsupportedOperationException("Removal from the
     * SelectResults not allowed as the query result is constrained by LIMIT"); }
     */
    if (hasLimitIterator) {
      Iterator itr = iterator();
      boolean removed = false;
      Object element;
      while (itr.hasNext()) {
        element = itr.next();
        if (collection.contains(element)) {
          itr.remove();
          removed = true;
        }
      }
      return removed;
    } else {
      return base.removeAll(collection);
    }
  }

  @Override
  public boolean retainAll(Collection collection) {
    /*
     * if( this.limit > -1) { throw new UnsupportedOperationException("Modification of the
     * SelectResults not allowed as the query result is constrained by LIMIT"); }
     */
    if (hasLimitIterator) {
      Iterator itr = iterator();
      boolean changed = false;
      Object element;
      while (itr.hasNext()) {
        element = itr.next();
        if (!collection.contains(element)) {
          itr.remove();
          changed = true;
        }
      }
      return changed;
    } else {
      return retainAll(collection);
    }
  }

  public static Object[] collectionToArray(Collection c) {
    // guess the array size; expect to possibly be different
    int len = c.size();
    Object[] arr = new Object[len];
    Iterator itr = c.iterator();
    int idx = 0;
    while (true) {
      while (idx < len && itr.hasNext()) {
        arr[idx++] = itr.next();
      }
      if (!itr.hasNext()) {
        if (idx == len) {
          return arr;
        }
        // otherwise have to trim
        return Arrays.copyOf(arr, idx, Object[].class);
      }
      // otherwise, have to grow
      int newcap = ((arr.length / 2) + 1) * 3;
      if (newcap < arr.length) {
        // overflow
        if (arr.length < Integer.MAX_VALUE) {
          newcap = Integer.MAX_VALUE;
        } else {
          throw new OutOfMemoryError("required array size too large");
        }
      }
      arr = Arrays.copyOf(arr, newcap, Object[].class);
      len = newcap;
    }
  }

  public static Object[] collectionToArray(Collection c, Object[] a) {
    Class aType = a.getClass();
    // guess the array size; expect to possibly be different
    int len = c.size();
    Object[] arr =
        (a.length >= len ? a : (Object[]) Array.newInstance(aType.getComponentType(), len));
    Iterator itr = c.iterator();
    int idx = 0;
    while (true) {
      while (idx < len && itr.hasNext()) {
        arr[idx++] = itr.next();
      }
      if (!itr.hasNext()) {
        if (idx == len) {
          return arr;
        }
        if (arr == a) {
          // orig array -> null terminate
          a[idx] = null;
          return a;
        } else {
          // have to trim
          return Arrays.copyOf(arr, idx, aType);
        }
      }
      // otherwise, have to grow
      int newcap = ((arr.length / 2) + 1) * 3;
      if (newcap < arr.length) {
        // overflow
        if (arr.length < Integer.MAX_VALUE) {
          newcap = Integer.MAX_VALUE;
        } else {
          throw new OutOfMemoryError("required array size too large");
        }
      }
      arr = Arrays.copyOf(arr, newcap, aType);
      len = newcap;
    }
  }

  @Override
  public Object[] toArray() {
    if (hasLimitIterator) {
      return collectionToArray(this);
    } else {
      return base.toArray();
    }
  }

  @Override
  public Object[] toArray(Object[] obj) {
    if (hasLimitIterator) {
      return collectionToArray(this, obj);
    } else {
      return base.toArray(obj);
    }
  }

  // Asif: It is possible that if the underlying List
  // when exposed by this method is modified externally
  // then the ResultsCollectionWrapper object's limit
  // functionality may not work correctly
  @Override
  public List asList() {
    if (hasLimitIterator) {
      List returnList = null;
      if (base instanceof List) {
        int truncate = base.size() - limit;
        if (truncate > limit) {
          returnList = new ArrayList(this);
        } else {
          ListIterator li = ((List) base).listIterator(base.size());
          for (int j = 0; j < truncate; ++j) {
            li.previous();
            li.remove();
          }
          returnList = (List) base;
        }
      } else {
        returnList = new ArrayList(this);
      }
      return returnList;
    } else {
      return base instanceof List ? (List) base : new ArrayList(base);
    }
  }

  // Asif: It is possible that if the underlying Set
  // when exposed by this method is modified externally
  // then the ResultsCollectionWrapper object's limit
  // functionality may not work correctly

  @Override
  public Set asSet() {
    if (hasLimitIterator) {
      Set returnSet = null;
      if (base instanceof Set) {
        Iterator itr = base.iterator();
        int j = 0;
        while (itr.hasNext()) {
          itr.next();
          ++j;
          if (j > limit) {
            itr.remove();
          }
        }
        returnSet = (Set) base;
      } else {
        returnSet = new HashSet(this);
      }
      return returnSet;
    } else {
      return base instanceof Set ? (Set) base : new HashSet(base);
    }
  }

  @Override
  public void setElementType(ObjectType elementType) {
    collectionType = new CollectionTypeImpl(getBaseClass(), elementType);
  }

  @Override
  public CollectionType getCollectionType() {
    return collectionType;
  }

  /**
   * Getter for property modifiable.
   *
   * @return Value of property modifiable.
   */
  @Override
  public boolean isModifiable() {
    return modifiable;
  }

  /**
   * Setter for property modifiable.
   *
   * @param modifiable New value of property modifiable.
   */
  public void setModifiable(boolean modifiable) {
    this.modifiable = modifiable;
  }

  // Asif : If the underlying collection is a ordered
  // one then it will allow duplicates. In such case , our
  // limit iterator will correctly give the number of occurrences
  // but if the underlying collection is not ordered , it will
  // not allow duplicates, but then since we have already truncated
  // the unordered set, it will work correctly.
  @Override
  public int occurrences(Object element) {
    if (!getCollectionType().allowsDuplicates() && !hasLimitIterator) {
      return base.contains(element) ? 1 : 0;
    }
    // expensive!!
    int count = 0;
    /* this.base.iterator() */
    for (Object v : this) {
      if (element == null ? v == null : element.equals(v)) {
        count++;
      }
    }
    return count;
  }

  @Override
  public int getDSFID() {
    return RESULTS_COLLECTION_WRAPPER;
  }


  /**
   * Writes the state of this object as primitive data to the given <code>DataOutput</code>.
   *
   * @throws IOException A problem occurs while writing to <code>out</code>
   */
  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    // special case when wrapping a ResultsBag.SetView
    boolean isBagSetView = base instanceof Bag.SetView;
    out.writeBoolean(isBagSetView);
    if (isBagSetView) {
      InternalDataSerializer.writeSet(base, out);
    } else {
      context.getSerializer().writeObject(base, out);
    }
    context.getSerializer().writeObject(collectionType, out);
    out.writeBoolean(modifiable);
  }

  /**
   * Reads the state of this object as primitive data from the given <code>DataInput</code>.
   *
   * @throws IOException A problem occurs while reading from <code>in</code>
   * @throws ClassNotFoundException A class could not be loaded while reading from <code>in</code>
   */
  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    boolean isBagSetView = in.readBoolean();
    if (isBagSetView) {
      base = InternalDataSerializer.readSet(in);
    } else {
      base = context.getDeserializer().readObject(in);
    }
    collectionType = context.getDeserializer().readObject(in);
    modifiable = in.readBoolean();
  }

  /**
   * Abstract the base class to Set if it implements Set (instead of using the concrete class as the
   * type). Fix for #41249: Prevents the class ResultsBag.SetView from being serialized to an older
   * version client.
   *
   * This kind of abstraction could be done in the future for Lists, etc., as well, if desired, but
   * there is no requirement for this at this time
   */
  private Class getBaseClass() {
    if (base instanceof Ordered) {
      return Ordered.class;
    } else if (base instanceof TreeSet) {
      return TreeSet.class;
    } else if (base instanceof Set) {
      return Set.class;
    } else {
      return base.getClass();
    }
  }

  class LimitIterator implements Iterator {
    private final Iterator iter;

    private int currPos = 0;

    private final int localLimit;

    LimitIterator() {
      synchronized (limitLock) {
        iter = base.iterator();
        localLimit = limit;
      }
    }

    @Override
    public boolean hasNext() {
      return currPos < localLimit;
    }

    @Override
    public Object next() {
      if (currPos == localLimit) {
        throw new NoSuchElementException();
      } else {
        Object obj = iter.next();
        ++currPos;
        return obj;
      }
    }

    /**
     * No thread safe
     */
    @Override
    public void remove() {
      if (currPos == 0) {
        throw new IllegalStateException("next() must be called before remove()");
      } else {
        synchronized (limitLock) {
          iter.remove();
          --limit;
        }

      }
      // throw new UnsupportedOperationException("Removal from the SelectResults
      // not allowed as the query result is constrained by LIMIT");
    }
  }

  public void setKeepSerialized(boolean keepSerialized) {
    if (base instanceof EntriesSet) {
      ((EntriesSet) base).setKeepSerialized(keepSerialized);
    }
  }

  public void setIgnoreCopyOnReadForQuery(boolean ignore) {
    if (base instanceof EntriesSet) {
      ((EntriesSet) base).setIgnoreCopyOnReadForQuery(ignore);
    }
  }

  @Override
  public KnownVersion[] getSerializationVersions() {
    return null;
  }
}