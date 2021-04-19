/*
 *    Copyright 2021 OICR
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

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Stores deleted usernames
 *
 * @author natalieperez
 */
@ApiModel(value = "DeletedUsername", description = "Usernames of deleted Dockstore accounts")
@Entity
@Table(name = "deletedusername")
@NamedQueries({ @NamedQuery (name = "io.dockstore.webservice.core.DeletedUsername.findByUsername", query = "SELECT u FROM DeletedUsername u WHERE u.username = :username"),
        @NamedQuery (name = "io.dockstore.webservice.core.DeletedUsername.findNonReusableUsername", query = "SELECT u FROM DeletedUsername u WHERE u.username = :username AND u.dbCreateDate > :timestamp")})
public class DeletedUsername {

    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty(value = "ID for deleted username", position = 0)
    private long id;

    @Column(nullable = false)
    @ApiModelProperty(value = "Username that has been deleted. Cannot be reused for 3 years")
    private String username;

    @Column(updatable = false)
    @CreationTimestamp
    private Timestamp dbCreateDate;

    @Column
    @JsonIgnore
    @UpdateTimestamp
    private Timestamp dbUpdateDate;

    public DeletedUsername() {

    }

    public DeletedUsername(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public long getId() {
        return id;
    }

    public Timestamp getDbCreateDate() {
        return dbCreateDate;
    }

}
