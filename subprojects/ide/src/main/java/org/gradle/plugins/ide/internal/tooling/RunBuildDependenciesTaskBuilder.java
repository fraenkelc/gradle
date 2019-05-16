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
import org.gradle.api.Action;
import org.gradle.api.NonNullApi;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.artifacts.DefaultProjectComponentIdentifier;
import org.gradle.api.internal.model.NamedObjectInstantiator;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.api.internal.project.ProjectStateRegistry;
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
    private final ProjectStateRegistry projectRegistry;

    public RunBuildDependenciesTaskBuilder(ProjectStateRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    @Override
    public Class<EclipseRuntime> getParameterType() {
        return EclipseRuntime.class;
    }

    @Override
    public Object buildAll(String modelName, EclipseRuntime eclipseRuntime, Project project) {
        Set<String> closedProjectPaths = gatherClosedProjectPaths(eclipseRuntime);
        Set<TaskDependency> buildDependencies = gatherBuildDependenciesForClosedProjects(closedProjectPaths);

        if (!buildDependencies.isEmpty()) {
            Project rootProject = project.getRootProject();
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

    private Set<TaskDependency> gatherBuildDependenciesForClosedProjects(Set<String> closedProjectPaths) {
        Set<TaskDependency> buildDependencies = new HashSet<>();
        for (ProjectState state : projectRegistry.getAllProjects()) {
            ProjectInternal project = state.getOwner().getLoadedSettings().getGradle().getRootProject().project(state.getComponentIdentifier().getProjectPath());
            EclipseModel model = project.getExtensions().getByType(EclipseModel.class);
            // TODO: do we need to handle minusConfigurations too?
            for (Configuration configuration : model.getClasspath().getPlusConfigurations()) {
                ArtifactView artifactView = configuration.getIncoming().artifactView(vc -> {
                    vc.componentFilter(new Spec<ComponentIdentifier>() {
                        @Override
                        public boolean isSatisfiedBy(ComponentIdentifier element) {
                            return element instanceof DefaultProjectComponentIdentifier && closedProjectPaths.contains(((DefaultProjectComponentIdentifier) element).getIdentityPath().getPath());
                        }
                    });
                    vc.attributes(new Action<AttributeContainer>() {
                        @Override
                        public void execute(AttributeContainer attrs) {
                            attrs.attribute(Usage.USAGE_ATTRIBUTE, NamedObjectInstantiator.INSTANCE.named(Usage.class, Usage.JAVA_RUNTIME_JARS));
                        }
                    });
                    vc.setLenient(true);
                });
                if (!artifactView.getFiles().isEmpty()) {
                    buildDependencies.add(artifactView.getFiles().getBuildDependencies());
                }
            }
        }
        return buildDependencies;
    }

    private Set<String> gatherClosedProjectPaths(EclipseRuntime eclipseRuntime) {
        Set<String> closedProjectNames = eclipseRuntime.getWorkspace().getProjects().stream().filter(p -> !p.isOpen()).map(p -> p.getName()).collect(Collectors.toSet());
        Set<String> closedProjectPaths = new HashSet<>();
        for (ProjectState state : projectRegistry.getAllProjects()) {
            ProjectInternal project = state.getOwner().getLoadedSettings().getGradle().getRootProject().project(state.getComponentIdentifier().getProjectPath());
            project.getPluginManager().apply(EclipsePlugin.class);
            EclipseModel model = project.getExtensions().getByType(EclipseModel.class);
            if (closedProjectNames.contains(model.getProject().getName())) {
                closedProjectPaths.add(project.getIdentityPath().getPath());
            }
        }
        return closedProjectPaths;
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
