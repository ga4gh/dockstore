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

import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.persistence.AttributeConverter;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.NamedAttributeNode;
import javax.persistence.NamedEntityGraph;
import javax.persistence.NamedEntityGraphs;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.NamedSubgraph;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.OrderBy;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.DescriptorLanguageSubclass;
import io.dockstore.common.SourceControl;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;
import org.hibernate.annotations.Check;

/**
 * This describes one workflow in the dockstore, extending Entry with the fields necessary to describe workflows.
 *
 * @author dyuen
 */
@ApiModel(value = "Workflow", description = "This describes one workflow in the dockstore", subTypes = {BioWorkflow.class, Service.class}, discriminator = "type")

@Entity
// this is crazy, but even though this is an abstract class it looks like JPA dies without this dummy value
@Table(name = "foo")
@NamedQueries({
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.getByAlias", query = "SELECT e from Workflow e JOIN e.aliases a WHERE KEY(a) IN :alias"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findPublishedById", query = "SELECT c FROM Workflow c WHERE c.id = :id AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.countAllPublished", query = "SELECT COUNT(c.id)" + Workflow.PUBLISHED_QUERY),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findAllPublished", query = "SELECT c" + Workflow.PUBLISHED_QUERY + "ORDER BY size(c.starredUsers) DESC"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findByPath", query = "SELECT c FROM Workflow c WHERE c.sourceControl = :sourcecontrol AND c.organization = :organization AND c.repository = :repository"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findPublishedByPath", query = "SELECT c FROM Workflow c WHERE c.sourceControl = :sourcecontrol AND c.organization = :organization AND c.repository = :repository AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findByWorkflowPath", query = "SELECT c FROM Workflow c WHERE c.sourceControl = :sourcecontrol AND c.organization = :organization AND c.repository = :repository AND c.workflowName = :workflowname"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findPublishedByWorkflowPath", query = "SELECT c FROM Workflow c WHERE c.sourceControl = :sourcecontrol AND c.organization = :organization AND c.repository = :repository AND c.workflowName = :workflowname AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findByWorkflowPathNullWorkflowName", query = "SELECT c FROM Workflow c WHERE c.sourceControl = :sourcecontrol AND c.organization = :organization AND c.repository = :repository AND c.workflowName IS NULL"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findPublishedByWorkflowPathNullWorkflowName", query = "SELECT c FROM Workflow c WHERE c.sourceControl = :sourcecontrol AND c.organization = :organization AND c.repository = :repository AND c.workflowName IS NULL AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findByGitUrl", query = "SELECT c FROM Workflow c WHERE c.gitUrl = :gitUrl ORDER BY gitUrl"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findPublishedByOrganization", query = "SELECT c FROM Workflow c WHERE lower(c.organization) = lower(:organization) AND c.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findByOrganization", query = "SELECT c FROM Workflow c WHERE lower(c.organization) = lower(:organization) AND c.sourceControl = :sourceControl"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findWorkflowByWorkflowVersionId", query = "SELECT c FROM Workflow c, Version v WHERE v.id = :workflowVersionId AND c.id = v.parent"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.getEntriesByUserId", query = "SELECT w FROM Workflow w WHERE w.id in (SELECT ue.id FROM User u INNER JOIN u.entries ue where u.id = :userId)"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.getPublishedEntriesByUserId", query = "SELECT w FROM Workflow w WHERE w.isPublished = true AND w.id in (SELECT ue.id FROM User u INNER JOIN u.entries ue where u.id = :userId)"),
        @NamedQuery(name = "io.dockstore.webservice.core.Workflow.findByIds", query = "select w from Workflow w where w.id in :ids")
})
@NamedEntityGraphs({
        @NamedEntityGraph(name = "io.dockstore.webservice.core.Workflow.trs", attributeNodes = {
                @NamedAttributeNode(value = "workflowVersions", subgraph = "trs.workflowVersions"),
                @NamedAttributeNode("labels"),
                @NamedAttributeNode("aliases"),
                @NamedAttributeNode("starredUsers"),
                @NamedAttributeNode("defaultPaths")
        }, subgraphs = {
                @NamedSubgraph (name = "trs.workflowVersions", attributeNodes = {
                        @NamedAttributeNode("inputFileFormats"),
                        @NamedAttributeNode("outputFileFormats"),
                        @NamedAttributeNode("versionMetadata")
                })
        })
})

@Check(constraints = " ((ischecker IS TRUE) or (ischecker IS FALSE and workflowname NOT LIKE '\\_%'))")
@JsonPropertyOrder("descriptorType")
@SuppressWarnings("checkstyle:magicnumber")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({ @JsonSubTypes.Type(value = BioWorkflow.class, name = "BioWorkflow"),
    @JsonSubTypes.Type(value = Service.class, name = "Service") })
public abstract class Workflow extends Entry<Workflow, WorkflowVersion> {

    static final String PUBLISHED_QUERY = " FROM Workflow c WHERE c.isPublished = true ";

    @Column(nullable = false, columnDefinition = "Text default 'STUB'")
    @Enumerated(EnumType.STRING)
    @ApiModelProperty(value = "This indicates what mode this is in which informs how we do things like refresh, dockstore specific", required = true, position = 13)
    private WorkflowMode mode = WorkflowMode.STUB;

    @Column(columnDefinition = "text")
    @ApiModelProperty(value = "This is the name of the workflow, not needed when only one workflow in a repo", position = 14)
    private String workflowName;

    @Column(nullable = false)
    @ApiModelProperty(value = "This is a git organization for the workflow", required = true, position = 15)
    private String organization;

    @Column(nullable = false)
    @ApiModelProperty(value = "This is a git repository name", required = true, position = 16)
    private String repository;

    @Column(nullable = false, columnDefinition = "text")
    @ApiModelProperty(value = "This is a specific source control provider like github or bitbucket or n/a?, required: GA4GH", required = true, position = 17, dataType = "string")
    @Convert(converter = SourceControlConverter.class)
    private SourceControl sourceControl;

    // DOCKSTORE-2428 - demo how to add new workflow language
    // this one is annoying since the codegen doesn't seem to pick up @JsonValue in the DescriptorLanguage enum
    @Column(nullable = false)
    @Convert(converter = DescriptorLanguageConverter.class)
    @ApiModelProperty(value = "This is a descriptor type for the workflow, by default either CWL, WDL, NFL, or gxformat2 (Defaults to CWL).", required = true, position = 18, allowableValues = "CWL, WDL, NFL, gxformat2, service")
    private DescriptorLanguage descriptorType;

    // TODO: Change this to LAZY, this is the source of all our problems
    @Column(nullable = false, columnDefinition = "varchar(255) default 'n/a'")
    @Convert(converter = DescriptorLanguageSubclassConverter.class)
    @ApiModelProperty(value = "This is a descriptor type subclass for the workflow. Currently it is only used for services.", required = true, position = 22)
    private DescriptorLanguageSubclass descriptorTypeSubclass = DescriptorLanguageSubclass.NOT_APPLICABLE;

    @OneToMany(fetch = FetchType.EAGER, orphanRemoval = true, targetEntity = Version.class, mappedBy = "parent")
    @ApiModelProperty(value = "Implementation specific tracking of valid build workflowVersions for the docker container", position = 21)
    @OrderBy("id")
    @Cascade({ CascadeType.DETACH, CascadeType.SAVE_UPDATE })
    private final SortedSet<WorkflowVersion> workflowVersions;

    @JsonIgnore
    @OneToOne(targetEntity = WorkflowVersion.class, fetch = FetchType.LAZY)
    @JoinColumn(name = "actualDefaultVersion", referencedColumnName = "id", unique = true)
    private WorkflowVersion actualDefaultVersion;

    protected Workflow() {
        workflowVersions = new TreeSet<>();
    }

    protected Workflow(long id, String workflowName) {
        super(id);
        // this.userId = userId;
        this.workflowName = workflowName;
        workflowVersions = new TreeSet<>();
    }

    @JsonProperty
    @Override
    public String getGitUrl() {
        if (mode == WorkflowMode.HOSTED) {
            // for a dockstore hosted workflow, fake a git url. Used by the UI
            return "git@dockstore.org:workflows/" + this.getWorkflowPath()  + ".git";
        }
        return super.getGitUrl();
    }

    @Override
    public String getEntryPath() {
        return this.getWorkflowPath();
    }

    public abstract Entry getParentEntry();

    public abstract void setParentEntry(Entry parentEntry);

    /**
     * Copies some of the attributes of the source workflow to the target workflow
     * There are two of these which seems redundant.
     *
     * @param targetWorkflow workflow to update from this
     * @deprecated seems to overlap with {@link #update(Workflow)} , it is not clear why both exist
     */
    @Deprecated
    public void copyWorkflow(Workflow targetWorkflow) {
        targetWorkflow.setIsPublished(getIsPublished());
        targetWorkflow.setWorkflowName(getWorkflowName());
        targetWorkflow.setAuthor(getAuthor());
        targetWorkflow.setEmail(getEmail());
        targetWorkflow.setDescription(getDescription());
        targetWorkflow.setLastModified(getLastModifiedDate());
        targetWorkflow.setOrganization(getOrganization());
        targetWorkflow.setRepository(getRepository());
        targetWorkflow.setGitUrl(getGitUrl());
        targetWorkflow.setDescriptorType(getDescriptorType());
        targetWorkflow.setDescriptorTypeSubclass(getDescriptorTypeSubclass());
        targetWorkflow.setActualDefaultVersion(this.getActualDefaultVersion());
        targetWorkflow.setDefaultTestParameterFilePath(getDefaultTestParameterFilePath());
        targetWorkflow.setCheckerWorkflow(getCheckerWorkflow());
        targetWorkflow.setIsChecker(isIsChecker());
        targetWorkflow.setConceptDoi(getConceptDoi());
        targetWorkflow.setMode(getMode());
    }

    @Override
    public void setActualDefaultVersion(WorkflowVersion version) {
        this.actualDefaultVersion = version;
    }

    @Override
    public WorkflowVersion getActualDefaultVersion() {
        return this.actualDefaultVersion;
    }

    @JsonProperty
    public WorkflowMode getMode() {
        return mode;
    }

    @Override
    public boolean isHosted() {
        return getMode().equals(WorkflowMode.HOSTED);
    }

    public void setMode(WorkflowMode mode) {
        this.mode = mode;
    }

    @JsonProperty
    public String getWorkflowName() {
        return workflowName;
    }

    @Override
    public Set<WorkflowVersion> getWorkflowVersions() {
        return workflowVersions;
    }

    /**
     * @param workflowName the repo name to set
     */
    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    // Add for new descriptor types
    @JsonProperty("workflow_path")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the primary descriptor document", required = true, position = 19)
    public String getDefaultWorkflowPath() {
        return getDefaultPaths().getOrDefault(this.getDescriptorType().getFileType(), "/Dockstore.cwl");
    }

    //TODO: odd side effect, this means that if the descriptor language is set wrong, we will get or set the wrong the default paths
    public void setDefaultWorkflowPath(String defaultWorkflowPath) {
        getDefaultPaths().put(this.getDescriptorType().getFileType(), defaultWorkflowPath);
    }

    @JsonProperty
    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }

    @JsonProperty
    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    @JsonProperty("full_workflow_path")
    @ApiModelProperty(position = 24)
    public String getWorkflowPath() {
        return getPath() + (workflowName == null || "".equals(workflowName) ? "" : '/' + workflowName);
    }

    @ApiModelProperty(position = 25)
    public String getPath() {
        return sourceControl.toString() + '/' + organization + '/' + repository;
    }

    /**
     * @return
     */
    @JsonProperty("source_control_provider")
    @ApiModelProperty(position = 26)
    public String getSourceControlProvider() {
        return this.sourceControl.name();
    }

    public void setDescriptorType(DescriptorLanguage descriptorType) {
        this.descriptorType = descriptorType;
    }

    public DescriptorLanguage getDescriptorType() {
        // due to DB constraints, this should only come into play with newly created, non-persisted Workflows
        return Objects.requireNonNullElse(this.descriptorType, DescriptorLanguage.CWL);
    }

    public DescriptorLanguageSubclass getDescriptorTypeSubclass() {
        return descriptorTypeSubclass;
    }

    public void setDescriptorTypeSubclass(final DescriptorLanguageSubclass descriptorTypeSubclass) {
        this.descriptorTypeSubclass = descriptorTypeSubclass;
    }

    @JsonIgnore
    public DescriptorLanguage.FileType getFileType() {
        return this.getDescriptorType().getFileType();
    }

    @JsonIgnore
    public DescriptorLanguage.FileType getTestParameterType() {
        return this.getDescriptorType().getTestParamType();
    }

    @JsonProperty("defaultTestParameterFilePath")
    @ApiModelProperty(value = "This indicates for the associated git repository, the default path to the test parameter file", required = true, position = 20)
    public String getDefaultTestParameterFilePath() {
        return getDefaultPaths().getOrDefault(this.getDescriptorType().getTestParamType(), "/test.json");
    }

    public void setDefaultTestParameterFilePath(String defaultTestParameterFilePath) {
        getDefaultPaths().put(this.getDescriptorType().getTestParamType(), defaultTestParameterFilePath);
    }
    public SourceControl getSourceControl() {
        return sourceControl;
    }

    public void setSourceControl(SourceControl sourceControl) {
        this.sourceControl = sourceControl;
    }

    public abstract boolean isIsChecker();

    public abstract void setIsChecker(boolean isChecker);

    public static class DescriptorLanguageConverter implements AttributeConverter<DescriptorLanguage, String> {

        @Override
        public String convertToDatabaseColumn(DescriptorLanguage attribute) {
            return attribute.getLowerShortName();
        }

        @Override
        public DescriptorLanguage convertToEntityAttribute(String dbData) {
            return DescriptorLanguage.convertShortStringToEnum(dbData);
        }
    }

    public static class DescriptorLanguageSubclassConverter implements AttributeConverter<DescriptorLanguageSubclass, String> {

        @Override
        public String convertToDatabaseColumn(DescriptorLanguageSubclass attribute) {
            return attribute.getShortName();
        }

        @Override
        public DescriptorLanguageSubclass convertToEntityAttribute(String dbData) {
            return DescriptorLanguageSubclass.convertShortNameStringToEnum(dbData);
        }
    }
}
