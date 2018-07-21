/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.plugins.ide.eclipse.model;

import com.google.common.base.Preconditions;
import groovy.util.Node;

import java.io.File;

/**
 * A classpath entry representing a project dependency.
 */
public class ProjectDependency extends AbstractClasspathEntry {

    private String buildTaskName;
    private File publication;

    public ProjectDependency(Node node) {
        super(node);
        assertPathIsValid();
    }

    /**
     * Create a dependency on another Eclipse project.
     * @param path The path to the Eclipse project, which is the name of the eclipse project preceded by "/".
     */
    public ProjectDependency(String path) {
        super(path);
        assertPathIsValid();
    }

    private void assertPathIsValid() {
        Preconditions.checkArgument(path.startsWith("/"));
    }

    @Override
    public String getKind() {
        return "src";
    }

    @Override
    public String toString() {
        return "ProjectDependency{" +
            "path='" + path + '\'' +
            ", nativeLibraryLocation='" + getNativeLibraryLocation() +
            ", exported=" + exported +
            ", accessRules=" + accessRules +
            ", buildTaskName='" + buildTaskName + '\'' +
            ", publication=" + publication +
            '}';
    }

    public void setBuildTaskName(String name) {
        buildTaskName = name;
    }

    public void setPublication(File file) {
        publication = file;
    }

    public File getPublication() {
        return publication;
    }

    public String getBuildTaskName() {
        return buildTaskName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        ProjectDependency that = (ProjectDependency) o;

        if (buildTaskName != null ? !buildTaskName.equals(that.buildTaskName) : that.buildTaskName != null)
            return false;
        return publication != null ? publication.equals(that.publication) : that.publication == null;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (buildTaskName != null ? buildTaskName.hashCode() : 0);
        result = 31 * result + (publication != null ? publication.hashCode() : 0);
        return result;
    }
}
