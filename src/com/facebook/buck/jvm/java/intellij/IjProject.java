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

package com.facebook.buck.jvm.java.intellij;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidLibraryGraphEnhancer;
import com.facebook.buck.android.AndroidPrebuiltAar;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.DummyRDotJava;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.OptionalCompat;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Top-level class for IntelliJ project generation.
 */
public class IjProject {

  private final TargetGraphAndTargets targetGraphAndTargets;
  private final JavaPackageFinder javaPackageFinder;
  private final JavaFileParser javaFileParser;
  private final BuildRuleResolver buildRuleResolver;
  private final SourcePathResolver sourcePathResolver;
  private final ProjectFilesystem projectFilesystem;
  private final IjModuleGraph.AggregationMode aggregationMode;
  private final IjProjectConfig projectConfig;
  private final IntellijConfig intellijConfig;

  public IjProject(
      TargetGraphAndTargets targetGraphAndTargets,
      JavaPackageFinder javaPackageFinder,
      JavaFileParser javaFileParser,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      ProjectFilesystem projectFilesystem,
      IjModuleGraph.AggregationMode aggregationMode,
      BuckConfig buckConfig) {
    this.targetGraphAndTargets = targetGraphAndTargets;
    this.javaPackageFinder = javaPackageFinder;
    this.javaFileParser = javaFileParser;
    this.buildRuleResolver = buildRuleResolver;
    this.sourcePathResolver = sourcePathResolver;
    this.projectFilesystem = projectFilesystem;
    this.aggregationMode = aggregationMode;
    this.projectConfig = IjProjectBuckConfig.create(buckConfig);
    this.intellijConfig = new IntellijConfig(buckConfig);
  }

  /**
   * Write the project to disk.
   *
   * @param runPostGenerationCleaner Whether or not the post-generation cleaner should be run.
   * @return set of {@link BuildTarget}s which should be built in order for the project to index
   *   correctly.
   * @throws IOException
   */
  public ImmutableSet<BuildTarget> write(
      boolean runPostGenerationCleaner,
      boolean removeUnusedLibraries,
      boolean excludeArtifacts)
      throws IOException {
    final ImmutableSet.Builder<BuildTarget> requiredBuildTargets = ImmutableSet.builder();
    IjLibraryFactory libraryFactory = new DefaultIjLibraryFactory(
        new DefaultIjLibraryFactory.IjLibraryFactoryResolver() {
          @Override
          public Path getPath(SourcePath path) {
            Optional<BuildRule> rule = sourcePathResolver.getRule(path);
            if (rule.isPresent()) {
              requiredBuildTargets.add(rule.get().getBuildTarget());
            }
            return projectFilesystem.getRootPath().relativize(
                sourcePathResolver.getAbsolutePath(path));
          }

          @Override
          public Optional<Path> getPathIfJavaLibrary(TargetNode<?, ?> targetNode) {
            BuildRule rule = buildRuleResolver.getRule(targetNode.getBuildTarget());
            if (!(rule instanceof JavaLibrary)) {
              return Optional.empty();
            }
            if (rule instanceof AndroidPrebuiltAar) {
              AndroidPrebuiltAar aarRule = (AndroidPrebuiltAar) rule;
              return Optional.ofNullable(aarRule.getBinaryJar());
            }
            requiredBuildTargets.add(rule.getBuildTarget());
            return Optional.ofNullable(rule.getPathToOutput());
          }
        });
    IjModuleFactory.IjModuleFactoryResolver moduleFactoryResolver =
        new IjModuleFactory.IjModuleFactoryResolver() {

          @Override
          public Optional<Path> getDummyRDotJavaPath(TargetNode<?, ?> targetNode) {
            BuildTarget dummyRDotJavaTarget = AndroidLibraryGraphEnhancer.getDummyRDotJavaTarget(
                targetNode.getBuildTarget());
            Optional<BuildRule> dummyRDotJavaRule =
                buildRuleResolver.getRuleOptional(dummyRDotJavaTarget);
            if (dummyRDotJavaRule.isPresent()) {
              requiredBuildTargets.add(dummyRDotJavaTarget);
              return Optional.of(
                  DummyRDotJava.getRDotJavaBinFolder(dummyRDotJavaTarget, projectFilesystem));
            }
            return Optional.empty();
          }

          @Override
          public Path getAndroidManifestPath(
              TargetNode<AndroidBinaryDescription.Arg, ?> targetNode) {
            return sourcePathResolver.getAbsolutePath(targetNode.getConstructorArg().manifest);
          }

          @Override
          public Optional<Path> getLibraryAndroidManifestPath(
              TargetNode<AndroidLibraryDescription.Arg, ?> targetNode) {
            Optional<SourcePath> manifestPath = targetNode.getConstructorArg().manifest;
            Optional<Path> defaultAndroidManifestPath = intellijConfig.getAndroidManifest()
                .map(Path::toAbsolutePath);
            return manifestPath.map(sourcePathResolver::getAbsolutePath)
                .map(Optional::of)
                .orElse(defaultAndroidManifestPath);
          }

          @Override
          public Optional<Path> getProguardConfigPath(
              TargetNode<AndroidBinaryDescription.Arg, ?> targetNode) {
            return targetNode
                .getConstructorArg()
                .proguardConfig.map(this::getRelativePathAndRecordRule);
          }

          @Override
          public Optional<Path> getAndroidResourcePath(
              TargetNode<AndroidResourceDescription.Arg, ?> targetNode) {
            return targetNode
                .getConstructorArg()
                .res.map(this::getRelativePathAndRecordRule);
          }

          @Override
          public Optional<Path> getAssetsPath(
              TargetNode<AndroidResourceDescription.Arg, ?> targetNode) {
            return targetNode
                .getConstructorArg()
                .assets.map(this::getRelativePathAndRecordRule);
          }

          @Override
          public Optional<Path> getAnnotationOutputPath(
              TargetNode<? extends JvmLibraryArg, ?> targetNode) {
            AnnotationProcessingParams annotationProcessingParams =
                targetNode
                .getConstructorArg()
                .buildAnnotationProcessingParams(
                    targetNode.getBuildTarget(),
                    projectFilesystem,
                    buildRuleResolver
                );
            if (annotationProcessingParams == null || annotationProcessingParams.isEmpty()) {
              return Optional.empty();
            }

            return Optional.ofNullable(annotationProcessingParams.getGeneratedSourceFolderName());
          }

          private Path getRelativePathAndRecordRule(SourcePath sourcePath) {
            requiredBuildTargets.addAll(
                OptionalCompat.asSet(sourcePathResolver.getRule(sourcePath)
                    .map(HasBuildTarget::getBuildTarget)));
            return sourcePathResolver.getRelativePath(sourcePath);
          }
        };
    IjModuleGraph moduleGraph = IjModuleGraph.from(
        projectConfig,
        targetGraphAndTargets.getTargetGraph(),
        libraryFactory,
        new IjModuleFactory(
            projectFilesystem,
            moduleFactoryResolver,
            projectConfig,
            excludeArtifacts),
        aggregationMode);
    JavaPackageFinder parsingJavaPackageFinder = ParsingJavaPackageFinder.preparse(
        javaFileParser,
        projectFilesystem,
        IjProjectTemplateDataPreparer.createPackageLookupPathSet(moduleGraph),
        javaPackageFinder);
    IjProjectWriter writer = new IjProjectWriter(
        new IjProjectTemplateDataPreparer(parsingJavaPackageFinder, moduleGraph, projectFilesystem),
        projectConfig,
        projectFilesystem,
        moduleGraph);
    writer.write(runPostGenerationCleaner, removeUnusedLibraries);
    return requiredBuildTargets.build();
  }
}
