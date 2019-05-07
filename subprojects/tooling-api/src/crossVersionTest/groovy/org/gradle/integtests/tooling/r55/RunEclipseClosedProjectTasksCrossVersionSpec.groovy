/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.integtests.tooling.r55

import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.integtests.tooling.r54.IntermediateResultHandlerCollector
import org.gradle.tooling.model.eclipse.EclipseProject
import org.gradle.tooling.model.eclipse.EclipseWorkspace
import org.gradle.tooling.model.eclipse.EclipseWorkspaceProject
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@TargetGradleVersion(">=5.5")
@ToolingApiVersion(">=5.5")
class RunEclipseClosedProjectTasksCrossVersionSpec extends ToolingApiSpecification {

    @Rule
    TemporaryFolder externalProjectFolder = new TemporaryFolder()

    def setup() {
        ["child1", "child2"].each { file("$it/src/main/java").mkdirs() }

        buildFile << """
            subprojects {
                apply plugin: 'java-library'
            }
            project(":child1") {
                configurations {
                    testArtifacts
                }
                task testJar(type: Jar) {
                    classifier = "tests"
                }
                artifacts {
                    testArtifacts testJar
                }
            }
            project(":child2") {
                dependencies {
                    implementation project(":child1");
                    testImplementation project(path: ":child1", configuration: "testArtifacts")
                }
            }
        """
        settingsFile << "include ':child1', ':child2'"
    }

    def "will substitute and run build dependencies for closed projects on startup"() {
        setup:

        def projectsLoadedHandler = new IntermediateResultHandlerCollector<Void>()
        def buildFinishedHandler = new IntermediateResultHandlerCollector<EclipseProject>()
        def out = new ByteArrayOutputStream()
        def workspace = eclipseWorkspace([gradleProject("child1", false), gradleProject("child2")])
        when:
        withConnection { connection ->
            connection.action().projectsLoaded(new TellGradleToRunBuildDependencyTask(workspace), projectsLoadedHandler)
                .buildFinished(new LoadEclipseModel(workspace), buildFinishedHandler)
                .build()
                .setStandardOutput(out)
                .forTasks()
                .run()
        }

        then:
        def child2 = buildFinishedHandler.result.children.find { it.name == "child2" }
        child2.projectDependencies.isEmpty()
        child2.classpath.collect { it.file.name }.sort() == ['child1-tests.jar', 'child1.jar']
        taskExecuted(out, ":eclipseClosedProjectBuildDependencies")
        taskExecuted(out, ":child1:testJar")
        taskExecuted(out, ":child1:jar")
    }

    private def taskExecuted(ByteArrayOutputStream out, String taskPath) {
        out.toString().contains("> Task $taskPath")
    }

    EclipseWorkspace eclipseWorkspace(List<EclipseWorkspaceProject> projects) {
        new DefaultEclipseWorkspace(externalProjectFolder.newFolder("workspace"), projects)
    }

    EclipseWorkspaceProject gradleProject(String name, boolean isOpen = true) {
        project(name, file(name), isOpen)
    }

    EclipseWorkspaceProject project(String name, File location, boolean isOpen = true) {
        new DefaultEclipseWorkspaceProject(name, location, isOpen)
    }

    EclipseWorkspaceProject externalProject(String name) {
        new DefaultEclipseWorkspaceProject(name, externalProjectFolder.newFolder(name))
    }


}
