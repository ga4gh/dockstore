package io.dockstore.webservice.jdbi;

import io.dockstore.webservice.core.CloudInstance;
import io.dropwizard.hibernate.AbstractDAO;
import java.util.List;
import org.hibernate.SessionFactory;

public class CloudInstanceDAO extends AbstractDAO<CloudInstance> {
    public CloudInstanceDAO(SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public CloudInstance findById(Long id) {
        return get(id);
    }

    public long create(CloudInstance cloudInstance) {
        return persist(cloudInstance).getId();
    }

    public List<CloudInstance> findAllWithoutUser() {
        return list(namedTypedQuery("io.dockstore.webservice.core.CloudInstance.findAllWithoutUser"));
    }

    public void deleteById(Long id) {
        CloudInstance cloudInstance = get(id);
        currentSession().delete(cloudInstance);
    }
}
