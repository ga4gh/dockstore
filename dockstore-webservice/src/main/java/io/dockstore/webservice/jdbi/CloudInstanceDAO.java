package io.dockstore.webservice.jdbi;

import java.util.List;

import io.dockstore.webservice.core.CloudInstance;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

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
        return list((Query<CloudInstance>) namedQuery("io.dockstore.webservice.core.CloudInstance.findAllWithoutUser"));
    }

    public void deleteById(Long id) {
        CloudInstance cloudInstance = get(id);
        currentSession().delete(cloudInstance);
    }
}
