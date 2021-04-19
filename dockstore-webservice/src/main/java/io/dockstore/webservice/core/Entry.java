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

import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.MapKeyColumn;
import javax.persistence.MapKeyEnumerated;
import javax.persistence.NamedNativeQueries;
import javax.persistence.NamedNativeQuery;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToOne;
import javax.persistence.OrderBy;
import javax.persistence.SequenceGenerator;
import javax.persistence.UniqueConstraint;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.EntryType;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.helpers.EntryStarredSerializer;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.http.HttpStatus;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Base class for all entries in the dockstore
 *
 * @author dyuen
 */
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@SuppressWarnings("checkstyle:magicnumber")

@NamedQueries({
    @NamedQuery(name = "Entry.getGenericEntryById", query = "SELECT e from Entry e WHERE :id = e.id"),
        @NamedQuery(name = "Entry.getGenericEntryByAlias", query = "SELECT e from Entry e JOIN e.aliases a WHERE KEY(a) IN :alias"),
        @NamedQuery(name = "io.dockstore.webservice.core.Entry.findCollectionsByEntryId", query = "select new io.dockstore.webservice.core.CollectionOrganization(col.id, col.name, col.displayName, organization.id, organization.name, organization.displayName) from Collection col join col.entries as entry join col.organization as organization where entry.entry.id = :entryId and organization.status = 'APPROVED'"),
        @NamedQuery(name = "Entry.getCollectionWorkflows", query = "SELECT new io.dockstore.webservice.core.CollectionEntry(w.id, w.dbUpdateDate, 'workflow', w.sourceControl, w.organization, w.repository, w.workflowName) from BioWorkflow w, Collection col join col.entries as e where col.id = :collectionId and e.version is null and w.id = e.entry.id and w.isPublished = true"),
        @NamedQuery(name = "Entry.getWorkflowsLength", query = "SELECT COUNT(w.id) FROM BioWorkflow w, Collection col join col.entries as e where col.id = :collectionId and w.id = e.entry.id and w.isPublished = true"),
        @NamedQuery(name = "Entry.getCollectionServices", query = "SELECT new io.dockstore.webservice.core.CollectionEntry(w.id, w.dbUpdateDate, 'service', w.sourceControl, w.organization, w.repository, w.workflowName) from Service w, Collection col join col.entries as e where col.id = :collectionId and e.version is null and w.id = e.entry.id and w.isPublished = true"),
        @NamedQuery(name = "Entry.getCollectionTools", query = "SELECT new io.dockstore.webservice.core.CollectionEntry(t.id, t.dbUpdateDate, 'tool', t.registry, t.namespace, t.name, t.toolname) from Tool t, Collection col join col.entries as e where col.id = :collectionId and t.id = e.entry.id and e.version is null and t.isPublished = true"),
        @NamedQuery(name = "Entry.getToolsLength", query = "SELECT COUNT(t.id) FROM Tool t, Collection col join col.entries as e where col.id = :collectionId and t.id = e.entry.id and t.isPublished = true"),
        @NamedQuery(name = "Entry.getCollectionWorkflowsWithVersions", query = "SELECT new io.dockstore.webservice.core.CollectionEntry(w.id, w.dbUpdateDate, 'workflow', w.sourceControl, w.organization, w.repository, w.workflowName, v.name, v.versionMetadata.verified) from Version v, BioWorkflow w, Collection col join col.entries as e where v.id = e.version.id and col.id = :collectionId and w.id = e.entry.id and w.isPublished = true"),
        @NamedQuery(name = "Entry.getCollectionServicesWithVersions", query = "SELECT new io.dockstore.webservice.core.CollectionEntry(w.id, w.dbUpdateDate, 'service', w.sourceControl, w.organization, w.repository, w.workflowName, v.name, v.versionMetadata.verified) from Version v, Service w, Collection col join col.entries as e where v.id = e.version.id and col.id = :collectionId and w.id = e.entry.id and w.isPublished = true"),
        @NamedQuery(name = "Entry.getCollectionToolsWithVersions", query = "SELECT new io.dockstore.webservice.core.CollectionEntry(t.id, t.dbUpdateDate, 'tool', t.registry, t.namespace, t.name, t.toolname, v.name, v.versionMetadata.verified) from Version v, Tool t, Collection col join col.entries as e where v.id = e.version.id and col.id = :collectionId and t.id = e.entry.id and t.isPublished = true"),
        @NamedQuery(name = "io.dockstore.webservice.core.Entry.findLabelByEntryId", query = "SELECT e.labels FROM Entry e WHERE e.id = :entryId"),
        @NamedQuery(name = "Entry.findToolsDescriptorTypes", query = "SELECT t.descriptorType FROM Tool t WHERE t.id = :entryId"),
        @NamedQuery(name = "Entry.findWorkflowsDescriptorTypes", query = "SELECT w.descriptorType FROM Workflow w WHERE w.id = :entryId")

})
// TODO: Replace this with JPA when possible
@NamedNativeQueries({
    @NamedNativeQuery(name = "Entry.getEntryByPath", query =
        "SELECT 'tool' as type, id from tool where registry = :one and namespace = :two and name = :three and toolname = :four union"
            + " select 'workflow' as type, id from workflow where sourcecontrol = :one and organization = :two and repository = :three and workflowname = :four"),
    @NamedNativeQuery(name = "Entry.getEntryByPathNullName", query =
        "SELECT 'tool' as type, id from tool where registry = :one and namespace = :two and name = :three and toolname IS NULL union"
            + " select 'workflow' as type, id from workflow where sourcecontrol = :one and organization = :two and repository = :three and workflowname IS NULL"),
    @NamedNativeQuery(name = "Entry.getPublishedEntryByPath", query =
        "SELECT 'tool' as type, id from tool where registry = :one and namespace = :two and name = :three and toolname = :four and ispublished = TRUE union"
            + " select 'workflow' as type, id from workflow where sourcecontrol = :one and organization = :two and repository = :three and workflowname = :four and ispublished = TRUE"),
    @NamedNativeQuery(name = "Entry.getPublishedEntryByPathNullName", query =
        "SELECT 'tool' as type, id from tool where registry = :one and namespace = :two and name = :three and toolname IS NULL and ispublished = TRUE union"
            + " select 'workflow' as type, id from workflow where sourcecontrol = :one and organization = :two and repository = :three and workflowname IS NULL and ispublished = TRUE"),
    @NamedNativeQuery(name = "Entry.hostedWorkflowCount", query = "select (select count(*) from tool t, user_entry ue where mode = 'HOSTED' and ue.userid = :userid and ue.entryid = t.id) + (select count(*) from workflow w, user_entry ue where mode = 'HOSTED' and ue.userid = :userid and ue.entryid = w.id) as count;") })
public abstract class Entry<S extends Entry, T extends Version> implements Comparable<Entry>, Aliasable {

    /**
     * re-use existing generator for backwards compatibility
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "container_id_seq")
    @SequenceGenerator(name = "container_id_seq", sequenceName = "container_id_seq")
    @ApiModelProperty(value = "Implementation specific ID for the container in this web service", position = 0)
    private long id;

    @Column
    @ApiModelProperty(value = "This is the name of the author stated in the Dockstore.cwl", position = 1)
    private String author;
    @Column(columnDefinition = "TEXT")
    @ApiModelProperty(value = "This is a human-readable description of this container and what it is trying to accomplish, required GA4GH", position = 2)
    private String description;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "entry_label", joinColumns = @JoinColumn(name = "entryid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "labelid", referencedColumnName = "id"))
    @ApiModelProperty(value = "Labels (i.e. meta tags) for describing the purpose and contents of containers", position = 3)
    @OrderBy("id")
    @BatchSize(size = 25)
    private SortedSet<Label> labels = new TreeSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_entry", inverseJoinColumns = @JoinColumn(name = "userid", nullable = false, updatable = false, referencedColumnName = "id"), joinColumns = @JoinColumn(name = "entryid", nullable = false, updatable = false, referencedColumnName = "id"))
    @ApiModelProperty(value = "This indicates the users that have control over this entry, dockstore specific", required = false, position = 4)
    @OrderBy("id")
    @BatchSize(size = 25)
    private SortedSet<User> users;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "starred", inverseJoinColumns = @JoinColumn(name = "userid", nullable = false, updatable = false, referencedColumnName = "id"), joinColumns = @JoinColumn(name = "entryid", nullable = false, updatable = false, referencedColumnName = "id"))
    @ApiModelProperty(value = "This indicates the users that have starred this entry, dockstore specific", required = false, position = 5)
    @JsonSerialize(using = EntryStarredSerializer.class)
    @OrderBy("id")
    @BatchSize(size = 25)
    private SortedSet<User> starredUsers;

    @Column
    @ApiModelProperty(value = "This is the email of the git organization", position = 6)
    private String email;

    @Column
    @JsonProperty("is_published")
    @ApiModelProperty(value = "Implementation specific visibility in this web service", position = 8)
    private boolean isPublished;

    @Column
    @ApiModelProperty(value = "Implementation specific timestamp for last modified. "
            + "Tools-> For automated/manual builds: N/A. For hosted: Last time a file was updated/created (new version created). "
            + "Workflows-> For remote: When refresh is hit, last time GitHub repo was changed. Hosted: Last time a new version was made.", position = 9)
    private Date lastModified;

    @Column
    @ApiModelProperty(value = "Implementation specific timestamp for last updated on webservice. "
            + "Tools-> For automated builds: last time tool/namespace was refreshed Dockstore, tool info (like changing dockerfile path) updated, or default version selected. For hosted tools: when you created the tool. "
            + "Workflows-> For remote: When refresh all is hit for first time. Hosted: Seems to be time created.", position = 10, dataType = "long")
    @Schema(type = "integer", format = "int64")
    private Date lastUpdated;

    @Column
    @ApiModelProperty(value = "This is a link to the associated repo with a descriptor, required GA4GH", required = true, position = 11)
    private String gitUrl;

    @JsonIgnore
    @JoinColumn(name = "checkerid", unique = true)
    @OneToOne(targetEntity = BioWorkflow.class, fetch = FetchType.EAGER)
    @ApiModelProperty(value = "The id of the associated checker workflow")
    private BioWorkflow checkerWorkflow;

    @ElementCollection(targetClass = Alias.class)
    @JoinTable(name = "entry_alias", joinColumns = @JoinColumn(name = "id"), uniqueConstraints = @UniqueConstraint(name = "unique_entry_aliases", columnNames = { "alias" }))
    @MapKeyColumn(name = "alias", columnDefinition = "text")
    @ApiModelProperty(value = "aliases can be used as an alternate unique id for entries")
    @BatchSize(size = 25)
    private Map<String, Alias> aliases = new HashMap<>();

    // database timestamps
    @Column(updatable = false, nullable = false)
    @CreationTimestamp
    @ApiModelProperty(dataType = "long")
    @Schema(type = "integer", format = "int64")
    private Timestamp dbCreateDate;

    @Column(nullable = false)
    @UpdateTimestamp
    @ApiModelProperty(dataType = "long")
    @Schema(type = "integer", format = "int64")
    private Timestamp dbUpdateDate;

    @Column
    @ApiModelProperty(value = "The Id of the corresponding topic on Dockstore Discuss")
    private Long topicId;

    @JsonIgnore
    @ElementCollection
    @Column(columnDefinition = "text")
    private Set<String> blacklistedVersionNames = new LinkedHashSet<>();

    /**
     * Example of generalizing concept of default paths across tools, workflows
     */
    @JsonIgnore
    @ElementCollection(fetch = FetchType.EAGER)
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "path", nullable = false, columnDefinition = "text")
    @MapKeyColumn(name = "filetype")
    @CollectionTable(uniqueConstraints = @UniqueConstraint(name = "unique_paths", columnNames = { "entry_id", "filetype", "path" }))
    private Map<DescriptorLanguage.FileType, String> defaultPaths = new HashMap<>();

    @Column
    @ApiModelProperty(value = "The Digital Object Identifier (DOI) representing all of the versions of your workflow", position = 14)
    private String conceptDoi;

    @JsonProperty("input_file_formats")
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "entry_input_fileformat", joinColumns = @JoinColumn(name = "entryid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "fileformatid", referencedColumnName = "id"))
    @ApiModelProperty(value = "File formats for describing the input file formats of every version of an entry", position = 15)
    @OrderBy("id")
    @BatchSize(size = 25)
    private SortedSet<FileFormat> inputFileFormats = new TreeSet<>();

    @JsonProperty("output_file_formats")
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "entry_output_fileformat", joinColumns = @JoinColumn(name = "entryid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "fileformatid", referencedColumnName = "id"))
    @ApiModelProperty(value = "File formats for describing the output file formats of every version of an entry", position = 16)
    @OrderBy("id")
    @BatchSize(size = 25)
    private SortedSet<FileFormat> outputFileFormats = new TreeSet<>();

    @Embedded
    private LicenseInformation licenseInformation = new LicenseInformation();

    @Column
    @ApiModelProperty(value = "The presence of the put code indicates the entry was exported to ORCID.")
    private String orcidPutCode;

    public Entry() {
        users = new TreeSet<>();
        starredUsers = new TreeSet<>();
    }

    public Entry(long id) {
        this.id = id;
        users = new TreeSet<>();
        starredUsers = new TreeSet<>();
    }

    @JsonIgnore
    public abstract String getEntryPath();

    @JsonIgnore
    public abstract EntryType getEntryType();

    @JsonIgnore
    public abstract boolean isHosted();

    @JsonProperty("checker_id")
    @ApiModelProperty(value = "The id of the associated checker workflow", position = 12)
    public Long getCheckerId() {
        if (checkerWorkflow == null) {
            return null;
        } else {
            return checkerWorkflow.getId();
        }
    }

    public void setConceptDoi(String conceptDoi) {
        this.conceptDoi = conceptDoi;
    }

    @JsonProperty
    public String getConceptDoi() {
        return conceptDoi;
    }

    public Map<String, Alias> getAliases() {
        return aliases;
    }

    public void setAliases(Map<String, Alias> aliases) {
        this.aliases = aliases;
    }

    public BioWorkflow getCheckerWorkflow() {
        return checkerWorkflow;
    }

    public void setCheckerWorkflow(BioWorkflow checkerWorkflow) {
        this.checkerWorkflow = checkerWorkflow;
    }

    @JsonProperty
    public String getAuthor() {
        return author;
    }

    @JsonProperty
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @JsonProperty
    public String getDescription() {
        return description;
    }

    /**
     * @param description the repo description to set
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Currently unused because too many entries do not have a default version set
     */
    public void syncMetadataWithDefault() {
        T realDefaultVersion = this.getActualDefaultVersion();
        if (realDefaultVersion != null) {
            this.setMetadataFromVersion(realDefaultVersion);
        }
    }
    @ApiModelProperty(value = "This is the name of the default version of the entry", position = 7)
    public String getDefaultVersion() {
        if (this.getActualDefaultVersion() != null) {
            return this.getActualDefaultVersion().getName();
        } else {
            return null;
        }
    }

    public LicenseInformation getLicenseInformation() {
        return licenseInformation != null ? licenseInformation : new LicenseInformation();
    }

    public void setLicenseInformation(LicenseInformation licenseInformation) {
        this.licenseInformation = licenseInformation;
    }


    public void setAuthor(String newAuthor) {
        this.author = newAuthor;
    }

    public Set<Label> getLabels() {
        return labels;
    }

    public void setLabels(SortedSet<Label> labels) {
        this.labels = labels;
    }

    public void setUsers(SortedSet<User> users) {
        this.users = users;
    }

    public Set<User> getUsers() {
        return users;
    }

    public void addUser(User user) {
        users.add(user);
    }

    public boolean removeUser(User user) {
        return users.remove(user);
    }

    @JsonProperty
    public String getEmail() {
        return email;
    }

    public void setEmail(String newEmail) {
        this.email = newEmail;
    }

    /**
     * @param isPublished will the repo be published
     */
    public void setIsPublished(boolean isPublished) {
        this.isPublished = isPublished;
    }

    /**
     * @param lastModified the lastModified to set
     */
    public void setLastModified(Date lastModified) {
        this.lastModified = lastModified;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public boolean getIsPublished() {
        return isPublished;
    }

    @JsonProperty("last_modified")
    public Integer getLastModified() {
        // this is lossy, but needed for backwards compatibility
        return lastModified == null ? null : (int)lastModified.getTime();
    }

    @JsonProperty("has_checker")
    public boolean hasChecker() {
        return checkerWorkflow != null;
    }

    @JsonProperty("last_modified_date")
    @ApiModelProperty(dataType = "long")
    @Schema(type = "integer", format = "int64")
    public Date getLastModifiedDate() {
        return lastModified;
    }

    /**
     * @return will return the git url or empty string if not present
     */
    @JsonProperty
    public String getGitUrl() {
        if (gitUrl == null) {
            return "";
        }
        return gitUrl;
    }

    @JsonProperty
    public Date getLastUpdated() {
        if (lastUpdated == null) {
            return new Date(0L);
        }
        return lastUpdated;
    }

    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Set<User> getStarredUsers() {
        return starredUsers;
    }

    public void addStarredUser(User user) {
        starredUsers.add(user);
    }

    public boolean removeStarredUser(User user) {
        return starredUsers.remove(user);
    }

    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    /**
     * Used during refresh to update containers
     *
     * @param entry
     */
    public void update(S entry) {
        setLicenseInformation(entry.getLicenseInformation());
        setMetadataFromEntry(entry);
        lastModified = entry.getLastModifiedDate();
        // Only overwrite the giturl if the new git url is not empty (no value)
        // This will stop the case where there are no autobuilds for a quay repo, but a manual git repo has been set.
        //  Giturl will only be changed if the git repo from quay has an autobuild
        if (!entry.getGitUrl().isEmpty()) {
            gitUrl = entry.getGitUrl();
        }
    }

    public void setMetadataFromEntry(S entry) {
        this.author = entry.getAuthor();
        this.description = entry.getDescription();
        this.email = entry.getEmail();
    }

    public void setMetadataFromVersion(Version version) {
        this.author = version.getAuthor();
        this.description = version.getDescription();
        this.email = version.getEmail();
    }

    public SortedSet<FileFormat> getInputFileFormats() {
        return this.inputFileFormats;
    }
    public void setInputFileFormats(final SortedSet<FileFormat> inputFileFormats) {
        this.inputFileFormats = inputFileFormats;
    }

    public SortedSet<FileFormat> getOutputFileFormats() {
        return this.outputFileFormats;
    }
    public void setOutputFileFormats(final SortedSet<FileFormat> outputFileFormats) {
        this.outputFileFormats = outputFileFormats;
    }

    /**
     * Convenience method to access versions in a generic manner
     *
     * @return versions
     */
    @JsonProperty
    public abstract Set<T> getWorkflowVersions();

    @JsonProperty
    public void setWorkflowVersions(Set<T> set) {
        this.getWorkflowVersions().clear();
        this.getWorkflowVersions().addAll(set);
    }

    public boolean addWorkflowVersion(T workflowVersion) {
        workflowVersion.setParent(this);
        return getWorkflowVersions().add(workflowVersion);
    }

    public boolean removeWorkflowVersion(T workflowVersion) {
        workflowVersion.setParent(null);
        return getWorkflowVersions().remove(workflowVersion);
    }

    public abstract void setActualDefaultVersion(T version);

    @JsonIgnore
    public abstract T getActualDefaultVersion();

    /**
     * @param newDefaultVersion
     * @return true if defaultVersion is a valid Docker tag
     */
    public boolean checkAndSetDefaultVersion(String newDefaultVersion) {
        for (T version : this.getWorkflowVersions()) {
            if (Objects.equals(newDefaultVersion, version.getName())) {
                if (version.isHidden()) {
                    throw new CustomWebApplicationException("You can not set the default version to a hidden version.", HttpStatus.SC_BAD_REQUEST);
                }
                this.setActualDefaultVersion(version);
                this.syncMetadataWithDefault();
                return true;
            }
        }
        return false;
    }

    /**
     * Given a path (A/B/C/D), splits it into parts and returns it
     *
     * @param path
     * @return An array of fields used to identify an entry
     */
    public static String[] splitPath(String path) {
        // Used for accessing index of path
        final int registryIndex = 0;
        final int orgIndex = 1;
        final int repoIndex = 2;
        final int entryNameIndex = 3;

        // Lengths of paths
        final int pathNoNameLength = 3;
        final int pathWithNameLength = 4;

        // Used for storing values at path locations
        String registry;
        String org;
        String repo;
        String entryName = null;

        // Split path by slash
        String[] splitPath = path.split("/");

        // Only split if it is the correct length
        if (splitPath.length == pathNoNameLength || splitPath.length == pathWithNameLength) {
            // Get remaining positions
            registry = splitPath[registryIndex];
            org = splitPath[orgIndex];
            repo = splitPath[repoIndex];
            if (splitPath.length == pathWithNameLength) {
                entryName = splitPath[entryNameIndex];
            }

            // Return an array of the form [A,B,C,D]
            return new String[]{registry, org, repo, entryName};
        } else {
            return null;
        }
    }

    @JsonIgnore
    public abstract Event.Builder getEventBuilder();

    public Timestamp getDbCreateDate() {
        return dbCreateDate;
    }

    public Timestamp getDbUpdateDate() {
        return dbUpdateDate;
    }

    @Override
    public int compareTo(@NotNull Entry that) {
        return ComparisonChain.start().compare(this.getId(), that.getId(), Ordering.natural().nullsLast())
            .result();
    }

    public Map<DescriptorLanguage.FileType, String> getDefaultPaths() {
        return defaultPaths;
    }

    public void setDefaultPaths(Map<DescriptorLanguage.FileType, String> defaultPaths) {
        this.defaultPaths = defaultPaths;
    }

    public Set<String> getBlacklistedVersionNames() {
        return blacklistedVersionNames;
    }

    public void setBlacklistedVersionNames(Set<String> blacklistedVersionNames) {
        this.blacklistedVersionNames = blacklistedVersionNames;
    }

    public String getOrcidPutCode() {
        return orcidPutCode;
    }

    public void setOrcidPutCode(String orcidPutCode) {
        this.orcidPutCode = orcidPutCode;
    }
}
