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
package org.apache.geode.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Signifies that a public API (public class, method or field) is subject to incompatible changes,
 * or even removal, in a future release. An API bearing this annotation is exempt from any
 * compatibility guarantees made by its containing library.
 *
 * <p>
 * Note that the presence of this annotation implies nothing about the quality or performance of the
 * API in question, only the fact that it is not "API-frozen."
 *
 * <p>
 * It is generally safe for <i>applications</i> to depend on beta APIs, at the cost of some extra
 * work during upgrades. However, it is generally inadvisable for <i>libraries</i> (which get
 * included on users' class paths, outside the library developers' control) to do so.
 *
 * <p>
 * Inspired by similar annotations in JGroups, Spark, DataflowJavaSDK.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR, ElementType.FIELD,
    ElementType.METHOD, ElementType.PACKAGE, ElementType.TYPE})
public @interface Experimental {

  /**
   * Optional description
   *
   * @return the description for this annotation
   */
  String value() default "";

}
