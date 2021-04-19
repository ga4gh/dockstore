package io.dockstore.webservice.resources;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.Service;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.http.client.HttpClient;
import org.hibernate.SessionFactory;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "workflows", description = ResourceConstants.WORKFLOWS)
public class ServiceResource extends AbstractWorkflowResource<Service> {

    public ServiceResource(HttpClient client, SessionFactory sessionFactory, EntryResource entryResource, DockstoreWebserviceConfiguration configuration) {
        super(client, sessionFactory, entryResource, configuration);
    }
}
