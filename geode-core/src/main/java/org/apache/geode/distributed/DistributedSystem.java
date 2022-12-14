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

package org.apache.geode.distributed;

import static org.apache.geode.distributed.ConfigurationProperties.CONSERVE_SOCKETS;

import java.io.File;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.geode.CancelCriterion;
import org.apache.geode.LogWriter;
import org.apache.geode.StatisticsFactory;
import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.classloader.ClassPathLoader;
import org.apache.geode.internal.tcp.ConnectionTable;
import org.apache.geode.internal.util.IOUtils;
import org.apache.geode.metrics.internal.InternalDistributedSystemMetricsService;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * A "connection" to a GemFire distributed system. A <code>DistributedSystem</code> is created by
 * invoking the {@link #connect} method with a configuration as described
 * <a href="#configuration">below</a>. A <code>DistributedSystem</code> is used when calling
 * {@link org.apache.geode.cache.CacheFactory#create}. This class should not be confused with the
 * {@link org.apache.geode.admin.AdminDistributedSystem AdminDistributedSystem} interface that is
 * used for administering a distributed system.
 *
 * <P>
 *
 * When a program connects to the distributed system, a "distribution manager" is started in this VM
 * and the other members of the distributed system are located. This discovery is performed by
 * contacting "locators" running on a given host and port. All DistributedSystems that are
 * configured to use the same locators are part of the same distributed system.
 *
 * <P>
 *
 * The current version of GemFire only supports creating one <code>DistributedSystem</code> per
 * virtual machine. Attempts to connect to multiple distributed systems (that is calling
 * {@link #connect} multiple times with different configuration <code>Properties</code>) will result
 * in an {@link IllegalStateException} being thrown (if <code>connect</code> is invoked multiple
 * times with equivalent <code>Properties</code>, then the same instance of
 * <code>DistributedSystem</code> will be returned). A common practice is to connect to the
 * distributed system and store a reference to the <code>DistributedSystem</code> object in a
 * well-known location such as a <code>static</code> variable. This practice provides access to the
 * <code>DistributedSystem</code> slightly faster than invoking <code>connect</code> multiple times.
 * Note that it is always advisable to {@link #disconnect()} from the distributed system when a
 * program will no longer access it. Disconnecting frees up certain resources and allows your
 * application to connect to a different distributed system, if desirable.
 *
 * <P>
 *
 * Users should never subclass this class.
 *
 * @since GemFire 3.0
 */
public abstract class DistributedSystem implements StatisticsFactory {

  /**
   * The instances of <code>DistributedSystem</code> created in this VM. Presently only one connect
   * to a distributed system is allowed in a VM. This set is never modified in place (it is always
   * read only) but the reference can be updated by holders of {@link #existingSystemsLock}.
   */
  @MakeNotStatic
  protected static volatile List<InternalDistributedSystem> existingSystems =
      Collections.emptyList();
  /**
   * This lock must be changed to add or remove a system. It is notified when a system is removed.
   *
   * @see #existingSystems
   */
  protected static final Object existingSystemsLock = new Object();

  /**
   * Connects to a GemFire distributed system with a configuration supplemented by the given
   * properties. See {@linkplain ConfigurationProperties} for available GemFire properties and their
   * meanings.
   * <P>
   * The actual configuration attribute values used to connect comes from the following sources:
   * <OL>
   * <LI>System properties. If a system property named "<code>gemfire.</code><em>propertyName</em>"
   * is defined and its value is not an empty string then its value will be used for the named
   * configuration attribute.
   *
   * <LI>Code properties. Otherwise if a property is defined in the <code>config</code> parameter
   * object and its value is not an empty string then its value will be used for that configuration
   * attribute.
   * <LI>File properties. Otherwise if a property is defined in a configuration property file found
   * by this application and its value is not an empty string then its value will be used for that
   * configuration attribute. A configuration property file may not exist. See the following section
   * for how configuration property files are found.
   * <LI>Defaults. Otherwise a default value is used.
   * </OL>
   * <P>
   * The name of the property file can be specified using the "gemfirePropertyFile" system property.
   * If the system property is set to a relative file name then it is searched for in following
   * locations. If the system property is set to an absolute file name then that file is used as the
   * property file. If the system property is not set, then the name of the property file defaults
   * to "gemfire.properties". The configuration file is searched for in the following locations:
   *
   * <OL>
   * <LI>Current directory (directory in which the VM was launched)</LI>
   * <LI>User's home directory</LI>
   * <LI>Class path (loaded as a {@linkplain ClassLoader#getResource(String) system resource})</LI>
   * </OL>
   *
   * If the configuration file cannot be located, then the property will have its default value as
   * described <a href="#configuration">above</a>.
   *
   * @param config The <a href="#configuration">configuration properties</a> used when connecting to
   *        the distributed system
   * @return a connection to the distributed system that is appropriate for administration
   *
   * @throws IllegalArgumentException If <code>config</code> contains an unknown configuration
   *         property or a configuration property does not have an allowed value. Note that the
   *         values of boolean properties are parsed using
   *         {@link Boolean#valueOf(java.lang.String)}. Therefore all values other than "true"
   *         values will be considered <code>false</code> -- an exception will not be thrown.
   * @throws IllegalStateException If a <code>DistributedSystem</code> with a different
   *         configuration has already been created in this VM or if this VM is
   *         {@link org.apache.geode.admin.AdminDistributedSystem administering} a distributed
   *         system.
   * @throws org.apache.geode.GemFireIOException Problems while reading configuration properties
   *         file or while opening the log file.
   * @throws org.apache.geode.GemFireConfigException The distribution transport is not configured
   *         correctly
   *
   * @deprecated as of 6.5 use {@link CacheFactory#create} or {@link ClientCacheFactory#create}
   *             instead.
   *
   */
  @Deprecated
  public static DistributedSystem connect(Properties config) {
    return InternalDistributedSystem.connectInternal(config, null,
        new InternalDistributedSystemMetricsService.Builder());
  }

  protected static void addSystem(InternalDistributedSystem newSystem) {
    synchronized (existingSystemsLock) {
      int size = existingSystems.size();
      if (size == 0) {
        existingSystems = Collections.singletonList(newSystem);
      } else {
        ArrayList<InternalDistributedSystem> l = new ArrayList<>(size + 1);
        l.addAll(existingSystems);
        l.add(0, newSystem);
        existingSystems = Collections.unmodifiableList(l);
      }
    }
  }

  protected static void removeSystem(InternalDistributedSystem oldSystem) {
    synchronized (existingSystemsLock) {
      List<InternalDistributedSystem> listOfSystems = new ArrayList<>(existingSystems);
      boolean result = listOfSystems.remove(oldSystem);
      if (result) {
        int size = listOfSystems.size();
        if (size == 0) {
          existingSystems = Collections.emptyList();
        } else if (size == 1) {
          existingSystems = Collections.singletonList(listOfSystems.get(0));
        } else {
          existingSystems = Collections.unmodifiableList(listOfSystems);
        }
      }
    }
  }

  /**
   * Sets the calling thread's socket policy. This value will override that default set by the
   * <code>conserve-sockets</code> configuration property.
   *
   * @param conserveSockets If <code>true</code> then calling thread will share socket connections
   *        with other threads. If <code>false</code> then calling thread will have its own sockets.
   * @since GemFire 4.1
   */
  public static void setThreadsSocketPolicy(boolean conserveSockets) {
    if (conserveSockets) {
      ConnectionTable.threadWantsSharedResources();
    } else {
      ConnectionTable.threadWantsOwnResources();
    }
  }



  /**
   * Frees up any socket resources owned by the calling thread.
   *
   * @since GemFire 4.1
   */
  public static void releaseThreadsSockets() {
    ConnectionTable.releaseThreadsSockets();
  }

  /**
   * Returns an existing connection to the distributed system described by the given properties.
   *
   * @param config the properties used when creating a connection to the distributed system
   * @return a connection to the distributed system that is appropriate for administration
   *
   * @since GemFire 4.0
   */
  protected static DistributedSystem getConnection(Properties config) {
    // In an admin VM you can have a connection to more than one
    // distributed system. If we are already connected to the desired
    // distributed system, return that connection.
    for (InternalDistributedSystem existingSystem : existingSystems) {
      if (existingSystem.sameSystemAs(config)) {
        Assert.assertTrue(existingSystem.isConnected());
        return existingSystem;
      }
    }

    return null;
  }

  /**
   * Returns a connection to the distributed system that is appropriate for administration. This
   * method is for internal use only by the admin API.
   *
   * @param props the properties used when creating a connection to the distributed system
   * @return a connection to the distributed system that is appropriate for administration
   *
   * @since GemFire 4.0
   */
  protected static DistributedSystem connectForAdmin(Properties props) {
    DistributedSystem existing = getConnection(props);
    if (existing != null) {
      return existing;

    } else {
      props.setProperty(CONSERVE_SOCKETS, "true");
      return connect(props);
    }
  }

  /**
   * see {@link org.apache.geode.admin.AdminDistributedSystemFactory}
   *
   * @param adminOnly whether this VM is dedicated to administration
   * @since GemFire 5.7
   */
  protected static void setEnableAdministrationOnly(boolean adminOnly) {
    synchronized (existingSystemsLock) {
      if (existingSystems != null && !existingSystems.isEmpty()) {
        throw new IllegalStateException(
            String.format("This VM already has one or more Distributed System connections %s",
                existingSystems));
      }
      ClusterDistributionManager.setIsDedicatedAdminVM(adminOnly);
    }
  }

  ////////////////////// Constructors //////////////////////

  /**
   * Creates a new instance of <code>DistributedSystem</code>. This constructor is protected so that
   * it may only be invoked by subclasses.
   */
  protected DistributedSystem() {

  }

  //////////////////// Instance Methods ////////////////////

  /**
   * Returns the <code>LogWriter</code> used for logging information. See
   * <A href="#logFile">logFile</A>.
   *
   * @return the <code>LogWriter</code> used for logging information
   * @throws IllegalStateException This VM has {@linkplain #disconnect() disconnected} from the
   *         distributed system.
   */
  @Deprecated
  public abstract LogWriter getLogWriter();

  /**
   * Returns the <code>LogWriter</code> used for logging security related information. See
   * <A href="#logFile">logFile</A>.
   *
   * @return the <code>LogWriter</code> used for logging security related information
   * @throws IllegalStateException This VM has {@linkplain #disconnect() disconnected} from the
   *         distributed system.
   * @since GemFire 5.5
   */
  @Deprecated
  public abstract LogWriter getSecurityLogWriter();

  /**
   * Returns the configuration properties.
   *
   * @return the configuration Properties
   */
  public abstract Properties getProperties();

  /**
   * Returns the security specific configuration properties.
   *
   * @return the configuration Properties
   * @since GemFire 5.5
   */
  public abstract Properties getSecurityProperties();

  /**
   *
   * @return the cancel criterion for this system
   */
  public abstract CancelCriterion getCancelCriterion();

  /**
   * Disconnects from this distributed system. This operation will close the distribution manager
   * and render the {@link org.apache.geode.cache.Cache Cache} and all distributed collections
   * obtained from this distributed system inoperable. After a disconnect has completed, a VM may
   * connect to another distributed system.
   *
   * <P>
   *
   * Attempts to access a distributed system after a VM has disconnected from it will result in an
   * {@link IllegalStateException} being thrown.
   *
   * @deprecated as of 6.5 use {@link Cache#close} or {@link ClientCache#close} instead.
   */
  @Deprecated
  public abstract void disconnect();

  /**
   * Returns whether or not this <code>DistributedSystem</code> is connected to the distributed
   * system.
   *
   * @return whether this <code>DistributedSystem</code> is connected to the distributed system
   *
   * @see #disconnect()
   */
  public abstract boolean isConnected();

  /**
   * Returns the id of this connection to the distributed system.
   *
   * @return the id of this connection to the distributed system
   *
   * @deprecated {@link #getDistributedMember} provides an identity for this connection that is
   *             unique across the entire distributed system.
   */
  @Deprecated
  public abstract long getId();

  /**
   * Returns a string that uniquely identifies this connection to the distributed system.
   *
   * @return a string that uniquely identifies this connection to the distributed system
   * @see org.apache.geode.admin.SystemMembershipEvent#getMemberId
   *
   * @since GemFire 4.0
   * @deprecated as of GemFire 5.0, use {@link #getDistributedMember} instead
   */
  @Deprecated
  public abstract String getMemberId();

  /**
   * Returns the {@link DistributedMember} that identifies this connection to the distributed
   * system.
   *
   * @return the member that represents this distributed system connection.
   * @since GemFire 5.0
   */
  public abstract DistributedMember getDistributedMember();

  /**
   * Returns a set of all the other members in this distributed system.
   *
   * @return returns a set of all the other members in this distributed system.
   * @since GemFire 7.0
   */
  public abstract Set<DistributedMember> getAllOtherMembers();

  /**
   * Returns a set of all the members in the given group. Members join a group by setting the
   * "groups" gemfire property.
   *
   * @param group the group to which the distributed members to find belong
   * @return returns a set of all the member in a group.
   * @since GemFire 7.0
   */
  public abstract Set<DistributedMember> getGroupMembers(String group);


  /**
   * Find the set of distributed members running on a given address
   *
   * @param address the address of the distributed members to find
   * @return a set of all DistributedMembers that have any interfaces that match the given IP
   *         address. May be empty if there are no members.
   *
   * @since GemFire 7.1
   */
  public abstract Set<DistributedMember> findDistributedMembers(InetAddress address);

  /**
   * Find the distributed member with the given name
   *
   * @param name the name of the distributed member to find
   * @return the distributed member that has the given name, or null if no member is currently
   *         running with the given name.
   *
   * @since GemFire 7.1
   */
  public abstract DistributedMember findDistributedMember(String name);

  /**
   * Returns the <a href="#name">name</a> of this connection to the distributed system.
   *
   * @return the name of this connection to the distributed system
   */
  public abstract String getName();

  /**
   * The <code>PROPERTIES_FILE_PROPERTY</code> is the system property that can be used to specify
   * the name of the properties file that the connect method will check for when it looks for a
   * properties file. Unless the value specifies the fully qualified path to the file, the file will
   * be searched for, in order, in the following directories:
   * <ol>
   * <li>the current directory
   * <li>the home directory
   * <li>the class path
   * </ol>
   * Only the first file found will be used.
   * <p>
   * The default value is {@link #PROPERTIES_FILE_DEFAULT}. However if the
   * <code>PROPERTIES_FILE_PROPERTY</code> is set then its value will be used instead of the
   * default. If this value is a relative file system path then the above search is done. If it is
   * an absolute file system path then that file must exist; no search for it is done.
   *
   * @see #PROPERTIES_FILE_DEFAULT
   * @see #getPropertiesFile()
   * @since Geode 1.0
   */
  public static final String PROPERTIES_FILE_PROPERTY = "gemfirePropertyFile";

  /**
   * The default value of <code>PROPERTIES_FILE_PROPERTY</code> is
   * <code>"gemfire.properties"</code>. The location of the file will be resolved during connect as
   * described for {@link #PROPERTIES_FILE_PROPERTY}.
   *
   * @see #PROPERTIES_FILE_PROPERTY
   * @see #getPropertiesFile()
   * @since Geode 1.0
   */
  public static final String PROPERTIES_FILE_DEFAULT =
      GeodeGlossary.GEMFIRE_PREFIX + "properties";

  /**
   * Returns the current value of {@link #PROPERTIES_FILE_PROPERTY} system property if set or the
   * default value {@link #PROPERTIES_FILE_DEFAULT}.
   *
   * @return the current value of {@link #PROPERTIES_FILE_PROPERTY} system property if set or the
   *         default value {@link #PROPERTIES_FILE_DEFAULT}
   *
   * @see #PROPERTIES_FILE_PROPERTY
   * @see #PROPERTIES_FILE_DEFAULT
   * @since Geode 1.0
   */
  public static String getPropertiesFile() {
    return System.getProperty(PROPERTIES_FILE_PROPERTY, PROPERTIES_FILE_DEFAULT);
  }

  /**
   * The <code>PROPERTY_FILE</code> is the name of the properties file that the connect method will
   * check for when it looks for a properties file. The file will be searched for, in order, in the
   * following directories:
   * <ol>
   * <li>the current directory
   * <li>the home directory
   * <li>the class path
   * </ol>
   * Only the first file found will be used.
   * <p>
   * The default value of PROPERTY_FILE is <code>"gemfire.properties"</code>. However if the
   * "gemfirePropertyFile" system property is set then its value is the value of PROPERTY_FILE. If
   * this value is a relative file system path then the above search is done. If it is an absolute
   * file system path then that file must exist; no search for it is done.
   *
   * @see #getPropertiesFile()
   * @since GemFire 5.0
   * @deprecated As of 9.0, please use {@link #getPropertiesFile()} instead.
   */
  @Deprecated
  public static final String PROPERTY_FILE = getPropertiesFile();

  /**
   * The <code>SECURITY_PROPERTIES_FILE_PROPERTY</code> is the system property that can be used to
   * specify the name of the property file that the connect method will check for when it looks for
   * a property file. Unless the value specifies the fully qualified path to the file, the file will
   * be searched for, in order, in the following directories:
   * <ol>
   * <li>the current directory
   * <li>the home directory
   * <li>the class path
   * </ol>
   * Only the first file found will be used.
   * <p>
   * The default value is {@link #SECURITY_PROPERTIES_FILE_DEFAULT}. However if the
   * <code>SECURITY_PROPERTIES_FILE_PROPERTY</code> is set then its value will be used instead of
   * the default. If this value is a relative file system path then the above search is done. If it
   * is an absolute file system path then that file must exist; no search for it is done.
   *
   * @see #SECURITY_PROPERTIES_FILE_DEFAULT
   * @see #getSecurityPropertiesFile()
   * @since Geode 1.0
   */
  public static final String SECURITY_PROPERTIES_FILE_PROPERTY = "gemfireSecurityPropertyFile";

  /**
   * The default value of <code>SECURITY_PROPERTIES_FILE_PROPERTY</code> is
   * <code>"gfsecurity.properties"</code>. The location of the file will be resolved during connect
   * as described for {@link #SECURITY_PROPERTIES_FILE_PROPERTY}.
   *
   * @see #SECURITY_PROPERTIES_FILE_PROPERTY
   * @see #getSecurityPropertiesFile()
   * @since Geode 1.0
   */
  public static final String SECURITY_PROPERTIES_FILE_DEFAULT = "gfsecurity.properties";

  /**
   * Returns the current value of {@link #SECURITY_PROPERTIES_FILE_PROPERTY} system property if set
   * or the default value {@link #SECURITY_PROPERTIES_FILE_DEFAULT}.
   *
   * @return the current value of {@link #SECURITY_PROPERTIES_FILE_PROPERTY} system property if set
   *         or the default value {@link #SECURITY_PROPERTIES_FILE_DEFAULT}
   *
   * @see #SECURITY_PROPERTIES_FILE_PROPERTY
   * @see #SECURITY_PROPERTIES_FILE_DEFAULT
   * @since Geode 1.0
   */
  public static String getSecurityPropertiesFile() {
    return System.getProperty(SECURITY_PROPERTIES_FILE_PROPERTY, SECURITY_PROPERTIES_FILE_DEFAULT);
  }

  /**
   * The <code>SECURITY_PROPERTY_FILE</code> is the name of the property file that the connect
   * method will check for when it looks for a security property file. The file will be searched
   * for, in order, in the following directories:
   * <ol>
   * <li>the current directory
   * <li>the home directory
   * <li>the class path
   * </ol>
   * Only the first file found will be used.
   * <p>
   * The default value of SECURITY_PROPERTY_FILE is <code>"gfsecurity.properties"</code>. However if
   * the "gemfireSecurityPropertyFile" system property is set then its value is the value of
   * SECURITY_PROPERTY_FILE. If this value is a relative file system path then the above search is
   * done. If it is an absolute file system path then that file must exist; no search for it is
   * done.
   *
   * @see #getSecurityPropertiesFile()
   * @since GemFire 6.6.2
   * @deprecated As of 9.0, please use {@link #getSecurityPropertiesFile()} instead.
   */
  @Deprecated
  public static final String SECURITY_PROPERTY_FILE = getSecurityPropertiesFile();

  /**
   * Gets an <code>URL</code> for the properties file, if one can be found, that the connect method
   * will use as its properties file.
   * <p>
   * See {@link #PROPERTIES_FILE_PROPERTY} for information on the name of the properties file and
   * what locations it will be looked for in.
   *
   * @return a <code>URL</code> that names the GemFire property file. Null is returned if no
   *         property file was found.
   * @see #PROPERTIES_FILE_PROPERTY
   * @see #PROPERTIES_FILE_DEFAULT
   * @see #getPropertiesFile()
   * @since Geode 1.0
   */
  public static URL getPropertiesFileURL() {
    return getFileURL(getPropertiesFile());
  }

  /**
   * Gets an <code>URL</code> for the property file, if one can be found, that the connect method
   * will use as its property file.
   * <p>
   * See {@link #PROPERTIES_FILE_PROPERTY} for information on the name of the property file and what
   * locations it will be looked for in.
   *
   * @return a <code>URL</code> that names the GemFire property file. Null is returned if no
   *         property file was found.
   * @see #getPropertiesFileURL()
   * @since GemFire 5.0
   * @deprecated As of 9.0, please use {@link #getPropertiesFileURL()}
   */
  @Deprecated
  public static URL getPropertyFileURL() {
    return getPropertiesFileURL();
  }

  /**
   * Gets an <code>URL</code> for the security properties file, if one can be found, that the
   * connect method will use as its properties file.
   * <p>
   * See {@link #SECURITY_PROPERTIES_FILE_PROPERTY} for information on the name of the properties
   * file and what locations it will be looked for in.
   *
   * @return a <code>URL</code> that names the GemFire security properties file. Null is returned if
   *         no properties file was found.
   * @see #SECURITY_PROPERTIES_FILE_PROPERTY
   * @see #SECURITY_PROPERTIES_FILE_DEFAULT
   * @see #getSecurityPropertiesFile()
   * @since GemFire 6.6.2
   */
  public static URL getSecurityPropertiesFileURL() {
    return getFileURL(getSecurityPropertiesFile());
  }

  private static URL getFileURL(String fileName) {
    File file = new File(fileName).getAbsoluteFile();

    if (file.exists()) {
      try {
        return IOUtils.tryGetCanonicalFileElseGetAbsoluteFile(file).toURI().toURL();
      } catch (MalformedURLException ignore) {
      }
    }

    file = new File(System.getProperty("user.home"), fileName);

    if (file.exists()) {
      try {
        return IOUtils.tryGetCanonicalFileElseGetAbsoluteFile(file).toURI().toURL();
      } catch (MalformedURLException ignore) {
      }
    }

    return ClassPathLoader.getLatest().getResource(DistributedSystem.class, fileName);
  }

  /**
   * Test to see whether the DistributedSystem is in the process of reconnecting and recreating the
   * cache after it has been removed from the system by other members or has shut down due to
   * missing Roles and is reconnecting.
   * <p>
   * This will also return true if the DistributedSystem has finished reconnecting. When reconnect
   * has completed you can use {@link DistributedSystem#getReconnectedSystem} to retrieve the new
   * distributed system.
   *
   * @return true if the DistributedSystem is attempting to reconnect or has finished reconnecting
   */
  public abstract boolean isReconnecting();

  /**
   * Wait for the DistributedSystem to finish reconnecting to the system and recreate the cache.
   * This may throw a DistributedSystemDisconnectedException if reconnect fails. The exception
   * will detail what went wrong.
   *
   * @param time amount of time to wait, or -1 to wait forever
   * @param units the units associated with the time
   * @return true if the system was reconnected
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public abstract boolean waitUntilReconnected(long time, TimeUnit units)
      throws InterruptedException;

  /**
   * Force the DistributedSystem to stop reconnecting. If the DistributedSystem is currently
   * connected this will disconnect it and close the cache.
   */
  public abstract void stopReconnecting();

  /**
   * Returns the new DistributedSystem if there was an auto-reconnect
   *
   * @return the new DistributedSystem if there was an auto-reconnect
   */
  public abstract DistributedSystem getReconnectedSystem();
}
