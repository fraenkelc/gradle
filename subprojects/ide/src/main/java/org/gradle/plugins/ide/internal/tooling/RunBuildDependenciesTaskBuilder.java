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

package org.gradle.plugins.ide.internal.tooling;

import org.gradle.StartParameter;
import org.gradle.api.NonNullApi;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.tooling.model.eclipse.EclipseRuntime;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@NonNullApi
public class RunBuildDependenciesTaskBuilder implements ParameterizedToolingModelBuilder<EclipseRuntime> {
    private ArrayList<TaskDependency> buildDependencies = new ArrayList<>();
    private Set<String> closedProjectPaths = new HashSet<>();

    @Override
    public Class<EclipseRuntime> getParameterType() {
        return EclipseRuntime.class;
    }

    @Override
    public Object buildAll(String modelName, EclipseRuntime eclipseRuntime, Project project) {
        Set<String> closedProjectNames = eclipseRuntime.getWorkspace().getProjects().stream().filter(p -> !p.isOpen()).map(p -> p.getName()).collect(Collectors.toSet());

        gatherClosedProjectPaths(closedProjectNames, project);
        Project rootProject = project.getRootProject();
        populate(rootProject);
        if (!closedProjectPaths.isEmpty()) {
            StartParameter startParameter = project.getGradle().getStartParameter();
            List<String> taskPaths = new ArrayList<>(startParameter.getTaskNames());
            String placeHolderTaskName = placeHolderTaskName(rootProject, "eclipseClosedProjectBuildDependencies");
            Task task = rootProject.task(placeHolderTaskName);
            task.dependsOn(buildDependencies.toArray(new Object[0]));
            taskPaths.add(placeHolderTaskName);
            startParameter.setTaskNames(taskPaths);
        }
        return new Object();
    }

    private void gatherClosedProjectPaths(Set<String> closedProjectNames, Project project) {
        project.getPluginManager().apply(EclipsePlugin.class);
        EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
        String projectName = eclipseModel.getProject().getName();
        if (closedProjectNames.contains(projectName)) {
            closedProjectPaths.add(project.getPath());
        }
        project.getSubprojects().forEach(p -> gatherClosedProjectPaths(closedProjectNames, p));
    }

    private void populate(Project project) {

        for (Configuration configuration : project.getExtensions().getByType(EclipseModel.class).getClasspath().getPlusConfigurations()) {
            ArtifactView artifactView = configuration.getIncoming().artifactView(vc -> {
                vc.componentFilter(new Spec<ComponentIdentifier>() {
                    @Override
                    public boolean isSatisfiedBy(ComponentIdentifier element) {
                        return element instanceof ProjectComponentIdentifier && closedProjectPaths.contains(((ProjectComponentIdentifier) element).getProjectPath());
                    }
                });
            });
            buildDependencies.add(artifactView.getFiles().getBuildDependencies());
        }

        for (Project childProject : project.getChildProjects().values()) {
            populate(childProject);
        }

    }

    @Override
    public boolean canBuild(String modelName) {
        return "org.gradle.tooling.model.eclipse.RunClosedProjectBuildDependencies".equals(modelName);
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        // nothing to do if no EclipseRuntime is supplied.
        return new Object();
    }

    private static String placeHolderTaskName(Project project, String baseName) {
        if (project.getTasks().findByName(baseName) == null) {
            return baseName;
        } else {
            return placeHolderTaskName(project, baseName + "_");
        }
    }

}
