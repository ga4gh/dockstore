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

import java.io.File;
import java.io.IOException;
import java.net.URL;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.RegressionTest;
import io.dockstore.common.SlowTest;
import io.dockstore.common.SourceControl;
import io.dockstore.common.TestUtility;
import io.dropwizard.testing.ResourceHelpers;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.UsersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.Workflow;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import static io.dockstore.common.CommonTestUtilities.OLD_DOCKSTORE_VERSION;
import static io.dockstore.common.CommonTestUtilities.runOldDockstoreClient;
import static io.dockstore.common.CommonTestUtilities.runOldDockstoreClientWithSpaces;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * This test suite will have tests for the workflow mode of the old Dockstore Client.
 * Tests a variety of different CLI commands that start with 'dockstore workflow'
 * See CommonTestUtilities.OLD_DOCKSTORE_VERSION for the version of the Dockstore client used.
 * Testing Dockstore CLI 1.3.6 at the time of creation
 * @author gluu
 * @since 1.4.0
 */
@Category({ RegressionTest.class })
public class GeneralWorkflowRegressionIT extends BaseIT {
    @ClassRule
    public static TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().muteForSuccessfulTests();
    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().muteForSuccessfulTests();

    static URL url;
    static File dockstore;
    private static File md5sumJson;

    @BeforeClass
    public static void getOldDockstoreClient() throws IOException {
        TestUtility.createFakeDockstoreConfigFile();
        url = new URL("https://github.com/ga4gh/dockstore/releases/download/" + OLD_DOCKSTORE_VERSION + "/dockstore");
        dockstore = temporaryFolder.newFile("dockstore");
        FileUtils.copyURLToFile(url, dockstore);
        assertTrue(dockstore.setExecutable(true));
        url = new URL("https://raw.githubusercontent.com/DockstoreTestUser2/md5sum-checker/1.6.0/checker-input-cwl.json");
        md5sumJson = temporaryFolder.newFile("md5sum-wrapper-tool.json");
        FileUtils.copyURLToFile(url, md5sumJson);
        url = new URL("https://raw.githubusercontent.com/DockstoreTestUser2/md5sum-checker/1.6.0/md5sum.input");
        File md5sumInput = temporaryFolder.newFile("md5sum.input");
        FileUtils.copyURLToFile(url, md5sumInput);
    }

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
    }

    /**
     * This test checks that refresh all workflows (with a mix of stub and full) and refresh individual.  It then tries to publish them
     */
    @Test
    public void testRefreshAndPublishOld() {

        // refresh all
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });

        // refresh individual that is valid
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--script" });

        // refresh all
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });

        // check that valid is valid and full
        final long count = testingPostgres.runSelectStatement("select count(*) from workflow where ispublished='t'", long.class);
        assertEquals("there should be 0 published entries, there are " + count, 0, count);
        final long count2 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where valid='t'", long.class);
        assertEquals("there should be 2 valid versions, there are " + count2, 2, count2);
        final long count3 = testingPostgres.runSelectStatement("select count(*) from workflow where mode='FULL'", long.class);
        assertEquals("there should be 1 full workflows, there are " + count3, 1, count3);
        final long count4 = testingPostgres.runSelectStatement("select count(*) from workflowversion", long.class);
        assertEquals("there should be 4 versions, there are " + count4, 4, count4);

        // attempt to publish it
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "publish", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--script" });

        final long count5 = testingPostgres
                .runSelectStatement("select count(*) from workflow where ispublished='t'", long.class);
        assertEquals("there should be 1 published entry, there are " + count5, 1, count5);

        // unpublish
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "publish", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--unpub", "--script" });

        final long count6 = testingPostgres
                .runSelectStatement("select count(*) from workflow where ispublished='t'", long.class);
        assertEquals("there should be 0 published entries, there are " + count6, 0, count6);

    }

    /**
     * This test manually publishing a workflow and grabbing valid descriptor
     */
    @Test
    public void testManualPublishAndGrabWDLOld() {
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "manual_publish",
                        "--repository", "hello-dockstore-workflow", "--organization", "DockstoreTestUser2", "--git-version-control",
                        "github", "--workflow-name", "testname", "--workflow-path", "/Dockstore.wdl", "--descriptor-type", "wdl",
                        "--script" });
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "wdl", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow/testname:testBoth", "--script" });
    }

    /**
     * This tests adding and removing labels from a workflow
     */
    @Test
    public void testLabelEditingOld() {

        // Set up workflow
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "manual_publish",
                        "--repository", "hello-dockstore-workflow", "--organization", "DockstoreTestUser2", "--git-version-control",
                        "github", "--workflow-path", "/Dockstore.wdl", "--descriptor-type", "wdl", "--script" });

        // add labels
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "label", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--add", "test1", "--add",
                        "test2", "--script" });

        final long count = testingPostgres.runSelectStatement("select count(*) from entry_label", long.class);
        assertEquals("there should be 2 labels, there are " + count, 2, count);

        // remove labels
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "label", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--remove", "test1", "--add",
                        "test3", "--script" });

        final long count2 = testingPostgres.runSelectStatement("select count(*) from entry_label", long.class);
        assertEquals("there should be 2 labels, there are " + count2, 2, count2);
    }

    /**
     * This tests that a user can update a workflow version
     */
    @Test
    public void testUpdateWorkflowVersionOld() {
        // Set up DB


        // Update workflow
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "manual_publish",
                        "--repository", "hello-dockstore-workflow", "--organization", "DockstoreTestUser2", "--git-version-control",
                        "github", "--workflow-name", "testname", "--workflow-path", "/Dockstore.wdl", "--descriptor-type", "wdl",
                        "--script" });
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "version_tag", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow/testname", "--name", "master",
                        "--workflow-path", "/Dockstore2.wdl", "--hidden", "true", "--script" });

        final long count = testingPostgres.runSelectStatement(
                "select count(*) from workflowversion where name = 'master' and hidden = 't' and workflowpath = '/Dockstore2.wdl'",
                long.class);
        assertEquals("there should be 1 matching workflow version, there is " + count, 1, count);
    }

    /**
     * This tests that a restub will work on an unpublished, full workflow
     */
    @Test
    public void testRestubOld() {
        // Set up DB


        // Refresh and then restub
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--script" });
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "restub", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--script" });

        final long count = testingPostgres.runSelectStatement("select count(*) from workflowversion", long.class);
        assertEquals("there should be 0 workflow versions, there are " + count, 0, count);
    }

    /**
     * Tests that convert with valid imports will work (for WDL)
     */
    @Test
    public void testRefreshAndConvertWithImportsWDLOld() {
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "update_workflow", "--entry",
                        SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow", "--descriptor-type", "wdl",
                        "--workflow-path", "/Dockstore.wdl", "--script" });

        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--entry",
                        SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow", "--script" });
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "publish", "--entry",
                        SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow", "--script" });

        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "convert", "entry2json",
                        "--entry", SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow:wdl_import", "--script" });

    }

    /**
     * Tests that a developer can launch a WDL workflow locally, instead of getting files from Dockstore
     */
    @Test
    @Ignore("1.4.5 seems to have some legitimate issue with this WDL")
    public void testLocalLaunchWDLOld() {
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "launch", "--local-entry",
                        ResourceHelpers.resourceFilePath("wdl.wdl"), "--json", ResourceHelpers.resourceFilePath("wdl.json"), "--script" });
    }

    /**
     * Tests that a developer can launch a WDL workflow with a File input being a directory
     */
    @Test
    public void testLocalLaunchWDLWithDirOld() {
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "launch", "--local-entry",
                        ResourceHelpers.resourceFilePath("directorytest.wdl"), "--json",
                        ResourceHelpers.resourceFilePath("directorytest.json"), "--script" });
    }

    @Test
    public void testUpdateWorkflowPath() throws ApiException {
        // Set up webservice
        ApiClient webClient = WorkflowIT.getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        UsersApi usersApi = new UsersApi(webClient);
        usersApi.getUser().getId();

        Workflow githubWorkflow = workflowApi
                .manualRegister("github", "DockstoreTestUser2/test_lastmodified", "/Dockstore.cwl", "test-update-workflow", "cwl",
                        "/test.json");

        // Publish github workflow
        Workflow workflow = workflowApi.refresh(githubWorkflow.getId());

        //update the default workflow path to be hello.cwl , the workflow path in workflow versions should also be changes
        workflow.setWorkflowPath("/hello.cwl");
        workflowApi.updateWorkflowPath(githubWorkflow.getId(), workflow);
        workflowApi.refresh(githubWorkflow.getId());

        // Set up DB


        //check if the workflow versions have the same workflow path or not in the database
        final String masterpath = testingPostgres
                .runSelectStatement("select workflowpath from workflowversion where name = 'testWorkflowPath'", String.class);
        final String testpath = testingPostgres
                .runSelectStatement("select workflowpath from workflowversion where name = 'testWorkflowPath'", String.class);
        assertEquals("master workflow path should be the same as default workflow path, it is " + masterpath, "/Dockstore.cwl", masterpath);
        assertEquals("test workflow path should be the same as default workflow path, it is " + testpath, "/Dockstore.cwl", testpath);
    }

    /**
     * This tests the dirty bit attribute for workflow versions with github
     */
    @Test
    public void testGithubDirtyBitOld() {
        // Setup DB


        // refresh all
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });

        // refresh individual that is valid
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--script" });

        // Check that no versions have a true dirty bit
        final long count = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where dirtybit = true", long.class);
        assertEquals("there should be no versions with dirty bit, there are " + count, 0, count);

        // Edit workflow path for a version
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "version_tag", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--name", "master",
                        "--workflow-path", "/Dockstoredirty.cwl", "--script" });

        // There should be on dirty bit
        final long count1 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where dirtybit = true", long.class);
        assertEquals("there should be 1 versions with dirty bit, there are " + count1, 1, count1);

        // Update default cwl
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "update_workflow", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/hello-dockstore-workflow", "--workflow-path",
                        "/Dockstoreclean.cwl", "--script" });

        // There should be 3 versions with new cwl
        final long count2 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where workflowpath = '/Dockstoreclean.cwl'",
                        long.class);
        assertEquals("there should be 3 versions with workflow path /Dockstoreclean.cwl, there are " + count2, 3, count2);

    }

    /**
     * This tests the dirty bit attribute for workflow versions with bitbucket
     */
    @Test
    public void testBitbucketDirtyBitOld() {
        // Setup DB


        // refresh all
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });

        // refresh individual that is valid
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--entry",
                        SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow", "--script" });

        // Check that no versions have a true dirty bit
        final long count = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where dirtybit = true", long.class);
        assertEquals("there should be no versions with dirty bit, there are " + count, 0, count);

        // Edit workflow path for a version
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "version_tag", "--entry",
                        SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow", "--name", "master",
                        "--workflow-path", "/Dockstoredirty.cwl", "--script" });

        // There should be on dirty bit
        final long count1 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where dirtybit = true", long.class);
        assertEquals("there should be 1 versions with dirty bit, there are " + count1, 1, count1);

        // Update default cwl
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "update_workflow", "--entry",
                        SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow", "--workflow-path",
                        "/Dockstoreclean.cwl", "--script" });

        // There should be 3 versions with new cwl
        final long count2 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where workflowpath = '/Dockstoreclean.cwl'",
                        long.class);
        assertEquals("there should be 4 versions with workflow path /Dockstoreclean.cwl, there are " + count2, 4, count2);

    }

    /**
     * This is a high level test to ensure that gitlab basics are working for gitlab as a workflow repo
     */
    @Test
    @Category(SlowTest.class)
    public void testGitlab() {
        // Setup DB


        // Refresh workflow
        runOldDockstoreClient(dockstore, new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });
        runOldDockstoreClient(dockstore,new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--entry",
                SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example", "--script" });

        // Check a few things
        final long count = testingPostgres.runSelectStatement(
                "select count(*) from workflow where mode='FULL' and sourcecontrol = '" + SourceControl.GITLAB.toString() + "' and organization = 'dockstore.test.user2' and repository = 'dockstore-workflow-example'",
                long.class);
        assertEquals("there should be 1 workflow, there are " + count, 1, count);

        final long count2 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where valid='t'", long.class);
        assertEquals("there should be 2 valid version, there are " + count2, 2, count2);

        final long count3 = testingPostgres.runSelectStatement(
                "select count(*) from workflow where mode='FULL' and sourcecontrol = '" + SourceControl.GITLAB.toString() + "' and organization = 'dockstore.test.user2' and repository = 'dockstore-workflow-example'",
                long.class);
        assertEquals("there should be 1 workflow, there are " + count3, 1, count3);

        // publish
        runOldDockstoreClient(dockstore, new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "publish", "--entry",
                SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example", "--script" });
        final long count4 = testingPostgres.runSelectStatement(
                "select count(*) from workflow where mode='FULL' and sourcecontrol = '" + SourceControl.GITLAB.toString() + "' and organization = 'dockstore.test.user2' and repository = 'dockstore-workflow-example' and ispublished='t'",
                long.class);
        assertEquals("there should be 1 published workflow, there are " + count4, 1, count4);

        // Should be able to get info since it is published
        runOldDockstoreClient(dockstore, new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "info", "--entry",
                SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example", "--script" });

        // Should be able to grab descriptor
        runOldDockstoreClient(dockstore, new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "cwl", "--entry",
                SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example:master", "--script" });

        // unpublish
        runOldDockstoreClient(dockstore, new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "publish", "--entry",
                SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example", "--unpub", "--script" });
        final long count5 = testingPostgres.runSelectStatement(
                "select count(*) from workflow where mode='FULL' and sourcecontrol = '" + SourceControl.GITLAB.toString() + "' and organization = 'dockstore.test.user2' and repository = 'dockstore-workflow-example' and ispublished='t'",
                long.class);
        assertEquals("there should be 0 published workflows, there are " + count5, 0, count5);

        // change default branch
        final long count6 = testingPostgres.runSelectStatement(
                "select count(*) from workflow where sourcecontrol = '" + SourceControl.GITLAB.toString() + "' and organization = 'dockstore.test.user2' and repository = 'dockstore-workflow-example' and author is null and email is null and description is null",
                long.class);
        assertEquals("The given workflow shouldn't have any contact info", 1, count6);

        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "update_workflow", "--entry",
                        SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example", "--default-version", "test", "--script" });

        final long count7 = testingPostgres.runSelectStatement(
                "select count(*) from workflow where defaultversion = 'test' and author is null and email is null and description is null",
                long.class);
        assertEquals("The given workflow should now have contact info and description", 0, count7);

        // restub
        runOldDockstoreClient(dockstore, new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "restub", "--entry",
                SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example", "--script" });
        final long count8 = testingPostgres.runSelectStatement(
                "select count(*) from workflow where mode='STUB' and sourcecontrol = '" + SourceControl.GITLAB.toString() + "' and organization = 'dockstore.test.user2' and repository = 'dockstore-workflow-example'",
                long.class);
        assertEquals("The workflow should now be a stub", 1, count8);

        // The below does not work because default version is not set in 1.3.6 and so the client will fail
        // Convert to WDL workflow
//        runOldDockstoreClient(dockstore,
//                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "update_workflow", "--entry",
//                        SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example", "--descriptor-type", "wdl", "--script" });

        // Should now be a WDL workflow
//        final long count9 = testingPostgres
//                .runSelectStatement("select count(*) from workflow where descriptortype='wdl'", long.class);
//        Assert.assertTrue("there should be no 1 wdl workflow" + count9, count9 == 1);

    }

    /**
     * This tests manually publishing a gitlab workflow
     */
    @Test
    public void testManualPublishGitlabOld() {
        // Setup DB


        // manual publish
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "manual_publish",
                        "--repository", "dockstore-workflow-example", "--organization", "dockstore.test.user2", "--git-version-control",
                        "gitlab", "--workflow-name", "testname", "--workflow-path", "/Dockstore.wdl", "--descriptor-type", "wdl",
                        "--script" });

        // Check for one valid version
        final long count = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where valid='t'", long.class);
        assertEquals("there should be 1 valid version, there are " + count, 1, count);

        // grab wdl file
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "wdl", "--entry",
                        SourceControl.GITLAB.toString() + "/dockstore.test.user2/dockstore-workflow-example/testname:master", "--script" });

    }

    /**
     * This tests that WDL files are properly parsed for secondary WDL files
     */
    @Test
    public void testWDLWithImportsOld() {
        // Setup DB


        // Refresh all
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });

        // Update workflow to be WDL with correct path
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "update_workflow", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/test_workflow_wdl", "--descriptor-type", "wdl",
                        "--workflow-path", "/hello.wdl", "--script" });

        // Check for WDL files
        final long count = testingPostgres
                .runSelectStatement("select count(*) from sourcefile where path='helper.wdl'", long.class);
        assertEquals("there should be 1 secondary file named helper.wdl, there are " + count, 1, count);

    }

    /**
     * This tests basic concepts with workflow test parameter files
     */
    @Test
    public void testTestParameterFileOld() {
        // Setup DB


        // Refresh all
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });

        // Refresh specific
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--script" });

        // There should be no sourcefiles
        final long count = testingPostgres
                .runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        assertEquals("there should be no source files that are test parameter files, there are " + count, 0, count);

        // Update version master with test parameters
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "test_parameter", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--version", "master", "--add",
                        "test.cwl.json", "--add", "test2.cwl.json", "--add", "fake.cwl.json", "--remove", "notreal.cwl.json", "--script" });
        final long count2 = testingPostgres
                .runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        assertEquals("there should be two sourcefiles that are test parameter files, there are " + count2, 2, count2);

        // Update version with test parameters
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "test_parameter", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--version", "master", "--add",
                        "test.cwl.json", "--remove", "test2.cwl.json", "--script" });
        final long count3 = testingPostgres
                .runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        assertEquals("there should be one sourcefile that is a test parameter file, there are " + count3, 1, count3);

        // Update other version with test parameters
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "test_parameter", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--version", "wdltest", "--add",
                        "test.wdl.json", "--script" });
        final long count4 = testingPostgres
                .runSelectStatement("select count(*) from sourcefile where type='CWL_TEST_JSON'", long.class);
        assertEquals("there should be two sourcefiles that are cwl test parameter files, there are " + count4, 2, count4);

        // Restub
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "restub", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--script" });

        // Change to WDL
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "update_workflow", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--descriptor-type", "wdl",
                        "--workflow-path", "Dockstore.wdl", "--script" });

        // Should be no sourcefiles
        final long count5 = testingPostgres
                .runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        assertEquals("there should be no source files that are test parameter files, there are " + count5, 0, count5);

        // Update version wdltest with test parameters
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "test_parameter", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--version", "wdltest", "--add",
                        "test.wdl.json", "--script" });
        final long count6 = testingPostgres
                .runSelectStatement("select count(*) from sourcefile where type='WDL_TEST_JSON'", long.class);
        assertEquals("there should be one sourcefile that is a wdl test parameter file, there are " + count6, 1, count6);
    }

    /**
     * This tests that you can verify and unverify a workflow
     * This currently fails
     */
    @Test
    public void testVerifyOld() {
        // Setup DB


        // Versions should be unverified
        final long count = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where verified='true'", long.class);
        assertEquals("there should be no verified workflowversions, there are " + count, 0, count);

        // Refresh workflows
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });

        // Refresh workflow
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--script" });

        // Verify workflowversion
        runOldDockstoreClientWithSpaces(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "verify", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--verified-source",
                        "docker testing group", "--version", "master", "--script" });

        // Version should be verified
        final long count2 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where verified='true' and verifiedSource='docker testing group'",
                        long.class);

        // Update workflowversion to have new verified source
        runOldDockstoreClientWithSpaces(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "verify", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--verified-source",
                        "docker testing group2", "--version", "master", "--script" });

        // Version should have new verified source
        final long count3 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where verified='true' and verifiedSource='docker testing group2'",
                        long.class);
        assertEquals("there should be one verified workflowversion, there are " + count3, 1, count3);

        // Verify another version
        runOldDockstoreClientWithSpaces(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "verify", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--verified-source",
                        "docker testing group", "--version", "wdltest", "--script" });

        // Version should be verified
        final long count4 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where verified='true'", long.class);
        assertEquals("there should be two verified workflowversions, there are " + count4, 2, count4);

        // Unverify workflowversion
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "verify", "--entry",
                        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/parameter_test_workflow", "--unverify", "--version",
                        "master", "--script" });

        // Workflowversion should be unverified
        final long count5 = testingPostgres
                .runSelectStatement("select count(*) from workflowversion where verified='true'", long.class);
        assertEquals("there should be one verified workflowversion, there are " + count5, 1, count5);
    }

    /**
     * This tests that you can refresh user data by refreshing a workflow
     * ONLY WORKS if the current user in the database dump has no metadata, and on Github there is metadata (bio, location)
     * If the user has metadata, test will pass as long as the user's metadata isn't the same as Github already
     */
    @Test
    public void testRefreshingUserMetadataOld() {
        // Setup database


        // Refresh all workflows
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "refresh", "--script" });

        // TODO: bizarrely, the new GitHub Java API library doesn't seem to handle bio
        // final long count = testingPostgres.runSelectStatement("select count(*) from enduser where location='Toronto' and bio='I am a test user'", long.class);
        final long count = testingPostgres.runSelectStatement("select count(*) from user_profile where location='Toronto'", long.class);
        assertEquals("One user should have this info now, there are  " + count, 1, count);
    }

    /**
     * Tests that the workflow can be manually registered (and published) and then launched once the json and input file is attained
     *
     */
    @Test
    public void testActualWorkflowLaunch() {
        // manual publish the workflow
        runOldDockstoreClient(dockstore,
                new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "manual_publish",
                        "--repository", "md5sum-checker", "--organization", "DockstoreTestUser2", "--git-version-control", "github",
                        "--workflow-name", "testname", "--workflow-path", "/checker-workflow-wrapping-tool.cwl", "--descriptor-type", "cwl",
                        "--script" });
        // launch the workflow
        String[] commandArray = { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "workflow", "launch", "--entry",
                "github.com/DockstoreTestUser2/md5sum-checker/testname", "--json", md5sumJson.getAbsolutePath(), "--script" };
        ImmutablePair<String, String> stringStringImmutablePair = runOldDockstoreClient(dockstore, commandArray);
        assertTrue("Final process status was not a success",
                (stringStringImmutablePair.getLeft().contains("Final process status is success")));
        assertTrue("Final process status was not a success",
                (stringStringImmutablePair.getRight().contains("Final process status is success")));

    }
}
