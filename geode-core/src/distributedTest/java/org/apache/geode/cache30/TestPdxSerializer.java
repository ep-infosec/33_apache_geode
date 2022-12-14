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
package org.apache.geode.cache30;

import java.util.Properties;

import org.apache.geode.internal.cache.xmlcache.Declarable2;
import org.apache.geode.pdx.PdxReader;
import org.apache.geode.pdx.PdxSerializer;
import org.apache.geode.pdx.PdxWriter;

public class TestPdxSerializer implements PdxSerializer, Declarable2 {

  private Properties properties;

  @Override
  public boolean toData(Object o, PdxWriter out) {
    return false;
  }

  @Override
  public Object fromData(Class<?> clazz, PdxReader in) {
    return null;
  }

  @Override
  public void init(Properties props) {
    properties = props;

  }

  @Override
  public Properties getConfig() {
    return properties;
  }

  @Override
  public int hashCode() {
    return properties.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof TestPdxSerializer)) {
      return false;
    }
    return properties.equals(((TestPdxSerializer) obj).properties);
  }



}
