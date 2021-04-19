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
package io.dockstore.webservice.resources.proposedGA4GH;

import java.util.Map;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.resources.ResourceConstants;
import io.dropwizard.auth.Auth;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.api.NotFoundException;
import io.swagger.model.Error;
import io.swagger.model.ToolV1;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.http.HttpStatus;

import static io.dockstore.webservice.Constants.JWT_SECURITY_DEFINITION_NAME;

/**
 * GET methods for organization related information on path: /api/ga4gh/v2/tools
 */
@Path(DockstoreWebserviceApplication.GA4GH_API_PATH_V2_BETA + "/extended")
@Api("extendedGA4GH")
@Produces({ "application/json", "text/plain" })
@Tag(name = "extendedGA4GH", description = ResourceConstants.EXTENDEDGA4GH)
public class ToolsExtendedApi {
    private final ToolsExtendedApiService delegate = ToolsApiExtendedServiceFactory.getToolsExtendedApi();

    @GET
    @Path("/tools/{organization}")
    @UnitOfWork(readOnly = true)
    @Produces({ MediaType.APPLICATION_JSON })
    @ApiOperation(nickname = ToolsOrgGet.OPERATION_ID, value = ToolsOrgGet.SUMMARY, notes = ToolsOrgGet.DESCRIPTION, response = ToolV1.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = ToolsOrgGet.OK_RESPONSE, response = ToolV1.class, responseContainer = "List") })
    @Operation(operationId = ToolsOrgGet.OPERATION_ID, summary = ToolsOrgGet.SUMMARY, description = ToolsOrgGet.DESCRIPTION, responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_OK + "", description = ToolsOrgGet.OK_RESPONSE, content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = ToolV1.class))))
    })
    public Response toolsOrgGet(
            @ApiParam(value = "An organization, for example `cancercollaboratory`", required = true) @PathParam("organization") String organization,
            @Context SecurityContext securityContext) throws NotFoundException {
        return delegate.toolsOrgGet(organization, securityContext);
    }

    @POST
    @Path("/tools/entry/_search")
    @Produces({ MediaType.APPLICATION_JSON })
    @ApiOperation(nickname = ToolsIndexSearch.OPERATION_ID, value = ToolsIndexSearch.SUMMARY, notes = ToolsIndexSearch.DESCRIPTION, response = String.class)
    @ApiResponses(value = { @ApiResponse(code = HttpStatus.SC_OK, message = ToolsIndexSearch.OK_RESPONSE, response = String.class) })
    @Operation(operationId = ToolsIndexSearch.OPERATION_ID, summary = ToolsIndexSearch.SUMMARY, description = ToolsIndexSearch.DESCRIPTION, responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_OK + "", description = ToolsIndexSearch.OK_RESPONSE, content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = String.class)))
    })
    public Response toolsIndexSearch(@ApiParam(value = "elastic search query", required = true) String query,
                                       @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        return delegate.toolsIndexSearch(query, uriInfo != null ? uriInfo.getQueryParameters() : null, securityContext);
    }

    @POST
    @Path("/tools/index")
    @UnitOfWork
    @RolesAllowed({"curator", "admin"})
    @Produces({ MediaType.TEXT_PLAIN })
    @ApiOperation(value = ToolsIndexGet.SUMMARY, notes = ToolsIndexGet.DESCRIPTION, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) }, response = Integer.class)
    @ApiResponses(value = { @ApiResponse(code = HttpStatus.SC_OK, message = ToolsIndexGet.OK_RESPONSE) })
    @Operation(operationId = ToolsIndexGet.SUMMARY, summary = ToolsIndexGet.SUMMARY, description = ToolsIndexGet.DESCRIPTION, security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME), responses = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_OK + "", description = ToolsIndexGet.OK_RESPONSE, content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(implementation = Integer.class)))
    })
    public Response toolsIndexGet(@ApiParam(hidden = true) @Parameter(hidden = true) @Auth User user, @Context SecurityContext securityContext)
        throws NotFoundException {
        return delegate.toolsIndexGet(securityContext);
    }

    @GET
    @Path("/workflows/{organization}")
    @UnitOfWork(readOnly = true)
    @Produces({ MediaType.APPLICATION_JSON })
    @ApiOperation(nickname = WorkflowsOrgGet.OPERATION_ID, value = WorkflowsOrgGet.SUMMARY, notes = WorkflowsOrgGet.DESCRIPTION, response = ToolV1.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = WorkflowsOrgGet.OK_RESPONSE, response = ToolV1.class, responseContainer = "List") })
    @Operation(operationId = WorkflowsOrgGet.OPERATION_ID, summary = WorkflowsOrgGet.SUMMARY, description = WorkflowsOrgGet.DESCRIPTION, responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_OK + "", description = WorkflowsOrgGet.OK_RESPONSE, content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = ToolV1.class))))
    })
    public Response workflowsOrgGet(
            @ApiParam(value = "An organization, for example `cancercollaboratory`", required = true) @PathParam("organization") String organization,
            @Context SecurityContext securityContext) throws NotFoundException {
        return delegate.workflowsOrgGet(organization, securityContext);
    }

    @GET
    @Path("/containers/{organization}")
    @UnitOfWork(readOnly = true)
    @Produces({ MediaType.APPLICATION_JSON })
    @ApiOperation(value = EntriesOrgGet.SUMMARY, nickname = EntriesOrgGet.OPERATION_ID, notes = EntriesOrgGet.DESCRIPTION, response = ToolV1.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = EntriesOrgGet.OK_RESPONSE, response = ToolV1.class, responseContainer = "List") })
    @Operation(operationId = EntriesOrgGet.OPERATION_ID, summary = EntriesOrgGet.SUMMARY, description = EntriesOrgGet.DESCRIPTION, responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_OK + "", description = EntriesOrgGet.OK_RESPONSE, content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = ToolV1.class))))
    })
    public Response entriesOrgGet(
            @ApiParam(value = "An organization, for example `cancercollaboratory`", required = true) @PathParam("organization") String organizations,
            @Context SecurityContext securityContext) throws NotFoundException {
        return delegate.entriesOrgGet(organizations, securityContext);
    }

    @GET
    @Path("/organizations")
    @UnitOfWork(readOnly = true)
    @Produces({ MediaType.APPLICATION_JSON })
    @ApiOperation(value = "List all organizations", nickname = EntriesOrgsGet.OPERATION_ID, notes = EntriesOrgsGet.DESCRIPTION, response = String.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = HttpStatus.SC_OK, message = EntriesOrgsGet.OK_RESPONSE, response = String.class, responseContainer = "List") })
    @Operation(operationId = EntriesOrgsGet.OPERATION_ID, summary = EntriesOrgsGet.SUMMARY, description = EntriesOrgsGet.DESCRIPTION, responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_OK + "", description = EntriesOrgsGet.OK_RESPONSE, content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = String.class))))
    })
    public Response entriesOrgGet(
            @Context SecurityContext securityContext) {
        return delegate.organizationsGet(securityContext);
    }

    @POST
    @UnitOfWork
    @RolesAllowed({ "curator", "admin" })
    @Path("/{id}/versions/{version_id}/{type}/tests/{relative_path : .+}")
    @Produces({ MediaType.APPLICATION_JSON })
    @ApiOperation(value = VerifyTestParameterFilePost.SUMMARY, notes = VerifyTestParameterFilePost.DESCRIPTION, response = Map.class, authorizations = {
        @Authorization(value = JWT_SECURITY_DEFINITION_NAME) })
    @ApiResponses(value = {
        @ApiResponse(code = HttpStatus.SC_OK, message = VerifyTestParameterFilePost.OK_RESPONSE, response = Map.class),
        @ApiResponse(code = HttpStatus.SC_NOT_FOUND, message = VerifyTestParameterFilePost.NOT_FOUND_RESPONSE, response = Error.class),
        @ApiResponse(code = HttpStatus.SC_UNAUTHORIZED, message = VerifyTestParameterFilePost.UNAUTHORIZED_RESPONSE, response = Error.class) })
    @Operation(operationId = "verifyTestParameterFilePost", summary = VerifyTestParameterFilePost.SUMMARY, description = VerifyTestParameterFilePost.DESCRIPTION, security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME), responses = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_OK + "", description = VerifyTestParameterFilePost.OK_RESPONSE, content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Map.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_UNAUTHORIZED + "", description = VerifyTestParameterFilePost.UNAUTHORIZED_RESPONSE, content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Error.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = HttpStatus.SC_NOT_FOUND + "", description = VerifyTestParameterFilePost.NOT_FOUND_RESPONSE, content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Error.class)))
    })
    @SuppressWarnings("checkstyle:parameternumber")
    public Response toolsIdVersionsVersionIdTypeTestsPost(@ApiParam(hidden = true) @Parameter(hidden = true) @Auth User user,
        @ApiParam(value = "The type of the underlying descriptor. Allowable values include \"CWL\", \"WDL\", \"NFL\".", required = true) @PathParam("type") String type,
        @ApiParam(value = "A unique identifier of the tool, scoped to this registry, for example `123456`", required = true) @PathParam("id") String id,
        @ApiParam(value = "An identifier of the tool version for this particular tool registry, for example `v1`", required = true) @PathParam("version_id") String versionId,
        @ApiParam(value = "A relative path to the test json as retrieved from the files endpoint or the tests endpoint", required = true) @PathParam("relative_path") String relativePath,
        @ApiParam(value = "Platform to report on", required = true) @QueryParam("platform") String platform,
        @ApiParam(value = "Version of the platform to report on", required = true) @QueryParam("platform_version") String platformVersion,
        @ApiParam(value = "Verification status, omit to delete key") @QueryParam("verified") Boolean verified,
        @ApiParam(value = "Additional information on the verification (notes, explanation)", required = true) @QueryParam("metadata") String metadata,
        @Context SecurityContext securityContext, @Context ContainerRequestContext containerContext) {
        return delegate.setSourceFileMetadata(type, id, versionId, platform, platformVersion, relativePath, verified, metadata);
    }

    private static final class VerifyTestParameterFilePost {
        public static final String SUMMARY = "Annotate test JSON with information on whether it ran successfully on particular platforms plus metadata";
        public static final String DESCRIPTION = "Test JSON can be annotated with whether they ran correctly keyed by platform and associated with some metadata.";
        public static final String OK_RESPONSE = "The tool test JSON response.";
        public static final String NOT_FOUND_RESPONSE = "The tool test cannot be found to annotate.";
        public static final String UNAUTHORIZED_RESPONSE = "Credentials not provided or incorrect.";
    }
    private static final class ToolsIndexGet {
        public static final String SUMMARY = "Update the workflows and tools indices";
        public static final String DESCRIPTION = "This endpoint updates the indices for all published tools and workflows.";
        public static final String OK_RESPONSE = "Workflows and tools indices populated with entries.";
    }
    private static final class EntriesOrgsGet {
        public static final String OPERATION_ID = "entriesOrgsGet";
        public static final String SUMMARY = "List all organizations";
        public static final String DESCRIPTION = "This endpoint returns list of all organizations.";
        public static final String OK_RESPONSE = "An array of organizations' names.";
    }
    private static final class EntriesOrgGet {
        public static final String OPERATION_ID = "entriesOrgGet";
        public static final String SUMMARY = "List entries of an organization";
        public static final String DESCRIPTION = "This endpoint returns entries of an organization.";
        public static final String OK_RESPONSE = "An array of Tools of the input organization.";
    }
    private static final class WorkflowsOrgGet {
        public static final String OPERATION_ID = "workflowsOrgGet";
        public static final String SUMMARY = "List workflows of an organization";
        public static final String DESCRIPTION = "This endpoint returns workflows of an organization.";
        public static final String OK_RESPONSE = "An array of Tools of the input organization.";
    }
    private static final class ToolsIndexSearch {
        public static final String OPERATION_ID = "toolsIndexSearch";
        public static final String SUMMARY = "Search the tools and workflows indices.";
        public static final String DESCRIPTION = "This endpoint searches the indices for all published tools and workflows. Used by utilities that expect to talk to an elastic search endpoint.";
        public static final String OK_RESPONSE = "An elastic search result.";

    }
    private static final class ToolsOrgGet {
        public static final String OPERATION_ID = "toolsOrgGet";
        public static final String SUMMARY = "List tools of an organization";
        public static final String DESCRIPTION = "This endpoint returns tools of an organization.";
        public static final String OK_RESPONSE = "An array of Tools of the input organization.";
    }
}
