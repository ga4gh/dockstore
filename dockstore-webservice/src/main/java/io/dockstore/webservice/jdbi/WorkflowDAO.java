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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.persistence.EntityGraph;
import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import io.dockstore.common.SourceControl;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.SourceControlConverter;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.database.TrsTool;
import org.apache.http.HttpStatus;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author dyuen
 */
public class WorkflowDAO extends EntryDAO<Workflow> {

    private static final String WORKFLOW_NAME = "workflowName";
    private static final String IS_PUBLISHED = "isPublished";
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowDAO.class);

    public WorkflowDAO(SessionFactory factory) {
        super(factory);
    }

    /**
     * Finds all workflows with the given path (ignores workflow name)
     * When findPublished is true, will only look at published workflows
     *
     * @param path
     * @param findPublished
     * @return A list of workflows with the given path
     */
    public List<Workflow> findAllByPath(String path, boolean findPublished) {
        String[] splitPath = Workflow.splitPath(path);

        // Not a valid path
        if (splitPath == null) {
            return null;
        }

        // Valid path
        String sourcecontrol = splitPath[registryIndex];
        String organization = splitPath[orgIndex];
        String repository = splitPath[repoIndex];

        // Create full query name
        String fullQueryName = "io.dockstore.webservice.core.Workflow.";

        if (findPublished) {
            fullQueryName += "findPublishedByPath";
        } else {
            fullQueryName += "findByPath";
        }

        SourceControlConverter converter = new SourceControlConverter();
        // Create query
        Query query = namedQuery(fullQueryName)
            .setParameter("sourcecontrol", converter.convertToEntityAttribute(sourcecontrol))
            .setParameter("organization", organization)
            .setParameter("repository", repository);

        return list(query);
    }

    /**
     * Finds the workflows matching the given workflow path. A service and
     * a BioWorkflow can have the same workflow path, which is why this returns
     * a List.
     * When findPublished is true, will only look at published workflows
     *
     * @param path
     * @param findPublished
     * @return Workflow matching the path
     */
    public List<Workflow> findByPath(String path, boolean findPublished) {
        String[] splitPath = Workflow.splitPath(path);

        // Not a valid path
        if (splitPath == null) {
            return Collections.emptyList();
        }

        // Valid path
        String sourcecontrol = splitPath[registryIndex];
        String organization = splitPath[orgIndex];
        String repository = splitPath[repoIndex];
        String workflowname = splitPath[entryNameIndex];


        // Create full query name
        String fullQueryName = "io.dockstore.webservice.core.Workflow.";

        if (splitPath[entryNameIndex] == null) {
            if (findPublished) {
                fullQueryName += "findPublishedByWorkflowPathNullWorkflowName";
            } else {
                fullQueryName += "findByWorkflowPathNullWorkflowName";
            }

        } else {
            if (findPublished) {
                fullQueryName += "findPublishedByWorkflowPath";
            } else {
                fullQueryName += "findByWorkflowPath";
            }
        }

        SourceControlConverter converter = new SourceControlConverter();

        // Create query
        Query query = namedQuery(fullQueryName)
            .setParameter("sourcecontrol", converter.convertToEntityAttribute(sourcecontrol))
            .setParameter("organization", organization)
            .setParameter("repository", repository);

        if (splitPath[entryNameIndex] != null) {
            query.setParameter("workflowname", workflowname);
        }

        return list(query);
    }

    /**
     * Finds a BioWorkflow or Service matching the given path.
     *
     * Initial implementation currently calls findByPath and filters the result; would ideally do it as a query with
     * no filtering instead.
     *
     * @param path
     * @param findPublished
     * @param clazz
     * @param <T>
     * @return
     */
    public <T extends Workflow> Optional<T> findByPath(String path, boolean findPublished, Class<T> clazz) {
        final List<Workflow> workflows = findByPath(path, findPublished);
        final List<T> filteredWorkflows  = workflows.stream()
                .filter(workflow -> workflow.getClass().equals(clazz))
                .map(clazz::cast)
                .collect(Collectors.toList());
        if (filteredWorkflows.size() > 1) {
            // DB constraints should never let this happen, I think
            throw new CustomWebApplicationException("Entries with the same path exist", HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
        return filteredWorkflows.size() == 1 ? Optional.of(filteredWorkflows.get(0)) : Optional.empty();
    }

    public List<Workflow> findByPaths(List<String> paths, boolean findPublished) {
        List<Predicate> predicates = new ArrayList<>();
        SourceControlConverter converter = new SourceControlConverter();

        // Create dynamic query using a CriteriaBuilder instance for all paths in the given list of strings
        CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        CriteriaQuery<Workflow> q = cb.createQuery(Workflow.class);
        Root<Workflow> entry = q.from(Workflow.class);

        for (String path : paths) {
            String[] splitPath = Workflow.splitPath(path);

            if (splitPath == null) {
                continue;
            }

            String sourcecontrol = splitPath[registryIndex];
            String organization = splitPath[orgIndex];
            String repository = splitPath[repoIndex];
            String workflowname = splitPath[entryNameIndex];

            Predicate predicate;
            if (splitPath[entryNameIndex] == null) {
                if (findPublished) {
                    predicate = cb.and(
                            cb.isNull(entry.get(WORKFLOW_NAME)),
                            cb.isTrue(entry.get(IS_PUBLISHED))
                    );
                } else {
                    predicate = cb.isNull(entry.get(WORKFLOW_NAME));
                }
            } else {
                if (findPublished) {
                    predicate = cb.and(
                            cb.equal(entry.get(WORKFLOW_NAME), workflowname),
                            cb.isTrue(entry.get(IS_PUBLISHED))
                    );
                } else {
                    predicate = cb.equal(entry.get(WORKFLOW_NAME), workflowname);
                }
            }

            predicates.add(cb.and(
                    cb.equal(entry.get("sourceControl"), converter.convertToEntityAttribute(sourcecontrol)),
                    cb.equal(entry.get("organization"), organization),
                    cb.equal(entry.get("repository"), repository),
                    predicate)
            );
        }
        // Perform disjunctive OR over all predicates in the array
        q.where(cb.or(predicates.toArray(new Predicate[]{})));

        return list(q);
    }

    public List<Workflow> findByGitUrl(String giturl) {
        return list(namedQuery("io.dockstore.webservice.core.Workflow.findByGitUrl")
            .setParameter("gitUrl", giturl));
    }

    public List<Workflow> findPublishedByOrganization(String organization) {
        return list(namedQuery("io.dockstore.webservice.core.Workflow.findPublishedByOrganization")
            .setParameter("organization", organization));
    }

    public List<Workflow> findByOrganization(SourceControl sourceControl, String organization) {
        return list(namedQuery("io.dockstore.webservice.core.Workflow.findByOrganization")
                .setParameter("organization", organization)
                .setParameter("sourceControl", sourceControl));
    }

    public Workflow findByAlias(String alias) {
        return uniqueResult(namedQuery("io.dockstore.webservice.core.Workflow.getByAlias").setParameter("alias", alias));
    }

    /**
     * Finds a workflow based on a workflow version id. If the workflow cannot be found
     * and an exception is generated an empty optional is returned
     *
     * @param workflowVersionId the id of the workflow version
     * @return optional workflow
     */
    public Optional<Workflow> getWorkflowByWorkflowVersionId(long workflowVersionId) {
        try {
            Workflow workflow = uniqueResult(namedQuery("io.dockstore.webservice.core.Workflow.findWorkflowByWorkflowVersionId")
                    .setParameter("workflowVersionId", workflowVersionId));
            return Optional.of(workflow);
        } catch (NoResultException nre) {
            LOG.error("Could get workflow based on workflow version id " + workflowVersionId + ". Error is " + nre.getMessage(), nre);
            return Optional.empty();
        }
    }
    /**
     *
     * @param registry
     * @param organization
     * @param checker
     * @param toolname
     * @param author
     * @param description
     * @param limit
     * @return
     */
    public List<Workflow> findAllTrsPublished(final Optional<String> registry, final Optional<String> organization,
            final Optional<Boolean> checker, final Optional<String> toolname, final Optional<String> author,
            final Optional<String> description, final int limit) {
        final CriteriaBuilder cb = currentSession().getCriteriaBuilder();
        final CriteriaQuery<TrsTool> query = cb.createQuery(TrsTool.class);
        final Root<BioWorkflow> root = query.from(BioWorkflow.class);
        query.select(cb.construct(TrsTool.class, root.get("id"), root.get("sourceControl")));
        Predicate predicate = cb.isTrue(root.get("isPublished"));
        predicate = andLike(cb, predicate, root.get("organization"), organization);
        predicate = andLike(cb, predicate, root.get("workflowName"), toolname);
        predicate = andLike(cb, predicate, root.get("author"), author);
        predicate = andLike(cb, predicate, root.get("description"), description);
        if (checker.isPresent()) {
            predicate = cb.and(predicate, cb.isTrue(root.get("isChecker")));
        }
        query.where(predicate);
        final Query<TrsTool> toolQuery = currentSession().createQuery(query).setMaxResults(registry.isPresent() ? Integer.MAX_VALUE : limit);
        final List<TrsTool> tools = toolQuery.getResultList();
        final List<Long> ids = filterByRegistry(registry, tools, limit);
        final EntityGraph<?> entityGraph = currentSession().getEntityGraph("io.dockstore.webservice.core.Workflow.trs");
        return list(namedQuery("io.dockstore.webservice.core.Workflow.findByIds").setParameterList("ids", ids)
                .setHint("javax.persistence.loadgraph", entityGraph));
        // return list(namedQuery("io.dockstore.webservice.core.Workflow.findByIds").setParameterList("ids", ids));
    }

    // Filter is on SourceControl().toString(), which returns a value defined
    // in Java, different from what is stored in the DB. So yeah, don't think we
    // can do this in SQL. Fortunately, these objects should now be pretty light, and
    // I assume this filter is rarely used, so typically may not be a major impact.
    private List<Long> filterByRegistry(final Optional<String> registry, final List<TrsTool> tools, final int limit) {
        final List<TrsTool> trsTools = registry
                .map(s -> tools.stream()
                        .filter(tool -> tool.getSourceControlOrRegistry().toString().contains(s)).limit(limit)
                        .collect(Collectors.toList())).orElse(tools);
        return trsTools.stream().map(tool -> tool.getId()).collect(Collectors.toList());
    }

    private Predicate andLike(CriteriaBuilder cb, Predicate existingPredicate, Path<String> column, Optional<String> value) {
        return value.map(val -> cb.and(existingPredicate, cb.like(column, wildcardLike(val))))
                .orElse(existingPredicate);
    }

    private String wildcardLike(String value) {
        return '%' + value + '%';
    }


}
