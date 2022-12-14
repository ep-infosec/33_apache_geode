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

package org.apache.geode.cache.wan;

/**
 *
 * @since GemFire 7.0
 */
public interface GatewayReceiverFactory {

  String A_GATEWAY_RECEIVER_ALREADY_EXISTS_ON_THIS_MEMBER =
      "A Gateway Receiver already exists on this member.";

  /**
   * Sets the start port for the <code>GatewayReceiver</code>. If set the GatewayReceiver will start
   * at one of the port between startPort and endPort. The default startPort 50505.
   *
   * @param startPort the start port for the GatewayReceiver
   * @return this factory
   */
  GatewayReceiverFactory setStartPort(int startPort);

  /**
   * Sets the end port for the GatewayReceiver. If set the GatewayReceiver will start at one of the
   * port between startPort and endPort. The default endPort 50605.
   *
   * @param endPort the end port for the GatewayReceiver
   * @return this factory
   */
  GatewayReceiverFactory setEndPort(int endPort);

  /**
   * Sets the buffer size in bytes of the socket connection for this <code>GatewayReceiver</code>.
   * The default is 32768 bytes.
   *
   * @param socketBufferSize The size in bytes of the socket buffer
   * @return this factory
   */
  GatewayReceiverFactory setSocketBufferSize(int socketBufferSize);

  /**
   * Sets the ip address or host name that this <code>GatewayReceiver</code> is to listen on for
   * GatewaySender Connection
   *
   * @param address String representing ip address or host name
   * @return this factory
   */
  GatewayReceiverFactory setBindAddress(String address);

  /**
   * Adds a <code>GatewayTransportFilter</code>
   *
   * @param filter GatewayTransportFilter
   * @return this factory
   */
  GatewayReceiverFactory addGatewayTransportFilter(GatewayTransportFilter filter);

  /**
   * Removes a <code>GatewayTransportFilter</code>
   *
   * @param filter GatewayTransportFilter
   * @return this factory
   */
  GatewayReceiverFactory removeGatewayTransportFilter(GatewayTransportFilter filter);

  /**
   * Sets the maximum amount of time between client pings.The default is 60000 ms.
   *
   * @param time The maximum amount of time between client pings
   * @return this factory
   */
  GatewayReceiverFactory setMaximumTimeBetweenPings(int time);

  /**
   * Sets the ip address or host name that server locators will tell GatewaySenders that this
   * GatewayReceiver is listening on.
   *
   * @param address String representing ip address or host name
   * @return this factory
   */
  GatewayReceiverFactory setHostnameForSenders(String address);

  /**
   * Sets the manual start boolean property for this <code>GatewayReceiver</code>.
   *
   * @since GemFire 8.1 Default is true i.e. the <code>GatewayReceiver</code> will not start
   *        automatically once created. Ideal default value should be false to match with
   *        GatewaySender counterpart. But to not to break the existing functionality default value
   *        is set to true. For next major releases, default value will be changed to false.
   *
   * @param start the manual start boolean property for this <code>GatewayReceiver</code>
   * @return this factory
   */
  GatewayReceiverFactory setManualStart(boolean start);

  /**
   * Creates and returns an instance of <code>GatewayReceiver</code>
   *
   * @return instance of GatewayReceiver
   */
  GatewayReceiver create();

}
