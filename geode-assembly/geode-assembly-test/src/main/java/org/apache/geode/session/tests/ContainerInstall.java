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
package org.apache.geode.session.tests;

import static java.nio.file.Files.isDirectory;
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static org.apache.commons.io.FilenameUtils.getBaseName;
import static org.apache.commons.io.FilenameUtils.getExtension;
import static org.apache.geode.management.internal.configuration.utils.ZipUtils.unzip;
import static org.apache.geode.test.util.ResourceUtils.getResource;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;
import java.util.function.IntSupplier;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.logging.log4j.Logger;
import org.codehaus.cargo.container.installer.Installer;
import org.codehaus.cargo.container.installer.ZipURLInstaller;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.apache.geode.logging.internal.log4j.api.LogService;

/**
 * Base class for handling downloading and configuring J2EE containers.
 *
 * This class contains common logic for downloading and configuring J2EE containers with cargo, and
 * some common methods for applying geode session replication configuration to those containers.
 *
 * Subclasses provide installation of specific containers.
 */
public abstract class ContainerInstall {

  static final Logger logger = LogService.getLogger();

  private final IntSupplier portSupplier;

  static final Path GEODE_HOME_PATH = Paths.get(System.getenv("GEODE_HOME"));
  static final Path GEODE_LIB_PATH = GEODE_HOME_PATH.resolve("lib");
  static final Path DEFAULT_MODULE_PATH = GEODE_HOME_PATH.resolve("tools").resolve("Modules");

  protected IntSupplier portSupplier() {
    return portSupplier;
  }

  private final ConnectionType connType;
  private final Path rootDir;
  private final Path installPath;
  private final Path modulePath;
  private final Path warFilePath;
  private final String defaultLocatorAddress;

  private int defaultLocatorPort;

  /**
   * Represents the type of connection used in this installation
   *
   * Supports either PEER_TO_PEER or CLIENT_SERVER. Also containers several useful strings needed to
   * identify XML files or connection types when setting up containers.
   */
  public enum ConnectionType {
    PEER_TO_PEER("peer-to-peer", "cache-peer.xml", false, false),
    CLIENT_SERVER("client-server", "cache-client.xml", false, true),
    CACHING_CLIENT_SERVER("client-server", "cache-client.xml", true, true);

    private final String name;

    private final String cacheXMLFileName;

    private final boolean enableLocalCache;
    private final boolean isClientServer;

    ConnectionType(String name, String cacheXMLFileName, boolean enableLocalCache,
        boolean isClientServer) {
      this.name = name;
      this.cacheXMLFileName = cacheXMLFileName;
      this.enableLocalCache = enableLocalCache;
      this.isClientServer = isClientServer;
    }

    public String getName() {
      return name;
    }

    public String getCacheXMLFileName() {
      return cacheXMLFileName;
    }

    public boolean enableLocalCache() {
      return enableLocalCache;
    }

    public boolean isClientServer() {
      return isClientServer;
    }
  }

  public ContainerInstall(Path rootDir, String name, String downloadURL,
      ConnectionType connectionType,
      String moduleName, IntSupplier portSupplier) throws IOException {
    this(rootDir, name, downloadURL, connectionType, moduleName, DEFAULT_MODULE_PATH, portSupplier);
  }

  /**
   * @param rootDir The root folder used by default for cargo logs, container configs and other
   *        files and directories
   * @param name used to name install directory
   * @param downloadURL the URL from which to download the container
   * @param connType Enum representing the connection type of this installation (either client
   *        server or peer to peer)
   * @param moduleName The module name of the installation being setup (i.e. tomcat, appserver,
   *        etc.)
   * @param geodeModuleLocation the path to the module jars
   * @param portSupplier the port supplier
   * @throws IOException if an exception is encountered when deleting or accessing files
   */
  public ContainerInstall(Path rootDir, String name, String downloadURL, ConnectionType connType,
      String moduleName, Path geodeModuleLocation, IntSupplier portSupplier)
      throws IOException {
    this.rootDir = rootDir;

    this.connType = connType;
    this.portSupplier = portSupplier;

    Path installDir = rootDir.toAbsolutePath().resolve("cargo_containers").resolve(name);

    clearPreviousInstall(installDir);

    URL url = getResource(getClass(), "/" + downloadURL);
    logger.info("Installing container from URL {}", url);

    // Optional step to install the container from a URL pointing to its distribution
    Installer installer =
        new ZipURLInstaller(url, installDir + "/downloads", installDir.toString());
    installer.install();

    // Set install home
    installPath = Paths.get(installer.getHome());

    // Find and extract the module path
    Path modulesDir = rootDir.toAbsolutePath().resolve("cargo_modules");
    modulePath = findAndExtractModule(modulesDir, geodeModuleLocation, moduleName);
    logger.info("Extracted module {} to {}", moduleName, modulePath);

    // Find the session testing war path
    warFilePath = findSessionTestingWar();

    // Default locator
    defaultLocatorPort = 8080;
    defaultLocatorAddress = "localhost";

    logger.info("Installed container into " + getHome());
  }

  ServerContainer generateContainer(String containerDescriptors) throws IOException {
    return generateContainer(rootDir, null, containerDescriptors);
  }

  /**
   * Cleans up the installation by deleting the extracted module and downloaded installation folders
   */
  private void clearPreviousInstall(Path installDir) throws IOException {
    // Remove installs from previous runs in the same folder
    if (Files.exists(installDir)) {
      logger.info("Deleting previous install folder {}", installDir);
      deleteDirectory(installDir.toFile());
    }
  }

  void setDefaultLocatorPort(int port) {
    defaultLocatorPort = port;
  }

  /**
   * Whether the installation is client server
   *
   * Since an installation can only be client server or peer to peer there is no need for a function
   * which checks for a peer to peer installation (just check if not client server).
   */
  boolean isClientServer() {
    return connType.isClientServer();
  }

  Path getRootDir() {
    return rootDir;
  }

  /*
   * Where the installation is located
   */
  public Path getHome() {
    return installPath;
  }

  /**
   * Where the module is located
   *
   * The module contains jars needed for geode session setup as well as default templates for some
   * needed XML files.
   */
  String getModulePath() {
    return modulePath.toString();
  }

  /**
   * The path to the session testing WAR file
   */
  Path getWarFilePath() {
    return warFilePath;
  }

  /**
   * @return The enum {@link #connType} which represents the type of connection for this
   *         installation
   */
  ConnectionType getConnectionType() {
    return connType;
  }

  /**
   * Gets the {@link #defaultLocatorAddress}
   *
   * This is the address that a container uses by default. Containers themselves can have their own
   * personal locator address, but will default to this address unless specifically set.
   */
  String getDefaultLocatorAddress() {
    return defaultLocatorAddress;
  }

  /**
   * Gets the {@link #defaultLocatorPort}
   *
   * This is the port that a container uses by default. Containers themselves can have their own
   * personal locator port, but will default to this port unless specifically set.
   */
  int getDefaultLocatorPort() {
    return defaultLocatorPort;
  }

  /*
   * Gets the cache XML file to use by default for this installation
   */
  Path getCacheXMLFile() {
    return modulePath.resolve("conf").resolve(getConnectionType().getCacheXMLFileName());
  }

  /*
   * Cargo specific string to identify the container with
   */
  public abstract String getInstallId();

  /*
   * A human readable description of the installation
   */
  public abstract String getInstallDescription();

  /*
   * Get the session manager class to use
   */
  public abstract String getContextSessionManagerClass();

  /**
   * Generates a {@link ServerContainer} from the given {@code ContainerInstall}
   *
   * @param rootDir The root folder used by default for cargo logs, container configs and other
   *        files and directories
   * @param containerConfigHome The folder that the container configuration folder should be setup
   *        in
   * @param containerDescriptors Additional descriptors used to identify a container
   * @return the newly generated {@link ServerContainer}
   * @throws IOException if an exception is encountered
   */
  public abstract ServerContainer generateContainer(Path rootDir,
      Path containerConfigHome,
      String containerDescriptors) throws IOException;

  /**
   * Get the path to the session testing war by walking up directories to the correct folder.
   *
   * NOTE::This walks into the extensions folder and then uses a hardcoded path from there making it
   * very unreliable if things are moved.
   */
  private static Path findSessionTestingWar() {
    // Start out searching directory above current
    String curPath = "../";

    // Looking for extensions folder
    final String warModuleDirName = "extensions";
    File warModuleDir = null;

    // While directory searching for is not found
    while (warModuleDir == null) {
      // Try to find the find the directory in the current directory
      File[] files = new File(curPath).listFiles();
      for (File file : files) {
        if (file.isDirectory() && file.getName().equals(warModuleDirName)) {
          warModuleDir = file;
          break;
        }
      }

      // Keep moving up until you find it
      curPath += "../";
    }

    // Return path to extensions plus hardcoded path from there to the WAR
    return warModuleDir.toPath().toAbsolutePath().normalize().resolve(
        Paths.get("session-testing-war", "build", "libs", "session-testing-war.war"));
  }

  /**
   * Finds and extracts the geode module associated with the specified module.
   *
   * @param moduleName The module name (i.e. tomcat, appserver, etc.) of the module that should be
   *        extract. Used as a search parameter to find the module archive.
   * @return The path to the non-archive (extracted) version of the module files
   */
  private Path findAndExtractModule(Path defaultModulesDir, Path geodeModuleLocation,
      String moduleName)
      throws IOException {
    logger.info("Trying to access build dir {}", geodeModuleLocation);

    // Search directory for tomcat module folder/zip
    final Path moduleSource = Files.list(geodeModuleLocation)
        .filter(p -> p.toString().toLowerCase().contains(moduleName))
        .findFirst()
        .map(Path::toAbsolutePath)
        .orElseThrow(() -> new AssertionError("Could not find module " + moduleName));

    if (isDirectory(moduleSource)) {
      return moduleSource;
    }

    String moduleFileNameFull = moduleSource.getFileName().toString();

    // Get the name of the new module folder within the extraction directory
    final Path moduleDestinationDir = defaultModulesDir.resolve(getBaseName(moduleFileNameFull));

    // Remove any previous module folders extracted here
    if (isDirectory(moduleDestinationDir)) {
      logger.info("Deleting previous modules directory {}", moduleDestinationDir);
      deleteDirectory(moduleDestinationDir.toFile());
    }

    // Unzip if it is a zip file
    assertThat(moduleSource).isRegularFile();

    if (!getExtension(moduleFileNameFull).equals("zip")) {
      throw new IOException("Bad module archive " + moduleSource);
    }

    // Extract folder to location if not already there
    unzip(moduleSource.toString(), moduleDestinationDir.toString());

    return moduleDestinationDir;
  }

  /**
   * Edits the specified property within the given property file
   *
   * @param filePath path to the property file
   * @param propertyName property name to edit
   * @param propertyValue new property value
   * @param append whether or not to append the given property value. If true appends the given
   *        property value the current value. If false, replaces the current property value with the
   *        given property value
   */
  static void editPropertyFile(Path filePath, String propertyName, String propertyValue,
      boolean append) throws Exception {
    InputStream input = Files.newInputStream(filePath);
    Properties properties = new Properties();
    properties.load(input);

    String val;
    if (append) {
      val = properties.getProperty(propertyName) + propertyValue;
    } else {
      val = propertyValue;
    }

    properties.setProperty(propertyName, val);
    properties.store(Files.newOutputStream(filePath), null);

    logger.info("Modified container Property file " + filePath);
  }

  static void editXMLFile(Path XMLPath, String tagId, String tagName,
      String parentTagName, HashMap<String, String> attributes) {
    editXMLFile(XMLPath, tagId, tagName, tagName, parentTagName, attributes, false);
  }

  static void editXMLFile(Path XMLPath, String tagName, String parentTagName,
      HashMap<String, String> attributes, boolean writeOnSimilarAttributeNames) {
    editXMLFile(XMLPath, null, tagName, tagName, parentTagName, attributes,
        writeOnSimilarAttributeNames);
  }

  /**
   * Edit the given xml file
   *
   * Uses {@link #findNodeWithAttribute(Document, String, String, String)},
   * {@link #rewriteNodeAttributes(Node, HashMap)},
   * {@link #nodeHasExactAttributes(Node, HashMap, boolean)} to edit the required parts of the XML
   * file.
   *
   * @param XMLPath The path to the xml file to edit
   * @param tagId The id of tag to edit. If null, then this method will add a new xml element,
   *        unless writeOnSimilarAttributeNames is set to true.
   * @param tagName The name of the xml element to edit
   * @param replacementTagName The new name of the XML attribute that is being edited
   * @param parentTagName The parent element of the element we should edit
   * @param attributes the xml attributes for the element to edit
   * @param writeOnSimilarAttributeNames If true, find an existing element with the same set of
   *        attributes as the attributes parameter, and modifies the attributes of that element,
   *        rather than adding a new element. If false, create a new XML element (unless tagId is
   *        not null).
   */
  private static void editXMLFile(Path XMLPath, String tagId, String tagName,
      String replacementTagName, String parentTagName, HashMap<String, String> attributes,
      boolean writeOnSimilarAttributeNames) {

    try {
      // Get XML file to edit
      DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
      Document doc = docBuilder.parse(XMLPath.toFile());

      Node node = null;
      // Get node with specified tagId
      if (tagId != null) {
        node = findNodeWithAttribute(doc, tagName, "id", tagId);
      }
      // If writing on similar attributes then search by tag name
      else if (writeOnSimilarAttributeNames) {
        // Get all the nodes with the given tag name
        NodeList nodes = doc.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
          Node n = nodes.item(i);
          // If the node being iterated across has the exact attributes then it is the one that
          // should be edited
          if (nodeHasExactAttributes(n, attributes, false)) {
            node = n;
            break;
          }
        }
      }
      // If a node if found
      if (node != null) {
        doc.renameNode(node, null, replacementTagName);
        // Rewrite the node attributes
        rewriteNodeAttributes(node, attributes);
        // Write the tagId so that it can be found easier next time
        if (tagId != null) {
          ((Element) node).setAttribute("id", tagId);
        }
      }
      // No node found creates new element under the parent tag passed in
      else {
        Element e = doc.createElement(replacementTagName);
        // Set id attribute
        if (tagId != null) {
          e.setAttribute("id", tagId);
        }
        // Set other attributes
        for (String key : attributes.keySet()) {
          e.setAttribute(key, attributes.get(key));
        }

        // Add it as a child of the tag for the file
        doc.getElementsByTagName(parentTagName).item(0).appendChild(e);
      }

      // Write updated XML file
      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      Transformer transformer = transformerFactory.newTransformer();
      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(XMLPath.toFile());
      transformer.transform(source, result);

      logger.info("Modified container XML file " + XMLPath);
    } catch (Exception e) {
      throw new RuntimeException("Unable to edit XML file " + XMLPath, e);
    }
  }

  /**
   * Finds the node in the given document with the given name and attribute
   *
   * @param doc XML document to search for the node
   * @param nodeName The name of the node to search for
   * @param name The name of the attribute that the node should contain
   * @param value The value of the node's given attribute
   * @return Node with the given name, attribute, and attribute value
   */
  private static Node findNodeWithAttribute(Document doc, String nodeName, String name,
      String value) {
    // Get all nodes with given name
    NodeList nodes = doc.getElementsByTagName(nodeName);
    if (nodes == null) {
      return null;
    }

    // Find and return the first node that has the given attribute
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      Node nodeAttr = node.getAttributes().getNamedItem(name);

      if (nodeAttr != null && nodeAttr.getTextContent().equals(value)) {
        return node;
      }
    }

    return null;
  }

  /**
   * Replaces the node's attributes with the attributes in the given hashmap
   *
   * @param node XML node that should be edited
   * @param attributes HashMap of strings representing the attributes of a node (key = value)
   * @return The given node with ONLY the given attributes
   */
  private static Node rewriteNodeAttributes(Node node, HashMap<String, String> attributes) {
    NamedNodeMap nodeAttrs = node.getAttributes();

    // Remove all previous attributes
    while (nodeAttrs.getLength() > 0) {
      nodeAttrs.removeNamedItem(nodeAttrs.item(0).getNodeName());
    }

    // Set to new attributes
    for (String key : attributes.keySet()) {
      ((Element) node).setAttribute(key, attributes.get(key));
    }

    return node;
  }

  /**
   * Checks to see whether the given XML node has the exact attributes given in the attributes
   * hashmap
   *
   * @param checkSimilarValues If true, will also check to make sure that the given node's
   *        attributes also have the exact same values as the ones given in the attributes HashMap.
   * @return True if the node has only the attributes the are given by the HashMap (no more and no
   *         less attributes). If {@param checkSimilarValues} is true then only returns true if the
   *         node shares attributes with the given attribute list exactly.
   */
  private static boolean nodeHasExactAttributes(Node node, HashMap<String, String> attributes,
      boolean checkSimilarValues) {
    NamedNodeMap nodeAttrs = node.getAttributes();

    // Check to make sure the node has all attribute fields
    for (String key : attributes.keySet()) {
      Node attr = nodeAttrs.getNamedItem(key);
      if (attr == null
          || checkSimilarValues && !attr.getTextContent().equals(attributes.get(key))) {
        return false;
      }
    }

    // Check to make sure the node does not have more than the attribute fields
    for (int i = 0; i < nodeAttrs.getLength(); i++) {
      String attr = nodeAttrs.item(i).getNodeName();
      if (attributes.get(attr) == null || checkSimilarValues
          && !attributes.get(attr).equals(nodeAttrs.item(i).getTextContent())) {
        return false;
      }
    }

    return true;
  }

  private static String createTempDir() {
    try {
      return Files.createTempDirectory("geode_container_install")
          .toAbsolutePath()
          .toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
