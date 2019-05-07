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
import org.gradle.api.tasks.TaskDependency;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.eclipse.model.EclipseClasspath;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.tooling.model.eclipse.EclipseRuntime;
import org.gradle.tooling.model.eclipse.EclipseWorkspaceProject;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@NonNullApi
public class RunBuildDependenciesTaskBuilder implements ParameterizedToolingModelBuilder<EclipseRuntime> {
    private EclipseRuntime eclipseRuntime;
    private ArrayList<TaskDependency> buildDependencies = new ArrayList<>();

    @Override
    public Class<EclipseRuntime> getParameterType() {
        return EclipseRuntime.class;
    }

    @Override
    public Object buildAll(String modelName, EclipseRuntime eclipseRuntime, Project project) {
        this.eclipseRuntime = eclipseRuntime;
        Project rootProject = project.getRootProject();
        populate(rootProject);
        if (!buildDependencies.isEmpty()) {
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

    private void populate(Project project) {

        project.getPluginManager().apply(EclipsePlugin.class);
        EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
        EclipseClasspath eclipseClasspath = eclipseModel.getClasspath();

        Map<String, EclipseWorkspaceProject> eclipseWorkspaceProjects = new HashMap<>();
        for (EclipseWorkspaceProject workspaceProject : eclipseRuntime.getWorkspace().getProjects()) {
            eclipseWorkspaceProjects.put(workspaceProject.getName(), workspaceProject);
        }

        EclipseModelBuilder.gatherClasspathElements(new LinkedList<>(), new LinkedList<>(), new LinkedList<>(), new LinkedList<>(), false, eclipseWorkspaceProjects, eclipseClasspath, buildDependencies);


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
