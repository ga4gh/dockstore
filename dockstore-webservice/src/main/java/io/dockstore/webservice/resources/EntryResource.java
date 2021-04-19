/*
 *    Copyright 2018 OICR
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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.JAXBException;
import javax.xml.datatype.DatatypeConfigurationException;

import com.codahale.metrics.annotation.Timed;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.CollectionOrganization;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.database.VersionVerifiedPlatform;
import io.dockstore.webservice.helpers.ORCIDHelper;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.VersionDAO;
import io.dropwizard.auth.Auth;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import io.swagger.discourse.client.ApiClient;
import io.swagger.discourse.client.ApiException;
import io.swagger.discourse.client.Configuration;
import io.swagger.discourse.client.api.TopicsApi;
import io.swagger.discourse.client.model.InlineResponse2005;
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
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.webservice.Constants.JWT_SECURITY_DEFINITION_NAME;
import static io.dockstore.webservice.resources.ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME;

/**
 * Prototype for methods that apply identically across tools and workflows.
 *
 * @author dyuen
 */
@Path("/entries")
@Api("entries")
@Produces(MediaType.APPLICATION_JSON)
@SecuritySchemes({ @SecurityScheme(type = SecuritySchemeType.HTTP, name = "bearer", scheme = "bearer") })
@Tag(name = "entries", description = ResourceConstants.ENTRIES)
public class EntryResource implements AuthenticatedResourceInterface, AliasableResourceInterface<Entry> {

    public static final String VERSION_NOT_BELONG_TO_ENTRY_ERROR_MESSAGE = "Version does not belong to entry";
    public static final String ENTRY_NO_DOI_ERROR_MESSAGE = "Entry does not have a concept DOI associated with it";
    public static final String VERSION_NO_DOI_ERROR_MESSAGE = "Version does not have a DOI url associated with it";
    private static final Logger LOG = LoggerFactory.getLogger(EntryResource.class);

    private final TokenDAO tokenDAO;
    private final ToolDAO toolDAO;
    private final VersionDAO versionDAO;
    private final TopicsApi topicsApi;
    private final String discourseKey;
    private final String discourseUrl;
    private final int discourseCategoryId;
    private final String discourseApiUsername = "system";
    private final int maxDescriptionLength = 500;
    private final String hostName;
    private String baseApiURL;

    public EntryResource(TokenDAO tokenDAO, ToolDAO toolDAO, VersionDAO versionDAO, DockstoreWebserviceConfiguration configuration) {
        this.toolDAO = toolDAO;
        this.versionDAO = versionDAO;
        this.tokenDAO = tokenDAO;
        discourseUrl = configuration.getDiscourseUrl();
        discourseKey = configuration.getDiscourseKey();
        discourseCategoryId = configuration.getDiscourseCategoryId();
        try {
            URL orcidAuthUrl = new URL(configuration.getUiConfig().getOrcidAuthUrl());
            // baseUrl should result in something like "https://api.sandbox.orcid.org/v3.0/" or "https://api.orcid.org/v3.0/";
            baseApiURL = orcidAuthUrl.getProtocol() + "://api." + orcidAuthUrl.getHost() + "/v3.0/";
        } catch (MalformedURLException e) {
            LOG.error("The ORCID Auth URL in the dropwizard configuration file is malformed.", e);
        }

        ApiClient apiClient = Configuration.getDefaultApiClient();
        apiClient.addDefaultHeader("Content-Type", "application/x-www-form-urlencoded");
        apiClient.addDefaultHeader("cache-control", "no-cache");
        apiClient.setBasePath(discourseUrl);

        hostName = configuration.getExternalConfig().getHostname();
        topicsApi = new TopicsApi(apiClient);
    }

    @POST
    @Timed
    @UnitOfWork
    @Override
    @Path("/{id}/aliases")
    @Operation(operationId = "addAliases", description = "Add aliases linked to a entry in Dockstore.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(nickname = "addAliases", value = "Add aliases linked to a entry in Dockstore.", authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, notes = "Aliases are alphanumerical (case-insensitive and may contain internal hyphens), given in a comma-delimited list.", response = Entry.class)
    @ApiResponse(responseCode = HttpStatus.SC_OK + "", description = "Successfully added alias to entry", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Entry.class)))
    public Entry addAliases(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
                               @ApiParam(value = "Entry to modify.", required = true) @PathParam("id") Long id,
                               @ApiParam(value = "Comma-delimited list of aliases.", required = true) @QueryParam("aliases") String aliases) {
        return AliasableResourceInterface.super.addAliases(user, id, aliases);
    }

    @GET
    @Path("/{id}/collections")
    @Timed
    @UnitOfWork(readOnly = true)
    @Operation(operationId = "entryCollections", description = "Get the collections and approved organizations that contain the published entry")
    @ApiOperation(value = "Get the collections and organizations that contain the published entry", notes = "Entry must be published", response = CollectionOrganization.class, responseContainer = "List")
    public List<CollectionOrganization> entryCollections(@ApiParam(value = "id", required = true) @PathParam("id") Long id) {
        Entry<? extends Entry, ? extends Version> entry = toolDAO.getGenericEntryById(id);
        if (entry == null || !entry.getIsPublished()) {
            throw new CustomWebApplicationException("Published entry does not exist.", HttpStatus.SC_BAD_REQUEST);
        }
        return this.toolDAO.findCollectionsByEntryId(entry.getId());
    }

    @GET
    @Path("/{entryId}/verifiedPlatforms")
    @UnitOfWork
    @ApiOperation(value = "Get the verified platforms for each version of an entry.",  hidden = true)
    @Operation(operationId = "getVerifiedPlatforms", description = "Get the verified platforms for each version of an entry.", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    public List<VersionVerifiedPlatform> getVerifiedPlatforms(@Parameter(hidden = true, name = "user")@Auth Optional<User> user,
            @Parameter(name = "entryId", description = "id of the entry", required = true, in = ParameterIn.PATH) @PathParam("entryId") Long entryId) {
        Entry<? extends Entry, ? extends Version> entry = toolDAO.getGenericEntryById(entryId);
        checkEntry(entry);

        checkEntryPermissions(user, entry);

        List<VersionVerifiedPlatform> verifiedVersions = versionDAO.findEntryVersionsWithVerifiedPlatforms(entryId);
        return verifiedVersions;
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{entryId}/versions/{versionId}/fileTypes")
    @ApiOperation(value = "Retrieve the file types of a version's sourcefiles",  hidden = true)
    @Operation(operationId = "getVersionsFileTypes", description = "Retrieve the unique file types of a version's sourcefile", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    public SortedSet<DescriptorLanguage.FileType> getVersionsFileTypes(@Parameter(hidden = true, name = "user")@Auth Optional<User> user,
            @Parameter(name = "entryId", description = "Entry to retrieve the version from", required = true, in = ParameterIn.PATH) @PathParam("entryId") Long entryId,
            @Parameter(name = "versionId", description = "Version to retrieve the sourcefile types from", required = true, in = ParameterIn.PATH) @PathParam("versionId") Long versionId) {
        Entry<? extends Entry, ? extends Version> entry = toolDAO.getGenericEntryById(entryId);
        checkEntry(entry);

        checkEntryPermissions(user, entry);

        Version version = versionDAO.findVersionInEntry(entryId, versionId);
        if (version == null) {
            throw new CustomWebApplicationException("Version " + versionId + " does not exist for this entry", HttpStatus.SC_BAD_REQUEST);
        }

        SortedSet<SourceFile> sourceFiles = version.getSourceFiles();
        return sourceFiles.stream().map(sourceFile -> sourceFile.getType()).collect(Collectors.toCollection(TreeSet::new));
    }

    public void checkEntryPermissions(final Optional<User> user, final Entry<? extends Entry, ? extends Version> entry) {
        if (!entry.getIsPublished()) {
            if (user.isEmpty()) {
                throw new CustomWebApplicationException("This entry is not published.", HttpStatus.SC_NOT_FOUND);
            }
            checkUser(user.get(), entry);
        }
    }

    @POST
    @Path("/{entryId}/exportToOrcid")
    @Timed
    @UnitOfWork
    @Operation(description = "Export entry to ORCID. DOI is required", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiResponse(responseCode = HttpStatus.SC_NO_CONTENT + "", description = "No Content")
    @ApiResponse(responseCode = HttpStatus.SC_INTERNAL_SERVER_ERROR + "", description = "Internal Server Error")
    @ApiResponse(responseCode = HttpStatus.SC_NOT_FOUND + "", description = "Not Found")
    @ApiResponse(responseCode = HttpStatus.SC_BAD_REQUEST + "", description = "Bad Request")
    @ApiOperation(value = "hidden", hidden = true)
    public void exportToORCID(@Parameter(hidden = true, name = "user") @Auth User user, @Parameter(description = "The id of the entry to export.", name = "entryId", in = ParameterIn.PATH, required = true)
        @PathParam("entryId") Long entryId,
        @Parameter(description = "Optional version ID of the entry version to export.", name = "versionId", in = ParameterIn.QUERY) @QueryParam("versionId") Long versionId) {
        Entry<? extends Entry, ? extends Version> entry = toolDAO.getGenericEntryById(entryId);
        checkEntry(entry);
        checkEntryPermissions(Optional.of(user), entry);
        List<Token> orcidByUserId = tokenDAO.findOrcidByUserId(user.getId());
        String putCode;
        Optional<Version> optionalVersion = Optional.empty();
        if (versionId != null) {
            Version version = versionDAO.findVersionInEntry(entry.getId(), versionId);
            if (version == null) {
                throw new CustomWebApplicationException(VERSION_NOT_BELONG_TO_ENTRY_ERROR_MESSAGE, HttpStatus.SC_BAD_REQUEST);
            }
            if (version.getDoiURL() == null) {
                throw new CustomWebApplicationException(VERSION_NO_DOI_ERROR_MESSAGE, HttpStatus.SC_BAD_REQUEST);
            }
            optionalVersion = Optional.ofNullable(version);
        } else {
            if (entry.getConceptDoi() == null) {
                throw new CustomWebApplicationException(ENTRY_NO_DOI_ERROR_MESSAGE, HttpStatus.SC_BAD_REQUEST);
            }
        }
        if (baseApiURL == null) {
            LOG.error("ORCID auth URL is likely incorrect");
            throw new CustomWebApplicationException("Could not export to ORCID: Dockstore ORCID integration is not set up correctly.", HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
        if (orcidByUserId.isEmpty()) {
            throw new CustomWebApplicationException("ORCID account is not linked to user account", HttpStatus.SC_BAD_REQUEST);
        }
        if (optionalVersion.isPresent()) {
            putCode = optionalVersion.get().getVersionMetadata().getOrcidPutCode();
        } else {
            putCode = entry.getOrcidPutCode();
        }
        String orcidWorkString;
        try {
            orcidWorkString = ORCIDHelper.getOrcidWorkString(entry, optionalVersion, putCode);
        } catch (JAXBException | DatatypeConfigurationException e) {
            throw new CustomWebApplicationException("Could not export to ORCID: " + e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
        try {
            if (putCode == null) {
                createOrcidWork(optionalVersion, entry, user, orcidWorkString, orcidByUserId);
            } else {
                updateOrcidWork(user, orcidWorkString, orcidByUserId, putCode);
            }
        } catch (IOException e) {
            throw new CustomWebApplicationException("Could not export to ORCID: " + e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void createOrcidWork(Optional<Version> optionalVersion, Entry entry, User user, String orcidWorkString, List<Token> orcidTokens)
            throws IOException {
        try (CloseableHttpResponse response = ORCIDHelper.postWorkString(baseApiURL, user.getOrcid(), orcidWorkString, orcidTokens.get(0).getToken())) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
                throw new CustomWebApplicationException("Could not export to ORCID: " + response.getStatusLine().getReasonPhrase(),
                        response.getStatusLine().getStatusCode());
            } else {
                if (optionalVersion.isPresent()) {
                    optionalVersion.get().getVersionMetadata().setOrcidPutCode(getPutCodeFromLocation(response));
                } else {
                    entry.setOrcidPutCode(getPutCodeFromLocation(response));
                }
            }
        }
    }

    private void updateOrcidWork(User user, String orcidWorkString, List<Token> orcidTokens, String putCode) throws IOException {
        try (CloseableHttpResponse response = ORCIDHelper.putWorkString(baseApiURL, user.getOrcid(), orcidWorkString, orcidTokens.get(0).getToken(), putCode)) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                throw new CustomWebApplicationException("Could not export to ORCID: " + response.getStatusLine().getReasonPhrase(),
                        response.getStatusLine().getStatusCode());
            }
        }
    }

    /**
     * Get the ORCID put code from the response
     * @param httpResponse
     * @return
     */
    private static String getPutCodeFromLocation(HttpResponse httpResponse) {
        Header[] locations = httpResponse.getHeaders("Location");
        URI uri;
        try {
            uri = new URI(locations[0].getValue());
        } catch (URISyntaxException e) {
            throw new CustomWebApplicationException("Could not get ORCID work put code", HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @POST
    @Path("/{id}/topic")
    @Timed
    @RolesAllowed({ "curator", "admin" })
    @UnitOfWork
    @ApiOperation(value = "Create a discourse topic for an entry.", authorizations = {
            @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Entry.class)
    @Operation(description = "Create a discourse topic for an entry.", security = @SecurityRequirement(name = "bearer"))
    public Entry setDiscourseTopic(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam(value = "The id of the entry to add a topic to.", required = true)
            @Parameter(description = "The id of the entry to add a topic to.", name = "id", in = ParameterIn.PATH, required = true)
            @PathParam("id") Long id) {
        return createAndSetDiscourseTopic(id);
    }

    /**
     * For a given entry, create a Discourse thread if applicable and set in database
     * @param id entry id
     * @return Entry with discourse ID set
     */
    public Entry createAndSetDiscourseTopic(Long id) throws CustomWebApplicationException {
        Entry entry = this.toolDAO.getGenericEntryById(id);

        if (entry == null || !entry.getIsPublished()) {
            throw new CustomWebApplicationException("Entry " + id + " does not exist or is not published.", HttpStatus.SC_NOT_FOUND);
        }

        if (entry.getTopicId() != null) {
            throw new CustomWebApplicationException("Entry " + id + " already has an associated Discourse topic.", HttpStatus.SC_BAD_REQUEST);
        }

        // Create title and link to entry

        String entryLink = "https://dockstore.org/";
        String title = "";
        if (hostName.contains("staging")) {
            entryLink = "https://staging.dockstore.org/";
            title = "Staging ";
        }
        if (entry instanceof BioWorkflow) {
            title += ((BioWorkflow)(entry)).getWorkflowPath();
            entryLink += "workflows/";
        } else if (entry instanceof Service) {
            title += ((Service)(entry)).getWorkflowPath();
            entryLink += "services/";
        } else {
            title += ((Tool)(entry)).getToolPath();
            entryLink += "tools/";
        }

        entryLink += title;

        // Create description
        String description = "";
        if (entry.getDescription() != null) {
            description = entry.getDescription() != null ? entry.getDescription().substring(0, Math.min(entry.getDescription().length(), maxDescriptionLength)) : "";
        }

        description += "\n<hr>\n<small>This is a companion discussion topic for the original entry at <a href='" + entryLink + "'>" + title + "</a></small>\n";

        // Check that discourse is reachable
        boolean isReachable;
        HttpURLConnection connection = null;
        try {
            URL url = new URL(discourseUrl);
            connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            int respCode = connection.getResponseCode();
            isReachable = respCode == HttpStatus.SC_OK;
        } catch (IOException ex) {
            LOG.error("Error reaching " + discourseUrl, ex);
            isReachable = false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (isReachable) {
            // Create a discourse topic
            InlineResponse2005 response;
            try {
                response = topicsApi.postsJsonPost(description, discourseKey, discourseApiUsername, title, null, discourseCategoryId, null, null, null);
                entry.setTopicId(response.getTopicId().longValue());
            } catch (ApiException ex) {
                String message = "Could not add a topic " + title + " to category " + discourseCategoryId;
                LOG.error(message, ex);
                throw new CustomWebApplicationException(message, HttpStatus.SC_BAD_REQUEST);
            }
        }

        return entry;
    }

    @Override
    public Optional<PublicStateManager> getPublicStateManager() {
        return Optional.of(PublicStateManager.getInstance());
    }

    @Override
    public Entry getAndCheckResource(User user, Long id) {
        Entry<? extends Entry, ? extends Version> c = toolDAO.getGenericEntryById(id);
        checkEntry(c);
        checkUserCanUpdate(user, c);
        return c;
    }

    @Override
    public Entry getAndCheckResourceByAlias(String alias) {
        throw new UnsupportedOperationException("Use the TRS API for tools and workflows");
    }
}
