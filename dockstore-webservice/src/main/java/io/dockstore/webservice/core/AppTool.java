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

import io.dockstore.common.EntryType;
import io.swagger.annotations.ApiModel;
import javax.persistence.Entity;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

@ApiModel(value = "AppTool", description = "This describes one app tool in dockstore as a special degenerate case of a workflow", parent = Workflow.class)
@Entity
@Table(name = "apptool")
@NamedQueries({
    @NamedQuery(name = "io.dockstore.webservice.core.AppTool.findAllPublished", query = "SELECT a FROM AppTool a WHERE a.isPublished = true")
})
public class AppTool extends Workflow {

    @Override
    public EntryType getEntryType() {
        return EntryType.APPTOOL;
    }

    @Override
    public Entry getParentEntry() {
        return null;
    }

    @Override
    public void setParentEntry(Entry parentEntry) {
        throw new UnsupportedOperationException("AppTool cannot be a checker workflow");
    }

    @Override
    public boolean isIsChecker() {
        return false;
    }

    @Override
    public void setIsChecker(boolean isChecker) {
        throw new UnsupportedOperationException("AppTool cannot be a checker workflow");
    }

    public Event.Builder getEventBuilder() {
        return new Event.Builder().withAppTool(this);
    }
}
