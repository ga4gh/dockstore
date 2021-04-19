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
import java.util.SortedSet;

import io.dockstore.webservice.core.FileContent;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.database.VersionVerifiedPlatform;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

/**
 * @author xliu
 */
public class VersionDAO<T extends Version> extends AbstractDAO<T> {

    private final FileContentDAO fileContentDAO;
    public VersionDAO(SessionFactory sessionFactory) {
        super(sessionFactory);
        this.fileContentDAO = new FileContentDAO(sessionFactory);
    }

    public T findById(Long id) {
        return get(id);
    }

    public long create(T tag) {
        //avoid "A different object with the same identifier value was already associated with the session" by removing duplicate file content
        SortedSet<SourceFile> sourceFiles = tag.getSourceFiles();
        for (SourceFile file : sourceFiles) {
            FileContent content = file.getFileContent() == null ? null : fileContentDAO.findById(file.getFileContent().getId());
            if (content == null) {
                content = fileContentDAO.persist(file.getFileContent());
            }
            file.setFileContent(content);
        }
        return persist(tag).getId();
    }

    public void delete(T version) {
        currentSession().delete(version);
    }

    public Version<T> findVersionInEntry(Long entryId, Long versionId) {
        return uniqueResult(this.currentSession().getNamedQuery("io.dockstore.webservice.core.Version.findVersionInEntry").setParameter("entryId", entryId).setParameter("versionId", versionId));
    }

    public List<VersionVerifiedPlatform> findEntryVersionsWithVerifiedPlatforms(Long entryId) {
        return list(this.currentSession().getNamedQuery("io.dockstore.webservice.core.database.VersionVerifiedPlatform.findEntryVersionsWithVerifiedPlatforms").setParameter("entryId", entryId));
    }

    // Currently not used for anything, will be used for paginated versions
    public long getVersionsCount(long entryId) {
        Query query = namedQuery("io.dockstore.webservice.core.Version.getCountByEntryId");
        query.setParameter("id", entryId);
        return (long)query.getSingleResult();
    }
}
