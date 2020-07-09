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
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.OrderBy;
import javax.persistence.PrimaryKeyJoinColumn;
import javax.persistence.SequenceGenerator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import io.dockstore.webservice.CustomWebApplicationException;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.apache.http.HttpStatus;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * This describes one version of either a workflow or a tool.
 *
 * @author dyuen
 */
@Entity
@ApiModel(value = "Version", description = "Base class for versions of entries in the Dockstore")
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)

// Ensure that the version requested belongs to a workflow a user has access to.
@NamedQueries({
        @NamedQuery(name = "io.dockstore.webservice.core.Version.findVersionInEntry", query = "SELECT v FROM Version v WHERE :entryId = v.parent.id AND :versionId = v.id"),
        @NamedQuery(name = "io.dockstore.webservice.core.database.VersionVerifiedPlatform.findEntryVersionsWithVerifiedPlatforms",
                query = "SELECT new io.dockstore.webservice.core.database.VersionVerifiedPlatform(version.id, KEY(verifiedbysource), verifiedbysource.metadata) FROM Version version "
                        + "INNER JOIN version.sourceFiles as sourcefiles INNER JOIN sourcefiles.verifiedBySource as verifiedbysource WHERE KEY(verifiedbysource) IS NOT NULL AND "
                        + "version.parent.id = :entryId"
        )
})

@SuppressWarnings("checkstyle:magicnumber")
public abstract class Version<T extends Version> implements Comparable<T> {
    public static final String CANNOT_FREEZE_VERSIONS_WITH_NO_FILES = "cannot freeze versions with no files";
    private static final Gson GSON = new Gson();

    /**
     * re-use existing generator for backwards compatibility
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tag_id_seq")
    @SequenceGenerator(name = "tag_id_seq", sequenceName = "tag_id_seq")
    @ApiModelProperty(value = "Implementation specific ID for the tag in this web service", position = 0)
    protected long id;

    @Column
    @ApiModelProperty(value = "git commit/tag/branch", required = true, position = 1)
    protected String reference;

    @Column
    @ApiModelProperty(value = "Implementation specific, can be a quay.io or docker hub tag name", required = true, position = 2)
    protected String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parentid", nullable = false)
    @ApiModelProperty(value = "parent entry id", required = true, position = 0, accessMode = ApiModelProperty.AccessMode.READ_ONLY)
    private Entry<?, ?> parent;


    @Column(columnDefinition = "text")
    @ApiModelProperty(value = "This is the commit id for the source control that the files belong to", position = 3)
    private String commitID;

    @Column(columnDefinition = "boolean default false")
    @ApiModelProperty(value = "When true, this version cannot be affected by refreshes to the content or updates to its metadata", position = 4)
    private boolean frozen = false;

    @Column(columnDefinition = "text default 'UNSET'", nullable = false)
    @Enumerated(EnumType.STRING)
    @ApiModelProperty(value = "This indicates the type of git (or other source control) reference", position = 5)
    private ReferenceType referenceType = ReferenceType.UNSET;

    // watch out for https://hibernate.atlassian.net/browse/HHH-3799 if this is set to EAGER
    // TODO: @JsonIgnore this field to catch more places in UI that use it.
    // TODO: Change to FetchType.LAZY
    @OneToMany(fetch = FetchType.LAZY, orphanRemoval = true, cascade = CascadeType.ALL)
    @JoinTable(name = "version_sourcefile", joinColumns = @JoinColumn(name = "versionid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "sourcefileid", referencedColumnName = "id"))
    @ApiModelProperty(value = "Cached files for each version. Includes Dockerfile and Descriptor files", position = 6)
    @Cascade(org.hibernate.annotations.CascadeType.DETACH)
    @OrderBy("path")
    private final SortedSet<SourceFile> sourceFiles;

    @Column
    @ApiModelProperty(value = "Implementation specific, whether this tag has valid files from source code repo", position = 7)
    private boolean valid;

    @Column(columnDefinition = "boolean default false")
    @ApiModelProperty(value = "True if user has altered the tag", position = 8)
    private boolean dirtyBit = false;

    @JsonIgnore
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "parent")
    @Cascade(org.hibernate.annotations.CascadeType.ALL)
    @PrimaryKeyJoinColumn
    private VersionMetadata versionMetadata = new VersionMetadata();

    @ApiModelProperty(value = "Particularly for hosted workflows, this records who edited to create a revision", position = 9)
    @OneToOne
    private User versionEditor;

    // database timestamps
    @Column(updatable = false, nullable = false)
    @CreationTimestamp
    @ApiModelProperty(position = 10)
    private Timestamp dbCreateDate;

    @Column(nullable = false)
    @UpdateTimestamp
    @JsonProperty("dbUpdateDate")
    @ApiModelProperty(position = 11)
    private Timestamp dbUpdateDate;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "version_input_fileformat", joinColumns = @JoinColumn(name = "versionid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "fileformatid", referencedColumnName = "id"))
    @ApiModelProperty(value = "File formats for describing the input file formats of versions (tag/workflowVersion)", position = 12)
    @OrderBy("id")
    private SortedSet<FileFormat> inputFileFormats = new TreeSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "version_output_fileformat", joinColumns = @JoinColumn(name = "versionid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "fileformatid", referencedColumnName = "id"))
    @ApiModelProperty(value = "File formats for describing the output file formats of versions (tag/workflowVersion)", position = 13)
    @OrderBy("id")
    private SortedSet<FileFormat> outputFileFormats = new TreeSet<>();

    @OneToMany(fetch = FetchType.LAZY, orphanRemoval = true, cascade = CascadeType.ALL)
    @JoinTable(name = "version_validation", joinColumns = @JoinColumn(name = "versionid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "validationid", referencedColumnName = "id"))
    @ApiModelProperty(value = "Cached validations for each version.", position = 14)
    @OrderBy("type")
    private final SortedSet<Validation> validations;

    @OneToMany(fetch = FetchType.LAZY, orphanRemoval = true, cascade = CascadeType.ALL)
    @JoinTable(name = "entry_version_image", joinColumns = @JoinColumn(name = "versionid", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "imageid", referencedColumnName = "id"))
    @ApiModelProperty(value = "The images that belong to this version", position = 15)
    private Set<Image> images = new HashSet<>();

    public Version() {
        sourceFiles = new TreeSet<>();
        validations = new TreeSet<>();
        versionMetadata.doiStatus = DOIStatus.NOT_REQUESTED;
        versionMetadata.parent = this;
    }

    @ApiModelProperty(value = "Whether this version has been verified or not", position = 16)
    public boolean isVerified() {
        return this.versionMetadata.verified;
    }

    @ApiModelProperty(value = "Verified source for the version", position = 17)
    @Deprecated
    public String getVerifiedSource() {
        return this.getVersionMetadata().verifiedSource;
    }

    @ApiModelProperty(value = "Verified source for the version", position = 18)
    public String[] getVerifiedSources() {
        if (this.getVersionMetadata().verifiedSource == null) {
            return new String[0];
        } else {
            return GSON.fromJson(Strings.nullToEmpty(this.getVersionMetadata().verifiedSource), String[].class);
        }
    }

    /**
     * Used to determine the "newer" version. WorkflowVersion relies on last_modified, Tag relies on last_built (hosted tools fall back to dbCreateDate).
     * @return  The date used to determine the "newer" version
     */
    @JsonIgnore
    public abstract Date getDate();

    public boolean isDirtyBit() {
        return dirtyBit;
    }

    public void setDirtyBit(boolean dirtyBit) {
        if (!this.isFrozen()) {
            this.dirtyBit = dirtyBit;
        }
    }

    void updateByUser(final Version version) {
        this.getVersionMetadata().hidden = version.isHidden();
        this.setDoiStatus(version.getDoiStatus());
        this.setDoiURL(version.getDoiURL());
        if (!this.isFrozen()) {
            if (version.frozen && this.sourceFiles.isEmpty()) {
                throw new CustomWebApplicationException(CANNOT_FREEZE_VERSIONS_WITH_NO_FILES, HttpStatus.SC_BAD_REQUEST);
            }
            this.setFrozen(version.frozen);
            reference = version.reference;
        }
    }

    public abstract String getWorkingDirectory();

    public void update(T version) {
        valid = version.isValid();
        name = version.getName();
        referenceType = version.getReferenceType();
        frozen = version.isFrozen();
        commitID = version.getCommitID();
        this.setVersionMetadata(version.getVersionMetadata());
    }

    public void clone(T version) {
        name = version.getName();
        referenceType = version.getReferenceType();
        frozen = version.isFrozen();
    }

    @JsonProperty
    public long getId() {
        return id;
    }

    @JsonProperty
    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public SortedSet<SourceFile> getSourceFiles() {
        return sourceFiles;
    }

    public void addSourceFile(SourceFile file) {
        sourceFiles.add(file);
    }

    public SortedSet<Validation> getValidations() {
        return validations;
    }

    public void addOrUpdateValidation(Validation versionValidation) {
        Optional<Validation> matchingValidation = getValidations().stream()
            .filter(versionValidation1 -> Objects.equals(versionValidation.getType(), versionValidation1.getType())).findFirst();
        if (matchingValidation.isPresent()) {
            matchingValidation.get().setMessage(versionValidation.getMessage());
            matchingValidation.get().setValid(versionValidation.isValid());
        } else {
            validations.add(versionValidation);
        }
    }

    @JsonProperty
    @ApiModelProperty(value = "Implementation specific, whether this row is visible to other users aside from the owner", position = 18)
    public boolean isHidden() {
        return versionMetadata.hidden;
    }

    public void setHidden(boolean hidden) {
        this.getVersionMetadata().hidden = hidden;
    }

    @JsonProperty
    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public abstract Version createEmptyVersion();

    @JsonProperty
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<Image> getImages() {
        return images;
    }

    public void setImages(Set<Image> images) {
        this.images = images;
    }

    public void updateVerified() {
        this.getVersionMetadata().verified = calculateVerified(this.getSourceFiles());
        this.getVersionMetadata().verifiedSource = calculateVerifiedSource(this.getSourceFiles());
    }

    private static boolean calculateVerified(SortedSet<SourceFile> versionSourceFiles) {
        return versionSourceFiles.stream().anyMatch(file -> file.getVerifiedBySource().values().stream().anyMatch(innerEntry -> innerEntry.verified));
    }

    private static String calculateVerifiedSource(SortedSet<SourceFile> versionSourceFiles) {
        Set<String> verifiedSources = new TreeSet<>();
        versionSourceFiles.forEach(sourceFile -> {
            Map<String, SourceFile.VerificationInformation> verifiedBySource = sourceFile.getVerifiedBySource();
            for (Map.Entry<String, SourceFile.VerificationInformation> thing : verifiedBySource.entrySet()) {
                if (thing.getValue().verified) {
                    verifiedSources.add(thing.getValue().metadata);
                }
            }
        });
        // How strange that we're returning an array-like string
        return convertStringSetToString(verifiedSources);
    }

    private static String convertStringSetToString(Set<String> verifiedSources) {
        Gson gson = new Gson();
        if (verifiedSources.isEmpty()) {
            return null;
        } else {
            return Strings.nullToEmpty(gson.toJson(verifiedSources));
        }
    }

    @JsonProperty
    @ApiModelProperty(value = "This is a URL for the DOI for the version of the entry", position = 19)
    public String getDoiURL() {
        return versionMetadata.doiURL;
    }

    public void setDoiURL(String doiURL) {
        this.getVersionMetadata().doiURL = doiURL;
    }

    @ApiModelProperty(value = "This indicates the DOI status", position = 20)
    public DOIStatus getDoiStatus() {
        return versionMetadata.doiStatus;
    }

    @ApiModelProperty(position = 21)
    public String getAuthor() {
        return this.getVersionMetadata().author;
    }

    @ApiModelProperty(position = 22)
    public String getDescription() {
        return this.getVersionMetadata().description;
    }

    @ApiModelProperty(position = 23)
    public DescriptionSource getDescriptionSource() {
        return this.getVersionMetadata().descriptionSource;
    }

    @ApiModelProperty(position = 24)
    public String getEmail() {
        return this.getVersionMetadata().email;
    }

    public void setDoiStatus(DOIStatus doiStatus) {
        this.getVersionMetadata().doiStatus = doiStatus;
    }

    public void setDescriptionAndDescriptionSource(String newDescription, DescriptionSource newDescriptionSource) {
        this.getVersionMetadata().description = newDescription;
        this.getVersionMetadata().descriptionSource = newDescriptionSource;
    }

    public void setAuthor(String newAuthor) {
        this.getVersionMetadata().author = newAuthor;
    }

    public void setEmail(String newEmail) {
        this.getVersionMetadata().email = newEmail;
    }

    public ReferenceType getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(ReferenceType referenceType) {
        this.referenceType = referenceType;
    }

    @JsonProperty("input_file_formats")
    public Set<FileFormat> getInputFileFormats() {
        return inputFileFormats;
    }

    public void setInputFileFormats(SortedSet<FileFormat> inputFileFormats) {
        this.inputFileFormats = inputFileFormats;
    }

    @JsonProperty("output_file_formats")
    public Set<FileFormat> getOutputFileFormats() {
        return outputFileFormats;
    }

    public void setOutputFileFormats(SortedSet<FileFormat> outputFileFormats) {
        this.outputFileFormats = outputFileFormats;
    }

    public User getVersionEditor() {
        return versionEditor;
    }

    public void setVersionEditor(User versionEditor) {
        this.versionEditor = versionEditor;
    }

    public String getCommitID() {
        return commitID;
    }

    public void setCommitID(String commitID) {
        this.commitID = commitID;
    }

    @JsonIgnore
    public Timestamp getDbCreateDate() {
        return dbCreateDate;
    }

    public void setDbCreateDate(Timestamp dbCreateDate) {
        this.dbCreateDate = dbCreateDate;
    }

    @JsonIgnore
    public Timestamp getDbUpdateDate() {
        return dbUpdateDate;
    }

    public void setDbUpdateDate(Timestamp dbUpdateDate) {
        this.dbUpdateDate = dbUpdateDate;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
        // freeze sourcefiles as well, this ideally would be de-normalized but postgres doesn't do multi-table constraints and
        // multitable row-level security is an even bigger pain
        this.sourceFiles.forEach(s -> s.setFrozen(frozen));
    }

    public VersionMetadata getVersionMetadata() {
        if (versionMetadata == null) {
            versionMetadata = new VersionMetadata();
            versionMetadata.setId(this.id);
        }
        return versionMetadata;
    }

    /**
     * Setting each property individually because we don't want the id
     * @param newVersionMetadata    Newest metadata from source control
     */
    public void setVersionMetadata(VersionMetadata newVersionMetadata) {
        this.setAuthor(newVersionMetadata.author);
        this.setEmail(newVersionMetadata.email);
        this.setDescriptionAndDescriptionSource(newVersionMetadata.description, newVersionMetadata.descriptionSource);
    }

    public void setParent(Entry<?, ?> parent) {
        this.parent = parent;
    }

    public enum DOIStatus { NOT_REQUESTED, REQUESTED, CREATED }

    public enum ReferenceType { COMMIT, TAG, BRANCH, NOT_APPLICABLE, UNSET }


}
