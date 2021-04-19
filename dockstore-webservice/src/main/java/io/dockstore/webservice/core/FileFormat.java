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
import java.util.Comparator;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

import com.google.common.base.Objects;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * This describes a input or output file format that is associated with an entry in the dockstore
 *
 * @author gluu
 * @since 1.5.0
 */
@ApiModel(value = "FileFormat", description = "This describes an input or output file format that is associated with an entry in the dockstore")
@Entity
@Table(name = "fileformat")
@NamedQueries({
        @NamedQuery(name = "io.dockstore.webservice.core.FileFormat.findByFileFormatValue", query = "SELECT l FROM FileFormat l WHERE l.value = :fileformatValue")
})

public class FileFormat implements Comparable<FileFormat> {

    private static final Comparator<String> NULL_SAFE_STRING_COMPARATOR = Comparator
            .nullsFirst(String::compareToIgnoreCase);

    private static final Comparator<FileFormat> FILE_FORMAT_COMPARATOR = Comparator
            .comparing(FileFormat::getValue, NULL_SAFE_STRING_COMPARATOR)
            .thenComparing(FileFormat::getValue, NULL_SAFE_STRING_COMPARATOR);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "Implementation specific ID for file format in this web service", position = 0)
    private long id;

    @Column(unique = true, columnDefinition = "text")
    @ApiModelProperty(value = "String representation of the file format", required = true, position = 1)
    private String value;

    // database timestamps
    @Column(updatable = false)
    @CreationTimestamp
    private Timestamp dbCreateDate;

    @Column()
    @UpdateTimestamp
    private Timestamp dbUpdateDate;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final FileFormat other = (FileFormat)obj;
        return Objects.equal(value, other.value);
    }

    @Override
    public int compareTo(FileFormat that) {
        return FILE_FORMAT_COMPARATOR.compare(this, that);
    }
}
