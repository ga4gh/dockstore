package io.dockstore.webservice.jdbi;

import java.sql.Timestamp;
import java.util.List;

import io.dockstore.webservice.core.DeletedUsername;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

public class DeletedUsernameDAO extends AbstractDAO<DeletedUsername> {
    public DeletedUsernameDAO(SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public DeletedUsername findById(Long id) {
        return get(id);
    }

    public long create(DeletedUsername deletedUsername) {
        return persist(deletedUsername).getId();
    }

    public void delete(DeletedUsername deletedUsername) {
        Session session = currentSession();
        session.delete(deletedUsername);
        session.flush();
    }

    public List<DeletedUsername> findByUsername(String username) {
        return list((this.currentSession().getNamedQuery("io.dockstore.webservice.core.DeletedUsername.findByUsername").setParameter("username", username)));
    }

    public DeletedUsername findNonReusableUsername(String username, Timestamp timestamp) {
        return uniqueResult(this.currentSession().getNamedQuery("io.dockstore.webservice.core.DeletedUsername.findNonReusableUsername").setParameter("username", username).setParameter("timestamp", timestamp));
    }
}
