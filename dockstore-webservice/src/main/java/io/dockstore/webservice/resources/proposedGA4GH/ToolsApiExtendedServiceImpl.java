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

import static io.openapi.api.impl.ToolsApiServiceImpl.BAD_DECODE_RESPONSE;

import com.google.common.io.Resources;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Tag;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.helpers.ElasticSearchHelper;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.helpers.statelisteners.ElasticListener;
import io.dockstore.webservice.jdbi.AppToolDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.openapi.api.impl.ToolsApiServiceImpl;
import io.swagger.api.impl.ToolsImplCommon;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.http.HttpStatus;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementations of methods to return responses containing organization related information
 * @author kcao on 01/03/17.
 *
 */
public class ToolsApiExtendedServiceImpl extends ToolsExtendedApiService {

    private static final Logger LOG = LoggerFactory.getLogger(ToolsApiExtendedServiceImpl.class);

    private static final String TOOLS_INDEX = ElasticListener.TOOLS_INDEX;
    private static final String WORKFLOWS_INDEX = ElasticListener.WORKFLOWS_INDEX;
    private static final String ALL_INDICES = ElasticListener.ALL_INDICES;
    private static final int SEARCH_TERM_LIMIT = 500;

    private static ToolDAO toolDAO = null;
    private static WorkflowDAO workflowDAO = null;
    private static AppToolDAO appToolDAO = null;
    private static DockstoreWebserviceConfiguration config = null;
    private static PublicStateManager publicStateManager = null;

    public static void setStateManager(PublicStateManager manager) {
        ToolsApiExtendedServiceImpl.publicStateManager = manager;
    }

    public static void setToolDAO(ToolDAO toolDAO) {
        ToolsApiExtendedServiceImpl.toolDAO = toolDAO;
    }

    public static void setWorkflowDAO(WorkflowDAO workflowDAO) {
        ToolsApiExtendedServiceImpl.workflowDAO = workflowDAO;
    }

    public static void setAppToolDAO(AppToolDAO appToolDAO) {
        ToolsApiExtendedServiceImpl.appToolDAO = appToolDAO;
    }

    public static void setConfig(DockstoreWebserviceConfiguration config) {
        ToolsApiExtendedServiceImpl.config = config;
    }

    /**
     * Avoid using this one, this is quite slow
     *
     * @return
     */
    private List<Entry> getPublished() {
        final List<Entry> published = new ArrayList<>();
        published.addAll(toolDAO.findAllPublished());
        published.addAll(workflowDAO.findAllPublished());
        published.addAll(appToolDAO.finalAllPublished());
        published.sort(Comparator.comparing(Entry::getGitUrl));
        return published;
    }

    /**
     * More optimized
     *
     * @param organization
     * @return
     */
    private List<Entry<?, ?>> getPublishedByOrganization(String organization) {
        final List<Entry<?, ?>> published = new ArrayList<>();
        published.addAll(workflowDAO.findPublishedByOrganization(organization));
        published.addAll(toolDAO.findPublishedByNamespace(organization));
        published.sort(Comparator.comparing(Entry::getGitUrl));
        return published;
    }

    @Override
    public Response toolsOrgGet(String organization, SecurityContext securityContext) {
        return Response.ok().entity(getPublishedByOrganization(organization)).build();
    }

    private List<io.openapi.model.Tool> workflowOrgGetList(String organization) {
        List<Workflow> published = workflowDAO.findPublishedByOrganization(organization);
        return published.stream().map(c -> ToolsImplCommon.convertEntryToTool(c, config)).collect(Collectors.toList());
    }

    private List<io.openapi.model.Tool> entriesOrgGetList(String organization) {
        List<Tool> published = toolDAO.findPublishedByNamespace(organization);
        return published.stream().map(c -> ToolsImplCommon.convertEntryToTool(c, config)).collect(Collectors.toList());
    }

    @Override
    public Response workflowsOrgGet(String organization, SecurityContext securityContext) {
        return Response.ok(workflowOrgGetList(organization)).build();
    }

    @Override
    public Response entriesOrgGet(String organization, SecurityContext securityContext) {
        return Response.ok(entriesOrgGetList(organization)).build();
    }

    @Override
    public Response organizationsGet(SecurityContext securityContext) {
        List<String> organizations = new ArrayList<>();
        for (Entry c : getPublished()) {
            String org;
            if (c instanceof Workflow) {
                org = ((Workflow)c).getOrganization().toLowerCase();
            } else {
                org = ((Tool)c).getNamespace().toLowerCase();
            }
            if (!organizations.contains(org)) {
                organizations.add(org);
            }
        }
        return Response.ok(organizations).build();
    }

    @Override
    public Response toolsIndexGet(SecurityContext securityContext) {
        if (!config.getEsConfiguration().getHostname().isEmpty()) {
            List<Entry> published = getPublished();
            try {
                RestHighLevelClient client = ElasticSearchHelper.restHighLevelClient();
                // Delete previous indices
                deleteIndex(client, TOOLS_INDEX);
                deleteIndex(client, WORKFLOWS_INDEX);

                // Get mapping for tools index
                URL urlTools = Resources.getResource("queries/mapping_tool.json");
                String textTools = Resources.toString(urlTools, StandardCharsets.UTF_8);

                // Get mapping for workflows index
                URL urlWorkflows = Resources.getResource("queries/mapping_workflow.json");
                String textWorkflows = Resources.toString(urlWorkflows, StandardCharsets.UTF_8);

                // Create indices
                CreateIndexRequest toolsRequest = new CreateIndexRequest(TOOLS_INDEX);
                toolsRequest.source(textTools, XContentType.JSON);
                CreateIndexRequest workflowsRequest = new CreateIndexRequest(WORKFLOWS_INDEX);
                workflowsRequest.source(textWorkflows, XContentType.JSON);
                client.indices().create(toolsRequest, RequestOptions.DEFAULT);
                client.indices().create(workflowsRequest, RequestOptions.DEFAULT);

                // Populate index
                if (!published.isEmpty()) {
                    publicStateManager.bulkUpsert(published);
                }
            } catch (IOException e) {
                LOG.error("Could not create elastic search index", e);
                throw new CustomWebApplicationException("Search indexing failed", HttpStatus.SC_INTERNAL_SERVER_ERROR);
            }
            return Response.ok().entity(published.size()).build();
        }
        return Response.ok().entity(0).build();
    }

    @Override
    public Response toolsIndexSearch(String query, MultivaluedMap<String, String> queryParameters, SecurityContext securityContext) {
        if (!config.getEsConfiguration().getHostname().isEmpty()) {
            // Performing a search on the UI sends multiple POST requests. When the search term ("include" key in request payload) is large,
            // one of these POST requests will fail, but the others will continue to pass.
            if (query != null) {
                JSONObject json = new JSONObject(query);
                try {
                    String include = json.getJSONObject("aggs").getJSONObject("autocomplete").getJSONObject("terms").getString("include");
                    if (include.length() > SEARCH_TERM_LIMIT) {
                        throw new CustomWebApplicationException("Search request exceeds limit", HttpStatus.SC_REQUEST_TOO_LONG);
                    }

                } catch (JSONException ex) {
                    // The request bodies all look pretty different, so it's okay for the exception to get thrown.
                    LOG.debug("Couldn't parse search payload request.");
                }
            }

            try {
                RestClient restClient = ElasticSearchHelper.restClient();
                Map<String, String> parameters = new HashMap<>();
                // TODO: note that this is lossy if there are repeated parameters
                // but it looks like the elastic search http client classes don't handle it
                if (queryParameters != null) {
                    queryParameters.forEach((key, value) -> parameters.put(key, value.get(0)));
                }
                // This should be using the high-level Elasticsearch client instead
                Request request = new Request("GET", "/" + ALL_INDICES + "/_search");
                if (query != null) {
                    request.setJsonEntity(query);
                }
                request.addParameters(parameters);
                org.elasticsearch.client.Response get = restClient.performRequest(request);
                if (get.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    throw new CustomWebApplicationException("Could not search " + ALL_INDICES + "index",
                            HttpStatus.SC_INTERNAL_SERVER_ERROR);
                }
                return Response.ok().entity(get.getEntity().getContent()).build();
            } catch (ResponseException e) {
                // Only surface these codes to the user, everything else is not entirely obvious so returning 500 instead.
                int[] codesToResurface = {HttpStatus.SC_BAD_REQUEST};
                int statusCode = e.getResponse().getStatusLine().getStatusCode();
                LOG.error("Could not use Elasticsearch search", e);
                // Provide a minimal amount of error information in the browser console as outlined by
                // https://ucsc-cgl.atlassian.net/browse/SEAB-2128
                String reasonPhrase = e.getResponse().getStatusLine().getReasonPhrase();
                if (ArrayUtils.contains(codesToResurface, statusCode)) {
                    throw new CustomWebApplicationException(reasonPhrase, statusCode);
                } else {
                    throw new CustomWebApplicationException(reasonPhrase, HttpStatus.SC_INTERNAL_SERVER_ERROR);
                }
            } catch (IOException e2) {
                LOG.error("Could not use Elasticsearch search", e2);
                throw new CustomWebApplicationException("Search failed", HttpStatus.SC_INTERNAL_SERVER_ERROR);
            }
        }
        return Response.ok().entity(0).build();
    }

    @SuppressWarnings("checkstyle:parameternumber")
    @Override
    public Response setSourceFileMetadata(String type, String id, String versionId, String platform, String platformVersion, String relativePath, Boolean verified,
        String metadata) {

        ToolsApiServiceImpl impl = new ToolsApiServiceImpl();
        ToolsApiServiceImpl.ParsedRegistryID parsedID = null;
        try {
            parsedID = new ToolsApiServiceImpl.ParsedRegistryID(id);
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return BAD_DECODE_RESPONSE;
        }
        Entry<?, ?> entry = impl.getEntry(parsedID, Optional.empty());
        Optional<? extends Version<?>> versionOptional;

        if (entry instanceof Workflow) {
            Workflow workflow = (Workflow)entry;
            Set<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
            versionOptional = workflowVersions.stream().filter(workflowVersion -> workflowVersion.getName().equals(versionId)).findFirst();
        } else if (entry instanceof Tool) {
            Tool tool = (Tool)entry;
            Set<Tag> versions = tool.getWorkflowVersions();
            versionOptional = versions.stream().filter(tag -> tag.getName().equals(versionId)).findFirst();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (versionOptional.isPresent()) {
            Version<?> version = versionOptional.get();
            // so in this stream we need to standardize relative to the main descriptor
            Optional<SourceFile> correctSourceFile = impl
                .lookForFilePath(version.getSourceFiles(), relativePath, version.getWorkingDirectory());
            if (correctSourceFile.isPresent()) {
                SourceFile sourceFile = correctSourceFile.get();
                if (!(SourceFile.TEST_FILE_TYPES.contains(sourceFile.getType()))) {
                    throw new CustomWebApplicationException("File was not a test parameter file", HttpStatus.SC_BAD_REQUEST);
                }
                if (verified == null) {
                    sourceFile.getVerifiedBySource().remove(platform);
                } else {
                    SourceFile.VerificationInformation verificationInformation = new SourceFile.VerificationInformation();
                    verificationInformation.metadata = metadata;
                    verificationInformation.verified = verified;
                    verificationInformation.platformVersion = platformVersion;
                    sourceFile.getVerifiedBySource().put(platform, verificationInformation);
                }
                // denormalizes verification out to the version level for performance
                // not sure why the cast is needed
                version.updateVerified();
                return Response.ok().entity(sourceFile.getVerifiedBySource()).build();
            }
        }
        throw new CustomWebApplicationException("Could not submit verification information", HttpStatus.SC_BAD_REQUEST);
    }

    private void deleteIndex(RestHighLevelClient restClient, String index) {
        try {
            DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(index);
            restClient.indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
        } catch (Exception e) {
            LOG.warn("Could not delete previous elastic search " + index + " index, not an issue if this is cold start", e);
        }
    }
}
