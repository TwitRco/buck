/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Information for annotation processing.
 *
 * Annotation processing involves a set of processors, their classpath(s), and a few other
 * command-line options for javac.  We want to be able to specify all this various information
 * in a BUCK configuration file and use it when we generate the javac command.  This facilitates
 * threading the information through buck in a more descriptive package rather than passing all
 * the components separately.
 */
public class AnnotationProcessingParams implements RuleKeyAppendable {

  public static final AnnotationProcessingParams EMPTY = new AnnotationProcessingParams(
      /* owner target */ null,
      /* project filesystem */ null,
      ImmutableSet.of(),
      ImmutableSet.of(),
      ImmutableSet.of(),
      ImmutableSortedSet.of(),
      false);

  @Nullable
  private final BuildTarget ownerTarget;
  @Nullable
  private final ProjectFilesystem filesystem;
  private final ImmutableSortedSet<SourcePath> searchPathElements;
  private final ImmutableSortedSet<String> names;
  private final ImmutableSortedSet<String> parameters;
  private final ImmutableSortedSet<SourcePath> inputs;
  private final boolean processOnly;

  private AnnotationProcessingParams(
      @Nullable BuildTarget ownerTarget,
      @Nullable ProjectFilesystem filesystem,
      Set<SourcePath> searchPathElements,
      Set<String> names,
      Set<String> parameters,
      ImmutableSortedSet<SourcePath> inputs,
      boolean processOnly) {
    this.ownerTarget = ownerTarget;
    this.filesystem = filesystem;
    this.searchPathElements = ImmutableSortedSet.copyOf(searchPathElements);
    this.names = ImmutableSortedSet.copyOf(names);
    this.parameters = ImmutableSortedSet.copyOf(parameters);
    this.inputs = inputs;
    this.processOnly = processOnly;

    if (!isEmpty() && ownerTarget != null) {
      Preconditions.checkNotNull(filesystem);
    }
  }

  private Path getGeneratedSrcFolder() {
    Preconditions.checkNotNull(filesystem);
    return BuildTargets.getAnnotationPath(
        filesystem,
        Preconditions.checkNotNull(ownerTarget), "__%s_gen__");
  }

  public boolean isEmpty() {
    return searchPathElements.isEmpty() && names.isEmpty() && parameters.isEmpty();
  }

  public ImmutableSortedSet<SourcePath> getSearchPathElements() {
    return searchPathElements;
  }

  public ImmutableSortedSet<String> getNames() {
    return names;
  }

  public ImmutableSortedSet<String> getParameters() {
    return parameters;
  }

  public ImmutableSortedSet<SourcePath> getInputs() {
    return inputs;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    if (!isEmpty()) {
      // searchPathElements is not needed here since it comes from rules, which is appended below.
      sink.setReflectively("owner", ownerTarget)
          .setReflectively("names", names)
          .setReflectively("parameters", parameters)
          .setReflectively("processOnly", processOnly)
          .setReflectively("inputs", inputs);
    }
  }

  public boolean getProcessOnly() {
    return processOnly;
  }

  @Nullable
  public Path getGeneratedSourceFolderName() {
    if ((ownerTarget != null) && !isEmpty()) {
      return getGeneratedSrcFolder();
    } else {
      return null;
    }
  }

  public static class Builder {
    @Nullable
    private BuildTarget ownerTarget;
    @Nullable
    private ProjectFilesystem filesystem;
    private Set<BuildRule> rules = Sets.newHashSet();
    private Set<String> names = Sets.newHashSet();
    private Set<String> parameters = Sets.newHashSet();
    private boolean processOnly;

    public Builder setOwnerTarget(BuildTarget owner) {
      ownerTarget = owner;
      return this;
    }

    public Builder addProcessorBuildTarget(BuildRule rule) {
      rules.add(rule);
      return this;
    }

    public Builder addAllProcessors(Collection<? extends String> processorNames) {
      names.addAll(processorNames);
      return this;
    }

    public Builder addParameter(String parameter) {
      parameters.add(parameter);
      return this;
    }

    public Builder setProcessOnly(boolean processOnly) {
      this.processOnly = processOnly;
      return this;
    }

    public Builder setProjectFilesystem(ProjectFilesystem filesystem) {
      this.filesystem = filesystem;
      return this;
    }

    public AnnotationProcessingParams build() {
      if (names.isEmpty() && rules.isEmpty() && parameters.isEmpty()) {
        return EMPTY;
      }

      ImmutableSortedSet.Builder<SourcePath> inputs = ImmutableSortedSet.naturalOrder();
      Set<SourcePath> searchPathElements = Sets.newHashSet();

      for (BuildRule rule : this.rules) {
        if (rule.getClass().isAnnotationPresent(BuildsAnnotationProcessor.class)) {
          Path pathToOutput = rule.getPathToOutput();
          if (pathToOutput != null) {
            BuildTargetSourcePath outputSourcePath =
                new BuildTargetSourcePath(rule.getBuildTarget());
            inputs.add(outputSourcePath);
            searchPathElements.add(outputSourcePath);
          }
        } else if (rule instanceof HasClasspathEntries) {
          HasClasspathEntries hasClasspathEntries = (HasClasspathEntries) rule;
          ImmutableSet<JavaLibrary> entries = hasClasspathEntries.getTransitiveClasspathDeps();
          for (JavaLibrary entry : entries) {
            // Libraries may merely re-export other libraries' class paths, instead of having one
            // itself. In such cases do not add the library itself, and just move on.
            if (entry.getPathToOutput() != null) {
              inputs.add(new BuildTargetSourcePath(entry.getBuildTarget()));
            }
          }
          searchPathElements.addAll(hasClasspathEntries.getTransitiveClasspaths());
        } else {
          throw new HumanReadableException(
              "%1$s: Error adding '%2$s' to annotation_processing_deps: " +
              "must refer only to prebuilt jar, java binary, or java library targets.",
              ownerTarget,
              rule.getFullyQualifiedName());
        }
      }

      return new AnnotationProcessingParams(
          ownerTarget,
          filesystem,
          searchPathElements,
          names,
          parameters,
          inputs.build(),
          processOnly);
    }
  }
}
