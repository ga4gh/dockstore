package io.dockstore.webservice.resources.proposedGA4GH;

import io.swagger.api.NotFoundException;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

/**
 * Created by kcao on 01/03/17.
 */
public abstract class ToolsExtendedApiService {
    public abstract Response toolsOrgGet(String organization, SecurityContext securityContext) throws NotFoundException;
    public abstract Response workflowsOrgGet(String organization, SecurityContext securityContext) throws NotFoundException;
    public abstract Response entriesOrgGet(String organization, SecurityContext securityContext) throws NotFoundException;
}
