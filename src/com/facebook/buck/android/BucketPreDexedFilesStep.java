/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.dalvik.CanaryFactory;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.java.classes.FileLike;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responsible for bucketing pre-dexed objects into primary and secondary dex files.
 */
public class BucketPreDexedFilesStep extends AbstractExecutionStep {
  
  private final Optional<DexWithClasses> rDotJavaDex;
  private final List<DexWithClasses> dexFilesToMerge;
  private final ClassNameFilter primaryDexFilter;
  private final long linearAllocHardLimit;
  private final DexStore dexStore;
  private final Path secondaryDexJarFilesDir;

  /**
   * These fields are set during the step execution.
   */
  private Set<Path> primaryDexInputs;
  private Map<Path, DexWithClasses> metadataTxtEntries;
  private Multimap<Path, Path> secondaryOutputToInputs;

  /**
   * Directory under the project filesystem where this step may write temporary data. This directory
   * must exist and be empty before this step writes to it.
   */
  private final Path scratchDirectory;

  public BucketPreDexedFilesStep(
      Optional<DexWithClasses> rDotJavaDex,
      List<DexWithClasses> dexFilesToMerge,
      ImmutableSet<String> primaryDexPatterns,
      Path scratchDirectory,
      long linearAllocHardLimit,
      DexStore dexStore,
      Path secondaryDexJarFilesDir) {
    super("bucket_dx");
    this.rDotJavaDex = Preconditions.checkNotNull(rDotJavaDex);
    this.dexFilesToMerge = Preconditions.checkNotNull(dexFilesToMerge);
    this.primaryDexFilter = ClassNameFilter.fromConfiguration(
        Preconditions.checkNotNull(primaryDexPatterns));
    this.scratchDirectory = Preconditions.checkNotNull(scratchDirectory);
    Preconditions.checkState(linearAllocHardLimit > 0);
    this.linearAllocHardLimit = linearAllocHardLimit;
    this.dexStore = Preconditions.checkNotNull(dexStore);
    this.secondaryDexJarFilesDir = Preconditions.checkNotNull(secondaryDexJarFilesDir);
  }

  public Supplier<Set<Path>> getPrimaryDexInputsSupplier() {
    return new Supplier<Set<Path>>() {
      @Override
      public Set<Path> get() {
        return Preconditions.checkNotNull(primaryDexInputs,
            "Trying to read primary dex inputs before BucketPreDexedFilesStep finished.");
      }
    };
  }

  public Map<Path, DexWithClasses> getMetadataTxtEntries() {
    return Preconditions.checkNotNull(metadataTxtEntries,
        "Trying to read secondary dex metadata contents before BucketPreDexedFilesStep finished.");
  }

  public Supplier<Multimap<Path, Path>> getSecondaryOutputToInputsSupplier() {
    return new Supplier<Multimap<Path, Path>>() {
      @Override
      public Multimap<Path, Path> get() {
        return Preconditions.checkNotNull(secondaryOutputToInputs,
            "Trying to read secodary dex inputs before BucketPreDexedFilesStep finished.");
      }
    };
  }

  @Override
  public int execute(ExecutionContext context) {
    List<DexWithClasses> primaryDexContents = Lists.newArrayList();
    List<List<DexWithClasses>> secondaryDexesContents = Lists.newArrayList();

    int primaryDexSize = 0;
    // R.class files should always be in the primary dex.
    if (rDotJavaDex.isPresent()) {
      primaryDexSize += rDotJavaDex.get().getSizeEstimate();
      primaryDexContents.add(rDotJavaDex.get());
    }

    // Sort dex files so that there's a better chance of the same set of pre-dexed files to end up
    // in a given secondary dex file.
    ImmutableList<DexWithClasses> sortedDexFilesToMerge = FluentIterable.from(dexFilesToMerge)
        .toSortedList(DexWithClasses.DEX_WITH_CLASSES_COMPARATOR);

    // Bucket each DexWithClasses into the appropriate dex file.
    List<DexWithClasses> currentSecondaryDexContents = null;
    int currentSecondaryDexSize = 0;
    for (DexWithClasses dexWithClasses : sortedDexFilesToMerge) {
      if (mustBeInPrimaryDex(dexWithClasses)) {
        // Case 1: Entry must be in the primary dex.
        primaryDexSize += dexWithClasses.getSizeEstimate();
        if (primaryDexSize > linearAllocHardLimit) {
          context.postEvent(LogEvent.severe(
              "DexWithClasses %s with cost %s puts the linear alloc estimate for the primary dex " +
                  "at %s, exceeding the maximum of %s.",
              dexWithClasses.getPathToDexFile(),
              dexWithClasses.getSizeEstimate(),
              primaryDexSize,
              linearAllocHardLimit));
          return 1;
        }
        primaryDexContents.add(dexWithClasses);
      } else {
        // Case 2: Entry must go in a secondary dex.

        // If the individual DexWithClasses exceeds the limit for a secondary dex, then we have done
        // something horribly wrong.
        if (dexWithClasses.getSizeEstimate() > linearAllocHardLimit) {
          context.postEvent(LogEvent.severe(
              "DexWithClasses %s with cost %s exceeds the max cost %s for a secondary dex file.",
              dexWithClasses.getPathToDexFile(),
              dexWithClasses.getSizeEstimate(),
              linearAllocHardLimit));
          return 1;
        }

        // If there is no current secondary dex, or dexWithClasses would put the current secondary
        // dex over the cost threshold, then create a new secondary dex and initialize it with a
        // canary.
        if (currentSecondaryDexContents == null ||
            dexWithClasses.getSizeEstimate() + currentSecondaryDexSize > linearAllocHardLimit) {
          DexWithClasses canary;
          try {
            canary = createCanary(secondaryDexesContents.size() + 1, context);
          } catch (IOException e) {
            context.logError(e, "Failed to create canary for secondary dex.");
            return 1;
          }

          currentSecondaryDexContents = Lists.newArrayList(canary);
          currentSecondaryDexSize = canary.getSizeEstimate();
          secondaryDexesContents.add(currentSecondaryDexContents);
        }

        // Now add the contributions from the dexWithClasses entry.
        currentSecondaryDexContents.add(dexWithClasses);
        currentSecondaryDexSize += dexWithClasses.getSizeEstimate();
      }
    }

    primaryDexInputs = FluentIterable.from(primaryDexContents)
        .transform(DexWithClasses.TO_PATH)
        .toSet();

    metadataTxtEntries = Maps.newHashMap();

    String pattern = "secondary-%d" + dexStore.getExtension();
    ImmutableMultimap.Builder<Path, Path> builder = ImmutableMultimap.builder();
    for (int index = 0; index < secondaryDexesContents.size(); index++) {
      String secondaryDexFilename = String.format(pattern, index + 1);
      Path pathToSecondaryDex = secondaryDexJarFilesDir.resolve(secondaryDexFilename);
      metadataTxtEntries.put(pathToSecondaryDex, secondaryDexesContents.get(0).get(0));
      Collection<Path> dexContentPaths = Collections2.transform(
          secondaryDexesContents.get(index), DexWithClasses.TO_PATH);
      builder.putAll(pathToSecondaryDex, dexContentPaths);
    }
    secondaryOutputToInputs = builder.build();

    return 0;
  }


  private boolean mustBeInPrimaryDex(DexWithClasses dexWithClasses) {
    for (String className : dexWithClasses.getClassNames()) {
      if (primaryDexFilter.matches(className)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @see com.facebook.buck.dalvik.CanaryFactory#create(int)
   * @throws IOException
   */
  private DexWithClasses createCanary(int index, ExecutionContext context) throws IOException {
    FileLike fileLike = CanaryFactory.create(index);
    String canaryDirName = "canary_" + String.valueOf(index);
    final Path scratchDirectoryForCanaryClass = scratchDirectory.resolve(canaryDirName);

    // Strip the .class suffix to get the class name for the DexWithClasses object.
    String relativePathToClassFile = fileLike.getRelativePath();
    Preconditions.checkState(relativePathToClassFile.endsWith(".class"));
    final String className = relativePathToClassFile.replaceFirst("\\.class$", "");

    // Write out the .class file.
    Path classFile = scratchDirectoryForCanaryClass.resolve(relativePathToClassFile);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    projectFilesystem.createParentDirs(classFile);
    try (InputStream inputStream = fileLike.getInput()) {
      projectFilesystem.copyToPath(inputStream, classFile);
    }

    return new DexWithClasses() {

      @Override
      public int getSizeEstimate() {
        // Because we do not know the units being used for DEX size estimation and the canary should
        // be very small, assume the size is zero.
        return 0;
      }

      @Override
      public Path getPathToDexFile() {
        return scratchDirectoryForCanaryClass;
      }

      @Override
      public ImmutableSet<String> getClassNames() {
        return ImmutableSet.of(className);
      }
    };
  }
}
