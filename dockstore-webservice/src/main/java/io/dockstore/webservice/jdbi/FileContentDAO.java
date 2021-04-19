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

import io.dockstore.webservice.core.FileContent;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;

/**
 * @author dyuen
 */
public class FileContentDAO extends AbstractDAO<FileContent> {

    public FileContentDAO(SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public FileContent findById(String id) {
        return get(id);
    }

    @Override
    protected FileContent persist(FileContent file) throws HibernateException {
        // intercept creation of new content to de-duplicate
        if (file == null) {
            return null;
        }
        FileContent content = findById(file.getId());
        if (content == null) {
            FileContent persist = super.persist(file);
            return persist;
        }
        return content;
    }

    public String create(FileContent content) {
        return persist(content).getId();
    }
}
