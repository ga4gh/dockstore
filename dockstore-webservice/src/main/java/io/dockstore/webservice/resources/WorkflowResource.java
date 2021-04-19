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

package io.dockstore.webservice.resources;

import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.Beta;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.DescriptorLanguage.FileType;
import io.dockstore.common.SourceControl;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.api.PublishRequest;
import io.dockstore.webservice.api.StarRequest;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.Checksum;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Image;
import io.dockstore.webservice.core.LambdaEvent;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.SourceControlConverter;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.TokenType;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowMode;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.helpers.AliasHelper;
import io.dockstore.webservice.helpers.EntryVersionHelper;
import io.dockstore.webservice.helpers.FileFormatHelper;
import io.dockstore.webservice.helpers.MetadataResourceHelper;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.helpers.SourceCodeRepoFactory;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.helpers.StateManagerMode;
import io.dockstore.webservice.helpers.URIHelper;
import io.dockstore.webservice.helpers.ZenodoHelper;
import io.dockstore.webservice.jdbi.BioWorkflowDAO;
import io.dockstore.webservice.jdbi.EntryDAO;
import io.dockstore.webservice.jdbi.FileFormatDAO;
import io.dockstore.webservice.jdbi.LabelDAO;
import io.dockstore.webservice.jdbi.ServiceEntryDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.VersionDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dockstore.webservice.languages.LanguageHandlerFactory;
import io.dockstore.webservice.languages.LanguageHandlerInterface;
import io.dockstore.webservice.permissions.Permission;
import io.dockstore.webservice.permissions.PermissionsInterface;
import io.dockstore.webservice.permissions.Role;
import io.dockstore.webservice.permissions.SharedWorkflows;
import io.dropwizard.auth.Auth;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import io.swagger.api.impl.ToolsImplCommon;
import io.swagger.jaxrs.PATCH;
import io.swagger.model.DescriptorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.zenodo.client.ApiClient;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.common.DescriptorLanguage.CWL;
import static io.dockstore.common.DescriptorLanguage.WDL;
import static io.dockstore.webservice.Constants.JWT_SECURITY_DEFINITION_NAME;
import static io.dockstore.webservice.Constants.OPTIONAL_AUTH_MESSAGE;
import static io.dockstore.webservice.core.WorkflowMode.DOCKSTORE_YML;
import static io.dockstore.webservice.resources.ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME;
import static io.dockstore.webservice.resources.ResourceConstants.VERSION_PAGINATION_LIMIT;

/**
 * TODO: remember to document new security concerns for hosted vs other workflows
 *
 * @author dyuen
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "workflows", description = ResourceConstants.WORKFLOWS)
public class WorkflowResource extends AbstractWorkflowResource<Workflow>
    implements EntryVersionHelper<Workflow, WorkflowVersion, WorkflowDAO>, StarrableResourceInterface,
    SourceControlResourceInterface {
    public static final String FROZEN_VERSION_REQUIRED = "Frozen version required to generate DOI";
    public static final String NO_ZENDO_USER_TOKEN = "Could not get Zenodo token for user";
    public static final String SC_REGISTRY_ACCESS_MESSAGE = "User does not have access to the given source control registry.";
    private static final String CWL_CHECKER = "_cwl_checker";
    private static final String WDL_CHECKER = "_wdl_checker";
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowResource.class);
    private static final String PAGINATION_LIMIT = "100";
    private static final String ALIASES = "aliases";
    private static final String VALIDATIONS = "validations";
    private static final String IMAGES = "images";
    private static final String VERSIONS = "versions";
    private static final String SHA_TYPE_FOR_SOURCEFILES = "SHA-1";

    private final ToolDAO toolDAO;
    private final LabelDAO labelDAO;
    private final FileFormatDAO fileFormatDAO;
    private final ServiceEntryDAO serviceEntryDAO;
    private final BioWorkflowDAO bioWorkflowDAO;
    private final VersionDAO versionDAO;

    private final PermissionsInterface permissionsInterface;
    private final String zenodoUrl;
    private final String zenodoClientID;
    private final String zenodoClientSecret;
    private final String dashboardPrefix;

    private final String dockstoreUrl;
    private final String dockstoreGA4GHBaseUrl;

    public WorkflowResource(HttpClient client, SessionFactory sessionFactory, PermissionsInterface permissionsInterface,
            EntryResource entryResource, DockstoreWebserviceConfiguration configuration) {
        super(client, sessionFactory, entryResource, configuration);
        this.toolDAO = new ToolDAO(sessionFactory);
        this.labelDAO = new LabelDAO(sessionFactory);
        this.serviceEntryDAO = new ServiceEntryDAO(sessionFactory);
        this.bioWorkflowDAO = new BioWorkflowDAO(sessionFactory);
        this.fileFormatDAO = new FileFormatDAO(sessionFactory);
        this.versionDAO = new VersionDAO(sessionFactory);

        this.permissionsInterface = permissionsInterface;


        zenodoUrl = configuration.getZenodoUrl();
        zenodoClientID = configuration.getZenodoClientID();
        zenodoClientSecret = configuration.getZenodoClientSecret();
        dashboardPrefix = configuration.getDashboard();

        dockstoreUrl = URIHelper.createBaseUrl(configuration.getExternalConfig().getScheme(),
                configuration.getExternalConfig().getHostname(), configuration.getExternalConfig().getUiPort());

        try {
            dockstoreGA4GHBaseUrl = ToolsImplCommon.baseURL(configuration);
        } catch (URISyntaxException e) {
            LOG.error("Could create Dockstore base URL. Error is " + e.getMessage(), e);
            throw new CustomWebApplicationException("Could create Dockstore base URL. "
                    + "Error is " + e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * TODO: this should not be a GET either
     *
     * @param user
     * @param workflowId
     * @return
     */
    @GET
    @Path("/{workflowId}/restub")
    @Timed
    @UnitOfWork
    @Operation(operationId = "restub", summary = "Restub a workflow", description = "Restub a workflow", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Restub a workflow", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Restubs a full, unpublished workflow.", response = Workflow.class)
    public Workflow restub(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        // Check that workflow is valid to restub
        if (workflow.getIsPublished()) {
            throw new CustomWebApplicationException("A workflow must be unpublished to restub.", HttpStatus.SC_BAD_REQUEST);
        }

        if (workflow.isIsChecker()) {
            throw new CustomWebApplicationException("A checker workflow cannot be restubed.", HttpStatus.SC_BAD_REQUEST);
        }

        checkNotHosted(workflow);
        checkCanWriteWorkflow(user, workflow);

        workflow.setMode(WorkflowMode.STUB);

        // go through and delete versions for a stub
        for (WorkflowVersion version : workflow.getWorkflowVersions()) {
            workflowVersionDAO.delete(version);
        }
        workflow.setActualDefaultVersion(null);
        workflow.getWorkflowVersions().clear();

        // Do we maintain the checker workflow association? For now we won't
        workflow.setCheckerWorkflow(null);

        PublicStateManager.getInstance().handleIndexUpdate(workflow, StateManagerMode.DELETE);
        return workflow;

    }

    /**
     * Logs a refresh statement with the workflow's descriptor language if workflow is a FULL workflow.
     * These logs will be monitored by CloudWatch and displayed on Grafana.
     * @param workflow                workflow that is being refreshed
     */
    private void logFullWorkflowRefresh(final Workflow workflow) {
        if (workflow.getMode() == WorkflowMode.FULL) {
            LOG.info(String.format("%s: Refreshing %s workflow named %s", dashboardPrefix, workflow.getDescriptorType(), workflow.getEntryPath()));
        }
    }

    /**
     * Logs a version refresh statement with the workflow's descriptor language if workflow is a FULL workflow .
     * These logs will be monitored by CloudWatch and displayed on Grafana.
     * @param workflow
     * @param workflowVersion
     */
    private void logWorkflowVersionRefresh(final Workflow workflow, final String workflowVersion) {
        if (workflow.getMode() == WorkflowMode.FULL) {
            LOG.info(String.format("%s: Refreshing version %s for %s workflow named %s", dashboardPrefix, workflowVersion, workflow.getDescriptorType(), workflow.getEntryPath()));
        }
    }

    @GET
    @Path("/{workflowId}/refresh")
    @Timed
    @UnitOfWork
    @Operation(operationId = "refresh", description = "Refresh one particular workflow.", security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "refresh", value = "Refresh one particular workflow.", notes = "Full refresh", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class)
    public Workflow refresh(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "completely refresh all versions, even if they have not changed", defaultValue = "true") @QueryParam("hardRefresh") @DefaultValue("true") Boolean hardRefresh) {
        Workflow workflow = refreshWorkflow(user, workflowId, Optional.empty(), hardRefresh);
        EntryVersionHelper.removeSourceFilesFromEntry(workflow, sessionFactory);
        return workflow;
    }

    @GET
    @Path("/{workflowId}/refresh/{version}")
    @Timed
    @UnitOfWork
    @Operation(operationId = "refreshVersion", description = "Refresh one particular workflow version.", security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "refreshVersion", value = "Refresh one particular workflow version.", notes = "Refresh existing or new version of a workflow.", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class)
    public Workflow refreshVersion(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId,
            @ApiParam(value = "version", required = true) @PathParam("version") String version,
            @ApiParam(value = "completely refresh version, even if it has not changed", defaultValue = "true") @QueryParam("hardRefresh") @DefaultValue("true") Boolean hardRefresh) {
        if (version == null || version.isBlank()) {
            String msg = "Version is a required field for this endpoint.";
            LOG.error(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }
        Workflow workflow = refreshWorkflow(user, workflowId, Optional.of(version), hardRefresh);
        EntryVersionHelper.removeSourceFilesFromEntry(workflow, sessionFactory);
        return workflow;
    }

    /**
     * Refresh a workflow, pulling in all versions from remote sources
     * Optionally pass version to only refresh a specific version
     * @param user User who made call
     * @param workflowId ID of workflow
     * @param version Name of the workflow version
     * @param hardRefresh refresh all versions, even if no changes
     * @return Updated workflow
     */
    private Workflow refreshWorkflow(User user, Long workflowId, Optional<String> version, boolean hardRefresh) {
        Workflow existingWorkflow = workflowDAO.findById(workflowId);
        checkEntry(existingWorkflow);
        checkUserOwnsEntry(user, existingWorkflow);
        checkNotHosted(existingWorkflow);
        checkNotService(existingWorkflow);
        // get a live user for the following
        user = userDAO.findById(user.getId());
        // Update user data
        user.updateUserMetadata(tokenDAO);

        // Set up source code interface and ensure token is set up
        final SourceCodeRepoInterface sourceCodeRepo = getSourceCodeRepoInterface(existingWorkflow.getGitUrl(), user);

        // If this point has been reached, then the workflow will be a FULL workflow (and not a STUB)
        if (!Objects.equals(existingWorkflow.getMode(), DOCKSTORE_YML)) {
            existingWorkflow.setMode(WorkflowMode.FULL);
        }

        // Look for checker workflows to associate with if applicable
        if (existingWorkflow instanceof BioWorkflow && !existingWorkflow.isIsChecker() && existingWorkflow.getDescriptorType() == CWL || existingWorkflow.getDescriptorType() == WDL) {
            String workflowName = existingWorkflow.getWorkflowName() == null ? "" : existingWorkflow.getWorkflowName();
            String checkerWorkflowName = "/" + workflowName + (existingWorkflow.getDescriptorType() == CWL ? CWL_CHECKER : WDL_CHECKER);
            BioWorkflow byPath = workflowDAO.findByPath(existingWorkflow.getPath() + checkerWorkflowName, false, BioWorkflow.class).orElse(null);
            if (byPath != null && existingWorkflow.getCheckerWorkflow() == null) {
                existingWorkflow.setCheckerWorkflow(byPath);
            }
        }

        if (version.isEmpty()) {
            logFullWorkflowRefresh(existingWorkflow);
        } else {
            logWorkflowVersionRefresh(existingWorkflow, version.get());
        }

        // Create a new workflow based on the current state of the Git repository
        final Workflow newWorkflow = sourceCodeRepo
                .createWorkflowFromGitRepository(existingWorkflow.getOrganization() + '/' + existingWorkflow.getRepository(), Optional.of(existingWorkflow), version, hardRefresh);
        existingWorkflow.getUsers().add(user);

        // Use new workflow to update existing workflow
        updateDBWorkflowWithSourceControlWorkflow(existingWorkflow, newWorkflow, user, version);
        // Update file formats in each version and then the entry
        FileFormatHelper.updateFileFormats(existingWorkflow, newWorkflow.getWorkflowVersions(), fileFormatDAO, true);

        // Keep this code that updates the existing workflow BEFORE refreshing its checker workflow below. Refreshing the checker workflow will eventually call
        // EntryVersionHelper.removeSourceFilesFromEntry() which performs a session.flush and commits to the db. It's important the parent workflow is updated completely before committing to the db..
        existingWorkflow.getWorkflowVersions().forEach(Version::updateVerified);
        String repositoryId = sourceCodeRepo.getRepositoryId(existingWorkflow);
        sourceCodeRepo.setDefaultBranchIfNotSet(existingWorkflow, repositoryId);
        existingWorkflow.syncMetadataWithDefault();

        // Refresh checker workflow
        if (!existingWorkflow.isIsChecker() && existingWorkflow.getCheckerWorkflow() != null) {
            if (version.isEmpty()) {
                refresh(user, existingWorkflow.getCheckerWorkflow().getId(), hardRefresh);
            } else {
                refreshVersion(user, existingWorkflow.getCheckerWorkflow().getId(), version.get(), hardRefresh);
            }
        }

        PublicStateManager.getInstance().handleIndexUpdate(existingWorkflow, StateManagerMode.UPDATE);
        return existingWorkflow;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{workflowId}")
    @Operation(operationId = "getWorkflow", description = "Retrieve a workflow", security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "getWorkflow", value = "Retrieve a workflow", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class, notes = "This is one of the few endpoints that returns the user object with populated properties (minus the userProfiles property)")
    public Workflow getWorkflow(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId, @ApiParam(value = "Comma-delimited list of fields to include: " + VALIDATIONS + ", " + ALIASES) @QueryParam("include") String include) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkCanRead(user, workflow);
        // This somehow forces users to get loaded
        Hibernate.initialize(workflow.getUsers());
        Hibernate.initialize(workflow.getAliases());
        // This should be removed once we have a workflows/{workflowId}/version/{versionId} endpoint
        workflow.getWorkflowVersions().forEach(version -> Hibernate.initialize(version.getVersionMetadata().getParsedInformationSet()));
        initializeAdditionalFields(include, workflow);
        return workflow;
    }

    @GET
    @Path("/{workflowId}/workflowVersions")
    @UnitOfWork
    @ApiOperation(nickname = "getWorkflowVersions", value = "Return first 200 versions in an entry", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = WorkflowVersion.class, responseContainer = "List")
    @Operation(operationId = "getWorkflowVersions", description = "Return first 200 versions in an entry", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiResponse(responseCode = HttpStatus.SC_OK + "", description = "Get list workflow versions in a workflow", content = @Content(
            mediaType = "application/json",
            array = @ArraySchema(schema = @Schema(implementation = WorkflowVersion.class))))
    @ApiResponse(responseCode = HttpStatus.SC_BAD_REQUEST + "", description = "Bad Request")
    public Set<WorkflowVersion> getWorkflowVersions(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam(value = "workflowID", required = true) @Parameter(name = "workflowId", description = "id of the worflow", required = true, in = ParameterIn.PATH) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkCanRead(user, workflow);

        List<WorkflowVersion> versions = this.workflowVersionDAO.getWorkflowVersionsByWorkflowId(workflow.getId(), VERSION_PAGINATION_LIMIT, 0);
        SortedSet<WorkflowVersion> setOfVersions = new TreeSet<>(versions);
        return setOfVersions;
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/labels")
    @Operation(operationId = "updateLabels", description = "Update the labels linked to a workflow.", security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "updateLabels", value = "Update the labels linked to a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Labels are alphanumerical (case-insensitive and may contain internal hyphens), given in a comma-delimited list.", response = Workflow.class)
    public Workflow updateLabels(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Tool to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "Comma-delimited list of labels.", required = true) @QueryParam("labels") String labelStrings,
        @ApiParam(value = "This is here to appease Swagger. It requires PUT methods to have a body, even if it is empty. Please leave it empty.") String emptyBody) {
        Workflow workflow = this.updateLabels(user, workflowId, labelStrings, labelDAO);
        Hibernate.initialize(workflow.getWorkflowVersions());
        return workflow;
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}")
    @Operation(operationId = "updateWorkflow", description = "Update the workflow with the given workflow.", security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "updateWorkflow", value = "Update the workflow with the given workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class,
            notes = "Updates descriptor type (if stub), default workflow path, default file path, and default version")
    public Workflow updateWorkflow(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "Workflow with updated information", required = true) Workflow workflow) {
        Workflow wf = workflowDAO.findById(workflowId);
        checkEntry(wf);
        checkNotHosted(wf);
        checkCanWriteWorkflow(user, wf);

        Workflow duplicate = workflowDAO.findByPath(workflow.getWorkflowPath(), false, BioWorkflow.class).orElse(null);

        if (duplicate != null && duplicate.getId() != workflowId) {
            LOG.info(user.getUsername() + ": " + "duplicate workflow found: {}" + workflow.getWorkflowPath());
            throw new CustomWebApplicationException("Workflow " + workflow.getWorkflowPath() + " already exists.",
                HttpStatus.SC_BAD_REQUEST);
        }

        updateInfo(wf, workflow);
        wf.getWorkflowVersions().stream().forEach(workflowVersion -> workflowVersion.setSynced(false));
        Workflow result = workflowDAO.findById(workflowId);
        checkEntry(result);
        PublicStateManager.getInstance().handleIndexUpdate(result, StateManagerMode.UPDATE);
        return result;

    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/defaultVersion")
    @Operation(operationId = "updateDefaultVersion", description = "Update the default version of a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Update the default version of a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class, nickname = "updateWorkflowDefaultVersion")
    public Workflow updateDefaultVersion(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "Version name to set as default", required = true) String version) {
        return (Workflow)updateDefaultVersionHelper(version, workflowId, user);
    }

    // Used to update workflow manually (not refresh)
    private void updateInfo(Workflow oldWorkflow, Workflow newWorkflow) {
        // If workflow is FULL and descriptor type is being changed throw an error
        if (Objects.equals(oldWorkflow.getMode(), WorkflowMode.FULL) && !Objects
            .equals(oldWorkflow.getDescriptorType(), newWorkflow.getDescriptorType())) {
            throw new CustomWebApplicationException("You cannot change the descriptor type of a FULL workflow.", HttpStatus.SC_BAD_REQUEST);
        }

        // Only copy workflow type if old workflow is a STUB
        if (Objects.equals(oldWorkflow.getMode(), WorkflowMode.STUB)) {
            oldWorkflow.setDescriptorType(newWorkflow.getDescriptorType());
        }

        oldWorkflow.setDefaultWorkflowPath(newWorkflow.getDefaultWorkflowPath());
        oldWorkflow.setDefaultTestParameterFilePath(newWorkflow.getDefaultTestParameterFilePath());
        oldWorkflow.setForumUrl(newWorkflow.getForumUrl());
        if (newWorkflow.getDefaultVersion() != null) {
            if (!oldWorkflow.checkAndSetDefaultVersion(newWorkflow.getDefaultVersion()) && newWorkflow.getMode() != WorkflowMode.STUB) {
                throw new CustomWebApplicationException("Workflow version does not exist.", HttpStatus.SC_BAD_REQUEST);
            }
        }
    }

    /**
     * Get the Zenodo access token and refresh it if necessary
     * @param user Dockstore with Zenodo account
     */
    private List<Token> checkOnZenodoToken(User user) {
        List<Token> tokens = tokenDAO.findZenodoByUserId(user.getId());
        if (!tokens.isEmpty()) {
            Token zenodoToken = tokens.get(0);

            // Check that token is an hour old
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime updateTime = zenodoToken.getDbUpdateDate().toLocalDateTime();
            if (now.isAfter(updateTime.plusHours(1).minusMinutes(1))) {
                LOG.info("Refreshing the Zenodo Token");
                String refreshUrl = zenodoUrl + "/oauth/token";
                String payload = "client_id=" + zenodoClientID + "&client_secret=" + zenodoClientSecret
                        + "&grant_type=refresh_token&refresh_token=" + zenodoToken.getRefreshToken();
                refreshToken(refreshUrl, zenodoToken, client, tokenDAO, null, null, payload);
            }
        }
        return tokenDAO.findByUserId(user.getId());
    }

    @PUT
    @Timed
    @UnitOfWork
    @Beta
    @Path("/{workflowId}/requestDOI/{workflowVersionId}")
    @Operation(operationId = "requestDOIForWorkflowVersion", description = "Request a DOI for this version of a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Request a DOI for this version of a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = WorkflowVersion.class, responseContainer = "List")
    public Set<WorkflowVersion> requestDOIForWorkflowVersion(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "workflowVersionId", required = true) @PathParam("workflowVersionId") Long workflowVersionId,
        @ApiParam(value = "This is here to appease Swagger. It requires PUT methods to have a body, even if it is empty. Please leave it empty.") String emptyBody) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkUserOwnsEntry(user, workflow);

        WorkflowVersion workflowVersion = workflowVersionDAO.findById(workflowVersionId);
        if (workflowVersion == null) {
            LOG.error(user.getUsername() + ": could not find version: " + workflow.getWorkflowPath());
            throw new CustomWebApplicationException("Version not found.", HttpStatus.SC_BAD_REQUEST);

        }

        //Only issue doi if workflow is frozen.
        final String workflowNameAndVersion = workflowNameAndVersion(workflow, workflowVersion);
        if (!workflowVersion.isFrozen()) {
            LOG.error(user.getUsername() + ": Could not generate DOI for " +  workflowNameAndVersion + ". " + FROZEN_VERSION_REQUIRED);
            throw new CustomWebApplicationException("Could not generate DOI for " + workflowNameAndVersion + ". " + FROZEN_VERSION_REQUIRED + ". ", HttpStatus.SC_BAD_REQUEST);
        }

        List<Token> tokens = checkOnZenodoToken(user);
        Token zenodoToken = Token.extractToken(tokens, TokenType.ZENODO_ORG);

        // Update the zenodo token in case it changed. This handles the case where the token has been changed but an error occurred, so the token in the database was not updated
        if (zenodoToken != null) {
            tokenDAO.update(zenodoToken);
            sessionFactory.getCurrentSession().getTransaction().commit();
            sessionFactory.getCurrentSession().beginTransaction();
        }

        if (zenodoToken == null) {
            LOG.error(NO_ZENDO_USER_TOKEN + " " + user.getUsername());
            throw new CustomWebApplicationException(NO_ZENDO_USER_TOKEN + " " + user.getUsername(), HttpStatus.SC_BAD_REQUEST);
        }
        final String zenodoAccessToken = zenodoToken.getContent();

        //TODO: Determine whether workflow DOIStatus is needed; we don't use it
        //E.g. Version.DOIStatus.CREATED

        ApiClient zenodoClient = new ApiClient();
        // for testing, either 'https://sandbox.zenodo.org/api' or 'https://zenodo.org/api' is the first parameter
        String zenodoUrlApi = zenodoUrl + "/api";
        zenodoClient.setBasePath(zenodoUrlApi);
        zenodoClient.setApiKey(zenodoAccessToken);

        registerZenodoDOIForWorkflow(zenodoClient, workflow, workflowVersion, user);

        Workflow result = workflowDAO.findById(workflowId);
        checkEntry(result);
        PublicStateManager.getInstance().handleIndexUpdate(result, StateManagerMode.UPDATE);
        return result.getWorkflowVersions();

    }

    /**
     * Register a Zenodo DOI for the workflow and workflow version
     * @param zenodoClient Client for interacting with Zenodo server
     * @param workflow    workflow for which DOI is registered
     * @param workflowVersion workflow version for which DOI is registered
     * @param user user authenticated to issue a DOI for the workflow
     */
    private void registerZenodoDOIForWorkflow(ApiClient zenodoClient, Workflow workflow, WorkflowVersion workflowVersion, User user) {

        // Create Dockstore workflow URL (e.g. https://dockstore.org/workflows/github.com/DataBiosphere/topmed-workflows/UM_variant_caller_wdl)
        String workflowUrl = MetadataResourceHelper.createWorkflowURL(workflow);

        ZenodoHelper.ZenodoDoiResult zenodoDoiResult = ZenodoHelper.registerZenodoDOI(zenodoClient, workflow,
                workflowVersion, workflowUrl, dockstoreGA4GHBaseUrl, dockstoreUrl, this);

        workflowVersion.setDoiURL(zenodoDoiResult.getDoiUrl());
        workflow.setConceptDoi(zenodoDoiResult.getConceptDoi());
        // Only add the alias to the workflow version after publishing the DOI succeeds
        // Otherwise if the publish call fails we will have added an alias
        // that will not be used and cannot be deleted
        // This code also checks that the alias does not start with an invalid prefix
        // If it does, this will generate an exception, the alias will not be added
        // to the workflow version, but there may be an invalid Related Identifier URL on the Zenodo entry
        AliasHelper.addWorkflowVersionAliasesAndCheck(this, workflowDAO, workflowVersionDAO, user,
                workflowVersion.getId(), zenodoDoiResult.getDoiAlias(), false);
    }


    private String workflowNameAndVersion(Workflow workflow, WorkflowVersion workflowVersion) {
        return workflow.getWorkflowPath() + ":" + workflowVersion.getName();
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/resetVersionPaths")
    @Operation(operationId = "updateWorkflowPath", description = "Reset the workflow paths.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Reset the workflow paths.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Resets the workflow paths of all versions to match the default workflow path from the workflow object passed.", response = Workflow.class)
    public Workflow updateWorkflowPath(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "Workflow with updated information", required = true) Workflow workflow) {

        Workflow wf = workflowDAO.findById(workflowId);

        //check if the user and the entry is correct
        checkEntry(wf);
        checkCanWriteWorkflow(user, wf);
        checkNotHosted(wf);

        //update the workflow path in all workflowVersions
        Set<WorkflowVersion> versions = wf.getWorkflowVersions();
        for (WorkflowVersion version : versions) {
            if (!version.isDirtyBit()) {
                version.setWorkflowPath(workflow.getDefaultWorkflowPath());
                version.setSynced(false);
            }
        }
        PublicStateManager.getInstance().handleIndexUpdate(wf, StateManagerMode.UPDATE);
        return wf;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{workflowId}/users")
    @Operation(operationId = "getUsers", description = "Get users of a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "getUsers", value = "Get users of a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = User.class, responseContainer = "List")
    public List<User> getUsers(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "workflow ID", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow c = workflowDAO.findById(workflowId);
        checkEntry(c);

        checkUser(user, c);

        return new ArrayList<>(c.getUsers());
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/published/{workflowId}")
    @Operation(operationId = "getPublishedWorkflow", description = "Get a published workflow.")
    @ApiOperation(value = "Get a published workflow.", notes = "Hidden versions will not be visible. NO authentication", response = Workflow.class)
    public Workflow getPublishedWorkflow(@ApiParam(value = "Workflow ID", required = true) @PathParam("workflowId") Long workflowId, @ApiParam(value = "Comma-delimited list of fields to include: " + VALIDATIONS + ", " + ALIASES) @QueryParam("include") String include) {
        Workflow workflow = workflowDAO.findPublishedById(workflowId);
        checkEntry(workflow);
        initializeAdditionalFields(include, workflow);
        Hibernate.initialize(workflow.getAliases());
        return filterContainersForHiddenTags(workflow);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/organization/{organization}/published")
    @Operation(operationId = "getPublishedWorkflowsByOrganization", description = "List all published workflows of an organization.")
    @ApiOperation(value = "List all published workflows of an organization.", notes = "NO authentication", response = Workflow.class, responseContainer = "List")
    public List<Workflow> getPublishedWorkflowsByOrganization(
        @ApiParam(value = "organization", required = true) @PathParam("organization") String organization) {
        List<Workflow> workflows = workflowDAO.findPublishedByOrganization(organization);
        filterContainersForHiddenTags(workflows);
        return workflows;
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/publish")
    @Operation(operationId = "publish", description = "Publish or unpublish a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "publish", value = "Publish or unpublish a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Publish/publish a workflow (public or private).", response = Workflow.class)
    public Workflow publish(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow id to publish/unpublish", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "PublishRequest to refresh the list of repos for a user", required = true) PublishRequest request) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);

        checkCanShareWorkflow(user, workflow);

        Workflow publishedWorkflow = publishWorkflow(workflow, request.getPublish());
        Hibernate.initialize(publishedWorkflow.getWorkflowVersions());
        return publishedWorkflow;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("published")
    @Operation(operationId = "allPublishedWorkflows", description = "List all published workflows.")
    @ApiOperation(value = "List all published workflows.", tags = {
        "workflows" }, notes = "NO authentication", response = Workflow.class, responseContainer = "List")
    public List<Workflow> allPublishedWorkflows(
        @ApiParam(value = "Start index of paging. Pagination results can be based on numbers or other values chosen by the registry implementor (for example, SHA values). If this exceeds the current result set return an empty set.  If not specified in the request, this will start at the beginning of the results.") @QueryParam("offset") String offset,
        @ApiParam(value = "Amount of records to return in a given page, limited to "
            + PAGINATION_LIMIT, allowableValues = "range[1,100]", defaultValue = PAGINATION_LIMIT) @DefaultValue(PAGINATION_LIMIT) @QueryParam("limit") Integer limit,
        @ApiParam(value = "Filter, this is a search string that filters the results.") @DefaultValue("") @QueryParam("filter") String filter,
        @ApiParam(value = "Sort column") @DefaultValue("stars") @QueryParam("sortCol") String sortCol,
        @ApiParam(value = "Sort order", allowableValues = "asc,desc") @DefaultValue("desc") @QueryParam("sortOrder") String sortOrder,
        @ApiParam(value = "services", defaultValue = "false") @DefaultValue("false") @QueryParam("services") boolean services,
        @Context HttpServletResponse response) {
        // delete the next line if GUI pagination is not working by 1.5.0 release
        int maxLimit = Math.min(Integer.parseInt(PAGINATION_LIMIT), limit);
        List<Workflow> workflows = workflowDAO.findAllPublished(offset, maxLimit, filter, sortCol, sortOrder, (Class<Workflow>)(services
            ? Service.class : BioWorkflow.class));
        filterContainersForHiddenTags(workflows);
        stripContent(workflows);
        EntryDAO entryDAO = services ? serviceEntryDAO : bioWorkflowDAO;
        response.addHeader("X-total-count", String.valueOf(entryDAO.countAllPublished(Optional.of(filter))));
        response.addHeader("Access-Control-Expose-Headers", "X-total-count");
        return workflows;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("shared")
    @Operation(operationId = "sharedWorkflows", description = "Retrieve all workflows shared with user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Retrieve all workflows shared with user.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, tags = {
        "workflows" }, response = SharedWorkflows.class, responseContainer = "List")
    public List<SharedWorkflows> sharedWorkflows(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user) {
        final Map<Role, List<String>> workflowsSharedWithUser  = this.permissionsInterface.workflowsSharedWithUser(user);

        final List<String> paths =
            workflowsSharedWithUser.values()
                .stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // Fetch workflows in batch
        List<Workflow> workflowList = workflowDAO.findByPaths(paths, false);

        return workflowsSharedWithUser.entrySet().stream().map(e -> {
            // Create a SharedWorkFlow map for each Role and the list of workflows that belong to it
            final List<Workflow> workflows = workflowList.stream()
                // Filter only the workflows that belong to the current Role and where the user is not the owner
                .filter(workflow -> e.getValue().contains(workflow.getWorkflowPath()) && !workflow.getUsers().contains(user))
                .collect(Collectors.toList());
            return new SharedWorkflows(e.getKey(), workflows);
        }).filter(sharedWorkflow -> sharedWorkflow.getWorkflows().size() > 0).collect(Collectors.toList());
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/path/workflow/{repository}")
    @Operation(operationId = "getWorkflowByPath", summary = "Get a workflow by path.", description = "Requires full path (including workflow name if applicable).", security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get a workflow by path.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Requires full path (including workflow name if applicable).", response = Workflow.class)
    public Workflow getWorkflowByPath(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @Parameter(name = "repository", description = "Repository path", required = true, in = ParameterIn.PATH) @ApiParam(value = "repository path", required = true) @PathParam("repository") String path,
            @Parameter(name = "include", description = "Comma-delimited list of fields to include: " + VALIDATIONS + ", " + ALIASES, in = ParameterIn.QUERY) @ApiParam(value = "Comma-delimited list of fields to include: " + VALIDATIONS + ", " + ALIASES) @QueryParam("include") String include,
            @Parameter(name = "services", description = "Whether to get a service or workflow", in = ParameterIn.QUERY, schema = @Schema(defaultValue = "false")) @ApiParam(value = "services", defaultValue = "false") @DefaultValue("false") @QueryParam("services") boolean services) {
        final Class<? extends Workflow> targetClass = services ? Service.class : BioWorkflow.class;
        Workflow workflow = workflowDAO.findByPath(path, false, targetClass).orElse(null);
        checkEntry(workflow);
        checkCanRead(user, workflow);
        Hibernate.initialize(workflow.getAliases());
        initializeAdditionalFields(include, workflow);
        return workflow;
    }
    @SuppressWarnings("checkstyle:MagicNumber")
    private void setWorkflowVersionSubset(Workflow workflow, String include, String versionName) {
        sessionFactory.getCurrentSession().detach(workflow);

        // Almost all observed workflows have under 200 version, this number should be lowered once the frontend actually supports pagination
        List<WorkflowVersion> ids = this.workflowVersionDAO.getWorkflowVersionsByWorkflowId(workflow.getId(), VERSION_PAGINATION_LIMIT, 0);
        SortedSet<WorkflowVersion> workflowVersions = new TreeSet<>(ids);
        if (versionName != null && workflowVersions.stream().noneMatch(version -> version.getName().equals(versionName))) {
            WorkflowVersion workflowVersionByWorkflowIdAndVersionName = this.workflowVersionDAO
                    .getWorkflowVersionByWorkflowIdAndVersionName(workflow.getId(), versionName);
            if (workflowVersionByWorkflowIdAndVersionName != null) {
                workflowVersions.add(workflowVersionByWorkflowIdAndVersionName);
            }
        }
        workflow.setWorkflowVersionsOverride(workflowVersions);
        initializeAdditionalFields(include, workflow);
        ids.forEach(id -> sessionFactory.getCurrentSession().detach(id));
    }

    /**
     * Checks if <code>user</code> has permission to read <code>workflow</code>. If the user
     * does not have permission, throws a {@link CustomWebApplicationException}.
     *
     * @param user
     * @param workflow
     */
    @Override
    public void checkCanRead(User user, Entry workflow) {
        try {
            checkUser(user, workflow);
        } catch (CustomWebApplicationException ex) {
            if (!permissionsInterface.canDoAction(user, (Workflow)workflow, Role.Action.READ)) {
                throw ex;
            }
        }
    }

    /**
     * Checks if <code>user</code> has permission to write <code>workflow</code>. If the user
     * does not have permission, throws a {@link CustomWebApplicationException}.
     *
     * @param user
     * @param workflow
     */
    private void checkCanWriteWorkflow(User user, Workflow workflow) {
        try {
            checkUserOwnsEntry(user, workflow);
        } catch (CustomWebApplicationException ex) {
            if (!permissionsInterface.canDoAction(user, workflow, Role.Action.WRITE)) {
                throw ex;
            }
        }
    }

    /**
     * Checks if <code>user</code> has permission to share <code>workflow</code>. If the user
     * does not have permission, throws a {@link CustomWebApplicationException}.
     *
     * @param user
     * @param workflow
     */
    private void checkCanShareWorkflow(User user, Workflow workflow) {
        try {
            checkUser(user, workflow);
        } catch (CustomWebApplicationException ex) {
            if (!permissionsInterface.canDoAction(user, workflow, Role.Action.SHARE)) {
                throw ex;
            }
        }
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/path/workflow/{repository}/permissions")
    @Operation(operationId = "getWorkflowPermissions", description = "Get all permissions for a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get all permissions for a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "The user must be the workflow owner.", response = Permission.class, responseContainer = "List")
    public List<Permission> getWorkflowPermissions(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "repository path", required = true) @PathParam("repository") String path,
        @ApiParam(value = "services", defaultValue = "false") @DefaultValue("false") @QueryParam("services") boolean services) {
        final Class<? extends Workflow> targetClass = services ? Service.class : BioWorkflow.class;
        Workflow workflow = workflowDAO.findByPath(path, false, targetClass).orElse(null);
        checkEntry(workflow);
        return this.permissionsInterface.getPermissionsForWorkflow(user, workflow);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/path/workflow/{repository}/actions")
    @Operation(operationId = "getWorkflowActions", description = "Gets all actions a user can perform on a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Gets all actions a user can perform on a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Role.Action.class, responseContainer = "List")
    public List<Role.Action> getWorkflowActions(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "repository path", required = true) @PathParam("repository") String path,
        @ApiParam(value = "services", defaultValue = "false") @DefaultValue("false") @QueryParam("services") boolean services) {
        final Class<? extends Workflow> targetClass = services ? Service.class : BioWorkflow.class;
        Workflow workflow = workflowDAO.findByPath(path, false, targetClass).orElse(null);
        checkEntry(workflow);
        return this.permissionsInterface.getActionsForWorkflow(user, workflow);
    }

    @PATCH
    @Timed
    @UnitOfWork
    @Path("/path/workflow/{repository}/permissions")
    @Operation(operationId = "addWorkflowPermission", description = "Set the specified permission for a user on a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Set the specified permission for a user on a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "The user must be the workflow owner. Currently only supported on hosted workflows.", response = Permission.class, responseContainer = "List")
    public List<Permission> addWorkflowPermission(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "repository path", required = true) @PathParam("repository") String path,
        @ApiParam(value = "user permission", required = true) Permission permission,
        @ApiParam(value = "services", defaultValue = "false") @DefaultValue("false") @QueryParam("services") boolean services) {
        final Class<? extends Workflow> targetClass = services ? Service.class : BioWorkflow.class;
        Workflow workflow = workflowDAO.findByPath(path, false, targetClass).orElse(null);
        checkEntry(workflow);
        // TODO: Remove this guard when ready to expand sharing to non-hosted workflows. https://github.com/dockstore/dockstore/issues/1593
        if (workflow.getMode() != WorkflowMode.HOSTED) {
            throw new CustomWebApplicationException("Setting permissions is only allowed on hosted workflows.", HttpStatus.SC_BAD_REQUEST);
        }
        return this.permissionsInterface.setPermission(user, workflow, permission);
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/path/workflow/{repository}/permissions")
    @Operation(operationId = "removeWorkflowRole", description = "Remove the specified user role for a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Remove the specified user role for a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "The user must be the workflow owner.", response = Permission.class, responseContainer = "List")
    public List<Permission> removeWorkflowRole(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "repository path", required = true) @PathParam("repository") String path,
        @ApiParam(value = "user email", required = true) @QueryParam("email") String email,
        @ApiParam(value = "role", required = true) @QueryParam("role") Role role, @ApiParam(value = "services", defaultValue = "false") @DefaultValue("false") @QueryParam("services") boolean services) {
        final Class<? extends Workflow> targetClass = services ? Service.class : BioWorkflow.class;
        Workflow workflow = workflowDAO.findByPath(path, false, targetClass).orElse(null);
        checkEntry(workflow);
        this.permissionsInterface.removePermission(user, workflow, email, role);
        return this.permissionsInterface.getPermissionsForWorkflow(user, workflow);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/path/entry/{repository}")
    @Operation(operationId = "getEntryByPath", description = "Get an entry by path.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get an entry by path.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Requires full path (including entry name if applicable).", response = Entry.class)
    public Entry getEntryByPath(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "repository path", required = true) @PathParam("repository") String path) {
        MutablePair<String, Entry> entryPair = toolDAO.findEntryByPath(path, false);

        // Check if the entry exists
        if (entryPair == null) {
            throw new CustomWebApplicationException("Entry not found", HttpStatus.SC_BAD_REQUEST);
        }

        // Ensure the user has access
        checkUser(user, entryPair.getValue());

        return entryPair.getValue();
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/path/entry/{repository}/published")
    @Operation(operationId = "getPublishedEntryByPath", description = "Get a published entry by path.")
    @ApiOperation(nickname = "getPublishedEntryByPath", value = "Get a published entry by path.", notes = "Requires full path (including entry name if applicable).", response = Entry.class)
    public Entry getPublishedEntryByPath(@ApiParam(value = "repository path", required = true) @PathParam("repository") String path) {
        MutablePair<String, Entry> entryPair = toolDAO.findEntryByPath(path, true);

        // Check if the entry exists
        if (entryPair == null) {
            throw new CustomWebApplicationException("Entry not found", HttpStatus.SC_BAD_REQUEST);
        }

        return entryPair.getValue();
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/path/{repository}")
    @Operation(operationId = "getAllWorkflowByPath", description = "Get a list of workflows by path.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "getAllWorkflowByPath", value = "Get a list of workflows by path.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Does not require workflow name.", response = Workflow.class, responseContainer = "List")
    public List<Workflow> getAllWorkflowByPath(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "repository path", required = true) @PathParam("repository") String path) {
        List<Workflow> workflows = workflowDAO.findAllByPath(path, false);
        checkEntry(workflows);
        AuthenticatedResourceInterface.checkUser(user, workflows);
        return workflows;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/path/workflow/{repository}/published")
    @Operation(operationId = "getPublishedWorkflowByPath", description = "Get a published workflow by path")
    @ApiOperation(nickname = "getPublishedWorkflowByPath", value = "Get a published workflow by path", notes = "Does not require workflow name.", response = Workflow.class)
    public Workflow getPublishedWorkflowByPath(@ApiParam(value = "repository path", required = true) @PathParam("repository") String path, @ApiParam(value = "Comma-delimited list of fields to include: " + VALIDATIONS + ", " + ALIASES) @QueryParam("include") String include,
        @ApiParam(value = "services", defaultValue = "false") @DefaultValue("false") @QueryParam("services") boolean services, @ApiParam(value = "Version name", required = false) @QueryParam("versionName") String versionName) {
        final Class<? extends Workflow> targetClass = services ? Service.class : BioWorkflow.class;
        Workflow workflow = workflowDAO.findByPath(path, true, targetClass).orElse(null);
        checkEntry(workflow);

        Hibernate.initialize(workflow.getAliases());
        setWorkflowVersionSubset(workflow, include, versionName);
        filterContainersForHiddenTags(workflow);
        return workflow;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/versions")
    @Operation(operationId = "tags", description = "List the versions for a published workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "tags", value = "List the versions for a published workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = WorkflowVersion.class, responseContainer = "List", hidden = true)
    public List<WorkflowVersion> tags(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user, @QueryParam("workflowId") long workflowId) {
        Workflow repository = workflowDAO.findPublishedById(workflowId);
        checkEntry(repository);
        return new ArrayList<>(repository.getWorkflowVersions());
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{workflowId}/primaryDescriptor")
    @Operation(operationId = "primaryDescriptor", description = "Get the primary descriptor file.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "primaryDescriptor", value = "Get the primary descriptor file.", tags = {
        "workflows" }, notes = OPTIONAL_AUTH_MESSAGE, response = SourceFile.class, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public SourceFile primaryDescriptor(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth Optional<User> user,
        @ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId,
        @QueryParam("tag") String tag, @QueryParam("language") String language) {
        final FileType fileType = DescriptorLanguage.getFileType(language).orElseThrow(() ->  new CustomWebApplicationException("Language not valid", HttpStatus.SC_BAD_REQUEST));
        return getSourceFile(workflowId, tag, fileType, user, fileDAO);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{workflowId}/descriptor/{relative-path}")
    @Operation(operationId = "secondaryDescriptorPath", description = "Get the corresponding descriptor file from source control.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "secondaryDescriptorPath", value = "Get the corresponding descriptor file from source control.", tags = {
        "workflows" }, notes = OPTIONAL_AUTH_MESSAGE, response = SourceFile.class, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public SourceFile secondaryDescriptorPath(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth Optional<User> user,
        @ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("tag") String tag,
        @PathParam("relative-path") String path, @QueryParam("language") String language) {
        final FileType fileType = DescriptorLanguage.getFileType(language).orElseThrow(() ->  new CustomWebApplicationException("Language not valid", HttpStatus.SC_BAD_REQUEST));
        return getSourceFileByPath(workflowId, tag, fileType, path, user, fileDAO);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{workflowId}/secondaryDescriptors")
    @Operation(operationId = "secondaryDescriptors", description = "Get the corresponding descriptor documents from source control.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "secondaryDescriptors", value = "Get the corresponding descriptor documents from source control.", tags = {
        "workflows" }, notes = OPTIONAL_AUTH_MESSAGE, response = SourceFile.class, responseContainer = "List", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public List<SourceFile> secondaryDescriptors(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth Optional<User> user,
        @ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("tag") String tag, @QueryParam("language") String language) {
        final FileType fileType = DescriptorLanguage.getFileType(language).orElseThrow(() ->  new CustomWebApplicationException("Language not valid", HttpStatus.SC_BAD_REQUEST));
        return getAllSecondaryFiles(workflowId, tag, fileType, user, fileDAO);
    }



    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{workflowId}/testParameterFiles")
    @Operation(operationId = "getTestParameterFiles", description = "Get the corresponding test parameter files.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "getTestParameterFiles", value = "Get the corresponding test parameter files.", tags = {
        "workflows" }, notes = OPTIONAL_AUTH_MESSAGE, response = SourceFile.class, responseContainer = "List", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public List<SourceFile> getTestParameterFiles(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth Optional<User> user,
        @ApiParam(value = "Workflow id", required = true) @PathParam("workflowId") Long workflowId, @QueryParam("version") String version) {

        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        FileType testParameterType = workflow.getTestParameterType();
        return getAllSourceFiles(workflowId, version, testParameterType, user, fileDAO);
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/testParameterFiles")
    @Operation(operationId = "addTestParameterFiles", description = "Add test parameter files for a given version.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "addTestParameterFiles", value = "Add test parameter files for a given version.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = SourceFile.class, responseContainer = "Set")
    public Set<SourceFile> addTestParameterFiles(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "List of paths.", required = true) @QueryParam("testParameterPaths") List<String> testParameterPaths,
        @ApiParam(value = "This is here to appease Swagger. It requires PUT methods to have a body, even if it is empty. Please leave it empty.") String emptyBody,
        @QueryParam("version") String version) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkCanWriteWorkflow(user, workflow);
        checkNotHosted(workflow);

        if (workflow.getMode() == WorkflowMode.STUB) {
            String msg = "The workflow '" + workflow.getWorkflowPath()
                + "' is a STUB. Refresh the workflow if you want to add test parameter files";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        Optional<WorkflowVersion> potentialWorfklowVersion = workflow.getWorkflowVersions().stream()
            .filter((WorkflowVersion v) -> v.getName().equals(version)).findFirst();

        if (potentialWorfklowVersion.isEmpty()) {
            String msg = "The version '" + version + "' for workflow '" + workflow.getWorkflowPath() + "' does not exist.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        WorkflowVersion workflowVersion = potentialWorfklowVersion.get();
        checkNotFrozen(workflowVersion);
        workflowVersion.setSynced(false);

        Set<SourceFile> sourceFiles = workflowVersion.getSourceFiles();

        // Add new test parameter files
        FileType testParameterType = workflow.getTestParameterType();
        createTestParameters(testParameterPaths, workflowVersion, sourceFiles, testParameterType, fileDAO);
        PublicStateManager.getInstance().handleIndexUpdate(workflow, StateManagerMode.UPDATE);
        return workflowVersion.getSourceFiles();
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/testParameterFiles")
    @Operation(operationId = "deleteTestParameterFiles", description = "Delete test parameter files for a given version.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "deleteTestParameterFiles", value = "Delete test parameter files for a given version.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = SourceFile.class, responseContainer = "Set")
    public Set<SourceFile> deleteTestParameterFiles(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "List of paths.", required = true) @QueryParam("testParameterPaths") List<String> testParameterPaths,
        @QueryParam("version") String version) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkCanWriteWorkflow(user, workflow);
        checkNotHosted(workflow);

        Optional<WorkflowVersion> potentialWorkflowVersion = workflow.getWorkflowVersions().stream()
            .filter((WorkflowVersion v) -> v.getName().equals(version)).findFirst();

        if (potentialWorkflowVersion.isEmpty()) {
            LOG.info("The version '" + version + "' for workflow '" + workflow.getWorkflowPath() + "' does not exist.");
            throw new CustomWebApplicationException("The version '" + version + "' for workflow '"
                + workflow.getWorkflowPath() + "' does not exist.",
                HttpStatus.SC_BAD_REQUEST);
        }

        WorkflowVersion workflowVersion = potentialWorkflowVersion.get();
        checkNotFrozen(workflowVersion);
        workflowVersion.setSynced(false);

        Set<SourceFile> sourceFiles = workflowVersion.getSourceFiles();

        // Remove test parameter files
        FileType testParameterType = workflow.getTestParameterType();
        testParameterPaths
            .forEach(path -> {
                boolean fileDeleted = sourceFiles.removeIf((SourceFile v) -> v.getPath().equals(path) && v.getType() == testParameterType);
                if (!fileDeleted) {
                    throw new CustomWebApplicationException("There are no existing test parameter files with the path: " + path, HttpStatus.SC_NOT_FOUND);
                }
            });
        PublicStateManager.getInstance().handleIndexUpdate(workflow, StateManagerMode.UPDATE);
        return workflowVersion.getSourceFiles();
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/manualRegister")
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Operation(operationId = "manualRegister", description = "Manually register a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Manually register a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Manually register workflow (public or private).", response = Workflow.class)
    public Workflow manualRegister(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow registry", required = true) @QueryParam("workflowRegistry") String workflowRegistry,
        @ApiParam(value = "Workflow repository", required = true) @QueryParam("workflowPath") String workflowPath,
        @ApiParam(value = "Workflow container new descriptor path (CWL or WDL) and/or name", required = true) @QueryParam("defaultWorkflowPath") String defaultWorkflowPath,
        @ApiParam(value = "Workflow name, set to empty if none required", required = true) @QueryParam("workflowName") String workflowName,
        @ApiParam(value = "Descriptor type", required = true) @QueryParam("descriptorType") String descriptorType,
        @ApiParam(value = "Default test parameter file path") @QueryParam("defaultTestParameterFilePath") String defaultTestParameterFilePath) {

        for (DescriptorLanguage typeItem : DescriptorLanguage.values()) {
            if (typeItem.getShortName().equalsIgnoreCase(descriptorType)) {
                // check that plugin is active
                if (typeItem.isPluginLanguage() && !LanguageHandlerFactory.getPluginMap().containsKey(typeItem)) {
                    throw new CustomWebApplicationException("plugin for " + typeItem.getShortName() + " is not installed",
                        HttpStatus.SC_BAD_REQUEST);
                }
                if (typeItem.getDefaultPrimaryDescriptorExtensions().stream().noneMatch(defaultWorkflowPath::endsWith)) {
                    throw new CustomWebApplicationException(
                        "Please ensure that the given workflow path '" + defaultWorkflowPath + "' is of type " + descriptorType
                            + " and ends in an extension from" + String.join(",", typeItem.getDefaultPrimaryDescriptorExtensions()),
                        HttpStatus.SC_BAD_REQUEST);
                }
            }
        }

        // Validate source control registry
        Optional<SourceControl> sourceControlEnum = Arrays.stream(SourceControl.values()).filter(value -> workflowRegistry.equalsIgnoreCase(value.getFriendlyName().toLowerCase())).findFirst();
        if (sourceControlEnum.isEmpty()) {
            throw new CustomWebApplicationException("The given git registry is not supported.", HttpStatus.SC_BAD_REQUEST);
        }

        String registryURLPrefix = sourceControlEnum.get().toString();
        String gitURL = "git@" + registryURLPrefix + ":" + workflowPath + ".git";
        final SourceCodeRepoInterface sourceCodeRepo = getSourceCodeRepoInterface(gitURL, user);

        // Create workflow and override defaults
        Workflow newWorkflow = sourceCodeRepo.createStubBioworkflow(workflowPath);
        newWorkflow.setDescriptorType(DescriptorLanguage.convertShortStringToEnum(descriptorType));
        newWorkflow.setDefaultWorkflowPath(defaultWorkflowPath);
        newWorkflow.setWorkflowName(Strings.isNullOrEmpty(workflowName) ? null : workflowName);
        newWorkflow.setDefaultTestParameterFilePath(defaultTestParameterFilePath);

        // Save into database and then pull versions
        Workflow workflowFromDB = saveNewWorkflow(newWorkflow, user);
        updateDBWorkflowWithSourceControlWorkflow(workflowFromDB, newWorkflow, user, Optional.empty());
        return workflowDAO.findById(workflowFromDB.getId());
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/workflowVersions")
    @Operation(operationId = "updateWorkflowVersion", description = "Update the workflow versions linked to a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Update the workflow versions linked to a workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Updates workflow path, reference, and hidden attributes.", response = WorkflowVersion.class, responseContainer = "List")
    public Set<WorkflowVersion> updateWorkflowVersion(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Workflow to modify.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "List of modified workflow versions", required = true) List<WorkflowVersion> workflowVersions) {

        Workflow w = workflowDAO.findById(workflowId);
        checkEntry(w);
        checkCanWriteWorkflow(user, w);

        // create a map for quick lookup
        Map<Long, WorkflowVersion> mapOfExistingWorkflowVersions = new HashMap<>();
        for (WorkflowVersion version : w.getWorkflowVersions()) {
            mapOfExistingWorkflowVersions.put(version.getId(), version);
        }

        for (WorkflowVersion version : workflowVersions) {
            if (mapOfExistingWorkflowVersions.containsKey(version.getId())) {
                if (w.getActualDefaultVersion() != null && w.getActualDefaultVersion().getId() == version.getId() && version.isHidden()) {
                    throw new CustomWebApplicationException("You cannot hide the default version.", HttpStatus.SC_BAD_REQUEST);
                }
                // remove existing copy and add the new one
                WorkflowVersion existingTag = mapOfExistingWorkflowVersions.get(version.getId());

                existingTag.setSynced(false);

                // If path changed then update dirty bit to true
                if (!existingTag.getWorkflowPath().equals(version.getWorkflowPath())) {
                    String newExtension = FilenameUtils.getExtension(version.getWorkflowPath());
                    String correctExtension = FilenameUtils.getExtension(w.getDefaultWorkflowPath());
                    if (!Objects.equals(newExtension, correctExtension)) {
                        throw new CustomWebApplicationException("Please ensure that the workflow path uses the file extension " + correctExtension, HttpStatus.SC_BAD_REQUEST);
                    }
                    existingTag.setDirtyBit(true);
                }

                boolean wasFrozen = existingTag.isFrozen();
                existingTag.updateByUser(version);
                boolean nowFrozen = existingTag.isFrozen();
                // If version is snapshotted on this update, grab and store image information. Also store dag and tool table json if not available.
                if (!wasFrozen && nowFrozen) {
                    Optional<String> toolsJSONTable;
                    LanguageHandlerInterface lInterface = LanguageHandlerFactory.getInterface(w.getFileType());
                    // Store tool table json
                    if (existingTag.getToolTableJson() == null) {
                        toolsJSONTable = lInterface.getContent(w.getWorkflowPath(), getMainDescriptorFile(existingTag).getContent(), extractDescriptorAndSecondaryFiles(existingTag), LanguageHandlerInterface.Type.TOOLS, toolDAO);
                        existingTag.setToolTableJson(toolsJSONTable.get());
                    } else {
                        toolsJSONTable = Optional.of(existingTag.getToolTableJson());
                    }

                    if (toolsJSONTable.isPresent()) {
                        Set<Image> images = lInterface.getImagesFromRegistry(toolsJSONTable.get());
                        existingTag.getImages().addAll(images);
                    }
                    // Grab checksum for file descriptors if not already available.
                    for (SourceFile sourceFile : existingTag.getSourceFiles()) {
                        Optional<String> sha = FileFormatHelper.calcSHA1(sourceFile.getContent());
                        if (sha.isPresent()) {
                            List<Checksum> checksums = new ArrayList<>();
                            checksums.add(new Checksum(SHA_TYPE_FOR_SOURCEFILES, sha.get()));
                            if (sourceFile.getChecksums() == null) {
                                sourceFile.setChecksums(checksums);
                            } else if (sourceFile.getChecksums().isEmpty()) {
                                sourceFile.getChecksums().addAll(checksums);
                            }
                        }

                    }

                    // store dag
                    if (existingTag.getDagJson() == null) {
                        String dagJson = lInterface.getCleanDAG(w.getWorkflowPath(), getMainDescriptorFile(existingTag).getContent(), extractDescriptorAndSecondaryFiles(existingTag), LanguageHandlerInterface.Type.DAG, toolDAO);
                        existingTag.setDagJson(dagJson);
                    }
                }
            }
        }
        Workflow result = workflowDAO.findById(workflowId);
        checkEntry(result);
        PublicStateManager.getInstance().handleIndexUpdate(result, StateManagerMode.UPDATE);
        return result.getWorkflowVersions();
    }

    @GET
    @Timed
    @UnitOfWork()
    @Path("/{workflowId}/dag/{workflowVersionId}")
    @Operation(operationId = "getWorkflowDag", description = "Get the DAG for a given workflow version.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get the DAG for a given workflow version.", response = String.class, notes = OPTIONAL_AUTH_MESSAGE, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public String getWorkflowDag(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth Optional<User> user,
        @ApiParam(value = "workflowId", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "workflowVersionId", required = true) @PathParam("workflowVersionId") Long workflowVersionId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkOptionalAuthRead(user, workflow);

        WorkflowVersion workflowVersion = getWorkflowVersion(workflow, workflowVersionId);
        SourceFile mainDescriptor = getMainDescriptorFile(workflowVersion);

        // json in db cleared after a refresh
        if (workflowVersion.getDagJson() != null) {
            return workflowVersion.getDagJson();
        }

        if (mainDescriptor != null) {
            Set<SourceFile> secondaryDescContent = extractDescriptorAndSecondaryFiles(workflowVersion);

            LanguageHandlerInterface lInterface = LanguageHandlerFactory.getInterface(workflow.getFileType());
            final String dagJson = lInterface.getCleanDAG(workflowVersion.getWorkflowPath(), mainDescriptor.getContent(), secondaryDescContent,
                    LanguageHandlerInterface.Type.DAG, toolDAO);

            workflowVersion.setDagJson(dagJson);
            return dagJson;
        }
        return null;
    }

    /**
     * This method will create a json data consisting tool and its data required in a workflow for 'Tool' tab
     *
     * @param workflowId        workflow to grab tools for
     * @param workflowVersionId version of the workflow to grab tools for
     * @return json content consisting of a workflow and the tools it uses
     */
    @GET
    @Timed
    @UnitOfWork()
    @Path("/{workflowId}/tools/{workflowVersionId}")
    @Operation(operationId = "getTableToolContent", description = "Get the Tools for a given workflow version.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get the Tools for a given workflow version.", notes = OPTIONAL_AUTH_MESSAGE, response = String.class, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public String getTableToolContent(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth Optional<User> user,
        @ApiParam(value = "workflowId", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "workflowVersionId", required = true) @PathParam("workflowVersionId") Long workflowVersionId) {

        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkOptionalAuthRead(user, workflow);

        WorkflowVersion workflowVersion = getWorkflowVersion(workflow, workflowVersionId);
        if (workflowVersion == null) {
            throw new CustomWebApplicationException("workflow version " + workflowVersionId + " does not exist", HttpStatus.SC_BAD_REQUEST);
        }

        // json in db cleared after a refresh
        if (workflowVersion.getToolTableJson() != null) {
            return workflowVersion.getToolTableJson();
        }

        SourceFile mainDescriptor = getMainDescriptorFile(workflowVersion);
        if (mainDescriptor != null) {
            Set<SourceFile> secondaryDescContent = extractDescriptorAndSecondaryFiles(workflowVersion);
            LanguageHandlerInterface lInterface = LanguageHandlerFactory.getInterface(workflow.getFileType());
            final Optional<String> toolTableJson = lInterface.getContent(workflowVersion.getWorkflowPath(), mainDescriptor.getContent(), secondaryDescContent,
                LanguageHandlerInterface.Type.TOOLS, toolDAO);

            final String json = toolTableJson.orElse(null);

            // Can't UPDATE workflowversion when frozen = true
            if (workflowVersion.isFrozen()) {
                LOG.warn("workflow version " + workflowVersionId + " is frozen without toolTableJson");
            } else {
                workflowVersion.setToolTableJson(json);
            }

            return json;
        }

        return null;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("{workflowId}/workflowVersions/{workflowVersionId}/sourcefiles")
    @ApiOperation(value = "Retrieve sourcefiles for an entry's version",  hidden = true)
    @Operation(operationId = "getWorkflowVersionsSourcefiles", description = "Retrieve sourcefiles for an entry's version", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    public SortedSet<SourceFile> getWorkflowVersionsSourceFiles(@Parameter(hidden = true, name = "user")@Auth Optional<User> user,
            @Parameter(name = "workflowId", description = "Workflow to retrieve the version from.", required = true, in = ParameterIn.PATH) @PathParam("workflowId") Long workflowId,
            @Parameter(name = "workflowVersionId", description = "Workflow version to retrieve the version from.", required = true, in = ParameterIn.PATH) @PathParam("workflowVersionId") Long workflowVersionId,
            @Parameter(name = "fileTypes", description = "List of file types to filter sourcefiles by", in = ParameterIn.QUERY) @QueryParam("fileTypes") List<DescriptorLanguage.FileType> fileTypes) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkOptionalAuthRead(user, workflow);

        return getVersionsSourcefiles(workflowId, workflowVersionId, fileTypes, versionDAO);
    }

    /**
     * Populates the return file with the descriptor and secondaryDescContent as a map between file paths and secondary files
     *
     * @param workflowVersion source control version to consider
     * @return secondary file map (string path -> string content)
     */
    private Set<SourceFile> extractDescriptorAndSecondaryFiles(WorkflowVersion workflowVersion) {
        return workflowVersion.getSourceFiles().stream()
                .filter(sf -> !sf.getPath().equals(workflowVersion.getWorkflowPath()))
                .collect(Collectors.toSet());
    }

    /**
     * This method will find the workflowVersion based on the workflowVersionId passed in the parameter and return it
     *
     * @param workflow          a workflow to grab a workflow version from
     * @param workflowVersionId the workflow version to get
     * @return WorkflowVersion
     */
    private WorkflowVersion getWorkflowVersion(Workflow workflow, Long workflowVersionId) {
        Set<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
        WorkflowVersion workflowVersion = null;

        for (WorkflowVersion wv : workflowVersions) {
            if (wv.getId() == workflowVersionId) {
                workflowVersion = wv;
                break;
            }
        }

        return workflowVersion;
    }

    /**
     * This method will find the main descriptor file based on the workflow version passed in the parameter
     *
     * @param workflowVersion workflowVersion with collects sourcefiles
     * @return mainDescriptor
     */
    private SourceFile getMainDescriptorFile(WorkflowVersion workflowVersion) {

        SourceFile mainDescriptor = null;
        for (SourceFile sourceFile : workflowVersion.getSourceFiles()) {
            if (sourceFile.getPath().equals(workflowVersion.getWorkflowPath())) {
                mainDescriptor = sourceFile;
                break;
            }
        }

        return mainDescriptor;
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{workflowId}/star")
    @Operation(operationId = "starEntry", description = "Star a workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "starEntry", value = "Star a workflow.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public void starEntry(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Tool to star.", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "StarRequest to star a repo for a user", required = true) StarRequest request) {
        Workflow workflow = workflowDAO.findById(workflowId);
        if (request.getStar()) {
            starEntryHelper(workflow, user, "workflow", workflow.getWorkflowPath());
        } else {
            unstarEntryHelper(workflow, user, "workflow", workflow.getWorkflowPath());
        }
        PublicStateManager.getInstance().handleIndexUpdate(workflow, StateManagerMode.UPDATE);
    }

    @GET
    @Path("/{workflowId}/starredUsers")
    @Timed
    @UnitOfWork(readOnly = true)
    @Operation(operationId = "getStarredUsers", description = "Returns list of users who starred the given workflow.")
    @ApiOperation(nickname = "getStarredUsers", value = "Returns list of users who starred the given workflow.", response = User.class, responseContainer = "List")
    public Set<User> getStarredUsers(
        @ApiParam(value = "Workflow to grab starred users for.", required = true) @PathParam("workflowId") Long workflowId) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);

        return workflow.getStarredUsers();
    }

    /**
     *
     * @param user  The user the workflow belongs to
     * @param workflowId    The ID of the workflow to change descriptor type
     * @param descriptorLanguage    The descriptor type to change to
     * @return  The modified workflow
     */
    @POST
    @Path("/{workflowId}/descriptorType")
    @Timed
    @UnitOfWork
    @Operation(operationId = "updateDescriptorType", summary = "Changes the descriptor type of an unpublished, invalid workflow.", description = "Use with caution. This deletes all the workflowVersions, only use if there's nothing worth keeping in the workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "hidden", hidden = true)
    public Workflow updateLanguage(
            @ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
            @ApiParam(value = "Workflow to grab starred users for.", required = true) @PathParam("workflowId") Long workflowId,
            @ApiParam(value = "Descriptor type to update to", required = true) @QueryParam("descriptorType") DescriptorLanguage descriptorLanguage) {
        Workflow workflow = workflowDAO.findById(workflowId);
        checkEntry(workflow);
        checkUser(user, workflow);
        if (workflow.getIsPublished()) {
            throw new CustomWebApplicationException("Cannot change descriptor type of a published workflow", Response.Status.BAD_REQUEST.getStatusCode());
        } else {
            Set<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
            workflowVersions.forEach(workflowVersion -> {
                if (workflowVersion.isValid()) {
                    throw new CustomWebApplicationException("Cannot change descriptor type of a valid workflow", Response.Status.BAD_REQUEST.getStatusCode());
                }
            });
            // If the language was wrong, then is any workflowVersion even worth keeping?
            // If there's no workflowVersions, is the workflow even worth keeping? Maybe just delete the workflow? Maybe keep for events?
            workflow.setWorkflowVersions(new HashSet<>());
            if (descriptorLanguage == null) {
                throw new CustomWebApplicationException("Descriptor type must be not be null", Response.Status.BAD_REQUEST.getStatusCode());
            }
            workflow.setDescriptorType(descriptorLanguage);
            return workflow;
        }
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/{entryId}/registerCheckerWorkflow/{descriptorType}")
    @Operation(operationId = "registerCheckerWorkflow", description = "Register a checker workflow and associates it with the given tool/workflow.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Register a checker workflow and associates it with the given tool/workflow.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Entry.class)
    @SuppressWarnings("checkstyle:MagicNumber")
    public Entry registerCheckerWorkflow(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Path of the main descriptor of the checker workflow (located in associated tool/workflow repository)", required = true) @QueryParam("checkerWorkflowPath") String checkerWorkflowPath,
        @ApiParam(value = "Default path to test parameter files for the checker workflow. If not specified will use that of the entry.") @QueryParam("testParameterPath") String testParameterPath,
        @ApiParam(value = "Entry Id of parent tool/workflow.", required = true) @PathParam("entryId") Long entryId,
        @ApiParam(value = "Descriptor type of the workflow, either cwl or wdl.", required = true, allowableValues = "cwl, wdl") @PathParam("descriptorType") String descriptorType) {
        // Find the entry
        Entry<? extends Entry, ? extends Version> entry = toolDAO.getGenericEntryById(entryId);

        // Check if valid descriptor type
        if (!Objects.equals(descriptorType, DescriptorType.CWL.toString().toLowerCase()) && !Objects
            .equals(descriptorType, DescriptorType.WDL.toString().toLowerCase())) {
            throw new CustomWebApplicationException(descriptorType + " is not a valid descriptor type. Only cwl and wdl are valid.",
                HttpStatus.SC_BAD_REQUEST);
        }

        checkEntry(entry);
        checkUser(user, entry);

        // Don't allow workflow stubs
        if (entry instanceof Workflow) {
            Workflow workflow = (Workflow)entry;
            if (Objects.equals(workflow.getMode().name(), WorkflowMode.STUB.toString())) {
                throw new CustomWebApplicationException("Checker workflows cannot be added to workflow stubs.", HttpStatus.SC_BAD_REQUEST);
            }
        }

        // Ensure that the entry has no checker workflows already
        if (entry.getCheckerWorkflow() != null) {
            throw new CustomWebApplicationException("The given entry already has a checker workflow.", HttpStatus.SC_BAD_REQUEST);
        }

        // Checker workflow variables
        String defaultTestParameterPath;
        String organization;
        String repository;
        SourceControl sourceControl;
        boolean isPublished;
        String gitUrl;
        Date lastUpdated;
        String workflowName;

        // Grab information if tool
        if (entry instanceof Tool) {
            // Get tool
            Tool tool = (Tool)entry;

            // Generate workflow name
            workflowName = MoreObjects.firstNonNull(tool.getToolname(), "");

            // Get default test parameter path and toolname
            if (Objects.equals(descriptorType.toLowerCase(), DescriptorType.WDL.toString().toLowerCase())) {
                workflowName += "_wdl_checker";
                defaultTestParameterPath = tool.getDefaultTestWdlParameterFile();
            } else if (Objects.equals(descriptorType.toLowerCase(), DescriptorType.CWL.toString().toLowerCase())) {
                workflowName += "_cwl_checker";
                defaultTestParameterPath = tool.getDefaultTestCwlParameterFile();
            } else {
                throw new UnsupportedOperationException(
                    "The descriptor type " + descriptorType + " is not valid.\nSupported types include cwl and wdl.");
            }

            // Determine gitUrl
            gitUrl = tool.getGitUrl();

            // Determine source control, org, and repo
            Pattern p = Pattern.compile("git@(\\S+):(\\S+)/(\\S+)\\.git");
            Matcher m = p.matcher(tool.getGitUrl());
            if (m.find()) {
                SourceControlConverter converter = new SourceControlConverter();
                sourceControl = converter.convertToEntityAttribute(m.group(1));
                organization = m.group(2);
                repository = m.group(3);
            } else {
                throw new CustomWebApplicationException("Problem parsing git url.", HttpStatus.SC_BAD_REQUEST);
            }

            // Determine publish information
            isPublished = tool.getIsPublished();

            // Determine last updated
            lastUpdated = tool.getLastUpdated();

        } else if (entry instanceof Workflow) {
            // Get workflow
            Workflow workflow = (Workflow)entry;

            // Copy over common attributes
            defaultTestParameterPath = workflow.getDefaultTestParameterFilePath();
            organization = workflow.getOrganization();
            repository = workflow.getRepository();
            sourceControl = workflow.getSourceControl();
            isPublished = workflow.getIsPublished();
            gitUrl = workflow.getGitUrl();
            lastUpdated = workflow.getLastUpdated();

            // Generate workflow name
            workflowName = MoreObjects.firstNonNull(workflow.getWorkflowName(), "");

            if (workflow.getDescriptorType() == CWL) {
                workflowName += CWL_CHECKER;
            } else if (workflow.getDescriptorType() == WDL) {
                workflowName += WDL_CHECKER;
            } else {
                throw new UnsupportedOperationException("The descriptor type " + workflow.getDescriptorType().getShortName()
                    + " is not valid.\nSupported types include cwl and wdl.");
            }
        } else {
            throw new CustomWebApplicationException("No entry with the given ID exists.", HttpStatus.SC_BAD_REQUEST);
        }

        // Create checker workflow
        BioWorkflow checkerWorkflow = new BioWorkflow();
        checkerWorkflow.setMode(WorkflowMode.STUB);
        checkerWorkflow.setDescriptorType(DescriptorLanguage.convertShortStringToEnum(descriptorType));
        checkerWorkflow.setDefaultWorkflowPath(checkerWorkflowPath);
        checkerWorkflow.setDefaultTestParameterFilePath(defaultTestParameterPath);
        checkerWorkflow.setOrganization(organization);
        checkerWorkflow.setRepository(repository);
        checkerWorkflow.setSourceControl(sourceControl);
        checkerWorkflow.setIsPublished(isPublished);
        checkerWorkflow.setGitUrl(gitUrl);
        checkerWorkflow.setLastUpdated(lastUpdated);
        checkerWorkflow.setWorkflowName(workflowName);
        checkerWorkflow.setIsChecker(true);

        // Deal with possible custom default test parameter file
        if (testParameterPath != null) {
            checkerWorkflow.setDefaultTestParameterFilePath(testParameterPath);
        } else {
            checkerWorkflow.setDefaultTestParameterFilePath(defaultTestParameterPath);
        }

        // Persist checker workflow
        long id = workflowDAO.create(checkerWorkflow);
        checkerWorkflow.addUser(user);
        checkerWorkflow = (BioWorkflow)workflowDAO.findById(id);
        PublicStateManager.getInstance().handleIndexUpdate(checkerWorkflow, StateManagerMode.UPDATE);

        // Update original entry with checker id
        entry.setCheckerWorkflow(checkerWorkflow);

        // Return the original entry
        Entry<? extends Entry, ? extends Version> genericEntry = toolDAO.getGenericEntryById(entryId);
        Hibernate.initialize(genericEntry.getWorkflowVersions());
        return genericEntry;
    }

    /**
     * If include contains validations field, initialize the workflows validations for all of its workflow versions
     * If include contains aliases field, initialize the aliases for all of its workflow versions
     * If include contains images field, initialize the images for all of its workflow versions
     * @param include
     * @param workflow
     */
    private void initializeAdditionalFields(String include, Workflow workflow) {
        if (checkIncludes(include, VALIDATIONS)) {
            workflow.getWorkflowVersions().forEach(workflowVersion -> Hibernate.initialize(workflowVersion.getValidations()));
        }
        if (checkIncludes(include, ALIASES)) {
            workflow.getWorkflowVersions().forEach(workflowVersion -> Hibernate.initialize(workflowVersion.getAliases()));
        }
        if (checkIncludes(include, IMAGES)) {
            workflow.getWorkflowVersions().stream().filter(v -> v.isFrozen()).forEach(workflowVersion -> Hibernate.initialize(workflowVersion.getImages()));
        }
        if (checkIncludes(include, VERSIONS)) {
            Hibernate.initialize(workflow.getWorkflowVersions());
        }
    }

    @Override
    public WorkflowDAO getDAO() {
        return this.workflowDAO;
    }

    /**
     * Throws an exception if the workflow is hosted
     * @param workflow
     */
    private void checkNotHosted(Workflow workflow) {
        if (workflow.getMode() == WorkflowMode.HOSTED) {
            throw new CustomWebApplicationException("Cannot modify hosted entries this way", HttpStatus.SC_BAD_REQUEST);
        }
    }

    /**
     * Throws an exception if the workflow is a service
     * @param workflow
     */
    private void checkNotService(Workflow workflow) {
        if (workflow.getDescriptorType() == DescriptorLanguage.SERVICE) {
            throw new CustomWebApplicationException("Cannot modify services this way", HttpStatus.SC_BAD_REQUEST);
        }
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{workflowId}/zip/{workflowVersionId}")
    @Operation(operationId = "getWorkflowZip", description = "Download a ZIP file of a workflow and all associated files.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Download a ZIP file of a workflow and all associated files.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    @Produces("application/zip")
    public Response getWorkflowZip(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth Optional<User> user,
        @ApiParam(value = "workflowId", required = true) @PathParam("workflowId") Long workflowId,
        @ApiParam(value = "workflowVersionId", required = true) @PathParam("workflowVersionId") Long workflowVersionId) {

        Workflow workflow = workflowDAO.findById(workflowId);
        if (workflow == null) {
            throw new CustomWebApplicationException("could not find tool", HttpStatus.SC_NOT_FOUND);
        }
        checkOptionalAuthRead(user, workflow);

        WorkflowVersion workflowVersion = getWorkflowVersion(workflow, workflowVersionId);
        Set<SourceFile> sourceFiles = workflowVersion.getSourceFiles();
        java.nio.file.Path path = Paths.get(workflowVersion.getWorkingDirectory());
        if (sourceFiles == null || sourceFiles.size() == 0) {
            throw new CustomWebApplicationException("no files found to zip", HttpStatus.SC_NO_CONTENT);
        }

        String fileName = EntryVersionHelper.generateZipFileName(workflow.getWorkflowPath(), workflowVersion.getName());

        return Response.ok().entity((StreamingOutput)output -> writeStreamAsZip(sourceFiles, output, path))
            .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"").build();
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("{alias}/aliases")
    @Operation(operationId = "getWorkflowByAlias", description = "Retrieves a workflow by alias.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Retrieves a workflow by alias.", notes = OPTIONAL_AUTH_MESSAGE, response = Workflow.class, authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public Workflow getWorkflowByAlias(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth Optional<User> user,
            @ApiParam(value = "Alias", required = true) @PathParam("alias") String alias) {
        final Workflow workflow = this.workflowDAO.findByAlias(alias);
        checkEntry(workflow);
        optionalUserCheckEntry(user, workflow);
        return workflow;
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/registries/{gitRegistry}/organizations/{organization}/repositories/{repositoryName}")
    @Operation(operationId = "addWorkflow", description = "Adds a workflow for a registry and repository path with defaults set.", security = @SecurityRequirement(name = "bearer"))
    @ApiOperation(value = "See OpenApi for details")
    public BioWorkflow addWorkflow(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
                                   @Parameter(name = "gitRegistry", description = "Git registry", required = true, in = ParameterIn.PATH) @PathParam("gitRegistry") SourceControl gitRegistry,
                                   @Parameter(name = "organization", description = "Git repository organization", required = true, in = ParameterIn.PATH) @PathParam("organization") String organization,
                                   @Parameter(name = "repositoryName", description = "Git repository name", required = true, in = ParameterIn.PATH) @PathParam("repositoryName") String repositoryName) {
        User foundUser = userDAO.findById(authUser.getId());


        // Find matching source control
        List<Token> scTokens = getAndRefreshTokens(foundUser, tokenDAO, client, bitbucketClientID, bitbucketClientSecret)
                .stream()
                .filter(token -> Objects.equals(token.getTokenSource().getSourceControl(), gitRegistry))
                .collect(Collectors.toList());

        // Add repository as workflow
        if (scTokens.size() == 0) {
            String msg = "User does not have access to the given source control registry.";
            LOG.error(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        final Token gitToken = scTokens.get(0);

        SourceCodeRepoInterface sourceCodeRepo = SourceCodeRepoFactory.createSourceCodeRepo(gitToken);
        final String tokenSource = gitToken.getTokenSource().toString();
        final String repository = organization + "/" + repositoryName;

        String gitUrl = "git@" + tokenSource + ":" + repository + ".git";
        LOG.info("Adding " + gitUrl);

        // Create a workflow
        final Workflow createdWorkflow = sourceCodeRepo.createStubBioworkflow(repository);
        return saveNewWorkflow(createdWorkflow, foundUser);
    }

    /**
     * Saves a new workflow to the database
     * @param workflow
     * @param user
     * @return New workflow
     */
    private BioWorkflow saveNewWorkflow(Workflow workflow, User user) {
        // Check for duplicate
        Optional<BioWorkflow> duplicate = workflowDAO.findByPath(workflow.getWorkflowPath(), false, BioWorkflow.class);
        if (duplicate.isPresent()) {
            throw new CustomWebApplicationException("A workflow with the same path and name already exists.", HttpStatus.SC_BAD_REQUEST);
        }
        final long workflowID = workflowDAO.create(workflow);
        final Workflow workflowFromDB = workflowDAO.findById(workflowID);
        workflowFromDB.getUsers().add(user);
        return (BioWorkflow)workflowFromDB;
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/registries/{gitRegistry}/organizations/{organization}/repositories/{repositoryName}")
    @Operation(operationId = "deleteWorkflow", description = "Delete a stubbed workflow for a registry and repository path.", security = @SecurityRequirement(name = "bearer"))
    @ApiOperation(value = "See OpenApi for details")
    public void deleteWorkflow(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
                               @Parameter(name = "gitRegistry", description = "Git registry", required = true, in = ParameterIn.PATH) @PathParam("gitRegistry") SourceControl gitRegistry,
                               @Parameter(name = "organization", description = "Git repository organization", required = true, in = ParameterIn.PATH) @PathParam("organization") String organization,
                               @Parameter(name = "repositoryName", description = "Git repository name", required = true, in = ParameterIn.PATH) @PathParam("repositoryName") String repositoryName) {
        User foundUser = userDAO.findById(authUser.getId());

        // Get all of the users source control tokens
        List<Token> scTokens = getAndRefreshTokens(foundUser, tokenDAO, client, bitbucketClientID, bitbucketClientSecret)
                .stream()
                .filter(token -> Objects.equals(token.getTokenSource().getSourceControl(), gitRegistry))
                .collect(Collectors.toList());

        if (scTokens.size() == 0) {
            LOG.error(SC_REGISTRY_ACCESS_MESSAGE);
            throw new CustomWebApplicationException(SC_REGISTRY_ACCESS_MESSAGE, HttpStatus.SC_BAD_REQUEST);
        }

        // Delete workflow for a given repository
        final Token gitToken = scTokens.get(0);
        final String tokenSource = gitToken.getTokenSource().toString();
        final String repository = organization + "/" + repositoryName;

        String gitUrl = "git@" + tokenSource + ":" + repository + ".git";
        LOG.info("Deleting " + gitUrl);

        final Optional<BioWorkflow> existingWorkflow = workflowDAO.findByPath(tokenSource + "/" + repository, false, BioWorkflow.class);
        if (existingWorkflow.isEmpty()) {
            String msg = "No workflow with path " + tokenSource + "/" + repository + " exists.";
            LOG.error(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        BioWorkflow workflow = existingWorkflow.get();
        checkUser(foundUser, workflow);
        if (Objects.equals(workflow.getMode(), WorkflowMode.STUB)) {
            PublicStateManager.getInstance().handleIndexUpdate(existingWorkflow.get(), StateManagerMode.DELETE);
            eventDAO.deleteEventByEntryID(workflow.getId());
            workflowDAO.delete(workflow);
        } else {
            String msg = "The workflow with path " + tokenSource + "/" + repository + " cannot be deleted.";
            LOG.error(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }
    }

    @POST
    @Path("/github/release")
    @Timed
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @UnitOfWork
    @RolesAllowed({ "curator", "admin" })
    @Operation(description = "Handle a release of a repository on GitHub. Will create a workflow/service and version when necessary.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Handle a release of a repository on GitHub. Will create a workflow/service and version when necessary.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    public void handleGitHubRelease(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @Parameter(name = "repository", description = "Repository path (ex. dockstore/dockstore-ui2)", required = true) @FormParam("repository") String repository,
        @Parameter(name = "username", description = "Username of user on GitHub who triggered action", required = true) @FormParam("username") String username,
        @Parameter(name = "gitReference", description = "Full git reference for a GitHub branch/tag. Ex. refs/heads/master or refs/tags/v1.0", required = true) @FormParam("gitReference") String gitReference,
        @Parameter(name = "installationId", description = "GitHub installation ID", required = true) @FormParam("installationId") String installationId) {
        LOG.info("Branch/tag " + gitReference + " pushed to " + repository + "(" + username + ")");
        githubWebhookRelease(repository, username, gitReference, installationId);
    }

    @POST
    @Path("/github/install")
    @Timed
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @UnitOfWork
    @RolesAllowed({ "curator", "admin" })
    @Operation(description = "Handle the installation of our GitHub app onto a repository or organization.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME), responses = @ApiResponse(responseCode = "418", description = "This code tells AWS Lambda not to retry."))
    @ApiOperation(value = "Handle the installation of our GitHub app onto a repository or organization.", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class, responseContainer = "List")
    public Response handleGitHubInstallation(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @Parameter(name = "repositories", description = "Comma-separated repository paths (ex. dockstore/dockstore-ui2) for all repositories installed", required = true) @FormParam("repositories") String repositories,
            @Parameter(name = "username", description = "Username of user on GitHub who triggered action", required = true) @FormParam("username") String username,
            @Parameter(name = "installationId", description = "GitHub installation ID", required = true) @FormParam("installationId") String installationId) {
        LOG.info("GitHub app installed on the repositories " + repositories + "(" + username + ")");
        Optional<User> triggerUser = Optional.ofNullable(userDAO.findByGitHubUsername(username));
        Arrays.asList(repositories.split(",")).stream().forEach(repository -> {
            LambdaEvent lambdaEvent = new LambdaEvent();
            String[] splitRepository = repository.split("/");
            lambdaEvent.setOrganization(splitRepository[0]);
            lambdaEvent.setRepository(splitRepository[1]);
            lambdaEvent.setGithubUsername(username);
            lambdaEvent.setType(LambdaEvent.LambdaEventType.INSTALL);
            triggerUser.ifPresent(lambdaEvent::setUser);
            lambdaEventDAO.create(lambdaEvent);
        });
        return Response.status(HttpStatus.SC_OK).build();
    }

    @DELETE
    @Path("/github")
    @Timed
    @UnitOfWork
    @RolesAllowed({ "curator", "admin" })
    @Operation(description = "Handles the deletion of a branch on GitHub. Will delete all workflow versions that match in all workflows that share the same repository.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME), responses = @ApiResponse(responseCode = "418", description = "This code tells AWS Lambda not to retry."))
    @ApiOperation(value = "Handles the deletion of a branch on GitHub. Will delete all workflow versions that match in all workflows that share the same repository.", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Response.class)
    public Response handleGitHubBranchDeletion(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @Parameter(name = "repository", description = "Repository path (ex. dockstore/dockstore-ui2)", required = true) @QueryParam("repository") String repository,
            @Parameter(name = "username", description = "Username of user on GitHub who triggered action", required = true) @QueryParam("username") String username,
            @Parameter(name = "gitReference", description = "Full git reference for a GitHub branch/tag. Ex. refs/heads/master or refs/tags/v1.0", required = true) @QueryParam("gitReference") String gitReference,
            @Parameter(name = "installationId", description = "GitHub installation ID", required = true) @QueryParam("installationId") String installationId) {
        LOG.info("Branch/tag " + gitReference + " deleted from " + repository);
        githubWebhookDelete(repository, gitReference, username, installationId);
        return Response.status(HttpStatus.SC_NO_CONTENT).build();
    }
}
