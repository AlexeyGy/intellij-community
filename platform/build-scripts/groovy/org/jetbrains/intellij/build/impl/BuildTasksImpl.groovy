// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.intellij.build.impl

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.text.Formats
import com.intellij.openapi.util.text.Strings
import com.intellij.util.lang.CompoundRuntimeException
import com.intellij.util.system.CpuArch
import groovy.io.FileType
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.idea.maven.aether.ArtifactKind
import org.jetbrains.idea.maven.aether.ArtifactRepositoryManager
import org.jetbrains.idea.maven.aether.ProgressConsumer
import org.jetbrains.intellij.build.*
import org.jetbrains.intellij.build.impl.projectStructureMapping.ProjectStructureMapping
import org.jetbrains.jps.model.JpsGlobal
import org.jetbrains.jps.model.artifact.JpsArtifactService
import org.jetbrains.jps.model.jarRepository.JpsRemoteRepositoryService
import org.jetbrains.jps.model.java.*
import org.jetbrains.jps.model.library.JpsLibrary
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.library.JpsRepositoryLibraryType
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.model.module.JpsTypedModuleSourceRoot
import org.jetbrains.jps.model.serialization.JpsModelSerializationDataService
import org.jetbrains.jps.util.JpsPathUtil

import java.lang.reflect.UndeclaredThrowableException
import java.nio.file.*
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinTask
import java.util.function.Function
import java.util.function.Supplier

import static org.jetbrains.intellij.build.impl.TracerManager.spanBuilder

@CompileStatic
final class BuildTasksImpl extends BuildTasks {
  final BuildContext buildContext

  BuildTasksImpl(BuildContext buildContext) {
    this.buildContext = buildContext
  }

  @Override
  void zipProjectSources() {
    Path targetFile = Path.of("$buildContext.paths.artifacts/sources.zip")
    buildContext.executeStep(spanBuilder("build sources zip archive")
                               .setAttribute("path", buildContext.paths.buildOutputDir.relativize(targetFile).toString()),
                             BuildOptions.SOURCES_ARCHIVE_STEP, new Runnable() {
      @Override
      void run() {
        Files.createDirectories(Path.of(buildContext.paths.artifacts))
        Files.deleteIfExists(targetFile)
        doZipProjectSources(targetFile, buildContext)
        buildContext.notifyArtifactBuilt(targetFile)
      }
    })
    logFreeDiskSpace("after building sources archive")
  }

  @CompileStatic(TypeCheckingMode.SKIP)
  private static void doZipProjectSources(Path targetFile, BuildContext context) {
    context.ant.zip(destfile: targetFile.toString()) {
      fileset(dir: context.paths.projectHome) {
        ["java", "groovy", "ipr", "iml", "form", "xml", "properties", "kt"].each {
          include(name: "**/*.$it")
        }
        exclude(name: "**/testData/**")
        exclude(name: "out/**")
      }
    }
  }

  @Override
  @CompileStatic(TypeCheckingMode.SKIP)
  void zipSourcesOfModules(Collection<String> modules, Path targetFile, boolean includeLibraries) {
    buildContext.executeStep(spanBuilder("build module sources archives")
                               .setAttribute("path", buildContext.paths.buildOutputDir.relativize(targetFile).toString())
                               .setAttribute(AttributeKey.stringArrayKey("modules"), List.copyOf(modules)),
                             BuildOptions.SOURCES_ARCHIVE_STEP) {
      Files.createDirectories(targetFile.parent)
      Files.deleteIfExists(targetFile)

      String sourceFilesId = "source.files.only"
      buildContext.ant.patternset(id: sourceFilesId) {
        ["java", "groovy", "kt"].each {
          include(name: "**/*.$it")
        }
      }

      def includedLibraries = new LinkedHashSet<JpsLibrary>()
      if (includeLibraries) {
        List<String> debugMapping = new ArrayList<>()
        for (String moduleName in modules) {
          JpsModule module = buildContext.findRequiredModule(moduleName)
          if (moduleName.startsWith("intellij.platform.") && buildContext.findModule("${moduleName}.impl") != null) {
            Set<JpsLibrary> libraries = JpsJavaExtensionService.dependencies(module).productionOnly().compileOnly().recursivelyExportedOnly().libraries
            includedLibraries.addAll(libraries)
            libraries.collect(debugMapping) {
              it.name + " for " + moduleName
            }
          }
        }
        Span.current().addEvent("collect libraries to include into archive", Attributes.of(AttributeKey.stringArrayKey("mapping"), debugMapping))
        def librariesWithMissingSources = includedLibraries
          .collect { it.asTyped(JpsRepositoryLibraryType.INSTANCE) }
          .findAll { library ->
            library != null && library.getFiles(JpsOrderRootType.SOURCES).any { !it.exists() }
          }
        if (!librariesWithMissingSources.isEmpty()) {
          buildContext.messages.debug("Download missing sources for ${librariesWithMissingSources.size()} libraries")
          def repositories = JpsRemoteRepositoryService.instance.getRemoteRepositoriesConfiguration(buildContext.project)?.repositories?.collect {
            ArtifactRepositoryManager.createRemoteRepository(it.id, it.url)
          } ?: []
          def repositoryManager = new ArtifactRepositoryManager(getLocalArtifactRepositoryRoot(buildContext.projectModel.global), repositories, ProgressConsumer.DEAF)
          librariesWithMissingSources.each { library ->
            def descriptor = library.properties.data
            buildContext.messages.progress("Downloading sources for library '${library.name}' ($descriptor.mavenId)")
            def downloaded = repositoryManager.resolveDependencyAsArtifact(descriptor.groupId, descriptor.artifactId, descriptor.version,
                                                                           EnumSet.of(ArtifactKind.SOURCES),
                                                                           descriptor.includeTransitiveDependencies,
                                                                           descriptor.excludedDependencies)
            buildContext.messages.debug(" $library.name: downloaded ${downloaded.join(", ")}")
          }
        }
      }

      buildContext.ant.zip(destfile: targetFile.toString()) {
        for (String moduleName in modules) {
          buildContext.messages.debug(" include module $moduleName")
          JpsModule module = buildContext.findRequiredModule(moduleName)
          for (JpsTypedModuleSourceRoot<JavaSourceRootProperties> root in module.getSourceRoots(JavaSourceRootType.SOURCE)) {
            buildContext.ant.zipfileset(dir: root.file.absolutePath,
                                        prefix: root.properties.packagePrefix.replace('.', '/'), erroronmissingdir: false) {
              patternset(refid: sourceFilesId)
            }
          }
          for (JpsTypedModuleSourceRoot<JavaResourceRootProperties> root in module.getSourceRoots(JavaResourceRootType.RESOURCE)) {
            buildContext.ant.zipfileset(dir: root.file.absolutePath, prefix: root.properties.relativeOutputPath, erroronmissingdir: false) {
              patternset(refid: sourceFilesId)
            }
          }
        }
        def libraryRootUrls = includedLibraries.collectMany { it.getRootUrls(JpsOrderRootType.SOURCES) }
        buildContext.messages.debug(" include ${libraryRootUrls.size()} roots from ${includedLibraries.size()} libraries:")
        for (url in libraryRootUrls) {
          if (url.startsWith(JpsPathUtil.JAR_URL_PREFIX) && url.endsWith(JpsPathUtil.JAR_SEPARATOR)) {
            def file = JpsPathUtil.urlToFile(url)
            if (file.isFile()) {
              buildContext.messages.debug("  $file.absolutePath, ${Formats.formatFileSize(file.length())}, ${file.length().toString().padLeft(9, "0")} bytes")
              buildContext.ant.zipfileset(src: file.absolutePath) {
                patternset(refid: sourceFilesId)
              }
            }
            else {
              buildContext.messages.debug("  skipped root $file: file doesn't exist")
            }
          }
          else {
            buildContext.messages.debug("  skipped root $url: not a jar file")
          }
        }
      }

      buildContext.notifyArtifactBuilt(targetFile)
    }
  }

  //todo replace by DependencyResolvingBuilder#getLocalArtifactRepositoryRoot call after next update of jps-build-script-dependencies-bootstrap
  private static File getLocalArtifactRepositoryRoot(@NotNull JpsGlobal global) {
    def localRepoPath = JpsModelSerializationDataService.getPathVariablesConfiguration(global)?.getUserVariableValue("MAVEN_REPOSITORY")
    if (localRepoPath != null) {
      return new File(localRepoPath)
    }
    def root = System.getProperty("user.home", null)
    return root != null ? new File(root, ".m2/repository") : new File(".m2/repository")
  }

  /**
   * Build a list with modules that the IDE will provide for plugins.
   */
  private static void buildProvidedModuleList(BuildContext buildContext, Path targetFile, @NotNull Collection<String> modules) {
    buildContext.executeStep(spanBuilder("build provided module list").setAttribute("size", modules.size()),
                             BuildOptions.PROVIDED_MODULES_LIST_STEP, new Runnable() {
      @Override
      void run() {
        buildContext.messages.debug("Building provided module list for the following modules: $modules")
        Files.deleteIfExists(targetFile)
        // start the product in headless mode using com.intellij.ide.plugins.BundledPluginsLister
        BuildHelper.runApplicationStarter(buildContext, buildContext.paths.tempDir.resolve("builtinModules"), modules,
                                          List.of("listBundledPlugins", targetFile.toString()))
        if (Files.notExists(targetFile)) {
          buildContext.messages.error("Failed to build provided modules list: $targetFile doesn't exist")
        }
        buildContext.notifyArtifactWasBuilt(targetFile)
      }
    })
  }

  private Path patchIdeaPropertiesFile() {
    StringBuilder builder = new StringBuilder(Files.readString(buildContext.paths.communityHomeDir.resolve("bin/idea.properties")))

    buildContext.productProperties.additionalIDEPropertiesFilePaths.each {
      builder.append('\n').append(Files.readString(Paths.get(it)))
    }

    //todo[nik] introduce special systemSelectorWithoutVersion instead?
    String settingsDir = buildContext.systemSelector.replaceFirst("\\d+(\\.\\d+)?", "")
    String temp = builder.toString()
    builder.setLength(0)
    builder.append(BuildUtils.replaceAll(temp, ["settings_dir": settingsDir], "@@"))

    if (buildContext.applicationInfo.isEAP) {
      builder.append("""
#-----------------------------------------------------------------------
# Change to 'disabled' if you don't want to receive instant visual notifications
# about fatal errors that happen to an IDE or plugins installed.
#-----------------------------------------------------------------------
idea.fatal.error.notification=enabled
""")
    }
    else {
      builder.append("""
#-----------------------------------------------------------------------
# Change to 'enabled' if you want to receive instant visual notifications
# about fatal errors that happen to an IDE or plugins installed.
#-----------------------------------------------------------------------
idea.fatal.error.notification=disabled
""")
    }

    Path propertiesFile = buildContext.paths.tempDir.resolve("idea.properties")
    Files.writeString(propertiesFile, builder)
    return propertiesFile
  }

  private static void layoutShared(BuildContext buildContext) {
    buildContext.messages.block("copy files shared among all distributions", new Supplier<Void>() {
      @Override
      Void get() {
        BuildHelper buildHelper = BuildHelper.getInstance(buildContext)
        buildHelper.copyDirWithFileFilter(buildContext.paths.communityHomeDir.resolve("bin"),
                                          buildContext.paths.distAllDir.resolve("bin")) {
          !it.endsWith("idea.properties") && !it.endsWith("log.xml")
        }

        copyLogXml(buildContext)

        Path licenseOutDir = buildContext.paths.distAllDir.resolve("license")
        buildHelper.copyDir(buildContext.paths.communityHomeDir.resolve("license"), licenseOutDir)
        for (String additionalDirWithLicenses in buildContext.productProperties.additionalDirectoriesWithLicenses) {
          buildHelper.copyDir(Path.of(additionalDirWithLicenses), licenseOutDir)
        }

        if (buildContext.applicationInfo.svgRelativePath != null) {
          Path from = findBrandingResource(buildContext.applicationInfo.svgRelativePath, buildContext)
          Path to = buildContext.paths.distAllDir.resolve("bin/${buildContext.productProperties.baseFileName}.svg")
          Files.createDirectories(to.parent)
          Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        }

        buildContext.productProperties.copyAdditionalFiles(buildContext, buildContext.paths.distAll)
        return null
      }
    })
  }

  static void generateBuildTxt(@NotNull BuildContext buildContext, @NotNull Path targetDirectory) {
    Files.writeString(targetDirectory.resolve("build.txt"), buildContext.fullBuildNumber)
  }

  @NotNull
  private static Path findBrandingResource(@NotNull String relativePath, BuildContext buildContext) {
    String normalizedRelativePath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath
    Path inModule = buildContext.findFileInModuleSources(buildContext.productProperties.applicationInfoModule, normalizedRelativePath)
    if (inModule != null) {
      return inModule
    }

    for (String brandingResourceDir : buildContext.productProperties.brandingResourcePaths) {
      Path file = Paths.get(brandingResourceDir, normalizedRelativePath)
      if (Files.exists(file)) {
        return file
      }
    }

    buildContext.messages.error(
      "Cannot find '$normalizedRelativePath' neither in sources of '$buildContext.productProperties.applicationInfoModule'" +
      " nor in $buildContext.productProperties.brandingResourcePaths")
    return null
  }

  private static void copyLogXml(BuildContext buildContext) {
    Path src = buildContext.paths.communityHomeDir.resolve("bin/log.xml")
    Path dst = buildContext.paths.distAllDir.resolve("bin/log.xml")
    Files.createDirectories(dst.parent)

    String text = Files.readAllLines(src)
      .findAll { String line -> !line.contains('appender-ref ref="CONSOLE-WARN"') }
      .join("\n")
    Files.writeString(dst, text)
  }

  @NotNull
  private static BuildTaskRunnable<Path> createDistributionForOsTask(@NotNull OsFamily os,
                                                                     @NotNull Function<BuildContext, OsSpecificDistributionBuilder> factory) {
    return BuildTaskRunnable.<Path> taskWithResult(os.osId, new Function<BuildContext, Path>() {
      @Override
      Path apply(BuildContext context) {
        if (!context.shouldBuildDistributionForOS(os.osId)) {
          return null
        }

        OsSpecificDistributionBuilder builder = factory.apply(context)
        if (builder == null) {
          return null
        }

        return context.messages.block("build ${os.osName} distribution", new Supplier<Path>() {
          @Override
          Path get() {
            Path osSpecificDistDirectory = DistributionJARsBuilder.getOsSpecificDistDirectory(builder.targetOs, context)
            builder.buildArtifacts(osSpecificDistDirectory)
            return osSpecificDistDirectory
          }
        })
      }
    })
  }

  @Override
  void compileModulesFromProduct() {
    checkProductProperties()
    compileModulesForDistribution()
  }

  private DistributionJARsBuilder compileModulesForDistribution() {
    ProductModulesLayout productLayout = buildContext.productProperties.productLayout
    Collection<String> moduleNames = DistributionJARsBuilder.getModulesToCompile(buildContext)
    MavenArtifactsProperties mavenArtifacts = buildContext.productProperties.mavenArtifacts

    Set<String> toCompile = new LinkedHashSet<>()
    toCompile.addAll(moduleNames)
    toCompile.addAll(buildContext.proprietaryBuildTools.scrambleTool?.additionalModulesToCompile ?: Collections.<String>emptyList())
    toCompile.addAll(productLayout.mainModules)
    toCompile.addAll(mavenArtifacts.additionalModules)
    toCompile.addAll(mavenArtifacts.proprietaryModules)
    toCompile.addAll(buildContext.productProperties.modulesToCompileTests)
    compileModules(toCompile)

    def pluginsToPublish = new LinkedHashSet<>(
      DistributionJARsBuilder.getPluginsByModules(buildContext, buildContext.productProperties.productLayout.pluginModulesToPublish))

    if (buildContext.shouldBuildDistributions()) {
      Path providedModulesFile = Path.of(buildContext.paths.artifacts, "${buildContext.applicationInfo.productCode}-builtinModules.json")
      buildProvidedModuleList(buildContext, providedModulesFile, moduleNames)
      if (buildContext.productProperties.productLayout.buildAllCompatiblePlugins) {
        if (!buildContext.options.buildStepsToSkip.contains(BuildOptions.PROVIDED_MODULES_LIST_STEP)) {
          PluginsCollector collector = new PluginsCollector(buildContext)
          pluginsToPublish.addAll(collector.collectCompatiblePluginsToPublish(providedModulesFile.toString()))
        }
        else {
          buildContext.messages.info("Skipping collecting compatible plugins because PROVIDED_MODULES_LIST_STEP was skipped")
        }
      }
    }
    return compilePlatformAndPluginModules(pluginsToPublish)
  }

  private DistributionJARsBuilder compilePlatformAndPluginModules(@NotNull Set<PluginLayout> pluginsToPublish) {
    DistributionJARsBuilder distBuilder = new DistributionJARsBuilder(buildContext, pluginsToPublish)
    compileModules(distBuilder.getModulesForPluginsToPublish())

    // we need this to ensure that all libraries which may be used in the distribution are resolved,
    // even if product modules don't depend on them (e.g. JUnit5)
    CompilationTasks compilationTasks = CompilationTasks.create(buildContext)
    compilationTasks.resolveProjectDependencies()
    compilationTasks.buildProjectArtifacts(distBuilder.getIncludedProjectArtifacts(buildContext))
    return distBuilder
  }

  @Override
  void buildDistributions() {
    Span span = spanBuilder("build distributions").startSpan()
    Scope spanScope = span.makeCurrent()
    try {
      doBuildDistributions(buildContext)
    }
    catch (Throwable e) {
      span.recordException(e)
      span.setStatus(StatusCode.ERROR, e.message)

      TracerManager.finish()
      throw e
    }
    finally {
      span.end()
      spanScope.close()
    }
  }

  private void doBuildDistributions(BuildContext context) {
    checkProductProperties()
    copyDependenciesFile()
    setupBundledMaven()

    logFreeDiskSpace("before compilation")
    DistributionJARsBuilder distributionJARsBuilder = compileModulesForDistribution()
    logFreeDiskSpace("after compilation")

    ForkJoinTask<?> setupJetBrainsRuntimeTask = context.shouldBuildDistributions() ? createSetupJbreTask()?.fork() : null

    MavenArtifactsProperties mavenArtifacts = context.productProperties.mavenArtifacts
    if (mavenArtifacts.forIdeModules || !mavenArtifacts.additionalModules.isEmpty() || !mavenArtifacts.proprietaryModules.isEmpty()) {
      context.executeStep("generate maven artifacts", BuildOptions.MAVEN_ARTIFACTS_STEP, new Runnable() {
        @Override
        void run() {
          MavenArtifactsBuilder mavenArtifactsBuilder = new MavenArtifactsBuilder(context)
          List<String> moduleNames = new ArrayList<>()
          if (mavenArtifacts.forIdeModules) {
            Set<String> bundledPlugins = Set.copyOf(context.productProperties.productLayout.bundledPluginModules)
            moduleNames.addAll(distributionJARsBuilder.platformModules)
            moduleNames.addAll(context.productProperties.productLayout.getIncludedPluginModules(bundledPlugins))
          }
          moduleNames.addAll(mavenArtifacts.additionalModules)
          if (!moduleNames.isEmpty()) {
            mavenArtifactsBuilder.generateMavenArtifacts(moduleNames, 'maven-artifacts')
          }
          if (!mavenArtifacts.proprietaryModules.isEmpty()) {
            mavenArtifactsBuilder.generateMavenArtifacts(mavenArtifacts.proprietaryModules, 'proprietary-maven-artifacts')
          }
        }
      })
    }

    context.messages.block("build platform and plugin JARs", new Supplier<Void>() {
      @Override
      Void get() {
        if (context.shouldBuildDistributions()) {
          ProjectStructureMapping projectStructureMapping = distributionJARsBuilder.buildJARs(context)
          DistributionJARsBuilder.buildAdditionalArtifacts(context, projectStructureMapping)
        }
        else {
          Span.current().addEvent("skip building product distributions because " +
                                  "\"intellij.build.target.os\" property is set to \"$BuildOptions.OS_NONE\"")
          DistributionJARsBuilder.buildSearchableOptions(context, distributionJARsBuilder.getModulesForPluginsToPublish())
          distributionJARsBuilder.createBuildNonBundledPluginsTask(true, context).fork().join()
        }
        return null
      }
    })

    if (context.shouldBuildDistributions()) {
      layoutShared(context)
      setupJetBrainsRuntimeTask?.join()

      Path propertiesFile = patchIdeaPropertiesFile()
      List<BuildTaskRunnable<Path>> tasks = [
        createDistributionForOsTask(OsFamily.WINDOWS, new Function<BuildContext, OsSpecificDistributionBuilder>() {
          @Override
          OsSpecificDistributionBuilder apply(BuildContext customContext) {
            return customContext.windowsDistributionCustomizer?.with {
              new WindowsDistributionBuilder(customContext, it, propertiesFile, customContext.applicationInfo.getAppInfoXml())
            }
          }
        }),
        createDistributionForOsTask(OsFamily.LINUX, new Function<BuildContext, OsSpecificDistributionBuilder>() {
          @Override
          OsSpecificDistributionBuilder apply(BuildContext customContext) {
            return customContext.linuxDistributionCustomizer?.with {
              new LinuxDistributionBuilder(customContext, it, propertiesFile)
            }
          }
        }),
        createDistributionForOsTask(OsFamily.MACOS, new Function<BuildContext, OsSpecificDistributionBuilder>() {
          @Override
          OsSpecificDistributionBuilder apply(BuildContext customContext) {
            return customContext.macDistributionCustomizer?.with {
              new MacDistributionBuilder(customContext, it, propertiesFile)
            }
          }
        })
      ].findAll { it != null }

      List<Path> paths = runInParallel(tasks, context).findAll { it != null }
      if (Boolean.getBoolean("intellij.build.toolbox.litegen")) {
        if (context.buildNumber == null) {
          context.messages.warning("Toolbox LiteGen is not executed - it does not support SNAPSHOT build numbers")
        }
        else if (context.options.targetOS != BuildOptions.OS_ALL) {
          context.messages.
            warning("Toolbox LiteGen is not executed - it doesn't support installers are being built only for specific OS")
        }
        else {
          context.executeStep("build toolbox lite-gen links", BuildOptions.TOOLBOX_LITE_GEN_STEP, new Runnable() {
            @Override
            void run() {
              String toolboxLiteGenVersion = System.getProperty("intellij.build.toolbox.litegen.version")
              if (toolboxLiteGenVersion == null) {
                context.messages.error("Toolbox Lite-Gen version is not specified!")
              }
              else {
                String[] liteGenArgs = [
                  'runToolboxLiteGen',
                  "-Pintellij.build.toolbox.litegen.version=${toolboxLiteGenVersion}",
                  //NOTE[jo]: right now we assume all installer files are created under the same path
                  "-Pintellij.build.artifacts=${context.paths.artifacts}",
                  "-Pintellij.build.productCode=${context.applicationInfo.productCode}",
                  "-Pintellij.build.isEAP=${context.applicationInfo.isEAP}",
                  "-Pintellij.build.output=${context.paths.buildOutputRoot}/toolbox-lite-gen",
                ]

                context.gradle.runWithModularRuntime('Run Toolbox LiteGen', liteGenArgs)
              }
            }
          })
        }
      }

      if (context.productProperties.buildCrossPlatformDistribution) {
        if (paths.size() == 3) {
          context.executeStep("build cross-platform distribution", BuildOptions.CROSS_PLATFORM_DISTRIBUTION_STEP, new Runnable() {
            @Override
            void run() {
              Path monsterZip = new CrossPlatformDistributionBuilder().buildCrossPlatformZip(paths[0], paths[1], paths[2], context)
              Map<String, String> checkerConfig = context.productProperties.versionCheckerConfig
              if (checkerConfig != null) {
                new ClassVersionChecker(checkerConfig).checkVersions(context, monsterZip)
              }
            }
          })
        }
        else {
          Span.current().addEvent("skip building cross-platform distribution because some OS-specific distributions were skipped")
        }
      }
    }
    logFreeDiskSpace("after building distributions")
  }

  @Override
  void buildNonBundledPlugins(List<String> mainPluginModules) {
    checkProductProperties()
    checkPluginModules(mainPluginModules, "mainPluginModules", buildContext.productProperties.productLayout.allNonTrivialPlugins)
    copyDependenciesFile()
    Set<PluginLayout> pluginsToPublish = DistributionJARsBuilder.getPluginsByModules(buildContext, mainPluginModules)
    DistributionJARsBuilder distributionJARsBuilder = compilePlatformAndPluginModules(pluginsToPublish)
    DistributionJARsBuilder.buildSearchableOptions(buildContext, distributionJARsBuilder.getModulesForPluginsToPublish())
    distributionJARsBuilder.createBuildNonBundledPluginsTask(true, buildContext)?.fork()?.join()
  }

  @Override
  void generateProjectStructureMapping(File targetFile) {
    new DistributionJARsBuilder(buildContext).generateProjectStructureMapping(targetFile.toPath(), buildContext)
  }

  @Nullable
  private ForkJoinTask<?> createSetupJbreTask(String targetArch = null) {
    String message = "downloading JetBrains Runtime"
    return BuildHelper.getInstance(buildContext).createSkippableTask(
      spanBuilder(message).setAttribute("targetArch", targetArch),
      BuildOptions.RUNTIME_DOWNLOADING_STEP,
      buildContext,
      new Runnable() {
        @Override
        void run() {
          logFreeDiskSpace("before downloading runtime")
          String[] args = [
            "setupJbre", "-Dintellij.build.target.os=$buildContext.options.targetOS",
            "-Dintellij.build.bundled.jre.version=$buildContext.options.bundledJreVersion"
          ]
          if (targetArch != null) {
            args += "-Dintellij.build.target.arch=" + targetArch
          }
          String prefix = System.getProperty("intellij.build.bundled.jre.prefix")
          if (prefix != null) {
            args += "-Dintellij.build.bundled.jre.prefix=" + prefix
          }
          if (buildContext.options.bundledJreBuild != null) {
            args += "-Dintellij.build.bundled.jre.build=" + buildContext.options.bundledJreBuild
          }
          buildContext.gradle.run(message, args)
          logFreeDiskSpace("after downloading runtime")
        }
      }
    )
  }

  private void setupBundledMaven() {
    buildContext.executeStep("set-up bundled maven", BuildOptions.SETUP_BUNDLED_MAVE, new Runnable() {
      @Override
      void run() {
        logFreeDiskSpace("before downloading Maven")
        buildContext.gradle.runOneTask("setupBundledMaven")
        logFreeDiskSpace("after downloading Maven")
      }
    })
  }

  @CompileStatic(TypeCheckingMode.SKIP)
  static @NotNull Path unpackPty4jNative(BuildContext buildContext, @NotNull Path distDir, String pty4jOsSubpackageName) {
    Path pty4jNativeDir = distDir.resolve("lib/pty4j-native")
    def nativePkg = "resources/com/pty4j/native"
    def includedNativePkg = Strings.trimEnd(nativePkg + "/" + Strings.notNullize(pty4jOsSubpackageName), '/')
    buildContext.project.libraryCollection.findLibrary("pty4j").getFiles(JpsOrderRootType.COMPILED).each {
      buildContext.ant.unzip(src: it, dest: pty4jNativeDir.toString()) {
        buildContext.ant.patternset() {
          include(name: "$includedNativePkg/**")
        }
        buildContext.ant.mapper(type: "glob", from: "$nativePkg/*", to: "*")
      }
    }
    List<File> files = new ArrayList<>()
    pty4jNativeDir.toFile().eachFileRecurse(FileType.FILES) {
      files.add(it)
    }
    if (files.empty) {
      buildContext.messages.error("Cannot layout pty4j native: no files extracted")
    }
    return pty4jNativeDir
  }

  //dbus-java is used only on linux for KWallet integration.
  //It relies on native libraries, causing notarization issues on mac.
  //So it is excluded from all distributions and manually re-included on linux.
  static List<String> addDbusJava(CompilationContext context, @NotNull Path libDir) {
    JpsLibrary library = context.findModule("intellij.platform.credentialStore").libraryCollection.findLibrary("dbus-java")
    List<String> extraJars = new ArrayList<>()
    Files.createDirectories(libDir)
    for (File file : library.getFiles(JpsOrderRootType.COMPILED)) {
      BuildHelper.copyFileToDir(file.toPath(), libDir)
      extraJars.add(file.name)
    }
    return extraJars
  }

  static void appendLibsToClasspathJar(BuildContext buildContext, @NotNull Path distDir, @NotNull List<String> extraJars) {
    Path srcClassPathTxt = buildContext.paths.distAllDir.resolve("lib/classpath.txt")
    Path destLibDir = distDir.resolve("lib")
    //no file in fleet
    if (Files.exists(srcClassPathTxt)) {
      Path classPathTxt = destLibDir.resolve("classpath.txt")
      Files.copy(srcClassPathTxt, classPathTxt, StandardCopyOption.REPLACE_EXISTING)
      Files.writeString(classPathTxt, "\n" + String.join("\n", extraJars), StandardOpenOption.APPEND)
      buildContext.messages.warning("added ${extraJars.size()} extra jars to classpath.txt")
    }
    else {
      buildContext.messages.warning("no classpath.txt - no patching")
    }
  }

  private void logFreeDiskSpace(String phase) {
    CompilationContextImpl.logFreeDiskSpace(buildContext.messages, buildContext.paths.buildOutputRoot, phase)
  }

  private void copyDependenciesFile() {
    Path outputFile = Path.of(buildContext.paths.artifacts, "dependencies.txt")
    Files.createDirectories(outputFile.parent)
    Files.copy(buildContext.dependenciesProperties.file, outputFile, StandardCopyOption.REPLACE_EXISTING)
    buildContext.notifyArtifactWasBuilt(outputFile)
  }

  private void checkProductProperties() {
    checkProductLayout()
    def properties = buildContext.productProperties
    checkPaths(properties.brandingResourcePaths, "productProperties.brandingResourcePaths")
    checkPaths(properties.additionalIDEPropertiesFilePaths, "productProperties.additionalIDEPropertiesFilePaths")
    checkPaths(properties.additionalDirectoriesWithLicenses, "productProperties.additionalDirectoriesWithLicenses")

    checkModules(properties.additionalModulesToCompile, "productProperties.additionalModulesToCompile")
    checkModules(properties.modulesToCompileTests, "productProperties.modulesToCompileTests")
    checkModules(properties.additionalModulesRequiredForScrambling, "productProperties.additionalModulesRequiredForScrambling")

    def winCustomizer = buildContext.windowsDistributionCustomizer
    checkPaths([winCustomizer?.icoPath], "productProperties.windowsCustomizer.icoPath")
    checkPaths([winCustomizer?.icoPathForEAP], "productProperties.windowsCustomizer.icoPathForEAP")
    checkPaths([winCustomizer?.installerImagesPath], "productProperties.windowsCustomizer.installerImagesPath")

    checkPaths([buildContext.linuxDistributionCustomizer?.iconPngPath], "productProperties.linuxCustomizer.iconPngPath")
    checkPaths([buildContext.linuxDistributionCustomizer?.iconPngPathForEAP], "productProperties.linuxCustomizer.iconPngPathForEAP")

    def macCustomizer = buildContext.macDistributionCustomizer
    if (macCustomizer != null) {
      checkMandatoryField(macCustomizer.bundleIdentifier, "productProperties.macCustomizer.bundleIdentifier")
      checkMandatoryPath(macCustomizer.icnsPath, "productProperties.macCustomizer.icnsPath")
      checkPaths([macCustomizer.icnsPathForEAP], "productProperties.macCustomizer.icnsPathForEAP")
      checkMandatoryPath(macCustomizer.dmgImagePath, "productProperties.macCustomizer.dmgImagePath")
      checkPaths([macCustomizer.dmgImagePathForEAP], "productProperties.macCustomizer.dmgImagePathForEAP")
    }

    checkModules(properties.mavenArtifacts.additionalModules, "productProperties.mavenArtifacts.additionalModules")
    if (buildContext.productProperties.scrambleMainJar) {
      checkModules(buildContext.proprietaryBuildTools.scrambleTool?.namesOfModulesRequiredToBeScrambled,
                   "ProprietaryBuildTools.scrambleTool.namesOfModulesRequiredToBeScrambled")
    }
  }

  private void checkProductLayout() {
    ProductModulesLayout layout = buildContext.productProperties.productLayout
    if (layout.mainJarName == null) {
      buildContext.messages.error("productProperties.productLayout.mainJarName is not specified")
    }

    List<PluginLayout> nonTrivialPlugins = layout.allNonTrivialPlugins
    checkPluginDuplicates(nonTrivialPlugins)

    checkPluginModules(layout.bundledPluginModules, "productProperties.productLayout.bundledPluginModules", nonTrivialPlugins)
    checkPluginModules(layout.pluginModulesToPublish, "productProperties.productLayout.pluginModulesToPublish", nonTrivialPlugins)
    checkPluginModules(layout.compatiblePluginsToIgnore, "productProperties.productLayout.compatiblePluginsToIgnore", nonTrivialPlugins)

    if (!layout.buildAllCompatiblePlugins && !layout.compatiblePluginsToIgnore.isEmpty()) {
      buildContext.messages.warning("layout.buildAllCompatiblePlugins option isn't enabled. Value of " +
                                    "layout.compatiblePluginsToIgnore property will be ignored ($layout.compatiblePluginsToIgnore)")
    }
    if (layout.buildAllCompatiblePlugins && !layout.compatiblePluginsToIgnore.isEmpty()) {
      checkPluginModules(layout.compatiblePluginsToIgnore, "productProperties.productLayout.compatiblePluginsToIgnore", nonTrivialPlugins)
    }

    if (!buildContext.shouldBuildDistributions() && layout.buildAllCompatiblePlugins) {
      buildContext.messages.warning("Distribution is not going to build. Hence all compatible plugins won't be built despite " +
                                    "layout.buildAllCompatiblePlugins option is enabled. layout.pluginModulesToPublish will be used ($layout.pluginModulesToPublish)")
    }
    if (layout.prepareCustomPluginRepositoryForPublishedPlugins && layout.pluginModulesToPublish.isEmpty() &&
        !layout.buildAllCompatiblePlugins) {
      buildContext.messages.error("productProperties.productLayout.prepareCustomPluginRepositoryForPublishedPlugins option is enabled" +
                                  " but no pluginModulesToPublish are specified")
    }

    checkModules(layout.productApiModules, "productProperties.productLayout.productApiModules")
    checkModules(layout.productImplementationModules, "productProperties.productLayout.productImplementationModules")
    checkModules(layout.additionalPlatformJars.values(), "productProperties.productLayout.additionalPlatformJars")
    checkModules(layout.moduleExcludes.keySet(), "productProperties.productLayout.moduleExcludes")
    checkModules(layout.mainModules, "productProperties.productLayout.mainModules")
    checkProjectLibraries(layout.projectLibrariesToUnpackIntoMainJar, "productProperties.productLayout.projectLibrariesToUnpackIntoMainJar")
    nonTrivialPlugins.each { plugin ->
      checkBaseLayout(plugin, "'$plugin.mainModule' plugin")
    }
  }

  private void checkBaseLayout(BaseLayout layout, String description) {
    checkModules(layout.includedModuleNames, "moduleJars in $description")
    checkArtifacts(layout.includedArtifacts.keySet(), "includedArtifacts in $description")
    checkModules(layout.resourcePaths.collect { it.moduleName }, "resourcePaths in $description")
    checkModules(layout.moduleExcludes.keySet(), "moduleExcludes in $description")
    checkProjectLibraries(layout.includedProjectLibraries.collect { it.libraryName }, "includedProjectLibraries in $description")
    for (data in layout.includedModuleLibraries) {
      checkModules([data.moduleName], "includedModuleLibraries in $description")
      if (buildContext.findRequiredModule(data.moduleName).libraryCollection.libraries.find { LayoutBuilder.getLibraryName(it) == data.libraryName } == null) {
        buildContext.messages.error("Cannot find library '$data.libraryName' in '$data.moduleName' (used in $description)")
      }
    }
    checkModules(layout.excludedModuleLibraries.keySet(), "excludedModuleLibraries in $description")
    for (entry in layout.excludedModuleLibraries.entrySet()) {
      def libraries = buildContext.findRequiredModule(entry.key).libraryCollection.libraries
      for (libraryName in entry.value) {
      if (libraries.find { LayoutBuilder.getLibraryName(it) == libraryName } == null) {
          buildContext.messages.error("Cannot find library '$libraryName' in '$entry.key' (used in 'excludedModuleLibraries' in $description)")
        }
      }
    }
    checkProjectLibraries(layout.projectLibrariesToUnpack.values(), "projectLibrariesToUnpack in $description")
    checkModules(layout.modulesWithExcludedModuleLibraries, "modulesWithExcludedModuleLibraries in $description")
  }

  private void checkPluginDuplicates(List<PluginLayout> nonTrivialPlugins) {
    def pluginsGroupedByMainModule = nonTrivialPlugins.groupBy { it.mainModule }.values()
    for (List<PluginLayout> duplicatedPlugins : pluginsGroupedByMainModule) {
      if (duplicatedPlugins.size() > 1) {
        buildContext.messages.warning("Duplicated plugin description in productLayout.allNonTrivialPlugins: ${duplicatedPlugins[0].mainModule}")
      }
    }
  }

  private void checkModules(Collection<String> modules, String fieldName) {
    if (modules != null) {
      def unknownModules = modules.findAll {buildContext.findModule(it) == null}
      if (!unknownModules.empty) {
        buildContext.messages.error("The following modules from $fieldName aren't found in the project: $unknownModules")
      }
    }
  }

  private void checkProjectLibraries(Collection<String> names, String fieldName) {
    def unknownLibraries = names.findAll {buildContext.project.libraryCollection.findLibrary(it) == null}
    if (!unknownLibraries.empty) {
      buildContext.messages.error("The following libraries from $fieldName aren't found in the project: $unknownLibraries")
    }
  }

  private void checkArtifacts(Collection<String> names, String fieldName) {
    def unknownArtifacts = names - JpsArtifactService.instance.getArtifacts(buildContext.project).collect {it.name}
    if (!unknownArtifacts.empty) {
      buildContext.messages.error("The following artifacts from $fieldName aren't found in the project: $unknownArtifacts")
    }
  }

  private void checkPluginModules(List<String> pluginModules, String fieldName, List<PluginLayout> pluginLayoutList) {
    if (pluginModules == null) {
      return
    }
    checkModules(pluginModules, fieldName)

    def unspecifiedLayoutPluginModules = pluginModules.findAll { mainModuleName ->
      pluginLayoutList.find { it.mainModule == mainModuleName } == null
    }
    if (!unspecifiedLayoutPluginModules.empty) {
      buildContext.messages.info("No plugin layout specified in productProperties.productLayout.allNonTrivialPlugins for following plugin main modules. " +
                                    "Assuming simple layout. Modules list: $unspecifiedLayoutPluginModules")
    }

    List<String> unknownBundledPluginModules = pluginModules.findAll { buildContext.findFileInModuleSources(it, "META-INF/plugin.xml") == null }
    if (!unknownBundledPluginModules.empty) {
      buildContext.messages.error(
        "The following modules from $fieldName don't contain META-INF/plugin.xml file and aren't specified as optional plugin modules " +
        "in productProperties.productLayout.allNonTrivialPlugins: $unknownBundledPluginModules. "
      )
    }
  }

  private void checkPaths(@NotNull Collection<String> paths, String fieldName) {
    Collection<String> nonExistingFiles = paths.findAll { it != null && !Files.exists(Paths.get(it)) }
    if (!nonExistingFiles.empty) {
      buildContext.messages.error("$fieldName contains non-existing path${nonExistingFiles.size() > 1 ? "s" : ""}: ${String.join(",", nonExistingFiles)}")
    }
  }

  private void checkMandatoryField(String value, String fieldName) {
    if (value == null) {
      buildContext.messages.error("Mandatory property '$fieldName' is not specified")
    }
  }

  private void checkMandatoryPath(String path, String fieldName) {
    checkMandatoryField(path, fieldName)
    checkPaths([path], fieldName)
  }

  @Override
  void compileProjectAndTests(List<String> includingTestsInModules = []) {
    compileModules(null, includingTestsInModules)
  }

  @Override
  void compileModules(Collection<String> moduleNames, List<String> includingTestsInModules = []) {
    CompilationTasks.create(buildContext).compileModules(moduleNames, includingTestsInModules)
  }

  static <V> List<V> runInParallel(List<BuildTaskRunnable<V>> tasks, BuildContext buildContext) {
    if (tasks.empty) {
      return Collections.emptyList()
    }

    if (!buildContext.options.runBuildStepsInParallel) {
      return tasks.collect {
        it.execute(buildContext)
      }
    }

    Span span = spanBuilder("run tasks in parallel")
      .setAttribute(AttributeKey.stringArrayKey("tasks"), tasks.collect { it.stepId })
      .setAttribute("taskCount", tasks.size())
      .startSpan()
    Context traceContext = Context.current().with(span)
    try {
      List<ForkJoinTask<V>> futures = new ArrayList<ForkJoinTask<V>>(tasks.size())
      for (BuildTaskRunnable<V> task : tasks) {
        ForkJoinTask<V> forkJoinTask = createTaskWrapper(task, buildContext.forkForParallelTask(task.stepId), traceContext)
        if (forkJoinTask != null) {
          futures.add(forkJoinTask.fork())
        }
      }

      List<Throwable> errors = new ArrayList<>()

      List<V> results = new ArrayList<>(futures.size())
      // inversed order of join - better for FJP (https://shipilev.net/talks/jeeconf-May2012-forkjoin.pdf, slide 32)
      for (int i = futures.size() - 1; i >= 0; i--) {
        ForkJoinTask<V> task = futures.get(i)
        try {
          V result = task.join()
          if (result != null) {
            results.add(result)
          }
        }
        catch (Throwable e) {
          errors.add(e instanceof UndeclaredThrowableException ? e.cause : e)
        }
      }
      results.sort(Collections.reverseOrder())

      if (!errors.isEmpty()) {
        Span.current().setStatus(StatusCode.ERROR)
        if (errors.size() == 1) {
          buildContext.messages.error(errors.get(0).message, errors.get(0))
        }
        else {
          buildContext.messages.error("Some tasks failed", new CompoundRuntimeException(errors))
        }
      }
      return results
    }
    catch (Throwable e) {
      span.recordException(e)
      span.setStatus(StatusCode.ERROR)
      throw e
    }
    finally {
      span.end()
      buildContext.messages.onAllForksFinished()
    }
  }

  @Nullable
  private static <T> ForkJoinTask<T> createTaskWrapper(BuildTaskRunnable<T> task, BuildContext buildContext, Context traceContext) {
    if (buildContext.options.buildStepsToSkip.contains(task.stepId)) {
      Span span = spanBuilder(task.stepId).setParent(traceContext).startSpan()
      span.addEvent("skip")
      span.end()
      return null
    }

    return ForkJoinTask.adapt(new Callable<T>() {
      @Override
      T call() throws Exception {
        Span span = spanBuilder(task.stepId).setParent(traceContext).startSpan()
        Scope scope = span.makeCurrent()
        buildContext.messages.onForkStarted()
        try {
          if (buildContext.options.buildStepsToSkip.contains(task.stepId)) {
            span.addEvent("skip")
            return null
          }
          else {
            return task.execute(buildContext)
          }
        }
        catch (Throwable e) {
          span.recordException(e)
          span.setStatus(StatusCode.ERROR)
          throw e
        }
        finally {
          span.end()
          scope.close()
          buildContext.messages.onForkFinished()
        }
      }
    })
  }

  @Override
  @CompileStatic
  void buildUpdaterJar() {
    doBuildUpdaterJar("updater.jar")
  }

  @Override
  @CompileStatic
  void buildFullUpdaterJar() {
    doBuildUpdaterJar("updater-full.jar")
  }

  @CompileStatic(TypeCheckingMode.SKIP)
  private void doBuildUpdaterJar(String artifactName) {
    String updaterModule = "intellij.platform.updater"
    List<File> libraryFiles = JpsJavaExtensionService.dependencies(buildContext.findRequiredModule(updaterModule))
      .productionOnly()
      .runtimeOnly()
      .libraries.collectMany { it.getFiles(JpsOrderRootType.COMPILED) }
    new LayoutBuilder(buildContext).layout(buildContext.paths.artifacts) {
      jar(artifactName, true) {
        module(updaterModule)
        for (file in libraryFiles) {
          ant.zipfileset(src: file.absolutePath, excludes: 'META-INF/**')
        }
      }
    }
  }

  @Override
  void runTestBuild() {
    checkProductProperties()
    setupBundledMaven()
    DistributionJARsBuilder distributionJARsBuilder = compileModulesForDistribution()
    distributionJARsBuilder.buildJARs(buildContext)
    layoutShared(buildContext)
    Map<String, String> checkerConfig = buildContext.productProperties.versionCheckerConfig
    if (checkerConfig != null) {
      new ClassVersionChecker(checkerConfig).checkVersions(buildContext, buildContext.paths.distAllDir)
    }
  }

  @Override
  void buildUnpackedDistribution(@NotNull Path targetDirectory, boolean includeBinAndRuntime) {
    BuildContext buildContext = buildContext
    buildContext.paths.distAllDir = targetDirectory.toAbsolutePath().normalize()
    OsFamily currentOs = SystemInfoRt.isWindows ? OsFamily.WINDOWS :
                         SystemInfoRt.isMac ? OsFamily.MACOS :
                         SystemInfoRt.isLinux ? OsFamily.LINUX : null
    if (currentOs == null) {
      buildContext.messages.error("Update from source isn't supported for '$SystemInfoRt.OS_NAME'")
    }
    buildContext.options.targetOS = currentOs.osId
    buildContext.options.buildStepsToSkip.add(BuildOptions.GENERATE_JAR_ORDER_STEP)

    setupBundledMaven()
    compileModulesForDistribution().buildJARs(buildContext, true)
    JvmArchitecture arch = CpuArch.isArm64() ? JvmArchitecture.aarch64 : JvmArchitecture.x64
    if (includeBinAndRuntime) {
      createSetupJbreTask(arch.name()).fork().join()
    }
    layoutShared(buildContext)

    if (includeBinAndRuntime) {
      Path propertiesFile = patchIdeaPropertiesFile()
      OsSpecificDistributionBuilder builder
      switch (currentOs) {
        case OsFamily.WINDOWS:
          builder = new WindowsDistributionBuilder(buildContext, buildContext.windowsDistributionCustomizer, propertiesFile, "$buildContext.applicationInfo")
          break
        case OsFamily.LINUX:
          builder = new LinuxDistributionBuilder(buildContext, buildContext.linuxDistributionCustomizer, propertiesFile)
          break
        case OsFamily.MACOS:
          builder = new MacDistributionBuilder(buildContext, buildContext.macDistributionCustomizer, propertiesFile)
          break
      }
      builder.copyFilesForOsDistribution(targetDirectory, arch)
      buildContext.bundledJreManager.extractJreTo(currentOs, targetDirectory.resolve("jbr"), arch)

      List<String> executableFilesPatterns = builder.generateExecutableFilesPatterns(true)
      updateExecutablePermissions(targetDirectory, executableFilesPatterns)
    }
    else {
      copyDistFiles(buildContext, targetDirectory)
      unpackPty4jNative(buildContext, targetDirectory, null)
    }
  }

  @CompileStatic(TypeCheckingMode.SKIP)
  private void updateExecutablePermissions(Path targetDirectory, List<String> executableFilesPatterns) {
    buildContext.ant.chmod(perm: "755") {
      fileset(dir: targetDirectory.toString()) {
        for (String pattern in executableFilesPatterns) {
          include(name: pattern)
        }
      }
    }
  }

  static copyDistFiles(@NotNull BuildContext buildContext, @NotNull Path newDir) {
    Files.createDirectories(newDir)
    for (Map.Entry<Path, String> item : buildContext.distFiles) {
      Path file = item.getKey()
      Path dir = newDir.resolve(item.getValue())
      Files.createDirectories(dir)
      Files.copy(file, dir.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  static void copyInspectScript(@NotNull BuildContext buildContext, @NotNull Path distBinDir) {
    String inspectScript = buildContext.productProperties.inspectCommandName
    if (inspectScript != "inspect") {
      Path targetPath = distBinDir.resolve("${inspectScript}.sh")
      Files.move(distBinDir.resolve("inspect.sh"), targetPath, StandardCopyOption.REPLACE_EXISTING)
      buildContext.patchInspectScript(targetPath)
    }
  }
}
