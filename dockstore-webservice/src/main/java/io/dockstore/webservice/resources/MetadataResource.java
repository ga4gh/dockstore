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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.codahale.metrics.annotation.Timed;
import com.google.common.io.Resources;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.PipHelper;
import io.dockstore.common.Registry;
import io.dockstore.common.SourceControl;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.api.Config;
import io.dockstore.webservice.core.Collection;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Organization;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.database.RSSToolPath;
import io.dockstore.webservice.core.database.RSSWorkflowPath;
import io.dockstore.webservice.helpers.MetadataResourceHelper;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.helpers.statelisteners.RSSListener;
import io.dockstore.webservice.helpers.statelisteners.SitemapListener;
import io.dockstore.webservice.jdbi.BioWorkflowDAO;
import io.dockstore.webservice.jdbi.CollectionDAO;
import io.dockstore.webservice.jdbi.OrganizationDAO;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dockstore.webservice.languages.LanguageHandlerFactory;
import io.dockstore.webservice.resources.proposedGA4GH.ToolsApiExtendedServiceFactory;
import io.dockstore.webservice.resources.proposedGA4GH.ToolsExtendedApiService;
import io.dockstore.webservice.resources.rss.RSSEntry;
import io.dockstore.webservice.resources.rss.RSSFeed;
import io.dockstore.webservice.resources.rss.RSSHeader;
import io.dockstore.webservice.resources.rss.RSSWriter;
import io.dropwizard.hibernate.UnitOfWork;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import okhttp3.Cache;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.hibernate.SessionFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.webservice.helpers.statelisteners.RSSListener.RSS_KEY;
import static io.dockstore.webservice.helpers.statelisteners.SitemapListener.SITEMAP_KEY;

/**
 * @author dyuen
 */
@Path("/metadata")
@Api("metadata")
@Produces({MediaType.TEXT_HTML, MediaType.TEXT_XML})
@Tag(name = "metadata", description = ResourceConstants.METADATA)
public class MetadataResource {

    public static final int RSS_ENTRY_LIMIT = 50;
    private static final Logger LOG = LoggerFactory.getLogger(MetadataResource.class);

    private final ToolsExtendedApiService delegate = ToolsApiExtendedServiceFactory.getToolsExtendedApi();
    private final ToolDAO toolDAO;
    private final OrganizationDAO organizationDAO;
    private final CollectionDAO collectionDAO;
    private final BioWorkflowDAO bioWorkflowDAO;
    private final DockstoreWebserviceConfiguration config;
    private final SitemapListener sitemapListener;
    private final RSSListener rssListener;

    public MetadataResource(SessionFactory sessionFactory, DockstoreWebserviceConfiguration config) {
        this.toolDAO = new ToolDAO(sessionFactory);
        this.organizationDAO = new OrganizationDAO(sessionFactory);
        this.collectionDAO = new CollectionDAO(sessionFactory);
        this.config = config;
        this.bioWorkflowDAO = new BioWorkflowDAO(sessionFactory);
        this.sitemapListener = PublicStateManager.getInstance().getSitemapListener();
        this.rssListener = PublicStateManager.getInstance().getRSSListener();
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("sitemap")
    @Operation(summary = "List all available workflow, tool, organization, and collection paths.", description = "List all available workflow, tool, organization, and collection paths. Available means published for tools/workflows, and approved for organizations and their respective collections. NO authentication")
    @ApiOperation(value = "List all available workflow, tool, organization, and collection paths.", notes = "List all available workflow, tool, organization, and collection paths. Available means published for tools/workflows, and approved for organizations and their respective collections.")
    public String sitemap() {
        try {
            SortedSet<String> sitemap = sitemapListener.getCache().get(SITEMAP_KEY, this::getSitemap);
            return String.join(System.lineSeparator(), sitemap);
        } catch (ExecutionException e) {
            throw new CustomWebApplicationException("Sitemap cache problems", HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    public SortedSet<String> getSitemap() {
        SortedSet<String> urls = new TreeSet<>();
        urls.addAll(getToolPaths());
        urls.addAll(getBioWorkflowPaths());
        urls.addAll(getOrganizationAndCollectionPaths());
        return urls;
    }

    /**
     * Adds organization and collection URLs
     * //TODO needs to be more efficient via JPA query
     */
    private List<String> getOrganizationAndCollectionPaths() {
        List<String> urls = new ArrayList<>();
        List<Organization> organizations = organizationDAO.findAllApproved();
        organizations.forEach(organization -> {
            urls.add(createOrganizationURL(organization));
            List<Collection> collections = collectionDAO.findAllByOrg(organization.getId());
            collections.stream().map(collection -> createCollectionURL(collection, organization)).forEach(urls::add);
        });
        return urls;
    }

    private List<String> getToolPaths() {
        return toolDAO.findAllPublishedPaths().stream().map(toolPath -> createToolURL(toolPath.getTool())).collect(Collectors.toList());
    }

    private List<String> getBioWorkflowPaths() {
        return bioWorkflowDAO.findAllPublishedPaths().stream().map(workflowPath -> createWorkflowURL(workflowPath.getBioWorkflow())).collect(
                Collectors.toList());
    }

    private String createOrganizationURL(Organization organization) {
        return MetadataResourceHelper.createOrganizationURL(organization);
    }

    private String createCollectionURL(Collection collection, Organization organization) {
        return MetadataResourceHelper.createCollectionURL(collection, organization);
    }

    private String createWorkflowURL(Workflow workflow) {
        return MetadataResourceHelper.createWorkflowURL(workflow);
    }

    private String createToolURL(Tool tool) {
        return MetadataResourceHelper.createToolURL(tool);
    }

    @GET
    @Timed
    @UnitOfWork(readOnly = true)
    @Path("rss")
    @Produces(MediaType.TEXT_XML)
    @Operation(summary = "List all published tools and workflows in creation order", description = "List all published tools and workflows in creation order, NO authentication")
    @ApiOperation(value = "List all published tools and workflows in creation order.", notes = "NO authentication")
    public String rssFeed() {
        try {
            return rssListener.getCache().get(RSS_KEY, this::getRSS);
        } catch (ExecutionException e) {
            throw new CustomWebApplicationException("RSS cache problems", HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private String getRSS() {
        List<Tool> tools = toolDAO.findAllPublishedPathsOrderByDbupdatedate().stream().map(RSSToolPath::getTool).collect(Collectors.toList());
        List<Workflow> workflows = bioWorkflowDAO.findAllPublishedPathsOrderByDbupdatedate().stream().map(RSSWorkflowPath::getBioWorkflow).collect(
                Collectors.toList());

        List<Entry<?, ?>> dbEntries =  new ArrayList<>();
        dbEntries.addAll(tools);
        dbEntries.addAll(workflows);
        dbEntries.sort(Comparator.comparingLong(entry -> entry.getLastUpdated().getTime()));

        // TODO: after seeing if this works, make this more efficient than just returning everything
        RSSFeed feed = new RSSFeed();

        RSSHeader header = new RSSHeader();
        header.setCopyright("Copyright " + Year.now().getValue() + " OICR");
        header.setTitle("Dockstore");
        header.setDescription("Dockstore, developed by the Cancer Genome Collaboratory, is an open platform used by the GA4GH for sharing Docker-based tools described with either the Common Workflow Language (CWL) or the Workflow Description Language (WDL).");
        header.setLanguage("en");
        header.setLink("https://dockstore.org/");
        header.setPubDate(RSSFeed.formatDate(Calendar.getInstance()));

        feed.setHeader(header);

        List<RSSEntry> entries = new ArrayList<>();
        for (Entry<?, ?> dbEntry : dbEntries) {
            RSSEntry entry = new RSSEntry();
            if (dbEntry instanceof Workflow) {
                Workflow workflow = (Workflow)dbEntry;
                entry.setTitle(workflow.getWorkflowPath());
                String workflowURL = createWorkflowURL(workflow);
                entry.setGuid(workflowURL);
                entry.setLink(workflowURL);
            } else if (dbEntry instanceof Tool) {
                Tool tool = (Tool)dbEntry;
                entry.setTitle(tool.getPath());
                String toolURL = createToolURL(tool);
                entry.setGuid(toolURL);
                entry.setLink(toolURL);
            } else {
                throw new CustomWebApplicationException("Unknown data type unsupported for RSS feed.", HttpStatus.SC_INTERNAL_SERVER_ERROR);
            }
            final int arbitraryDescriptionLimit = 200;
            entry.setDescription(StringUtils.truncate(dbEntry.getDescription(), arbitraryDescriptionLimit));
            Calendar instance = Calendar.getInstance();
            instance.setTime(dbEntry.getLastUpdated());
            entry.setPubDate(RSSFeed.formatDate(instance));
            entries.add(entry);
        }
        feed.setEntries(entries);

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            RSSWriter.write(feed, byteArrayOutputStream);
            return byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new CustomWebApplicationException("Could not write RSS feed.", HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Produces({ "text/plain", "application/json" })
    @Path("/runner_dependencies")
    @Operation(summary = "Returns the file containing runner dependencies", description = "Returns the file containing runner dependencies, NO authentication")
    @ApiResponse(description = "The requirements.txt file", content = @Content(
        mediaType = "application/json",
        schema = @Schema(implementation = String.class)))
    @ApiOperation(value = "Returns the file containing runner dependencies.", response = String.class)
    public Response getRunnerDependencies(
            @Parameter(name = "client_version", description = "The Dockstore client version")
            @ApiParam(value = "The Dockstore client version") @QueryParam("client_version") String clientVersion,
            @Parameter(name = "python_version", description = "Python version, only relevant for the cwltool runner", in = ParameterIn.QUERY, schema = @Schema(defaultValue = "2"))
            @ApiParam(value = "Python version, only relevant for the cwltool runner") @DefaultValue("3") @QueryParam("python_version") String pythonVersion,
            @Parameter(name = "runner", description = "The tool runner", in = ParameterIn.QUERY, schema = @Schema(defaultValue = "cwltool", allowableValues = {"cwltool"}))
            @ApiParam(value = "The tool runner", allowableValues = "cwltool") @DefaultValue("cwltool") @QueryParam("runner") String runner,
            @Parameter(name = "output", description = "Response type", in = ParameterIn.QUERY, schema = @Schema(defaultValue = "text", allowableValues = {"json", "text"}))
            @ApiParam(value = "Response type", allowableValues = "json, text") @DefaultValue("text") @QueryParam("output") String output,
            @Context ContainerRequestContext containerRequestContext) {
        if (!("cwltool").equals(runner)) {
            return Response.noContent().build();
        }
        boolean unwrap = !("json").equals(output);
        String fileVersion = PipHelper.convertSemVerToAvailableVersion(clientVersion);
        try {
            String content = Resources.toString(this.getClass().getClassLoader()
                    .getResource("requirements/" + fileVersion + "/requirements" + (pythonVersion.startsWith("3") ? "3" : "") + ".txt"), StandardCharsets.UTF_8);
            Map<String, String> pipDepMap = PipHelper.convertPipRequirementsStringToMap(content);
            return Response.status(Response.Status.OK).type(unwrap ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON)
                    .entity(unwrap ? content : pipDepMap).build();
        } catch (IOException e) {
            throw new CustomWebApplicationException("Could not retrieve runner dependencies file: " + e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Timed
    @Path("/sourceControlList")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get the list of source controls supported on Dockstore", description = "Get the list of source controls supported on Dockstore, NO authentication")
    @ApiResponse(description = "List of source control repositories", content = @Content(
        mediaType = "application/json",
        array = @ArraySchema(schema = @Schema(implementation = SourceControl.SourceControlBean.class))))
    @ApiOperation(value = "Get the list of source controls supported on Dockstore.", notes = "NO authentication", response = SourceControl.SourceControlBean.class, responseContainer = "List")
    public List<SourceControl.SourceControlBean> getSourceControlList() {
        List<SourceControl.SourceControlBean> sourceControlList = new ArrayList<>();
        Arrays.asList(SourceControl.values()).forEach(sourceControl -> sourceControlList.add(new SourceControl.SourceControlBean(sourceControl)));
        return sourceControlList;
    }

    @GET
    @Timed
    @Path("/dockerRegistryList")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get the list of docker registries supported on Dockstore", description = "Get the list of docker registries supported on Dockstore, NO authentication")
    @ApiResponse(description = "List of Docker registries", content = @Content(
        mediaType = "application/json",
        array = @ArraySchema(schema = @Schema(implementation = Registry.RegistryBean.class))))
    @ApiOperation(nickname = "getDockerRegistries", value = "Get the list of docker registries supported on Dockstore.", notes = "NO authentication", response = Registry.RegistryBean.class, responseContainer = "List")
    public List<Registry.RegistryBean> getDockerRegistries() {
        List<Registry.RegistryBean> registryList = new ArrayList<>();
        Arrays.asList(Registry.values()).forEach(registry -> registryList.add(new Registry.RegistryBean(registry)));
        return registryList;
    }

    @GET
    @Timed
    @Path("/descriptorLanguageList")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get the list of descriptor languages supported on Dockstore", description = "Get the list of descriptor languages supported on Dockstore, NO authentication")
    @ApiResponse(description = "List of descriptor languages", content = @Content(
        mediaType = "application/json",
        array = @ArraySchema(schema = @Schema(implementation = DescriptorLanguage.DescriptorLanguageBean.class))))
    @ApiOperation(value = "Get the list of descriptor languages supported on Dockstore.", notes = "NO authentication", response = DescriptorLanguage.DescriptorLanguageBean.class, responseContainer = "List")
    public List<DescriptorLanguage.DescriptorLanguageBean> getDescriptorLanguages() {
        List<DescriptorLanguage.DescriptorLanguageBean> descriptorLanguageList = new ArrayList<>();
        Arrays.stream(DescriptorLanguage.values()).filter(lang ->
            // only include plugin languages that have installed plugins
            !lang.isPluginLanguage() || LanguageHandlerFactory.getPluginMap().containsKey(lang)).
            forEach(descriptorLanguage -> descriptorLanguageList.add(new DescriptorLanguage.DescriptorLanguageBean(descriptorLanguage)));
        return descriptorLanguageList;
    }

    @GET
    @Timed
    @Path("/okHttpCachePerformance")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get measures of cache performance", description = "Get measures of cache performance, NO authentication")
    @ApiResponse(description = "Cache performance information", content = @Content(mediaType = "application/json"))
    @ApiOperation(value = "Get measures of cache performance.", notes = "NO authentication", response = Map.class)
    public Map<String, String> getCachePerformance() {
        Cache cache = DockstoreWebserviceApplication.getCache(null);
        Map<String, String> results = new HashMap<>();
        results.put("requestCount", String.valueOf(cache.requestCount()));
        results.put("networkCount", String.valueOf(cache.networkCount()));
        results.put("hitCount", String.valueOf(cache.hitCount()));
        results.put("maxSize", cache.maxSize() + " bytes");

        try {
            results.put("size", cache.size() + " bytes");
        } catch (IOException e) {
            /* do nothing if we cannot report size */
            LOG.warn("unable to determine cache size, may not have initialized yet");
        }
        return results;
    }

    @GET
    @Timed
    @UnitOfWork
    @Path("/elasticSearch")
    @Operation(summary = "Successful response if elastic search is up and running", description = "Successful response if elastic search is up and running, NO authentication")
    @ApiOperation(value = "Successful response if elastic search is up and running.", notes = "NO authentication")
    public Response checkElasticSearch() {
        Response elasticSearchResponse;
        try {
            elasticSearchResponse = delegate.toolsIndexSearch(null, null, null);
            String result = IOUtils.toString((InputStream)(elasticSearchResponse.getEntity()), StandardCharsets.UTF_8);
            JSONObject jsonObj = new JSONObject(result);
            JSONObject hitsHolder = jsonObj.getJSONObject("hits");
            JSONArray hitsArray = hitsHolder.getJSONArray("hits");
            if (hitsArray.toList().isEmpty()) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()).build();
            }
        } catch (Exception ex) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()).build();
        }
        return Response.ok().build();
    }

    @GET
    @Path("/config.json")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Configuration for UI clients of the API", description = "Configuration, NO authentication")
    @ApiOperation(value = "Configuration for UI clients of the API", notes = "NO authentication")
    public Config getConfig() {
        try {
            return Config.fromWebConfig(this.config);
        } catch (InvocationTargetException | IllegalAccessException e) {
            LOG.error("Error generating config response", e);
            throw new CustomWebApplicationException("Error retrieving config information", HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

}
