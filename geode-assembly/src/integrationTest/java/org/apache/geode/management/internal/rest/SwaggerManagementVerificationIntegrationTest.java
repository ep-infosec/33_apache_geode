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
package org.apache.geode.management.internal.rest;


import static org.apache.geode.test.junit.rules.HttpResponseAssert.assertResponse;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.distributed.ConfigurationProperties;
import org.apache.geode.test.junit.categories.RestAPITest;
import org.apache.geode.test.junit.categories.SecurityTest;
import org.apache.geode.test.junit.rules.GeodeHttpClientRule;
import org.apache.geode.test.junit.rules.LocatorStarterRule;
import org.apache.geode.test.junit.rules.RequiresGeodeHome;

@Category({SecurityTest.class, RestAPITest.class})
public class SwaggerManagementVerificationIntegrationTest {

  @ClassRule
  public static LocatorStarterRule locatorStarter = new LocatorStarterRule()
      .withProperty(ConfigurationProperties.SECURITY_AUTH_TOKEN_ENABLED_COMPONENTS, "management")
      .withHttpService()
      .withAutoStart();

  @Rule
  public GeodeHttpClientRule client = new GeodeHttpClientRule(locatorStarter::getHttpPort);

  @Rule
  public RequiresGeodeHome requiresGeodeHome = new RequiresGeodeHome();

  @Test
  public void isSwaggerRunning() throws Exception {
    // Check the UI
    assertResponse(client.get("/management/swagger-ui.html")).hasStatusCode(200);

    // Check the JSON
    JsonNode json =
        assertResponse(client.get("/management/v3/api-docs")).hasStatusCode(200)
            .getJsonObject();
    assertThat(json.get("openapi").asText(), is("3.0.1"));

    JsonNode info = json.get("info");
    assertThat(info.get("description").asText(),
        containsString("REST API to manage Geode. This is experimental."));
    assertThat(info.get("title").asText(),
        is("Apache Geode Management REST API"));
    assertThat(info.get("authTokenEnabled").asText(), is("true"));

    JsonNode license = info.get("license");
    assertThat(license.get("name").asText(), is("Apache License, version 2.0"));
    assertThat(license.get("url").asText(), is("http://www.apache.org/licenses/"));

  }
}
