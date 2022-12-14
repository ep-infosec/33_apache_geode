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

import static org.apache.geode.distributed.ConfigurationProperties.LOG_LEVEL;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;

import javax.servlet.http.HttpSession;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;
import org.apache.catalina.core.StandardWrapper;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.springframework.util.SocketUtils;
import org.xml.sax.SAXException;

import org.apache.geode.cache.Region;
import org.apache.geode.modules.session.catalina.DeltaSessionManager;
import org.apache.geode.modules.session.catalina.PeerToPeerCacheLifecycleListener;

public abstract class AbstractSessionsTest {
  protected static int port;
  private static EmbeddedTomcat server;
  private static StandardWrapper servlet;
  private static Region<String, HttpSession> region;
  protected static DeltaSessionManager sessionManager;

  // Set up the servers we need
  protected static void setupServer(final DeltaSessionManager manager) throws Exception {
    FileUtils.copyDirectory(
        Paths.get("..", "..", "resources", "integrationTest", "tomcat").toFile(),
        new File("./tomcat"));
    port = SocketUtils.findAvailableTcpPort();
    server = new EmbeddedTomcat(port, "JVM-1");

    final PeerToPeerCacheLifecycleListener p2pListener = new PeerToPeerCacheLifecycleListener();
    p2pListener.setProperty(MCAST_PORT, "0");
    p2pListener.setProperty(LOG_LEVEL, "config");
    server.getEmbedded().addLifecycleListener(p2pListener);
    sessionManager = manager;
    sessionManager.setEnableCommitValve(true);
    server.getRootContext().setManager(sessionManager);

    servlet = server.addServlet("/test/*", "default", CommandServlet.class.getName());
    server.startContainer();

    /*
     * Can only retrieve the region once the container has started up (and the cache has started
     * too).
     */
    region = sessionManager.getSessionCache().getSessionRegion();
  }

  @AfterClass
  public static void teardownClass() {
    server.stopContainer();
  }

  /**
   * Reset some data
   */
  @Before
  public void setup() {
    sessionManager.setMaxInactiveInterval(30);
    region.clear();
  }

  private WebResponse setCallbackAndExecuteGet(final Callback callback)
      throws IOException, SAXException {
    servlet.getServletContext().setAttribute("callback", callback);

    final WebConversation wc = new WebConversation();
    final WebRequest req = new GetMethodWebRequest(String.format("http://localhost:%d/test", port));
    req.setParameter("cmd", QueryCommand.CALLBACK.name());
    req.setParameter("param", "callback");

    return wc.getResponse(req);
  }

  private WebRequest prepareRequest(final String key, final String value) {
    final WebRequest req = new GetMethodWebRequest(String.format("http://localhost:%d/test", port));
    req.setParameter("cmd", QueryCommand.SET.name());
    req.setParameter("param", key);
    req.setParameter("value", value);

    return req;
  }

  /*
   * Check that the basics are working
   */
  @Test
  public void testSanity() throws Exception {
    final WebConversation wc = new WebConversation();
    final WebRequest req = new GetMethodWebRequest(String.format("http://localhost:%d/test", port));
    req.setParameter("cmd", QueryCommand.GET.name());
    req.setParameter("param", "null");
    final WebResponse response = wc.getResponse(req);

    assertEquals("JSESSIONID", response.getNewCookieNames()[0]);
  }

  /*
   * Test callback functionality. This is here really just as an example. Callbacks are useful to
   * implement per test actions which can be defined within the actual test method instead of in a
   * separate servlet class.
   */
  @Test
  public void testCallback() throws Exception {
    final String helloWorld = "Hello World";
    final Callback c = (request, response) -> {
      final PrintWriter out = response.getWriter();
      out.write(helloWorld);
    };

    final WebResponse response = setCallbackAndExecuteGet(c);
    assertEquals(helloWorld, response.getText());
  }

  /*
   * Test that calling session.isNew() works for the initial as well as subsequent requests.
   */
  @Test
  public void testIsNew() throws Exception {
    final Callback c = (request, response) -> {
      final HttpSession session = request.getSession();
      response.getWriter().write(Boolean.toString(session.isNew()));
    };
    servlet.getServletContext().setAttribute("callback", c);

    final WebConversation wc = new WebConversation();
    final WebRequest req = new GetMethodWebRequest(String.format("http://localhost:%d/test", port));

    req.setParameter("cmd", QueryCommand.CALLBACK.name());
    req.setParameter("param", "callback");
    WebResponse response = wc.getResponse(req);

    assertEquals("true", response.getText());
    response = wc.getResponse(req);

    assertEquals("false", response.getText());
  }

  /*
   * Check that our session persists. The values we pass in as query params are used to set
   * attributes on the session.
   */
  @Test
  public void testSessionPersists1() throws Exception {
    final String key = "value_testSessionPersists1";
    final String value = "Foo";

    final WebConversation wc = new WebConversation();
    final WebRequest req = prepareRequest(key, value);
    WebResponse response = wc.getResponse(req);
    final String sessionId = response.getNewCookieValue("JSESSIONID");

    assertNotNull("No apparent session cookie", sessionId);

    // The request retains the cookie from the prior response...
    req.setParameter("cmd", QueryCommand.GET.name());
    req.setParameter("param", key);
    req.removeParameter("value");
    response = wc.getResponse(req);

    assertEquals(value, response.getText());
  }

  /*
   * Test that invalidating a session makes it's attributes inaccessible.
   */
  @Test
  public void testInvalidate() throws Exception {
    final String key = "value_testInvalidate";
    final String value = "Foo";

    final WebConversation wc = new WebConversation();
    final WebRequest req = prepareRequest(key, value);
    wc.getResponse(req);

    // Invalidate the session
    req.removeParameter("param");
    req.removeParameter("value");
    req.setParameter("cmd", QueryCommand.INVALIDATE.name());
    wc.getResponse(req);

    // The attribute should not be accessible now...
    req.setParameter("cmd", QueryCommand.GET.name());
    req.setParameter("param", key);
    final WebResponse response = wc.getResponse(req);

    assertEquals("", response.getText());
  }

  /*
   * Test setting the session expiration
   */
  @Test
  public void testSessionExpiration1() throws Exception {
    // TestSessions only live for a second
    sessionManager.setMaxInactiveInterval(1);

    final String key = "value_testSessionExpiration1";
    final String value = "Foo";

    final WebConversation wc = new WebConversation();
    final WebRequest req = prepareRequest(key, value);
    wc.getResponse(req);

    // Sleep a while
    Thread.sleep(2000);

    // The attribute should not be accessible now...
    req.setParameter("cmd", QueryCommand.GET.name());
    req.setParameter("param", key);
    final WebResponse response = wc.getResponse(req);

    assertEquals("", response.getText());
  }

  /**
   * Test setting the session expiration via a property change as would happen under normal
   * deployment conditions.
   */
  @Test
  public void testSessionExpiration2() {
    // TestSessions only live for a minute
    sessionManager.propertyChange(new PropertyChangeEvent(server.getRootContext(), "sessionTimeout",
        30, 1));

    // Check that the value has been set to 60 seconds
    assertEquals(60, sessionManager.getMaxInactiveInterval());
  }

  /*
   * Test expiration of a session by the tomcat container, rather than gemfire expiration
   */
  @Test
  public void testSessionExpirationByContainer() throws Exception {

    final String key = "value_testSessionExpiration1";
    final String value = "Foo";

    final WebConversation wc = new WebConversation();
    final WebRequest req = prepareRequest(key, value);
    wc.getResponse(req);

    // Set the session timeout of this one session.
    req.setParameter("cmd", QueryCommand.SET_MAX_INACTIVE.name());
    req.setParameter("value", "1");
    wc.getResponse(req);

    // Wait until the session should expire
    Thread.sleep(2000);

    // Do a request, which should cause the session to be expired
    req.setParameter("cmd", QueryCommand.GET.name());
    req.setParameter("param", key);
    final WebResponse response = wc.getResponse(req);

    assertEquals("", response.getText());
  }

  /*
   * Test that removing a session attribute also removes it from the region
   */
  @Test
  public void testRemoveAttribute() throws Exception {
    final String key = "value_testRemoveAttribute";
    final String value = "Foo";

    final WebConversation wc = new WebConversation();
    final WebRequest req = prepareRequest(key, value);
    WebResponse response = wc.getResponse(req);
    final String sessionId = response.getNewCookieValue("JSESSIONID");

    // Implicitly remove the attribute
    req.removeParameter("value");
    wc.getResponse(req);

    // The attribute should not be accessible now...
    req.setParameter("cmd", QueryCommand.GET.name());
    req.setParameter("param", key);
    response = wc.getResponse(req);

    assertEquals("", response.getText());
    assertNull(region.get(sessionId).getAttribute(key));
  }

  /*
   * Test that a session attribute gets set into the region too.
   */
  @Test
  public void testBasicRegion() throws Exception {
    final String key = "value_testBasicRegion";
    final String value = "Foo";

    final WebConversation wc = new WebConversation();
    final WebRequest req = prepareRequest(key, value);
    final WebResponse response = wc.getResponse(req);
    final String sessionId = response.getNewCookieValue("JSESSIONID");

    assertEquals(value, region.get(sessionId).getAttribute(key));
  }

  /*
   * Test that a session attribute gets removed from the region when the session is invalidated.
   */
  @Test
  public void testRegionInvalidate() throws Exception {
    final String key = "value_testRegionInvalidate";
    final String value = "Foo";

    final WebConversation wc = new WebConversation();
    final WebRequest req = prepareRequest(key, value);
    final WebResponse response = wc.getResponse(req);
    final String sessionId = response.getNewCookieValue("JSESSIONID");

    // Invalidate the session
    req.removeParameter("param");
    req.removeParameter("value");
    req.setParameter("cmd", QueryCommand.INVALIDATE.name());
    wc.getResponse(req);

    assertNull("The region should not have an entry for this session", region.get(sessionId));
  }

  /*
   * Test that multiple attribute updates, within the same request result in only the latest one
   * being effective.
   */
  @Test
  public void testMultipleAttributeUpdates() throws Exception {
    final String key = "value_testMultipleAttributeUpdates";
    final Callback c = (request, response) -> {
      final HttpSession session = request.getSession();
      for (int i = 0; i < 1000; i++) {
        session.setAttribute(key, Integer.toString(i));
      }
    };

    final WebResponse response = setCallbackAndExecuteGet(c);
    final String sessionId = response.getNewCookieValue("JSESSIONID");
    assertEquals("999", region.get(sessionId).getAttribute(key));
  }

  /*
   * Test for issue #38 CommitSessionValve throws exception on invalidated sessions
   */
  @Test
  public void testCommitSessionValveInvalidSession() throws Exception {
    final Callback c = (request, response) -> {
      final HttpSession session = request.getSession();
      session.invalidate();
      response.getWriter().write("done");
    };

    final WebResponse response = setCallbackAndExecuteGet(c);
    assertEquals("done", response.getText());
  }

  /*
   * Test for issue #45 Sessions are being created for every request
   */
  @Test
  public void testExtraSessionsNotCreated() throws Exception {
    final Callback c = (request, response) -> {
      // Do nothing with sessions
      response.getWriter().write("done");
    };

    final WebResponse response = setCallbackAndExecuteGet(c);
    assertEquals("done", response.getText());
    assertEquals("The region should be empty", 0, region.size());
  }

  /*
   * Test for issue #46 lastAccessedTime is not updated at the start of the request, but only at the
   * end.
   */
  @Test
  public void testLastAccessedTime() throws Exception {
    final Callback c = (request, response) -> {
      final HttpSession session = request.getSession();
      // Hack to expose the session to our test context
      session.getServletContext().setAttribute("session", session);
      session.setAttribute("lastAccessTime", session.getLastAccessedTime());
      try {
        Thread.sleep(100);
      } catch (final InterruptedException ex) {
        // Ignore.
      }
      session.setAttribute("somethingElse", 1);
      request.getSession();
      response.getWriter().write("done");
    };
    servlet.getServletContext().setAttribute("callback", c);

    final WebConversation wc = new WebConversation();
    final WebRequest req = new GetMethodWebRequest(String.format("http://localhost:%d/test", port));

    // Execute the callback
    req.setParameter("cmd", QueryCommand.CALLBACK.name());
    req.setParameter("param", "callback");
    wc.getResponse(req);

    final HttpSession session = (HttpSession) servlet.getServletContext().getAttribute("session");
    final Long lastAccess = (Long) session.getAttribute("lastAccessTime");

    assertTrue(
        "Last access time not set correctly: " + lastAccess + " not <= "
            + session.getLastAccessedTime(),
        lastAccess <= session.getLastAccessedTime());
  }
}
