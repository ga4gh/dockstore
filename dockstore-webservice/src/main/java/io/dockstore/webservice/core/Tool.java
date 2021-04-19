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

package io.dockstore.webservice.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.OrderBy;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.UniqueConstraint;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.EntryType;
import io.dockstore.common.Registry;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;
import org.hibernate.annotations.Check;

/**
 * This describes one tool in the dockstore, extending entry with fields necessary to describe bioinformatics tools.
 * <p>
 * Logically, this currently means one tuple of registry (either quay or docker hub), organization, image name, and toolname which can be
 * associated with CWL and Dockerfile documents.
 *
 * @author xliu
 * @author dyuen
 */
@ApiModel(value = "DockstoreTool", description =
        "This describes one entry in the dockstore. Logically, this currently means one tuple of registry (either quay or docker hub), organization, image name, and toolname which can be\n"
                + " * associated with CWL and Dockerfile documents")
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "ukbq5vy17y4ocaist3d3r3imcus", columnNames = { "registry", "namespace", "name", "toolname" }))
@NamedQueries({
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.getByAlias", query = "SELECT e from Tool e JOIN e.aliases a WHERE KEY(a) IN :alias"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByNameAndNamespaceAndRegistry", query = "SELECT c FROM Tool c WHERE c.name = :name AND c.namespace = :namespace AND c.registry = :registry"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedById", query = "SELECT c FROM Tool c WHERE c.id = :id AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.countAllPublished", query = "SELECT COUNT(c.id)" + Tool.PUBLISHED_QUERY),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findAllPublished", query = "SELECT c" + Tool.PUBLISHED_QUERY + "ORDER BY size(c.starredUsers) DESC"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findAllPublishedPaths", query = "SELECT new io.dockstore.webservice.core.database.ToolPath(c.registry, c.namespace, c.name, c.toolname)" + Tool.PUBLISHED_QUERY),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findAllPublishedPathsOrderByDbupdatedate", query = "SELECT new io.dockstore.webservice.core.database.RSSToolPath(c.registry, c.namespace, c.name, c.toolname, c.lastUpdated, c.description)" + Tool.PUBLISHED_QUERY + "and c.dbUpdateDate is not null ORDER BY c.dbUpdateDate desc"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByMode", query = "SELECT c FROM Tool c WHERE c.mode = :mode"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedByNamespace", query = "SELECT c FROM Tool c WHERE lower(c.namespace) = lower(:namespace) AND c.isPublished = true ORDER BY gitUrl"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByPath", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedByPath", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByToolPath", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.toolname = :toolname"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedByToolPath", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.toolname = :toolname AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByToolPathNullToolName", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.toolname IS NULL"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findPublishedByToolPathNullToolName", query = "SELECT c FROM Tool c WHERE c.registry = :registry AND c.namespace = :namespace AND c.name = :name AND c.toolname IS NULL AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.getEntryLiteByUserId", query = "SELECT new io.dockstore.webservice.core.database.EntryLite$EntryLiteTool(t.registry, t.namespace, t.name, t.toolname, t.dbUpdateDate as entryUpdated, MAX(v.dbUpdateDate) as versionUpdated) "
                + "FROM Tool t LEFT JOIN t.workflowVersions v "
                + "WHERE t.id in (SELECT ue.id FROM User u INNER JOIN u.entries ue where u.id = :userId) "
                + "GROUP BY t.registry, t.namespace, t.name, t.toolname, t.dbUpdateDate"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByUserRegistryNamespace", query = "SELECT t from Tool t WHERE t.id in (SELECT ue.id FROM User u INNER JOIN u.entries ue where u.id = :userId) AND t.registry = :registry AND t.namespace = :namespace"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.findByUserRegistryNamespaceRepository", query = "SELECT t from Tool t WHERE t.id in (SELECT ue.id FROM User u INNER JOIN u.entries ue where u.id = :userId) AND t.registry = :registry AND t.namespace = :namespace AND t.name = :repository"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.getEntriesByUserId", query = "SELECT t FROM Tool t WHERE t.id in (SELECT ue.id FROM User u INNER JOIN u.entries ue where u.id = :userId)"),
        @NamedQuery(name = "io.dockstore.webservice.core.Tool.getPublishedEntriesByUserId", query = "SELECT t FROM Tool t WHERE t.isPublished = true AND t.id in (SELECT ue.id FROM User u INNER JOIN u.entries ue where u.id = :userId)")
})


@Check(constraints = "(toolname NOT LIKE '\\_%')")
@SuppressWarnings("checkstyle:magicnumber")
@Schema(name = "DockstoreTool")
public class Tool extends Entry<Tool, Tag> {

    static final String PUBLISHED_QUERY = " FROM Tool c WHERE c.isPublished = true ";

    @Column(nullable = false, columnDefinition = "Text default 'AUTO_DETECT_QUAY_TAGS_AUTOMATED_BUILDS'")
    @Enumerated(EnumType.STRING)
    @ApiModelProperty(value = "This indicates what mode this is in which informs how we do things like refresh, dockstore specific", required = true, position = 13)
    private ToolMode mode = ToolMode.AUTO_DETECT_QUAY_TAGS_AUTOMATED_BUILDS;

    @Column(nullable = false)
    @ApiModelProperty(value = "This is the name of the container, required: GA4GH", required = true, position = 14)
    private String name;

    @Column
    @JsonProperty("tool_maintainer_email")
    @ApiModelProperty(value = "The email address of the tool maintainer. Required for private repositories", position = 20)
    private String toolMaintainerEmail = "";

    @Column(columnDefinition = "boolean default false")
    @JsonProperty("private_access")
    @ApiModelProperty(value = "Is the docker image private or not.", required = true, position = 21)
    private boolean privateAccess = false;

    @Column(columnDefinition = "Text")
    @ApiModelProperty(value = "This is the tool name of the container, when not-present this will function just like 0.1 dockstore"
            + "when present, this can be used to distinguish between two containers based on the same image, but associated with different "
            + "CWL and Dockerfile documents. i.e. two containers with the same registry+namespace+name but different toolnames "
            + "will be two different entries in the dockstore registry/namespace/name/tool, different options to edit tags, and "
            + "only the same insofar as they would \"docker pull\" the same image, required: GA4GH", position = 22)
    private String toolname;

    @Column
    @ApiModelProperty(value = "This is a docker namespace for the container, required: GA4GH", required = true, position = 23)
    private String namespace;

    @Column(nullable = false)
    @ApiModelProperty(value = "This is a specific docker provider like quay.io or dockerhub or n/a?, required: GA4GH", required = true, position = 24)
    private String registry;

    @Column
    @ApiModelProperty(value = "Implementation specific timestamp for last built. For automated builds: When refresh is hit, the last time the tool was built gets stored here. "
            + "If tool was never built on quay.io, then last build will be null. N/A for hosted/manual path tools", position = 25, dataType = "long")
    private Date lastBuild;

    @Column(nullable = false, columnDefinition = "varchar default ''")
    @Convert(converter = DescriptorTypeConverter.class)
    @ApiModelProperty(position = 28, accessMode = ApiModelProperty.AccessMode.READ_ONLY)
    private List<String> descriptorType = new ArrayList<>();

    @Column
    @Size(max = 256)
    @ApiModelProperty(value = "This is a link to a forum or discussion board")
    private String forumUrl;

    @OneToMany(fetch = FetchType.EAGER, orphanRemoval = true, targetEntity = Version.class, mappedBy = "parent")
    @ApiModelProperty(value = "Implementation specific tracking of valid build tags for the docker container", position = 26)
    @JsonAlias({ "tags", "workflowVersions"})
    @OrderBy("id")
    @Cascade(CascadeType.DETACH)
    @BatchSize(size = 25)
    private final SortedSet<Tag> workflowVersions;

    @JsonIgnore
    @OneToOne(targetEntity = Tag.class, fetch = FetchType.LAZY)
    @JoinColumn(name = "actualDefaultVersion", referencedColumnName = "id", unique = true)
    private Tag actualDefaultVersion;

    @Transient
    @JsonProperty
    private Set<Tag> tags = null;

    public Tool() {
        workflowVersions = new TreeSet<>();
    }

    public Tool(long id, String name) {
        super(id);
        // this.userId = userId;
        this.name = name;
        workflowVersions = new TreeSet<>();
    }

    @Override
    public void setActualDefaultVersion(Tag version) {
        this.actualDefaultVersion = version;
    }

    @Override
    public Tag getActualDefaultVersion() {
        return this.actualDefaultVersion;
    }

    @JsonProperty
    @Override
    public String getGitUrl() {
        if (mode == ToolMode.HOSTED) {
            // for a dockstore hosted tool, fake a git url. Used by the UI
            return "git@dockstore.org:" + this.getPath()  + ".git";
        }
        return super.getGitUrl();
    }

    @Override
    public String getEntryPath() {
        return this.getToolPath();
    }

    public EntryType getEntryType() {
        return EntryType.TOOL;
    }

    // compromise: this sucks, but setting the json property to tags allows for backwards compatibility of existing clients
    // the method name being standardized allows for simpler coding going forward
    @Override
    public Set<Tag> getWorkflowVersions() {
        return workflowVersions;
    }

    // TODO: remove when all clients are on 1.7.0
    @Deprecated
    public Set<Tag> getTags() {
        return tags;
    }

    // TODO: remove when all clients are on 1.7.0
    @Deprecated
    public void setTags(Set<Tag> tags) {
        this.tags = tags;
    }

    /**
     * Used during refresh to update tools
     *
     * @param tool
     */
    public void update(Tool tool) {
        super.update(tool);
        this.setDescription(tool.getDescription());
        lastBuild = tool.getLastBuild();
        this.toolMaintainerEmail = tool.getToolMaintainerEmail();
        this.privateAccess = tool.isPrivateAccess();
    }

    @JsonProperty
    public String getName() {
        return name;
    }

    /**
     * @param name the repo name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty
    public String getNamespace() {
        return namespace;
    }

    /**
     * @param namespace the repo name to set
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    @JsonProperty("registry_string")
    public String getRegistry() {
        return registry;
    }

    public void setRegistry(String registry) {
        this.registry = registry;
    }

    @ApiModelProperty(position = 27)
    public String getPath() {
        return registry + '/' + namespace + '/' + name;
    }

    @JsonProperty
    public Date getLastBuild() {
        return lastBuild;
    }

    public void setLastBuild(Date lastBuild) {
        this.lastBuild = lastBuild;
    }

    @JsonProperty
    public ToolMode getMode() {
        return mode;
    }

    @Override
    public boolean isHosted() {
        return getMode().equals(ToolMode.HOSTED);
    }

    public void setMode(ToolMode mode) {
        this.mode = mode;
    }

    public List<String> getDescriptorType() {
        return this.descriptorType;
    }

    public void setDescriptorType(final List<String> descriptorType) {
        this.descriptorType = descriptorType;
    }

    @JsonProperty("default_dockerfile_path")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the Dockerfile, required: GA4GH", required = true, position = 15)
    public String getDefaultDockerfilePath() {
        return getDefaultPaths().getOrDefault(DescriptorLanguage.FileType.DOCKERFILE, "/Dockerfile");
    }

    public void setDefaultDockerfilePath(String defaultDockerfilePath) {
        getDefaultPaths().put(DescriptorLanguage.FileType.DOCKERFILE, defaultDockerfilePath);
    }

    @JsonProperty("default_cwl_path")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the CWL document, required: GA4GH", required = true, position = 16)
    public String getDefaultCwlPath() {
        return getDefaultPaths().getOrDefault(DescriptorLanguage.FileType.DOCKSTORE_CWL, "/Dockstore.cwl");
    }

    public void setDefaultCwlPath(String defaultCwlPath) {
        getDefaultPaths().put(DescriptorLanguage.FileType.DOCKSTORE_CWL, defaultCwlPath);
    }

    @JsonProperty("default_wdl_path")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the WDL document", required = true, position = 17)
    public String getDefaultWdlPath() {
        return getDefaultPaths().getOrDefault(DescriptorLanguage.FileType.DOCKSTORE_WDL, "/Dockstore.wdl");
    }

    public void setDefaultWdlPath(String defaultWdlPath) {
        getDefaultPaths().put(DescriptorLanguage.FileType.DOCKSTORE_WDL, defaultWdlPath);
    }

    @JsonProperty
    public String getForumUrl() {
        return forumUrl;
    }
    public void setForumUrl(String forumUrl) {
        this.forumUrl = forumUrl;
    }

    @JsonProperty
    public String getToolname() {
        return toolname;
    }

    public void setToolname(String toolname) {
        this.toolname = toolname;
    }

    @JsonProperty("tool_path")
    @ApiModelProperty(position = 29)
    public String getToolPath() {
        return getPath() + (toolname == null || toolname.isEmpty() ? "" : '/' + toolname);
    }

    /**
     * We cannot only use an enum because Custom Docker Registry Path for Seven Bridges, Amazon ECR, and etc requires a string property.
     * We cannot only use the string because in many situations, it's easier to use an enum
     * @return the registry as an enum
     */
    @Enumerated(EnumType.STRING)
    @JsonProperty("registry")
    @ApiModelProperty(position = 30)
    public Registry getRegistryProvider() {
        if (this.registry == null) {
            return null;
        }
        for (Registry r : Registry.values()) {
            if (r.getDockerPath() != null && r.getDockerPath().equals(this.registry)) {
                return r;
            }
        }

        // Deal with registries with custom registry paths
        if (this.registry.matches("^[a-zA-Z0-9]+\\.dkr\\.ecr\\.[a-zA-Z0-9]+\\.amazonaws\\.com")) {
            return Registry.AMAZON_ECR;
        } else if (this.registry.matches("^([a-zA-Z0-9]+-)?images\\.sbgenomics\\.com")) {
            return Registry.SEVEN_BRIDGES;
        } else {
            return null;
        }
    }

    public void setRegistryProvider(Registry registryThing) {
        switch (registryThing) {
        case GITLAB:
        case QUAY_IO:
        case DOCKER_HUB:
            this.setRegistry(registryThing.getDockerPath());
            break;
        case SEVEN_BRIDGES:
        case AMAZON_ECR:
            break;
        default:
            break;
        }

    }

    public void setCustomerDockerRegistryPath(String newCustomDockerRegistryString) {
        if (newCustomDockerRegistryString != null) {
            this.setRegistry(newCustomDockerRegistryString);
        }
    }

    public Event.Builder getEventBuilder() {
        return new Event.Builder().withTool(this);
    }

    @JsonProperty("custom_docker_registry_path")
    public String getCustomDockerRegistryPath() {
        return this.registry;
    }

    public String getToolMaintainerEmail() {
        return toolMaintainerEmail;
    }

    public void setToolMaintainerEmail(String toolMaintainerEmail) {
        this.toolMaintainerEmail = toolMaintainerEmail;
    }

    public boolean isPrivateAccess() {
        return privateAccess;
    }

    public void setPrivateAccess(boolean privateAccess) {
        this.privateAccess = privateAccess;
    }

    @JsonProperty("defaultWDLTestParameterFile")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the WDL test parameter file", required = true, position = 19)
    public String getDefaultTestWdlParameterFile() {
        return getDefaultPaths().getOrDefault(DescriptorLanguage.FileType.WDL_TEST_JSON, "/test.json");
    }

    public void setDefaultTestWdlParameterFile(String defaultTestWdlParameterFile) {
        getDefaultPaths().put(DescriptorLanguage.FileType.WDL_TEST_JSON, defaultTestWdlParameterFile);
    }

    @JsonProperty("defaultCWLTestParameterFile")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the CWL test parameter file", required = true, position = 18)
    public String getDefaultTestCwlParameterFile() {
        return getDefaultPaths().getOrDefault(DescriptorLanguage.FileType.CWL_TEST_JSON, "/test.json");
    }

    public void setDefaultTestCwlParameterFile(String defaultTestCwlParameterFile) {
        getDefaultPaths().put(DescriptorLanguage.FileType.CWL_TEST_JSON, defaultTestCwlParameterFile);
    }

    public List<String> calculateDescriptorType() {
        Set<DescriptorLanguage.FileType> set = this.getWorkflowVersions().stream().flatMap(tag -> tag.getSourceFiles().stream()).map(SourceFile::getType).collect(
                Collectors.toSet());
        return Arrays.stream(DescriptorLanguage.values()).filter(lang -> !(lang.toString().equals("cwl") || lang.toString().equals("wdl"))).filter(lang -> set.contains(lang.getFileType()))
                .map(lang -> lang.toString()).distinct().collect(Collectors.toList());
    }
}
