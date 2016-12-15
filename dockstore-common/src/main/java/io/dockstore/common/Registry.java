/*
 *    Copyright 2016 OICR
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

package io.dockstore.common;

/**
 * This enumerates the types of docker registry that we can associate an entry with.
 * 
 * @author dyuen
 */
public enum Registry {
    // Add new registries here
    QUAY_IO("quay.io", "Quay.io", "https://quay.io/repository/", false), DOCKER_HUB("registry.hub.docker.com", "Docker Hub", "https://hub.docker.com/", false),
    GITLAB("registry.gitlab.com", "GitLab", "https://gitlab.com/", false), AMAZON_ECR(null, "Amazon ECR", null, true);

    /**
     * this name is what is actually used in commands like docker pull
     */
    private final String dockerCommand;

    /**
     * this name is what is displayed to users to name the registry
     */
    private final String friendlyName;

    /**
     * this url is what is used for creating links to the registry website.
     * A value of 'empty' means that there are no ways to directly link to the image
     */
    private final String url;

    /**
     * if set to true, then the registry has no public facing site and is only available through an authorized docker pull (the docker registry path is not set)
     * if set to false, then the registry has a public facing site which can be linked to
     */
    private final boolean privateOnly;

    Registry(final String dockerCommand, final String friendlyName, final String url, final boolean privateOnly) {
        this.friendlyName = friendlyName;
        this.dockerCommand = dockerCommand;
        this.url = url;
        this.privateOnly = privateOnly;
    }

    @Override
    public String toString() {
        return dockerCommand;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public String getUrl() {
        return url;
    }

    public boolean isPrivateOnly() {
        return privateOnly;
    }
}
