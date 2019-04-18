@file:Suppress("Detekt.TooManyFunctions")

package com.vanniktech.code.quality.tools

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LintPlugin
import com.android.build.gradle.internal.dsl.LintOptions
import com.android.repository.Revision
import de.aaschmid.gradle.plugins.cpd.Cpd
import de.aaschmid.gradle.plugins.cpd.CpdExtension
import de.aaschmid.gradle.plugins.cpd.CpdPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.CheckstylePlugin
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.plugins.quality.PmdPlugin
import org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File


const val GROUP_VERIFICATION = "verification"

class CodeQualityToolsPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    val extension = target.extensions.create("codeQualityTools", CodeQualityToolsPluginExtension::class.java, target.objects)

    val hasSubProjects = target.subprojects.size > 0

    if (hasSubProjects) {
      target.subprojects.forEach { subProject ->
        subProject.afterEvaluate {
          addCodeQualityTools(this, target, extension)
        }
      }
    } else {
      target.afterEvaluate {
        addCodeQualityTools(this, target, extension)
      }
    }
  }

  private fun addCodeQualityTools(project: Project, rootProject: Project, extension: CodeQualityToolsPluginExtension) {
    project.addPmd(rootProject, extension)
    project.addCheckstyle(rootProject, extension)
    project.addKtlint(rootProject, extension)
    project.addKotlin(extension)
    project.addCpd(extension)
    project.addDetekt(rootProject, extension)
    project.addErrorProne(extension)
    project.addLint(extension)
  }
}

@Suppress("Detekt.TooGenericExceptionCaught") // Will be fixed by latest version of Detekt.
fun androidGradlePluginVersion(): Revision {
  val o = Object()

  try {
    return Revision.parseRevision(Class.forName("com.android.builder.Version").getDeclaredField("ANDROID_GRADLE_PLUGIN_VERSION").get(o).toString(), Revision.Precision.PREVIEW)
  } catch (ignored: Exception) {
  }

  try {
    return Revision.parseRevision(Class.forName("com.android.builder.model.Version").getDeclaredField("ANDROID_GRADLE_PLUGIN_VERSION").get(o).toString(), Revision.Precision.PREVIEW)
  } catch (ignored: Exception) {
  }

  throw IllegalArgumentException("Can't get Android Gradle Plugin version")
}

fun hasLintPlugin(): Boolean {
  return try {
    Class.forName("com.android.build.gradle.LintPlugin")
    true
  } catch (ignored: ClassNotFoundException) {
    false
  }
}

fun Project.kotlinFiles() = fileTree(mapOf("dir" to ".", "exclude" to "**/build/**", "includes" to listOf("**/*.kt", "**/*.kts")))

fun Project.editorconfigFiles() = fileTree(mapOf("dir" to ".", "include" to "**/.editorconfig"))

fun Project.addPmd(rootProject: Project, extension: CodeQualityToolsPluginExtension): Boolean {
  val isNotIgnored = !shouldIgnore(extension)
  val isEnabled = extension.pmd.enabled
  val isPmdSupported = isJavaProject() || isAndroidProject()

  if (isNotIgnored && isEnabled && isPmdSupported) {
    plugins.apply(PmdPlugin::class.java)

    extensions.configure(PmdExtension::class.java) {
      toolVersion = extension.pmd.toolVersion
      isIgnoreFailures = extension.pmd.ignoreFailures ?: !extension.failEarly
      ruleSetFiles = files(rootProject.file(extension.pmd.ruleSetFile))
    }

    tasks.register("pmd", Pmd::class.java) {
      description = "Runs pmd."
      group = GROUP_VERIFICATION

      ruleSets = emptyList()

      source = fileTree(extension.pmd.source)
      include(extension.pmd.include)
      exclude(extension.pmd.exclude)

      reports.html.isEnabled = extension.htmlReports
      reports.xml.isEnabled = extension.xmlReports
    }

    tasks.named(CHECK_TASK_NAME).configure { dependsOn("pmd") }
    return true
  }

  return false
}

fun Project.addCheckstyle(rootProject: Project, extension: CodeQualityToolsPluginExtension): Boolean {
  val isNotIgnored = !shouldIgnore(extension)
  val isEnabled = extension.checkstyle.enabled
  val isCheckstyleSupported = isJavaProject() || isAndroidProject()

  if (isNotIgnored && isEnabled && isCheckstyleSupported) {
    plugins.apply(CheckstylePlugin::class.java)

    extensions.configure(CheckstyleExtension::class.java) {
      toolVersion = extension.checkstyle.toolVersion
      configFile = rootProject.file(extension.checkstyle.configFile)
      isIgnoreFailures = extension.checkstyle.ignoreFailures ?: !extension.failEarly
      isShowViolations = extension.checkstyle.showViolations ?: extension.failEarly
    }

    tasks.register("checkstyle", Checkstyle::class.java) {
      description = "Runs checkstyle."
      group = GROUP_VERIFICATION

      source = fileTree(extension.checkstyle.source)
      include(extension.checkstyle.include)
      exclude(extension.checkstyle.exclude)

      classpath = files()

      reports.html.isEnabled = extension.htmlReports
      reports.xml.isEnabled = extension.xmlReports
    }

    tasks.named(CHECK_TASK_NAME).configure { dependsOn("checkstyle") }
    return true
  }

  return false
}

@Suppress("Detekt.ComplexMethod")
fun Project.addLint(extension: CodeQualityToolsPluginExtension): Boolean {
  val isNotIgnored = !shouldIgnore(extension)
  val isEnabled = extension.lint.enabled
  val isAndroidProject = isAndroidProject()
  val isJavaProject = isJavaProject()

  if (isNotIgnored && isEnabled) {
    val lintOptions = if (isAndroidProject) {
      extensions.getByType(BaseExtension::class.java).lintOptions
    } else if (isJavaProject && hasLintPlugin()) {
      plugins.apply(LintPlugin::class.java)
      extensions.getByType(LintOptions::class.java)
    } else {
      null
    }

    if (lintOptions != null) {
      lintOptions.isWarningsAsErrors = extension.lint.warningsAsErrors ?: extension.failEarly
      lintOptions.isAbortOnError = extension.lint.abortOnError ?: extension.failEarly

      extension.lint.checkAllWarnings?.let {
        lintOptions.isCheckAllWarnings = it
      }

      extension.lint.absolutePaths?.let {
        lintOptions.isAbsolutePaths = it
      }

      extension.lint.baselineFileName?.let {
        lintOptions.baselineFile = file(it)
      }

      extension.lint.lintConfig?.let {
        lintOptions.lintConfig = it
      }

      extension.lint.checkReleaseBuilds?.let {
        lintOptions.isCheckReleaseBuilds = it
      }

      extension.lint.checkTestSources?.let {
        lintOptions.isCheckTestSources = it
      }

      extension.lint.checkDependencies?.let {
        lintOptions.isCheckDependencies = it
      }

      extension.lint.textReport?.let {
        lintOptions.setTextReport(it)
        lintOptions.textOutput(extension.lint.textOutput)
      }

      tasks.named(CHECK_TASK_NAME).configure { dependsOn("lint") }
      return true
    }
  }

  return false
}

fun Project.addKotlin(extension: CodeQualityToolsPluginExtension): Boolean {
  val isNotIgnored = !shouldIgnore(extension)
  val isKotlinProject = isKotlinProject()

  if (isNotIgnored && isKotlinProject) {
    project.tasks.withType(KotlinCompile::class.java) {
      kotlinOptions.allWarningsAsErrors = extension.kotlin.allWarningsAsErrors
    }
    return true
  }

  return false
}

private const val REPORT_KTLINT = "reports/ktlint/"

fun Project.addKtlint(rootProject: Project, extension: CodeQualityToolsPluginExtension): Boolean {
  val isNotIgnored = !shouldIgnore(extension)

  val isEnabled = extension.ktlint.enabled
  val isKtlintSupported = isKotlinProject()

  if (isNotIgnored && isEnabled && isKtlintSupported) {
    val ktlint = "ktlint"

    configurations.create(ktlint).defaultDependencies {
      add(dependencies.create("com.github.shyiko:ktlint:${extension.ktlint.toolVersion}"))
    }

    tasks.register(ktlint, KtLintTask::class.java) {
      experimental = extension.ktlint.experimental
      version = extension.ktlint.toolVersion
      outputDirectory = File(buildDir, REPORT_KTLINT)
      inputs.files(kotlinFiles(), rootProject.editorconfigFiles())
    }

    tasks.register(ktlint, KtLintTask::class.java) {
      experimental = extension.ktlint.experimental
      version = extension.ktlint.toolVersion
      outputDirectory = File(buildDir, REPORT_KTLINT)
      inputs.files(kotlinFiles(), rootProject.editorconfigFiles())
    }

    tasks.register("ktlintFormat", KtLintFormatTask::class.java) {
      experimental = extension.ktlint.experimental
      version = extension.ktlint.toolVersion
      outputDirectory = File(buildDir, REPORT_KTLINT)
      inputs.files(kotlinFiles(), rootProject.editorconfigFiles())
    }

    tasks.named(CHECK_TASK_NAME).configure { dependsOn(ktlint) }
    return true
  }

  return false
}

fun Project.addCpd(extension: CodeQualityToolsPluginExtension): Boolean {
  val isNotIgnored = !shouldIgnore(extension)
  val isEnabled = extension.cpd.enabled
  val isCpdSupported = isJavaProject() || isAndroidProject()

  if (isNotIgnored && isEnabled && isCpdSupported) {
    plugins.apply(CpdPlugin::class.java)

    extensions.configure(CpdExtension::class.java) {
      language = extension.cpd.language
      toolVersion = extension.pmd.toolVersion
      ignoreFailures = extension.cpd.ignoreFailures ?: !extension.failEarly
      minimumTokenCount = extension.cpd.minimumTokenCount
    }

    // CPD Plugin already creates the task so we'll just reconfigure it.
    tasks.named("cpdCheck", Cpd::class.java) {
      description = "Runs cpd."
      group = GROUP_VERIFICATION

      reports.text.isEnabled = extension.textReports
      reports.xml.isEnabled = extension.xmlReports

      encoding = "UTF-8"
      source = fileTree(extension.cpd.source).filter { it.name.endsWith(".${extension.cpd.language}") }.asFileTree
    }

    tasks.named(CHECK_TASK_NAME).configure { dependsOn("cpdCheck") }
    return true
  }

  return false
}

fun Project.addDetekt(rootProject: Project, extension: CodeQualityToolsPluginExtension): Boolean {
  val isNotIgnored = !shouldIgnore(extension)
  val isEnabled = extension.detekt.enabled
  val isDetektSupported = isKotlinProject()

  if (isNotIgnored && isEnabled && isDetektSupported) {
    configurations.create("detekt").defaultDependencies {
      add(dependencies.create("io.gitlab.arturbosch.detekt:detekt-cli:${extension.detekt.toolVersion}"))
    }

    tasks.register("detektCheck", DetektCheckTask::class.java) {
      failFast = extension.detekt.failFast
      version = extension.detekt.toolVersion
      outputDirectory = File(buildDir, "reports/detekt/")
      configFile = rootProject.file(extension.detekt.config)
      inputs.files(kotlinFiles())

      inputs.property("baseline-file-exists", false)

      extension.detekt.baselineFileName?.let {
        val file = file(it)
        baselineFilePath = file.toString()
        inputs.property("baseline-file-exists", file.exists())
      }
    }

    tasks.named(CHECK_TASK_NAME).configure { dependsOn("detektCheck") }
    return true
  }

  return false
}

private fun Project.shouldIgnore(extension: CodeQualityToolsPluginExtension) = extension.ignoreProjects.contains(name)

fun Project.addErrorProne(extension: CodeQualityToolsPluginExtension): Boolean {
  val isNotIgnored = !shouldIgnore(extension)
  val isEnabled = extension.errorProne.enabled
  val isErrorProneSupported = isJavaProject() || isAndroidProject()

  if (isNotIgnored && isEnabled && isErrorProneSupported) {
    plugins.apply("net.ltgt.errorprone")
    dependencies.add("errorprone", "com.google.errorprone:error_prone_core:2.3.3")
    dependencies.add("errorproneJavac", "com.google.errorprone:javac:9+181-r4173-1")
    return true
  }

  return false
}

private fun Project.isJavaProject(): Boolean {
  val isJava = plugins.hasPlugin("java")
  val isJavaLibrary = plugins.hasPlugin("java-library")
  val isJavaGradlePlugin = plugins.hasPlugin("java-gradle-plugin")
  return isJava || isJavaLibrary || isJavaGradlePlugin
}

private fun Project.isAndroidProject(): Boolean {
  val isAndroidLibrary = plugins.hasPlugin("com.android.library")
  val isAndroidApp = plugins.hasPlugin("com.android.application")
  val isAndroidTest = plugins.hasPlugin("com.android.test")
  val isAndroidFeature = plugins.hasPlugin("com.android.feature")
  val isAndroidInstantApp = plugins.hasPlugin("com.android.instantapp")
  return isAndroidLibrary || isAndroidApp || isAndroidTest || isAndroidFeature || isAndroidInstantApp
}

private fun Project.isKotlinProject(): Boolean {
  val isKotlin = plugins.hasPlugin("kotlin")
  val isKotlinAndroid = plugins.hasPlugin("kotlin-android")
  val isKotlinPlatformCommon = plugins.hasPlugin("kotlin-platform-common")
  val isKotlinPlatformJvm = plugins.hasPlugin("kotlin-platform-jvm")
  val isKotlinPlatformJs = plugins.hasPlugin("kotlin-platform-js")
  return isKotlin || isKotlinAndroid || isKotlinPlatformCommon || isKotlinPlatformJvm || isKotlinPlatformJs
}
