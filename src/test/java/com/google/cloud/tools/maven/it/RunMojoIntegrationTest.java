/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.tools.maven.it;

import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.cloud.tools.maven.AppEngineFactory.SupportedDevServerVersion;
import com.google.cloud.tools.maven.it.util.UrlUtils;
import com.google.cloud.tools.maven.it.verifier.StandardVerifier;
import com.google.cloud.tools.maven.util.SocketUtil;
import com.google.common.base.Strings;
import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

@RunWith(JUnitParamsRunner.class)
public class RunMojoIntegrationTest extends AbstractMojoIntegrationTest {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private int serverPort;
  private int adminPort;

  @Before
  public void initPorts() throws IOException {
    serverPort = SocketUtil.findPort();
    adminPort = SocketUtil.findPort();
  }

  @Test
  @Parameters
  public void testRun(final SupportedDevServerVersion version, String[] profiles,
      String expectedModuleName)
          throws IOException, VerificationException, InterruptedException {

    final String name = "testRun" + version + Arrays.toString(profiles);
    final Verifier verifier = createVerifier(name, version);
    final StringBuilder urlContent = new StringBuilder();

    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          // wait up to 60 seconds for the server to start (retry every second)
          urlContent.append(UrlUtils.getUrlContentWithRetries(getServerUrl(), 60000, 1000));
        } catch (InterruptedException e) {
          e.printStackTrace();
        } finally {
          // stop server
          try {
            Verifier stopVerifier = createVerifier(name + "_stop", version);
            stopVerifier.executeGoal("appengine:stop");
            // wait up to 5 seconds for the server to stop
            assertTrue(UrlUtils.isUrlDownWithRetries(getServerUrl(), 5000, 100));
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
    };
    thread.setDaemon(true);
    thread.start();

    // execute
    for (String profile : profiles) {
      if (!profile.isEmpty()) {
        verifier.addCliOption("-P" + profile);
      }
    }
    verifier.executeGoal("appengine:run");

    thread.join();

    assertThat(urlContent.toString(),
        containsString("Hello from the App Engine Standard project."));
    assertThat(urlContent.toString(), containsString("TEST_VAR=testVariableValue"));
    verifier.verifyErrorFreeLog();
    verifier.verifyTextInLog("Dev App Server is now running");
    verifier.verifyTextInLog("Module instance " + expectedModuleName + " is running");
  }

  /**
   * Provides parameters  for {@link #testRun(SupportedDevServerVersion, String[], String)}.
   */
  @SuppressWarnings("unused")
  private Object[] parametersForTestRun() {
    List<Object[]> result = new ArrayList<>();
    for (SupportedDevServerVersion serverVersion : SupportedDevServerVersion.values()) {
        result.add(new Object[]{ serverVersion, new String[0], "standard-project" });
        result.add(new Object[]{ serverVersion, new String[]{ "base-it-profile", "appyamls" },
            "standard-project-appyamls" });
        result.add(new Object[]{ serverVersion, new String[]{ "base-it-profile", "services" },
          "standard-project-services" });
    }
    return result.toArray(new Object[0]);
  }

  @Test
  public void testRun_failsWhenAppYamlsAndServicesBothSet() throws IOException, VerificationException {
    expectedException.expect(VerificationException.class);
    expectedException.expectMessage("Both <appYamls> and <services> are defined."
        + " <appYamls> is deprecated, use <services> only.");
    expectedException.expectMessage("BUILD FAILURE");
    final Verifier verifier = createVerifier("testRunAppYamlsAndServices", SupportedDevServerVersion.V1);
    verifier.addCliOption("-PappYamlsAndServices");
    verifier.executeGoal("appengine:run");
  }

  private Verifier createVerifier(String name, SupportedDevServerVersion version)
      throws IOException, VerificationException {
    Verifier verifier = new StandardVerifier(name);
    verifier.setSystemProperty("app.devserver.port", Integer.toString(serverPort));
    if (version == SupportedDevServerVersion.V2ALPHA) {
      verifier.setSystemProperty("app.devserver.adminPort", Integer.toString(adminPort));
      verifier.setSystemProperty("app.devserver.version", "2-alpha");
    }
    return verifier;
  }

  private String getServerUrl() {
    return "http://localhost:" + serverPort;
  }
}
