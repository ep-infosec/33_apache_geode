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
package org.apache.geode.internal.classloader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Vector;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.generic.ClassGen;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.rules.TemporaryFolder;

import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.ResultSender;
import org.apache.geode.internal.cache.execute.FunctionContextImpl;
import org.apache.geode.management.configuration.Deployment;
import org.apache.geode.services.result.ServiceResult;
import org.apache.geode.test.compiler.ClassBuilder;
import org.apache.geode.test.compiler.JarBuilder;
import org.apache.geode.test.junit.rules.RestoreTCCLRule;

/**
 * Integration tests for {@link ClassPathLoader}.
 */
public class ClassPathLoaderIntegrationTest {

  private static final int TEMP_FILE_BYTES_COUNT = 256;

  private File tempFile;
  private File tempFile2;
  private final ClassBuilder classBuilder = new ClassBuilder();

  @Rule
  public RestoreTCCLRule restoreTCCLRule = new RestoreTCCLRule();

  @Rule
  public RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties();

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Before
  public void setUp() throws Exception {
    System.setProperty(ClassPathLoader.EXCLUDE_TCCL_PROPERTY, "false");

    tempFile = temporaryFolder.newFile("tempFile1.tmp");
    FileOutputStream fos = new FileOutputStream(tempFile);
    fos.write(new byte[TEMP_FILE_BYTES_COUNT]);
    fos.close();

    tempFile2 = temporaryFolder.newFile("tempFile2.tmp");
    fos = new FileOutputStream(tempFile2);
    fos.write(new byte[TEMP_FILE_BYTES_COUNT]);
    fos.close();

    // System.setProperty("user.dir", temporaryFolder.getRoot().getAbsolutePath());
    ClassPathLoader.setLatestToDefault(temporaryFolder.getRoot());
  }

  @After
  public void teardown() {
    ClassPathLoader.setLatestToDefault(null);
  }

  @Test
  public void testClassLoaderWithNullTccl() throws IOException, ClassNotFoundException {
    // GEODE-2796
    Thread.currentThread().setContextClassLoader(null);
    String jarName = "JarDeployerIntegrationTest.jar";

    String classAResource = "integration/parent/ClassA.class";

    String classAName = "integration.parent.ClassA";

    File firstJar = createJarWithClass(jarName, "ClassA");

    // First deploy of the JAR file
    Deployment deployment = createDeploymentFromJar(firstJar);
    ClassPathLoader.getLatest().getJarDeploymentService().deploy(deployment);

    assertThatClassCanBeLoaded(classAName);
    assertThatResourceCanBeLoaded(classAResource);
  }

  @Test
  public void testDeployFileAndChange() throws IOException, ClassNotFoundException {
    String jarName = "JarDeployerIntegrationTest.jar";

    String classAResource = "integration/parent/ClassA.class";
    String classBResource = "integration/parent/ClassB.class";

    String classAName = "integration.parent.ClassA";
    String classBName = "integration.parent.ClassB";

    File firstJar = createJarWithClass(jarName, "ClassA");
    ByteArrayOutputStream firstJarBytes = new ByteArrayOutputStream();
    IOUtils.copy(new FileInputStream(firstJar), firstJarBytes);

    // First deploy of the JAR file
    Deployment deployment = createDeploymentFromJar(firstJar);
    ServiceResult<Deployment> serviceResult =
        ClassPathLoader.getLatest().getJarDeploymentService()
            .deploy(deployment);

    assertThat(firstJar).exists().hasBinaryContent(firstJarBytes.toByteArray());
    assertThat(serviceResult.getMessage().getFilePath()).contains(".v1.").doesNotContain(".v2.");

    assertThatClassCanBeLoaded(classAName);
    assertThatClassCannotBeLoaded(classBName);

    assertThatResourceCanBeLoaded(classAResource);
    assertThatResourceCannotBeLoaded(classBResource);

    // Now deploy an updated JAR file and make sure that the next version of the JAR file
    // was created and the first one is no longer used
    File secondJar = createJarWithClass(jarName, "ClassB");
    ByteArrayOutputStream secondJarBytes = new ByteArrayOutputStream();
    IOUtils.copy(new FileInputStream(secondJar), secondJarBytes);

    Deployment secondDeployment = createDeploymentFromJar(secondJar);
    serviceResult =
        ClassPathLoader.getLatest().getJarDeploymentService().deploy(secondDeployment);

    assertThat(secondJar).exists().hasBinaryContent(secondJarBytes.toByteArray());
    assertThat(serviceResult.getMessage().getFilePath()).contains(".v2.").doesNotContain(".v1.");

    assertThatClassCanBeLoaded(classBName);
    assertThatClassCannotBeLoaded(classAName);

    assertThatResourceCanBeLoaded(classBResource);
    assertThatResourceCannotBeLoaded(classAResource);

    // Now undeploy JAR and make sure it gets cleaned up
    ClassPathLoader.getLatest().getJarDeploymentService().undeployByFileName(jarName);
    assertThatClassCannotBeLoaded(classBName);
    assertThatClassCannotBeLoaded(classAName);

    assertThatResourceCannotBeLoaded(classBResource);
    assertThatResourceCannotBeLoaded(classAResource);
  }

  @Test
  public void testDeployNoUpdateWhenNoChange() throws IOException, ClassNotFoundException {
    String jarName = "JarDeployerIntegrationTest.jar";

    // First deploy of the JAR file
    byte[] jarBytes = new ClassBuilder().createJarFromName("JarDeployerDUnitDNUWNC");
    File jarFile = temporaryFolder.newFile(jarName);
    writeJarBytesToFile(jarFile, jarBytes);
    Deployment deployment = createDeploymentFromJar(jarFile);
    ServiceResult<Deployment> serviceResult =
        ClassPathLoader.getLatest().getJarDeploymentService()
            .deploy(deployment);
    File deployedJar = new File(jarFile.getCanonicalPath());

    assertThat(deployedJar).exists();
    assertThat(serviceResult.getMessage().getFilePath()).contains(".v1.");

    // Re-deploy of the same JAR should do nothing

    serviceResult =
        ClassPathLoader.getLatest().getJarDeploymentService().deploy(deployment);
    assertThat(serviceResult.isSuccessful()).isTrue();
    assertThat(deployedJar).exists();
  }

  private void assertThatClassCanBeLoaded(String className) throws ClassNotFoundException {
    assertThat(ClassPathLoader.getLatest().forName(className)).isNotNull();
  }

  private void assertThatClassCannotBeLoaded(String className) throws ClassNotFoundException {
    assertThatThrownBy(() -> ClassPathLoader.getLatest().forName(className))
        .isExactlyInstanceOf(ClassNotFoundException.class);
  }

  private void assertThatResourceCanBeLoaded(String resourceName) throws IOException {
    // ClassPathLoader.getResource
    assertThat(ClassPathLoader.getLatest().getResource(resourceName)).isNotNull();

    // ClassPathLoader.getResources
    Enumeration<URL> urls = ClassPathLoader.getLatest().getResources(resourceName);
    assertThat(urls).isNotNull();
    assertThat(urls.hasMoreElements()).isTrue();

    // ClassPathLoader.getResourceAsStream
    InputStream is = ClassPathLoader.getLatest().getResourceAsStream(resourceName);
    assertThat(is).isNotNull();
  }

  private void assertThatResourceCannotBeLoaded(String resourceName) throws IOException {
    // ClassPathLoader.getResource
    assertThat(ClassPathLoader.getLatest().getResource(resourceName)).isNull();

    // ClassPathLoader.getResources
    Enumeration<URL> urls = ClassPathLoader.getLatest().getResources(resourceName);
    assertThat(urls.hasMoreElements()).isFalse();

    // ClassPathLoader.getResourceAsStream
    InputStream is = ClassPathLoader.getLatest().getResourceAsStream(resourceName);
    assertThat(is).isNull();
  }


  /**
   * Verifies that <tt>getResource</tt> works with TCCL from {@link ClassPathLoader}.
   */
  @Test
  public void testGetResourceWithTCCL() throws Exception {
    System.out.println("\nStarting ClassPathLoaderTest#testGetResourceWithTCCL");

    ClassPathLoader dcl = ClassPathLoader.createWithDefaults(false);

    String resourceToGet = "com/nowhere/testGetResourceWithTCCL.rsc";
    assertNull(dcl.getResource(resourceToGet));

    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(new GeneratingClassLoader());
      URL url = dcl.getResource(resourceToGet);
      assertNotNull(url);

      InputStream is = url.openStream();
      assertNotNull(is);

      int totalBytesRead = 0;
      byte[] input = new byte[128];

      BufferedInputStream bis = new BufferedInputStream(is);
      for (int bytesRead = bis.read(input); bytesRead > -1;) {
        totalBytesRead += bytesRead;
        bytesRead = bis.read(input);
      }
      bis.close();

      assertEquals(TEMP_FILE_BYTES_COUNT, totalBytesRead);
    } finally {
      Thread.currentThread().setContextClassLoader(cl);
    }
  }

  /**
   * Verifies that <tt>getResources</tt> works with TCCL from {@link ClassPathLoader}.
   */
  @Test
  public void testGetResourcesWithTCCL() throws Exception {
    System.out.println("\nStarting ClassPathLoaderTest#testGetResourceWithTCCL");

    ClassPathLoader dcl = ClassPathLoader.createWithDefaults(false);

    String resourceToGet = "com/nowhere/testGetResourceWithTCCL.rsc";
    Enumeration<URL> urls = dcl.getResources(resourceToGet);
    assertNotNull(urls);
    assertFalse(urls.hasMoreElements());

    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(new GeneratingClassLoader());
      urls = dcl.getResources(resourceToGet);
      assertNotNull(urls);

      URL url = urls.nextElement();
      InputStream is = url.openStream();
      assertNotNull(is);

      int totalBytesRead = 0;
      byte[] input = new byte[128];

      BufferedInputStream bis = new BufferedInputStream(is);
      for (int bytesRead = bis.read(input); bytesRead > -1;) {
        totalBytesRead += bytesRead;
        bytesRead = bis.read(input);
      }
      bis.close();

      assertEquals(TEMP_FILE_BYTES_COUNT, totalBytesRead);
    } finally {
      Thread.currentThread().setContextClassLoader(cl);
    }
  }

  /**
   * Verifies that <tt>getResourceAsStream</tt> works with TCCL from {@link ClassPathLoader}.
   */
  @Test
  public void testGetResourceAsStreamWithTCCL() throws Exception {
    System.out.println("\nStarting ClassPathLoaderTest#testGetResourceAsStreamWithTCCL");

    ClassPathLoader dcl = ClassPathLoader.createWithDefaults(false);

    String resourceToGet = "com/nowhere/testGetResourceAsStreamWithTCCL.rsc";
    assertNull(dcl.getResourceAsStream(resourceToGet));

    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    try {
      // ensure that TCCL is only CL that can find this resource
      Thread.currentThread().setContextClassLoader(new GeneratingClassLoader());
      InputStream is = dcl.getResourceAsStream(resourceToGet);
      assertNotNull(is);

      int totalBytesRead = 0;
      byte[] input = new byte[128];

      BufferedInputStream bis = new BufferedInputStream(is);
      for (int bytesRead = bis.read(input); bytesRead > -1;) {
        totalBytesRead += bytesRead;
        bytesRead = bis.read(input);
      }
      bis.close();

      assertEquals(TEMP_FILE_BYTES_COUNT, totalBytesRead);
    } finally {
      Thread.currentThread().setContextClassLoader(cl);
    }
  }

  @Test
  public void testDeclarableFunctionsWithNoCacheXml() throws Exception {
    final String jarFilename = "JarClassLoaderJUnitNoXml.jar";

    // Add a Declarable Function without parameters for the class to the Classpath
    String functionString =
        "import java.util.Properties;" + "import org.apache.geode.cache.Declarable;"
            + "import org.apache.geode.cache.execute.Function;"
            + "import org.apache.geode.cache.execute.FunctionContext;"
            + "public class JarClassLoaderJUnitFunctionNoXml implements Function, Declarable {"
            + "public String getId() {return \"JarClassLoaderJUnitFunctionNoXml\";}"
            + "public void init(Properties props) {}"
            + "public void execute(FunctionContext context) {context.getResultSender().lastResult(\"NOPARMSv1\");}"
            + "public boolean hasResult() {return true;}"
            + "public boolean optimizeForWrite() {return false;}"
            + "public boolean isHA() {return false;}}";

    byte[] jarBytes = classBuilder
        .createJarFromClassContent("JarClassLoaderJUnitFunctionNoXml", functionString);
    File jarFile = temporaryFolder.newFile(jarFilename);
    writeJarBytesToFile(jarFile, jarBytes);

    Deployment deployment = createDeploymentFromJar(jarFile);
    ClassPathLoader.getLatest().getJarDeploymentService().deploy(deployment);

    ClassPathLoader.getLatest().forName("JarClassLoaderJUnitFunctionNoXml");

    // Check to see if the function without parameters executes correctly
    Function function = FunctionService.getFunction("JarClassLoaderJUnitFunctionNoXml");
    assertThat(function).isNotNull();
    TestResultSender resultSender = new TestResultSender();
    function.execute(new FunctionContextImpl(null, function.getId(), null, resultSender));
    assertThat((String) resultSender.getResults()).isEqualTo("NOPARMSv1");
  }

  @Test
  public void testDependencyBetweenJars() throws Exception {
    final File parentJarFile = temporaryFolder.newFile("JarClassLoaderJUnitParent.jar");
    final File usesJarFile = temporaryFolder.newFile("JarClassLoaderJUnitUses.jar");

    // Write out a JAR files.
    StringBuilder StringBuilder = new StringBuilder();
    StringBuilder.append("package jcljunit.parent;");
    StringBuilder.append("public class JarClassLoaderJUnitParent {");
    StringBuilder.append("public String getValueParent() {");
    StringBuilder.append("return \"PARENT\";}}");

    byte[] jarBytes = classBuilder.createJarFromClassContent(
        "jcljunit/parent/JarClassLoaderJUnitParent", StringBuilder.toString());
    writeJarBytesToFile(parentJarFile, jarBytes);
    Deployment parentDeployment = createDeploymentFromJar(parentJarFile);
    ClassPathLoader.getLatest().getJarDeploymentService().deploy(parentDeployment);

    StringBuilder = new StringBuilder();
    StringBuilder.append("package jcljunit.uses;");
    StringBuilder.append("public class JarClassLoaderJUnitUses {");
    StringBuilder.append("public String getValueUses() {");
    StringBuilder.append("return \"USES\";}}");

    jarBytes = classBuilder.createJarFromClassContent("jcljunit/uses/JarClassLoaderJUnitUses",
        StringBuilder.toString());
    writeJarBytesToFile(usesJarFile, jarBytes);
    Deployment userDeployment = createDeploymentFromJar(usesJarFile);
    ClassPathLoader.getLatest().getJarDeploymentService().deploy(userDeployment);

    StringBuilder = new StringBuilder();
    StringBuilder.append("package jcljunit.function;");
    StringBuilder.append("import jcljunit.parent.JarClassLoaderJUnitParent;");
    StringBuilder.append("import jcljunit.uses.JarClassLoaderJUnitUses;");
    StringBuilder.append("import org.apache.geode.cache.execute.Function;");
    StringBuilder.append("import org.apache.geode.cache.execute.FunctionContext;");
    StringBuilder.append(
        "public class JarClassLoaderJUnitFunction  extends JarClassLoaderJUnitParent implements Function {");
    StringBuilder.append("private JarClassLoaderJUnitUses uses = new JarClassLoaderJUnitUses();");
    StringBuilder.append("public boolean hasResult() {return true;}");
    StringBuilder.append(
        "public void execute(FunctionContext context) {context.getResultSender().lastResult(getValueParent() + \":\" + uses.getValueUses());}");
    StringBuilder.append("public String getId() {return \"JarClassLoaderJUnitFunction\";}");
    StringBuilder.append("public boolean optimizeForWrite() {return false;}");
    StringBuilder.append("public boolean isHA() {return false;}}");

    ClassBuilder functionClassBuilder = new ClassBuilder();
    functionClassBuilder.addToClassPath(parentJarFile.getAbsolutePath());
    functionClassBuilder.addToClassPath(usesJarFile.getAbsolutePath());
    jarBytes = functionClassBuilder.createJarFromClassContent(
        "jcljunit/function/JarClassLoaderJUnitFunction", StringBuilder.toString());
    File jarFunction = temporaryFolder.newFile("JarClassLoaderJUnitFunction.jar");
    writeJarBytesToFile(jarFunction, jarBytes);

    Deployment deployment = createDeploymentFromJar(jarFunction);
    ClassPathLoader.getLatest().getJarDeploymentService().deploy(deployment);

    Function function = FunctionService.getFunction("JarClassLoaderJUnitFunction");
    assertThat(function).isNotNull();
    TestResultSender resultSender = new TestResultSender();
    FunctionContext functionContext =
        new FunctionContextImpl(null, function.getId(), null, resultSender);
    function.execute(functionContext);
    assertThat((String) resultSender.getResults()).isEqualTo("PARENT:USES");
  }

  @Test
  public void testFindResource() throws IOException, ClassNotFoundException {
    final String fileName = "file.txt";
    final String fileContent = "FILE CONTENT";

    byte[] jarBytes = classBuilder.createJarFromFileContent(fileName, fileContent);
    File tempJar = temporaryFolder.newFile("JarClassLoaderJUnitResource.jar");
    writeJarBytesToFile(tempJar, jarBytes);
    Deployment deployment = createDeploymentFromJar(tempJar);
    ClassPathLoader.getLatest().getJarDeploymentService().deploy(deployment);

    InputStream inputStream = ClassPathLoader.getLatest().getResourceAsStream(fileName);
    assertThat(inputStream).isNotNull();

    final byte[] fileBytes = new byte[fileContent.length()];
    inputStream.read(fileBytes);
    inputStream.close();
    assertThat(fileContent).isEqualTo(new String(fileBytes));
  }


  @Test
  public void testUpdateClassInJar() throws Exception {
    // First use of the JAR file
    byte[] jarBytes = classBuilder.createJarFromClassContent("JarClassLoaderJUnitTestClass",
        "public class JarClassLoaderJUnitTestClass { public Integer getValue5() { return new Integer(5); } }");
    File jarFile = temporaryFolder.newFile("JarClassLoaderJUnitUpdate.jar");
    writeJarBytesToFile(jarFile, jarBytes);
    Deployment deployment = createDeploymentFromJar(jarFile);
    ClassPathLoader.getLatest().getJarDeploymentService().deploy(deployment);

    Class<?> clazz = ClassPathLoader.getLatest().forName("JarClassLoaderJUnitTestClass");
    Object object = clazz.newInstance();
    Method getValue5Method = clazz.getMethod("getValue5");
    Integer value = (Integer) getValue5Method.invoke(object);
    assertThat(value).isEqualTo(5);

    // Now create an updated JAR file and make sure that the method from the new
    // class is available.
    jarBytes = classBuilder.createJarFromClassContent("JarClassLoaderJUnitTestClass",
        "public class JarClassLoaderJUnitTestClass { public Integer getValue10() { return new Integer(10); } }");
    File jarFile2 = new File(temporaryFolder.getRoot(), "JarClassLoaderJUnitUpdate.jar");
    writeJarBytesToFile(jarFile2, jarBytes);
    Deployment deployment2 = createDeploymentFromJar(jarFile2);
    ClassPathLoader.getLatest().getJarDeploymentService().deploy(deployment2);

    clazz = ClassPathLoader.getLatest().forName("JarClassLoaderJUnitTestClass");
    object = clazz.newInstance();
    Method getValue10Method = clazz.getMethod("getValue10");
    value = (Integer) getValue10Method.invoke(object);
    assertThat(value).isEqualTo(10);
  }

  private void writeJarBytesToFile(File jarFile, byte[] jarBytes) throws IOException {
    final OutputStream outStream = new FileOutputStream(jarFile);
    outStream.write(jarBytes);
    outStream.close();
  }

  /**
   * Custom class loader which uses BCEL to always dynamically generate a class for any class name
   * it tries to load.
   */
  private class GeneratingClassLoader extends ClassLoader {

    /**
     * Currently unused but potentially useful for some future test. This causes this loader to only
     * generate a class that the parent could not find.
     *
     * @param parent the parent class loader to check with first
     */
    @SuppressWarnings("unused")
    public GeneratingClassLoader(ClassLoader parent) {
      super(parent);
    }

    /**
     * Specifies no parent to ensure that this loader generates the named class.
     */
    public GeneratingClassLoader() {
      super(null); // no parent!!
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      ClassGen cg = new ClassGen(name, "java.lang.Object", "<generated>",
          Constants.ACC_PUBLIC | Constants.ACC_SUPER, null);
      cg.addEmptyConstructor(Constants.ACC_PUBLIC);
      JavaClass jClazz = cg.getJavaClass();
      byte[] bytes = jClazz.getBytes();
      return defineClass(jClazz.getClassName(), bytes, 0, bytes.length);
    }

    @Override
    protected URL findResource(String name) {
      URL url = null;
      try {
        url = getTempFile().getAbsoluteFile().toURI().toURL();
        System.out.println("GeneratingClassLoader#findResource returning " + url);
      } catch (IOException ignored) {
      }
      return url;
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
      URL url = null;
      try {
        url = getTempFile().getAbsoluteFile().toURI().toURL();
        System.out.println("GeneratingClassLoader#findResources returning " + url);
      } catch (IOException ignored) {
      }
      Vector<URL> urls = new Vector<>();
      urls.add(url);
      return urls.elements();
    }

    protected File getTempFile() {
      return tempFile;
    }
  }

  private File createJarWithClass(String fileName, String className) throws IOException {
    String stringBuilder = "package integration.parent;" + "public class " + className + " {}";
    File jarFile = new File(temporaryFolder.getRoot(), fileName);
    if (jarFile.exists()) {
      jarFile = new File(temporaryFolder.newFolder(), fileName);
    }
    JarBuilder jarBuilder = new JarBuilder();
    jarBuilder.buildJar(jarFile, stringBuilder);
    return jarFile;
  }

  private Deployment createDeploymentFromJar(File jar) {
    Deployment deployment = new Deployment(jar.getName(), "test", Instant.now().toString());
    deployment.setFile(jar);
    return deployment;
  }

  private static class TestResultSender implements ResultSender<Object> {
    private Object result;

    public TestResultSender() {}

    protected Object getResults() {
      return result;
    }

    @Override
    public void lastResult(final Object lastResult) {
      result = lastResult;
    }

    @Override
    public void sendResult(final Object oneResult) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendException(final Throwable t) {
      throw new UnsupportedOperationException();
    }
  }
}
