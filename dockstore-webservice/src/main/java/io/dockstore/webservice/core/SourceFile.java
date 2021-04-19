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

import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.ElementCollection;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToOne;
import javax.persistence.MapKeyColumn;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ComparisonChain;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.webservice.helpers.FileFormatHelper;
import io.dockstore.webservice.helpers.ZipSourceFileHelper;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This describes a cached copy of a remotely accessible file. Implementation specific.
 *
 * @author xliu
 */
@ApiModel("SourceFile")
@Entity
@Table(name = "sourcefile")
@NamedQueries({
        @NamedQuery(name = "io.dockstore.webservice.core.SourceFile.findSourceFilesForVersion", query = "SELECT sourcefiles FROM Version version INNER JOIN version.sourceFiles as sourcefiles WHERE version.id = :versionId"),
})
@SuppressWarnings("checkstyle:magicnumber")
public class SourceFile implements Comparable<SourceFile> {

    public static final EnumSet<DescriptorLanguage.FileType> TEST_FILE_TYPES = EnumSet.of(DescriptorLanguage.FileType.CWL_TEST_JSON, DescriptorLanguage.FileType.WDL_TEST_JSON, DescriptorLanguage.FileType.NEXTFLOW_TEST_PARAMS);

    private static final Logger LOG = LoggerFactory.getLogger(SourceFile.class);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "Implementation specific ID for the source file in this web service", position = 0)
    private long id;

    @Enumerated(EnumType.STRING)
    @ApiModelProperty(value = "Enumerates the type of file", required = true, position = 1)
    @Schema(description = "Enumerates the type of file", required = true)
    private DescriptorLanguage.FileType type;

    //TODO sha1 duplicates part of checksums
    @ManyToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "sha1", referencedColumnName = "id")
    @JsonIgnore
    private FileContent fileContent;

    @Column(nullable = false, columnDefinition = "TEXT")
    @ApiModelProperty(value = "Path to sourcefile relative to its parent", required = true, position = 3)
    @Schema(description = "Path to sourcefile relative to its parent", required = true)
    private String path;

    @Column(nullable = false, columnDefinition = "TEXT")
    @ApiModelProperty(value = "Absolute path of sourcefile in git repo", required = true, position = 4)
    @Schema(description = "Absolute path of sourcefile in git repo", required = true)
    private String absolutePath;

    @Column(columnDefinition = "boolean default false")
    @ApiModelProperty(value = "When true, this version cannot be affected by refreshes to the content or updates to its metadata", position = 5)
    private boolean frozen = false;

    @Column(columnDefinition = "varchar")
    @Convert(converter = ChecksumConverter.class)
    @ApiModelProperty(value = "The checksum(s) of the sourcefile's content", position = 6)
    private List<Checksum> checksums = new ArrayList<>();

    // database timestamps
    @Column(updatable = false)
    @CreationTimestamp
    private Timestamp dbCreateDate;

    @Column()
    @UpdateTimestamp
    private Timestamp dbUpdateDate;

    @ElementCollection(targetClass = VerificationInformation.class, fetch = FetchType.EAGER)
    @JoinTable(name = "sourcefile_verified", joinColumns = @JoinColumn(name = "id"), uniqueConstraints = @UniqueConstraint(columnNames = {
        "id", "source" }))
    @MapKeyColumn(name = "source", columnDefinition = "text")
    @ApiModelProperty(value = "maps from platform to whether an entry successfully ran on it using this test json")
    @BatchSize(size = 25)
    private Map<String, VerificationInformation> verifiedBySource = new HashMap<>();

    public Map<String, VerificationInformation> getVerifiedBySource() {
        return verifiedBySource;
    }

    public void setVerifiedBySource(Map<String, VerificationInformation> verifiedBySource) {
        this.verifiedBySource = verifiedBySource;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public DescriptorLanguage.FileType getType() {
        return type;
    }

    public void setType(DescriptorLanguage.FileType type) {
        this.type = type;
    }

    @ApiModelProperty(value = "Cache for the contents of the target file", position = 2)
    public String getContent() {
        if (fileContent != null) {
            return fileContent.getContent();
        } else {
            return null;
        }
    }

    @JsonIgnore
    public FileContent getFileContent() {
        return fileContent;
    }

    @JsonIgnore
    public void setFileContent(FileContent fileContent) {
        this.fileContent = fileContent;
    }

    public void setContent(String content) {
        String calcSha1 = FileFormatHelper.calcSHA1(content).orElse(null);
        if (fileContent == null || !Objects.equals(fileContent.getId(), calcSha1)) {
            this.fileContent = new FileContent(calcSha1, content);
        }
    }

    public String getPath() {
        return this.path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getAbsolutePath() {
        if (absolutePath == null) {
            return null;
        }
        return Paths.get(absolutePath).normalize().toString();
    }

    public List<Checksum> getChecksums() {
        return checksums;
    }

    public void setChecksums(final List<Checksum> checksums) {
        this.checksums = checksums;
    }

    public void setAbsolutePath(String absolutePath) {
        // TODO: Figure out the actual absolute path before this workaround
        // FIXME: it looks like dockstore tool test_parameter --add and a number of other CLI commands depend on this now
        this.absolutePath = ZipSourceFileHelper.addLeadingSlashIfNecessary((absolutePath));
        if (!this.absolutePath.equals(absolutePath)) {
            LOG.warn("Absolute path workaround used, this should be fixed at some point");
        }
    }

    @JsonIgnore
    public Timestamp getDbCreateDate() {
        return dbCreateDate;
    }

    @JsonIgnore
    public Timestamp getDbUpdateDate() {
        return dbUpdateDate;
    }

    // removed overridden hashcode and equals, resulted in issue due to https://hibernate.atlassian.net/browse/HHH-3799

    @Override
    public int compareTo(@NotNull SourceFile that) {
        if (this.absolutePath == null || that.absolutePath == null) {
            return ComparisonChain.start().compare(this.path, that.path).result();
        } else {
            return ComparisonChain.start().compare(this.absolutePath, that.absolutePath).result();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("id", id).add("type", type).add("path", path).add("absolutePath", absolutePath).toString();
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    /**
     * Stores verification information for a given (test) file
     */
    @Embeddable
    public static class VerificationInformation {
        public boolean verified = false;
        @Column(columnDefinition = "text")
        public String metadata = "";

        // By default set to null in database
        @Column(columnDefinition = "text")
        public String platformVersion = null;

        // database timestamps
        @Column(updatable = false)
        @CreationTimestamp
        private Timestamp dbCreateDate;

        @Column()
        @UpdateTimestamp
        private Timestamp dbUpdateDate;
    }
}
