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
package org.apache.geode.codeAnalysis;

import java.util.HashMap;
import java.util.Map;

import org.apache.geode.codeAnalysis.decode.CompiledClass;
import org.apache.geode.codeAnalysis.decode.CompiledMethod;



public class ClassAndMethods implements Comparable {
  public CompiledClass dclass;
  public Map<String, CompiledMethod> methods = new HashMap<>();
  public short variableCount;

  public ClassAndMethods(CompiledClass parsedClass) {
    dclass = parsedClass;
  }

  @Override
  public int compareTo(Object other) {
    if (!(other instanceof ClassAndMethods)) {
      return -1;
    }
    return dclass.compareTo(((ClassAndMethods) other).dclass);
  }

  public int numMethods() {
    return methods.size();
  }

  @Override
  public String toString() {
    return ClassAndMethodDetails.convertForStoring(this);
  }
}
