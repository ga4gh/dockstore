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

package io.dockstore.webservice.jdbi;

import java.util.List;

import io.dockstore.webservice.core.FileContent;
import io.dockstore.webservice.core.SourceFile;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;

/**
 * @author xliu
 */
public class FileDAO extends AbstractDAO<SourceFile> {
    private final FileContentDAO fileContentDAO;

    public FileDAO(SessionFactory sessionFactory) {
        super(sessionFactory);
        this.fileContentDAO = new FileContentDAO(sessionFactory);
    }

    public SourceFile findById(Long id) {
        return get(id);
    }

    @Override
    protected SourceFile persist(SourceFile file) throws HibernateException {
        // intercept creation of new content to de-duplicate
        if (file.getFileContent() != null) {
            FileContent content = fileContentDAO.findById(file.getFileContent().getId());
            if (content == null) {
                content = fileContentDAO.persist(file.getFileContent());
            }
            file.setFileContent(content);
        }
        SourceFile persist = super.persist(file);
        return findById(persist.getId());
    }

    public long create(SourceFile file) {
        return persist(file).getId();
    }

    public List<SourceFile> findSourceFilesByVersion(Long versionId) {
        return list(this.currentSession().getNamedQuery("io.dockstore.webservice.core.SourceFile.findSourceFilesForVersion").setParameter("versionId", versionId));
    }
}
