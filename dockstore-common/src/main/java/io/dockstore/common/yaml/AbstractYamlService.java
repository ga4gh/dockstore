/*
 *    Copyright 2020 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package io.dockstore.common.yaml;

import java.util.List;
import java.util.Map;

import javax.validation.constraints.NotNull;

/**
 * Abstract base class for services defined in .dockstore.yml
 */
public abstract class AbstractYamlService {
    /**
     * The name of the service
     */
    private String name;
    /**
     * The service's author
     */
    private String author;
    /**
     * The service's description
     */
    private String description;
    /**
     * Change the service's publish-state, if set.
     * null does nothing; True & False correspond with the current API behaviour of publishing & unpublishing.
     */
    private Boolean publish;
    /**
     * A list of files that Dockstore should index
     */
    private List<String> files;
    /**
     * A set of git reference globs/regex patterns that Dockstore should filter for
     */
    private Filters filters = new Filters();
    /**
     * A scripts object for the service's lifecycle
     */
    private Scripts scripts;
    /**
     * A map where the keys are environment variable names and the values are <code>EnvironmentVariable</code>s
     */
    private Map<String, EnvironmentVariable> environment;
    /**
     * A map where the keys are the dataset names and the values are <code>DataSet</code>s
     */
    private Map<String, DataSet> data;

    public String getAuthor() {
        return author;
    }

    public void setAuthor(final String author) {
        this.author = author;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public Boolean getPublish() {
        return publish;
    }

    public void setPublish(final Boolean publish) {
        this.publish = publish;
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(final List<String> files) {
        this.files = files;
    }

    public Filters getFilters() {
        return filters;
    }

    public void setFilters(final Filters filters) {
        this.filters = filters;
    }

    public Scripts getScripts() {
        return scripts;
    }

    public void setScripts(final Scripts scripts) {
        this.scripts = scripts;
    }

    public Map<String, EnvironmentVariable> getEnvironment() {
        return environment;
    }

    public void setEnvironment(final Map<String, EnvironmentVariable> environment) {
        this.environment = environment;
    }

    public Map<String, DataSet> getData() {
        return data;
    }

    public void setData(final Map<String, DataSet> data) {
        this.data = data;
    }

    /**
     * Scripts is essentially a map, but with a known set of keys. The values are scripts that should be run at different stages
     * in the lifecycle of a service
     */
    public static class Scripts {
        /**
         * Associated script should return the port the service is exposing
         */
        private String port;
        /**
         * Associated script will run after the platform launcher has provisioned data
         */
        private String postprovision;
        /**
         * Associated script will run after the service has started
         */
        private String poststart;
        /**
         * Associated script will run before the platform launcher provisions data for the service
         */
        private String preprovision;
        /**
         * Associated script to run just before starting the service
         */
        private String prestart;

        @NotNull
        /**
         * The script to run to start up the service
         */
        private String start;

        @NotNull
        /**
         * The script to run to start the service
         */
        private String stop;

        public String getPort() {
            return port;
        }

        public void setPort(final String port) {
            this.port = port;
        }

        public String getPostprovision() {
            return postprovision;
        }

        public void setPostprovision(final String postprovision) {
            this.postprovision = postprovision;
        }

        public String getPoststart() {
            return poststart;
        }

        public void setPoststart(final String poststart) {
            this.poststart = poststart;
        }

        public String getPreprovision() {
            return preprovision;
        }

        public void setPreprovision(final String preprovision) {
            this.preprovision = preprovision;
        }

        public String getPrestart() {
            return prestart;
        }

        public void setPrestart(final String prestart) {
            this.prestart = prestart;
        }

        public String getStart() {
            return start;
        }

        public void setStart(final String start) {
            this.start = start;
        }

        public String getStop() {
            return stop;
        }

        public void setStop(final String stop) {
            this.stop = stop;
        }
    }

    /**
     * Describes an environment variable.
     */
    public static class EnvironmentVariable {
        private String defaultValue; // default is a key word
        @NotNull
        private String description;

        public String getDefault() {
            return defaultValue;
        }

        public void setDefault(final String newDefault) {
            this.defaultValue = newDefault;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }
    }

    /**
     * Describes a dataset. A dataset has a targetDirectory, where files should be downloaded to, and a map of file descriptors
     */
    public static class DataSet {
        @NotNull
        private String targetDirectory;
        /**
         * A map of name to FileDesc. The name is an arbitrary value, used to map values in an input.json type file
         */
        private Map<String, YamlService11.FileDesc> files;

        public String getTargetDirectory() {
            return targetDirectory;
        }

        public void setTargetDirectory(final String targetDirectory) {
            this.targetDirectory = targetDirectory;
        }

        public Map<String, YamlService11.FileDesc> getFiles() {
            return files;
        }

        public void setFiles(final Map<String, YamlService11.FileDesc> files) {
            this.files = files;
        }
    }

    /**
     * Describes a file. The targetDirectory is optional and will only be specified if the DataSet#targetDirectory needs to be
     * overridden.
     */
    public static class FileDesc {
        @NotNull
        private String description;

        private String targetDirectory;

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }

        public String getTargetDirectory() {
            return targetDirectory;
        }

        public void setTargetDirectory(final String targetDirectory) {
            this.targetDirectory = targetDirectory;
        }
    }
}
