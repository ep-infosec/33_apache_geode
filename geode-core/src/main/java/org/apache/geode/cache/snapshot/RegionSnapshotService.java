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
package org.apache.geode.cache.snapshot;

import java.io.File;
import java.io.IOException;

import org.apache.geode.DataSerializer;
import org.apache.geode.Instantiator;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.snapshot.SnapshotOptions.SnapshotFormat;
import org.apache.geode.pdx.PdxSerializer;

/**
 * Allows a snapshot of region data to be imported and exported. Example usage:
 *
 * <pre>
 * // obtain a snapshot
 * RegionSnapshot snapshot = region.getSnapshotService();
 *
 * // export the snapshot, every region in the cache will be exported
 * snapshot.save(new File("snapshot.gfd"), SnapshotOptions.GEMFIRE);
 *
 * // import the snapshot file, updates any existing entries in the region
 * snapshot.load(new File("snapshot.gfd"), SnapshotOptions.GEMFIRE);
 * </pre>
 *
 *
 * When parallel export is used, the file name will be modified to include a unique identifier for
 * the member that created the file, so providing a file location of "snapshot.gfd" would result in
 * creation of multiple files with the format "snapshot-unique_id.gfd". When loading files from a
 * parallel export, a directory can be given instead of a single file and all snapshot files in that
 * directory will be loaded.
 *
 * <pre>
 * // import directory of snapshot files
 * snapshot.load(new File("snapshotDir"), SnapshotOptions.GEMFIRE);
 * </pre>
 *
 * The default behavior is to perform all I/O operations on the node where the snapshot operations
 * are invoked. This will involve either collecting or dispersing data over the network if the
 * region is a partitioned region. The snapshot behavior can be changed using
 * {@link SnapshotOptions}. For example:
 *
 * <pre>
 * RegionSnapshotService snapshot = region.getSnapshotService();
 * SnapshotFilter filter = new SnapshotFilter() {
 *   public boolean accept(Entry$lt;K, V$gt; entry) {
 *     return true;
 *   }
 * };
 *
 * SnapshotOptions$lt;Object, Object$gt; options = snapshot.createOptions();
 * options.setFilter(filter);
 *
 * snapshot.save(new File("snapshot.gfd"), SnapshotFormat.GEMFIRE, options);
 * </pre>
 *
 * Note that the snapshot does not provide a consistency guarantee. Updates to data during the
 * course of import/export operations could result data inconsistencies.
 *
 * @param <K> the cache entry key type
 * @param <V> the cache entry value type
 *
 * @see Region#getSnapshotService()
 * @see SnapshotOptions
 *
 * @since GemFire 7.0
 */
public interface RegionSnapshotService<K, V> {

  /**
   * File extension for snapshot files
   */
  String SNAPSHOT_FILE_EXTENSION = ".gfd";

  /**
   * Creates a <code>SnapshotOptions</code> object configured with default settings. The options can
   * be used to configure snapshot behavior.
   *
   * @return the default options
   */
  SnapshotOptions<K, V> createOptions();

  /**
   * Exports the region data into the snapshot file.
   *
   * @param snapshot the snapshot file (must end in .gfd)
   * @param format the snapshot format
   *
   * @throws IOException error writing snapshot
   */
  void save(File snapshot, SnapshotFormat format) throws IOException;

  /**
   * Exports the region data into the snapshot file by applying user-configured options.
   *
   * @param snapshot the snapshot file (must end in .gfd)
   * @param format the snapshot format
   * @param options the snapshot options
   *
   * @throws IOException error writing snapshot
   */
  void save(File snapshot, SnapshotFormat format, SnapshotOptions<K, V> options) throws IOException;

  /**
   * Imports the snapshot file into the specified region. The snapshot parameter can be either a
   * single snapshot file or a directory containing snapshot files. If a single file, it must end in
   * the snapshot file extension (.gfd) to be imported. If a directory, only files in the directory
   * that end in .gfd will be loaded.
   * <p>
   * Prior to loading data, the region should have been created and any necessary serializers
   * (either {@link DataSerializer} or {@link PdxSerializer}) and {@link Instantiator}s should have
   * been registered.
   *
   * @param snapshot the snapshot file (ending in .gfd) or a directory of .gfd snapshot files
   * @param format the snapshot file format
   *
   * @throws IOException Unable to import data
   * @throws ClassNotFoundException Unable to import data
   */
  void load(File snapshot, SnapshotFormat format) throws IOException, ClassNotFoundException;

  /**
   * Imports the snapshot file into the specified region by applying user-configured options. The
   * snapshot parameter can be either a single snapshot file or a directory containing snapshot
   * files. If a single file, it must end in the snapshot file extension (.gfd) to be imported. If a
   * directory, only files in the directory that end in .gfd will be loaded.
   * <p>
   * Prior to loading data, the region should have been created and any necessary serializers
   * (either {@link DataSerializer} or {@link PdxSerializer}) and {@link Instantiator}s should have
   * been registered.
   *
   * @param snapshot the snapshot file (ending in .gfd) or a directory of .gfd snapshot files
   * @param format the snapshot file format
   * @param options the snapshot options
   *
   * @throws IOException Unable to import data
   * @throws ClassNotFoundException Unable to import data
   */
  void load(File snapshot, SnapshotFormat format, SnapshotOptions<K, V> options)
      throws IOException, ClassNotFoundException;
}
