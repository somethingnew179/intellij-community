// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus.Internal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Internal
public interface FileBasedIndexInfrastructureExtensionUpdatableIndex<K, V, I, D> extends UpdatableIndex<K, V, I, D> {

  @Override
  @NotNull IndexInfrastructureExtensionUpdateComputation mapInputAndPrepareUpdate(int inputId, @Nullable I content);

  UpdatableIndex<K, V, FileContent, D> getBaseIndex();
}
