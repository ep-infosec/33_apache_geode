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

import static org.apache.geode.cache.query.security.RestrictedMethodAuthorizer.UNAUTHORIZED_STRING;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.geode.cache.query.AmbiguousNameException;
import org.apache.geode.cache.query.NameNotFoundException;
import org.apache.geode.cache.query.NameResolutionException;
import org.apache.geode.cache.query.QueryInvocationTargetException;
import org.apache.geode.cache.query.internal.types.TypeUtils;
import org.apache.geode.cache.query.security.MethodInvocationAuthorizer;
import org.apache.geode.security.NotAuthorizedException;

/**
 * Utility class for mapping operations in the query language to Java methods
 */
public class MethodDispatch {
  private Method _method;
  private final Class _targetClass;
  private final String _methodName;
  private final Class[] _argTypes;

  public MethodDispatch(Class targetClass, String methodName, List argTypes)
      throws NameResolutionException {
    _targetClass = targetClass;
    _methodName = methodName;
    _argTypes = (Class[]) argTypes.toArray(new Class[0]);

    resolve();
    // override security in case this is a method on a nonpublic class
    // with a public method
    _method.setAccessible(true);
  }

  public Object invoke(Object target, List args, ExecutionContext executionContext)
      throws NameNotFoundException, QueryInvocationTargetException {
    Object[] argsArray = args.toArray();

    try {
      MethodInvocationAuthorizer authorizer = executionContext.getMethodInvocationAuthorizer();

      // CQs are generally executed on individual events, so caching is just an overhead.
      if (executionContext.isCqQueryContext()) {
        if (!authorizer.authorize(_method, target)) {
          throw new NotAuthorizedException(UNAUTHORIZED_STRING + _method.getName());
        }
      } else {
        // Try to use cached result so authorizer gets invoked only once per query.
        boolean authorizationResult;
        Boolean cachedResult = (Boolean) executionContext.cacheGet(_method);

        if (cachedResult != null) {
          // Use cached result.
          authorizationResult = cachedResult;
        } else {
          // First time, evaluate and cache result.
          authorizationResult = authorizer.authorize(_method, target);
          executionContext.cachePut(_method, authorizationResult);
        }

        if (!authorizationResult) {
          throw new NotAuthorizedException(UNAUTHORIZED_STRING + _method.getName());
        }
      }

      return _method.invoke(target, argsArray);
    } catch (IllegalAccessException e) {
      throw new NameNotFoundException(
          String.format("Method ' %s ' in class ' %s ' is not accessible to the query processor",
              _method.getName(), target.getClass().getName()),
          e);
    } catch (InvocationTargetException e) {
      // if targetException is Exception, wrap it, otherwise wrap the InvocationTargetException
      // itself
      Throwable t = e.getTargetException();
      if (t instanceof Exception) {
        throw new QueryInvocationTargetException(t);
      }
      throw new QueryInvocationTargetException(e);
    }
  }

  private void resolve() throws NameResolutionException {
    // if argTypes contains a null, then go directly to resolveGeneral(),
    // otherwise try to resolve on the specific types first
    // (a null type gets passed in if the runtime value of the arg is null)
    for (final Class argType : _argTypes) {
      if (argType == null) {
        resolveGeneral();
        return;
      }
    }

    // first try to get the method based on the exact parameter types
    try {
      _method = _targetClass.getMethod(_methodName, _argTypes);
    } catch (NoSuchMethodException e) {
      resolveGeneral();
    }
  }

  private void resolveGeneral() throws NameResolutionException {
    Method[] allMethods = _targetClass.getMethods();
    // keep only ones whose method names match and have the same number of args
    List<Method> candidates = new ArrayList<>();
    for (Method meth : allMethods) {
      /*
       * if (Modifier.isStatic(meth.getModifiers())) continue;
       */
      if (!meth.getName().equals(_methodName)) {
        continue;
      }
      if (meth.getParameterTypes().length != _argTypes.length) {
        continue;
      }
      // are the args all convertible to the parameter types?
      if (!TypeUtils.areTypesConvertible(_argTypes, meth.getParameterTypes())) {
        continue;
      }
      candidates.add(meth);
    }

    if (candidates.isEmpty()) {
      throw new NameNotFoundException(
          String.format(
              "No applicable and accessible method named ' %s ' was found in class ' %s ' for the argument types %s",
              _methodName, _targetClass.getName(), Arrays.asList(_argTypes)));
    }

    // now we have a list of accessible and applicable method,
    // choose the most specific
    if (candidates.size() == 1) {
      _method = candidates.get(0);
      return;
    }

    sortByDecreasingSpecificity(candidates);
    // get the first two methods in the sorted list,
    // if they are equally specific, then throw AmbiguousMethodException
    Method meth1 = candidates.get(0);
    Method meth2 = candidates.get(1);
    // if meth1 cannot be type-converted to meth2, then meth1 is not more
    // specific than meth2 and the invocation is ambiguous.
    // special case a null argument type in this case, since there should
    // be not differentiation for those parameter types regarding specificity

    if (equalSpecificity(meth1, meth2, _argTypes)) {
      throw new AmbiguousNameException(
          String.format(
              "Two or more maximally specific methods were found for the method named ' %s ' in class ' %s ' for the argument types: %s",
              meth1.getName(), _targetClass.getName(), Arrays.asList(_argTypes)));
    }

    _method = meth1;
  }

  private void sortByDecreasingSpecificity(List methods) {
    Collections.sort(methods, (Comparator) (o1, o2) -> {
      Method m1 = (Method) o1;
      Method m2 = (Method) o2;
      if (m1.equals(m2)) {
        return 0;
      }

      boolean convertible1 = methodConvertible(m1, m2);
      boolean convertible2 = methodConvertible(m2, m1);
      // check to see if they are convertible both ways or neither way
      if (convertible1 == convertible2) {
        return 0;
      }
      return convertible1 ? -1 : 1;
    });
  }

  private boolean methodConvertible(Method m1, Method m2) {
    boolean declaringClassesConvertible =
        TypeUtils.isTypeConvertible(m1.getDeclaringClass(), m2.getDeclaringClass());

    boolean paramsConvertible = true;
    Class[] p1 = m1.getParameterTypes();
    Class[] p2 = m2.getParameterTypes();
    for (int i = 0; i < p1.length; i++) {
      if (!TypeUtils.isTypeConvertible(p1[i], p2[i])) {
        paramsConvertible = false;
        break;
      }
    }
    return declaringClassesConvertible && paramsConvertible;
  }

  private boolean equalSpecificity(Method m1, Method m2, Class[] argTypes) {
    // if the m1 is not convertible to m2, then definitely equal specificity,
    // since this would be ambiguous even in Java
    if (!methodConvertible(m1, m2)) {
      return true;
    }

    // if there is at least one param type that is more specific
    // ignoring parameters with a null argument, then
    // answer false, otherwise true.
    Class[] p1 = m1.getParameterTypes();
    Class[] p2 = m2.getParameterTypes();
    for (int i = 0; i < p1.length; i++) {
      // assumes m1 is <= m2 in specificity
      if (argTypes[i] != null && p1[i] != p2[i] && TypeUtils.isTypeConvertible(p1[i], p2[i])) {
        return false;
      }
    }

    return true;
  }
}
