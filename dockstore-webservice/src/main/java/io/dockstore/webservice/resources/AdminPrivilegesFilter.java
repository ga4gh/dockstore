package io.dockstore.webservice.resources;

import java.security.Principal;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import io.dockstore.webservice.core.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every request will be filtered through here to determine if an admin user is making the request. If it is an admin,
 * then the username, method, and endpoint path will be logged.
 */
@Provider
public class AdminPrivilegesFilter implements ContainerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(AdminPrivilegesFilter.class);

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (requestContext != null) {
            Principal principal = requestContext.getSecurityContext().getUserPrincipal();
            if (principal instanceof User) {
                User user = (User)principal;
                if (user.getIsAdmin()) {
                    String logMessage = "Admin " + user.getUsername() + " making " + requestContext.getMethod() + " request at " + requestContext.getUriInfo().getPath();
                    LOG.info(logMessage);
                }
            }
        }
    }
}
