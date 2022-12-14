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
package org.apache.geode.management.internal.cli.functions;

import static org.apache.geode.internal.lang.SystemUtils.getOsArchitecture;
import static org.apache.geode.internal.lang.SystemUtils.getOsName;
import static org.apache.geode.internal.lang.SystemUtils.getOsVersion;
import static org.apache.geode.internal.lang.SystemUtils.isLinux;
import static org.apache.geode.internal.lang.SystemUtils.isMacOSX;
import static org.apache.geode.internal.lang.SystemUtils.isSolaris;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Logger;

import org.apache.geode.annotations.Immutable;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.internal.cache.execute.InternalFunction;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.management.internal.cli.CliUtils;
import org.apache.geode.management.internal.cli.CliUtils.DeflaterInflaterData;
import org.apache.geode.management.internal.cli.GfshParser;
import org.apache.geode.management.internal.i18n.CliStrings;

/**
 * Executes 'netstat' OS command & returns the result as compressed bytes.
 *
 * @since GemFire 7.0
 */
@SuppressWarnings({"serial"})
public class NetstatFunction implements InternalFunction<NetstatFunction.NetstatFunctionArgument> {
  private static final Logger logger = LogService.getLogger();
  private static final long serialVersionUID = 1L;

  @Immutable
  public static final NetstatFunction INSTANCE = new NetstatFunction();

  private static final String NETSTAT_COMMAND = "netstat";
  private static final String LSOF_COMMAND = "lsof";

  private static final String ID =
      "org.apache.geode.management.internal.cli.functions.NetstatFunction";

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public boolean hasResult() {
    return true;
  }

  @Override
  public void execute(final FunctionContext<NetstatFunctionArgument> context) {
    DistributedSystem ds = InternalDistributedSystem.getConnectedInstance();
    if (ds == null || !ds.isConnected()) {
      return;
    }

    String host = ds.getDistributedMember().getHost();
    NetstatFunctionArgument args = context.getArguments();
    boolean withlsof = args.isWithlsof();
    String lineSeparator = args.getLineSeparator();

    String netstatOutput = executeCommand(lineSeparator, withlsof);

    StringBuilder netstatInfo = new StringBuilder();

    // {0} will be replaced on Manager
    addMemberHostHeader(netstatInfo, "{0}", host, lineSeparator);

    NetstatFunctionResult result = new NetstatFunctionResult(host, netstatInfo.toString(),
        CliUtils.compressBytes(netstatOutput.getBytes()));

    context.getResultSender().lastResult(result);
  }

  private static void addMemberHostHeader(final StringBuilder netstatInfo, final String id,
      final String host, final String lineSeparator) {

    String osInfo = getOsName() + " " + getOsVersion() + " " + getOsArchitecture();

    int nameIdLength = Math.max(Math.max(id.length(), host.length()), osInfo.length()) * 2;

    StringBuilder netstatInfoBottom = new StringBuilder();
    for (int i = 0; i < nameIdLength; i++) {
      netstatInfo.append("#");
      netstatInfoBottom.append("#");
    }

    String memberPlatFormInfo = CliStrings.format(CliStrings.NETSTAT__MSG__FOR_HOST_1_OS_2_MEMBER_0,
        id, host, osInfo, lineSeparator);
    netstatInfo.append(lineSeparator).append(memberPlatFormInfo).append(lineSeparator)
        .append(netstatInfoBottom).append(lineSeparator);
  }

  private static void addNetstatDefaultOptions(final List<String> cmdOptionsList) {
    cmdOptionsList.add("-v");
    cmdOptionsList.add("-a");
    cmdOptionsList.add("-n");
    if (isLinux()) {
      cmdOptionsList.add("-e");
    }
  }

  private static void executeNetstat(final StringBuilder netstatInfo, final String lineSeparator) {
    List<String> cmdOptionsList = new ArrayList<>();
    cmdOptionsList.add(NETSTAT_COMMAND);
    addNetstatDefaultOptions(cmdOptionsList);

    if (logger.isDebugEnabled()) {
      logger.debug("NetstatFunction executing {}", cmdOptionsList);
    }

    ProcessBuilder processBuilder = new ProcessBuilder(cmdOptionsList);
    Process netstat = null;
    InputStreamReader inputStreamReader = null;
    BufferedReader breader = null;
    try {
      netstat = processBuilder.start();

      InputStream is = netstat.getInputStream();
      inputStreamReader = new InputStreamReader(is);
      breader = new BufferedReader(inputStreamReader);
      String line;

      while ((line = breader.readLine()) != null) {
        netstatInfo.append(line).append(lineSeparator);
      }
    } catch (IOException e) {
      // TODO: change this to keep the full stack trace
      netstatInfo.append(CliStrings.format(CliStrings.NETSTAT__MSG__COULD_NOT_EXECUTE_0_REASON_1,
          NETSTAT_COMMAND, e.getMessage()));
    } finally {
      netstatInfo.append(lineSeparator); // additional new line
      releaseResources(netstat, inputStreamReader, breader);
    }
  }

  private static void executeLsof(final StringBuilder existingNetstatInfo,
      final String lineSeparator) {
    existingNetstatInfo.append("################ ").append(LSOF_COMMAND)
        .append(" output ###################").append(lineSeparator);

    if (isLinux() || isMacOSX() || isSolaris()) {
      List<String> cmdOptionsList = new ArrayList<>();
      cmdOptionsList.add(LSOF_COMMAND);
      cmdOptionsList.add("-n");
      cmdOptionsList.add("-P");

      ProcessBuilder procBuilder = new ProcessBuilder(cmdOptionsList);

      Process lsof = null;
      InputStreamReader reader = null;
      BufferedReader breader = null;
      try {
        lsof = procBuilder.start();
        reader = new InputStreamReader(lsof.getInputStream());
        breader = new BufferedReader(reader);
        String line;

        while ((line = breader.readLine()) != null) {
          existingNetstatInfo.append(line).append(lineSeparator);
        }
      } catch (IOException e) {
        // TODO: change this to keep the full stack trace
        String message = e.getMessage();
        if (message.contains("error=2, No such file or directory")) {
          existingNetstatInfo
              .append(CliStrings.format(CliStrings.NETSTAT__MSG__COULD_NOT_EXECUTE_0_REASON_1,
                  LSOF_COMMAND, CliStrings.NETSTAT__MSG__LSOF_NOT_IN_PATH));
        } else {
          existingNetstatInfo
              .append(CliStrings.format(CliStrings.NETSTAT__MSG__COULD_NOT_EXECUTE_0_REASON_1,
                  LSOF_COMMAND, e.getMessage()));
        }
      } finally {
        releaseResources(lsof, reader, breader);
        existingNetstatInfo.append(lineSeparator); // additional new line
      }
    } else {
      existingNetstatInfo.append(CliStrings.NETSTAT__MSG__NOT_AVAILABLE_FOR_WINDOWS)
          .append(lineSeparator);
    }
  }

  private static void releaseResources(Process netstat, InputStreamReader inputStreamReader,
      BufferedReader breader) {
    if (breader != null) {
      try {
        breader.close();
      } catch (IOException ignore) {
      }
    }
    if (inputStreamReader != null) {
      try {
        inputStreamReader.close();
      } catch (IOException ignore) {
      }
    }
    if (netstat != null) {
      netstat.destroy();
    }
  }

  private static String executeCommand(final String lineSeparator, final boolean withlsof) {
    StringBuilder netstatInfo = new StringBuilder();

    executeNetstat(netstatInfo, lineSeparator);

    if (withlsof) {
      executeLsof(netstatInfo, lineSeparator);
    }

    return netstatInfo.toString();
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  public boolean isHA() {
    return false;
  }

  public static void main(final String[] args) {
    String netstat = executeCommand(GfshParser.LINE_SEPARATOR, true);
    System.out.println(netstat);
  }

  public static class NetstatFunctionArgument implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String lineSeparator;
    private final boolean withlsof;

    public NetstatFunctionArgument(final String lineSeparator, final boolean withlsof) {
      this.lineSeparator = lineSeparator;
      this.withlsof = withlsof;
    }

    public String getLineSeparator() {
      return lineSeparator;
    }

    public boolean isWithlsof() {
      return withlsof;
    }
  }

  public static class NetstatFunctionResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String host;
    private final String headerInfo;
    private final DeflaterInflaterData compressedBytes;

    protected NetstatFunctionResult(final String host, final String headerInfo,
        final DeflaterInflaterData compressedBytes) {
      this.host = host;
      this.headerInfo = headerInfo;
      this.compressedBytes = compressedBytes;
    }

    public String getHost() {
      return host;
    }

    public String getHeaderInfo() {
      return headerInfo;
    }

    public DeflaterInflaterData getCompressedBytes() {
      return compressedBytes;
    }
  }
}
