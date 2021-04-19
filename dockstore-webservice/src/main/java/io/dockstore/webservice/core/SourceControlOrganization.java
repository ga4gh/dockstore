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

import java.util.Objects;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(value = "SourceControlOrganization", description = "This describes a source control organization")
public class SourceControlOrganization {

    @ApiModelProperty(value = "Id for the organization e.g. 1")
    private long id;
    @ApiModelProperty(value = "Name of the organization e.g. dockstore")
    private String name;

    public SourceControlOrganization(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String toString() {
        return this.getId() + ":" + this.getName();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SourceControlOrganization checksum1 = (SourceControlOrganization)o;
        return Objects.equals(id, checksum1.id) && Objects.equals(name, checksum1.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }
}
