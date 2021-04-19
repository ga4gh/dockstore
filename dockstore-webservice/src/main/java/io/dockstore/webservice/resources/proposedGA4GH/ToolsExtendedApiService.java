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
package io.dockstore.webservice.resources.proposedGA4GH;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import io.swagger.api.NotFoundException;

/**
 * Created by kcao on 01/03/17.
 *
 * Service which defines methods to return responses containing organization related information
 */
public abstract class ToolsExtendedApiService {
    public abstract Response toolsOrgGet(String organization, SecurityContext securityContext) throws NotFoundException;
    public abstract Response workflowsOrgGet(String organization, SecurityContext securityContext) throws NotFoundException;
    public abstract Response entriesOrgGet(String organization, SecurityContext securityContext) throws NotFoundException;
    public abstract Response organizationsGet(SecurityContext securityContext);

    public abstract Response toolsIndexGet(SecurityContext securityContext) throws NotFoundException;

    public abstract Response toolsIndexSearch(String query, MultivaluedMap<String, String> queryParameters, SecurityContext securityContext);
    @SuppressWarnings("checkstyle:parameternumber")
    public abstract Response setSourceFileMetadata(String type, String id, String versionId, String relativePath, String platform, String platformVersion, Boolean verified, String metadata);


}
