/*
 *    Copyright 2018 OICR
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
package io.dockstore.webservice;

import io.dockstore.client.cli.BaseIT;
import io.dockstore.client.cli.WorkflowIT;
import io.dockstore.common.CommonTestUtilities;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.ApiResponse;
import io.swagger.client.api.EntriesApi;
import io.swagger.client.api.ExtendedGa4GhApi;
import io.swagger.client.api.MetadataApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.Workflow;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.rules.ExpectedException;

import static io.dockstore.common.CommonTestUtilities.restartElasticsearch;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author dyuen
 */
public class SearchResourceIT extends BaseIT {

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private final String exampleESQuery = "{\"size\":201,\"_source\":{\"excludes\":[\"*.content\",\"*.sourceFiles\",\"description\",\"users\",\"workflowVersions.dirtyBit\",\"workflowVersions.hidden\",\"workflowVersions.last_modified\",\"workflowVersions.name\",\"workflowVersions.valid\",\"workflowVersions.workflow_path\",\"workflowVersions.workingDirectory\",\"workflowVersions.reference\"]},\"query\":{\"match_all\":{}}}";

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        restartElasticsearch();
    }

    /**
     * Continuously checks the elasticsearch index to see if it has the correct amount of entries
     * Increasing amount of sleep time, up to 15 seconds or so
     * @param hit   The amount of hits expected
     * @param extendedGa4GhApi  The api to get the elasticsearch results
     * @param counter   The amount of tries attempted
     */
    private void waitForIndexRefresh(int hit, ExtendedGa4GhApi extendedGa4GhApi, int counter) {
        try {
            String s = extendedGa4GhApi.toolsIndexSearch(exampleESQuery);
            // There's actually two "total", one for shards and one for hits.
            // Need to only look at the hits one
            if (!s.contains("hits\":{\"total\":{\"value\":" + hit + ",")) {
                if (counter > 5) {
                    Assert.fail(s + " does not have the correct amount of hits");
                } else {
                    long sleepTime = 1000 * counter;
                    Thread.sleep(sleepTime);
                    waitForIndexRefresh(hit, extendedGa4GhApi, counter + 1);
                }
            }
        } catch (Exception e) {
            Assert.fail("There were troubles sleeping: " + e.getMessage());
        }
    }

    @Test
    public void testSearchOperations() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        ExtendedGa4GhApi extendedGa4GhApi = new ExtendedGa4GhApi(webClient);
        EntriesApi entriesApi = new EntriesApi(webClient);
        // update the search index
        ApiResponse<Void> voidApiResponse = extendedGa4GhApi.toolsIndexGetWithHttpInfo();
        int statusCode = voidApiResponse.getStatusCode();
        Assert.assertEquals(200, statusCode);
        waitForIndexRefresh(0, extendedGa4GhApi, 0);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi
            .getWorkflowByPath(WorkflowIT.DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, null, false);
        // do targeted refresh, should promote workflow to fully-fleshed out workflow
        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);
        entriesApi.addAliases(workflow.getId(), "potatoAlias");
        workflowApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(false));
        workflowApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));
        waitForIndexRefresh(1, extendedGa4GhApi,  0);
        // after publication index should include workflow
        String s = extendedGa4GhApi.toolsIndexSearch(exampleESQuery);
        assertTrue(s + " should've contained potatoAlias", s.contains("\"aliases\":{\"potatoAlias\":{}}"));
        assertFalse(s.contains("\"aliases\":null"));
        assertTrue(s.contains(WorkflowIT.DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW));
    }

    /**
     * This tests that the elastic search health check will fail if the Docker container is down, the
     * index is not made, or the index is made but there are no results.
     */
    @Test
    public void testElasticSearchHealthCheck() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        ExtendedGa4GhApi extendedGa4GhApi = new ExtendedGa4GhApi(webClient);
        MetadataApi metadataApi = new MetadataApi(webClient);

        // Should fail with no index
        try {
            metadataApi.checkElasticSearch();
        } catch (ApiException ex) {
            assertTrue("Should fail", true);
        }

        // Update the search index
        extendedGa4GhApi.toolsIndexGet();
        waitForIndexRefresh(0, extendedGa4GhApi,  0);
        try {
            metadataApi.checkElasticSearch();
            fail("Should fail even with index because there's no hits");
        } catch (ApiException ex) {
            assertTrue(ex.getMessage().contains("Internal Server Error"));
        }

        // Register and publish workflow
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi
            .getWorkflowByPath(WorkflowIT.DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, null, false);
        // do targetted refresh, should promote workflow to fully-fleshed out workflow
        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);

        workflowApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));

        waitForIndexRefresh(1, extendedGa4GhApi,  0);

        // Should not fail since a workflow exists in index
        try {
            metadataApi.checkElasticSearch();
        } catch (ApiException ex) {
            fail("Should not fail");
        }
    }
}
