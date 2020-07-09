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
package io.swagger.api.impl;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Image;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Tag;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowVersion;
import io.openapi.api.impl.ToolsApiServiceImpl;
import io.openapi.model.Checksum;
import io.openapi.model.DescriptorType;
import io.openapi.model.ExtendedFileWrapper;
import io.openapi.model.FileWrapper;
import io.openapi.model.ImageData;
import io.openapi.model.ImageType;
import io.openapi.model.Tool;
import io.openapi.model.ToolVersion;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for interacting with GA4GH tools and workflows
 * Created by kcao on 01/03/17.
 */
public final class ToolsImplCommon {
    public static final String WORKFLOW_PREFIX = "#workflow";
    public static final String SERVICE_PREFIX = "#service";
    public static final String DOCKER_IMAGE_SHA_TYPE_FOR_TRS = "sha-256";
    private static final Logger LOG = LoggerFactory.getLogger(ToolsImplCommon.class);

    private ToolsImplCommon() { }



    /**
     * This converts a Dockstore's SourceFile to a GA4GH ToolDescriptor
     *
     * @param url clean url with no conversion
     * @param sourceFile The Dockstore SourceFile
     * @return The converted GA4GH ToolDescriptor paired with the raw content
     */
    public static ExtendedFileWrapper sourceFileToToolDescriptor(String url, SourceFile sourceFile) {
        ExtendedFileWrapper toolDescriptor = new ExtendedFileWrapper();
        convertToTRSChecksums(sourceFile);
        toolDescriptor.setChecksum(convertToTRSChecksums(sourceFile));
        toolDescriptor.setContent(sourceFile.getContent());
        toolDescriptor.setUrl(url);
        toolDescriptor.setOriginalFile(sourceFile);
        return toolDescriptor;
    }

    public static Tool convertEntryToTool(Entry<?, ?> container, DockstoreWebserviceConfiguration config) {
        return convertEntryToTool(container, config, false);
    }

    /**
     * Convert our Tool object to a standard Tool format
     *
     * @param container our data object
     * @return standardised data object
     */
    public static Tool convertEntryToTool(Entry<?, ?> container, DockstoreWebserviceConfiguration config, boolean showHiddenTags) {
        String newID = getNewId(container);
        boolean isDockstoreTool;
        String url = getUrlFromId(config, newID);
        if (url == null) {
            return null;
        }
        // TODO: hook this up to a type field in our DB?
        Tool tool = new Tool();
        setGeneralToolInfo(tool, container);
        tool.setId(newID);
        tool.setUrl(url);
        String checkerWorkflowPath = getCheckerWorkflowPath(config, container);
        checkerWorkflowPath = (checkerWorkflowPath == null) ? "" : checkerWorkflowPath;
        tool.setCheckerUrl(checkerWorkflowPath);
        boolean hasChecker = !(tool.getCheckerUrl().isEmpty() || tool.getCheckerUrl() == null);
        tool.setHasChecker(hasChecker);
        Set<? extends Version<?>> inputVersions;
        // tool specific
        io.dockstore.webservice.core.Tool castedContainer = null;
        if (container instanceof io.dockstore.webservice.core.Tool) {
            isDockstoreTool = true;
            castedContainer = (io.dockstore.webservice.core.Tool)container;
            // The name is composed of the repository name and then the optional toolname split with a '/'
            String name = castedContainer.getName();
            String toolName = castedContainer.getToolname();
            String returnName = constructName(Arrays.asList(name, toolName));
            tool.setName(returnName);
            tool.setOrganization(castedContainer.getNamespace());
            inputVersions = castedContainer.getWorkflowVersions();
        } else if (container instanceof Workflow) {
            isDockstoreTool = false;
            // workflow specific
            Workflow workflow = (Workflow)container;

            // The name is composed of the repository name and then the optional toolname split with a '/'
            String name = workflow.getRepository();
            String workflowName = workflow.getWorkflowName();
            String returnName = constructName(Arrays.asList(name, workflowName));
            tool.setName(returnName);
            tool.setOrganization(workflow.getOrganization());
            inputVersions = ((Workflow)container).getWorkflowVersions();
        } else {
            LOG.error("Unrecognized container type - neither tool or workflow: " + container.getId());
            return null;
        }
        tool.setAliases(new ArrayList<>(container.getAliases().keySet()));

        for (Version version : inputVersions) {
            if (shouldHideToolVersion(version, showHiddenTags, container.isHosted())) {
                continue;
            }

            ToolVersion toolVersion = new ToolVersion();

            toolVersion.setAuthor(MoreObjects.firstNonNull(toolVersion.getAuthor(), Lists.newArrayList()));
            //TODO: would hook up identified tools that form workflows here
            toolVersion.setIncludedApps(MoreObjects.firstNonNull(toolVersion.getIncludedApps(), Lists.newArrayList()));

            toolVersion.setSigned(false);
            final String author = ObjectUtils.firstNonNull(version.getAuthor(), container.getAuthor());
            if (author != null) {
                toolVersion.getAuthor().add(author);
            }

            toolVersion.setImages(MoreObjects.firstNonNull(toolVersion.getImages(), Lists.newArrayList()));
            if (container instanceof io.dockstore.webservice.core.Tool) {
                processImageDataForToolVersion(castedContainer, (Tag)version, toolVersion);
            }

            toolVersion.setIsProduction(version.isFrozen());
            if (toolVersion.isIsProduction()) {
                List<ImageData> trsImages = processImageDataForWorkflowVersions(version);
                toolVersion.getImages().addAll(trsImages);
            }

            try {
                toolVersion = setGeneralToolVersionInfo(url, toolVersion, version);
            } catch (UnsupportedEncodingException e) {
                LOG.error("Could not construct URL for our container with id: " + container.getId());
                return null;
            }
            toolVersion.setId(tool.getId() + ":" + version.getName());

            //            final Set<SourceFile> sourceFiles = version.getSourceFiles();
            //            for (SourceFile file : sourceFiles) {
            //                switch (file.getType()) {
            //                case DOCKSTORE_CWL:
            //                    toolVersion.addDescriptorTypeItem(DescriptorType.CWL);
            //                    break;
            //                case DOCKSTORE_WDL:
            //                    toolVersion.addDescriptorTypeItem(DescriptorType.WDL);
            //                    break;
            //                case DOCKSTORE_GXFORMAT2:
            //                    toolVersion.addDescriptorTypeItem(DescriptorType.GXFORMAT2);
            //                    break;
            //                // DOCKSTORE-2428 - demo how to add new workflow language
            //                //                case DOCKSTORE_SWL:
            //                //                    toolVersion.addDescriptorTypeItem(DescriptorType.SWL);
            //                //                    break;
            //                // TODO not sure how to treat service languages
            //                case DOCKSTORE_SERVICE_TEST_JSON:
            //                case DOCKSTORE_SERVICE_YML:
            //                    toolVersion.addDescriptorTypeItem(DescriptorType.SERVICE);
            //                    break;
            //                case NEXTFLOW:
            //                case NEXTFLOW_CONFIG:
            //                    toolVersion.addDescriptorTypeItem(DescriptorType.NFL);
            //                    break;
            //                case DOCKERFILE:
            //                    toolVersion.setContainerfile(true);
            //                default:
            //                    // Unhandled file type is apparently ignored
            //                    break;
            //                }
            //            }

            toolVersion.setDescriptorType(MoreObjects.firstNonNull(toolVersion.getDescriptorType(), Lists.newArrayList()));
            // ensure that descriptor is non-null before adding to list
            if (!toolVersion.getDescriptorType().isEmpty()) {
                // do some clean-up
                if (isDockstoreTool) {
                    Tag castedTag = (Tag)version;
                    toolVersion.setMetaVersion(String.valueOf(castedTag.getLastBuilt() != null ? castedTag.getLastBuilt() : new Date(0)));
                } else {
                    WorkflowVersion castedWorkflowVersion = (WorkflowVersion)version;
                    toolVersion.setMetaVersion(String.valueOf(castedWorkflowVersion.getLastModified() != null ? castedWorkflowVersion.getLastModified() : new Date(0)));
                }
                final List<DescriptorType> descriptorType = toolVersion.getDescriptorType();
                if (!descriptorType.isEmpty()) {
                    EnumSet<DescriptorType> set = EnumSet.copyOf(descriptorType);
                    toolVersion.setDescriptorType(Lists.newArrayList(set));
                }
                tool.getVersions().add(toolVersion);
            }
        }
        return tool;
    }

    private static List<ImageData> processImageDataForWorkflowVersions(final Version version) {
        Set<Image> images = version.getImages();
        List<ImageData> trsImages = new ArrayList<>();

        for (Image image : images) {
            ImageData imageData = new ImageData();
            imageData.setImageType(ImageType.DOCKER);
            imageData.setRegistryHost(image.getImageRegistry().getDockerPath());
            imageData.setImageName(constructName(Arrays.asList(image.getRepository(), image.getTag())));
            List<Checksum> trsChecksums = new ArrayList<>();
            List<io.dockstore.webservice.core.Checksum> checksumList = image.getChecksums();

            for (io.dockstore.webservice.core.Checksum checksum : checksumList) {
                Checksum trsChecksum = new Checksum();
                trsChecksum.setType(DOCKER_IMAGE_SHA_TYPE_FOR_TRS);
                trsChecksum.setChecksum(checksum.getChecksum());
                trsChecksums.add(trsChecksum);
            }
            imageData.setChecksum(trsChecksums);
            trsImages.add(imageData);
        }
        return trsImages;
    }

    /**
     * creates image data for tools (in the Dockstore definition specifically)
     * @param castedContainer Dockstore tool
     * @param version tag for Dockstore tool
     * @param toolVersion toolVersion to return in TRS
     */
    private static void processImageDataForToolVersion(io.dockstore.webservice.core.Tool castedContainer, Tag version,
        ToolVersion toolVersion) {
        ImageData data = new ImageData();
        List<Checksum> trsChecksums = new ArrayList<>();
        if (version.getImages() != null) {
            version.getImages().forEach(image -> {
                image.getChecksums().forEach(checksum -> {
                    Checksum trsChecksum = new Checksum();
                    trsChecksum.setType(DOCKER_IMAGE_SHA_TYPE_FOR_TRS);
                    trsChecksum.setChecksum(checksum.getChecksum());
                    trsChecksums.add(trsChecksum);
                });
            });
            data.setChecksum(trsChecksums);
        } else {
            //tools without images?
            data.setChecksum(MoreObjects.firstNonNull(data.getChecksum(), Lists.newArrayList()));
        }
        //TODO: for now, all container images are Docker based
        data.setImageType(ImageType.DOCKER);
        //TODO: hook up proper size
        data.setSize(0);
        //TODO: hook up proper date
        data.setUpdated(new Date().toString());
        data.setImageName(constructName(Arrays.asList(castedContainer.getRegistry(), castedContainer.getNamespace(), castedContainer.getName())));
        data.setRegistryHost(castedContainer.getRegistry());
        toolVersion.getImages().add(data);
    }

    /**
     * Whether to hide the ToolVersion in TRS or not
     * @param version   Dockstore version
     * @param showHiddenTags    Whether the user has read access to the Dockstore version or not
     * @return
     */
    private static boolean shouldHideToolVersion(Version<?> version, boolean showHiddenTags, boolean isHosted) {
        // Hide version if no name
        if (version.getName() == null) {
            return true;
        }
        // Hide hidden versions if not authenticated
        if (version.isHidden() && !showHiddenTags) {
            return true;
        }
        // Hide tags with no image ID (except hosted tools which do not have image IDs)
        return version instanceof Tag && ((Tag)version).getImageId() == null && !isHosted;
    }

    /**
     * Construct escaped ID and then the URL of the Tool
     *
     * @param newID   The ID of the Tool
     * @param baseURL The base URL for the tools endpoint
     * @return The URL of the Tool
     * @throws UnsupportedEncodingException When URL encoding has failed
     */
    public static String getUrl(String newID, String baseURL) throws UnsupportedEncodingException {
        String escapedID = URLEncoder.encode(newID, StandardCharsets.UTF_8.displayName());
        return baseURL + escapedID;
    }

    /**
     * Get baseURL from DockstoreWebServiceConfiguration
     *
     * @param config The DockstoreWebServiceConfiguration
     * @return The baseURL for GA4GH tools endpoint (e.g. "http://localhost:8080/api/api/ga4gh/v2/tools/")
     * @throws URISyntaxException When URI building goes wrong
     */
    public static String baseURL(DockstoreWebserviceConfiguration config) throws URISyntaxException {
        int port = config.getExternalConfig().getPort() == null ? -1 : Integer.parseInt(config.getExternalConfig().getPort());
        // basePath should be "/" or "/api/"
        String basePath = MoreObjects.firstNonNull(config.getExternalConfig().getBasePath(), "/");
        // Example without the replace: "/api/" + "/api/ga4gh/v2" + "/tools/" = "/api//api/ga4gh/v2/tools"
        // Example with the replace: "/api/api/ga4gh/v2/tools"
        String baseURI = basePath + DockstoreWebserviceApplication.GA4GH_API_PATH_V2_BETA.replaceFirst("/", "") + "/tools/";
        URI uri = new URI(config.getExternalConfig().getScheme(), null, config.getExternalConfig().getHostname(), port, baseURI, null, null);
        return uri.toString();
    }

    /**
     * Gets the checker workflow GA4GH path (test_tool_path) if it exists
     * @param config    The dockstore configuration file in order to find the base GA4GH path
     * @param entry     The entry to find its checker workflow path (test_tool_path)
     * @return          The checker workflow's GA4GH Tool ID
     */
    private static String getCheckerWorkflowPath(DockstoreWebserviceConfiguration config, Entry<?, ?> entry) {
        if (entry.getCheckerWorkflow() == null) {
            return null;
        } else {
            String newID = WORKFLOW_PREFIX + "/" + entry.getCheckerWorkflow().getWorkflowPath();
            return getUrlFromId(config, newID);
        }
    }

    /**
     * Gets the new ID of the Tool
     *
     * @param container The Dockstore Entry (Tool or Workflow)
     * @return The new ID of the Tool
     */
    private static String getNewId(Entry<?, ?> container) {
        if (container instanceof io.dockstore.webservice.core.Tool) {
            return ((io.dockstore.webservice.core.Tool)container).getToolPath();
        } else if (container instanceof Workflow) {
            Workflow workflow = (Workflow)container;
            DescriptorLanguage descriptorType = workflow.getDescriptorType();
            String workflowPath = workflow.getWorkflowPath();
            if (descriptorType == DescriptorLanguage.SERVICE) {
                return SERVICE_PREFIX + "/" + workflowPath;
            } else {
                return WORKFLOW_PREFIX + "/" + workflowPath;
            }
        } else {
            LOG.error("Could not construct URL for our container with id: " + container.getId());
            return null;
        }
    }

    /**
     * Set most of the GA4GH's ToolVersion information that is not based on the Dockstore source files
     *
     * @param url         Base URL of the tool
     * @param toolVersion The ToolVersion that will be modified
     * @param version     The Dockstore Version (Tag or WorkflowVersion)
     * @return The modified ToolVersion
     * @throws UnsupportedEncodingException When URL encoding has failed
     */
    private static ToolVersion setGeneralToolVersionInfo(String url, ToolVersion toolVersion, Version<?> version)
        throws UnsupportedEncodingException {
        String globalVersionId;
        globalVersionId = url + "/versions/" + URLEncoder.encode(version.getName(), StandardCharsets.UTF_8.displayName());
        toolVersion.setUrl(globalVersionId);
        toolVersion.setName(version.getName());
        toolVersion.setVerified(version.isVerified());
        String[] toolVerifiedSources = version.getVerifiedSources();
        toolVersion.setVerifiedSource(Lists.newArrayList(toolVerifiedSources));
        toolVersion.setContainerfile(false);
        return toolVersion;
    }

    /**
     * Set most of the GA4GH's Tool information that is not dependant on Dockstore's Tags or WorkflowVersions
     *
     * @param tool      The GA4GH Tool that will be modified
     * @param container The Dockstore Tool or Workflow
     * @return The modified Tool
     */
    private static Tool setGeneralToolInfo(Tool tool, Entry container) {
        // Set meta-version
        tool.setMetaVersion(container.getLastUpdated() != null ? container.getLastUpdated().toString() : new Date(0).toString());

        // Set type
        if (container instanceof io.dockstore.webservice.core.Tool) {
            tool.setToolclass(io.openapi.api.impl.ToolClassesApiServiceImpl.getCommandLineToolClass());
        } else if (container instanceof BioWorkflow) {
            tool.setToolclass(io.openapi.api.impl.ToolClassesApiServiceImpl.getWorkflowClass());
        } else if (container instanceof Service) {
            tool.setToolclass(io.openapi.api.impl.ToolClassesApiServiceImpl.getServiceClass());
        } else {
            throw new UnsupportedOperationException("encountered unknown entry type in TRS");
        }

        // Set description
        tool.setDescription(container.getDescription() != null ? container.getDescription() : "");

        // edge case: in Dockstore, a tool with no versions can still have an author but V2 final moved authors to versions of a tool
        // append it to description
        if (container.getWorkflowVersions().isEmpty() && container.getAuthor() != null) {
            tool.setDescription(tool.getDescription() + '\n' + "Author: " + container.getAuthor());
        }
        return tool;
    }

    /**
     * Construct the workflow/tool full name
     *
     * @param strings The components that make up the full name (repository name + optional workflow/tool name)
     * @return The full workflow/tool name
     */
    private static String constructName(List<String> strings) {
        // The name is composed of the repository name and then the optional workflowname split with a '/'
        StringJoiner joiner = new StringJoiner("/");
        for (String string : strings) {
            if (!Strings.isNullOrEmpty(string)) {
                joiner.add(string);
            }
        }
        return joiner.toString();
    }

    /**
     * Converts a Dockstore SourceFile to GA4GH ToolTests
     *
     * @param urlWithWorkDirectory
     * @param sourceFile The Dockstore SourceFile to convert
     * @return The resulting GA4GH ToolTests
     */
    public static FileWrapper sourceFileToToolTests(String urlWithWorkDirectory, SourceFile sourceFile) {
        DescriptorLanguage.FileType type = sourceFile.getType();
        if (!type.equals(DescriptorLanguage.FileType.WDL_TEST_JSON) && !type.equals(DescriptorLanguage.FileType.CWL_TEST_JSON) && !type.equals(
            DescriptorLanguage.FileType.NEXTFLOW_TEST_PARAMS)) {
            LOG.error("This source file is not a recognized test file.");
        }
        ExtendedFileWrapper toolTests = new ExtendedFileWrapper();
        List<Checksum> trsChecksums = convertToTRSChecksums(sourceFile);
        toolTests.setChecksum(trsChecksums);
        toolTests.setUrl(urlWithWorkDirectory + sourceFile.getPath());
        toolTests.setContent(sourceFile.getContent());
        toolTests.setOriginalFile(sourceFile);
        return toolTests;
    }

    private static List<Checksum> convertToTRSChecksums(final SourceFile sourceFile) {
        List<Checksum> trsChecksums = ToolsApiServiceImpl.convertToTRSChecksums(sourceFile);
        return trsChecksums;
    }

    /**
     * Create the GA4GH /tools/{id} url for a specific GA4GH Tool
     * @param config    The DockstoreWebserviceConfiguration which is used to get the baseURL
     * @param toolID    The ID of the GA4GH Tool
     * @return          The GA4GH /tools/{id} url
     */
    public static String getUrlFromId(DockstoreWebserviceConfiguration config, String toolID) {
        String url;
        if (toolID == null) {
            return null;
        } else {
            try {
                String baseURL = baseURL(config);
                url = getUrl(toolID, baseURL);
                return url;
            } catch (URISyntaxException | UnsupportedEncodingException e) {
                LOG.error("Could not construct URL for our container with id: " + toolID);
                return null;
            }
        }
    }
}
