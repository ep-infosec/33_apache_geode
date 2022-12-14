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

package org.apache.geode.modules.session;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.Function;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class CommandServlet extends HttpServlet {
  @SuppressWarnings("unused")
  private ServletContext context;

  /**
   * Save a reference to the ServletContext for later use.
   */
  @Override
  public void init(ServletConfig config) {
    context = config.getServletContext();
  }

  /**
   * The standard servlet method overridden.
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {

    QueryCommand cmd = QueryCommand.UNKNOWN;
    String param = request.getParameter("param");
    String value = request.getParameter("value");
    PrintWriter out = response.getWriter();

    try {
      String cmdStr = request.getParameter("cmd");
      if (cmdStr != null) {
        cmd = QueryCommand.valueOf(cmdStr);
      }

      HttpSession session;

      switch (cmd) {
        case SET:
          session = request.getSession();
          session.setAttribute(param, value);
          break;
        case SET_MAX_INACTIVE:
          session = request.getSession();
          session.setMaxInactiveInterval(Integer.parseInt(value));
          break;
        case GET:
          session = request.getSession();
          String val = (String) session.getAttribute(param);
          if (val != null) {
            out.write(val);
          }
          break;
        case REMOVE:
          session = request.getSession();
          session.removeAttribute(param);
          break;
        case INVALIDATE:
          session = request.getSession();
          session.invalidate();
          break;
        case FUNCTION:
          String functionClass = request.getParameter("function");
          Class<? extends Function> clazz = (Class<? extends Function>) Thread.currentThread()
              .getContextClassLoader().loadClass(functionClass);
          Function<HttpServletRequest, String> function = clazz.newInstance();
          String result = function.apply(request);
          if (result != null) {
            out.write(result);
          }
          break;
      }
    } catch (Exception e) {
      out.write("Error in servlet: " + e);
    }
  }
}
