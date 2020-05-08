package io.dockstore.webservice.graphqlapi;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dropwizard.hibernate.HibernateBundle;
import io.swagger.client.ApiClient;
import io.swagger.client.api.OrganizationsApi;

public class CollectionDataFetcher implements DataFetcher<io.swagger.client.model.Collection> {
    private final HibernateBundle<DockstoreWebserviceConfiguration> hibernateBundle;

    public CollectionDataFetcher(HibernateBundle<DockstoreWebserviceConfiguration> hibernateBundle, DockstoreWebserviceConfiguration configuration) {
        this.hibernateBundle = hibernateBundle;
    }

    @Override
    public io.swagger.client.model.Collection get(DataFetchingEnvironment environment) {
        Double id = environment.getArgument("collectionID");
        Double organizationId = environment.getArgument("organizationID");
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("http://localhost:8080/api");
        OrganizationsApi organizationsApi = new OrganizationsApi(apiClient);
        io.swagger.client.model.Collection collectionById = organizationsApi.getCollectionById((organizationId).longValue(), id.longValue());
        return collectionById;
    }
}
