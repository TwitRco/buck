/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.jvm.java.abi.source.api.BootClasspathOracle;
import com.facebook.buck.util.exportedfiles.Nullable;
import com.facebook.buck.util.exportedfiles.Preconditions;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;

/**
 * A {@link TaskListener} that is used during full compilation to validate the guesses made by
 * source-based ABI generation.
 */
public class ValidatingTaskListener
    implements TaskListener, ExpressionTreeResolutionValidator.Listener {
  private final JavacTask javacTask;
  private final List<CompilationUnitTree> compilationUnits = new ArrayList<>();
  private final BootClasspathOracle bootClasspathOracle;
  private final Diagnostic.Kind messageKind;
  @Nullable
  private Trees trees;
  @Nullable
  private FrontendOnlyJavacTask frontendTask;
  @Nullable
  private ExpressionTreeResolutionValidator validator;
  private int enterDepth = 0;

  public ValidatingTaskListener(
      JavaCompiler.CompilationTask task,
      BootClasspathOracle bootClasspathOracle,
      Diagnostic.Kind messageKind) {
    this.javacTask = (JavacTask) task;
    this.bootClasspathOracle = bootClasspathOracle;
    this.messageKind = messageKind;
  }

  private void ensureInitialized() {
    // We can't do this on construction, because the Task might not be fully initialized yet
    if (trees == null) {
      frontendTask = new FrontendOnlyJavacTask(javacTask);
      trees = frontendTask.getTrees();
      validator = new ExpressionTreeResolutionValidator(javacTask, frontendTask);

      // This is just to keep the linter happy; this will be used in the next commit
      Preconditions.checkNotNull(bootClasspathOracle);
    }
  }

  @Override
  public void started(TaskEvent e) {
    final TaskEvent.Kind kind = e.getKind();

    if (kind == TaskEvent.Kind.ENTER) {
      enterDepth += 1;
    }
  }

  @Override
  public void finished(TaskEvent e) {
    ensureInitialized();

    final TaskEvent.Kind kind = e.getKind();
    if (kind == TaskEvent.Kind.PARSE) {
      final CompilationUnitTree compilationUnit = e.getCompilationUnit();
      Preconditions.checkNotNull(frontendTask).enterTree(compilationUnit);
      compilationUnits.add(compilationUnit);
    } else if (kind == TaskEvent.Kind.ENTER) {
      // We wait until we've received all enter events so that the validation time shows up
      // separately from compiler enter time in the traces
      enterDepth -= 1;
      if (enterDepth == 0) {
        compilationUnits.forEach(compilationUnit -> Preconditions.checkNotNull(validator).validate(
            compilationUnit,
            this));
      }
    }
  }

  @Override
  public void onIncorrectTypeResolution(
      CompilationUnitTree file,
      ExpressionTree tree,
      DeclaredType guessedType,
      DeclaredType actualType) {
    TypeElement guessedTypeElement = ((TypeElement) guessedType.asElement());
    TypeElement actualTypeElement = ((TypeElement) actualType.asElement());

    Preconditions.checkNotNull(trees).printMessage(
        messageKind,
        String.format(
            "Source-based ABI generator could not guess the meaning of this name.\n" +
                "   ABI generator guessed: %s\n" +
                "   But the correct answer was: %s\n" +
                "Please qualify the name so that the ABI generator can guess correctly.\n",
            guessedTypeElement.getQualifiedName(),
            actualTypeElement.getQualifiedName()),
        tree,
        file);
  }
}
