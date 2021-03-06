/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.parser.NoSuchBuildTargetException;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

public class CoordinatorAndMinionModeRunnerIntegrationTest {

  private static final BuildId BUILD_ID = ThriftCoordinatorServerIntegrationTest.BUILD_ID;

  @Test(timeout = 10000L)
  public void testDiamondGraphRun()
      throws IOException, NoSuchBuildTargetException, InterruptedException {

    int port = ThriftCoordinatorServerIntegrationTest.findRandomOpenPortOnAllLocalInterfaces();
    CoordinatorModeRunner coordinator = new CoordinatorModeRunner(
        port,
        BuildTargetsQueueTest.createDiamondDependencyQueue(), BUILD_ID);
    MinionModeRunnerIntegrationTest.LocalBuilderImpl localBuilder =
        new MinionModeRunnerIntegrationTest.LocalBuilderImpl();
    MinionModeRunner minion = new MinionModeRunner(
        "localhost",
        port,
        localBuilder,
        BUILD_ID);
    CoordinatorAndMinionModeRunner jointRunner = new CoordinatorAndMinionModeRunner(
        coordinator,
        minion);
    int exitCode = jointRunner.runAndReturnExitCode();
    Assert.assertEquals(0, exitCode);
    Assert.assertEquals(3, localBuilder.getCallArguments().size());
    Assert.assertEquals(
        BuildTargetsQueueTest.TARGET_NAME,
        localBuilder.getCallArguments().get(2).iterator().next());
  }
}
