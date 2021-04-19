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

import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.Lists;
import io.dockstore.common.Registry;
import io.dockstore.common.Repository;
import io.dockstore.common.SourceControl;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.api.Limits;
import io.dockstore.webservice.api.PrivilegeRequest;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.CloudInstance;
import io.dockstore.webservice.core.Collection;
import io.dockstore.webservice.core.DeletedUsername;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.EntryUpdateTime;
import io.dockstore.webservice.core.ExtendedUserData;
import io.dockstore.webservice.core.LambdaEvent;
import io.dockstore.webservice.core.Organization;
import io.dockstore.webservice.core.OrganizationUpdateTime;
import io.dockstore.webservice.core.OrganizationUser;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.SourceControlOrganization;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.TokenType;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowMode;
import io.dockstore.webservice.core.database.EntryLite;
import io.dockstore.webservice.core.database.MyWorkflows;
import io.dockstore.webservice.helpers.DeletedUserHelper;
import io.dockstore.webservice.helpers.EntryVersionHelper;
import io.dockstore.webservice.helpers.GoogleHelper;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.helpers.SourceCodeRepoFactory;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.jdbi.BioWorkflowDAO;
import io.dockstore.webservice.jdbi.DeletedUsernameDAO;
import io.dockstore.webservice.jdbi.EntryDAO;
import io.dockstore.webservice.jdbi.EventDAO;
import io.dockstore.webservice.jdbi.LambdaEventDAO;
import io.dockstore.webservice.jdbi.ServiceDAO;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dockstore.webservice.permissions.PermissionsInterface;
import io.dropwizard.auth.Auth;
import io.dropwizard.auth.CachingAuthenticator;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import io.swagger.jaxrs.PATCH;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.webservice.Constants.JWT_SECURITY_DEFINITION_NAME;
import static io.dockstore.webservice.resources.ResourceConstants.APPEASE_SWAGGER_PATCH;
import static io.dockstore.webservice.resources.ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME;
import static io.dockstore.webservice.resources.ResourceConstants.PAGINATION_LIMIT;
import static io.dockstore.webservice.resources.ResourceConstants.PAGINATION_LIMIT_TEXT;
import static io.dockstore.webservice.resources.ResourceConstants.PAGINATION_OFFSET_TEXT;

/**
 * @author xliu
 */
@Path("/users")
@Api("/users")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "users", description = ResourceConstants.USERS)
public class UserResource implements AuthenticatedResourceInterface, SourceControlResourceInterface {
    private static final Logger LOG = LoggerFactory.getLogger(UserResource.class);
    private static final Pattern USERNAME_CONTAINS_KEYWORD_PATTERN = Pattern.compile("(?i)(dockstore|admin|curator|system|manager)");
    private static final Pattern VALID_USERNAME_PATTERN = Pattern.compile("^[a-zA-Z]+[.a-zA-Z0-9-_]*$");
    private static final String CLOUD_INSTANCE_ID_DESCRIPTION = "ID of cloud instance to update/delete";
    private final UserDAO userDAO;
    private final TokenDAO tokenDAO;

    private final WorkflowResource workflowResource;
    private final DockerRepoResource dockerRepoResource;
    private final WorkflowDAO workflowDAO;
    private final ToolDAO toolDAO;
    private final BioWorkflowDAO bioWorkflowDAO;
    private final ServiceDAO serviceDAO;
    private final EventDAO eventDAO;
    private final LambdaEventDAO lambdaEventDAO;
    private final DeletedUsernameDAO deletedUsernameDAO;
    private final PermissionsInterface authorizer;
    private final CachingAuthenticator cachingAuthenticator;
    private final HttpClient client;

    private final String bitbucketClientSecret;
    private final String bitbucketClientID;

    @SuppressWarnings("checkstyle:parameternumber")
    public UserResource(HttpClient client, SessionFactory sessionFactory, WorkflowResource workflowResource, ServiceResource serviceResource,
                        DockerRepoResource dockerRepoResource, CachingAuthenticator cachingAuthenticator, PermissionsInterface authorizer, DockstoreWebserviceConfiguration configuration) {
        this.eventDAO = new EventDAO(sessionFactory);
        this.userDAO = new UserDAO(sessionFactory);
        this.tokenDAO = new TokenDAO(sessionFactory);
        this.workflowDAO = new WorkflowDAO(sessionFactory);
        this.toolDAO = new ToolDAO(sessionFactory);
        this.bioWorkflowDAO = new BioWorkflowDAO(sessionFactory);
        this.serviceDAO = new ServiceDAO(sessionFactory);
        this.lambdaEventDAO = new LambdaEventDAO(sessionFactory);
        this.deletedUsernameDAO = new DeletedUsernameDAO(sessionFactory);
        this.workflowResource = workflowResource;
        this.dockerRepoResource = dockerRepoResource;
        this.authorizer = authorizer;
        this.cachingAuthenticator = cachingAuthenticator;
        this.client = client;
        this.bitbucketClientID = configuration.getBitbucketClientID();
        this.bitbucketClientSecret = configuration.getBitbucketClientSecret();
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/username/{username}")
    @Operation(operationId = "listUser", description = "Get a user by username.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get a user by username.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = User.class)
    public User listUser(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
            @ApiParam("Username of user to return") @PathParam("username") String username) {
        @SuppressWarnings("deprecation")
        User user = userDAO.findByUsername(username);
        checkUser(authUser, user.getId());
        return user;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{userId}")
    @Operation(operationId = "getSpecificUser", description = "Get user by id.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "getSpecificUser", value = "Get user by id.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = User.class)
    public User getUser(@ApiParam(hidden = true) @Parameter(hidden = true) @Auth User authUser, @ApiParam("User to return") @PathParam("userId") long userId) {
        checkUser(authUser, userId);
        User user = userDAO.findById(userId);
        if (user == null) {
            throw new CustomWebApplicationException("User not found.", HttpStatus.SC_BAD_REQUEST);
        }
        return user;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/user")
    @Operation(operationId = "getUser", description = "Get the logged-in user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "getUser", value = "Get the logged-in user.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = User.class)
    public User getUser(@ApiParam(hidden = true) @Parameter(hidden = true) @Auth User user) {
        User foundUser = userDAO.findById(user.getId());
        Hibernate.initialize(foundUser.getUserProfiles());
        return foundUser;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/user/memberships")
    @Operation(operationId = "getUserMemberships", description = "Get the logged-in user's memberships.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get the logged-in user's memberships.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = OrganizationUser.class, responseContainer = "set")
    public Set<OrganizationUser> getUserMemberships(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user) {
        User foundUser = userDAO.findById(user.getId());
        Set<OrganizationUser> organizationUsers = foundUser.getOrganizations();
        organizationUsers.forEach(organizationUser -> Hibernate.initialize(organizationUser.getOrganization()));
        return organizationUsers;
    }



    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/user/extended")
    @Operation(operationId = "getExtendedUserData", description = "Get additional information about the authenticated user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get additional information about the authenticated user.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = ExtendedUserData.class)
    public ExtendedUserData getExtendedUserData(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user) {
        User foundUser = userDAO.findById(user.getId());
        return new ExtendedUserData(foundUser, this.authorizer, userDAO);
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/user/changeUsername")
    @Operation(operationId = "changeUsername", description = "Change username if possible.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Change username if possible.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = User.class)
    public User changeUsername(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser, @ApiParam("Username to change to") @QueryParam("username") String username) {
        checkUser(authUser, authUser.getId());
        if (!VALID_USERNAME_PATTERN.asPredicate().test(username)) {
            throw new CustomWebApplicationException("Username pattern invalid", HttpStatus.SC_BAD_REQUEST);
        }
        restrictUsername(username);

        User user = userDAO.findById(authUser.getId());
        if (!new ExtendedUserData(user, this.authorizer, userDAO).canChangeUsername()) {
            throw new CustomWebApplicationException("Cannot change username, user not ready", HttpStatus.SC_BAD_REQUEST);
        }

        if (userDAO.findByUsername(username) != null || DeletedUserHelper.nonReusableUsernameFound(username, deletedUsernameDAO)) {
            String errorMsg = "Cannot change user to " + username + " because it is already in use";
            LOG.error(errorMsg);
            throw new CustomWebApplicationException(errorMsg, HttpStatus.SC_BAD_REQUEST);
        }


        user.setUsername(username);
        user.setSetupComplete(true);
        userDAO.clearCache();
        List<Token> tokens = tokenDAO.findByUserId(user.getId());
        Optional<Token> dockstoreToken = tokens
                .stream()
                .filter((Token token) -> Objects.equals(TokenType.DOCKSTORE, token.getTokenSource()))
                .findFirst();

        if (dockstoreToken.isPresent()) {
            dockstoreToken.get().setUsername(username);
            cachingAuthenticator.invalidate(dockstoreToken.get().getContent());
        }
        return userDAO.findById(user.getId());
    }

    public static void restrictUsername(String username) {
        Matcher matcher = USERNAME_CONTAINS_KEYWORD_PATTERN.matcher(username);
        if (matcher.find()) {
            throw new CustomWebApplicationException("Cannot change username to " + username
                    + " because it contains one or more of the following keywords: dockstore, admin, curator, system, or manager", HttpStatus.SC_BAD_REQUEST);
        }

    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/user")
    @Operation(operationId = "selfDestruct", description = "Delete user if possible.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Delete user if possible.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Boolean.class)
    public boolean selfDestruct(
            @ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser) {
        checkUser(authUser, authUser.getId());
        User user = userDAO.findById(authUser.getId());
        if (!new ExtendedUserData(user, this.authorizer, userDAO).canChangeUsername()) {
            throw new CustomWebApplicationException("Cannot delete user, user not ready for deletion", HttpStatus.SC_BAD_REQUEST);
        }

        // Remove dangling sharing artifacts before getting rid of tokens
        this.authorizer.selfDestruct(user);

        // Delete entries for which this user is the only user
        deleteSelfFromEntries(user);
        invalidateTokensForUser(user);
        deleteSelfFromLambdaEvents(user);
        boolean isDeleted =  userDAO.delete(user);
        if (isDeleted) {
            DeletedUsername deletedUsername = new DeletedUsername(user.getUsername());
            deletedUsernameDAO.create(deletedUsername);
        }
        return isDeleted;
    }

    private void invalidateTokensForUser(User user) {
        List<Token> byUserId = tokenDAO.findByUserId(user.getId());
        for (Token token : byUserId) {
            tokenDAO.delete(token);
            // invalidate tokens from caching authenticator
            cachingAuthenticator.invalidate(token.getContent());
        }
    }

    private void deleteSelfFromEntries(User user) {
        user.getEntries().stream()
                // The getIsPublished() check is arguably redundant as canChangeUsername(), above, already checks, but just in case...
                .filter(e -> e.getUsers().size() == 1 && !e.getIsPublished())
                .forEach(entry -> {
                    EntryDAO entryDAO;
                    if (entry instanceof Workflow) {
                        entryDAO = workflowDAO;
                    } else if (entry instanceof Tool) {
                        entryDAO = toolDAO;
                    } else {
                        throw new CustomWebApplicationException(
                                MessageFormat.format("Unexpected entry type {0}", entry.getClass().toString()),
                                HttpStatus.SC_INTERNAL_SERVER_ERROR);
                    }
                    eventDAO.deleteEventByEntryID(entry.getId());
                    entryDAO.delete(entry);
                });
    }

    // We don't delete the LambdaEvent because it is useful for other users
    private void deleteSelfFromLambdaEvents(User user) {
        lambdaEventDAO.findByUser(user).stream().forEach(lambdaEvent -> lambdaEvent.setUser(null));
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/user/{userId}")
    @RolesAllowed("admin")
    @Operation(operationId = "terminateUsers", description = "Terminate user if possible.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Terminate user if possible.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Boolean.class, nickname = "terminateUser")
    public boolean terminateUser(
        @ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,  @ApiParam("User to terminate") @PathParam("userId") long targetUserId) {
        // note this terminates the user but leaves behind a tombstone to prevent re-login
        checkUser(authUser, authUser.getId());

        User targetUser = userDAO.findById(targetUserId);
        if (targetUser == null) {
            throw new CustomWebApplicationException("User not found", HttpStatus.SC_BAD_REQUEST);
        }

        invalidateTokensForUser(targetUser);

        targetUser.setBanned(true);
        return true;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/checkUser/{username}")
    @ApiOperation(value = "Check if user with some username exists.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Boolean.class)
    @Operation(operationId = "checkUserExists", description = "Check if user with some username exists.", security = @SecurityRequirement(name = "bearer"))
    public boolean checkUserExists(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
                                   @ApiParam("User name to check") @PathParam("username") String username) {
        @SuppressWarnings("deprecation")
        User foundUser = userDAO.findByUsername(username);
        if (foundUser == null && !DeletedUserHelper.nonReusableUsernameFound(username, deletedUsernameDAO)) {
            return false;
        }
        return true;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{userId}/tokens")
    @Operation(operationId = "getUserTokens", description = "Get tokens with user id.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get tokens with user id.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Token.class, responseContainer = "List")
    public List<Token> getUserTokens(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam("User to return") @PathParam("userId") long userId) {
        checkUser(user, userId);
        return tokenDAO.findByUserId(userId);
    }


    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{userId}/containers/published")
    @Operation(operationId = "userPublishedContainers", description = "List all published tools from a user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "List all published tools from a user.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Tool.class, responseContainer = "List")
    public List<Tool> userPublishedContainers(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam(value = "User ID", required = true) @PathParam("userId") Long userId) {
        checkUser(user, userId);

        // get live entity
        final List<Tool> immutableList = toolDAO.findMyEntriesPublished(user.getId());
        final List<Tool> repositories = Lists.newArrayList(immutableList);
        repositories.removeIf(c -> !c.getIsPublished());
        return repositories;
    }

    //TODO: should separate out services and workflows
    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{userId}/workflows/published")
    @Operation(operationId = "userPublishedWorkflows", description = "List all published workflows from a user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "List all published workflows from a user.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class, responseContainer = "List")
    public List<Workflow> userPublishedWorkflows(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam(value = "User ID", required = true) @PathParam("userId") Long userId) {
        checkUser(user, userId);

        // get live entity
        final List<Workflow> immutableList = workflowDAO.findMyEntriesPublished(user.getId());
        final List<Workflow> repositories = Lists.newArrayList(immutableList);
        repositories.removeIf(workflow -> !workflow.getIsPublished());
        return repositories;
    }

    /**
     *
     * @param authUser
     * @param userId
     * @param organization
     * @param dockerRegistry not really a registry the way we use it now (ex: quay.io), rename in 1.10 this is actually a repository
     * @return
     */
    @GET
    @Timed
    @UnitOfWork
    @Path("/{userId}/containers/{organization}/refresh")
    @Operation(operationId = "refreshToolsByOrganization", description = "Refresh all tools owned by the authenticated user with specified organization.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Refresh all tools owned by the authenticated user with specified organization.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Tool.class, responseContainer = "List")
    public List<Tool> refreshToolsByOrganization(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
            @ApiParam(value = "User ID", required = true) @PathParam("userId") Long userId,
            @ApiParam(value = "Organization", required = true) @PathParam("organization") String organization,
            @ApiParam(value = "Docker registry", required = true) @QueryParam("dockerRegistry") String dockerRegistry) {

        checkUser(authUser, userId);

        // Check if the user has tokens for the organization they're refreshing
        checkToolTokens(authUser, userId, organization);
        if (dockerRegistry == null) {
            throw new CustomWebApplicationException("A repository is required", HttpStatus.SC_BAD_REQUEST);
        }
        dockerRepoResource.refreshToolsForUser(userId, organization, dockerRegistry);


        userDAO.clearCache();
        authUser = userDAO.findById(authUser.getId());
        // Update user data
        authUser.updateUserMetadata(tokenDAO);

        List<Tool> finalTools = getTools(authUser);
        bulkUpsertTools(authUser);
        return finalTools;
    }

    // TODO: Only update the ones that have changed
    private void bulkUpsertTools(User authUser) {
        List<Entry> toolEntries = toolDAO.findMyEntriesPublished(authUser.getId()).stream().map(Entry.class::cast)
                .collect(Collectors.toList());
        if (!toolEntries.isEmpty()) {
            PublicStateManager.getInstance().bulkUpsert(toolEntries);
        }
    }

    private void checkToolTokens(User authUser, Long userId, String organization) {
        List<Token> tokens = tokenDAO.findByUserId(userId);
        List<Tool> tools = userContainers(authUser, userId);
        if (organization != null && !organization.isEmpty()) {
            tools.removeIf(tool -> !tool.getNamespace().equals(organization));
        }
        Token gitLabToken = Token.extractToken(tokens, TokenType.GITLAB_COM);
        Token quayioToken = Token.extractToken(tokens, TokenType.QUAY_IO);
        Set<Registry> uniqueRegistry = new HashSet<>();
        tools.forEach(tool -> uniqueRegistry.add(tool.getRegistryProvider()));
        if (uniqueRegistry.size() == 0 && quayioToken == null) {
            throw new CustomWebApplicationException("You have no tools and no Quay.io token to automatically add tools. Please add a Quay.io token.", HttpStatus.SC_BAD_REQUEST);
        }
        if (uniqueRegistry.contains(Registry.QUAY_IO) && quayioToken == null) {
            throw new CustomWebApplicationException("You have Quay.io tools but no Quay.io token to refresh the tools with. Please add a Quay.io token.", HttpStatus.SC_BAD_REQUEST);
        }
        if (uniqueRegistry.contains(Registry.GITLAB) && gitLabToken == null) {
            throw new CustomWebApplicationException("You have GitLab tools but no GitLab token to refresh the tools with. Please add a GitLab token", HttpStatus.SC_BAD_REQUEST);
        }
    }

    @GET
    @Path("/{userId}/workflows")
    @Timed
    @UnitOfWork(readOnly = true)
    @Operation(operationId = "userWorkflows", description = "List all workflows owned by the authenticated user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME), method = "GET")
    @ApiOperation(value = "List all workflows owned by the authenticated user.", nickname = "userWorkflows", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class, responseContainer = "List")
    public List<Workflow> userWorkflows(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @Parameter(name = "userId", description = "User ID", required = true, in = ParameterIn.PATH) @ApiParam(value = "User ID", required = true) @PathParam("userId") Long userId) {
        checkUser(user, userId);
        final User fetchedUser = this.userDAO.findById(userId);
        if (fetchedUser == null) {
            throw new CustomWebApplicationException("The given user does not exist.", HttpStatus.SC_NOT_FOUND);
        }
        return convertMyWorkflowsToWorkflow(this.bioWorkflowDAO.findUserBioWorkflows(fetchedUser.getId()));
    }

    private List<Workflow> getWorkflows(User user) {
        return bioWorkflowDAO.findMyEntries(user.getId()).stream().map(BioWorkflow.class::cast).collect(Collectors.toList());
    }

    private List<Workflow> convertMyWorkflowsToWorkflow(List<MyWorkflows> myWorkflows) {
        List<Workflow> workflows = new ArrayList<>();
        myWorkflows.forEach(myWorkflow -> {
            Workflow workflow = new BioWorkflow();
            workflow.setOrganization(myWorkflow.getOrganization());
            workflow.setId(myWorkflow.getId());
            workflow.setSourceControl(myWorkflow.getSourceControl());
            workflow.setIsPublished(myWorkflow.isPublished());
            workflow.setWorkflowName(myWorkflow.getWorkflowName());
            workflow.setRepository(myWorkflow.getRepository());
            workflow.setMode(myWorkflow.getMode());
            workflow.setGitUrl(myWorkflow.getGitUrl());
            workflow.setDescription(myWorkflow.getDescription());
            workflows.add(workflow);
        });
        return workflows;
    }

    @GET
    @Path("/{userId}/services")
    @Timed
    @UnitOfWork(readOnly = true)
    @Operation(operationId = "userServices", description = "List all services owned by the authenticated user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "List all services owned by the authenticated user.", nickname = "userServices", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class, responseContainer = "List")
    public List<Workflow> userServices(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam(value = "User ID", required = true) @PathParam("userId") Long userId) {
        checkUser(user, userId);
        final User fetchedUser = this.userDAO.findById(userId);
        if (fetchedUser == null) {
            throw new CustomWebApplicationException("The given user does not exist.", HttpStatus.SC_NOT_FOUND);
        }
        return getStrippedServices(fetchedUser);
    }

    private List<Workflow> getServices(User user) {
        return serviceDAO.findMyEntries(user.getId()).stream().map(Service.class::cast).collect(Collectors.toList());
    }
    private List<Workflow> getStrippedServices(User user) {
        final List<Workflow> services = getServices(user);
        services.forEach(service -> Hibernate.initialize(service.getWorkflowVersions()));
        EntryVersionHelper.stripContent(services, this.userDAO);
        return services;
    }

    private List<Workflow> getStrippedWorkflowsAndServices(User user) {
        final List<Workflow> workflows = workflowDAO.findMyEntries(user.getId());
        EntryVersionHelper.stripContent(workflows, this.userDAO);
        return workflows;

    }

    private List<Tool> getTools(User user) {
        return toolDAO.findMyEntries(user.getId());
    }

    @GET
    @Path("/{userId}/containers")
    @Timed
    @UnitOfWork(readOnly = true)
    @Operation(operationId = "userContainers", description = "List all tools owned by the authenticated user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "List all tools owned by the authenticated user.", nickname = "userContainers", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Tool.class, responseContainer = "List")
    public List<Tool> userContainers(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam(value = "User ID", required = true) @PathParam("userId") Long userId) {
        checkUser(user, userId);
        final User byId = this.userDAO.findById(userId);
        List<Tool> tools = getTools(byId);
        EntryVersionHelper.stripContent(tools, this.userDAO);
        return tools;
    }
    @GET
    @Path("/users/organizations")
    @Timed
    @UnitOfWork(readOnly = true)
    @Operation(operationId = "getUserDockstoreOrganizations", description = "Get all of the Dockstore organizations for a user, sorted by most recently updated.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "See OpenApi for details")
    public List<OrganizationUpdateTime> getUserDockstoreOrganizations(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
                                                            @Parameter(name = "count", description = "Maximum number of organizations to return", in = ParameterIn.QUERY) @QueryParam("count") Integer count,
                                                            @Parameter(name = "filter", description = "Filter paths with matching text", in = ParameterIn.QUERY) @QueryParam("filter") String filter) {
        final List<OrganizationUpdateTime> organizations = new ArrayList<>();
        final User fetchedUser = this.userDAO.findById(authUser.getId());

        // Retrieve all organizations and get timestamps
        Set<OrganizationUser> organizationUsers = fetchedUser.getOrganizations();

        organizationUsers.forEach((OrganizationUser organizationUser) -> {
            Organization organization = organizationUser.getOrganization();
            Optional<Collection> mostRecentCollection = organization.getCollections().stream().max(Comparator.comparing(Collection::getDbUpdateDate));
            Timestamp timestamp = organization.getDbUpdateDate();
            if (mostRecentCollection.isPresent() && timestamp.before(mostRecentCollection.get().getDbUpdateDate())) {
                timestamp = mostRecentCollection.get().getDbUpdateDate();
            }
            organizations.add(new OrganizationUpdateTime(organization.getName(), organization.getDisplayName(), timestamp));
        });

        // Sort all organizations by timestamp
        List<OrganizationUpdateTime> sortedOrganizations = organizations
                .stream()
                .filter((OrganizationUpdateTime organizationUpdateTime) -> filter == null || filter.isBlank() || organizationUpdateTime.getName().toLowerCase().contains(filter.toLowerCase()) || organizationUpdateTime.getDisplayName().toLowerCase().contains(filter.toLowerCase()))
                .sorted(Comparator.comparing(OrganizationUpdateTime::getLastUpdateDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        // Grab subset if necessary
        if (count != null) {
            return sortedOrganizations.subList(0, Math.min(count, sortedOrganizations.size()));
        }
        return sortedOrganizations;
    }

    @GET
    @Path("/users/entries")
    @Timed
    @UnitOfWork(readOnly = true)
    @Operation(operationId = "getUserEntries", description = "Get all of the entries for a user, sorted by most recently updated.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "See OpenApi for details")
    public List<EntryUpdateTime> getUserEntries(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
                                                @Parameter(name = "count", description = "Maximum number of entries to return", in = ParameterIn.QUERY) @QueryParam("count") Integer count,
                                                @Parameter(name = "filter", description = "Filter paths with matching text", in = ParameterIn.QUERY) @QueryParam("filter") String filter) {
        //get entries with only minimal columns from database
        final List<EntryLite> entriesLite = new ArrayList<>();
        final long userId = authUser.getId();
        entriesLite.addAll(toolDAO.findEntryVersions(userId));
        entriesLite.addAll(bioWorkflowDAO.findEntryVersions(userId));
        entriesLite.addAll(serviceDAO.findEntryVersions(userId));

        //cleanup fields for UI: filter(if applicable), sort, and limit by count(if applicable)
        List<EntryUpdateTime> filteredEntries = entriesLite
                .stream().map(e -> new EntryUpdateTime(e.getEntryPath(), e.getPrettyPath(), e.getEntryType(), new Timestamp(e.getLastUpdated().getTime())))
                .filter((EntryUpdateTime entryUpdateTime) -> filter == null || filter.isBlank() || entryUpdateTime.getPath().toLowerCase().contains(filter.toLowerCase()))
                .sorted(Comparator.comparing(EntryUpdateTime::getLastUpdateDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(count != null ? count : Integer.MAX_VALUE)
                .collect(Collectors.toList());
        return filteredEntries;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/starredTools")
    @Operation(operationId = "getStarredTools", description = "Get the authenticated user's starred tools.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get the authenticated user's starred tools.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Entry.class, responseContainer = "List")
    public Set<Entry> getStarredTools(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user) {
        User u = userDAO.findById(user.getId());
        return u.getStarredEntries().stream().filter(element -> element instanceof Tool)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/starredWorkflows")
    @Operation(operationId = "getStarredWorkflows", description = "Get the authenticated user's starred workflows.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get the authenticated user's starred workflows.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Entry.class, responseContainer = "List")
    public Set<Entry> getStarredWorkflows(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user) {
        User u = userDAO.findById(user.getId());
        return u.getStarredEntries().stream().filter(element -> element instanceof Workflow)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/starredOrganizations")
    @Operation(operationId = "getStarredOrganizations", description = "Get the authenticated user's starred organizations.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Get the authenticated user's starred organizations.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Organization.class, responseContainer = "List")
    public Set<Organization> getStarredOrganizations(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user) {
        User u = userDAO.findById(user.getId());
        return u.getStarredOrganizations();
    }

    @GET
    @Timed
    @UnitOfWork
    @RolesAllowed("admin")
    @Path("/updateUserMetadata")
    @Operation(operationId = "updateUserMetadata", description = "Update metadata of all users.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Update metadata of all users.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Admin only.", response = User.class, responseContainer = "List")
    public List<User> updateUserMetadata(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user) {
        List<User> users = userDAO.findAll();
        for (User u : users) {
            u.updateUserMetadata(tokenDAO, false);
        }

        return userDAO.findAll();
    }

    /**
     * TODO: Use enum for the source parameter
     * @param user      The Authorized user
     * @param source    token source, currently either the google or github TokenType
     * @return          The updated user
     */
    @GET
    @Timed
    @UnitOfWork
    @Path("/user/updateUserMetadata")
    @Operation(operationId = "updateLoggedInUserMetadata", description = "Update metadata for logged in user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Update metadata for logged in user.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = User.class)
    public User updateLoggedInUserMetadata(@ApiParam(hidden = true)@Parameter(hidden = true, name = "user")@Auth User user, @ApiParam(value = "Token source", allowableValues = "google.com, github.com") @QueryParam("source") TokenType source) {
        User dbuser = userDAO.findById(user.getId());
        if (source.equals(TokenType.GOOGLE_COM)) {
            updateGoogleAccessToken(user.getId());
        }
        dbuser.updateUserMetadata(tokenDAO, source, true);
        return dbuser;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @RolesAllowed({"admin", "curator"})
    @Path("/user/{userId}/limits")
    @Operation(operationId = "getUserLimits", description = "Returns the specified user's limits. ADMIN or CURATOR only", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Returns the specified user's limits. ADMIN or CURATOR only", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Limits.class)
    public Limits getUserLimits(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
            @ApiParam(value = "User ID", required = true) @PathParam("userId") Long userId) {
        User user = userDAO.findById(userId);
        if (user == null) {
            throw new CustomWebApplicationException("User not found", HttpStatus.SC_NOT_FOUND);
        }
        Limits limits = new Limits();
        limits.setHostedEntryCountLimit(user.getHostedEntryCountLimit());
        limits.setHostedEntryVersionLimit(user.getHostedEntryVersionsLimit());
        return limits;
    }

    @PUT
    @Timed
    @UnitOfWork
    @RolesAllowed({"admin", "curator"})
    @Path("/user/{userId}/limits")
    @Operation(operationId = "setUserLimits", description = "Update the specified user's limits. ADMIN or CURATOR only", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Update the specified user's limits. ADMIN or CURATOR only", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Limits.class)
    public Limits setUserLimits(@ApiParam(hidden = true)  @Parameter(hidden = true, name = "user", in = ParameterIn.HEADER)@Auth User authUser,
            @ApiParam(value = "User ID", required = true) @PathParam("userId") Long userId,
            @ApiParam(value = "Limits to set for a user", required = true) Limits limits) {
        User user = userDAO.findById(userId);
        if (user == null) {
            throw new CustomWebApplicationException("User not found", HttpStatus.SC_NOT_FOUND);
        }
        user.setHostedEntryCountLimit(limits.getHostedEntryCountLimit());
        user.setHostedEntryVersionsLimit(limits.getHostedEntryVersionLimit());
        // User could be cached by Dockstore or Google token -- invalidate all
        tokenDAO.findByUserId(user.getId()).stream().forEach(token -> this.cachingAuthenticator.invalidate(token.getContent()));
        return limits;
    }

    @POST
    @Path("/github/sync")
    @Timed
    @UnitOfWork
    @Operation(operationId = "syncUserWithGitHub", description = "Syncs Dockstore account with GitHub App Installations.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Syncs Dockstore account with GitHub App Installations.", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) },
            response = Workflow.class, responseContainer = "List")
    public List<Workflow> syncUserWithGitHub(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser) {
        final User user = userDAO.findById(authUser.getId());
        workflowResource.syncEntitiesForUser(user);
        userDAO.clearCache();
        return getStrippedWorkflowsAndServices(userDAO.findById(user.getId()));
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/github/organizations")
    @Operation(operationId = "getMyGitHubOrgs", description = "Gets GitHub organizations for current user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiResponses(value = {
            @ApiResponse(responseCode = HttpStatus.SC_OK + "", description = "Descriptions of Github organizations (including but not limited to id, names)", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SourceControlOrganization.class)))),
            @ApiResponse(responseCode = HttpStatus.SC_BAD_REQUEST + "", description = "Bad request")
    })
    public List<SourceControlOrganization> getMyGitHubOrgs(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser) {
        final User user = userDAO.findById(authUser.getId());
        Token githubToken = getAndRefreshTokens(user, tokenDAO, client, bitbucketClientID, bitbucketClientSecret).stream()
                .filter(token -> token.getTokenSource() == TokenType.GITHUB_COM).findFirst().orElse(null);
        if (githubToken != null) {
            SourceCodeRepoInterface sourceCodeRepo =  SourceCodeRepoFactory.createSourceCodeRepo(githubToken);
            return sourceCodeRepo.getOrganizations();
        }
        return Lists.newArrayList();
    }

    @PATCH
    @Timed
    @UnitOfWork
    @Path("/{userId}/workflows")
    @ApiOperation(value = "Adds a user to any Dockstore workflows that they should have access to.", authorizations = {@Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Workflow.class, responseContainer = "List")
    @Operation(operationId = "addUserToDockstoreWorkflows", description = "Adds the logged-in user to any Dockstore workflows that they should have access to.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    public List<Workflow> addUserToDockstoreWorkflows(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
            @ApiParam(name = "userId", required = true, value = "User to update") @PathParam("userId") @Parameter(name = "userId", in = ParameterIn.PATH, description = "User to update", required = true) long userId,
            @ApiParam(name = "emptyBody", value = APPEASE_SWAGGER_PATCH) @Parameter(description = APPEASE_SWAGGER_PATCH, name = "emptyBody") String emptyBody) {
        final User user = userDAO.findById(authUser.getId());
        if (user == null || !Objects.equals(userId, user.getId())) {
            throw new CustomWebApplicationException("The user id provided does not match the logged-in user id.", HttpStatus.SC_BAD_REQUEST);
        }
        // Ignore hosted workflows
        List<SourceControl> sourceControls = Arrays.stream(SourceControl.values()).filter(sourceControl -> !Objects.equals(sourceControl, SourceControl.DOCKSTORE)).collect(
                Collectors.toList());

        List<Token> scTokens = getAndRefreshTokens(user, tokenDAO, client, bitbucketClientID, bitbucketClientSecret)
                .stream()
                .filter(token -> sourceControls.contains(token.getTokenSource().getSourceControl()))
                .collect(Collectors.toList());

        scTokens.forEach(token -> {
            SourceCodeRepoInterface sourceCodeRepo =  SourceCodeRepoFactory.createSourceCodeRepo(token);
            Map<String, String> gitUrlToRepositoryId = sourceCodeRepo.getWorkflowGitUrl2RepositoryId();
            Set<String> organizations = gitUrlToRepositoryId.values().stream().map(repository -> repository.split("/")[0]).collect(Collectors.toSet());

            organizations.forEach(organization -> {
                List<Workflow> workflowsWithoutuser = workflowDAO.findByOrganizationWithoutUser(token.getTokenSource().getSourceControl(), organization, user);
                workflowsWithoutuser.forEach(workflow -> workflow.addUser(user));
            });
        });
        return convertMyWorkflowsToWorkflow(this.bioWorkflowDAO.findUserBioWorkflows(user.getId()));
    }

    @PUT
    @Timed
    @UnitOfWork
    @RolesAllowed({"admin", "curator"})
    @Path("/{userId}/privileges")
    @Consumes("application/json")
    @Operation(operationId = "setUserPrivileges", description = "Updates the provided userID to admin or curator status, ADMIN or CURATOR only", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "Updates the provided userID to admin or curator status, ADMIN or CURATOR only", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = User.class, hidden = true)
    public User setUserPrivilege(@Parameter(hidden = true, name = "user")@Auth User authUser,
                                 @Parameter(name = "User ID", required = true) @PathParam("userId") Long userID,
                                 @Parameter(name = "Set privilege for a user", required = true) PrivilegeRequest privilegeRequest) {
        User user = userDAO.findById(userID);
        if (user == null) {
            throw new CustomWebApplicationException("User not found", HttpStatus.SC_NOT_FOUND);
        }

        // This ensures that the user cannot modify their own privileges.
        if (authUser.getId() == user.getId()) {
            throw new CustomWebApplicationException("You cannot modify your own privileges", HttpStatus.SC_FORBIDDEN);
        }

        // If the request's admin setting is different than the admin status of the user that is being modified, and the auth user is not an admin: Throw an error.
        // This ensures that a curator cannot modify the admin status of any user.
        if (privilegeRequest.isAdmin() != user.getIsAdmin() && !authUser.getIsAdmin()) {
            throw new CustomWebApplicationException("You do not have privileges to modify administrative rights", HttpStatus.SC_FORBIDDEN);
        }

        // Else if the request's settings is different from the privileges of the user that is being modified: update the privileges with the request
        if (privilegeRequest.isAdmin() != user.getIsAdmin() || privilegeRequest.isCurator() != user.isCurator()) {
            user.setIsAdmin(privilegeRequest.isAdmin());
            user.setCurator(privilegeRequest.isCurator());
            tokenDAO.findByUserId(user.getId()).stream().forEach(token -> this.cachingAuthenticator.invalidate(token.getContent()));
        }
        return user;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/github/events")
    @Operation(operationId = "getUserGitHubEvents", description = "Get all of the GitHub Events for the logged in user.", security = @SecurityRequirement(name = "bearer"))
    @ApiOperation(value = "See OpenApi for details")
    public List<LambdaEvent> getUserGitHubEvents(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
            @ApiParam(value = PAGINATION_OFFSET_TEXT) @QueryParam("offset") String offset,
            @ApiParam(value = PAGINATION_LIMIT_TEXT, allowableValues = "range[1,100]", defaultValue = PAGINATION_LIMIT) @DefaultValue(PAGINATION_LIMIT) @QueryParam("limit") Integer limit) {
        final User user = userDAO.findById(authUser.getId());
        return lambdaEventDAO.findByUser(user, offset, limit);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/registries")
    @Operation(operationId = "getUserRegistries", description = "Get all of the git registries accessible to the logged in user.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "See OpenApi for details")
    public List<SourceControl> getUserRegistries(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser) {
        return tokenDAO.findByUserId(authUser.getId())
                .stream()
                .filter(token -> token.getTokenSource().isSourceControlToken())
                .map(token -> token.getTokenSource().getSourceControl())
                .collect(Collectors.toList());
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/registries/{gitRegistry}/organizations")
    @Operation(operationId = "getUserOrganizations", description = "Get all of the organizations for a given git registry accessible to the logged in user.", security = @SecurityRequirement(name = "bearer"))
    @ApiOperation(value = "See OpenApi for details")
    public Set<String> getUserOrganizations(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
                                            @Parameter(name = "gitRegistry", description = "Git registry", required = true, in = ParameterIn.PATH) @PathParam("gitRegistry") SourceControl gitRegistry) {
        Map<String, String> repositoryUrlToName = getGitRepositoryMap(authUser, gitRegistry);
        return repositoryUrlToName.values().stream().map(repository -> repository.split("/")[0]).collect(Collectors.toSet());
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/registries/{gitRegistry}/organizations/{organization}")
    @Operation(operationId = "getUserOrganizationRepositories", description = "Get all of the repositories for an organization for a given git registry accessible to the logged in user.", security = @SecurityRequirement(name = "bearer"))
    @ApiOperation(value = "See OpenApi for details")
    public List<Repository> getUserOrganizationRepositories(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User authUser,
                                                           @Parameter(name = "gitRegistry", description = "Git registry", required = true, in = ParameterIn.PATH) @PathParam("gitRegistry") SourceControl gitRegistry,
                                                           @Parameter(name = "organization", description = "Git organization", required = true, in = ParameterIn.PATH) @PathParam("organization") String organization) {
        Map<String, String> repositoryUrlToName = getGitRepositoryMap(authUser, gitRegistry);
        return repositoryUrlToName.values().stream()
                .filter(repository -> repository.startsWith(organization + "/"))
                .map(repository -> new Repository(repository.split("/")[0], repository.split("/")[1], gitRegistry, workflowDAO.findByPath(gitRegistry + "/" + repository, false, BioWorkflow.class).isPresent(), canDeleteWorkflow(gitRegistry + "/" + repository)))
                .sorted(Comparator.comparing(Repository::getRepositoryName))
                .collect(Collectors.toList());
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{userId}/cloudInstances")
    @ApiOperation(value = "See OpenApi for details", hidden = true)
    @Operation(operationId = "getUserCloudInstances", description = "Get all cloud instances belonging to the user", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiResponse(responseCode = HttpStatus.SC_OK
            + "", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = CloudInstance.class))))
    public Set<CloudInstance> getUserCloudInstances(@Parameter(hidden = true, name = "user")@Auth User authUser,
        @ApiParam(name = "userId", required = true, value = "User to update") @PathParam("userId") @Parameter(name = "userId", in = ParameterIn.PATH, description = "ID of user to get cloud instances for", required = true) long userId) {
        final User user = userDAO.findById(userId);
        checkUser(authUser, userId);
        return user.getCloudInstances();
    }

    @POST
    @Timed
    @UnitOfWork()
    @Path("/{userId}/cloudInstances")
    @Operation(operationId = "postUserCloudInstance", description = "Create a new cloud instance belonging to the user", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiResponse(responseCode = HttpStatus.SC_NO_CONTENT + "", description = "No Content")
    @ApiResponse(responseCode = HttpStatus.SC_FORBIDDEN + "", description = "Forbidden")
    @ApiResponse(responseCode = HttpStatus.SC_UNAUTHORIZED + "", description = "Unauthorized")
    @ApiOperation(value = "See OpenApi for details", hidden = true)
    public Set<CloudInstance> postUserCloudInstance(@Parameter(hidden = true, name = "user")@Auth User authUser,
            @PathParam("userId") @Parameter(name = "userId", in = ParameterIn.PATH, description = "ID of user to create the cloud instance for", required = true) long userId,
            @Parameter(description = "Cloud instance to add to the user", name = "Cloud Instance", required = true) CloudInstance cloudInstanceBody) {
        final User user = userDAO.findById(userId);
        checkUser(authUser, userId);
        CloudInstance cloudInstanceToBeAdded = new CloudInstance();
        cloudInstanceToBeAdded.setPartner(cloudInstanceBody.getPartner());
        cloudInstanceToBeAdded.setUrl(cloudInstanceBody.getUrl());
        cloudInstanceToBeAdded.setSupportsFileImports(cloudInstanceBody.isSupportsFileImports());
        cloudInstanceToBeAdded.setSupportsHttpImports(cloudInstanceBody.isSupportsHttpImports());
        cloudInstanceToBeAdded.setSupportedLanguages(cloudInstanceBody.getSupportedLanguages());
        // TODO: Figure how to make this not required (already adding the instance to the user)
        cloudInstanceToBeAdded.setUser(user);

        user.getCloudInstances().add(cloudInstanceToBeAdded);
        return user.getCloudInstances();
    }

    @DELETE
    @Timed
    @UnitOfWork()
    @Path("/{userId}/cloudInstances/{cloudInstanceId}")
    @Operation(operationId = "deleteUserCloudInstance", description = "Delete a cloud instance belonging to the user", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiResponse(responseCode = HttpStatus.SC_NO_CONTENT + "", description = "No Content")
    @ApiResponse(responseCode = HttpStatus.SC_FORBIDDEN + "", description = "Forbidden")
    @ApiResponse(responseCode = HttpStatus.SC_UNAUTHORIZED + "", description = "Unauthorized")
    @ApiResponse(responseCode = HttpStatus.SC_NOT_FOUND + "", description = "Not Found")
    @ApiOperation(value = "See OpenApi for details", hidden = true)
    public void deleteUserCloudInstance(@Parameter(hidden = true, name = "user")@Auth User authUser,
            @PathParam("userId") @Parameter(name = "userId", in = ParameterIn.PATH, description = "ID of user to delete the cloud instance for", required = true) long userId,
            @PathParam("cloudInstanceId") @Parameter(name = "cloudInstanceId", in = ParameterIn.PATH, description = CLOUD_INSTANCE_ID_DESCRIPTION, required = true) long cloudInstanceId) {
        final User user = userDAO.findById(userId);
        checkUser(authUser, userId);
        boolean deleted = user.getCloudInstances().removeIf(cloudInstance -> cloudInstance.getId() == cloudInstanceId);
        if (!deleted) {
            throw new CustomWebApplicationException("ID of cloud instance does not exist", HttpStatus.SC_NOT_FOUND);
        }
    }

    @PUT
    @Timed
    @UnitOfWork()
    @Path("/{userId}/cloudInstances/{cloudInstanceId}")
    @Operation(operationId = "putUserCloudInstance", description = "Update a cloud instance belonging to the user", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "See OpenApi for details", hidden = true)
    @ApiResponse(responseCode = HttpStatus.SC_NO_CONTENT + "", description = "No Content")
    @ApiResponse(responseCode = HttpStatus.SC_FORBIDDEN + "", description = "Forbidden")
    @ApiResponse(responseCode = HttpStatus.SC_UNAUTHORIZED + "", description = "Unauthorized")
    @ApiResponse(responseCode = HttpStatus.SC_NOT_FOUND + "", description = "Not Found")
    public void putUserCloudInstance(@Parameter(hidden = true, name = "user")@Auth User authUser,
            @PathParam("userId") @Parameter(name = "userId", in = ParameterIn.PATH, description = "ID of user to update the cloud instance for", required = true) long userId,
            @PathParam("cloudInstanceId") @Parameter(name = "cloudInstanceId", in = ParameterIn.PATH, description = CLOUD_INSTANCE_ID_DESCRIPTION, required = true) long cloudInstanceId,
            @Parameter(description = "Cloud instance to replace for a user", name = "Cloud Instance", required = true) CloudInstance cloudInstanceBody) {
        final User user = userDAO.findById(userId);
        checkUser(authUser, userId);
        Optional<CloudInstance> optionalExistingCloudInstance = user.getCloudInstances().stream()
                .filter(cloudInstance -> cloudInstance.getId() == cloudInstanceId).findFirst();
        if (optionalExistingCloudInstance.isPresent()) {
            CloudInstance cloudInstance = optionalExistingCloudInstance.get();
            cloudInstance.setPartner(cloudInstanceBody.getPartner());
            cloudInstance.setUrl(cloudInstanceBody.getUrl());
            cloudInstance.setSupportsFileImports(cloudInstanceBody.isSupportsFileImports());
            cloudInstance.setSupportsHttpImports(cloudInstanceBody.isSupportsHttpImports());
            cloudInstance.setSupportedLanguages(cloudInstanceBody.getSupportedLanguages());
        } else {
            throw new CustomWebApplicationException("ID of cloud instance does not exist", HttpStatus.SC_NOT_FOUND);
        }
    }

    /**
     * Check if a workflow can be deleted.
     * For now this is simply if a workflow is a stub or not
     * @param path full path to workflow
     * @return can delete workflow
     */
    private boolean canDeleteWorkflow(String path) {
        Optional<BioWorkflow> workflow = workflowDAO.findByPath(path, false, BioWorkflow.class);
        if (workflow.isPresent()) {
            return workflow.get().getMode() == WorkflowMode.STUB;
        }
        return false;
    }

    /**
     * For a given user and git registry, retrieve a map of git url to repository path
     * @param user
     * @param gitRegistry
     * @return mapping of git url to repository path
     */
    private Map<String, String> getGitRepositoryMap(User user, SourceControl gitRegistry) {
        List<Token> scTokens = getAndRefreshTokens(user, tokenDAO, client, bitbucketClientID, bitbucketClientSecret)
                .stream()
                .filter(token -> Objects.equals(token.getTokenSource().getSourceControl(), gitRegistry))
                .collect(Collectors.toList());

        if (scTokens.size() > 0) {
            Token scToken = scTokens.get(0);
            SourceCodeRepoInterface sourceCodeRepo =  SourceCodeRepoFactory.createSourceCodeRepo(scToken);
            return sourceCodeRepo.getWorkflowGitUrl2RepositoryId();
        } else {
            return new HashMap<>();
        }
    }

    /**
     * Updates the user's google access token in the DB
     * @param userId    The user's ID
     */
    private void updateGoogleAccessToken(Long userId) {
        List<Token> googleByUserId = tokenDAO.findGoogleByUserId(userId);
        if (!googleByUserId.isEmpty()) {
            Token googleToken = googleByUserId.get(0);
            Optional<String> validAccessToken = GoogleHelper
                    .getValidAccessToken(googleToken);
            if (validAccessToken.isPresent()) {
                googleToken.setContent(validAccessToken.get());
                tokenDAO.update(googleToken);
            }
        }
    }

}
