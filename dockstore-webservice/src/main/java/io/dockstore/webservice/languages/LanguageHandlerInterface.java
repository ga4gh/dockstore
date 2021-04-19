/*
 *    Copyright 2017 OICR
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
package io.dockstore.webservice.languages;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.LanguageHandlerHelper;
import io.dockstore.common.Registry;
import io.dockstore.common.VersionTypeValidation;
import io.dockstore.webservice.core.Checksum;
import io.dockstore.webservice.core.Image;
import io.dockstore.webservice.core.ParsedInformation;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.dockerhub.DockerHubImage;
import io.dockstore.webservice.core.dockerhub.DockerHubTag;
import io.dockstore.webservice.core.dockerhub.Results;
import io.dockstore.webservice.helpers.AbstractImageRegistry;
import io.dockstore.webservice.helpers.DAGHelper;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.swagger.quay.client.ApiClient;
import io.swagger.quay.client.ApiException;
import io.swagger.quay.client.Configuration;
import io.swagger.quay.client.api.RepositoryApi;
import io.swagger.quay.client.model.QuayRepo;
import io.swagger.quay.client.model.QuayTag;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * This interface will be the future home of all methods that will need to be added to support a new workflow language
 */
public interface LanguageHandlerInterface {
    String QUAY_URL = "https://quay.io/api/v1/";
    String DOCKERHUB_URL = AbstractImageRegistry.DOCKERHUB_URL;
    Logger LOG = LoggerFactory.getLogger(LanguageHandlerInterface.class);
    Gson GSON = new Gson();
    ApiClient API_CLIENT = Configuration.getDefaultApiClient();
    Pattern AMAZON_ECR_PATTERN = Pattern.compile("(.+)(\\.dkr\\.ecr\\.)(.+)(\\.amazonaws.com/)(.+)");
    Pattern GOOGLE_PATTERN = Pattern.compile("((us|eu|asia)(.))?(gcr\\.io)(.+)");
    // <org>/<repository>:<version> -> broadinstitute/gatk:4.0.1.1
    Pattern DOCKER_HUB = Pattern.compile("(\\w)+/(.*):(.+)");
    // <repo>:<version> -> postgres:9.6 Official Docker Hub images belong to the org "library", but that's not included when pulling the image
    Pattern OFFICIAL_DOCKER_HUB_IMAGE = Pattern.compile("(\\w|-)+:(.+)");

    /**
     * Parses the content of the primary descriptor to get author, email, and description
     *
     * @param filepath path to file
     * @param content a descriptor language document
     * @param sourceFiles
     * @param version the version to modify
     * @return
     */
    Version parseWorkflowContent(String filepath, String content, Set<SourceFile> sourceFiles, Version version);

    /**
     * Validates a workflow set for the workflow described by with primaryDescriptorFilePath
     * @param sourcefiles Set of sourcefiles
     * @param primaryDescriptorFilePath Primary descriptor path
     * @return Is a valid workflow set, error message
     */
    VersionTypeValidation validateWorkflowSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath);

    /**
     * Validates a tool set for the workflow described by with primaryDescriptorFilePath
     * @param sourcefiles Set of sourcefiles
     * @param primaryDescriptorFilePath Primary descriptor path
     * @return Is a valid tool set, error message
     */
    VersionTypeValidation validateToolSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath);

    /**
     * Validates a test parameter set
     * @param sourceFiles Set of sourcefiles
     * @return Are all test parameter files valid, collection of error messages
     */
    VersionTypeValidation validateTestParameterSet(Set<SourceFile> sourceFiles);

    /**
     * Parse a descriptor file and return a recursive mapping of its imports
     *
     * @param repositoryId            identifies the git repository that we wish to use, normally something like 'organization/repo_name`
     * @param content                 content of the primary descriptor
     * @param version                 version of the files to get
     * @param sourceCodeRepoInterface used too retrieve imports
     * @param filepath                used to help find relative imports, must be absolute
     * @return map of file paths to SourceFile objects
     */
    Map<String, SourceFile> processImports(String repositoryId, String content, Version version,
        SourceCodeRepoInterface sourceCodeRepoInterface, String filepath);

    /**
     * Processes a descriptor and its associated secondary descriptors to either return the tools that a workflow has or a DAG representation
     * of a workflow
     *
     * @param mainDescriptorPath   the path of the main descriptor
     * @param mainDescriptor       the content of the main descriptor
     * @param secondarySourceFiles the content of the secondary descriptors in a map, looks like file paths -> content
     * @param type                 tools or DAG
     * @param dao                  used to retrieve information on tools
     * @return either a DAG or some form of a list of tools for a workflow
     */
    Optional<String> getContent(String mainDescriptorPath, String mainDescriptor, Set<SourceFile> secondarySourceFiles, Type type, ToolDAO dao);

    /**
     * Checks that the test parameter files are valid JSON or YAML
     * Note: If even one is invalid, return invalid. Also merges all validation messages into one.
     * @param sourcefiles Set of sourcefiles
     * @param fileType Test parameter file type
     * @return Pair of isValid and validationMessage
     */
    default VersionTypeValidation checkValidJsonAndYamlFiles(Set<SourceFile> sourcefiles, DescriptorLanguage.FileType fileType) {
        boolean isValid = true;
        Map<String, String> validationMessageObject = new HashMap<>();
        for (SourceFile sourcefile : sourcefiles) {
            if (Objects.equals(sourcefile.getType(), fileType)) {
                Yaml yaml = new Yaml();
                try {
                    yaml.load(sourcefile.getContent());
                } catch (YAMLException e) {
                    validationMessageObject.put(sourcefile.getPath(), e.getMessage());
                    isValid = false;
                }
            }
        }
        return new VersionTypeValidation(isValid, validationMessageObject);
    }

    default String getCleanDAG(String mainDescriptorPath, String mainDescriptor, Set<SourceFile> secondarySourceFiles, Type type, ToolDAO dao) {
        Optional<String> content = getContent(mainDescriptorPath, mainDescriptor, secondarySourceFiles, type, dao);
        if (content.isPresent()) {
            return DAGHelper.cleanDAG(content.get());
        } else {
            return null;
        }
    }

    default ParsedInformation getParsedInformation(Version version, DescriptorLanguage descriptorLanguage) {
        Optional<ParsedInformation> foundParsedInformation = version.getVersionMetadata().getParsedInformationSet().stream()
                .filter(parsedInformation -> parsedInformation.getDescriptorLanguage() == descriptorLanguage).findFirst();
        if (foundParsedInformation.isPresent()) {
            return foundParsedInformation.get();
        } else {
            ParsedInformation parsedInformation = new ParsedInformation();
            parsedInformation.setDescriptorLanguage(descriptorLanguage);
            version.getVersionMetadata().getParsedInformationSet().add(parsedInformation);
            return parsedInformation;
        }
    }

    /**
     * Removes any sourcefiles of some file types from a set
     * @param sourcefiles
     * @param fileTypes
     * @return Filtered sourcefile set
     */
    default Set<SourceFile> filterSourcefiles(Set<SourceFile> sourcefiles, List<DescriptorLanguage.FileType> fileTypes) {
        return sourcefiles.stream()
                .filter(sourcefile -> fileTypes.contains(sourcefile.getType()))
                .collect(Collectors.toSet());
    }

    /**
     * This method will setup the nodes (nodePairs) and edges (stepToDependencies) into Cytoscape compatible JSON
     *
     * @param nodePairs          looks like a list of node ids and docker pull information (often null)
     * @param stepToDependencies looks like a map of node ids to their parents
     * @param stepToType         looks like a list of node ids paired with their type
     * @param nodeDockerInfo     also looks like a list of node ids mapped to a triple describing where it came from and some docker information?
     * @return Cytoscape compatible JSON with nodes and edges
     */
    default String setupJSONDAG(List<Pair<String, String>> nodePairs, Map<String, ToolInfo> stepToDependencies,
            Map<String, String> stepToType, Map<String, DockerInfo> nodeDockerInfo) {
        List<Map<String, Map<String, String>>> nodes = new ArrayList<>();
        List<Map<String, Map<String, String>>> edges = new ArrayList<>();

        // Iterate over steps, make nodes and edges
        for (Pair<String, String> node : nodePairs) {
            String stepId = node.getLeft();
            String dockerUrl = null;
            if (nodeDockerInfo.get(stepId) != null) {
                dockerUrl = nodeDockerInfo.get(stepId).getDockerUrl();
            }

            Map<String, Map<String, String>> nodeEntry = new HashMap<>();
            Map<String, String> dataEntry = new HashMap<>();
            dataEntry.put("id", stepId);
            dataEntry.put("tool", dockerUrl);
            dataEntry.put("name", stepId.replaceFirst("^dockstore_", ""));
            dataEntry.put("type", stepToType.get(stepId));
            if (nodeDockerInfo.get(stepId) != null) {
                dataEntry.put("docker", nodeDockerInfo.get(stepId).getDockerImage());
                dataEntry.put("run", nodeDockerInfo.get(stepId).getRunPath());
            }
            nodeEntry.put("data", dataEntry);
            nodes.add(nodeEntry);

            // Make edges based on dependencies
            if (stepToDependencies.get(stepId) != null) {
                for (String dependency : stepToDependencies.get(stepId).toolDependencyList) {
                    Map<String, Map<String, String>> edgeEntry = new HashMap<>();
                    Map<String, String> sourceTarget = new HashMap<>();
                    sourceTarget.put("source", dependency);
                    sourceTarget.put("target", stepId);
                    edgeEntry.put("data", sourceTarget);
                    edges.add(edgeEntry);
                }
            }
        }

        Map<String, List<Map<String, Map<String, String>>>> dagJson = new LinkedHashMap<>();
        dagJson.put("nodes", nodes);
        dagJson.put("edges", edges);

        return convertToJSONString(dagJson);
    }

    // the following are helper methods used by implementations of getContent, messy, but not sure where to put them for now

    /**
     * This method will setup the tools of a workflow
     * It will then call another method to transform it through Gson to a Json string
     *
     * @param nodeDockerInfo map of stepId -> (run path, docker pull, docker url)
     * @return string representation of json table tool content
     */
    default String getJSONTableToolContent(Map<String, DockerInfo> nodeDockerInfo) {
        // set up JSON for Table Tool Content for all workflow languages
        ArrayList<Object> tools = new ArrayList<>();

        //iterate through each step within workflow file
        for (Map.Entry<String, DockerInfo> entry : nodeDockerInfo.entrySet()) {
            String key = entry.getKey();
            DockerInfo value = entry.getValue();
            //get the idName and fileName
            String fileName = value.getRunPath();

            //get the docker requirement
            String dockerPullName = value.getDockerImage();
            String dockerLink = value.getDockerUrl();

            //put everything into a map, then ArrayList
            Map<String, String> dataToolEntry = new LinkedHashMap<>();
            dataToolEntry.put("id", key.replaceFirst("^dockstore_", ""));
            dataToolEntry.put("file", fileName);
            dataToolEntry.put("docker", dockerPullName);
            dataToolEntry.put("link", dockerLink);

            // Only add if docker and link are present
            if (dockerLink != null && dockerPullName != null) {
                tools.add(dataToolEntry);
            }
        }

        //call the GSON to string transformer
        return convertToJSONString(tools);
    }

    /**
     * This method will transform object containing the tools/dag of a workflow to Json string
     *
     * @param content has the final content of task/tool/node
     * @return String
     */
    default String convertToJSONString(Object content) {
        //create json string and return
        Gson gson = new Gson();
        String json = gson.toJson(content);
        LOG.debug(json);

        return json;
    }

    /**
     * Given a docker entry (quay or dockerhub), return a URL to the given entry
     *
     * @param dockerEntry has the docker name
     * @return URL
     */
    // TODO: Potentially add support for other registries and add message that the registry is unsupported
    default String getURLFromEntry(String dockerEntry, ToolDAO toolDAO) {
        // For now ignore tag, later on it may be more useful
        String quayIOPath = "https://quay.io/repository/";
        String dockerHubPathR = "https://hub.docker.com/r/"; // For type repo/subrepo:tag
        String dockerHubPathUnderscore = "https://hub.docker.com/_/"; // For type repo:tag
        String dockstorePath = "https://www.dockstore.org/containers/"; // Update to tools once UI is updated to use /tools instead of /containers

        String url;

        // Remove tag if exists
        Pattern p = Pattern.compile("([^:]+):?(\\S+)?");
        Matcher m = p.matcher(dockerEntry);
        if (m.matches()) {
            dockerEntry = m.group(1);
        }

        if (dockerEntry.isEmpty()) {
            return null;
        }

        // Regex for determining registry requires a tag; add a fake "0" tag
        Optional<Registry> registry = determineImageRegistry(dockerEntry + ":0");

        // TODO: How to deal with multiple entries of a tool? For now just grab the first
        // TODO: How do we check that the URL is valid? If not then the entry is likely a local docker build
        if (registry.isPresent() && registry.get().equals(Registry.QUAY_IO)) {
            List<Tool> byPath = toolDAO.findAllByPath(dockerEntry, true);
            if (byPath == null || byPath.isEmpty()) {
                // when we cannot find a published tool on Dockstore, link to quay.io
                url = dockerEntry.replaceFirst("quay\\.io/", quayIOPath);
            } else {
                // when we found a published tool, link to the tool on Dockstore
                url = dockstorePath + dockerEntry;
            }
        } else if (registry.isEmpty() || !registry.get().equals(Registry.DOCKER_HUB)) {
            // if the registry is neither Quay nor Docker Hub, return the entry as the url
            url = "https://" + dockerEntry;
        } else {  // DOCKER_HUB
            String[] parts = dockerEntry.split("/");
            if (parts.length == 2) {
                // if the path looks like pancancer/pcawg-oxog-tools
                List<Tool> publishedByPath = toolDAO.findAllByPath("registry.hub.docker.com/" + dockerEntry, true);
                if (publishedByPath == null || publishedByPath.isEmpty()) {
                    // when we cannot find a published tool on Dockstore, link to docker hub
                    url = dockerHubPathR + dockerEntry;
                } else {
                    // when we found a published tool, link to the tool on Dockstore
                    url = dockstorePath + "registry.hub.docker.com/" + dockerEntry;
                }
            } else {
                // if the path looks like debian:8 or debian
                url = dockerHubPathUnderscore + dockerEntry;
            }

        }

        return url;
    }

    default Optional<Registry> determineImageRegistry(String image) {
        if (image.startsWith("quay.io/")) {
            return Optional.of(Registry.QUAY_IO);
        } else if (image.startsWith("images.sbgenomics")) {
            return Optional.of(Registry.SEVEN_BRIDGES);
        } else if (image.startsWith("registry.gitlab.com")) {
            return Optional.of(Registry.GITLAB);
        } else if (GOOGLE_PATTERN.matcher(image).matches()) {
            return Optional.empty();
        } else if (AMAZON_ECR_PATTERN.matcher(image).matches()) {
            return Optional.of(Registry.AMAZON_ECR);
        } else if ((DOCKER_HUB.matcher(image).matches() || OFFICIAL_DOCKER_HUB_IMAGE.matcher(image).matches())) {
            return Optional.of(Registry.DOCKER_HUB);
        } else {
            return Optional.empty();
        }
    }

    // TODO: Implement then gitlab, seven bridges, amazon, google if possible;
    default Set<Image> getImagesFromRegistry(String toolsJSONTable) {
        List<Map<String, String>> dockerTools = new ArrayList<>();
        dockerTools = (ArrayList<Map<String, String>>)GSON.fromJson(toolsJSONTable, dockerTools.getClass());

        // Eliminate duplicate docker strings
        Set<String> dockerStrings = dockerTools.stream().map(dockertool -> dockertool.get("docker")).filter(Objects::nonNull).collect(Collectors.toSet());

        Set<Image> dockerImages = new HashSet<>();

        for (String image : dockerStrings) {
            String[] splitDocker;
            String[] splitTag;

            Optional<Registry> registry = determineImageRegistry(image);
            Registry registryFound = registry.isEmpty() ? null : registry.get();
            if (registryFound == null || registryFound == Registry.AMAZON_ECR || registryFound == Registry.GITLAB) {
                continue;
            } else if (registryFound == Registry.QUAY_IO) {
                try {
                    splitDocker = image.split("/");
                    splitTag = splitDocker[2].split(":");
                } catch (ArrayIndexOutOfBoundsException ex) {
                    LOG.error("URL to image on Quay incomplete", ex);
                    continue;
                }

                if (splitTag.length > 1) {
                    String repo = splitDocker[1] + "/" + splitTag[0];
                    String tagName = splitTag[1];
                    Set<Image> quayImages = getImageResponseFromQuay(repo, tagName);
                    dockerImages.addAll(quayImages);

                } else {
                    LOG.error("Could not find image version specified for " + splitDocker[1]);
                }
            } else if (registryFound == Registry.DOCKER_HUB) {
                // <org>/<repository>:<version> -> broadinstitute/gatk:4.0.1.1
                if (DOCKER_HUB.matcher(image).matches()) {
                    try {
                        splitDocker = image.split("/");
                        splitTag = splitDocker[1].split(":");
                    } catch (ArrayIndexOutOfBoundsException ex) {
                        LOG.error("URL to image on DockerHub incomplete", ex);
                        continue;
                    }

                    String repo = splitDocker[0] + "/" + splitTag[0];
                    String tagName = splitTag[1];

                    Set<Image> dockerHubImages = getImagesFromDockerHub(repo, tagName);
                    dockerImages.addAll(dockerHubImages);
                } else {
                    try {
                        splitDocker = image.split(":");
                    } catch (ArrayIndexOutOfBoundsException ex) {
                        LOG.error("URL to image on DockerHub incomplete", ex);
                        continue;
                    }
                    // <repo>:<version> -> python:2.7
                    String repo = "library" + "/" + splitDocker[0];
                    String tagName = splitDocker[1];

                    Set<Image> dockerHubImages = getImagesFromDockerHub(repo, tagName);
                    dockerImages.addAll(dockerHubImages);
                }
            }
        }
        return dockerImages;
    }

    default Set<Image> getImagesFromDockerHub(final String repo, final String tagName) {
        Set<Image> dockerHubImages = new HashSet<>();
        Map<String, String> errorMap = new HashMap<>();
        Optional<String> response;
        boolean versionFound = false;
        String repoUrl = DOCKERHUB_URL + "repositories/" + repo + "/tags?name=" + tagName;
        DockerHubTag dockerHubTag = new DockerHubTag();
        do {
            try {
                URL url = new URL(repoUrl);
                response = Optional.of(IOUtils.toString(url, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                LOG.error("Unable to get DockerHub response for " + repo, ex);
                response = Optional.empty();
            }

            if (response.isPresent()) {

                final String json = response.get();
                errorMap = (Map<String, String>)GSON.fromJson(json, errorMap.getClass());
                if (errorMap.get("message") != null) {
                    LOG.error("Error response from DockerHub: " + errorMap.get("message"));
                    return dockerHubImages;
                }

                // DockerHub seems to give empty results if something is not found, other fields are marked as null
                dockerHubTag = GSON.fromJson(json, DockerHubTag.class);
                List<Results> results = Arrays.asList(dockerHubTag.getResults());
                if (results.isEmpty()) {
                    LOG.error("Could not find any results for " + repo);
                    break;
                }

                for (Results r : results) {
                    if (r.getName().equals(tagName)) {
                        List<DockerHubImage> images = Arrays.asList(r.getImages());
                        // For every version, DockerHub can provide multiple images, one for each os/architecture
                        images.stream().forEach(dockerHubImage -> {
                            final String manifestDigest = dockerHubImage.getDigest();
                            Checksum checksum = new Checksum(manifestDigest.split(":")[0], manifestDigest.split(":")[1]);
                            List<Checksum> checksums = Collections.singletonList(checksum);
                            // Docker Hub appears to return null for all the "last_pushed" properties of their images.
                            // Using the result's "last_pushed" as a workaround
                            Image archImage = new Image(checksums, repo, tagName, r.getImageID(), Registry.DOCKER_HUB,
                                    dockerHubImage.getSize(), r.getLastUpdated());

                            String osInfo = formatDockerHubInfo(dockerHubImage.getOs(), dockerHubImage.getOsVersion());
                            String archInfo = formatDockerHubInfo(dockerHubImage.getArchitecture(), dockerHubImage.getVariant());
                            archImage.setOs(osInfo);
                            archImage.setArchitecture(archInfo);

                            dockerHubImages.add(archImage);
                        });
                        versionFound = true;
                        break;
                    }
                }
                if (!versionFound) {
                    repoUrl = dockerHubTag.getNext();
                }
            }
        } while (response.isPresent() && !versionFound && dockerHubTag.getNext() != null);
        return dockerHubImages;
    }

    default String formatDockerHubInfo(String type, String version) {
        String imageInfo = null;
        if (type != null) {
            imageInfo = type;
            if (version != null) {
                imageInfo = imageInfo + "/" + version;
            }
        }
        return imageInfo;
    }

    default Set<Image> getImageResponseFromQuay(String repo, String tagName) {
        Set<Image> quayImages = new HashSet<>();
        RepositoryApi api = new RepositoryApi(API_CLIENT);
        try {

            final QuayRepo quayRepo = api.getRepo(repo, false);
            QuayTag tag = quayRepo.getTags().get(tagName);
            if (tag == null) {
                LOG.error("Unable to get find tag: " + tagName + " from Quay in repo " + repo);
                return quayImages;
            }
            final String digest = tag.getManifestDigest();
            final String imageID = tag.getImageId();
            List<Checksum> checksums = Collections.singletonList(new Checksum(digest.split(":")[0], digest.split(":")[1]));
            quayImages.add(new Image(checksums, repo, tagName, imageID, Registry.QUAY_IO, tag.getSize(), tag.getLastModified()));
        } catch (ApiException ex) {
            LOG.error("Could not read from " + repo, ex);
        }
        return quayImages;
    }

    /**
     * Resolves a relative path based on an absolute parent path
     * @param parentPath Absolute path to parent file
     * @param relativePath Relative path the parent file
     * @return Absolute version of relative path
     */
    default String convertRelativePathToAbsolutePath(String parentPath, String relativePath) {
        return LanguageHandlerHelper.convertRelativePathToAbsolutePath(parentPath, relativePath);
    }

    /**
     * Terrible refactor in progress.
     * This code is used by both WDL and Nextflow to deal with the maps that we create for them.
     *
     * @param mainDescName    the filename of the main desciptor, used in the DAG list to indicate which tasks live in which descriptors
     * @param type            are we handling DAG or tools listing
     * @param dao             data access to tools
     * @param callType        ?
     * @param toolType        labels nodes of the DAG
     * @param toolInfoMap     map from names of tools to their dependencies (processes that had to come before) and to actual Docker containers that are used
     * @param namespaceToPath ?
     * @return the actual JSON output of either a DAG or tool listing
     */
    default Optional<String> convertMapsToContent(final String mainDescName, final Type type, ToolDAO dao, final String callType,
        final String toolType, Map<String, ToolInfo> toolInfoMap, Map<String, String> namespaceToPath) {

        // Initialize data structures for DAG
        List<Pair<String, String>> nodePairs = new ArrayList<>();
        Map<String, String> callToType = new HashMap<>();

        // Initialize data structures for Tool table
        Map<String, DockerInfo> nodeDockerInfo = new HashMap<>(); // map of stepId -> (run path, docker image, docker url)

        // Create nodePairs, callToType, toolID, and toolDocker
        for (Map.Entry<String, ToolInfo> entry : toolInfoMap.entrySet()) {
            String callId = entry.getKey();
            String docker = entry.getValue().dockerContainer;
            nodePairs.add(new MutablePair<>(callId, docker));
            if (Strings.isNullOrEmpty(docker)) {
                callToType.put(callId, callType);
            } else {
                callToType.put(callId, toolType);
            }
            String dockerUrl = null;
            if (!Strings.isNullOrEmpty(docker)) {
                dockerUrl = getURLFromEntry(docker, dao);
            }

            // Determine if call is imported
            String[] callName = callId.replaceFirst("^dockstore_", "").split("\\.");

            if (callName.length > 1) {
                nodeDockerInfo.put(callId, new DockerInfo(namespaceToPath.get(callName[0]), docker, dockerUrl, null));
            } else {
                nodeDockerInfo.put(callId, new DockerInfo(mainDescName, docker, dockerUrl, null));
            }
        }

        // Determine start node edges
        for (Pair<String, String> node : nodePairs) {
            ToolInfo toolInfo = toolInfoMap.get(node.getLeft());
            if (toolInfo.toolDependencyList.size() == 0) {
                toolInfo.toolDependencyList.add("UniqueBeginKey");
            }
        }
        nodePairs.add(new MutablePair<>("UniqueBeginKey", ""));

        // Determine end node edges
        Set<String> internalNodes = new HashSet<>(); // Nodes that are not leaf nodes
        Set<String> leafNodes = new HashSet<>(); // Leaf nodes

        for (Map.Entry<String, ToolInfo> entry : toolInfoMap.entrySet()) {
            List<String> dependencies = entry.getValue().toolDependencyList;
            internalNodes.addAll(dependencies);
            leafNodes.add(entry.getKey());
        }

        // Find leaf nodes by removing internal nodes
        leafNodes.removeAll(internalNodes);

        List<String> endDependencies = new ArrayList<>(leafNodes);

        toolInfoMap.put("UniqueEndKey", new ToolInfo(null, endDependencies));
        nodePairs.add(new MutablePair<>("UniqueEndKey", ""));

        // Create JSON for DAG/table
        if (type == Type.DAG) {
            return Optional.of(setupJSONDAG(nodePairs, toolInfoMap, callToType, nodeDockerInfo));
        } else if (type == Type.TOOLS) {
            return Optional.of(getJSONTableToolContent(nodeDockerInfo));
        }

        return Optional.empty();
    }

    enum Type {
        DAG, TOOLS
    }

    enum DockerSpecifier {
        /**
         * The image is not a string literal
         */
        PARAMETER,
        /**
         * The image is a string literal, but doesn't specify a tag
         */
        NO_TAG,
        /**
         * The image is a string literal with the tag "latest"
         */
        LATEST,
        /**
         * The image has a tag that is not "latest" nor a digest
         */
        TAG,
        /**
         * The image tag is a digest
         */
        DIGEST
    }

    class ToolInfo {

        protected final DockerSpecifier dockerSpecifier;

        /**
         * Currently, the id of a docker container as used by docker pull.
         * Due to some confusion, this is used by nfl and wdl, but not cwl.
         */
        String dockerContainer;
        /**
         * A list if ids for tools, processes that had to come before
         */
        List<String> toolDependencyList;

        ToolInfo(String dockerContainer, List<String> toolDependencyList) {
            this(dockerContainer, toolDependencyList, null);
        }

        ToolInfo(final String dockerContainer, final List<String> toolDependencyList, final DockerSpecifier dockerSpecifier) {
            this.dockerContainer = dockerContainer;
            this.toolDependencyList = toolDependencyList;
            this.dockerSpecifier = dockerSpecifier;
        }
    }

    class DockerInfo {
        private final String runPath;
        private final String dockerImage;
        private final String dockerUrl;
        private final DockerSpecifier dockerSpecifier;

        public DockerInfo(final String runPath, final String dockerImage, final String dockerUrl) {
            this(runPath, dockerImage, dockerUrl, null);
        }

        public DockerInfo(final String runPath, final String dockerImage, final String dockerUrl, final DockerSpecifier dockerSpecifier) {
            this.runPath = runPath;
            this.dockerImage = dockerImage;
            this.dockerUrl = dockerUrl;
            this.dockerSpecifier = dockerSpecifier;
        }

        public String getRunPath() {
            return runPath;
        }

        public String getDockerImage() {
            return dockerImage;
        }

        public String getDockerUrl() {
            return dockerUrl;
        }

        public DockerSpecifier getDockerSpecifier() {
            return dockerSpecifier;
        }
    }

}
