package io.dockstore.client.cli;

import java.util.List;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.SourceControl;
import io.dockstore.openapi.client.ApiClient;
import io.dockstore.openapi.client.ApiException;
import io.dockstore.openapi.client.api.EntriesApi;
import io.dockstore.openapi.client.api.UsersApi;
import io.dockstore.openapi.client.api.WorkflowsApi;
import io.dockstore.openapi.client.model.User;
import io.dockstore.openapi.client.model.Workflow;
import io.dockstore.openapi.client.model.WorkflowVersion;
import io.dockstore.webservice.resources.EntryResource;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;

import static org.junit.Assert.fail;

public class EntryResourceIT extends BaseIT {
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
    }

    /**
     * Tests that exporting to ORCID does not work for entries or versions without DOI
     */
    @Test
    public void testOrcidExport() {
        ApiClient client = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        EntriesApi entriesApi = new EntriesApi(client);
        UsersApi usersApi = new UsersApi(client);
        User user = usersApi.getUser();
        WorkflowsApi workflowsApi = new WorkflowsApi(client);
        workflowsApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-wdl", "/dockstore.wdl", "",
                DescriptorLanguage.WDL.getShortName(), "");

        List<Workflow> workflows = usersApi.userWorkflows(user.getId());
        Long workflowId = workflows.get(0).getId();
        workflowsApi.refresh1(workflowId, false);
        Assert.assertTrue(workflows.size() > 0);
        Workflow workflow = workflowsApi.getWorkflow(workflowId, null);
        List<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
        Long workflowVersionId = workflowVersions.get(0).getId();

        try {
            entriesApi.exportToORCID(workflowId, null);
            fail("Should not have been able to export an entry without DOI concept URL");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_BAD_REQUEST, e.getCode());
            Assert.assertEquals(EntryResource.ENTRY_NO_DOI_ERROR_MESSAGE, e.getMessage());
        }
        testingPostgres.runUpdateStatement("update workflow set conceptDOI='dummy'");
        try {
            entriesApi.exportToORCID(workflowId, workflowVersionId);
            fail("Should not have been able to export a version without DOI URL");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_BAD_REQUEST, e.getCode());
            Assert.assertEquals(EntryResource.VERSION_NO_DOI_ERROR_MESSAGE, e.getMessage());
        }
        try {
            entriesApi.exportToORCID(workflowId, workflowVersionId + 1);
            fail("Should not have been able to export a version that doesn't belong to the entry");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_BAD_REQUEST, e.getCode());
            Assert.assertEquals(EntryResource.VERSION_NOT_BELONG_TO_ENTRY_ERROR_MESSAGE, e.getMessage());
        }

    }
}
