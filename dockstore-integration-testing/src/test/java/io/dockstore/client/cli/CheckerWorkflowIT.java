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

package io.dockstore.client.cli;

import java.util.ArrayList;
import java.util.List;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.Registry;
import io.dockstore.common.SourceControl;
import io.dockstore.common.WorkflowTest;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.Entry;
import io.swagger.client.model.PublishRequest;
import io.swagger.client.model.Workflow;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Confidential tests for checker workflows
 *
 * @author agduncan
 */
@Category({ ConfidentialTest.class, WorkflowTest.class })
public class CheckerWorkflowIT extends BaseIT {

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
    }

    /**
     * This tests the process of adding a checker workflow to a CWL tool entry and that some calls to the tool will also trigger the calls to the checker
     * - Refresh tool should refresh checker
     * - Publish tool should publish checker
     * - Unpublish tool should unpublish checker
     *
     * @throws ApiException
     */
    @Test
    public void testCWLToolAddCheckerRefreshPublishUnpublish() throws ApiException {
        // Setup for test
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        ContainersApi containersApi = new ContainersApi(webClient);

        final PublishRequest publishRequest = CommonTestUtilities.createPublishRequest(true);
        final PublishRequest unpublishRequest = CommonTestUtilities.createPublishRequest(false);

        // Manually register a tool
        DockstoreTool newTool = new DockstoreTool();
        newTool.setMode(DockstoreTool.ModeEnum.MANUAL_IMAGE_PATH);
        newTool.setName("my-md5sum");
        newTool.setGitUrl("git@github.com:DockstoreTestUser2/md5sum-checker.git");
        newTool.setDefaultDockerfilePath("/md5sum/Dockerfile");
        newTool.setDefaultCwlPath("/md5sum/md5sum-tool.cwl");
        newTool.setRegistryString(Registry.QUAY_IO.getDockerPath());
        newTool.setNamespace("dockstoretestuser2");
        newTool.setToolname("altname");
        newTool.setPrivateAccess(false);
        newTool.setDefaultCWLTestParameterFile("/testcwl.json");
        DockstoreTool githubTool = containersApi.registerManual(newTool);

        // Refresh the workflow
        DockstoreTool refresh = containersApi.refresh(githubTool.getId());

        // Check if the output file format is added to the file_formats property
        assertTrue(refresh.getWorkflowVersions().stream().anyMatch(tag -> tag.getOutputFileFormats().stream()
            .anyMatch(fileFormat -> fileFormat.getValue().equals("http://edamontology.org/data_3671"))));
        assertTrue(refresh.getOutputFileFormats().stream()
                .anyMatch(fileFormat -> fileFormat.getValue().equals("http://edamontology.org/data_3671")));
        assertTrue(refresh.getWorkflowVersions().stream().anyMatch(
            tag -> tag.getInputFileFormats().stream().anyMatch(fileFormat -> fileFormat.getValue().equals("file://fakeFileFormat"))));
        assertTrue(refresh.getInputFileFormats().stream().anyMatch(fileFormat -> fileFormat.getValue().equals("file://fakeFileFormat")));


        // Add checker workflow
        workflowApi.registerCheckerWorkflow("/checker-workflow-wrapping-tool.cwl", githubTool.getId(), "cwl", null);

        // Refresh workflow
        DockstoreTool refreshedEntry = containersApi.refresh(githubTool.getId());

        // Refreshing the entry also calls the update user metadata function which populates the user profile
        assertTrue("There should be at least one user of the workflow", refreshedEntry.getUsers().size() > 0);
        refreshedEntry.getUsers().forEach(entryUser -> {
            Assert.assertNotEquals("refresh() endpoint should have user profiles", null, entryUser.getUserProfiles());
        });

        // Checker workflow should refresh
        final long count = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("The checker workflow should be full, there are " + count, 1, count);

        // Checker workflow should have the same test path as entry
        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from workflow w, entry_defaultpaths ed where ed.path = '/testcwl.json' and w.id = ed.entry_id", long.class);
        assertEquals("The checker workflow should have the correct default test path /testcwl.json, there are " + count2, 1, count2);

        // Checker workflow should have the correct workflow path
        final long count3 = testingPostgres.runSelectStatement(
            "select count(*) from workflow where sourcecontrol = 'github.com' and organization = 'DockstoreTestUser2' and repository = 'md5sum-checker' and workflowname like 'altname%' and giturl = 'git@github.com:DockstoreTestUser2/md5sum-checker.git'",
            long.class);
        assertEquals("The checker workflow should have the correct path information, there are " + count3, 1, count3);

        // Publish workflow
        final long count4 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("No workflows should be published, there are " + count4, 0, count4);

        final long count5 = testingPostgres.runSelectStatement("select count(*) from tool where ispublished = true", long.class);
        assertEquals("No tools should be published, there are " + count5, 0, count5);

        containersApi.publish(githubTool.getId(), publishRequest);

        // Checker workflow should publish
        final long count6 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("The checker workflow should be published, there are " + count6, 1, count6);

        final long count7 = testingPostgres.runSelectStatement("select count(*) from tool where ispublished = true", long.class);
        assertEquals("the tool should be published, there are " + count7, 1, count7);

        // Unpublish workflow
        containersApi.publish(githubTool.getId(), unpublishRequest);

        // Checker workflow should unpublish
        final long count8 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("Checker workflow should not be published, there are " + count8, 0, count8);

        final long count9 = testingPostgres.runSelectStatement("select count(*) from tool where ispublished = true", long.class);
        assertEquals("the tool should not be published, there are " + count9, 0, count9);

        try {
            workflowApi.publish(refreshedEntry.getCheckerId(), publishRequest);
            fail("Should not be able to directly publish the checker");
        } catch (ApiException e) {
            assertEquals(HttpStatus.SC_BAD_REQUEST, e.getCode());
        }

        try {
            workflowApi.restub(refreshedEntry.getCheckerId());
            fail("Should not be able to restub the checker");
        } catch (ApiException e) {
            assertEquals(HttpStatus.SC_BAD_REQUEST, e.getCode());
        }
    }

    /**
     * This series of tests for
     * should be able to refresh all or the organization when a checker stub is present without a failure (constraints issue from #1405)
     */

    @Test
    public void testCheckerWorkflowAndRefreshIssueByAll() {
        testCheckerWorkflowAndRefresh(true, true);
    }

    @Test
    public void testCheckerWorkflowAndRefreshIssueByOrganization() {
        testCheckerWorkflowAndRefresh(true, false);
    }

    @Test
    public void testCheckerWorkflowAndRefreshIssueByAllToolVersion() {
        testCheckerWorkflowAndRefresh(false, true);
    }

    @Test
    public void testCheckerWorkflowAndRefreshIssueByOrganizationToolVersion() {
        testCheckerWorkflowAndRefresh(false, false);
    }

    private void testCheckerWorkflowAndRefresh(boolean workflow, boolean all) {
        // Setup for test
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        ContainersApi containersApi = new ContainersApi(webClient);

        long baseEntryId;
        if (workflow) {

            // Manually register a workflow
            Workflow githubWorkflow = workflowApi
                .manualRegister("github", "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.cwl", "", "cwl", "/testcwl.json");
            Assert.assertEquals("Should be able to get license after manual register", "Apache License 2.0", githubWorkflow.getLicenseInformation().getLicenseName());
            // Clear license name to mimic old workflow that does not have a license associated with it
            testingPostgres.runUpdateStatement("update workflow set licensename=null");
            Workflow refreshedWorkflow = workflowApi.refresh(githubWorkflow.getId(), false);
            Assert.assertEquals("Should be able to get license after refresh", "Apache License 2.0", refreshedWorkflow.getLicenseInformation().getLicenseName());
            // Refresh the workflow
            baseEntryId = refreshedWorkflow.getId();
        } else {
            // Manually register a tool
            DockstoreTool newTool = new DockstoreTool();
            newTool.setMode(DockstoreTool.ModeEnum.MANUAL_IMAGE_PATH);
            newTool.setName("my-md5sum");
            newTool.setGitUrl("git@github.com:DockstoreTestUser2/md5sum-checker.git");
            newTool.setDefaultDockerfilePath("/md5sum/Dockerfile");
            newTool.setDefaultCwlPath("/md5sum/md5sum-tool.cwl");
            newTool.setRegistryString(Registry.QUAY_IO.getDockerPath());
            newTool.setNamespace("dockstoretestuser2");
            newTool.setToolname("altname");
            newTool.setPrivateAccess(false);
            newTool.setDefaultCWLTestParameterFile("/testcwl.json");
            DockstoreTool githubTool = containersApi.registerManual(newTool);

            // Refresh the workflow
            DockstoreTool refresh = containersApi.refresh(githubTool.getId());
            baseEntryId = refresh.getId();
        }

        // Add checker workflow
        final Entry checkerWorkflowBase = workflowApi
            .registerCheckerWorkflow("/checker-workflow-wrapping-workflow.cwl", baseEntryId, "cwl", null);
        final Workflow stubCheckerWorkflow = workflowApi.getWorkflow(checkerWorkflowBase.getCheckerId(), null);
        assertSame(stubCheckerWorkflow.getMode(), Workflow.ModeEnum.STUB);

        // should be able to refresh all or the organization when a checker stub is present without a failure (constraints issue from #1405)
        List<Workflow> workflows = new ArrayList<>();
        workflows.add(workflowApi
                .manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-wdl", "/dockstore.wdl", "",
                        DescriptorLanguage.WDL.getShortName(), ""));
        workflows.add(workflowApi
                .manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-2", "/dockstore.wdl", "",
                        DescriptorLanguage.WDL.getShortName(), ""));
        workflows.add(workflowApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/ampa-nf", "/nextflow.config", "",
                DescriptorLanguage.NEXTFLOW.getShortName(), ""));
        workflows.add(workflowApi
                .manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl", "/test.json"));
        if (all) {
            for (Workflow workflowItem : workflows) {
                workflowApi.refresh(workflowItem.getId(), false);
            }
        } else {
            for (Workflow workflowItem : workflows) {
                if (workflowItem.getOrganization().equalsIgnoreCase(stubCheckerWorkflow.getOrganization())) {
                    workflowApi.refresh(workflowItem.getId(), false);
                }
            }
        }
    }

    /**
     * This tests the process of adding a checker workflow to a CWL workflow entry and that some calls to the workflow will also trigger the calls to the checker
     * - Refresh workflow should refresh checker
     * - Publish workflow should publish checker
     * - Unpublish workflow should unpublish checker
     *
     * @throws ApiException
     */
    @Test
    public void testCWLWorkflowAddCheckerRefreshPublishUnpublish() throws ApiException {
        // Setup for test
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        final PublishRequest publishRequest = CommonTestUtilities.createPublishRequest(true);
        final PublishRequest unpublishRequest = CommonTestUtilities.createPublishRequest(false);

        // Manually register a workflow
        Workflow githubWorkflow = workflowApi
            .manualRegister("github", "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.cwl", "altname", "cwl",
                "/testcwl.json");

        final long count = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("No workflows are in full mode, there are " + count, 0, count);

        // Refresh the workflow
        workflowApi.refresh(githubWorkflow.getId(), false);

        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("One workflow should be full, there are " + count2, 1, count2);

        // Add checker workflow
        workflowApi.registerCheckerWorkflow("/checker-workflow-wrapping-workflow.cwl", githubWorkflow.getId(), "cwl", null);

        // Refresh workflow
        Workflow refreshedEntry = workflowApi.refresh(githubWorkflow.getId(), false);

        // Should be able to download zip for first version
        Workflow checkerWorkflow = workflowApi.getWorkflow(refreshedEntry.getCheckerId(), null);
        workflowApi.getWorkflowZip(checkerWorkflow.getId(), checkerWorkflow.getWorkflowVersions().get(0).getId());

        // Refreshing the entry also calls the update user metadata function which populates the user profile
        refreshedEntry.getUsers().forEach(entryUser -> {
            Assert.assertNotEquals("refresh() endpoint should have user profiles", null, entryUser.getUserProfiles());
        });

        // Checker workflow should refresh
        final long count3 = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("Two workflows should be full (one being the checker), there are " + count3, 2, count3);

        // Checker workflow should have the same test path as entry
        final long count4 = testingPostgres.runSelectStatement(
            "select count(*) from workflow w, entry_defaultpaths ed where ed.path = '/testcwl.json' and w.id = ed.entry_id", long.class);
        assertEquals("There should be two workflows with default test parameter file path of /testcwl.json, there are " + count4, 2,
            count4);

        // Checker workflow should have the correct workflow path
        final long count5 = testingPostgres.runSelectStatement(
            "select count(*) from workflow where sourcecontrol = 'github.com' and organization = 'DockstoreTestUser2' and repository = 'md5sum-checker' and workflowname = 'altname_cwl_checker' and giturl = 'git@github.com:DockstoreTestUser2/md5sum-checker.git'",
            long.class);
        assertEquals("The workflow should have the correct path, there are " + count5, 1, count5);

        // Publish workflow
        final long count6 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("No workflows should be published, there are " + count6, 0, count6);
        workflowApi.publish(githubWorkflow.getId(), publishRequest);

        // Checker workflow should publish
        final long count7 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("Two workflows should be published (one being the checker), there are " + count7, 2, count7);

        // Should still be able to download zip for first version
        workflowApi.getWorkflowZip(checkerWorkflow.getId(), checkerWorkflow.getWorkflowVersions().get(0).getId());

        // Unpublish workflow
        workflowApi.publish(githubWorkflow.getId(), unpublishRequest);

        // Checker workflow should unpublish
        final long count8 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("No workflows should be published, there are " + count8, 0, count8);

        // Should not be able to directly publish the checker
        try {
            workflowApi.publish(refreshedEntry.getCheckerId(), publishRequest);
            fail("Should not reach this statement.");
        } catch (ApiException ex) {
            assertEquals(ex.getCode(), HttpStatus.SC_BAD_REQUEST);
        }
    }

    /**
     * This tests the process of adding a checker workflow to a WDL workflow entry and that some calls to the workflow will also trigger the calls to the checker
     * - Refresh workflow should refresh checker
     * - Publish workflow should publish checker
     * - Unpublish workflow should unpublish checker
     *
     * @throws ApiException
     */
    @Test
    public void testWDLWorkflowAddCheckerRefreshPublishUnpublish() throws ApiException {
        // Setup for test
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        final PublishRequest publishRequest = CommonTestUtilities.createPublishRequest(true);
        final PublishRequest unpublishRequest = CommonTestUtilities.createPublishRequest(false);

        // Manually register a workflow
        Workflow githubWorkflow = workflowApi
            .manualRegister("github", "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.wdl", "altname", "wdl",
                "/md5sum-wdl.json");

        final long count = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("No workflows are in full mode, there are " + count, 0, count);

        // Refresh the workflow
        workflowApi.refresh(githubWorkflow.getId(), false);

        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("One workflow should be full, there are " + count2, 1, count2);

        // Add checker workflow
        workflowApi.registerCheckerWorkflow("/checker-workflow-wrapping-workflow.wdl", githubWorkflow.getId(), "wdl", null);

        // Refresh workflow
        workflowApi.refresh(githubWorkflow.getId(), false);

        // Checker workflow should refresh
        final long count3 = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("Two workflows should be full (one being the checker), there are " + count3, 2, count3);

        // Checker workflow should have the same test path as entry
        final long count4 = testingPostgres.runSelectStatement(
            "select count(*) from workflow w, entry_defaultpaths ed where ed.path = '/md5sum-wdl.json' and w.id = ed.entry_id", long.class);
        assertEquals("There should be two workflows with default test parameter file path of /md5sum-wdl.json, there are " + count4, 2,
            count4);

        // Checker workflow should have the correct workflow path
        final long count5 = testingPostgres.runSelectStatement(
            "select count(*) from workflow where sourcecontrol = 'github.com' and organization = 'DockstoreTestUser2' and repository = 'md5sum-checker' and workflowname = 'altname_wdl_checker' and giturl = 'git@github.com:DockstoreTestUser2/md5sum-checker.git'",
            long.class);
        assertEquals("The workflow should have the correct path, there are " + count5, 1, count5);

        // Publish workflow
        final long count6 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("No workflows should be published, there are " + count6, 0, count6);
        workflowApi.publish(githubWorkflow.getId(), publishRequest);

        // Checker workflow should publish
        final long count7 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("Two workflows should be published (one being the checker), there are " + count7, 2, count7);

        // Unpublish workflow
        workflowApi.publish(githubWorkflow.getId(), unpublishRequest);

        // Checker workflow should unpublish
        final long count8 = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished = true", long.class);
        assertEquals("No workflows should be published, there are " + count8, 0, count8);
    }

    /**
     * Should not be able to add a checker workflow to a stub workflow (Should fail)
     *
     * @throws ApiException
     */
    @Test
    public void testAddCheckerToStub() throws ApiException {
        // Setup for test
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        // Manually register a workflow
        Workflow githubWorkflow = workflowApi
            .manualRegister("github", "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.cwl", "altname", "cwl",
                "/testcwl.json");

        final long count = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("No workflows are in full mode, there are " + count, 0, count);

        thrown.expect(ApiException.class);
        // Add checker workflow
        workflowApi.registerCheckerWorkflow("checker-workflow-wrapping-workflow.cwl", githubWorkflow.getId(), "cwl", null);
    }

    /**
     * Tests that you cannot register a tool with an underscore
     *
     * @throws ApiException
     */
    @Test
    public void testRegisteringToolWithUnderscoreInName() throws ApiException {
        // Setup for test
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        ContainersApi containersApi = new ContainersApi(webClient);

        // Make tool
        DockstoreTool newTool = new DockstoreTool();
        newTool.setMode(DockstoreTool.ModeEnum.MANUAL_IMAGE_PATH);
        newTool.setName("my-md5sum");
        newTool.setGitUrl("git@github.com:DockstoreTestUser2/md5sum-checker.git");
        newTool.setDefaultDockerfilePath("/md5sum/Dockerfile");
        newTool.setDefaultCwlPath("/md5sum/md5sum-tool.cwl");
        newTool.setRegistryString(Registry.QUAY_IO.getDockerPath());
        newTool.setNamespace("dockstoretestuser2");
        newTool.setToolname("_altname");
        newTool.setPrivateAccess(false);
        newTool.setDefaultCWLTestParameterFile("/testcwl.json");
        thrown.expect(ApiException.class);
        containersApi.registerManual(newTool);
    }

    /**
     * Tests that you cannot register a workflow with an underscore
     *
     * @throws ApiException
     */
    @Test
    public void testRegisteringWorkflowWithUnderscoreInName() throws ApiException {
        // Setup for test
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        // Manually register a workflow
        thrown.expect(ApiException.class);
        workflowApi.manualRegister("github", "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.cwl", "_altname", "cwl",
            "/testcwl.json");

    }

}
