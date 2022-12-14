/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geode.datasource;

import java.util.Properties;

import javax.sql.DataSource;

import org.apache.geode.annotations.Experimental;

/**
 * Classes that implement this interface can be used as the class name specified
 * in the "gfsh create data-source --pooled-data-source-factory-class" parameter.
 * <br>
 * This parameter is only valid when the data-source type is "POOLED".
 * <br>
 * For more information see "gfsh create data-source".
 * <p>
 * Note: implementors of this interface must also implement a zero-arg constructor.
 */
@Experimental
public interface PooledDataSourceFactory {
  /**
   * Create and return a data source configured with the given properties.
   * <p>
   * If you desire to have the data source release its resources when the cache is closed
   * or the jndi-binding is removed, then also implement {@link AutoCloseable}.
   *
   * @param poolProperties properties to use to initialize the pool part of the data source
   *        The poolProperties names can be any of the following:
   *        <br>
   *        connection-url, user-name, password, jdbc-driver-class,
   *        max-pool-size, init-pool-size, idle-timeout-seconds,
   *        login-timeout-seconds, or blocking-timeout-seconds.
   *        <p>
   * @param dataSourceProperties properties to use to initialize the data source the pool will use
   *        to create connections
   *        <p>
   * @return the created data source
   */
  DataSource createDataSource(Properties poolProperties, Properties dataSourceProperties);

}
