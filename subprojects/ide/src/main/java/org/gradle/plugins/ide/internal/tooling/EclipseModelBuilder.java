/*
 * Copyright 2011 the original author or authors.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.xml.XmlTransformer;
import org.gradle.plugins.ide.api.XmlFileContentMerger;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.eclipse.model.AbstractClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.AbstractLibrary;
import org.gradle.plugins.ide.eclipse.model.AccessRule;
import org.gradle.plugins.ide.eclipse.model.BuildCommand;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.ClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.Container;
import org.gradle.plugins.ide.eclipse.model.EclipseClasspath;
import org.gradle.plugins.ide.eclipse.model.EclipseJdt;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.Library;
import org.gradle.plugins.ide.eclipse.model.Link;
import org.gradle.plugins.ide.eclipse.model.Output;
import org.gradle.plugins.ide.eclipse.model.ProjectDependency;
import org.gradle.plugins.ide.eclipse.model.SourceFolder;
import org.gradle.plugins.ide.internal.configurer.EclipseModelAwareUniqueProjectNameProvider;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultAccessRule;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultClasspathAttribute;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseBuildCommand;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseClasspathContainer;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseExternalDependency;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseJavaSourceSettings;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseLinkedResource;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseOutputLocation;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseProject;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseProjectDependency;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseProjectNature;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseSourceDirectory;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseTask;
import org.gradle.plugins.ide.internal.tooling.java.DefaultInstalledJdk;
import org.gradle.plugins.ide.internal.tooling.model.DefaultGradleProject;
import org.gradle.tooling.model.eclipse.EclipseRuntime;
import org.gradle.tooling.model.eclipse.EclipseWorkspace;
import org.gradle.tooling.model.eclipse.EclipseWorkspaceProject;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;
import org.gradle.util.CollectionUtils;
import org.gradle.util.GUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class EclipseModelBuilder implements ParameterizedToolingModelBuilder<EclipseRuntime> {
    private final GradleProjectBuilder gradleProjectBuilder;
    private final EclipseModelAwareUniqueProjectNameProvider uniqueProjectNameProvider;

    private boolean projectDependenciesOnly;
    private DefaultEclipseProject result;
    private List<DefaultEclipseProject> eclipseProjects;
    private TasksFactory tasksFactory;
    private DefaultGradleProject rootGradleProject;
    private Project currentProject;
    private EclipseRuntime eclipseRuntime;
    private Map<String, EclipseWorkspaceProject> eclipseWorkspaceProjects = new HashMap<>();

    @VisibleForTesting
    public EclipseModelBuilder(GradleProjectBuilder gradleProjectBuilder, ServiceRegistry services, EclipseModelAwareUniqueProjectNameProvider uniqueProjectNameProvider) {
        this.gradleProjectBuilder = gradleProjectBuilder;
        this.uniqueProjectNameProvider = uniqueProjectNameProvider;
    }

    public EclipseModelBuilder(GradleProjectBuilder gradleProjectBuilder, ServiceRegistry services) {
        this(gradleProjectBuilder, services, new EclipseModelAwareUniqueProjectNameProvider(services.get(ProjectStateRegistry.class)));
    }

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals("org.gradle.tooling.model.eclipse.EclipseProject")
            || modelName.equals("org.gradle.tooling.model.eclipse.HierarchicalEclipseProject");
    }

    @Override
    public Class<EclipseRuntime> getParameterType() {
        return EclipseRuntime.class;
    }

    @Override
    public Object buildAll(String modelName, EclipseRuntime eclipseRuntime, Project project) {
        this.eclipseRuntime = eclipseRuntime;
        if (eclipseRuntime.getWorkspace() != null && eclipseRuntime.getWorkspace().getProjects() != null) {
            for (EclipseWorkspaceProject workspaceProject : eclipseRuntime.getWorkspace().getProjects()) {
                eclipseWorkspaceProjects.put(workspaceProject.getName(), workspaceProject);
            }
        }

        return buildAll(modelName, project);
    }

    @Override
    public DefaultEclipseProject buildAll(String modelName, Project project) {
        boolean includeTasks = modelName.equals("org.gradle.tooling.model.eclipse.EclipseProject");
        tasksFactory = new TasksFactory(includeTasks);
        projectDependenciesOnly = modelName.equals("org.gradle.tooling.model.eclipse.HierarchicalEclipseProject");
        currentProject = project;
        eclipseProjects = Lists.newArrayList();
        Project root = project.getRootProject();
        rootGradleProject = gradleProjectBuilder.buildAll(project);
        tasksFactory.collectTasks(root);
        applyEclipsePlugin(root);
        deduplicateProjectNames(root);
        buildHierarchy(root);
        populate(root);
        return result;
    }

    private void deduplicateProjectNames(Project root) {
        uniqueProjectNameProvider.setReservedProjectNames(calculateReservedProjectNames(root, eclipseRuntime));
        for (Project project : root.getAllprojects()) {
            EclipseModel eclipseModel = project.getExtensions().findByType(EclipseModel.class);
            if (eclipseModel != null) {
                eclipseModel.getProject().setName(uniqueProjectNameProvider.getUniqueName(project));
            }
        }
    }

    private void applyEclipsePlugin(Project root) {
        Set<Project> allProjects = root.getAllprojects();
        for (Project p : allProjects) {
            p.getPluginManager().apply(EclipsePlugin.class);
        }
        for (IncludedBuild includedBuild : root.getGradle().getIncludedBuilds()) {
            IncludedBuildState includedBuildInternal = (IncludedBuildState) includedBuild;
            applyEclipsePlugin(includedBuildInternal.getConfiguredBuild().getRootProject());
        }
    }

    private DefaultEclipseProject buildHierarchy(Project project) {
        List<DefaultEclipseProject> children = new ArrayList<DefaultEclipseProject>();
        for (Project child : project.getChildProjects().values()) {
            children.add(buildHierarchy(child));
        }

        EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
        org.gradle.plugins.ide.eclipse.model.EclipseProject internalProject = eclipseModel.getProject();
        String name = internalProject.getName();
        String description = GUtil.elvis(internalProject.getComment(), null);
        DefaultEclipseProject eclipseProject =
            new DefaultEclipseProject(name, project.getPath(), description, project.getProjectDir(), children)
                .setGradleProject(rootGradleProject.findByPath(project.getPath()));

        for (DefaultEclipseProject child : children) {
            child.setParent(eclipseProject);
        }
        addProject(project, eclipseProject);
        return eclipseProject;
    }

    private void addProject(Project project, DefaultEclipseProject eclipseProject) {
        if (project == currentProject) {
            result = eclipseProject;
        }
        eclipseProjects.add(eclipseProject);
    }

    private void populate(Project project) {
        EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);

        final List<DefaultEclipseExternalDependency> externalDependencies = new LinkedList<DefaultEclipseExternalDependency>();
        final List<DefaultEclipseProjectDependency> projectDependencies = new LinkedList<DefaultEclipseProjectDependency>();
        final List<DefaultEclipseSourceDirectory> sourceDirectories = new LinkedList<DefaultEclipseSourceDirectory>();
        final List<DefaultEclipseClasspathContainer> classpathContainers = new LinkedList<DefaultEclipseClasspathContainer>();
        boolean projectDependenciesOnly = this.projectDependenciesOnly;
        Map<String, EclipseWorkspaceProject> eclipseWorkspaceProjects = this.eclipseWorkspaceProjects;

        DefaultEclipseOutputLocation outputLocation = gatherClasspathElements(externalDependencies, projectDependencies, sourceDirectories, classpathContainers, projectDependenciesOnly, eclipseWorkspaceProjects, eclipseModel.getClasspath(), new ArrayList<>());

        DefaultEclipseProject eclipseProject = findEclipseProject(project);

        eclipseProject.setClasspath(externalDependencies);
        eclipseProject.setProjectDependencies(projectDependencies);
        eclipseProject.setSourceDirectories(sourceDirectories);
        eclipseProject.setClasspathContainers(classpathContainers);
        eclipseProject.setOutputLocation(outputLocation != null ? outputLocation : new DefaultEclipseOutputLocation("bin"));
        eclipseProject.setAutoBuildTasks(!eclipseModel.getAutoBuildTasks().getDependencies(null).isEmpty());

        org.gradle.plugins.ide.eclipse.model.Project xmlProject = new org.gradle.plugins.ide.eclipse.model.Project(new XmlTransformer());

        XmlFileContentMerger projectFile = eclipseModel.getProject().getFile();
        if (projectFile == null) {
            xmlProject.configure(eclipseModel.getProject());
        } else {
            eclipseModel.getProject().mergeXmlProject(xmlProject);
        }

        populateEclipseProjectTasks(eclipseProject, tasksFactory.getTasks(project));
        populateEclipseProject(eclipseProject, xmlProject);
        populateEclipseProjectJdt(eclipseProject, eclipseModel.getJdt());

        for (Project childProject : project.getChildProjects().values()) {
            populate(childProject);
        }
    }

    public static DefaultEclipseOutputLocation gatherClasspathElements(List<DefaultEclipseExternalDependency> externalDependencies,
                                                                       List<DefaultEclipseProjectDependency> projectDependencies,
                                                                       List<DefaultEclipseSourceDirectory> sourceDirectories,
                                                                       List<DefaultEclipseClasspathContainer> classpathContainers,
                                                                       boolean projectDependenciesOnly,
                                                                       Map<String, EclipseWorkspaceProject> eclipseWorkspaceProjects,
                                                                       EclipseClasspath eclipseClasspath, List<TaskDependency> buildDependencies) {
        eclipseClasspath.setProjectDependenciesOnly(projectDependenciesOnly);

        List<ClasspathEntry> classpathEntries;
        if (eclipseClasspath.getFile() == null) {
            classpathEntries = eclipseClasspath.resolveDependencies();
        } else {
            Classpath classpath = new Classpath(eclipseClasspath.getFileReferenceFactory());
            eclipseClasspath.mergeXmlClasspath(classpath);
            classpathEntries = classpath.getEntries();
        }

        final Set<String> visitedProjectPaths = new HashSet<>();
        DefaultEclipseOutputLocation outputLocation = null;

        for (ClasspathEntry entry : classpathEntries) {
            //we don't handle Variables at the moment because users didn't request it yet
            //and it would probably push us to add support in the tooling api to retrieve the variable mappings.
            if (entry instanceof Library) {
                AbstractLibrary library = (AbstractLibrary) entry;
                final File file = library.getLibrary().getFile();
                final File source = library.getSourcePath() == null ? null : library.getSourcePath().getFile();
                final File javadoc = library.getJavadocPath() == null ? null : library.getJavadocPath().getFile();
                DefaultEclipseExternalDependency dependency = new DefaultEclipseExternalDependency(file, javadoc, source, library.getModuleVersion(), library.isExported(), createAttributes(library), createAccessRules(library));
                externalDependencies.add(dependency);
            } else if (entry instanceof ProjectDependency) {
                final ProjectDependency projectDependency = (ProjectDependency) entry;
                // By removing the leading "/", this is no longer a "path" as defined by Eclipse
                final String path = StringUtils.removeStart(projectDependency.getPath(), "/");
                EclipseWorkspaceProject eclipseWorkspaceProject = eclipseWorkspaceProjects.get(path);
                if (eclipseWorkspaceProject != null && !eclipseWorkspaceProject.isOpen()) {
                    externalDependencies.add(new DefaultEclipseExternalDependency(projectDependency.getPublication(), null, null, null, projectDependency.isExported(), createAttributes(projectDependency), createAccessRules(projectDependency)));
                    buildDependencies.add(projectDependency.getBuildDependencies());
                } else if (!visitedProjectPaths.contains(path)) {
                    // we only want one copy of a non-replaced project dependency on the classpath.
                    DefaultEclipseProjectDependency dependency = new DefaultEclipseProjectDependency(path, projectDependency.isExported(), createAttributes(projectDependency), createAccessRules(projectDependency));
                    projectDependencies.add(dependency);
                    visitedProjectPaths.add(path);
                }
            } else if (entry instanceof SourceFolder) {
                final SourceFolder sourceFolder = (SourceFolder) entry;
                String path = sourceFolder.getPath();
                List<String> excludes = sourceFolder.getExcludes();
                List<String> includes = sourceFolder.getIncludes();
                String output = sourceFolder.getOutput();
                sourceDirectories.add(new DefaultEclipseSourceDirectory(path, sourceFolder.getDir(), excludes, includes, output, createAttributes(sourceFolder), createAccessRules(sourceFolder)));
            } else if (entry instanceof Container) {
                final Container container = (Container) entry;
                classpathContainers.add(new DefaultEclipseClasspathContainer(container.getPath(), container.isExported(), createAttributes(container), createAccessRules(container)));
            } else if (entry instanceof Output) {
                outputLocation = new DefaultEclipseOutputLocation(((Output) entry).getPath());
            }
        }
        return outputLocation;
    }

    private static void populateEclipseProjectTasks(DefaultEclipseProject eclipseProject, Iterable<Task> projectTasks) {
        List<DefaultEclipseTask> tasks = new ArrayList<DefaultEclipseTask>();
        for (Task t : projectTasks) {
            tasks.add(new DefaultEclipseTask(eclipseProject, t.getPath(), t.getName(), t.getDescription()));
        }
        eclipseProject.setTasks(tasks);
    }

    private static void populateEclipseProject(DefaultEclipseProject eclipseProject, org.gradle.plugins.ide.eclipse.model.Project xmlProject) {
        List<DefaultEclipseLinkedResource> linkedResources = new LinkedList<DefaultEclipseLinkedResource>();
        for (Link r : xmlProject.getLinkedResources()) {
            linkedResources.add(new DefaultEclipseLinkedResource(r.getName(), r.getType(), r.getLocation(), r.getLocationUri()));
        }
        eclipseProject.setLinkedResources(linkedResources);

        List<DefaultEclipseProjectNature> natures = new ArrayList<DefaultEclipseProjectNature>();
        for (String n : xmlProject.getNatures()) {
            natures.add(new DefaultEclipseProjectNature(n));
        }
        eclipseProject.setProjectNatures(natures);

        List<DefaultEclipseBuildCommand> buildCommands = new ArrayList<DefaultEclipseBuildCommand>();
        for (BuildCommand b : xmlProject.getBuildCommands()) {
            Map<String, String> arguments = Maps.newLinkedHashMap();
            for (Map.Entry<String, String> entry : b.getArguments().entrySet()) {
                arguments.put(convertGString(entry.getKey()), convertGString(entry.getValue()));
            }
            buildCommands.add(new DefaultEclipseBuildCommand(b.getName(), arguments));
        }
        eclipseProject.setBuildCommands(buildCommands);
    }

    private static void populateEclipseProjectJdt(DefaultEclipseProject eclipseProject, EclipseJdt jdt) {
        if (jdt != null) {
            eclipseProject.setJavaSourceSettings(new DefaultEclipseJavaSourceSettings()
                .setSourceLanguageLevel(jdt.getSourceCompatibility())
                .setTargetBytecodeVersion(jdt.getTargetCompatibility())
                .setJdk(DefaultInstalledJdk.current())
            );
        }
    }

    private DefaultEclipseProject findEclipseProject(final Project project) {
        return CollectionUtils.findFirst(eclipseProjects, new Spec<DefaultEclipseProject>() {
            @Override
            public boolean isSatisfiedBy(DefaultEclipseProject element) {
                return element.getGradleProject().getPath().equals(project.getPath());
            }
        });
    }

    private static List<DefaultClasspathAttribute> createAttributes(AbstractClasspathEntry classpathEntry) {
        List<DefaultClasspathAttribute> result = Lists.newArrayList();
        Map<String, Object> attributes = classpathEntry.getEntryAttributes();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            Object value = entry.getValue();
            result.add(new DefaultClasspathAttribute(convertGString(entry.getKey()), value == null ? "" : value.toString()));
        }
        return result;
    }

    private static List<DefaultAccessRule> createAccessRules(AbstractClasspathEntry classpathEntry) {
        List<DefaultAccessRule> result = Lists.newArrayList();
        for (AccessRule accessRule : classpathEntry.getAccessRules()) {
            result.add(createAccessRule(accessRule));
        }
        return result;
    }

    private static DefaultAccessRule createAccessRule(AccessRule accessRule) {
        int kindCode;
        String kind = accessRule.getKind();
        if (kind.equals("accessible") || kind.equals("0")) {
            kindCode = 0;
        } else if (kind.equals("nonaccessible") || kind.equals("1")) {
            kindCode = 1;
        } else if (kind.equals("discouraged") || kind.equals("2")) {
            kindCode = 2;
        } else {
            kindCode = 0;
        }
        return new DefaultAccessRule(kindCode, accessRule.getPattern());
    }

    private List<Project> collectAllProjects(List<Project> all, Gradle gradle) {
        all.addAll(gradle.getRootProject().getAllprojects());
        for (IncludedBuild includedBuild : gradle.getIncludedBuilds()) {
            collectAllProjects(all, ((IncludedBuildState) includedBuild).getConfiguredBuild());
        }
        return all;
    }

    private Gradle getRootBuild(Gradle gradle) {
        if (gradle.getParent() == null) {
            return gradle;
        }
        return gradle.getParent();
    }

    private List<String> calculateReservedProjectNames(Project rootProject, EclipseRuntime parameter) {
        if (parameter == null) {
            return Collections.emptyList();
        }

        EclipseWorkspace workspace = parameter.getWorkspace();
        if (workspace == null) {
            return Collections.emptyList();
        }

        List<EclipseWorkspaceProject> projects = workspace.getProjects();
        if (projects == null) {
            return Collections.emptyList();
        }

        // The eclipse workspace contains projects from root and included builds. Check projects from all builds
        // so that models built for included builds do not consider projects from parent builds as external.
        Set<File> gradleProjectLocations = collectAllProjects(new ArrayList<>(), getRootBuild(rootProject.getGradle())).stream()
            .map(p -> p.getProjectDir().getAbsoluteFile()).collect(Collectors.toSet());
        List<String> reservedProjectNames = new ArrayList<>();
        for (EclipseWorkspaceProject project : projects) {
            if (project == null || project.getLocation() == null || project.getName() == null || project.getLocation() == null) {
                continue;
            }
            if (!gradleProjectLocations.contains(project.getLocation().getAbsoluteFile())) {
                reservedProjectNames.add(project.getName());
            }
        }

        return reservedProjectNames;
    }


    /*
     * Groovy manipulates the JVM to let GString extend String.
     * Whenever we have a Set or Map containing Strings, it might also
     * contain GStrings. This breaks deserialization on the client.
     * This method forces GString to String conversion.
     */
    private static String convertGString(CharSequence original) {
        return original.toString();
    }
}
