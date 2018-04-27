package io.dockstore.webservice.jdbi;

import io.dockstore.webservice.core.FileFormat;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.SessionFactory;

/**
 * @author gluu
 * @since 27/04/18
 */
public class FileFormatDAO extends AbstractDAO<FileFormat> {

    public FileFormatDAO(SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public FileFormat findById(Long id) {
        return get(id);
    }

    public FileFormat findByLabelValue(String fileFormatValue) {
        return uniqueResult(namedQuery("io.dockstore.webservice.core.FileFormat.findByFileFormatValue").setParameter("fileformatValue", fileFormatValue));
    }

    public String create(FileFormat fileFormat) {
        String id = persist(fileFormat).getValue();
        currentSession().flush();
        return id;
    }
}
