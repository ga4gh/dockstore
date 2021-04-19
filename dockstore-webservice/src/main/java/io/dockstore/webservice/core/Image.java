/*
 *    Copyright 2019 OICR
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
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import io.dockstore.common.Registry;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@ApiModel(value = "Image", description = "Image(s) associated with tags and workflow versions")
@Table(name = "image")
@SuppressWarnings("checkstyle:magicnumber")

public class Image {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "Implementation specific ID for the image in this webservice", position = 0)
    private long id;

    @Column(columnDefinition = "varchar")
    @Convert(converter = ChecksumConverter.class)
    @ApiModelProperty(value = "Checksum(s) associated with this image", position = 1)
    private List<Checksum> checksums = new ArrayList<>();

    @Column()
    @ApiModelProperty(value = "Repository image belongs to", position = 2)
    private String repository;

    @Column()
    @ApiModelProperty(value = "Git tag", position = 3)
    private String tag;

    @Column(name = "image_id")
    @ApiModelProperty(value = "Docker ID of the image", position = 4)
    private String imageID;

    @Column()
    @Enumerated(EnumType.STRING)
    @ApiModelProperty(value = "Registry the image belongs to", position = 5)
    private Registry imageRegistry;

    @Column()
    @ApiModelProperty(value = "Stores the architecture and, if available, the variant of an image. Separated by a / and only applicable to Docker Hub", position = 6)
    private String architecture;

    @Column()
    @ApiModelProperty(value = "Stores the OS and, if available the OS version. Separated by a / and only applicable to Docker Hub", position = 7)
    private String os;

    @Column()
    @ApiModelProperty(value = "The size of the image in bytes")
    private Long size;

    @Column()
    @ApiModelProperty(value = "The date the image was updated in the Docker repository")
    private String imageUpdateDate;

    @Column(updatable = false)
    @CreationTimestamp
    private Timestamp dbCreateDate;

    @Column()
    @UpdateTimestamp
    private Timestamp dbUpdateDate;

    public Image() {

    }

    public Image(List<Checksum> checksums, String repository, String tag, String imageID, Registry imageRegistry, Long size, String imageUpdateDate) {
        this.checksums = checksums;
        this.repository = repository;
        this.tag = tag;
        this.imageID = imageID;
        this.imageRegistry = imageRegistry;
        this.size = size;
        this.imageUpdateDate = imageUpdateDate;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getImageID() {
        return imageID;
    }

    public void setImageID(String imageID) {
        this.imageID = imageID;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getRepository() {
        return this.repository;
    }

    public void setChecksums(List<Checksum> checksums) {
        this.checksums = checksums;
    }

    public List<Checksum> getChecksums() {
        return this.checksums;
    }

    public Registry getImageRegistry() {
        return imageRegistry;
    }

    public void setImageRegistry(final Registry imageRegistry) {
        this.imageRegistry = imageRegistry;
    }

    public String getArchitecture() {
        return architecture;
    }

    public void setArchitecture(final String architecture) {
        this.architecture = architecture;
    }

    public String getOs() {
        return os;
    }

    public void setOs(final String os) {
        this.os = os;
    }

    public Timestamp getDbUpdateDate() {
        return dbUpdateDate;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getImageUpdateDate() {
        return imageUpdateDate;
    }
}
