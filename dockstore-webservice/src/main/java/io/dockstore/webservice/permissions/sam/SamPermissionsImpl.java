package io.dockstore.webservice.permissions.sam;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.TokenType;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.helpers.GoogleHelper;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.permissions.Action;
import io.dockstore.webservice.permissions.Permission;
import io.dockstore.webservice.permissions.PermissionsInterface;
import io.dockstore.webservice.permissions.Role;
import io.swagger.sam.client.ApiClient;
import io.swagger.sam.client.ApiException;
import io.swagger.sam.client.JSON;
import io.swagger.sam.client.api.ResourcesApi;
import io.swagger.sam.client.model.AccessPolicyMembership;
import io.swagger.sam.client.model.AccessPolicyResponseEntry;
import io.swagger.sam.client.model.ErrorReport;
import io.swagger.sam.client.model.ResourceAndAccessPolicy;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * An implementation of the {@link PermissionsInterface} that makes
 * calls to SAM.
 */
public class SamPermissionsImpl implements PermissionsInterface {

    private static final Logger LOG = LoggerFactory.getLogger(SamPermissionsImpl.class);

    /**
     * A map of SAM policy names to Dockstore roles.
     */
    private static Map<String, Role> samPermissionMap = new HashMap<>();
    static {
        samPermissionMap.put(SamConstants.OWNER_POLICY, Role.OWNER);
        samPermissionMap.put(SamConstants.WRITE_POLICY, Role.WRITER);
        samPermissionMap.put(SamConstants.READ_POLICY, Role.READER);
    }

    /**
     * A map of Dockstore roles to SAM policy names. Created by swapping the keys and values
     * in the <code>samPermissionMap</code>.
     */
    private static Map<Role, String> permissionSamMap =
            samPermissionMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, c -> c.getKey()));

    private DockstoreWebserviceConfiguration config;
    private final TokenDAO tokenDAO;

    public SamPermissionsImpl(TokenDAO tokenDAO, DockstoreWebserviceConfiguration config) {
        this.tokenDAO = tokenDAO;
        this.config = config;
    }

    @Override
    public List<Permission> setPermission(Workflow workflow, User requester, Permission permission) {
        ResourcesApi resourcesApi = getResourcesApi(requester);
        try {
            final String encodedPath = encodedWorkflowResource(workflow, resourcesApi.getApiClient());

            ensureResourceExists(workflow, requester, resourcesApi, encodedPath);

            resourcesApi.addUserToPolicy(SamConstants.RESOURCE_TYPE,
                    encodedPath,
                    permissionSamMap.get(permission.getRole()),
                    permission.getEmail());
            return getPermissionsForWorkflow(requester, workflow);
        } catch (ApiException e) {
            String errorMessage = readValue(e, ErrorReport.class)
                    .map(errorReport -> errorReport.getMessage())
                    .orElse("Error setting permission");
            LOG.error(errorMessage, e);
            throw new CustomWebApplicationException(errorMessage, e.getCode());
        }
    }

    ResourcesApi getResourcesApi(User requester) {
        return new ResourcesApi(getApiClient(requester));
    }

    private void ensureResourceExists(Workflow workflow, User requester, ResourcesApi resourcesApi, String encodedPath) {
        try {
            resourcesApi.listResourcePolicies(SamConstants.RESOURCE_TYPE, encodedPath);
        } catch (ApiException e) {
            if (e.getCode() == HttpStatus.SC_NOT_FOUND) {
                initializePermission(workflow, requester);
            } else {
                throw new CustomWebApplicationException("Error listing permissions", e.getCode());
            }
        }
    }

    @Override
    public List<String> workflowsSharedWithUser(User user) {
        ResourcesApi resourcesApi = getResourcesApi(user);
        try {
            List<ResourceAndAccessPolicy> resourceAndAccessPolicies = resourcesApi.listResourcesAndPolicies(SamConstants.RESOURCE_TYPE);
            return resourceAndAccessPolicies
                    .stream()
                    .filter(resourceAndAccessPolicy -> !SamConstants.OWNER_POLICY.equals(resourceAndAccessPolicy.getAccessPolicyName()))
                    .map(resourceAndPolicy -> resourceAndPolicy.getResourceId())
                    .collect(Collectors.toList());
        } catch (ApiException e) {
            LOG.error("Error getting shared workflows", e);
            throw new CustomWebApplicationException("Error getting shared workflows", e.getCode());
        }
    }

    @Override
    public List<Permission> getPermissionsForWorkflow(User user, Workflow workflow) {
        ResourcesApi resourcesApi = getResourcesApi(user);
        try {
            String encoded = encodedWorkflowResource(workflow, resourcesApi.getApiClient());
            return accessPolicyResponseEntryToUserPermissions(resourcesApi.listResourcePolicies(SamConstants.RESOURCE_TYPE,
                    encoded));
        } catch (ApiException e) {
            // If 404, the SAM resource has not yet been created, so just return an empty list.
            if (e.getCode() != HttpStatus.SC_NOT_FOUND) {
                throw new CustomWebApplicationException("Error getting permissions", e.getCode());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void removePermission(Workflow workflow, User user, String email, Role role) {
        ResourcesApi resourcesApi = getResourcesApi(user);
        String encodedPath = encodedWorkflowResource(workflow, resourcesApi.getApiClient());
        try {
            List<AccessPolicyResponseEntry> entries = resourcesApi.listResourcePolicies(SamConstants.RESOURCE_TYPE, encodedPath);
            for (AccessPolicyResponseEntry entry : entries) {
                if (permissionSamMap.get(role).equals(entry.getPolicyName())) {
                    if (entry.getPolicy().getMemberEmails().contains(email)) {
                        resourcesApi.removeUserFromPolicy(SamConstants.RESOURCE_TYPE, encodedPath, entry.getPolicyName(), email);
                    }
                }
            }
        } catch (ApiException e) {
            LOG.error(MessageFormat.format("Error removing {0} from workflow {1}", email, encodedPath), e);
            throw new CustomWebApplicationException("Error removing permissions", e.getCode());
        }
    }

    @Override
    public void initializePermission(Workflow workflow, User user) {
        ResourcesApi resourcesApi = getResourcesApi(user);
        String encodedPath = encodedWorkflowResource(workflow, resourcesApi.getApiClient());
        try {
            resourcesApi.createResourceWithDefaults(SamConstants.RESOURCE_TYPE, encodedPath);

            final AccessPolicyMembership writerPolicy = new AccessPolicyMembership();
            writerPolicy.addRolesItem("writer");
            resourcesApi.overwritePolicy(SamConstants.RESOURCE_TYPE, encodedPath, SamConstants.WRITE_POLICY, writerPolicy);

            final AccessPolicyMembership readerPolicy = new AccessPolicyMembership();
            readerPolicy.addRolesItem("reader");
            resourcesApi.overwritePolicy(SamConstants.RESOURCE_TYPE, encodedPath, SamConstants.READ_POLICY, readerPolicy);
        } catch (ApiException e) {
            throw new CustomWebApplicationException("Error initializing permissions", e.getCode());
        }
    }

    @Override
    public boolean canDoAction(User user, Workflow workflow, Action action) {
        ResourcesApi resourcesApi = getResourcesApi(user);
        String encodedPath = encodedWorkflowResource(workflow, resourcesApi.getApiClient());
        try {
            return resourcesApi.resourceAction(SamConstants.RESOURCE_TYPE, encodedPath, SamConstants.toSamAction(action));
        } catch (ApiException e) {
            return false;
        }
    }

    private ApiClient getApiClient(User user) {
        ApiClient apiClient = new ApiClient() {
            @Override
            protected void performAdditionalClientConfiguration(ClientConfig clientConfig) {
                // Calling ResourcesApi.addUserToPolicy invokes PUT without a body, which will fail
                // without this:
                clientConfig.property(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION, true);
            }
        };
        apiClient.setBasePath(config.getSamConfiguration().getBasepath());
        return googleAccessToken(user)
            .map(credentials -> {
                apiClient.setAccessToken(credentials);
                return apiClient;
            })
            .orElseThrow(() -> new CustomWebApplicationException("Unauthorized", HttpStatus.SC_UNAUTHORIZED));
    }

    private String encodedWorkflowResource(Workflow workflow, ApiClient apiClient) {
        final StringBuilder sb = new StringBuilder(SamConstants.WORKFLOW_PREFIX);
        sb.append(workflow.getWorkflowPath());
        return apiClient.escapeString(sb.toString());
    }

    /**
     * Gets a non-expired access token, which may entail refreshing the token. If the token
     * is refreshed, the access token is updated in the user table.
     *
     * @param user
     * @return
     */
    Optional<String> googleAccessToken(User user) {
        List<Token> tokens = tokenDAO.findByUserId(user.getId());
        Token token = Token.extractToken(tokens, TokenType.GOOGLE_COM);
        if (token != null) {
            return GoogleHelper.getValidAccessToken(token, config.getGoogleClientID(), config.getGoogleClientSecret())
                    .map(accessToken -> {
                        if (!accessToken.equals(token.getToken())) {
                            token.setContent(accessToken);
                            tokenDAO.update(token);
                        }
                        return Optional.of(accessToken);
                    })
                    .orElse(Optional.empty());
        }
        return Optional.empty();
    }

    List<Permission> accessPolicyResponseEntryToUserPermissions(List<AccessPolicyResponseEntry> accessPolicyList) {
        return accessPolicyList.stream().map(accessPolicy -> {
            Role role = samPermissionMap.get(accessPolicy.getPolicy().getRoles().get(0));
            return accessPolicy.getPolicy().getMemberEmails().stream().map(email -> {
                Permission permission = new Permission();
                permission.setRole(role);
                permission.setEmail(email);
                return permission;
            });
        }).flatMap(s -> s).collect(Collectors.toList());
    }

    <T> Optional<T> readValue(ApiException e, Class<T> clazz) {
        String body = e.getResponseBody();
        return readValue(body, clazz);
    }

    <T> Optional<T> readValue(String body, Class<T> clazz) {
        try {
            ObjectMapper context = new JSON().getContext(clazz);
            return Optional.of(context.readValue(body, clazz));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

}
