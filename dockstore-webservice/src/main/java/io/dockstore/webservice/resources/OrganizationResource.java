package io.dockstore.webservice.resources;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletResponse;
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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.Timed;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.api.StarRequest;
import io.dockstore.webservice.core.Event;
import io.dockstore.webservice.core.Organization;
import io.dockstore.webservice.core.OrganizationUser;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.jdbi.EventDAO;
import io.dockstore.webservice.jdbi.OrganizationDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dropwizard.auth.Auth;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.http.HttpStatus;
import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.webservice.Constants.JWT_SECURITY_DEFINITION_NAME;

/**
 * Collection of organization endpoints
 *
 * @author aduncan
 */
@Path("/organizations")
@Api("/organizations")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "organizations", description = ResourceConstants.ORGANIZATIONS)
@SecuritySchemes({ @SecurityScheme(type = SecuritySchemeType.HTTP, name = "bearer", scheme = "bearer") })
public class OrganizationResource implements AuthenticatedResourceInterface, AliasableResourceInterface<Organization> {
    private static final Logger LOG = LoggerFactory.getLogger(OrganizationResource.class);

    private static final String OPTIONAL_AUTH_MESSAGE = "Does not require authentication for approved organizations, authentication can be provided for unapproved organizations";
    private static final String PAGINATION_LIMIT = "100";
    private static final String DEFAULT_OFFSET = "0";

    private final OrganizationDAO organizationDAO;
    private final UserDAO userDAO;
    private final EventDAO eventDAO;
    private final SessionFactory sessionFactory;

    public OrganizationResource(SessionFactory sessionFactory) {
        this.organizationDAO = new OrganizationDAO(sessionFactory);
        this.userDAO = new UserDAO(sessionFactory);
        this.eventDAO = new EventDAO(sessionFactory);
        this.sessionFactory = sessionFactory;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @ApiOperation(value = "List all available organizations.", notes = "NO Authentication", responseContainer = "List", response = Organization.class)
    @Operation(operationId = "getApprovedOrganizations", summary = "List all available organizations.", description = "List all organizations that have been approved by a curator or admin, sorted by number of stars.")
    public List<Organization> getApprovedOrganizations() {
        List<Organization> organizations = organizationDAO.findApprovedSortedByStar();
        organizations.stream().forEach(org -> Hibernate.initialize(org.getAliases()));
        return organizations;
    }

    @POST
    @Timed
    @UnitOfWork
    @RolesAllowed({ "curator", "admin" })
    @Path("{organizationId}/approve/")
    @ApiOperation(value = "Approve an organization.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Admin/curator only", response = Organization.class)
    @Operation(operationId = "approveOrganization", summary = "Approve an organization.", description = "Approve the organization with the given id. Admin/curator only.", security = @SecurityRequirement(name = "bearer"))
    public Organization approveOrganization(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id) {
        Organization organization = organizationDAO.findById(id);
        throwExceptionForNullOrganization(organization);

        if (!Objects.equals(organization.getStatus(), Organization.ApplicationState.APPROVED)) {
            organization.setStatus(Organization.ApplicationState.APPROVED);

            Event approveOrgEvent = new Event.Builder().withOrganization(organization).withInitiatorUser(user)
                .withType(Event.EventType.APPROVE_ORG).build();
            eventDAO.create(approveOrgEvent);
        }

        return organizationDAO.findById(id);
    }

    @POST
    @Timed
    @UnitOfWork
    @RolesAllowed({ "curator", "admin" })
    @Path("{organizationId}/reject/")
    @ApiOperation(value = "Reject an organization.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Admin/curator only", response = Organization.class)
    @Operation(operationId = "rejectOrganization", summary = "Reject an organization.", description = "Reject the organization with the given id. Admin/curator only.", security = @SecurityRequirement(name = "bearer"))
    public Organization rejectOrganization(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id) {
        Organization organization = organizationDAO.findById(id);
        throwExceptionForNullOrganization(organization);

        if (Objects.equals(organization.getStatus(), Organization.ApplicationState.PENDING)) {
            organization.setStatus(Organization.ApplicationState.REJECTED);

            Event rejectOrgEvent = new Event.Builder().withOrganization(organization).withInitiatorUser(user)
                .withType(Event.EventType.REJECT_ORG).build();
            eventDAO.create(rejectOrgEvent);
        } else if (Objects.equals(organization.getStatus(), Organization.ApplicationState.APPROVED)) {
            String msg = "The organization is already approved";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        return organizationDAO.findById(id);
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("{organizationId}/request/")
    @ApiOperation(value = "Re-request an organization approval.", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Only for rejected organizations", response = Organization.class)
    @Operation(operationId = "requestOrganizationReview", summary = "Re-request an organization review.", description = "Re-request a review of the given organization. Requires the organization to be rejected.", security = @SecurityRequirement(name = "bearer"))
    public Organization requestOrganizationReview(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id) {
        Organization organization = organizationDAO.findById(id);
        throwExceptionForNullOrganization(organization);
        OrganizationUser organizationUser = getUserOrgRole(organization, user.getId());
        if (organizationUser == null || organizationUser.getRole() == OrganizationUser.Role.MEMBER) {
            String msg = "Organization not found";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        if (Objects.equals(organization.getStatus(), Organization.ApplicationState.REJECTED)) {
            organization.setStatus(Organization.ApplicationState.PENDING);
            Event rerequestOrgEvent = new Event.Builder().withOrganization(organization).withInitiatorUser(user)
                    .withType(Event.EventType.REREQUEST_ORG).build();
            eventDAO.create(rerequestOrgEvent);
        } else {
            String msg = "Only rejected organizations can request re-review.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        return organizationDAO.findById(id);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/name/{name}/")
    @ApiOperation(value = "Retrieve an organization by name.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Organization.class)
    @Operation(operationId = "getOrganizationByName", summary = "Retrieve an organization by name.", description = "Retrieve an organization by name. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public Organization getOrganizationByName(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
        @ApiParam(value = "Organization name.", required = true) @Parameter(description = "Organization name.", name = "name", in = ParameterIn.PATH, required = true) @PathParam("name") String name) {
        if (user.isEmpty()) {
            // No user given, only show approved organizations
            Organization organization = organizationDAO.findApprovedByName(name);
            throwExceptionForNullOrganization(organization);
            Hibernate.initialize(organization.getAliases());
            return organization;
        } else {
            // User is given, check if organization is either approved or the user has access
            // Admins and curators should be able to see unapproved organizations
            Organization organization = organizationDAO.findByName(name);
            throwExceptionForNullOrganization(organization);

            // If approved then return
            if (Objects.equals(organization.getStatus(), Organization.ApplicationState.APPROVED)) {
                Hibernate.initialize(organization.getAliases());
                return organization;
            }

            // If user has a role then return
            OrganizationUser role = getUserOrgRole(organization, user.get().getId());

            if (user.get().getIsAdmin() || user.get().isCurator() || role != null) {
                Hibernate.initialize(organization.getAliases());
                return organization;
            } else {
                String msg = "Organization not found";
                LOG.info(msg);
                throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    private void throwExceptionForNullOrganization(Organization organization) {
        if (organization == null) {
            String msg = "Organization not found";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{organizationId}")
    @ApiOperation(value = "Retrieve an organization by ID.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Organization.class)
    @Operation(operationId = "getOrganizationById", summary = "Retrieve an organization by ID.", description = "Retrieve an organization by ID. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public Organization getOrganizationById(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id) {
        Organization organization = getOrganizationByIdOptionalAuth(user, id);
        Hibernate.initialize(organization.getAliases());
        return organization;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{organizationId}/description")
    @ApiOperation(value = "Retrieve an organization description by organization ID.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = String.class)
    @Operation(operationId = "getOrganizationDescription", summary = "Retrieve an organization description by organization ID.", description = "Retrieve an organization description by organization ID. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public String getOrganizationDescription(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id) {
        return getOrganizationByIdOptionalAuth(user, id).getDescription();
    }

    @PUT
    @Timed
    @Path("{organizationId}/description")
    @UnitOfWork
    @ApiOperation(value = "Update an organization's description.", notes = "Description in markdown", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Organization.class)
    @Operation(operationId = "updateOrganizationDescription", summary = "Update an organization's description.", description = "Update an organization's description. Expects description in markdown format.", security = @SecurityRequirement(name = "bearer"))
    public Organization updateOrganizationDescription(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
        @ApiParam(value = "Organization's description in markdown.", required = true) @Parameter(description = "Organization's description in markdown.", name = "description", required = true) String description) {
        boolean doesOrgExist = doesOrganizationExistToUser(organizationId, user.getId());
        if (!doesOrgExist) {
            String msg = "Organization not found";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        Organization oldOrganization = organizationDAO.findById(organizationId);

        // Ensure that the user is a member of the organization
        boolean isUserAdminOrMaintainer = isUserAdminOrMaintainer(oldOrganization, user.getId());
        if (!isUserAdminOrMaintainer) {
            String msg = "You do not have permissions to update the organization.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_UNAUTHORIZED);
        }

        // Update organization
        oldOrganization.setDescription(description);

        Event updateOrganizationEvent = new Event.Builder().withOrganization(oldOrganization).withInitiatorUser(user)
            .withType(Event.EventType.MODIFY_ORG).build();
        eventDAO.create(updateOrganizationEvent);

        return organizationDAO.findById(organizationId);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{organizationId}/members")
    @ApiOperation(value = "Retrieve all members for an organization.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = OrganizationUser.class, responseContainer = "Set")
    @Operation(operationId = "getOrganizationMembers", summary = "Retrieve all members for an organization.", description = "Retrieve all members for an organization. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public Set<OrganizationUser> getOrganizationMembers(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id) {
        return getOrganizationByIdOptionalAuth(user, id).getUsers();
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{organizationId}/events")
    @ApiOperation(value = "Retrieve all events for an organization.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Event.class, responseContainer = "List")
    @Operation(operationId = "getOrganizationEvents", summary = "Retrieve all events for an organization.", description = "Retrieve all events for an organization. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public List<Event> getOrganizationEvents(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id,
            @ApiParam(value = "Start index of paging.  If this exceeds the current result set return an empty set.  If not specified in the request, this will start at the beginning of the results.", defaultValue = DEFAULT_OFFSET) @Parameter(description = "Start index of paging.  If this exceeds the current result set return an empty set.  If not specified in the request, this will start at the beginning of the results.", name = "offset", in = ParameterIn.QUERY, required = true) @DefaultValue(DEFAULT_OFFSET) @QueryParam("offset") Integer offset,
            @ApiParam(value = "Amount of records to return in a given page, limited to "
                    + PAGINATION_LIMIT, allowableValues = "range[1,100]", defaultValue = PAGINATION_LIMIT) @Parameter(description = "Amount of records to return in a given page, limited to " + PAGINATION_LIMIT, name = "limit", in = ParameterIn.QUERY, schema = @Schema(minimum = "1", maximum = "100"), required = true) @DefaultValue(PAGINATION_LIMIT) @QueryParam("limit") Integer limit,
            @Context HttpServletResponse response) {
        getOrganizationByIdOptionalAuth(user, id);
        response.addHeader("X-total-count", String.valueOf(eventDAO.countAllEventsForOrganization(id)));
        response.addHeader("Access-Control-Expose-Headers", "X-total-count");
        List<Event> eventsForOrganization = eventDAO.findEventsForOrganization(id, offset, limit);
        for (Event event : eventsForOrganization) {
            Hibernate.initialize(event.getInitiatorUser());
            Hibernate.initialize(event.getCollection());
        }
        return eventsForOrganization;
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{organizationId}/star")
    @ApiOperation(value = "Star an organization.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    @Operation(operationId = "starOrganization", summary = "Star an organization.", description = "Star an organization.", security = @SecurityRequirement(name = "bearer"))
    public void starOrganization(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
                          @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
                          @ApiParam(value = "StarRequest to star an organization for a user.", required = true) @Parameter(description = "StarRequest to star an organization for a user.", name = "request", required = true) StarRequest request) {
        Organization organization = organizationDAO.findApprovedById(organizationId);
        checkOrganization(organization);
        Set<User> starredUsers = organization.getStarredUsers();
        if (request.getStar()) {
            starOrganizationHelper(organization, starredUsers, user);
        } else {
            unstarOrganizationHelper(organization, starredUsers, user);
        }

    }

    private void starOrganizationHelper(Organization organization, Set<User> starredUsers, User user) {
        if (!starredUsers.contains(user)) {
            organization.addStarredUser(user);
        } else {
            throw new CustomWebApplicationException(
                "You cannot star the organization " + organization.getName() + " because you have already starred it.", HttpStatus.SC_BAD_REQUEST);
        }
    }

    private void unstarOrganizationHelper(Organization organization, Set<User> starredUsers, User user) {
        if (starredUsers.contains(user)) {
            organization.removeStarredUser(user);
        } else {
            throw new CustomWebApplicationException(
                "You cannot unstar the organization " + organization.getName() + " because you have not starred it.", HttpStatus.SC_BAD_REQUEST);
        }
    }

    @GET
    @Path("/{organizationId}/starredUsers")
    @Timed
    @UnitOfWork
    @ApiOperation(value = "Return list of users who starred the given approved organization.", response = User.class, responseContainer = "List")
    @Operation(operationId = "getStarredUsersForApprovedOrganization", summary = "Return list of users who starred the given approved organization.", description = "Return list of users who starred the given approved organization.")
    public Set<User> getStarredUsersForApprovedOrganization(
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId) {
        Organization organization = organizationDAO.findApprovedById(organizationId);
        checkOrganization(organization);
        return organization.getStarredUsers();
    }

    /**
     * Retrieve an organization using optional authentication
     *
     * @param user  Optional user to authenticate with
     * @param orgId Organization id
     * @return Organization with given id
     */
    private Organization getOrganizationByIdOptionalAuth(Optional<User> user, Long orgId) {
        if (user.isEmpty()) {
            // No user given, only show approved organizations
            Organization organization = organizationDAO.findApprovedById(orgId);
            throwExceptionForNullOrganization(organization);
            return organization;
        } else {
            // User is given, check if organization is either approved or the user has access
            // Admins and curators should be able to see unapproved organizations
            boolean doesOrgExist =
                doesOrganizationExistToUser(orgId, user.get().getId()) || user.get().getIsAdmin() || user.get().isCurator();
            if (!doesOrgExist) {
                String msg = "Organization not found";
                LOG.info(msg);
                throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
            }

            return organizationDAO.findById(orgId);
        }
    }

    @DELETE
    @Path("/{organizationId}")
    @Timed
    @UnitOfWork
    @ApiOperation(value = "hidden", hidden = true)
    @Operation(operationId = "deleteRejectedOrPendingOrganization", summary = "Delete pending or rejected organization", description = "Delete pending or rejected organization", security = @SecurityRequirement(name = "bearer"))
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "NO CONTENT"),
            @ApiResponse(responseCode = "400", description = "BAD REQUEST"),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN")
    })
    public void deleteRejectedOrPendingOrganization(
            @Parameter(hidden = true, name = "user") @Auth User user,
            @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId) {
        Organization organization = organizationDAO.findById(organizationId);
        OrganizationUser orgUser = getUserOrgRole(organization, user.getId());

        // If the user does not belong to the organization or if the user is not a maintainer of the organization
        // and if the user is neither an admin nor curator, then throw an error
        if ((orgUser == null || orgUser.getRole() != OrganizationUser.Role.MAINTAINER) && (!user.isCurator() && !user.getIsAdmin())) {
            throw new CustomWebApplicationException("You do not have access to delete this organization", HttpStatus.SC_FORBIDDEN);
        }

        // If the organization to be deleted is pending or has been rejected, then delete the organization
        if (organization.getStatus() == Organization.ApplicationState.PENDING || organization.getStatus() == Organization.ApplicationState.REJECTED) {
            eventDAO.deleteEventByOrganizationID(organizationId);
            organizationDAO.delete(organization);
        } else { // else if the organization is not pending nor rejected, then throw an error
            throw new CustomWebApplicationException("You can only delete organizations that are pending or have been rejected", HttpStatus.SC_BAD_REQUEST);
        }
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/all")
    @RolesAllowed({ "curator", "admin" })
    @ApiOperation(value = "List all organizations.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Admin/curator only", responseContainer = "List", response = Organization.class)
    @Operation(operationId = "getAllOrganizations", summary = "List all organizations.", description = "List all organizations, regardless of organization status. Admin/curator only.", security = @SecurityRequirement(name = "bearer"))
    public List<Organization> getAllOrganizations(
        @ApiParam(value = "Filter to apply to organizations.", required = true, allowableValues = "all, pending, rejected, approved") @Parameter(description = "Filter to apply to organizations.", name = "type", in = ParameterIn.QUERY, schema = @Schema(allowableValues = {"all", "pending", "rejected", "approved"}), required = true) @QueryParam("type") String type) {
        List<Organization> organizations;

        switch (type) {
        case "pending":
            organizations = organizationDAO.findAllPending();
            break;
        case "rejected":
            organizations = organizationDAO.findAllRejected();
            break;
        case "approved":
            organizations = organizationDAO.findAllApproved();
            break;
        case "all":
        default:
            organizations = organizationDAO.findAll();
            break;
        }

        organizations.forEach(organization -> Hibernate.initialize(organization.getUsers()));
        return organizations;
    }

    @POST
    @Timed
    @UnitOfWork
    @Consumes("application/json")
    @ApiOperation(value = "Create an organization.", notes = "Organization requires approval by an admin before being made public.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Organization.class)
    @Operation(operationId = "createOrganization", summary = "Create an organization.", description = "Create an organization. Organization requires approval by an admin before being made public.", security = @SecurityRequirement(name = "bearer"))
    public Organization createOrganization(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Organization to register.", required = true) @Parameter(description = "Organization to register.", name = "organization", required = true) Organization organization) {

        // Check if any other organizations exist with that name
        Organization matchingOrg = organizationDAO.findByName(organization.getName());
        if (matchingOrg != null) {
            String msg = "An organization already exists with the name '" + organization.getName() + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        // Validate email and link
        validateEmail(organization.getEmail());
        validateLink(organization.getLink());

        // Save organization
        organization.setStatus(Organization.ApplicationState.PENDING); // should not be approved by default
        long id = organizationDAO.create(organization);

        User foundUser = userDAO.findById(user.getId());

        // Create Role for user creating the organization
        OrganizationUser organizationUser = new OrganizationUser(foundUser, organizationDAO.findById(id), OrganizationUser.Role.ADMIN);
        organizationUser.setAccepted(true);
        Session currentSession = sessionFactory.getCurrentSession();
        currentSession.persist(organizationUser);

        Event createOrganizationEvent = new Event.Builder().withOrganization(organization).withInitiatorUser(foundUser)
            .withType(Event.EventType.CREATE_ORG).build();
        eventDAO.create(createOrganizationEvent);

        return organizationDAO.findById(id);
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("{organizationId}")
    @ApiOperation(value = "Update an organization.", notes = "Currently only name, display name, description, topic, email, link, avatarUrl, and location can be updated.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Organization.class)
    @Operation(operationId = "updateOrganization", summary = "Update an organization.", description = "Update an organization. Currently only name, display name, description, topic, email, link, avatarUrl, and location can be updated.", security = @SecurityRequirement(name = "bearer"))
    public Organization updateOrganization(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Organization to update with.", required = true) @Parameter(description = "Organization to register.", name = "organization", required = true) Organization organization,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id) {

        boolean doesOrgExist = doesOrganizationExistToUser(id, user.getId());
        if (!doesOrgExist) {
            String msg = "Organization not found";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        Organization oldOrganization = organizationDAO.findById(id);

        // Ensure that the user is an admin or maintainer of the organization
        if (!user.isCurator() && !user.getIsAdmin()) {
            OrganizationUser organizationUser = getUserOrgRole(oldOrganization, user.getId());
            if (organizationUser == null || organizationUser.getRole() == OrganizationUser.Role.MEMBER) {
                String msg = "You do not have permissions to update the organization.";
                LOG.info(msg);
                throw new CustomWebApplicationException(msg, HttpStatus.SC_UNAUTHORIZED);
            }
        }

        // Check if new name is valid
        if (!Objects.equals(oldOrganization.getName(), organization.getName())) {
            Organization duplicateName = organizationDAO.findByName(organization.getName());
            // if the duplicate is the old org itself, ignore it
            if (duplicateName != null) {
                if (duplicateName.getId() == oldOrganization.getId()) {
                    // do nothing
                    LOG.debug("this appears to be a case change");
                } else {
                    String msg = "An organization already exists with the name '" + organization.getName() + "', please try another one.";
                    LOG.info(msg);
                    throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
                }
            }
        }

        // Validate email and link
        validateEmail(organization.getEmail());
        validateLink(organization.getLink());

        if (!oldOrganization.getName().equals(organization.getName()) || !oldOrganization.getDisplayName().equals(organization.getDisplayName())) {
            if (user.getIsAdmin() || user.isCurator() || oldOrganization.getStatus() != Organization.ApplicationState.APPROVED) {
                // Only update the name and display name if the user is an admin/curator or if the org is not yet approved
                // This is for https://ucsc-cgl.atlassian.net/browse/SEAB-203 to prevent name squatting after organization was approved
                oldOrganization.setName(organization.getName());
                oldOrganization.setDisplayName(organization.getDisplayName());
            } else {
                throw new CustomWebApplicationException("Only admin and curators are able to change an approved Organization's name or display name. Contact Dockstore to have it changed.", HttpStatus.SC_UNAUTHORIZED);
            }
        }

        // Update rest of organization
        oldOrganization.setDescription(organization.getDescription());
        oldOrganization.setTopic(organization.getTopic());
        oldOrganization.setEmail(organization.getEmail());
        oldOrganization.setLink(organization.getLink());
        oldOrganization.setLocation(organization.getLocation());
        oldOrganization.setAvatarUrl(organization.getAvatarUrl());

        Event updateOrganizationEvent = new Event.Builder().withOrganization(oldOrganization).withInitiatorUser(user)
            .withType(Event.EventType.MODIFY_ORG).build();
        eventDAO.create(updateOrganizationEvent);

        return organizationDAO.findById(id);
    }

    /**
     * Validate email string. null/empty is valid since it's optional.
     * @param email The email to validate
     */
    private void validateEmail(String email) {
        if (StringUtils.isEmpty(email)) {
            return;
        }
        EmailValidator emailValidator = EmailValidator.getInstance();
        if (!emailValidator.isValid(email)) {
            String msg = "Email is invalid: " + email;
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }
    }

    /**
     * Validate url string. null/empty is valid since it's optional.
     * @param url The link to validate
     */
    private void validateLink(String url) {
        if (StringUtils.isEmpty(url)) {
            return;
        }
        String[] schemes = { "http", "https" };
        UrlValidator urlValidator = new UrlValidator(schemes);
        if (!urlValidator.isValid(url)) {
            String msg = "Link is invalid: " + url;
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{organizationId}/users/{username}")
    @ApiOperation(value = "Add a user role to an organization.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = OrganizationUser.class)
    @Operation(operationId = "addUserToOrgByUsername", summary = "Add a user role to an organization.", description = "Add a user role to an organization.", security = @SecurityRequirement(name = "bearer"))
    public OrganizationUser addUserToOrgByUsername(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Role of user.", allowableValues = "MAINTAINER, MEMBER", required = true) @Parameter(description = "Role of user.", name = "role", schema = @Schema(allowableValues = {"MAINTAINER", "MEMBER"}), required = true) String role,
        @ApiParam(value = "User to add to org.", required = true) @Parameter(description = "User to add to org.", name = "username", in = ParameterIn.PATH, required = true) @PathParam("username") String username,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId) {
        User userToAdd = userDAO.findByUsername(username);
        if (userToAdd == null) {
            String msg = "No user exists with the username '" + username + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }
        return addUserToOrg(user, role, userToAdd.getId(), organizationId, "");
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("/{organizationId}/user")
    @ApiOperation(value = "Add a user role to an organization.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = OrganizationUser.class)
    @Operation(operationId = "addUserToOrg", summary = "Add a user role to an organization.", description = "Add a user role to an organization.", security = @SecurityRequirement(name = "bearer"))
    public OrganizationUser addUserToOrg(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Role of user.", required = true, allowableValues = "ADMIN, MAINTAINER, MEMBER") @Parameter(description = "Role of user.", name = "role", in = ParameterIn.QUERY, schema = @Schema(allowableValues = {"ADMIN", "MAINTAINER", "MEMBER"}), required = true) @QueryParam("role") String role,
        @ApiParam(value = "User ID of user to add to organization.", required = true) @Parameter(description = "User ID of user to add to organization.", name = "userId", in = ParameterIn.QUERY, required = true) @QueryParam("userId") Long userId,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
        @ApiParam(value = "This is here to appease Swagger. It requires PUT methods to have a body, even if it is empty. Please leave it empty.") @Parameter(description = "This is here to appease Swagger. It requires PUT methods to have a body, even if it is empty. Please leave it empty.") String emptyBody) {

        // Basic checks to ensure that action can be taken
        Pair<Organization, User> organizationAndUserToAdd = commonUserOrg(organizationId, userId, user);

        // Check for existing roles the user has
        OrganizationUser existingRole = getUserOrgRole(organizationAndUserToAdd.getLeft(), userId);
        OrganizationUser organizationUser = null;
        if (existingRole == null) {
            organizationUser = new OrganizationUser(organizationAndUserToAdd.getRight(), organizationAndUserToAdd.getLeft(),
                OrganizationUser.Role.valueOf(role));
            Session currentSession = sessionFactory.getCurrentSession();
            currentSession.persist(organizationUser);
        } else {
            updateUserRole(user, role, userId, organizationId);
        }

        Event addUserOrganizationEvent = new Event.Builder().withUser(organizationAndUserToAdd.getRight())
            .withOrganization(organizationAndUserToAdd.getLeft()).withInitiatorUser(user).withType(Event.EventType.ADD_USER_TO_ORG).build();
        eventDAO.create(addUserOrganizationEvent);

        return organizationUser;
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/{organizationId}/user")
    @ApiOperation(value = "Update a user role in an organization.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = OrganizationUser.class)
    @Operation(operationId = "updateUserRole", summary = "Update a user role in an organization.", description = "Update a user role in an organization.", security = @SecurityRequirement(name = "bearer"))
    public OrganizationUser updateUserRole(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Role of user.", required = true, allowableValues = "ADMIN, MAINTAINER, MEMBER") @Parameter(description = "Role of user.", name = "role", in = ParameterIn.QUERY, required = true, schema = @Schema(allowableValues = {"ADMIN", "MAINTAINER", "MEMBER"})) @QueryParam("role") String role,
        @ApiParam(value = "User ID of user to update within organization.", required = true) @Parameter(description = "User ID of user to add to organization.", name = "userId", in = ParameterIn.QUERY, required = true) @QueryParam("userId") Long userId,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId) {

        // Basic checks to ensure that action can be taken
        Pair<Organization, User> organizationAndUserToUpdate = commonUserOrg(organizationId, userId, user);

        // Check for existing roles the user has
        OrganizationUser existingRole = getUserOrgRole(organizationAndUserToUpdate.getLeft(), userId);
        if (existingRole == null) {
            String msg = "The user with id '" + userId + "' does not have a role in the organization with id '" + organizationId + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_UNAUTHORIZED);
        } else {
            existingRole.setRole(OrganizationUser.Role.valueOf(role));
        }

        Event updateUserOrganizationEvent = new Event.Builder().withUser(organizationAndUserToUpdate.getRight())
            .withOrganization(organizationAndUserToUpdate.getLeft()).withInitiatorUser(user).withType(Event.EventType.MODIFY_USER_ROLE_ORG)
            .build();

        eventDAO.create(updateUserOrganizationEvent);

        return existingRole;
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/{organizationId}/user")
    @ApiOperation(value = "Remove a user from an organization.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = void.class)
    @Operation(operationId = "deleteUserRole", summary = "Remove a user from an organization.", description = "Remove a user from an organization.", security = @SecurityRequirement(name = "bearer"))
    public void deleteUserRole(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "User ID of user to remove from organization.", required = true) @Parameter(description = "User ID of user to add to organization.", name = "userId", in = ParameterIn.QUERY, required = true) @QueryParam("userId") Long userId,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId) {

        // Basic checks to ensure that action can be taken
        Pair<Organization, User> organizationAndUserToDelete = commonUserOrg(organizationId, userId, user);

        // Check for existing roles the user has
        OrganizationUser existingRole = getUserOrgRole(organizationAndUserToDelete.getLeft(), userId);
        if (existingRole == null) {
            String msg = "The user with id '" + userId + "' does not have a role in the organization with id '" + organizationId + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_UNAUTHORIZED);
        } else {
            Session currentSession = sessionFactory.getCurrentSession();
            currentSession.delete(existingRole);
        }

        Event deleteUserOrganizationEvent = new Event.Builder().withUser(organizationAndUserToDelete.getRight())
            .withOrganization(organizationAndUserToDelete.getLeft()).withInitiatorUser(user).withType(Event.EventType.REMOVE_USER_FROM_ORG)
            .build();
        eventDAO.create(deleteUserOrganizationEvent);
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("/{organizationId}/invitation")
    @ApiOperation(value = "Accept or reject an organization invitation.", notes = "True accepts the invitation, false rejects the invitation.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    @Operation(operationId = "acceptOrRejectInvitation", summary = "Accept or reject an organization invitation.", description = "Accept or reject an organization invitation. True accepts the invitation, false rejects the invitation.", security = @SecurityRequirement(name = "bearer"))
    public void acceptOrRejectInvitation(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
        @ApiParam(value = "Accept or reject.", required = true) @Parameter(description = "Accept or reject.", name = "accept", in = ParameterIn.QUERY, required = true) @QueryParam("accept") boolean accept) {

        // Check that the organization exists
        boolean doesOrgExist = doesOrganizationExistToUser(organizationId, user.getId());
        if (!doesOrgExist) {
            String msg = "Organization not found";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        Organization organization = organizationDAO.findById(organizationId);

        // Check that the role exists
        OrganizationUser organizationUser = getUserOrgRole(organization, user.getId());
        if (organizationUser == null) {
            String msg =
                "The user with id '" + user.getId() + "' does not have a role in the organization with id '" + organization.getId() + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_UNAUTHORIZED);
        }

        // Check that the role is not already accepted
        if (organizationUser.isAccepted()) {
            String msg =
                "The user with id '" + user.getId() + "' has already accepted a role in the organization with id '" + organization.getId()
                    + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        if (accept) {
            // Set to accepted if true
            organizationUser.setAccepted(true);
        } else {
            // Delete role if false
            Session currentSession = sessionFactory.getCurrentSession();
            currentSession.delete(organizationUser);
        }

        Event.EventType eventType = accept ? Event.EventType.APPROVE_ORG_INVITE : Event.EventType.REJECT_ORG_INVITE;
        Event addUserOrganizationEvent = new Event.Builder().withOrganization(organization).withInitiatorUser(user).withType(eventType)
            .build();
        eventDAO.create(addUserOrganizationEvent);
    }

    /**
     * Determine the role of a user in an organization
     *
     * @param organization
     * @param userId
     * @return OrganizationUser role
     */
    protected static OrganizationUser getUserOrgRole(Organization organization, Long userId) {
        Set<OrganizationUser> organizationUserSet = organization.getUsers();
        Optional<OrganizationUser> matchingUser = organizationUserSet.stream()
            .filter(organizationUser -> Objects.equals(organizationUser.getUser().getId(), userId)).findFirst();
        return matchingUser.orElse(null);
    }

    /**
     * Checks if a user has the given role type in the organization
     * Throws an error if the user has no roles or the wrong roles
     *
     * @param organization
     * @param userId
     * @return Role for the organizationUser
     */
    private OrganizationUser checkUserOrgRole(Organization organization, Long userId, OrganizationUser.Role role) {
        OrganizationUser organizationUser = getUserOrgRole(organization, userId);
        if (organizationUser == null) {
            String msg =
                "The user with id '" + userId + "' does not have a role in the organization with id '" + organization.getId() + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_UNAUTHORIZED);
        } else if (!Objects.equals(organizationUser.getRole(), role)) {
            String msg =
                "The user with id '" + userId + "' does not have the required role in the organization with id '" + organization.getId()
                    + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_UNAUTHORIZED);
        } else {
            return organizationUser;
        }
    }

    private boolean doesOrganizationExistToUser(Long organizationId, Long userId) {
        return doesOrganizationExistToUser(organizationId, userId, organizationDAO);
    }

    /**
     * Checks if the given user should know of the existence of the organization
     * For a user to see an organsation, either it must be approved or the user must have a role in the organization
     *
     * @param organizationId
     * @param userId
     * @return True if organization exists to user, false otherwise
     */
    static boolean doesOrganizationExistToUser(Long organizationId, Long userId, OrganizationDAO organizationDAO) {
        Organization organization = organizationDAO.findById(organizationId);
        if (organization == null) {
            return false;
        }
        OrganizationUser organizationUser = getUserOrgRole(organization, userId);
        return Objects.equals(organization.getStatus(), Organization.ApplicationState.APPROVED) || (organizationUser != null);
    }


    static boolean isUserAdminOrMaintainer(Organization organization, Long userId) {
        if (organization == null) {
            String msg = "Organization not found";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        OrganizationUser organizationUser = getUserOrgRole(organization, userId);
        if (organizationUser == null) {
            return false;
        }

        if (organizationUser.getRole() == OrganizationUser.Role.ADMIN || organizationUser.getRole() == OrganizationUser.Role.MAINTAINER) {
            return true;
        }
        return false;
    }

    static boolean isUserAdminOrMaintainer(Long organizationId, Long userId, OrganizationDAO organizationDAO) {
        Organization organization = organizationDAO.findById(organizationId);
        return isUserAdminOrMaintainer(organization, userId);
    }


    /**
     * Common checks done by the user add/edit/delete endpoints
     *
     * @param organizationId Organization ID of organization to perform action on
     * @param userId User ID of user to perform action on
     * @param user User performing the action
     * @return A pair of organistion to edit and user add/edit/delete role
     */
    private Pair<Organization, User> commonUserOrg(Long organizationId, Long userId, User user) {
        // Check that the organization exists
        boolean doesOrgExist = doesOrganizationExistToUser(organizationId, user.getId());
        if (!doesOrgExist) {
            String msg = "Organization not found";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        Organization organization = organizationDAO.findById(organizationId);

        // Check that the user exists
        User userToAdd = userDAO.findById(userId);
        if (userToAdd == null) {
            String msg = "No user exists with the ID '" + userId + "'.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        // Check that you are not applying action on yourself
        if (Objects.equals(user.getId(), userId)) {
            String msg = "You cannot modify yourself in an organization.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        // Ensure that the calling user is an admin of the organization
        checkUserOrgRole(organization, user.getId(), OrganizationUser.Role.ADMIN);

        return new ImmutablePair<>(organization, userToAdd);
    }

    @POST
    @Timed
    @UnitOfWork
    @Override
    @Path("{organizationId}/aliases")
    @ApiOperation(nickname = "addOrganizationAliases", value = "Add aliases linked to a listing in Dockstore.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Aliases are alphanumerical (case-insensitive and may contain internal hyphens), given in a comma-delimited list.", response = Organization.class)
    @Operation(operationId = "addOrganizationAliases", summary = "Add aliases linked to a listing in Dockstore.", description = "Add aliases linked to a listing in Dockstore. Aliases are alphanumerical (case-insensitive and may contain internal hyphens), given in a comma-delimited list.", security = @SecurityRequirement(name = "bearer"))
    @ApiResponse(responseCode = HttpStatus.SC_OK + "", description = "Successfully created organization alias", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Organization.class)))
    public Organization addAliases(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Organization to modify.", required = true) @Parameter(description = "Organization to modify.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long id,
        @ApiParam(value = "Comma-delimited list of aliases.", required = true) @Parameter(description = "Comma-delimited list of aliases.", name = "aliases", in = ParameterIn.QUERY, required = true) @QueryParam("aliases") String aliases) {
        return AliasableResourceInterface.super.addAliases(user, id, aliases);
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("{alias}/aliases")
    @ApiOperation(nickname = "getOrganizationByAlias", value = "Retrieve an organization by alias.", response = Organization.class)
    @Operation(operationId = "getOrganizationByAlias", summary = "Retrieve an organization by alias.", description = "Retrieve an organization by alias.")
    public Organization getOrganizationByAlias(@ApiParam(value = "Alias.", required = true) @Parameter(description = "Alias.", name = "alias", required = true) @PathParam("alias") String alias) {
        return this.getAndCheckResourceByAlias(alias);
    }

    @Override
    public Optional<PublicStateManager> getPublicStateManager() {
        return Optional.empty();
    }

    @Override
    public Organization getAndCheckResource(User user, Long id) {
        return getOrganizationByIdOptionalAuth(Optional.of(user), id);
    }

    @Override
    public Organization getAndCheckResourceByAlias(String alias) {
        final Organization orgByAlias = this.organizationDAO.getByAlias(alias);
        // If approved then return
        throwExceptionForNullOrganization(orgByAlias);
        if (Objects.equals(orgByAlias.getStatus(), Organization.ApplicationState.APPROVED)) {
            Hibernate.initialize(orgByAlias.getAliases());
            return orgByAlias;
        }
        throw new CustomWebApplicationException("Organization not found", HttpStatus.SC_NOT_FOUND);
    }
}
