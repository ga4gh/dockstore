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

import java.lang.reflect.ParameterizedType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import io.dockstore.webservice.core.CollectionEntry;
import io.dockstore.webservice.core.CollectionOrganization;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.Label;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.database.EntryLite;
import org.apache.commons.lang3.tuple.MutablePair;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author dyuen
 */
public abstract class EntryDAO<T extends Entry> extends AbstractDockstoreDAO<T> {

    private static final Logger LOG = LoggerFactory.getLogger(EntryDAO.class);

    final int registryIndex = 0;
    final int orgIndex = 1;
    final int repoIndex = 2;
    final int entryNameIndex = 3;

    private Class<T> typeOfT;

    EntryDAO(SessionFactory factory) {
        super(factory);
        /*
          ewwww, don't try this at home from https://stackoverflow.com/questions/4837190/java-generics-get-class
         */
        this.typeOfT = (Class<T>)((ParameterizedType)getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }

    public T findById(Long id) {
        return get(id);
    }

    public MutablePair<String, Entry> findEntryByPath(String path, boolean isPublished) {
        String queryString = "Entry.";
        if (isPublished) {
            queryString += "getPublishedEntryByPath";
        } else {
            queryString += "getEntryByPath";
        }

        // split path
        String[] splitPath = Tool.splitPath(path);

        // Not a valid path
        if (splitPath == null) {
            return null;
        }

        // Valid path
        String one = splitPath[registryIndex];
        String two = splitPath[orgIndex];
        String three = splitPath[repoIndex];
        String four = splitPath[entryNameIndex];

        if (four == null) {
            queryString += "NullName";
        }

        Query query = super.namedQuery(queryString);

        query.setParameter("one", one);
        query.setParameter("two", two);
        query.setParameter("three", three);

        if (four != null) {
            query.setParameter("four", four);
        }

        List<Object[]> pair = list(query);
        MutablePair<String, Entry> results = null;
        if (pair.size() > 0) {
            String type = (String)(pair.get(0))[0];
            BigInteger id = (BigInteger)(pair.get(0))[1];
            Long longId = id.longValue();
            if ("workflow".equals(type)) {
                results = new MutablePair<>("workflow", this.currentSession().get(Workflow.class, Objects.requireNonNull(longId)));
            } else {
                results = new MutablePair<>("tool", this.currentSession().get(Tool.class, Objects.requireNonNull(longId)));
            }
        }
        return results;
    }

    public long create(T entry) {
        return persist(entry).getId();
    }

    public void delete(T entry) {
        Session session = currentSession();
        session.delete(entry);
        session.flush();
    }

    public Entry<? extends Entry, ? extends Version> getGenericEntryById(long id) {
        return uniqueResult(this.currentSession().getNamedQuery("Entry.getGenericEntryById").setParameter("id", id));
    }

    public Entry<? extends Entry, ? extends Version> getGenericEntryByAlias(String alias) {
        return uniqueResult(this.currentSession().getNamedQuery("Entry.getGenericEntryByAlias").setParameter("alias", alias));
    }

    public List<CollectionOrganization> findCollectionsByEntryId(long entryId) {
        return list(this.currentSession().getNamedQuery("io.dockstore.webservice.core.Entry.findCollectionsByEntryId").setParameter("entryId", entryId));
    }

    public T findPublishedById(long id) {
        return (T)uniqueResult(
                this.currentSession().getNamedQuery("io.dockstore.webservice.core." + typeOfT.getSimpleName() + ".findPublishedById").setParameter("id", id));
    }

    public List<EntryLite> findEntryVersions(long userId) {
        return list(this.currentSession().getNamedQuery("io.dockstore.webservice.core." + typeOfT.getSimpleName() + ".getEntryLiteByUserId").setParameter("userId", userId));
    }
    public List<T> findMyEntries(long userId) {
        return list(this.currentSession().getNamedQuery("io.dockstore.webservice.core." + typeOfT.getSimpleName() + ".getEntriesByUserId").setParameter("userId", userId));
    }
    public List<T> findMyEntriesPublished(long userId) {
        return list(this.currentSession().getNamedQuery("io.dockstore.webservice.core." + typeOfT.getSimpleName() + ".getPublishedEntriesByUserId").setParameter("userId", userId));
    }

    public List<CollectionEntry> getCollectionWorkflows(long collectionId) {
        return list(this.currentSession().getNamedQuery("Entry.getCollectionWorkflows").setParameter("collectionId", collectionId));
    }

    public long getWorkflowsLength(long collectionId) {
        return (long)(this.currentSession().getNamedQuery("Entry.getWorkflowsLength").setParameter("collectionId", collectionId).getSingleResult());
    }

    public List<CollectionEntry> getCollectionServices(long collectionId) {
        return list(this.currentSession().getNamedQuery("Entry.getCollectionServices").setParameter("collectionId", collectionId));
    }

    public List<CollectionEntry> getCollectionTools(long collectionId) {
        return list(this.currentSession().getNamedQuery("Entry.getCollectionTools").setParameter("collectionId", collectionId));
    }

    public long getToolsLength(long collectionId) {
        return (long)(this.currentSession().getNamedQuery("Entry.getToolsLength").setParameter("collectionId", collectionId).getSingleResult());
    }

    public List<CollectionEntry> getCollectionWorkflowsWithVersions(long collectionId) {
        return list(this.currentSession().getNamedQuery("Entry.getCollectionWorkflowsWithVersions").setParameter("collectionId", collectionId));
    }

    public List<CollectionEntry> getCollectionServicesWithVersions(long collectionId) {
        return list(this.currentSession().getNamedQuery("Entry.getCollectionServicesWithVersions").setParameter("collectionId", collectionId));
    }

    public List<CollectionEntry> getCollectionToolsWithVersions(long collectionId) {
        return list(this.currentSession().getNamedQuery("Entry.getCollectionToolsWithVersions").setParameter("collectionId", collectionId));
    }

    public List<CollectionEntry> getCollectionEntries(long collectionId) {
        return list(this.currentSession().getNamedQuery("Entry.getCollectionEntries").setParameter("collectionId", collectionId));
    }

    public List<T> findAllPublished(String offset, Integer limit, String filter, String sortCol, String sortOrder) {
        return findAllPublished(offset, limit, filter, sortCol, sortOrder, typeOfT);
    }

    public List<T> findAllPublished(String offset, Integer limit, String filter, String sortCol, String sortOrder, Class<T> classType) {
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<T> query = criteriaQuery();
        Root<T> entry = query.from(classType != null ? classType : typeOfT);
        processQuery(filter, sortCol, sortOrder, cb, query, entry);
        query.select(entry);

        int primitiveOffset = Integer.parseInt(MoreObjects.firstNonNull(offset, "0"));
        TypedQuery<T> typedQuery = currentSession().createQuery(query).setFirstResult(primitiveOffset).setMaxResults(limit);
        return typedQuery.getResultList();
    }

    public List<T> findAllPublished() {
        return list(this.currentSession().getNamedQuery("io.dockstore.webservice.core." + typeOfT.getSimpleName() + ".findAllPublished"));
    }

    public long countAllHosted(long userid) {
        return ((BigInteger)namedQuery("Entry.hostedWorkflowCount").setParameter("userid", userid).getSingleResult()).longValueExact();
    }

    public long countAllPublished(Optional<String> filter) {
        if (filter.isEmpty()) {
            return countAllPublished();
        }
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<T> entry = query.from(typeOfT);
        processQuery(filter.get(), "", "", cb, query, entry);
        query.select(cb.count(entry));
        return currentSession().createQuery(query).getSingleResult();
    }

    private long countAllPublished() {
        return (long)this.currentSession().getNamedQuery("io.dockstore.webservice.core." + typeOfT.getSimpleName() + ".countAllPublished").getSingleResult();
    }

    public List<Label> getLabelByEntryId(long entryId) {
        return list(this.currentSession().getNamedQuery("io.dockstore.webservice.core.Entry.findLabelByEntryId").setParameter("entryId", entryId));
    }

    public List<String> getToolsDescriptorTypes(long entryId) {
        return (List<String>)this.currentSession().getNamedQuery("Entry.findToolsDescriptorTypes").setParameter("entryId", entryId)
                .getSingleResult();
    }

    public List<String> getWorkflowsDescriptorTypes(long entryId) {
        return Arrays.asList(this.currentSession().getNamedQuery("Entry.findWorkflowsDescriptorTypes").setParameter("entryId", entryId).getSingleResult().toString());
    }

    private void processQuery(String filter, String sortCol, String sortOrder, CriteriaBuilder cb, CriteriaQuery query, Root<T> entry) {
        List<Predicate> predicates = new ArrayList<>();
        if (!Strings.isNullOrEmpty(filter)) {
            // TODO: handle all search attributes that we want to hook up, this sucks since we didn't handle polymorphism quite right
            boolean toolMode = typeOfT == Tool.class;
            String nameName = toolMode ? "toolname" : "workflowName";
            String repoName = toolMode ? "name" : "repository";
            String orgName = toolMode ? "namespace" : "organization";

            predicates.add(cb.and(// get published workflows
                cb.isTrue(entry.get("isPublished")),
                // ensure we deal with null values and then do like queries on those non-null values
                cb.or(cb.and(cb.isNotNull(entry.get(nameName)), cb.like(cb.upper(entry.get(nameName)), "%" + filter.toUpperCase() + "%")), //
                    cb.and(cb.isNotNull(entry.get("author")), cb.like(cb.upper(entry.get("author")), "%" + filter.toUpperCase() + "%")), //
                    cb.and(cb.isNotNull(entry.get(repoName)), cb.like(cb.upper(entry.get(repoName)), "%" + filter.toUpperCase() + "%")), //
                    cb.and(cb.isNotNull(entry.get(orgName)), cb.like(cb.upper(entry.get(orgName)), "%" + filter.toUpperCase() + "%")))));

        } else {
            predicates.add(cb.isTrue(entry.get("isPublished")));
        }
        if (!Strings.isNullOrEmpty(sortCol)) {
            // sorting by stars is a special case since it needs a join
            if ("stars".equalsIgnoreCase(sortCol)) {
                if ("desc".equalsIgnoreCase(sortOrder)) {
                    query.orderBy(cb.desc(cb.size(entry.<Collection>get("starredUsers"))), cb.desc(entry.get("id")));
                } else {
                    query.orderBy(cb.asc(cb.size(entry.<Collection>get("starredUsers"))), cb.desc(entry.get("id")));
                }
            } else {
                Path<Object> sortPath = entry.get(sortCol);
                if (!Strings.isNullOrEmpty(sortOrder) && "desc".equalsIgnoreCase(sortOrder)) {
                    query.orderBy(cb.desc(sortPath), cb.desc(entry.get("id")));
                    predicates.add(sortPath.isNotNull());
                } else {
                    query.orderBy(cb.asc(sortPath), cb.desc(entry.get("id")));
                    predicates.add(sortPath.isNotNull());
                }
            }
        }
        query.where(predicates.toArray(new Predicate[]{}));
    }
}
