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
package org.apache.geode.security;

import static org.apache.geode.distributed.ConfigurationProperties.SECURITY_MANAGER;
import static org.apache.geode.distributed.ConfigurationProperties.SECURITY_POST_PROCESSOR;
import static org.apache.geode.distributed.ConfigurationProperties.USE_CLUSTER_CONFIGURATION;
import static org.apache.geode.test.dunit.IgnoredException.addIgnoredException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.GemFireConfigException;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.examples.SimpleSecurityManager;
import org.apache.geode.test.dunit.rules.ClusterStartupRule;
import org.apache.geode.test.dunit.rules.MemberVM;
import org.apache.geode.test.junit.categories.SecurityTest;
import org.apache.geode.test.junit.rules.ServerStarterRule;

@Category({SecurityTest.class})
public class SecurityClusterConfigDUnitTest {

  private static MemberVM locator;

  @ClassRule
  public static ClusterStartupRule cluster = new ClusterStartupRule();

  @Rule
  public ServerStarterRule serverStarter = new ServerStarterRule();

  @BeforeClass
  public static void beforeClass() throws Exception {
    addIgnoredException(
        "A server cannot specify its own security-manager or security-post-processor when using cluster configuration");
    addIgnoredException(
        "A server must use cluster configuration when joining a secured cluster.");

    Properties props = new Properties();
    props.setProperty(SECURITY_MANAGER, SimpleSecurityManager.class.getName());
    props.setProperty(SECURITY_POST_PROCESSOR, PDXPostProcessor.class.getName());
    locator = cluster.startLocatorVM(0, props);
  }

  @Test
  public void testStartServerWithClusterConfig() throws Exception {
    Properties props = new Properties();

    // the following are needed for peer-to-peer authentication
    props.setProperty("security-username", "cluster");
    props.setProperty("security-password", "cluster");
    props.setProperty(USE_CLUSTER_CONFIGURATION, "true");

    // initial security properties should only contain initial set of values
    serverStarter.startServer(props, locator.getPort());
    DistributedSystem ds = serverStarter.getCache().getDistributedSystem();

    // after cache is created, we got the security props passed in by cluster config
    Properties secProps = ds.getSecurityProperties();
    assertThat(secProps).containsKey("security-username");
    assertThat(secProps).containsKey("security-password");
    assertThat(secProps).containsKey(SECURITY_MANAGER);
    assertThat(secProps).containsKey(SECURITY_POST_PROCESSOR);
  }

  @Test
  public void testStartServerWithSameSecurityManager() throws Exception {
    Properties props = new Properties();

    // the following are needed for peer-to-peer authentication
    props.setProperty("security-username", "cluster");
    props.setProperty("security-password", "cluster");
    props.setProperty(SECURITY_MANAGER, SimpleSecurityManager.class.getName());
    props.setProperty(USE_CLUSTER_CONFIGURATION, "true");

    // initial security properties should only contain initial set of values
    serverStarter.startServer(props, locator.getPort());
    DistributedSystem ds = serverStarter.getCache().getDistributedSystem();

    // after cache is created, we got the security props passed in by cluster config
    Properties secProps = ds.getSecurityProperties();
    assertThat(secProps).containsKey("security-username");
    assertThat(secProps).containsKey("security-password");
    assertThat(secProps).containsKey(SECURITY_MANAGER);
    assertThat(secProps).containsKey(SECURITY_POST_PROCESSOR);
  }

  @Test
  public void serverWithDifferentSecurityManagerShouldThrowGemFireConfigException()
      throws Exception {
    Properties props = new Properties();

    // the following are needed for peer-to-peer authentication
    props.setProperty("security-username", "cluster");
    props.setProperty("security-password", "cluster");
    props.setProperty(SECURITY_MANAGER, OtherSimplySecurityManager.class.getName());
    props.setProperty(USE_CLUSTER_CONFIGURATION, "true");

    // initial security properties should only contain initial set of values
    assertThatThrownBy(
        () -> serverStarter.startServer(props, locator.getPort()))
            .isInstanceOf(GemFireConfigException.class).hasMessage(
                "A server cannot specify its own security-manager or security-post-processor when using cluster configuration");
  }

  @Test
  public void serverWithDifferentPostProcessorShouldThrowGemFireConfigException() throws Exception {
    Properties props = new Properties();

    // the following are needed for peer-to-peer authentication
    props.setProperty("security-username", "cluster");
    props.setProperty("security-password", "cluster");
    props.setProperty(SECURITY_MANAGER, SimpleSecurityManager.class.getName());
    props.setProperty(SECURITY_POST_PROCESSOR, OtherPDXPostProcessor.class.getName());
    props.setProperty(USE_CLUSTER_CONFIGURATION, "true");

    // initial security properties should only contain initial set of values
    assertThatThrownBy(
        () -> serverStarter.startServer(props, locator.getPort()))
            .isInstanceOf(GemFireConfigException.class).hasMessage(
                "A server cannot specify its own security-manager or security-post-processor when using cluster configuration");
  }

  @Test
  public void serverConnectingToSecuredLocatorMustUseClusterConfig() throws Exception {
    Properties props = new Properties();

    // the following are needed for peer-to-peer authentication
    props.setProperty("security-username", "cluster");
    props.setProperty("security-password", "cluster");
    props.setProperty(SECURITY_MANAGER, SimpleSecurityManager.class.getName());
    props.setProperty(USE_CLUSTER_CONFIGURATION, "false");

    assertThatThrownBy(
        () -> serverStarter.startServer(props, cluster.getMember(0).getPort()))
            .isInstanceOf(GemFireConfigException.class).hasMessage(
                "A server must use cluster configuration when joining a secured cluster.");
  }

}
