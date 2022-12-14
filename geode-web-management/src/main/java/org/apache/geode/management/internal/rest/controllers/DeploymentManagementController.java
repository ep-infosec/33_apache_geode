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

package org.apache.geode.management.internal.rest.controllers;

import static org.apache.geode.management.configuration.Links.URI_VERSION;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperFactoryBean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.management.api.ClusterManagementGetResult;
import org.apache.geode.management.api.ClusterManagementListResult;
import org.apache.geode.management.api.ClusterManagementRealizationResult;
import org.apache.geode.management.api.ClusterManagementResult;
import org.apache.geode.management.configuration.Deployment;
import org.apache.geode.management.configuration.HasFile;
import org.apache.geode.management.internal.beans.FileUploader;
import org.apache.geode.management.runtime.DeploymentInfo;

@RestController("deploymentManagement")
@RequestMapping(URI_VERSION)
public class DeploymentManagementController extends AbstractManagementController {

  @Autowired
  private Jackson2ObjectMapperFactoryBean objectMapper;

  private static final Logger logger = LogService.getLogger();

  @Operation(summary = "list deployed")
  @PreAuthorize("@securityService.authorize('CLUSTER', 'READ')")
  @GetMapping(Deployment.DEPLOYMENT_ENDPOINT)
  public ClusterManagementListResult<Deployment, DeploymentInfo> list(
      @RequestParam(required = false) String id,
      @RequestParam(required = false) String group) {
    Deployment deployment = new Deployment();
    if (StringUtils.isNotBlank(id)) {
      deployment.setFileName(id);
    }
    if (StringUtils.isNotBlank(group)) {
      deployment.setGroup(group);
    }
    return clusterManagementService.list(deployment);
  }

  @Operation(summary = "get deployed")
  @PreAuthorize("@securityService.authorize('CLUSTER', 'READ')")
  @GetMapping(Deployment.DEPLOYMENT_ENDPOINT + "/{id:.+}")
  public ClusterManagementGetResult<Deployment, DeploymentInfo> getDeployed(
      @PathVariable(name = "id") String id) {
    Deployment deployment = new Deployment();
    if (StringUtils.isNotBlank(id)) {
      deployment.setFileName(id);
    }
    return clusterManagementService.get(deployment);
  }

  @Operation(summary = "deploy")
  @ApiResponses({
      @ApiResponse(responseCode = "400", description = "Bad request."),
      @ApiResponse(responseCode = "500", description = "Internal error.")})
  @PreAuthorize("@securityService.authorize('CLUSTER', 'MANAGE', 'DEPLOY')")
  @PutMapping(value = Deployment.DEPLOYMENT_ENDPOINT,
      consumes = {"multipart/form-data"})
  public ResponseEntity<ClusterManagementResult> deploy(
      @Parameter(name = "filePath",
          required = true) @RequestParam(HasFile.FILE_PARAM) MultipartFile file,
      @Parameter(description = "deployment json configuration") @RequestParam(
          value = HasFile.CONFIG_PARAM,
          required = false) String json)
      throws IOException {
    // save the file to the staging area
    if (file == null) {
      throw new IllegalArgumentException("No file uploaded");
    }
    Path tempDir = FileUploader.createSecuredTempDirectory("uploaded-");
    File targetFile = new File(tempDir.toFile(), file.getOriginalFilename());
    file.transferTo(targetFile);
    Deployment deployment = new Deployment();
    if (StringUtils.isNotBlank(json)) {
      deployment = objectMapper.getObject().readValue(json, Deployment.class);
    }
    deployment.setFile(targetFile);
    ClusterManagementRealizationResult realizationResult =
        clusterManagementService.create(deployment);
    return new ResponseEntity<>(realizationResult, HttpStatus.CREATED);
  }

}
