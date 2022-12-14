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
package org.apache.geode.cache.management;

import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;

import org.apache.geode.LogWriter;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionFactory;
import org.apache.geode.cache.Scope;
import org.apache.geode.distributed.ConfigurationProperties;
import org.apache.geode.distributed.DistributedSystem;

/**
 * An test class for exploring the various notification listener behaviors
 *
 * Run it like this:
 *
 * java -cp geode-dependencies.jar:. -Dgemfire.log-file=system.log
 * -Dgemfire.statistic-archive-file=statsArchive.gfs
 * org.apache.geode.cache.control.MXMemoryPoolListenerExample
 *
 * @since GemFire 6.0
 */
public class MXMemoryPoolListenerExample implements NotificationListener {
  private final AtomicBoolean critical = new AtomicBoolean();
  private final LogWriter logger;

  public MXMemoryPoolListenerExample(DistributedSystem ds) {
    logger = ds.getLogWriter();
  }

  /*
   * (non-Javadoc)
   *
   * @see javax.management.NotificationListener#handleNotification(javax.management.Notification,
   * java.lang.Object)
   */
  @Override
  public void handleNotification(Notification arg0, Object arg1) {
    logger.info("Notification: " + arg0 + "; o: " + arg1 + "; m: " + arg0.getMessage());
    critical.set(true);
  }

  public static void main(String[] args) {

    final MemoryMXBean mbean = ManagementFactory.getMemoryMXBean();


    final double threshold;
    {
      double t = 0.8;
      if (args.length > 0) {
        try {
          t = Integer.parseInt(args[0]) / 100;
        } catch (NumberFormatException ignored) {
        }
      }
      if (t < 0.0 || t > 1.0) {
        throw new IllegalArgumentException("Theshold must be >= 0 and <= 100");
      }
      threshold = t;
    }

    final int percentTenured;
    {
      int p = 100;
      if (args.length > 1) {
        try {
          p = Integer.parseInt(args[1]);
        } catch (NumberFormatException ignored) {
        }
      }
      if (p > 100 || p < 0) {
        throw new IllegalArgumentException("Percent Tenured must be >= 0 and <= 100");
      }
      percentTenured = p;
    }

    Properties dsProps = new Properties();
    dsProps.setProperty(MCAST_PORT, "0"); // Loner
    dsProps.setProperty(ConfigurationProperties.LOG_LEVEL, "info");
    dsProps.setProperty(ConfigurationProperties.STATISTIC_SAMPLE_RATE, "200");
    dsProps.setProperty(ConfigurationProperties.ENABLE_TIME_STATISTICS, "true");
    dsProps.setProperty(ConfigurationProperties.STATISTIC_SAMPLING_ENABLED, "true");
    DistributedSystem ds = DistributedSystem.connect(dsProps);
    final LogWriter logger = ds.getLogWriter();

    logger.info("Usage threshold: " + threshold + "; percent tenured: " + percentTenured
        + "; Runtime Maximum memory: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "Mb"
        + "; Heap Maximum memory: " + (mbean.getHeapMemoryUsage().getMax() / (1024 * 1024)) + "Mb");

    MXMemoryPoolListenerExample me = new MXMemoryPoolListenerExample(ds);

    // Register this listener to NotificationEmitter
    NotificationEmitter emitter = (NotificationEmitter) mbean;
    emitter.addNotificationListener(me, null, null);
    List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
    for (MemoryPoolMXBean p : pools) {
      if (p.isCollectionUsageThresholdSupported()) {
        // p.setCollectionUsageThreshold(0);
        logger.info("Pool which supports collection usage threshold: " + p.getName() + "; "
            + p.getCollectionUsage());
      }

      // On JRockit do not set the usage threshold on the Nursery pool
      if (p.getType().equals(MemoryType.HEAP) && p.isUsageThresholdSupported()
          && !p.getName().startsWith("Nursery")) {
        int byteThreshold = (int) Math.ceil(threshold * p.getUsage().getMax());
        logger.info("Setting threshold " + (byteThreshold / (1024 * 1024)) + "Mb on: " + p.getName()
            + "; " + p.getCollectionUsage());
        p.setUsageThreshold(byteThreshold);
      }
    }

    final Cache c = CacheFactory.create(ds);
    new MemoryHog("hog_1", c, me.critical).consumeMemory(percentTenured).printTenuredSize();
    ds.disconnect();
  }

  public static class MemoryHog {
    private final String name;
    private final Region tenuredData;
    private final Cache cache;
    private final AtomicBoolean criticalState;

    public MemoryHog(String n, Cache c, AtomicBoolean critical) {
      name = n;
      cache = c;
      tenuredData = new RegionFactory().setScope(Scope.LOCAL).create(name);
      criticalState = critical;
    }

    public MemoryHog consumeMemory(final int percentTenured) {
      final long maxSecondsToRun = 180;
      final LogWriter logger = cache.getLogger();
      final long start = System.nanoTime();
      for (int i = 100;; i++) {
        // Create garbage
        byte[] val = new byte[1012]; // 1024 less 4 bytes for obj ref, less 8 bytes for Integer key
                                     // == 1012
        // Some random usage of the data to prevent optimization
        val[percentTenured] = (byte) i;
        if (percentTenured > 0 && (i % 100) <= percentTenured) {
          // Grow heap
          tenuredData.put(i, val);
        }

        if (i % 1000 == 0) {
          long runTime = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
          if (runTime > maxSecondsToRun) {
            logger.info(name + ": Ending consume loop after " + runTime + "s");
            break;
          }
        }

        if (criticalState.get()) {
          logger.info(name + ": Clearing tenured data: size="
              + (tenuredData.size() / 1024) + "Mb");
          tenuredData.clear();
          criticalState.set(false);
          try {
            Thread.sleep(250);
          } catch (InterruptedException ignored) {
          }
        }
      }
      return this;
    }

    public MemoryHog printTenuredSize() {
      cache.getLogger().info(
          "Tenured data size: " + tenuredData.getName() + ": " + tenuredData.size());
      return this;
    }
  }

}
