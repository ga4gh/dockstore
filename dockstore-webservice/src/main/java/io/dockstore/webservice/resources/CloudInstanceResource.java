package io.dockstore.webservice.resources;

import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.Timed;
import io.dockstore.webservice.core.CloudInstance;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.jdbi.CloudInstanceDAO;
import io.dropwizard.auth.Auth;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.http.HttpStatus;
import org.hibernate.SessionFactory;

import static io.dockstore.webservice.resources.ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME;

@Path("/cloudInstances")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Cloud Instances")
public class CloudInstanceResource implements AuthenticatedResourceInterface {
    private CloudInstanceDAO cloudInstanceDAO;

    public CloudInstanceResource(SessionFactory sessionFactory) {
        this.cloudInstanceDAO = new CloudInstanceDAO(sessionFactory);

    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Operation(operationId = "getCloudInstances", summary = "Get all known public cloud instances")
    @ApiResponse(responseCode = HttpStatus.SC_OK
            + "", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = CloudInstance.class))))
    public List<CloudInstance> getCloudInstances() {
        return this.cloudInstanceDAO.findAllWithoutUser();
    }

    @DELETE
    @Timed
    @UnitOfWork
    @Path("/{cloudInstanceId}")
    @RolesAllowed({ "admin" })
    @Operation(operationId = "deleteCloudInstance", summary = "Delete a public cloud instance, admin only", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiResponse(responseCode = HttpStatus.SC_NO_CONTENT + "", description = "No Content")
    @ApiResponse(responseCode = HttpStatus.SC_FORBIDDEN + "", description = "Forbidden")
    @ApiResponse(responseCode = HttpStatus.SC_UNAUTHORIZED + "", description = "Unauthorized")
    public void deleteCloudInstance(@Parameter(hidden = true, name = "user") @Auth User user,
            @Parameter(description = "ID of cloud instance to delete", name = "cloudInstanceId", in = ParameterIn.PATH, required = true) @PathParam("cloudInstanceId") Long cloudInstanceId) {
        this.cloudInstanceDAO.deleteById(cloudInstanceId);
    }

    @POST
    @Timed
    @UnitOfWork
    @RolesAllowed({ "admin" })
    @Operation(operationId = "postCloudInstance", summary = "Add a new public cloud instance, admin only", security = @SecurityRequirement(name = OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiResponse(responseCode = HttpStatus.SC_NO_CONTENT + "", description = "No Content")
    @ApiResponse(responseCode = HttpStatus.SC_FORBIDDEN + "", description = "Forbidden")
    @ApiResponse(responseCode = HttpStatus.SC_UNAUTHORIZED + "", description = "Unauthorized")
    public void postCloudInstance(@Parameter(hidden = true, name = "user") @Auth User user,
            @Parameter(name = "Cloud Instance", description = "Cloud instance to create", required = true) CloudInstance cloudInstance) {
        CloudInstance cloudInstanceToBeAdded = new CloudInstance();
        cloudInstanceToBeAdded.setPartner(cloudInstance.getPartner());
        cloudInstanceToBeAdded.setUrl(cloudInstance.getUrl());
        cloudInstanceToBeAdded.setSupportsFileImports(cloudInstance.isSupportsFileImports());
        cloudInstanceToBeAdded.setSupportsHttpImports(cloudInstance.isSupportsHttpImports());
        cloudInstanceToBeAdded.setSupportedLanguages(cloudInstance.getSupportedLanguages());
        cloudInstanceToBeAdded.setUser(null);
        this.cloudInstanceDAO.create(cloudInstanceToBeAdded);
    }
}
