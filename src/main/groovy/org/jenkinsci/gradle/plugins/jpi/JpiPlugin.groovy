/*
 * Copyright 2009-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.gradle.plugins.jpi

import org.gradle.api.DomainObjectSet
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.internal.artifacts.publish.ArchivePublishArtifact
import org.gradle.api.internal.plugins.DefaultArtifactPublicationSet
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.plugins.GroovyPlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.execution.TaskGraphExecuter
import org.gradle.language.base.plugins.LifecycleBasePlugin

import static org.gradle.api.plugins.JavaPlugin.RUNTIME_CONFIGURATION_NAME
import static org.gradle.util.GFileUtils.copyFile
import static org.jenkinsci.gradle.plugins.jpi.JpiManifest.attributesToMap

/**
 * Loads HPI related tasks into the current project.
 *
 * @author Hans Dockter
 * @author Kohsuke Kawaguchi
 * @author Andrew Bayer
 */
class JpiPlugin implements Plugin<Project> {
    /**
     * Represents the dependency to the Jenkins core.
     */
    public static final String CORE_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsCore'

    /**
     * Represents the dependency to the Jenkins war. Test scope.
     */
    public static final String WAR_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsWar'

    /**
     * Represents the dependencies on other Jenkins plugins.
     */
    public static final String PLUGINS_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsPlugins'

    /**
     * Represents the dependencies on other Jenkins plugins.
     *
     * Using a separate configuration until we see GRADLE-1749.
     */
    public static final String OPTIONAL_PLUGINS_DEPENDENCY_CONFIGURATION_NAME = 'optionalJenkinsPlugins'

    /**
     * Represents the Jenkins plugin test dependencies.
     */
    public static final String JENKINS_TEST_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsTest'

    public static final String SOURCES_JAR_TASK_NAME = 'sourcesJar'
    public static final String JAVADOC_JAR_TASK_NAME = 'javadocJar'

    void apply(final Project gradleProject) {
        gradleProject.plugins.apply(JavaPlugin)
        gradleProject.plugins.apply(WarPlugin)
        gradleProject.plugins.apply(MavenPublishPlugin)
        gradleProject.plugins.apply(GroovyPlugin)

        // never run war as it's useless
        gradleProject.tasks.getByName('war').onlyIf { false }

        def ext = new JpiExtension(gradleProject)
        gradleProject.extensions.jenkinsPlugin = ext

        def providedRuntime = gradleProject.configurations.getByName(WarPlugin.PROVIDED_RUNTIME_CONFIGURATION_NAME)

        def jpi = gradleProject.tasks.create(Jpi.TASK_NAME, Jpi)
        jpi.description = 'Generates the JPI package'
        jpi.group = BasePlugin.BUILD_GROUP
        jpi.dependsOn(ext.mainSourceTree().runtimeClasspath)
        jpi.classpath = ext.mainSourceTree().runtimeClasspath - providedRuntime

        gradleProject.tasks.findByName(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).dependsOn(jpi)

        def server = gradleProject.tasks.create(ServerTask.TASK_NAME, ServerTask)
        server.description = 'Run Jenkins in place with the plugin being developed'
        server.group = BasePlugin.BUILD_GROUP // TODO
        server.dependsOn(ext.mainSourceTree().runtimeClasspath)

        def stubs = gradleProject.tasks.create(StaplerGroovyStubsTask.TASK_NAME, StaplerGroovyStubsTask)
        stubs.description = 'Generates the Java stubs from Groovy source to enable Stapler annotation processing.'
        stubs.group = BasePlugin.BUILD_GROUP
        stubs.toolClasspath = gradleProject.sourceSets.main.compileClasspath
        stubs.inputDirs = gradleProject.sourceSets.main.groovy.srcDirs
        stubs.destinationDir = ext.staplerStubDir

        gradleProject.sourceSets.main.java.srcDirs += ext.staplerStubDir

        gradleProject.tasks.compileJava.dependsOn(stubs)

        // set build directory for Jenkins test harness, JENKINS-26331
        Test test = gradleProject.tasks.test as Test
        test.systemProperty('buildDirectory', gradleProject.buildDir.absolutePath)

        configureLocalizer(gradleProject)

        Task testInsertionTask = gradleProject.tasks.create(TestInsertionTask.TASK_NAME, TestInsertionTask)
        gradleProject.tasks.compileTestJava.dependsOn(testInsertionTask)

        gradleProject.task(SOURCES_JAR_TASK_NAME, type: Jar, dependsOn: 'classes') {
            classifier = 'sources'
            from gradleProject.sourceSets.main.allSource
        }
        gradleProject.task(JAVADOC_JAR_TASK_NAME, type: Jar, dependsOn: 'javadoc') {
            classifier = 'javadoc'
            from gradleProject.javadoc.destinationDir
        }

        configureRepositories(gradleProject)
        configureConfigurations(gradleProject)
        configureJpi(gradleProject)
        configureJar(gradleProject)
        configureTestResources(gradleProject)
        configurePublishing(gradleProject)

        // generate test hpl manifest for the current plugin, to be used during unit test
        def generateTestHpl = gradleProject.tasks.create('generate-test-hpl') << {
            def hpl = new File(ext.testSourceTree().output.classesDir, 'the.hpl')
            hpl.parentFile.mkdirs()
            hpl.withOutputStream { new JpiHplManifest(gradleProject).write(it) }
        }
        test.dependsOn(generateTestHpl)
    }

    private static Properties loadDotJenkinsOrg() {
        Properties props = new Properties()
        def dot = new File(new File(System.getProperty('user.home')), '.jenkins-ci.org')
        if (!dot.exists()) {
            throw new GradleException(
                    "Trying to deploy to Jenkins community repository but there's no credential file ${dot}." +
                            ' See https://wiki.jenkins-ci.org/display/JENKINS/Dot+Jenkins+Ci+Dot+Org'
            )
        }
        dot.withInputStream { i -> props.load(i) }
        props
    }

    private static configureJpi(Project project) {
        project.afterEvaluate {
            JpiExtension jpiExtension = project.extensions.getByType(JpiExtension)
            Jar jpiTask = project.tasks.getByName(Jpi.TASK_NAME) as Jpi
            jpiTask.archiveName = "${jpiExtension.shortName}.${jpiExtension.fileExtension}"
            jpiTask.extension = jpiExtension.fileExtension
        }
    }

    private static configureJar(Project project) {
        // add manifest to the JAR file
        project.afterEvaluate {
            Jar jarTask = project.tasks.getByName(JavaPlugin.JAR_TASK_NAME) as Jar
            jarTask.manifest.attributes(attributesToMap(new JpiManifest(project).mainAttributes))
        }
    }

    private static configureTestResources(Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)
        Task processTestResources = project.tasks.getByName(javaConvention.sourceSets.test.processResourcesTaskName)
        Configuration plugins = project.configurations.create('pluginResources')

        project.afterEvaluate {
            [PLUGINS_DEPENDENCY_CONFIGURATION_NAME, OPTIONAL_PLUGINS_DEPENDENCY_CONFIGURATION_NAME].each {
                project.configurations.getByName(it).dependencies.each {
                    project.dependencies.add(plugins.name, "${it.group}:${it.name}:${it.version}")
                }
            }
            processTestResources.inputs.files(plugins.resolve())
        }

        processTestResources.doLast {
            File targetDir = javaConvention.sourceSets.test.output.resourcesDir
            plugins.resolvedConfiguration.resolvedArtifacts.findAll { it.extension in ['hpi', 'jpi'] }.each {
                copyFile(it.file, new File(targetDir, "plugins/${it.name}.${it.extension}"))
            }
        }
    }

    private static configureLocalizer(Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)
        JpiExtension jpiExtension = project.extensions.getByType(JpiExtension)

        LocalizerTask localizer = project.tasks.create(LocalizerTask.TASK_NAME, LocalizerTask)
        localizer.description = 'Generates the Java source for the localizer.'
        localizer.group = BasePlugin.BUILD_GROUP
        localizer.sourceDirs = javaConvention.sourceSets.main.resources.srcDirs
        localizer.conventionMapping.map('destinationDir') {
            jpiExtension.localizerOutputDir
        }
        javaConvention.sourceSets.main.java.srcDir { localizer.destinationDir }
        project.tasks[javaConvention.sourceSets.main.compileJavaTaskName].dependsOn(localizer)
    }

    private static configureRepositories(Project project) {
        project.afterEvaluate {
            if (project.extensions.getByType(JpiExtension).configureRepositories) {
                project.repositories {
                    mavenCentral()
                    mavenLocal()
                    maven {
                        name 'jenkins'
                        url('http://repo.jenkins-ci.org/public/')
                    }
                }
            }
        }
    }

    private static configureConfigurations(Project project) {
        ConfigurationContainer cc = project.configurations
        Configuration jenkinsCoreConfiguration = cc.create(CORE_DEPENDENCY_CONFIGURATION_NAME).
                setVisible(false).
                setDescription('Jenkins core that your plugin is built against')
        Configuration jenkinsPluginsConfiguration = cc.create(PLUGINS_DEPENDENCY_CONFIGURATION_NAME).
                setVisible(false).
                setDescription('Jenkins plugins which your plugin is built against')
        Configuration optionalJenkinsPluginsConfiguration = cc.create(OPTIONAL_PLUGINS_DEPENDENCY_CONFIGURATION_NAME).
                setVisible(false).
                setDescription('Optional Jenkins plugins dependencies which your plugin is built against')
        Configuration jenkinsTestConfiguration = cc.create(JENKINS_TEST_DEPENDENCY_CONFIGURATION_NAME).
                setVisible(false).
                setDescription('Jenkins plugin test dependencies.')
                .exclude(group: 'org.jenkins-ci.modules', module: 'ssh-cli-auth')
                .exclude(group: 'org.jenkins-ci.modules', module: 'sshd')
        cc.getByName(WarPlugin.PROVIDED_COMPILE_CONFIGURATION_NAME).extendsFrom(jenkinsCoreConfiguration)
        cc.getByName(WarPlugin.PROVIDED_COMPILE_CONFIGURATION_NAME).extendsFrom(jenkinsPluginsConfiguration)
        cc.getByName(WarPlugin.PROVIDED_COMPILE_CONFIGURATION_NAME).extendsFrom(optionalJenkinsPluginsConfiguration)
        cc.getByName(JavaPlugin.TEST_COMPILE_CONFIGURATION_NAME).extendsFrom(jenkinsTestConfiguration)

        cc.create(WAR_DEPENDENCY_CONFIGURATION_NAME).
                setVisible(false).
                setDescription('Jenkins war that corresponds to the Jenkins core')

        cc.getByName(JavaPlugin.TEST_COMPILE_CONFIGURATION_NAME).incoming.beforeResolve {
            jenkinsTestConfiguration.resolvedConfiguration.resolvedArtifacts.each { ResolvedArtifact ra ->
                if (ra.extension == 'hpi' || ra.extension == 'jpi') {
                    project.dependencies.add(JavaPlugin.TEST_COMPILE_CONFIGURATION_NAME, "${ra.moduleVersion.id}@jar")
                }
            }
        }
    }

    private static configurePublishing(Project project) {
        PublishingExtension publishingExtension = project.extensions.getByType(PublishingExtension)
        JpiExtension jpiExtension = project.extensions.getByType(JpiExtension)

        AbstractArchiveTask jpi = project.tasks.getByName(Jpi.TASK_NAME) as AbstractArchiveTask
        Task jar = project.tasks.getByName(JavaPlugin.JAR_TASK_NAME)
        Task sourcesJar = project.tasks.getByName(SOURCES_JAR_TASK_NAME)
        Task javadocJar = project.tasks.getByName(JAVADOC_JAR_TASK_NAME)
        DependencySet runtimeDependencies = project.configurations[RUNTIME_CONFIGURATION_NAME].allDependencies
        DependencySet coreDependencies = project.configurations[CORE_DEPENDENCY_CONFIGURATION_NAME].allDependencies

        ArchivePublishArtifact jpiArtifact = new ArchivePublishArtifact(jpi)
        project.extensions.getByType(DefaultArtifactPublicationSet).addCandidate(jpiArtifact)

        // exclude core dependencies which are inherited from the parent POM
        DomainObjectSet<Dependency> jpiDependencies = runtimeDependencies.matching { !coreDependencies.contains(it) }

        SoftwareComponent jpiComponent = new JpiComponent(jpiArtifact, jpiDependencies)
        project.components.add(jpiComponent)

        // delay configuration until all settings are available (groupId, shortName, ...)
        project.afterEvaluate {
            if (jpiExtension.configurePublishing) {
                publishingExtension.publications {
                    mavenJpi(MavenPublication) {
                        artifactId jpiExtension.shortName

                        from jpiComponent

                        artifact jar
                        artifact sourcesJar
                        artifact javadocJar

                        pom.packaging = jpiExtension.fileExtension
                        pom.withXml { XmlProvider xmlProvider ->
                            new JpiPomCustomizer(project).customizePom(xmlProvider.asNode())
                        }
                    }
                }
                publishingExtension.repositories {
                    maven {
                        name 'jenkins'
                        if (project.version.toString().endsWith('-SNAPSHOT')) {
                            url jpiExtension.snapshotRepoUrl
                        } else {
                            url jpiExtension.repoUrl
                        }
                    }
                }
            }
        }

        // load credentials only when publishing
        project.gradle.taskGraph.whenReady { TaskGraphExecuter taskGraph ->
            if (jpiExtension.configurePublishing && taskGraph.hasTask(project.tasks.publish)) {
                def credentials = loadDotJenkinsOrg()
                publishingExtension.repositories.getByName('jenkins').credentials {
                    username credentials.userName
                    password credentials.password
                }
            }
        }
    }
}
