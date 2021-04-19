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
import java.util.Objects;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.google.common.collect.ComparisonChain;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * This stores de-duplicated file content, should not be user-facing
 * @author dyuen
 * @since 1.9
 */
@Entity
@Table(name = "filecontent")
@SuppressWarnings("checkstyle:magicnumber")
public class FileContent implements Comparable<FileContent> {

    /**
     * SHA-1 for file content
     */
    @Id
    private String id;

    @Column(columnDefinition = "text")
    private String content;

    // database timestamps
    @Column(updatable = false)
    @CreationTimestamp
    private Timestamp dbCreateDate;

    @Column()
    @UpdateTimestamp
    private Timestamp dbUpdateDate;

    protected FileContent() {
    }

    public FileContent(String id, String content) {
        this.id = id;
        this.content = content;
    }


    public String getId() {
        return id;
    }


    @Override
    public int compareTo(FileContent that) {
        return ComparisonChain.start().compare(this.getContent(), that.getContent()).result();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getContent());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof FileContent)) {
            return false;
        }
        FileContent otherContent = (FileContent)obj;
        return Objects.equals(otherContent.getContent(), getContent());
    }

    /**
     * File content
     */
    public String getContent() {
        return content;
    }
}
