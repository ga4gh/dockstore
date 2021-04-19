package io.dockstore.webservice.helpers;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.Sets;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.Alias;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dockstore.webservice.jdbi.WorkflowVersionDAO;
import io.dockstore.webservice.resources.AliasableResourceInterface;
import io.dockstore.webservice.resources.AuthenticatedResourceInterface;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AliasHelper {

    private static final Logger LOG = LoggerFactory.getLogger(AliasHelper.class);

    private AliasHelper() {
    }

    /**
     * Finds a workflow and returns the workflow id based on a workflow version id.
     *
     * @param workflowDAO Workflow data access object
     * @param workflowVersionId the id of the workflow version
     * @return workflow or throws an exception if the workflow cannot be found
     */
    public static Workflow getWorkflow(WorkflowDAO workflowDAO, long workflowVersionId) {
        Optional<Workflow> workflow = workflowDAO.getWorkflowByWorkflowVersionId(workflowVersionId);
        if (!workflow.isPresent()) {
            LOG.error("Could not get workflow based on workflow version id " + workflowVersionId);
            throw new CustomWebApplicationException("Could not get workflow based on workflow version id " + workflowVersionId, HttpStatus.SC_NOT_FOUND);
        }
        return workflow.get();
    }

    /**
     * Gets the Workflow Version object via a Workflow Version ID
     * and checks that the Workflow exists and that the
     * user is authorized to update it
     * @param authenticatedResourceInterface interface to check users and entries
     * @param workflowDAO Workflow data access object
     * @param workflowVersionDAO Workflow Version data access object
     * @param user user authenticated to update the workflow
     * @param workflowVersionId the id of the workflow version
     * @return the workflow version
     */
    public static WorkflowVersion getAndCheckWorkflowVersionResource(AuthenticatedResourceInterface authenticatedResourceInterface, WorkflowDAO workflowDAO,
            WorkflowVersionDAO workflowVersionDAO, User user, Long workflowVersionId) {
        final WorkflowVersion workflowVersion = workflowVersionDAO.findById(workflowVersionId);
        if (workflowVersion == null) {
            LOG.error("Could not find workflow version using the workflow version id: " + workflowVersionId);
            throw new CustomWebApplicationException("Workflow version not found when searching with id: " + workflowVersionId, HttpStatus.SC_BAD_REQUEST);
        }

        Workflow workflow = getWorkflow(workflowDAO, workflowVersionId);
        authenticatedResourceInterface.checkUserCanUpdate(user, workflow);
        return workflowVersion;
    }

    /**
     * Add aliases to a Workflow Version
     * and check that they are valid before adding them:
     * Only works for owner of the entry
     * If blockFormat false, then no limit on format
     * @param authenticatedResourceInterface interface to check users and entries
     * @param workflowDAO Workflow data access object
     * @param workflowVersionDAO Workflow Version data access object
     * @param user user authenticated to issue a DOI for the workflow
     * @param id the id of the Entry
     * @param aliases a comma separated string of aliases
     * @param blockFormat if true don't allow specific formats
     * @return the Workflow Version
     */
    public static WorkflowVersion addWorkflowVersionAliasesAndCheck(AuthenticatedResourceInterface authenticatedResourceInterface, WorkflowDAO workflowDAO,
            WorkflowVersionDAO workflowVersionDAO, User user, Long id, String aliases, boolean blockFormat) {
        WorkflowVersion workflowVersion = getAndCheckWorkflowVersionResource(authenticatedResourceInterface, workflowDAO, workflowVersionDAO, user, id);
        Set<String> oldAliases = workflowVersion.getAliases().keySet();
        Set<String> newAliases = Sets.newHashSet(Arrays.stream(aliases.split(",")).map(String::trim).toArray(String[]::new));

        AliasableResourceInterface.checkAliases(newAliases, user, blockFormat);

        Set<String> duplicateAliasesToAdd = Sets.intersection(newAliases, oldAliases);
        if (!duplicateAliasesToAdd.isEmpty()) {
            String dupAliasesString = String.join(", ", duplicateAliasesToAdd);
            throw new CustomWebApplicationException("Aliases " + dupAliasesString + " already exist; please use unique aliases",
                    HttpStatus.SC_BAD_REQUEST);
        }

        newAliases.forEach(alias -> workflowVersion.getAliases().put(alias, new Alias()));
        return workflowVersion;
    }
}
