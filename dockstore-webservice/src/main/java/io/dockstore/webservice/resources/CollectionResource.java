package io.dockstore.webservice.resources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.Timed;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.Collection;
import io.dockstore.webservice.core.CollectionEntry;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Event;
import io.dockstore.webservice.core.Label;
import io.dockstore.webservice.core.Organization;
import io.dockstore.webservice.core.OrganizationUser;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.jdbi.CollectionDAO;
import io.dockstore.webservice.jdbi.EventDAO;
import io.dockstore.webservice.jdbi.OrganizationDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dockstore.webservice.jdbi.VersionDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.http.HttpStatus;
import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.webservice.Constants.JWT_SECURITY_DEFINITION_NAME;
import static io.dockstore.webservice.resources.ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME;

/**
 * Collection of collection endpoints
 * TODO: Add an endpoint that retrieves collection entries
 * @author aduncan
 */
@Path("/organizations")
@Api("/organizations")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "organizations", description = ResourceConstants.ORGANIZATIONS)
@SecuritySchemes({ @SecurityScheme(type = SecuritySchemeType.HTTP, name = "bearer", scheme = "bearer") })
public class CollectionResource implements AuthenticatedResourceInterface, AliasableResourceInterface<Collection> {

    private static final Logger LOG = LoggerFactory.getLogger(OrganizationResource.class);

    private static final String OPTIONAL_AUTH_MESSAGE = "Does not require authentication for approved organizations, authentication can be provided for unapproved organizations";

    private final CollectionDAO collectionDAO;
    private final OrganizationDAO organizationDAO;
    private final WorkflowDAO workflowDAO;
    private final ToolDAO toolDAO;
    private final UserDAO userDAO;
    private final EventDAO eventDAO;
    private final SessionFactory sessionFactory;
    private final VersionDAO versionDAO;

    public CollectionResource(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
        this.collectionDAO = new CollectionDAO(sessionFactory);
        this.organizationDAO = new OrganizationDAO(sessionFactory);
        this.workflowDAO = new WorkflowDAO(sessionFactory);
        this.toolDAO = new ToolDAO(sessionFactory);
        this.userDAO = new UserDAO(sessionFactory);
        this.eventDAO = new EventDAO(sessionFactory);
        this.versionDAO = new VersionDAO(sessionFactory);
    }

    /**
     * TODO: Path looks a bit weird
     */
    @POST
    @Timed
    @UnitOfWork
    @Override
    @Path("/collections/{collectionId}/aliases")
    @ApiOperation(nickname = "addCollectionAliases", value = "Add aliases linked to a collection in Dockstore.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Aliases are alphanumerical (case-insensitive and may contain internal hyphens), given in a comma-delimited list.", response = Collection.class)
    @Operation(operationId = "addCollectionAliases", summary = "Add aliases linked to a collection in Dockstore.", description = "Aliases are alphanumerical (case-insensitive and may contain internal hyphens), given in a comma-delimited list.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiResponse(responseCode = HttpStatus.SC_OK + "", description = "Successfully added alias to collection", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Collection.class)))
    public Collection addAliases(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
        @ApiParam(value = "Collection to modify.", required = true) @Parameter(description = "Collection to modify.", name = "collectionId", in = ParameterIn.PATH, required = true) @PathParam("collectionId") Long id,
        @ApiParam(value = "Comma-delimited list of aliases.", required = true) @Parameter(description = "Comma-delimited list of aliases.", name = "aliases", in = ParameterIn.QUERY, required = true) @QueryParam("aliases") String aliases) {
        return AliasableResourceInterface.super.addAliases(user, id, aliases);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/collections/{alias}/aliases")
    @ApiOperation(nickname = "getCollectionByAlias", value = "Retrieve a collection by alias.", response = Collection.class)
    @Operation(operationId = "getCollectionByAlias", summary = "Retrieve a collection by alias.", description = "Retrieve a collection by alias.")
    public Collection getCollectionByAlias(@ApiParam(value = "Alias of the collection", required = true) @Parameter(description = "Alias of the collection.", name = "alias", required = true) @PathParam("alias") String alias) {
        return this.getAndCheckResourceByAlias(alias);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("{organizationId}/collections/{collectionId}")
    @ApiOperation(value = "Retrieve a collection by ID.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Collection.class)
    @Operation(operationId = "getCollectionById", summary = "Retrieve a collection by ID.", description = "Retrieve a collection by ID. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public Collection getCollectionById(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
            @ApiParam(value = "Collection ID.", required = true) @Parameter(description = "Collection ID.", name = "collectionId", in = ParameterIn.PATH, required = true) @PathParam("collectionId") Long collectionId) {

        if (user.isEmpty()) {
            // No user given, only show collections from approved organizations
            Collection collection = collectionDAO.findById(collectionId);
            // check that organization id matches
            if (collection.getOrganizationID() != organizationId) {
                collection = null;
            }
            throwExceptionForNullCollection(collection);
            assert collection != null;
            Hibernate.initialize(collection.getAliases());
            Collection approvalForCollection = getApprovalForCollection(collection);
            addCollectionEntriesToCollection(approvalForCollection);
            return approvalForCollection;
        } else {
            // User is given, check if the collections organization is either approved or the user has access
            // Admins and curators should be able to see collections from unapproved organizations
            Collection collection = getAndCheckCollection(Optional.of(organizationId), collectionId, user.get());
            // check that organization id matches
            if (collection.getOrganizationID() != organizationId) {
                collection = null;
                throwExceptionForNullCollection(collection);
            }
            assert collection != null;
            Hibernate.initialize(collection.getAliases());
            addCollectionEntriesToCollection(collection);
            return collection;
        }
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("{organizationName}/collections/{collectionName}/name")
    @ApiOperation(value = "Retrieve a collection by name.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Collection.class)
    @Operation(operationId = "getCollectionByName", summary = "Retrieve a collection by name.", description = "Retrieve a collection by name. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public Collection getCollectionByName(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
            @ApiParam(value = "Organization name.", required = true) @Parameter(description = "Organization name.", name = "organizationName", in = ParameterIn.PATH, required = true) @PathParam("organizationName") String organizationName,
            @ApiParam(value = "Collection name.", required = true) @Parameter(description = "Collection name.", name = "collectionName", in = ParameterIn.PATH, required = true) @PathParam("collectionName") String collectionName) {
        if (user.isEmpty()) {
            // No user given, only show collections from approved organizations
            Organization organization = organizationDAO.findApprovedByName(organizationName);
            if (organization == null) {
                String msg = "Organization " + organizationName + " not found.";
                LOG.info(msg);
                throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
            }

            Collection collection = collectionDAO.findByNameAndOrg(collectionName, organization.getId());
            throwExceptionForNullCollection(collection);
            Collection approvalForCollection = getApprovalForCollection(collection);
            addCollectionEntriesToCollection(approvalForCollection);
            return approvalForCollection;
        } else {
            // User is given, check if the collections organization is either approved or the user has access
            // Admins and curators should be able to see collections from unapproved organizations
            Organization organization = organizationDAO.findByName(organizationName);
            if (organization == null || !OrganizationResource.doesOrganizationExistToUser(organization.getId(), user.get().getId(), organizationDAO)) {
                String msg = "Organization " + organizationName + " not found.";
                LOG.info(msg);
                throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
            }

            Collection collection = collectionDAO.findByNameAndOrg(collectionName, organization.getId());
            Hibernate.initialize(collection.getAliases());
            addCollectionEntriesToCollection(collection);
            return collection;
        }
    }

    private void addCollectionEntriesToCollection(Collection collection) {
        Session currentSession = sessionFactory.getCurrentSession();
        currentSession.evict(collection);
        List<CollectionEntry> collectionWorkflows = workflowDAO.getCollectionWorkflows(collection.getId());
        List<CollectionEntry> collectionServices = workflowDAO.getCollectionServices(collection.getId());
        List<CollectionEntry> collectionTools = workflowDAO.getCollectionTools(collection.getId());
        List<CollectionEntry> collectionWorkflowsWithVersions = workflowDAO.getCollectionWorkflowsWithVersions(collection.getId());
        List<CollectionEntry> collectionServicesWithVersions = workflowDAO.getCollectionServicesWithVersions(collection.getId());
        List<CollectionEntry> collectionToolsWithVersions = workflowDAO.getCollectionToolsWithVersions(collection.getId());
        List<CollectionEntry> collectionEntries = new ArrayList<>();
        collectionEntries.addAll(collectionWorkflows);
        collectionEntries.addAll(collectionServices);
        collectionEntries.addAll(collectionTools);        
        collectionEntries.addAll(collectionWorkflowsWithVersions);
        collectionEntries.addAll(collectionServicesWithVersions);
        collectionEntries.addAll(collectionToolsWithVersions);
        collectionEntries.forEach(entry -> {
            List<Label> labels = workflowDAO.getLabelByEntryId(entry.getId());
            List<String> labelStrings = labels.stream().map(Label::getValue).collect(Collectors.toList());
            entry.setLabels(labelStrings);
            if (entry.getEntryType().equals("tool")) {
                entry.setDescriptorTypes(toolDAO.getToolsDescriptorTypes(entry.getId()));
            } else if (entry.getEntryType().equals("workflow")) {
                entry.setDescriptorTypes(workflowDAO.getWorkflowsDescriptorTypes(entry.getId()));
            }
        });
        collection.setCollectionEntries(collectionEntries);
        collection.setWorkflowsLength(collectionWorkflows.size() + collectionWorkflowsWithVersions.size());
        collection.setToolsLength(collectionTools.size() + collectionToolsWithVersions.size());
    }

    private void throwExceptionForNullCollection(Collection collection) {
        if (collection == null) {
            String msg = "Collection not found.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("{organizationId}/collections/{collectionId}/entry")
    @ApiOperation(value = "Add an entry to a collection.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Collection.class)
    @Operation(operationId = "addEntryToCollection", summary = "Add an entry to a collection.", description = "Add an entry to a collection.", security = @SecurityRequirement(name = "bearer"))
    public Collection addEntryToCollection(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
            @ApiParam(value = "Collection ID.", required = true) @Parameter(description = "Collection ID.", name = "collectionId", in = ParameterIn.PATH, required = true) @PathParam("collectionId") Long collectionId,
            @ApiParam(value = "Entry ID", required = true) @Parameter(description = "Entry ID.", name = "entryId", in = ParameterIn.QUERY, required = true) @QueryParam("entryId") Long entryId,
            @ApiParam(value = "Version ID", required = false) @Parameter(description = "Version ID.", name = "versionId", in = ParameterIn.QUERY, required = false) @QueryParam("versionId") Long versionId) {
        // Call common code to check if entry and collection exist and return them
        ImmutablePair<Entry, Collection> entryAndCollection = commonModifyCollection(organizationId, entryId, collectionId, user);
        if (versionId == null) {
            // Add the entry to the collection
            entryAndCollection.getRight().addEntry(entryAndCollection.getLeft(), null);
        } else {
            // TODO: Need to check that the version belongs to the entry
            Version version = versionDAO.findById(versionId);
            entryAndCollection.getRight().addEntry(entryAndCollection.getLeft(), version);
        }


        // Event for addition
        Organization organization = organizationDAO.findById(organizationId);

        Event.Builder eventBuild = new Event.Builder()
                .withOrganization(organization)
                .withCollection(entryAndCollection.getRight())
                .withInitiatorUser(user)
                .withType(Event.EventType.ADD_TO_COLLECTION);

        if (entryAndCollection.getLeft() instanceof BioWorkflow) {
            eventBuild = eventBuild.withBioWorkflow((BioWorkflow)entryAndCollection.getLeft());
        } else if (entryAndCollection.getLeft() instanceof Service) {
            eventBuild = eventBuild.withService((Service)entryAndCollection.getLeft());
        } else if (entryAndCollection.getLeft() instanceof Tool) {
            eventBuild = eventBuild.withTool((Tool)entryAndCollection.getLeft());
        }

        Event addToCollectionEvent = eventBuild.build();
        eventDAO.create(addToCollectionEvent);

        return collectionDAO.findById(collectionId);
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("{organizationId}/collections/{collectionId}/entry")
    @ApiOperation(value = "Delete an entry from a collection.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Collection.class)
    @Operation(operationId = "deleteEntryFromCollection", summary = "Delete an entry to a collection.", description = "Delete an entry to a collection.", security = @SecurityRequirement(name = "bearer"))
    public Collection deleteEntryFromCollection(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
            @ApiParam(value = "Collection ID.", required = true) @Parameter(description = "Collection ID.", name = "collectionId", in = ParameterIn.PATH, required = true) @PathParam("collectionId") Long collectionId,
            @ApiParam(value = "Entry ID", required = true) @Parameter(description = "Entry ID.", name = "entryId", in = ParameterIn.QUERY, required = true) @QueryParam("entryId") Long entryId,
            @ApiParam(value = "Version ID", required = false) @Parameter(description = "Version ID.", name = "versionId", in = ParameterIn.QUERY, required = false) @QueryParam("versionName") String versionName) {
        // Call common code to check if entry and collection exist and return them
        ImmutablePair<Entry, Collection> entryAndCollection = commonModifyCollection(organizationId, entryId, collectionId, user);

        Long versionId = null;
        if (versionName != null) {
            Set<Version> workflowVersions = entryAndCollection.getLeft().getWorkflowVersions();

            Optional<Version> first = workflowVersions.stream().filter(version -> version.getName().equals(versionName)).findFirst();
            if (first.isPresent()) {
                versionId = first.get().getId();
            } else {
                throw new CustomWebApplicationException("Version not found", HttpStatus.SC_NOT_FOUND);
            }
        }

        // Remove the entry from the organization,
        // This silently fails if the user somehow manages to give a non-existent entryId and versionId pair
        entryAndCollection.getRight().removeEntry(entryAndCollection.getLeft().getId(), versionId);

        // Event for deletion
        Organization organization = organizationDAO.findById(organizationId);

        Event.Builder eventBuild = new Event.Builder()
                .withOrganization(organization)
                .withCollection(entryAndCollection.getRight())
                .withInitiatorUser(user)
                .withType(Event.EventType.REMOVE_FROM_COLLECTION);

        if (entryAndCollection.getLeft() instanceof BioWorkflow) {
            eventBuild = eventBuild.withBioWorkflow((BioWorkflow)entryAndCollection.getLeft());
        } else if (entryAndCollection.getLeft() instanceof Service) {
            eventBuild = eventBuild.withService((Service)entryAndCollection.getLeft());
        } else if (entryAndCollection.getLeft() instanceof Tool) {
            eventBuild = eventBuild.withTool((Tool)entryAndCollection.getLeft());
        }

        Event removeFromCollectionEvent = eventBuild.build();
        eventDAO.create(removeFromCollectionEvent);

        return collectionDAO.findById(collectionId);
    }

    /**
     * Common code used to add and delete from a collection. Will ensure that both the entry and collection are
     * visible to the user and that the user has the correct credentials to edit the collection.
     * @param entryId the entry to add, will be considered a bad request if the entry doesn't exist
     * @param collectionId the collection to modify, will be considered a not found exception if the collection doesn't exist
     * @param user User performing the action
     * @return Pair of found Entry and Collection
     */
    private ImmutablePair<Entry, Collection> commonModifyCollection(Long organizationId, Long entryId, Long collectionId, User user) {
        // Check that entry exists (could use either workflowDAO or toolDAO here)
        // Note that only published entries can be added
        Entry<? extends Entry, ? extends Version> entry = workflowDAO.getGenericEntryById(entryId);
        if (entry == null || !entry.getIsPublished()) {
            String msg = "Entry not found.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }
        Collection collection = getAndCheckCollection(Optional.of(organizationId), collectionId, user);

        // Check that user is an admin or maintainer of the organization
        getOrganizationAndCheckModificationRights(user, collection);
        return new ImmutablePair<>(entry, collection);
    }

    /**
     * Get a collection and check whether user has rights to see and modify it
     * @param organizationId (provide as an optional check)
     * @param collectionId
     * @param user
     * @return
     */
    private Collection getAndCheckCollection(Optional<Long> organizationId, Long collectionId, User user) {
        // Check that collection exists to user
        final Collection collection = collectionDAO.findById(collectionId);
        boolean doesCollectionExist = doesCollectionExistToUser(collection, user.getId()) || user.getIsAdmin() || user.isCurator();
        if (!doesCollectionExist) {
            String msg = "Collection not found.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }
        // if organizationId is provided, check it
        if (organizationId.isPresent() && organizationId.get() != collection.getOrganizationID()) {
            String msg = "Collection not found.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        return collection;
    }


    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("{organizationId}/collections")
    @ApiOperation(value = "Retrieve all collections for an organization.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, responseContainer = "List", response = Collection.class)
    @Operation(operationId = "getCollectionsFromOrganization", summary = "Retrieve all collections for an organization.", description = "Retrieve all collections for an organization. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public List<Collection> getCollectionsFromOrganization(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
            @ApiParam(value = "Included fields") @Parameter(description = "Included fields.", name = "include", in = ParameterIn.QUERY, required = true) @QueryParam("include") String include) {
        if (user.isEmpty()) {
            Organization organization = organizationDAO.findApprovedById(organizationId);
            throwExceptionForNullOrganization(organization);
        } else {
            boolean doesOrgExist = OrganizationResource.doesOrganizationExistToUser(organizationId, user.get().getId(), organizationDAO);
            if (!doesOrgExist) {
                String msg = "Organization not found.";
                LOG.info(msg);
                throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
            }
        }

        List<Collection> collections = collectionDAO.findAllByOrg(organizationId);
        Session currentSession = sessionFactory.getCurrentSession();
        if (checkIncludes(include, "entries")) {
            collections.forEach(collection -> {
                currentSession.evict(collection);
                addCollectionEntriesToCollection(collection);
            });
        } else {
            // Ensure that entries is empty
            // This is probably unnecessary
            collections.forEach(collection -> {
                currentSession.evict(collection);
                collection.setEntries(new HashSet<>());
                collection.setWorkflowsLength(workflowDAO.getWorkflowsLength(collection.getId()));
                collection.setToolsLength(workflowDAO.getToolsLength(collection.getId()));
            });
        }
        return collections;
    }

    private void throwExceptionForNullOrganization(Organization organization) {
        if (organization == null) {
            String msg = "Organization not found";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }
    }

    @POST
    @Timed
    @UnitOfWork
    @Path("{organizationId}/collections")
    @ApiOperation(value = "Create a collection in the given organization.", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Collection.class)
    @Operation(operationId = "createCollection", summary = "Create a collection in the given organization.", description = "Create a collection in the given organization.", security = @SecurityRequirement(name = "bearer"))
    public Collection createCollection(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
            @ApiParam(value = "Collection to register.", required = true) @Parameter(description = "Collection to register.", name = "collection", required = true) Collection collection) {

        // First check if the organization exists and that the user is an admin or maintainer
        boolean isUserAdminOrMaintainer = OrganizationResource.isUserAdminOrMaintainer(organizationId, user.getId(), organizationDAO);
        if (!isUserAdminOrMaintainer) {
            String msg = "Organization not found.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        // Check if any other collections in the organization exist with that name
        Collection matchingCollection = collectionDAO.findByNameAndOrg(collection.getName(), organizationId);
        if (matchingCollection != null) {
            String msg = "A collection already exists with the name '" + collection.getName() + "' in the specified organization.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        // Get the organization
        Organization organization = organizationDAO.findById(organizationId);

        // Save the collection
        long id = collectionDAO.create(collection);
        organization.addCollection(collection);

        // Event for creation
        User foundUser = userDAO.findById(user.getId());
        Event createCollectionEvent = new Event.Builder()
                .withOrganization(organization)
                .withCollection(collection)
                .withInitiatorUser(foundUser)
                .withType(Event.EventType.CREATE_COLLECTION)
                .build();
        eventDAO.create(createCollectionEvent);

        return collectionDAO.findById(id);
    }

    @PUT
    @Timed
    @UnitOfWork
    @Path("{organizationId}/collections/{collectionId}")
    @ApiOperation(value = "Update a collection.", notes = "Currently only name, display name, description, and topic can be updated.", authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Collection.class)
    @Operation(operationId = "updateCollection", summary = "Update a collection.", description = "Update a collection. Currently only name, display name, description, and topic can be updated.", security = @SecurityRequirement(name = "bearer"))
    public Collection updateCollection(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
            @ApiParam(value = "Collection to update with.", required = true) @Parameter(description = "Collection to register.", name = "collection", required = true) Collection collection,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
            @ApiParam(value = "Collection ID.", required = true) @Parameter(description = "Collection ID.", name = "collectionId", in = ParameterIn.PATH, required = true) @PathParam("collectionId") Long collectionId) {
        // Ensure collection exists to the user
        Collection existingCollection = this.getAndCheckCollection(Optional.of(organizationId), collectionId, user);
        Organization organization = getOrganizationAndCheckModificationRights(user, existingCollection);

        // Check if new name is valid
        if (!Objects.equals(existingCollection.getName(), collection.getName())) {
            Collection duplicateName = collectionDAO.findByNameAndOrg(collection.getName(), existingCollection.getOrganization().getId());
            if (duplicateName != null) {
                if (duplicateName.getId() == existingCollection.getId()) {
                    // do nothing
                    LOG.debug("this appears to be a case change");
                } else {
                    String msg = "A collection already exists with the name '" + collection.getName() + "', please try another one.";
                    LOG.info(msg);
                    throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
                }
            }
        }

        // Update the collection
        existingCollection.setName(collection.getName());
        existingCollection.setDisplayName(collection.getDisplayName());
        existingCollection.setDescription(collection.getDescription());
        existingCollection.setTopic(collection.getTopic());

        // Event for update
        Event updateCollectionEvent = new Event.Builder()
                .withOrganization(organization)
                .withCollection(existingCollection)
                .withInitiatorUser(user)
                .withType(Event.EventType.MODIFY_COLLECTION)
                .build();
        eventDAO.create(updateCollectionEvent);

        return collectionDAO.findById(collectionId);

    }

    @PUT
    @Timed
    @Path("{organizationId}/collections/{collectionId}/description")
    @UnitOfWork
    @ApiOperation(value = "Update a collection's description.", notes = "Description in markdown", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Collection.class)
    @Operation(operationId = "updateCollectionDescription", summary = "Update a collection's description.", description = "Update a collection's description. Description in markdown.", security = @SecurityRequirement(name = "bearer"))
    public Collection updateCollectionDescription(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth User user,
            @ApiParam(value = "Organization ID.", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
            @ApiParam(value = "Collection ID.", required = true) @Parameter(description = "Collection ID.", name = "collectionId", in = ParameterIn.PATH, required = true) @PathParam("collectionId") Long collectionId,
            @ApiParam(value = "Collections's description in markdown.", required = true) @Parameter(description = "Collections's description in markdown.", name = "description", required = true) String description) {
        Collection oldCollection = this.getAndCheckCollection(Optional.of(organizationId), collectionId, user);
        Organization oldOrganization = getOrganizationAndCheckModificationRights(user, oldCollection);

        // Update collection
        oldCollection.setDescription(description);

        Event updateCollectionEvent = new Event.Builder()
                .withOrganization(oldOrganization)
                .withCollection(oldCollection)
                .withInitiatorUser(user)
                .withType(Event.EventType.MODIFY_ORG)
                .build();
        eventDAO.create(updateCollectionEvent);

        return collectionDAO.findById(collectionId);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("{organizationId}/collections/{collectionId}/description")
    @ApiOperation(value = "Retrieve a collection description by organization ID and collection ID.", notes = OPTIONAL_AUTH_MESSAGE, authorizations = { @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = String.class)
    @Operation(operationId = "getCollectionDescription", summary = "Retrieve a collection description by organization ID and collection ID.", description = "Retrieve a collection description by organization ID and collection ID. Supports optional authentication.", security = @SecurityRequirement(name = "bearer"))
    public String getCollectionDescription(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user") @Auth Optional<User> user,
            @ApiParam(value = "Organization ID", required = true) @Parameter(description = "Organization ID.", name = "organizationId", in = ParameterIn.PATH, required = true) @PathParam("organizationId") Long organizationId,
            @ApiParam(value = "Collection ID", required = true) @Parameter(description = "Collection ID.", name = "collectionId", in = ParameterIn.PATH, required = true) @PathParam("collectionId") Long collectionId) {
        return getCollectionById(user, organizationId, collectionId).getDescription();
    }

    private Organization getOrganizationAndCheckModificationRights(User user, Collection existingCollection) {
        Organization organization = organizationDAO.findById(existingCollection.getOrganization().getId());

        // Check that the user has rights to the organization
        OrganizationUser organizationUser = OrganizationResource.getUserOrgRole(organization, user.getId());
        if (organizationUser == null || organizationUser.getRole() == OrganizationUser.Role.MEMBER) {
            String msg = "User does not have rights to modify a collection from this organization.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_UNAUTHORIZED);
        }
        return organization;
    }

    /**
     * For a collection to exist to a user, it must either be from an approved organization
     * or an organization the user has access to.
     * @param collectionId
     * @param userId
     * @return True if collection exists to user, false otherwise
     */
    private boolean doesCollectionExistToUser(Long collectionId, Long userId) {
        // A collection is only visible to a user if the organization it belongs to is approved or they are a member
        Collection collection = collectionDAO.findById(collectionId);
        return doesCollectionExistToUser(collection, userId);
    }

    private boolean doesCollectionExistToUser(Collection collection, Long userId) {
        if (collection == null) {
            String msg = "Collection not found.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        return OrganizationResource.doesOrganizationExistToUser(collection.getOrganization().getId(), userId, organizationDAO);
    }

    /**
     * Checks if the include string (csv) includes some field
     * @param include CSV string where each field is of the form [a-zA-Z]+
     * @param field Field to query for
     * @return True if include has the given field, false otherwise
     */
    private boolean checkIncludes(String include, String field) {
        String includeString = (include == null ? "" : include);
        List<String> includeSplit = Arrays.asList(includeString.split(","));
        return includeSplit.contains(field);
    }

    @Override
    public Optional<PublicStateManager> getPublicStateManager() {
        return Optional.empty();
    }

    @Override
    public Collection getAndCheckResource(User user, Long id) {
        return this.getAndCheckCollection(Optional.empty(), id, user);
    }

    @Override
    public Collection getAndCheckResourceByAlias(String alias) {
        final Collection byAlias = this.collectionDAO.getByAlias(alias);
        if (byAlias == null) {
            String msg = "Collection not found.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }
        return getApprovalForCollection(byAlias);

    }

    private Collection getApprovalForCollection(Collection byAlias) {
        Organization organization = organizationDAO.findApprovedById(byAlias.getOrganization().getId());
        if (organization == null) {
            String msg = "Collection not found.";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_NOT_FOUND);
        }

        Hibernate.initialize(byAlias.getAliases());
        return byAlias;
    }
}
