package io.dockstore.webservice.resources;

import java.util.List;
import java.util.Set;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.Timed;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.LambdaEvent;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.helpers.GitHubSourceCodeRepo;
import io.dockstore.webservice.helpers.SourceCodeRepoFactory;
import io.dockstore.webservice.jdbi.LambdaEventDAO;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dropwizard.auth.Auth;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.http.HttpStatus;
import org.hibernate.SessionFactory;

import static io.dockstore.webservice.resources.ResourceConstants.PAGINATION_LIMIT;
import static io.dockstore.webservice.resources.ResourceConstants.PAGINATION_LIMIT_TEXT;
import static io.dockstore.webservice.resources.ResourceConstants.PAGINATION_OFFSET_TEXT;

@Path("/lambdaEvents")
@Api("/lambdaEvents")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "lambdaEvents", description = ResourceConstants.LAMBDAEVENTS)
public class LambdaEventResource {
    private final LambdaEventDAO lambdaEventDAO;
    private final UserDAO userDAO;
    private final TokenDAO tokenDAO;

    public LambdaEventResource(SessionFactory sessionFactory) {
        this.lambdaEventDAO = new LambdaEventDAO(sessionFactory);
        this.userDAO = new UserDAO(sessionFactory);
        this.tokenDAO = new TokenDAO(sessionFactory);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("/{organization}")
    @Operation(operationId = "getLambdaEventsByOrganization", description = "Get all of the Lambda Events for the given GitHub organization.", security = @SecurityRequirement(name = ResourceConstants.OPENAPI_JWT_SECURITY_DEFINITION_NAME))
    @ApiOperation(value = "See OpenApi for details")
    public List<LambdaEvent> getLambdaEventsByOrganization(@ApiParam(hidden = true) @Parameter(hidden = true, name = "user")@Auth User user,
            @ApiParam(value = "organization", required = true) @PathParam("organization") String organization,
            @ApiParam(value = PAGINATION_OFFSET_TEXT) @QueryParam("offset") @DefaultValue("0") String offset,
            @ApiParam(value = PAGINATION_LIMIT_TEXT, allowableValues = "range[1,100]", defaultValue = PAGINATION_LIMIT) @DefaultValue(PAGINATION_LIMIT) @QueryParam("limit") Integer limit) {
        User authUser = userDAO.findById(user.getId());
        List<Token> githubToken = tokenDAO.findGithubByUserId(authUser.getId());
        if (githubToken.size() == 0) {
            throw new CustomWebApplicationException("You do not have GitHub connected to your account.", HttpStatus.SC_BAD_REQUEST);
        }

        GitHubSourceCodeRepo sourceCodeRepoInterface = (GitHubSourceCodeRepo)SourceCodeRepoFactory.createSourceCodeRepo(githubToken.get(0));
        Set<String> organizations = sourceCodeRepoInterface.getMyOrganizations();
        if (!organizations.contains(organization)) {
            throw new CustomWebApplicationException("You do not have access to the GitHub organization '" + organization + "'", HttpStatus.SC_UNAUTHORIZED);
        }

        return lambdaEventDAO.findByOrganization(organization, offset, limit);
    }
}
