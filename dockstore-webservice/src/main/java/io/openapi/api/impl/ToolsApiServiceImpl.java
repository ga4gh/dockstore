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

package io.openapi.api.impl;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Tag;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.helpers.EntryVersionHelper;
import io.dockstore.webservice.helpers.statelisteners.TRSListener;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dockstore.webservice.resources.AuthenticatedResourceInterface;
import io.openapi.api.ToolsApiService;
import io.openapi.model.Checksum;
import io.openapi.model.Error;
import io.openapi.model.ExtendedFileWrapper;
import io.openapi.model.FileWrapper;
import io.openapi.model.ToolFile;
import io.openapi.model.ToolVersion;
import io.swagger.api.impl.ToolsImplCommon;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.common.DescriptorLanguage.FileType.DOCKERFILE;
import static io.dockstore.common.DescriptorLanguage.FileType.DOCKSTORE_CWL;
import static io.dockstore.common.DescriptorLanguage.FileType.DOCKSTORE_WDL;
import static io.openapi.api.impl.ToolClassesApiServiceImpl.COMMAND_LINE_TOOL;
import static io.openapi.api.impl.ToolClassesApiServiceImpl.WORKFLOW;
import static io.swagger.api.impl.ToolsImplCommon.SERVICE_PREFIX;
import static io.swagger.api.impl.ToolsImplCommon.WORKFLOW_PREFIX;

public class ToolsApiServiceImpl extends ToolsApiService implements AuthenticatedResourceInterface {
    private static final String GITHUB_PREFIX = "git@github.com:";
    private static final String BITBUCKET_PREFIX = "git@bitbucket.org:";
    private static final int SEGMENTS_IN_ID = 3;
    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final String DESCRIPTOR_FILE_SHA_TYPE_FOR_TRS = "sha1";
    private static final Logger LOG = LoggerFactory.getLogger(ToolsApiServiceImpl.class);

    private static ToolDAO toolDAO = null;
    private static WorkflowDAO workflowDAO = null;
    private static DockstoreWebserviceConfiguration config = null;
    private static EntryVersionHelper<Tool, Tag, ToolDAO> toolHelper;
    private static TRSListener trsListener = null;
    private static EntryVersionHelper<Workflow, WorkflowVersion, WorkflowDAO> workflowHelper;

    public static void setToolDAO(ToolDAO toolDAO) {
        ToolsApiServiceImpl.toolDAO = toolDAO;
        ToolsApiServiceImpl.toolHelper = () -> toolDAO;
    }

    public static void setWorkflowDAO(WorkflowDAO workflowDAO) {
        ToolsApiServiceImpl.workflowDAO = workflowDAO;
        ToolsApiServiceImpl.workflowHelper = () -> workflowDAO;
    }

    public static void setTrsListener(TRSListener listener) {
        ToolsApiServiceImpl.trsListener = listener;
    }

    public static void setConfig(DockstoreWebserviceConfiguration config) {
        ToolsApiServiceImpl.config = config;
    }

    @Override
    public Response toolsIdGet(String id, SecurityContext securityContext, ContainerRequestContext value, Optional<User> user) {
        ParsedRegistryID parsedID = new ParsedRegistryID(id);
        Entry<?, ?> entry = getEntry(parsedID, user);
        return buildToolResponse(entry, null, false);
    }

    @Override
    public Response toolsIdVersionsGet(String id, SecurityContext securityContext, ContainerRequestContext value, Optional<User> user) {
        ParsedRegistryID parsedID = new ParsedRegistryID(id);
        Entry<?, ?> entry = getEntry(parsedID, user);
        return buildToolResponse(entry, null, true);
    }

    private Response buildToolResponse(Entry<?, ?> container, String version, boolean returnJustVersions) {
        Response response;
        if (container == null) {
            response = Response.status(Status.NOT_FOUND).build();
        } else if (!container.getIsPublished()) {
            response = Response.status(Status.UNAUTHORIZED).build();
        } else {
            io.openapi.model.Tool tool = ToolsImplCommon.convertEntryToTool(container, config);
            assert (tool != null);
            // filter out other versions if we're narrowing to a specific version
            if (version != null) {
                tool.getVersions().removeIf(v -> !v.getName().equals(version));
                if (tool.getVersions().size() != 1) {
                    response = Response.status(Status.NOT_FOUND).build();
                } else {
                    response = Response.ok(tool.getVersions().get(0)).build();
                }
            } else {
                if (returnJustVersions) {
                    response = Response.ok(tool.getVersions()).build();
                } else {
                    response = Response.ok(tool).build();
                }
            }
        }
        return response;
    }

    @Override
    public Response toolsIdVersionsVersionIdGet(String id, String versionId, SecurityContext securityContext, ContainerRequestContext value,
        Optional<User> user) {
        ParsedRegistryID parsedID = new ParsedRegistryID(id);
        String newVersionId;
        try {
            newVersionId = URLDecoder.decode(versionId, StandardCharsets.UTF_8.displayName());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        Entry<?, ?> entry = getEntry(parsedID, user);
        return buildToolResponse(entry, newVersionId, false);
    }

    public Entry<?, ?> getEntry(ParsedRegistryID parsedID, Optional<User> user) {
        Entry<?, ?> entry;
        String entryPath = parsedID.getPath();
        String entryName = parsedID.getToolName().isEmpty() ? null : parsedID.getToolName();
        if (entryName != null) {
            entryPath += "/" + parsedID.getToolName();
        }
        if (parsedID.toolType() == ParsedRegistryID.ToolType.TOOL) {
            entry = toolDAO.findByPath(entryPath, user.isEmpty());
        } else if (parsedID.toolType() == ParsedRegistryID.ToolType.WORKFLOW) {
            entry = workflowDAO.findByPath(entryPath, user.isEmpty(), BioWorkflow.class).orElse(null);
        } else if (parsedID.toolType() == ParsedRegistryID.ToolType.SERVICE) {
            entry = workflowDAO.findByPath(entryPath, user.isEmpty(), Service.class).orElse(null);
        } else {
            throw new UnsupportedOperationException("Tool type that should not be present found:" + parsedID.toolType());
        }
        if (entry != null && entry.getIsPublished()) {
            return entry;
        }
        if (entry != null && user.isPresent()) {
            checkUser(user.get(), entry);
            return entry;
        }
        return null;
    }

    @Override
    public Response toolsIdVersionsVersionIdTypeDescriptorGet(String type, String id, String versionId, SecurityContext securityContext,
        ContainerRequestContext value, Optional<User> user) {
        final Optional<DescriptorLanguage.FileType> fileType = DescriptorLanguage.getFileType(type);
        if (fileType.isEmpty()) {
            return Response.status(Status.NOT_FOUND).build();
        }
        return getFileByToolVersionID(id, versionId, fileType.get(), null,
            contextContainsPlainText(value) || StringUtils.containsIgnoreCase(type, "plain"), user);
    }

    @Override
    public Response toolsIdVersionsVersionIdTypeDescriptorRelativePathGet(String type, String id, String versionId, String relativePath,
        SecurityContext securityContext, ContainerRequestContext value, Optional<User> user) {
        if (type == null) {
            return Response.status(Status.BAD_REQUEST).build();
        }
        final Optional<DescriptorLanguage.FileType> fileType = DescriptorLanguage.getFileType(type);
        if (fileType.isEmpty()) {
            return Response.status(Status.NOT_FOUND).build();
        }
        return getFileByToolVersionID(id, versionId, fileType.get(), relativePath,
            contextContainsPlainText(value) || StringUtils.containsIgnoreCase(type, "plain"), user);
    }

    private boolean contextContainsPlainText(ContainerRequestContext value) {
        return value.getAcceptableMediaTypes().contains(MediaType.TEXT_PLAIN_TYPE);
    }

    @Override
    public Response toolsIdVersionsVersionIdTypeTestsGet(String type, String id, String versionId, SecurityContext securityContext,
        ContainerRequestContext value, Optional<User> user) {
        if (type == null) {
            return Response.status(Status.BAD_REQUEST).build();
        }
        final Optional<DescriptorLanguage.FileType> fileType = DescriptorLanguage.getFileType(type);
        if (fileType.isEmpty()) {
            return Response.status(Status.NOT_FOUND).build();
        }

        // The getFileType version never returns *TEST_JSON filetypes.  Linking CWL_TEST_JSON with DOCKSTORE_CWL and etc until solved.
        boolean plainTextResponse = contextContainsPlainText(value) || type.toLowerCase().contains("plain");

        final DescriptorLanguage.FileType fileTypeActual = fileType.get();
        final DescriptorLanguage descriptorLanguage = DescriptorLanguage.getDescriptorLanguage(fileTypeActual);
        final DescriptorLanguage.FileType testParamType = descriptorLanguage.getTestParamType();
        return getFileByToolVersionID(id, versionId, testParamType, null, plainTextResponse, user);
    }

    @Override
    public Response toolsIdVersionsVersionIdContainerfileGet(String id, String versionId, SecurityContext securityContext,
        ContainerRequestContext value, Optional<User> user) {
        // matching behaviour of the descriptor endpoint
        return getFileByToolVersionID(id, versionId, DOCKERFILE, null, contextContainsPlainText(value), user);
    }

    @SuppressWarnings({"checkstyle:ParameterNumber", "checkstyle:MethodLength"})
    @Override
    public Response toolsGet(String id, String alias, String toolClass, String registry, String organization, String name, String toolname,
        String description, String author, Boolean checker, String offset, Integer limit, SecurityContext securityContext,
        ContainerRequestContext value, Optional<User> user) {

        final int actualLimit = MoreObjects.firstNonNull(limit, DEFAULT_PAGE_SIZE);

        final Integer hashcode = new HashCodeBuilder().append(id).append(alias).append(toolClass).append(registry).append(organization).append(name)
                .append(toolname).append(description).append(author).append(checker).append(offset).append(limit)
                .append(user.orElseGet(User::new).getId()).build();
        final Optional<Response.ResponseBuilder> trsResponses = trsListener.getTrsResponse(hashcode);
        if (trsResponses.isPresent()) {
            return trsResponses.get().build();
        }

        final List<Entry<?, ?>> all = new ArrayList<>();

        // short circuit id and alias filters, these are a bit weird because they have a max of one result
        if (id != null) {
            ParsedRegistryID parsedID = new ParsedRegistryID(id);
            Entry<?, ?> entry = getEntry(parsedID, user);
            all.add(entry);
        } else if (alias != null) {
            all.add(toolDAO.getGenericEntryByAlias(alias));
        } else {
            if (toolClass == null || COMMAND_LINE_TOOL.equalsIgnoreCase(toolClass)) {
                //                all.addAll(toolDAO.findAllPublished());
            }
            if (toolClass == null || WORKFLOW.equalsIgnoreCase(toolClass)) {
                all.addAll(workflowDAO.findAllTrsPublished(
                        Optional.ofNullable(registry),
                        Optional.ofNullable(organization),
                        Optional.ofNullable(checker),
                        Optional.ofNullable(toolname),
                        Optional.ofNullable(author),
                        Optional.ofNullable(description),
                        actualLimit));
            }
            all.sort(Comparator.comparing(Entry::getGitUrl));
        }

        List<io.openapi.model.Tool> results = new ArrayList<>();
        for (Entry<?, ?> c : all) {
            // filters just for tools
            if (c instanceof Tool) {
                Tool tool = (Tool)c;
                // check each criteria. This sucks. Can we do this better with reflection? Or should we pre-convert?
                if (registry != null && tool.getRegistry() != null && !tool.getRegistry().contains(registry)) {
                    continue;
                }
                if (organization != null && tool.getNamespace() != null && !tool.getNamespace().contains(organization)) {
                    continue;
                }
                if (name != null && tool.getName() != null && !tool.getName().contains(name)) {
                    continue;
                }
                if (toolname != null && tool.getToolname() != null && !tool.getToolname().contains(toolname)) {
                    continue;
                }
                if (checker != null && checker) {
                    // tools are never checker workflows
                    continue;
                }
            }
            // filters just for tools
            if (c instanceof Workflow) {
                Workflow workflow = (Workflow)c;
                // check each criteria. This sucks. Can we do this better with reflection? Or should we pre-convert?
                if (registry != null && workflow.getSourceControl() != null && !workflow.getSourceControl().toString().contains(registry)) {
                    continue;
                }
                if (organization != null && workflow.getOrganization() != null && !workflow.getOrganization().contains(organization)) {
                    continue;
                }
                if (name != null && workflow.getRepository() != null && !workflow.getRepository().contains(name)) {
                    continue;
                }
                if (toolname != null && workflow.getWorkflowName() != null && !workflow.getWorkflowName().contains(toolname)) {
                    continue;
                }
                if (checker != null && workflow.isIsChecker() != checker) {
                    continue;
                }
            }
            // common filters between tools and workflows
            if (description != null && c.getDescription() != null && !c.getDescription().contains(description)) {
                continue;
            }
            if (author != null && c.getAuthor() != null && !c.getAuthor().contains(author)) {
                continue;
            }
            // if passing, for each container that matches the criteria, convert to standardised format and return
            io.openapi.model.Tool tool = ToolsImplCommon.convertEntryToTool(c, config);
            if (tool != null) {
                results.add(tool);
            }
        }

        List<List<io.openapi.model.Tool>> pagedResults = Lists.partition(results, actualLimit);
        int offsetInteger = 0;
        if (offset != null) {
            offsetInteger = Integer.parseInt(offset);
        }
        if (offsetInteger >= pagedResults.size()) {
            results = new ArrayList<>();
        } else {
            results = pagedResults.get(offsetInteger);
        }
        final Response.ResponseBuilder responseBuilder = Response.ok(results);
        responseBuilder.header("current_offset", offset);
        responseBuilder.header("current_limit", actualLimit);
        try {
            int port = config.getExternalConfig().getPort() == null ? -1 : Integer.parseInt(config.getExternalConfig().getPort());
            responseBuilder.header("self_link",
                new URI(config.getExternalConfig().getScheme(), null, config.getExternalConfig().getHostname(), port,
                    ObjectUtils.firstNonNull(config.getExternalConfig().getBasePath(), "") + value.getUriInfo().getRequestUri().getPath(),
                    value.getUriInfo().getRequestUri().getQuery(), null).normalize().toURL().toString());
            // construct links to other pages
            List<String> filters = new ArrayList<>();
            handleParameter(id, "id", filters);
            handleParameter(organization, "organization", filters);
            handleParameter(name, "name", filters);
            handleParameter(toolname, "toolname", filters);
            handleParameter(description, "description", filters);
            handleParameter(author, "author", filters);
            handleParameter(registry, "registry", filters);
            handleParameter(String.valueOf(actualLimit), "limit", filters);

            if (offsetInteger + 1 < pagedResults.size()) {
                URI nextPageURI = new URI(config.getExternalConfig().getScheme(), null, config.getExternalConfig().getHostname(), port,
                    ObjectUtils.firstNonNull(config.getExternalConfig().getBasePath(), "") + DockstoreWebserviceApplication.GA4GH_API_PATH_V2_BETA
                        + "/tools", Joiner.on('&').join(filters) + "&offset=" + (offsetInteger + 1), null).normalize();
                responseBuilder.header("next_page", nextPageURI.toURL().toString());
            }
            URI lastPageURI = new URI(config.getExternalConfig().getScheme(), null, config.getExternalConfig().getHostname(), port,
                ObjectUtils.firstNonNull(config.getExternalConfig().getBasePath(), "") + DockstoreWebserviceApplication.GA4GH_API_PATH_V2_BETA
                    + "/tools", Joiner.on('&').join(filters) + "&offset=" + (pagedResults.size() - 1), null).normalize();
            responseBuilder.header("last_page", lastPageURI.toURL().toString());

        } catch (URISyntaxException | MalformedURLException e) {
            throw new CustomWebApplicationException("Could not construct page links", HttpStatus.SC_BAD_REQUEST);
        }
        trsListener.loadTRSResponse(hashcode, responseBuilder);
        return responseBuilder.build();
    }

    private void handleParameter(String parameter, String queryName, List<String> filters) {
        if (parameter != null) {
            filters.add(queryName + "=" + parameter);
        }
    }

    /**
     * @param gitUrl       The git formatted url for the repo
     * @param reference    the git tag or branch
     * @param githubPrefix the prefix for the git formatted url to strip out
     * @param builtPrefix  the prefix to use to start the extracted prefix
     * @return the prefix to access these files
     */
    private static String extractHTTPPrefix(String gitUrl, String reference, String githubPrefix, String builtPrefix) {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(builtPrefix);
        final String substring = gitUrl.substring(githubPrefix.length(), gitUrl.lastIndexOf(".git"));
        urlBuilder.append(substring).append(builtPrefix.contains("bitbucket.org") ? "/raw/" : '/').append(reference);
        return urlBuilder.toString();
    }

    /**
     * @param registryId   registry id
     * @param versionIdParam    git reference
     * @param type         type of file
     * @param parameterPath if null, return the primary descriptor, if not null, return a specific file
     * @param unwrap       unwrap the file and present the descriptor sans wrapper model
     * @return a specific file wrapped in a response
     */
    private Response getFileByToolVersionID(String registryId, String versionIdParam, DescriptorLanguage.FileType type, String parameterPath,
        boolean unwrap, Optional<User> user) {

        // if a version is provided, get that version, otherwise return the newest
        ParsedRegistryID parsedID = new ParsedRegistryID(registryId);
        String versionId;
        try {
            versionId = URLDecoder.decode(versionIdParam, StandardCharsets.UTF_8.displayName());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        Entry<?, ?> entry = getEntry(parsedID, user);

        // check whether this is registered
        if (entry == null) {
            Response.StatusType status = getExtendedStatus(Status.NOT_FOUND, "incorrect id");
            return Response.status(status).build();
        }

        boolean showHiddenVersions = false;
        if (user.isPresent() && !AuthenticatedResourceInterface
                .userCannotRead(user.get(), entry)) {
            showHiddenVersions = true;
        }

        final io.openapi.model.Tool convertedTool = ToolsImplCommon.convertEntryToTool(entry, config, showHiddenVersions);

        String finalVersionId = versionId;
        if (convertedTool == null || convertedTool.getVersions() == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        final Optional<ToolVersion> convertedToolVersion = convertedTool.getVersions().stream()
            .filter(toolVersion -> toolVersion.getName().equalsIgnoreCase(finalVersionId)).findFirst();
        Optional<? extends Version<?>> entryVersion;
        if (entry instanceof Tool) {
            Tool toolEntry = (Tool)entry;
            entryVersion = toolEntry.getWorkflowVersions().stream().filter(toolVersion -> toolVersion.getName().equalsIgnoreCase(finalVersionId))
                .findFirst();
        } else {
            Workflow workflowEntry = (Workflow)entry;
            entryVersion = workflowEntry.getWorkflowVersions().stream()
                .filter(toolVersion -> toolVersion.getName().equalsIgnoreCase(finalVersionId)).findFirst();
        }

        if (entryVersion.isEmpty()) {
            Response.StatusType status = getExtendedStatus(Status.NOT_FOUND, "version not found");
            return Response.status(status).build();
        }

        String urlBuilt;
        String gitUrl = entry.getGitUrl();
        if (gitUrl.startsWith(GITHUB_PREFIX)) {
            urlBuilt = extractHTTPPrefix(gitUrl, entryVersion.get().getReference(), GITHUB_PREFIX, "https://raw.githubusercontent.com/");
        } else if (gitUrl.startsWith(BITBUCKET_PREFIX)) {
            urlBuilt = extractHTTPPrefix(gitUrl, entryVersion.get().getReference(), BITBUCKET_PREFIX, "https://bitbucket.org/");
        } else {
            LOG.error("Found a git url neither from BitBucket nor GitHub " + gitUrl);
            urlBuilt = "https://unimplemented_git_repository/";
        }

        if (convertedToolVersion.isPresent()) {
            final ToolVersion toolVersion = convertedToolVersion.get();
            if (type.getCategory().equals(DescriptorLanguage.FileTypeCategory.TEST_FILE)) {
                // this only works for test parameters associated with tools
                List<SourceFile> testSourceFiles = new ArrayList<>();
                try {
                    testSourceFiles.addAll(toolHelper.getAllSourceFiles(entry.getId(), versionId, type, user));
                } catch (CustomWebApplicationException e) {
                    LOG.warn("intentionally ignoring failure to get test parameters", e);
                }
                try {
                    testSourceFiles.addAll(workflowHelper.getAllSourceFiles(entry.getId(), versionId, type, user));
                } catch (CustomWebApplicationException e) {
                    LOG.warn("intentionally ignoring failure to get source files", e);
                }

                List<FileWrapper> toolTestsList = new ArrayList<>();

                for (SourceFile file : testSourceFiles) {
                    FileWrapper toolTests = ToolsImplCommon.sourceFileToToolTests(urlBuilt, file);
                    toolTestsList.add(toolTests);
                }
                return Response.status(Status.OK).type(unwrap ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON).entity(
                    unwrap ? toolTestsList.stream().map(FileWrapper::getContent).filter(Objects::nonNull).collect(Collectors.joining("\n"))
                        : toolTestsList).build();
            }
            if (type == DOCKERFILE) {
                Optional<SourceFile> potentialDockerfile = entryVersion.get().getSourceFiles().stream()
                    .filter(sourcefile -> sourcefile.getType() == DOCKERFILE).findFirst();
                if (potentialDockerfile.isPresent()) {
                    ExtendedFileWrapper dockerfile = new ExtendedFileWrapper();
                    //TODO: hook up file checksum here
                    dockerfile.setChecksum(convertToTRSChecksums(potentialDockerfile.get()));
                    dockerfile.setContent(potentialDockerfile.get().getContent());
                    dockerfile.setUrl(urlBuilt + ((Tag)entryVersion.get()).getDockerfilePath());
                    dockerfile.setOriginalFile(potentialDockerfile.get());
                    toolVersion.setContainerfile(true);
                    List<FileWrapper> containerfilesList = new ArrayList<>();
                    containerfilesList.add(dockerfile);
                    return Response.status(Status.OK).type(unwrap ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON)
                        .entity(unwrap ? dockerfile.getContent() : containerfilesList).build();
                }
            }
            String path;
            // figure out primary descriptors and use them if no relative path is specified
            if (entry instanceof Tool) {
                if (type == DOCKSTORE_WDL) {
                    path = ((Tag)entryVersion.get()).getWdlPath();
                } else if (type == DOCKSTORE_CWL) {
                    path = ((Tag)entryVersion.get()).getCwlPath();
                } else {
                    return Response.status(Status.NOT_FOUND).build();
                }
            } else {
                path = ((WorkflowVersion)entryVersion.get()).getWorkflowPath();
            }
            String searchPath;
            if (parameterPath != null) {
                searchPath = parameterPath;
            } else {
                searchPath = path;
            }

            final Set<SourceFile> sourceFiles = entryVersion.get().getSourceFiles();

            Optional<SourceFile> correctSourceFile = lookForFilePath(sourceFiles, searchPath, entryVersion.get().getWorkingDirectory());
            if (correctSourceFile.isPresent()) {
                SourceFile sourceFile = correctSourceFile.get();
                // annoyingly, test json and Dockerfiles include a fullpath whereas descriptors are just relative to the main descriptor,
                // so in this stream we need to standardize relative to the main descriptor
                final Path workingPath = Paths.get("/", entryVersion.get().getWorkingDirectory());
                final Path relativize = workingPath.relativize(Paths.get(StringUtils.prependIfMissing(sourceFile.getAbsolutePath(), "/")));
                String sourceFileUrl = urlBuilt + StringUtils.prependIfMissing(entryVersion.get().getWorkingDirectory(), "/") + StringUtils
                    .prependIfMissing(relativize.toString(), "/");
                ExtendedFileWrapper toolDescriptor = ToolsImplCommon.sourceFileToToolDescriptor(sourceFileUrl, sourceFile);
                return Response.status(Status.OK).type(unwrap ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON)
                    .entity(unwrap ? sourceFile.getContent() : toolDescriptor).build();
            }
        }
        Response.StatusType status = getExtendedStatus(Status.NOT_FOUND,
            "version found, but file not found (bad filename, invalid file, etc.)");
        return Response.status(status).build();
    }

    public static List<Checksum> convertToTRSChecksums(final SourceFile sourceFile) {
        List<Checksum> trsChecksums = new ArrayList<>();
        if (sourceFile.getChecksums() != null && !sourceFile.getChecksums().isEmpty()) {
            sourceFile.getChecksums().stream().forEach(checksum -> {
                Checksum trsChecksum = new Checksum();
                trsChecksum.setType(DESCRIPTOR_FILE_SHA_TYPE_FOR_TRS);
                trsChecksum.setChecksum(checksum.getChecksum());
                trsChecksums.add(trsChecksum);
            });
        }
        return trsChecksums;
    }

    private Response.StatusType getExtendedStatus(Status status, String additionalMessage) {
        return new Response.StatusType() {
            @Override
            public int getStatusCode() {
                return status.getStatusCode();
            }

            @Override
            public Status.Family getFamily() {
                return status.getFamily();
            }

            @Override
            public String getReasonPhrase() {
                return status.getReasonPhrase() + " : " + additionalMessage;
            }
        };
    }

    /**
     * Return a matching source file
     *
     * @param sourceFiles      files to look through
     * @param searchPathParam       file to look for, could be relative or absolute
     * @param workingDirectory working directory if relevant
     * @return
     */
    public Optional<SourceFile> lookForFilePath(Set<SourceFile> sourceFiles, String searchPathParam, String workingDirectory) {
        String targetPath;
        if (searchPathParam.startsWith("/")) {
            // treat searchPath as an absolute path
            targetPath = cleanRelativePath(searchPathParam).toLowerCase();
        } else {
            // treat searchPath as a relative path
            String relativeSearchPath = cleanRelativePath(searchPathParam);
            // assemble normalized absolute path
            targetPath = Paths.get(workingDirectory, relativeSearchPath).normalize().toString().toLowerCase();
        }

        // assembled map from paths normalized relative to the root (not the main descriptor) to files
        Map<String, SourceFile> calculatedPathMap = sourceFiles.stream().collect(Collectors.toMap(sourceFile -> {
            return cleanRelativePath(sourceFile.getAbsolutePath()).toLowerCase();
        }, sourceFile -> sourceFile));

        return Optional.ofNullable(calculatedPathMap.get(targetPath));
    }

    @Override
    public Response toolsIdVersionsVersionIdTypeFilesGet(String type, String id, String versionId, SecurityContext securityContext,
        ContainerRequestContext containerRequestContext, Optional<User> user) {
        ParsedRegistryID parsedID = new ParsedRegistryID(id);
        Entry<?, ?> entry = getEntry(parsedID, user);
        List<String> primaryDescriptorPaths = new ArrayList<>();
        if (entry instanceof Workflow) {
            Workflow workflow = (Workflow)entry;
            Set<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
            Optional<WorkflowVersion> first = workflowVersions.stream()
                .filter(workflowVersion -> workflowVersion.getName().equals(versionId)).findFirst();
            if (first.isPresent()) {
                WorkflowVersion workflowVersion = first.get();
                // Matching the workflow path in a workflow automatically indicates that the file is a primary descriptor
                primaryDescriptorPaths.add(workflowVersion.getWorkflowPath());
                Set<SourceFile> sourceFiles = workflowVersion.getSourceFiles();
                List<ToolFile> toolFiles = getToolFiles(sourceFiles, primaryDescriptorPaths, type, workflowVersion.getWorkingDirectory());
                return Response.ok().entity(toolFiles).build();
            } else {
                return Response.noContent().build();
            }
        } else if (entry instanceof Tool) {
            Tool tool = (Tool)entry;
            Set<Tag> versions = tool.getWorkflowVersions();
            Optional<Tag> first = versions.stream().filter(tag -> tag.getName().equals(versionId)).findFirst();
            if (first.isPresent()) {
                Tag tag = first.get();
                // Matching the CWL path or WDL path in a tool automatically indicates that the file is a primary descriptor
                primaryDescriptorPaths.add(tag.getCwlPath());
                primaryDescriptorPaths.add(tag.getWdlPath());
                Set<SourceFile> sourceFiles = tag.getSourceFiles();
                List<ToolFile> toolFiles = getToolFiles(sourceFiles, primaryDescriptorPaths, type, tag.getWorkingDirectory());
                return Response.ok().entity(toolFiles).build();
            } else {
                return Response.noContent().build();
            }
        } else {
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    /**
     * Converts SourceFile.FileType to ToolFile.FileTypeEnum
     *
     * @param fileType The SourceFile.FileType
     * @return The ToolFile.FileTypeEnum
     */
    private static ToolFile.FileTypeEnum fileTypeToToolFileFileTypeEnum(DescriptorLanguage.FileType fileType) {
        if (fileType.getCategory() == DescriptorLanguage.FileTypeCategory.TEST_FILE) {
            return ToolFile.FileTypeEnum.TEST_FILE;
        } else if (fileType.getCategory() == DescriptorLanguage.FileTypeCategory.CONTAINERFILE) {
            return ToolFile.FileTypeEnum.CONTAINERFILE;
        } else if (fileType.getCategory() == DescriptorLanguage.FileTypeCategory.SECONDARY_DESCRIPTOR) {
            return ToolFile.FileTypeEnum.SECONDARY_DESCRIPTOR;
        } else if (fileType.getCategory() == DescriptorLanguage.FileTypeCategory.PRIMARY_DESCRIPTOR) {
            return ToolFile.FileTypeEnum.PRIMARY_DESCRIPTOR;
        } else if (fileType.getCategory() == DescriptorLanguage.FileTypeCategory.GENERIC_DESCRIPTOR) {
            return ToolFile.FileTypeEnum.SECONDARY_DESCRIPTOR;
        }
        return ToolFile.FileTypeEnum.OTHER;
    }

    /**
     * Converts a list of SourceFile to a list of ToolFile.
     *
     * @param sourceFiles    The list of SourceFile to convert
     * @param mainDescriptor The main descriptor path, used to determine if the file is a primary or secondary descriptor
     * @return A list of ToolFile for the Tool
     */
    public static List<ToolFile> getToolFiles(Set<SourceFile> sourceFiles, List<String> mainDescriptor, String type, String workingDirectory) {
        // Filters the source files to only show the ones that are possibly relevant to the type (CWL or WDL or NFL)
        final DescriptorLanguage descriptorLanguage = DescriptorLanguage.convertShortStringToEnum(type);
        List<SourceFile> filteredSourceFiles = sourceFiles.stream()
            .filter(sourceFile -> descriptorLanguage.isRelevantFileType(sourceFile.getType())).collect(Collectors.toList());

        final Path path = Paths.get("/" + workingDirectory);
        return filteredSourceFiles.stream().map(file -> {
            ToolFile toolFile = new ToolFile();
            toolFile.setPath(path.relativize(Paths.get(file.getAbsolutePath())).toString());
            ToolFile.FileTypeEnum fileTypeEnum = fileTypeToToolFileFileTypeEnum(file.getType());
            if (fileTypeEnum.equals(ToolFile.FileTypeEnum.SECONDARY_DESCRIPTOR) && mainDescriptor.contains(file.getPath())) {
                fileTypeEnum = ToolFile.FileTypeEnum.PRIMARY_DESCRIPTOR;
            }
            toolFile.setFileType(fileTypeEnum);
            return toolFile;
        }).sorted(Comparator.comparing(ToolFile::getPath)).collect(Collectors.toList());
    }

    private String cleanRelativePath(String relativePath) {
        String cleanRelativePath = StringUtils.removeStart(relativePath, "./");
        return StringUtils.removeStart(cleanRelativePath, "/");
    }

    /**
     * Used to parse localised IDs (no URL)
     * If tool, the id will look something like "registry.hub.docker.com/sequenza/sequenza"
     * If workflow, the id will look something like "#workflow/DockstoreTestUser/dockstore-whalesay/dockstore-whalesay-wdl"
     * If service, the id will look something like "#service/DockstoreTestUser/dockstore-whalesay/dockstore-whalesay-wdl"
     * Both cases have registry/organization/name/toolName but workflows have a "#workflow" prepended to it
     * and services have a "#service" prepended to it.
     */
    public static class ParsedRegistryID {
        private enum ToolType { TOOL, SERVICE, WORKFLOW };
        private ToolType type = ToolType.TOOL;
        private final String registry;
        private final String organization;
        private final String name;
        private final String toolName;

        public ParsedRegistryID(String paramId) {
            String id;
            try {
                id = URLDecoder.decode(paramId, StandardCharsets.UTF_8.displayName());
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
            List<String> textSegments = Splitter.on('/').omitEmptyStrings().splitToList(id);
            List<String> list = new ArrayList<>(textSegments);
            String firstTextSegment = list.get(0);
            if (WORKFLOW_PREFIX.equalsIgnoreCase(firstTextSegment)) {
                list.remove(0); // Remove #workflow from ArrayList to make parsing similar to tool
                type = ToolType.WORKFLOW;
            }
            if (SERVICE_PREFIX.equalsIgnoreCase(firstTextSegment)) {
                list.remove(0); // Remove #service from ArrayList to make parsing similar to tool
                type = ToolType.SERVICE;
            }
            checkToolId(list);
            registry = list.get(0);
            organization = list.get(1);
            name = list.get(2);
            toolName = list.size() > SEGMENTS_IN_ID ? list.get(SEGMENTS_IN_ID) : "";
        }

        /**
         * This checks if the GA4GH toolId string segments provided by the user is of proper length
         * If it is not the proper length, returns an Error response object similar to what's defined for the
         * 404 response in the GA4GH swagger.yaml
         *
         * @param toolIdStringSegments The toolId provided by the user which was split into string segments
         */
        private void checkToolId(List<String> toolIdStringSegments) {
            if (toolIdStringSegments.size() < SEGMENTS_IN_ID) {
                Error error = new Error();
                error.setCode(HttpStatus.SC_BAD_REQUEST);
                error.setMessage("Tool ID should have at least 3 separate segments, seperated by /");
                Response errorResponse = Response.status(HttpStatus.SC_BAD_REQUEST).entity(error).type(MediaType.APPLICATION_JSON).build();
                throw new WebApplicationException(errorResponse);
            }
        }

        public String getRegistry() {
            return registry;
        }

        public String getOrganization() {
            return organization;
        }

        public String getName() {
            return name;
        }

        public String getToolName() {
            return toolName;
        }

        /**
         * Get an internal path
         *
         * @return an internal path, usable only if we know if we have a tool or workflow
         */
        public String getPath() {
            return registry + "/" + organization + "/" + name;
        }

        public ToolType toolType() {
            return type;
        }
    }
}
