/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.cxx.StripStep;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class StripLinkable extends AbstractBuildRule {

  @AddToRuleKey
  private final Tool stripTool;

  @AddToRuleKey
  private final SourcePath sourcePathToStrip;

  @AddToRuleKey
  private final String strippedObjectName;

  private final Path resultDir;

  public StripLinkable(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool stripTool,
      SourcePath sourcePathToStrip,
      String strippedObjectName) {
    super(buildRuleParams, resolver);
    this.stripTool = stripTool;
    this.strippedObjectName = strippedObjectName;
    this.sourcePathToStrip = sourcePathToStrip;
    this.resultDir =
        BuildTargets.getGenPath(getProjectFilesystem(), buildRuleParams.getBuildTarget(), "%s");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MkdirStep(getProjectFilesystem(), resultDir));
    steps.add(
        new StripStep(
            getProjectFilesystem().getRootPath(),
            stripTool.getEnvironment(),
            stripTool.getCommandPrefix(getResolver()),
            ImmutableList.of("--strip-unneeded"),
            getResolver().getAbsolutePath(sourcePathToStrip),
            getPathToOutput()));

    buildableContext.recordArtifact(getPathToOutput());

    return steps.build();
  }

  @Override
  public Path getPathToOutput() {
    return resultDir.resolve(strippedObjectName);
  }
}
