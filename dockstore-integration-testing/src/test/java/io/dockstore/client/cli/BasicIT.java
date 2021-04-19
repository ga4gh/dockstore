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

package io.dockstore.client.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.Registry;
import io.dockstore.common.SlowTest;
import io.dockstore.common.SourceControl;
import io.dockstore.common.ToolTest;
import io.dockstore.openapi.client.model.SourceFile;
import io.dockstore.webservice.resources.EventSearchType;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.ContainertagsApi;
import io.swagger.client.api.EventsApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.Event;
import io.swagger.client.model.StarRequest;
import io.swagger.client.model.Tag;
import io.swagger.client.model.Workflow;
import io.swagger.model.DescriptorType;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;

import static io.swagger.client.model.DockstoreTool.ModeEnum.MANUAL_IMAGE_PATH;
import static junit.framework.TestCase.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Basic confidential integration tests, focusing on publishing/unpublishing both automatic and manually added tools
 * This is important as it tests the web service with real data instead of dummy data, using actual services like Github and Quay
 *
 * @author aduncan
 */
@Category({ ConfidentialTest.class, ToolTest.class })
public class BasicIT extends BaseIT {
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate1(SUPPORT);
    }

    @SuppressWarnings("checkstyle:parameternumber")
    private DockstoreTool manualRegisterAndPublish(ContainersApi containersApi, String namespace, String name, String toolName,
        String gitUrl, String cwlPath, String wdlPath, String dockerfilePath, DockstoreTool.RegistryEnum registry, String gitReference,
        String versionName, boolean toPublish, boolean isPrivate, String email, String customDockerPath) {
        DockstoreTool newTool = new DockstoreTool();
        newTool.setNamespace(namespace);
        newTool.setName(name);
        newTool.setToolname(toolName);
        newTool.setDefaultCwlPath(cwlPath);
        newTool.setDefaultWdlPath(wdlPath);
        newTool.setDefaultDockerfilePath(dockerfilePath);
        newTool.setGitUrl(gitUrl);
        newTool.setRegistry(registry);
        newTool.setRegistryString(registry.getValue());
        newTool.setMode(MANUAL_IMAGE_PATH);
        newTool.setPrivateAccess(isPrivate);
        newTool.setToolMaintainerEmail(email);
        if (customDockerPath != null) {
            newTool.setRegistryString(customDockerPath);
        }

        if (!Registry.QUAY_IO.name().equals(registry.name())) {
            Tag tag = new Tag();
            tag.setReference(gitReference);
            tag.setName(versionName);
            tag.setDockerfilePath(dockerfilePath);
            tag.setCwlPath(cwlPath);
            tag.setWdlPath(wdlPath);
            List<Tag> tags = new ArrayList<>();
            tags.add(tag);
            newTool.setWorkflowVersions(tags);
        }

        // Manually register
        DockstoreTool tool = containersApi.registerManual(newTool);

        // Refresh
        tool = containersApi.refresh(tool.getId());

        // Publish
        if (toPublish) {
            tool = containersApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(true));
            assertTrue(tool.isIsPublished());
        }
        return tool;
    }

    @SuppressWarnings("checkstyle:parameternumber")
    private DockstoreTool manualRegisterAndPublish(ContainersApi containersApi, String namespace, String name, String toolName,
        String gitUrl, String cwlPath, String wdlPath, String dockerfilePath, DockstoreTool.RegistryEnum registry, String gitReference,
        String versionName, boolean toPublish) {
        return manualRegisterAndPublish(containersApi, namespace, name, toolName, gitUrl, cwlPath, wdlPath, dockerfilePath, registry,
            gitReference, versionName, toPublish, false, null, null);
    }

    /*
     * General-ish tests
     */

    /**
     * Tests that refresh all works, also that refreshing without a quay.io token should not destroy tools
     */
    @Test
    public void testRefresh() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        UsersApi usersApi = new UsersApi(client);

        final long startToolCount = testingPostgres.runSelectStatement("select count(*) from tool", long.class);
        // should have 0 tools to start with
        usersApi.refreshToolsByOrganization((long)1, "DockstoreTestUser", "quayandbitbucket");
        // should have a certain number of tools based on github contents
        final long secondToolCount = testingPostgres.runSelectStatement("select count(*) from tool", long.class);
        assertTrue(startToolCount <= secondToolCount && secondToolCount > 1);

        // delete quay.io token
        testingPostgres.runUpdateStatement("delete from token where tokensource = 'quay.io'");

        // refresh
        try {
            usersApi.refreshToolsByOrganization((long)1, "DockstoreTestUser", "quayandbitbucket");
            fail("Refresh should fail");
        } catch (ApiException e) {
            assertTrue("Should see error message since user has Quay tools but no Quay token.",
                e.getMessage().contains("Please add a Quay.io token"));
            // should not delete tools
            final long thirdToolCount = testingPostgres.runSelectStatement("select count(*) from tool", long.class);
            Assert.assertEquals("there should be no change in count of tools", secondToolCount, thirdToolCount);
        }
    }

    @Test
    public void testDisallowedOrgRefresh() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        UsersApi usersApi = new UsersApi(client);
        try {
            usersApi.refreshToolsByOrganization((long)1, "DockstoreTestUser", null);
            fail("Refresh by organization should fail");
        } catch (ApiException e) {
            assertTrue("Should see error message", e.getMessage().contains("Missing the required parameter"));
        }
    }

    /**
     * Tests that registration works with non-short names
     */
    @Test
    public void testRegistrationWithNonLowerCase() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);

        workflowsApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-wdl", "/dockstore.wdl", "", DescriptorLanguage.WDL.getShortName(), "");

        // refresh a specific workflow
        Workflow workflow = workflowsApi
                .getWorkflowByPath(SourceControl.GITHUB.toString() + "/DockstoreTestUser/dockstore-whalesay-wdl", "", false);
        workflow = workflowsApi.refresh(workflow.getId(), false);
        assertFalse(workflow.getWorkflowVersions().isEmpty());
    }

    @Test
    public void testRefreshToolNoVersions() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi containersApi = new ContainersApi(client);
        DockstoreTool tool = containersApi.getContainerByToolPath("quay.io/dockstoretestuser/noautobuild", null);
        tool.setGitUrl("git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        containersApi.updateContainer(tool.getId(), tool);
        containersApi.refresh(tool.getId());


        tool = containersApi.getContainerByToolPath("quay.io/dockstoretestuser/nobuildsatall", null);
        tool.setGitUrl("git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        containersApi.updateContainer(tool.getId(), tool);
        DockstoreTool refresh = containersApi.refresh(tool.getId());
        assertNull(refresh.getDefaultVersion());
    }


    /**
     * Tests that refresh workflows works, also that refreshing without a github token should not destroy workflows or their existing versions
     */
    @Test
    public void testRefreshWorkflow() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);

        workflowsApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-wdl", "/dockstore.wdl", "", DescriptorLanguage.WDL.getShortName(), "");
        workflowsApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/ampa-nf", "/nextflow.config", "", DescriptorLanguage.NEXTFLOW.getShortName(), "");


        // should have a certain number of workflows based on github contents
        final long secondWorkflowCount = testingPostgres.runSelectStatement("select count(*) from workflow", long.class);
        assertTrue("should find non-zero number of workflows", secondWorkflowCount > 0);

        // refresh a specific workflow
        Workflow workflow = workflowsApi
            .getWorkflowByPath(SourceControl.GITHUB.toString() + "/DockstoreTestUser/dockstore-whalesay-wdl", "", false);
        workflow = workflowsApi.refresh(workflow.getId(), false);

        // artificially create an invalid version
        testingPostgres.runUpdateStatement("update workflowversion set name = 'test'");
        testingPostgres.runUpdateStatement("update workflowversion set reference = 'test'");

        // refresh individual workflow
        workflow = workflowsApi.refresh(workflow.getId(), false);

        // check that the version was deleted
        final long updatedWorkflowVersionCount = testingPostgres.runSelectStatement("select count(*) from workflowversion", long.class);
        final long updatedWorkflowVersionName = testingPostgres
            .runSelectStatement("select count(*) from workflowversion where name='master'", long.class);
        assertTrue("there should be only one version", updatedWorkflowVersionCount == 1 && updatedWorkflowVersionName == 1);

        // delete quay.io token
        testingPostgres.runUpdateStatement("delete from token where tokensource = 'github.com'");

        systemExit.checkAssertionAfterwards(() -> {
            // should not delete workflows
            final long thirdWorkflowCount = testingPostgres.runSelectStatement("select count(*) from workflow", long.class);
            Assert.assertEquals("there should be no change in count of workflows", secondWorkflowCount, thirdWorkflowCount);
        });

        // should include nextflow example workflow stub
        final long nfWorkflowCount = testingPostgres
            .runSelectStatement("select count(*) from workflow where giturl like '%ampa-nf%'", long.class);
        assertTrue("should find non-zero number of next flow workflows", nfWorkflowCount > 0);

        // refresh without github token
        try {
            workflow = workflowsApi.refresh(workflow.getId(), false);
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("No GitHub or Google token found"));
        }
    }

    /**
     * Tests manually adding, updating, and removing a dockerhub tool
     */
    @Test
    public void testVersionTagDockerhub() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        ContainertagsApi toolTagsApi = new ContainertagsApi(client);

        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "dockerhubandgithub", "regular",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
        EventsApi eventsApi = new EventsApi(client);
        List<Event> events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        Assert.assertTrue("No starred entries, so there should be no events returned", events.isEmpty());
        StarRequest starRequest = new StarRequest();
        starRequest.setStar(true);
        toolsApi.starEntry(tool.getId(), starRequest);
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        Assert.assertTrue("Should not be an event for the non-tag version that was automatically created for the newly registered tool", events.isEmpty());
        // Add a tag
        Tag tag = new Tag();
        tag.setName("masterTest");
        tag.setReference("master");
        tag.setReferenceType(Tag.ReferenceTypeEnum.TAG);
        tag.setImageId("4728f8f5ce1709ec8b8a5282e274e63de3c67b95f03a519191e6ea675c5d34e8");
        List<Tag> tags = new ArrayList<>();
        tags.add(tag);

        tags = toolTagsApi.addTags(tool.getId(), tags);
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        Assert.assertEquals("Should have created an event for the new tag", 1, events.size());
        final long count = testingPostgres.runSelectStatement("select count(*) from tag where name = 'masterTest'", long.class);
        Assert.assertEquals("there should be one tag", 1, count);

        // Update tag
        Optional<Tag> optTag = tags.stream().filter(version -> Objects.equals(version.getName(), "masterTest")).findFirst();
        if (optTag.isEmpty()) {
            fail("Should have masterTest tag");
        }
        tag = optTag.get();
        tag.setHidden(true);
        tags = new ArrayList<>();
        tags.add(tag);
        toolTagsApi.updateTags(tool.getId(), tags);
        toolsApi.refresh(tool.getId());

        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from tag t, version_metadata vm where name = 'masterTest' and vm.hidden='t' and t.id = vm.id", long.class);
        Assert.assertEquals("there should be one tag", 1, count2);

        toolTagsApi.deleteTags(tool.getId(), tag.getId());

        final long count3 = testingPostgres.runSelectStatement("select count(*) from tag where name = 'masterTest'", long.class);
        Assert.assertEquals("there should be no tags", 0, count3);

    }

    /**
     * Tests the case where a manually registered quay tool matching an automated build should be treated as a separate auto build (see issue 106)
     */
    @Test
    public void testManualQuaySameAsAutoQuay() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);


        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithub", "regular",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where mode != 'MANUAL_IMAGE_PATH' and registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and toolname = 'regular'", long.class);
        Assert.assertEquals("the tool should be Auto", 1, count);
    }

    /**
     * Tests the case where a manually registered quay tool has the same path as an auto build but different git repo
     */
    @Test
    public void testManualQuayToAutoSamePathDifferentGitRepo() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithub", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay-alternate.git", "/testDir/Dockstore.cwl", "/testDir/Dockstore.wdl",
            "/testDir/Dockerfile", DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where mode = 'MANUAL_IMAGE_PATH' and registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and toolname = 'alternate'", long.class);
        Assert.assertEquals("the tool should be Manual still", 1, count);
    }

    /**
     * Tests that a manually published tool still becomes manual even after the existing similar auto tools all have toolnames (see issue 120)
     */
    @Test
    public void testManualQuayToAutoNoAutoWithoutToolname() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        existingTool.setToolname("testToolname");
        toolsApi.updateContainer(existingTool.getId(), existingTool);

        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithub", "testtool",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where mode != 'MANUAL_IMAGE_PATH' and registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and toolname = 'testtool'", long.class);
        Assert.assertEquals("the tool should be Auto", 1, count);
    }

    /**
     * Tests the case where a manually registered quay tool does not have any automated builds set up, though a manual build was run (see issue 107)
     * UPDATE: Should fail because you can't publish a tool with no valid tags
     */
    @Test
    public void testManualQuayManualBuild() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "noautobuild", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
            fail("Should not be able to publish");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Repository does not meet requirements to publish."));
        }
    }

    /**
     * Tests the case where a manually registered quay tool does not have any tags
     */
    @Test
    public void testManualQuayNoTags() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "nobuildsatall", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
            fail("Should not be able to register");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("has no tags."));
        }
    }

    /**
     * Tests that a quick registered quay tool with no autobuild can be updated to have a manually set CWL file from git (see issue 19)
     */
    @Test
    public void testQuayNoAutobuild() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/noautobuild", "");
        existingTool.setGitUrl("git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        existingTool = toolsApi.updateContainer(existingTool.getId(), existingTool);

        final long count = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'noautobuild' and giturl = 'git@github.com:DockstoreTestUser/dockstore-whalesay.git'",
            long.class);
        Assert.assertEquals("the tool should now have an associated git repo", 1, count);

        DockstoreTool existingToolNoBuild = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/nobuildsatall", "");
        existingToolNoBuild.setGitUrl("git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        existingToolNoBuild = toolsApi.updateContainer(existingToolNoBuild.getId(), existingToolNoBuild);

        final long count2 = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'nobuildsatall' and giturl = 'git@github.com:DockstoreTestUser/dockstore-whalesay.git'",
            long.class);
        Assert.assertEquals("the tool should now have an associated git repo", 1, count2);

    }

    /**
     * Tests a user trying to add a quay tool that they do not own and are not in the owning organization
     */
    @Test
    public void testAddQuayRepoOfNonOwnedOrg() {
        // Repo user isn't part of org
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstore2", "testrepo2", "testOrg",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
            fail("Should not be able to register");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("User does not own"));
        }
    }

    /**
     * Check that refreshing an existing tool with a different tool name will not throw an error
     */
    @Test
    public void testRefreshCorrectTool() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandbitbucket", "");
        toolsApi.refresh(tool.getId());

        // Register BitBucket tool
        DockstoreTool bitbucketTool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "dockerhubandbitbucket", "regular",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
        bitbucketTool = toolsApi.refresh(bitbucketTool.getId());
        assertTrue(bitbucketTool.isIsPublished());

        // Register GitHub tool
        DockstoreTool githubTool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "dockerhubandgithub", "regular",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
        githubTool = toolsApi.refresh(githubTool.getId());
        assertTrue(githubTool.isIsPublished());
    }

    /**
     * TODO: Don't use SQL statements here
     * The testing database originally has tools with tags.  This test:
     * - Deletes a tag from a certain tool from db
     * - Refreshes the tool
     * - Checks if the tag is back
     */
    @Test
    public void testRefreshAfterDeletingAVersion() {
        // Get the tool id of the entry whose path is quay.io/dockstoretestuser/quayandgithub
        final long id = testingPostgres
            .runSelectStatement("select id from tool where name = 'quayandgithub' and namespace='dockstoretestuser' and registry='quay.io'",
                long.class);

        // Check how many versions the entry has
        final long currentNumberOfTags = testingPostgres
            .runSelectStatement("select count(*) from tag where parentid = '" + id + "'", long.class);
        assertTrue("There are no tags for this tool", currentNumberOfTags > 0);

        // This grabs the first tag that belongs to the tool
        final long firstTag = testingPostgres.runSelectStatement("select id from tag where parentid = '" + id + "'", long.class);

        // Delete the version that is known
        testingPostgres.runUpdateStatement("delete from tag where parentid = '" + id + "' and id='" + firstTag + "'");

        // Double check that there is one less tag
        final long afterDeletionTags = testingPostgres
            .runSelectStatement("select count(*) from tag where parentid = '" + id + "'", long.class);
        Assert.assertEquals(currentNumberOfTags - 1, afterDeletionTags);

        // Refresh the tool
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        toolsApi.refresh(tool.getId());

        // Check how many tags there are after the refresh
        final long afterRefreshTags = testingPostgres
            .runSelectStatement("select count(*) from tag where parentid = '" + id + "'", long.class);
        Assert.assertEquals(currentNumberOfTags, afterRefreshTags);
    }

    /**
     * Tests that a git reference for a tool can include branches named like feature/...
     */
    @Test
    public void testGitReferenceFeatureBranch() {
        final long count = testingPostgres.runSelectStatement("select count(*) from tag where reference = 'feature/test'", long.class);
        Assert.assertEquals("there should be 2 tags with the reference feature/test", 2, count);
    }

    /*
     * Tests for creating tools from various image registries and git registries
     */

    /**
     * Tests auto registration of tools
     */
    @Test
    public void testAutoRegistration() {
        autoRegistrationHelper(Registry.QUAY_IO, "github.com", 5);
        autoRegistrationHelper(Registry.QUAY_IO, "bitbucket.org", 2);
        autoRegistrationHelper(Registry.QUAY_IO, "gitlab.com", 2);
    }

    private void autoRegistrationHelper(Registry imageRegistry, String gitRegistry, int expectedToolCount) {
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where  registry = '" + imageRegistry.getDockerPath() + "' and giturl like 'git@" + gitRegistry + "%'",
            long.class);
        Assert.assertEquals(
            "there should be " + expectedToolCount + " registered from " + imageRegistry + " and " + gitRegistry + ", there are " + count,
            expectedToolCount, count);
    }

    /**
     * Tests publishing tools with non-standard structure
     */
    @Test
    public void testPublishAlternateStructure() {
        publishAlternateStructureHelper("quay.io/dockstoretestuser/quayandgithubalternate");
        publishAlternateStructureHelper("quay.io/dockstoretestuser/quayandbitbucketalternate");
        publishAlternateStructureHelper("quay.io/dockstoretestuser/quayandgitlabalternate");
    }

    private void publishAlternateStructureHelper(String toolPath) {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = toolsApi.getContainerByToolPath(toolPath, "");
        try {
            toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(true));
            fail("Should not be able to publish");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Repository does not meet requirements to publish"));
        }
    }

    /**
     * Tests publishing tools with normal structure
     */
    @Test
    public void testPublishAndUnpublishTool() {
        publishAndUnpublishToolHelper("quay.io/dockstoretestuser/quayandgithub");
        publishAndUnpublishToolHelper("quay.io/dockstoretestuser/quayandbitbucket");
        publishAndUnpublishToolHelper("quay.io/dockstoretestuser/quayandgitlab");
    }

    private void publishAndUnpublishToolHelper(String toolPath) {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = toolsApi.getContainerByToolPath(toolPath, "");

        // Publish
        tool = toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(true));

        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where name = '" + toolPath.split("/")[2] + "' and ispublished='t'", long.class);
        Assert.assertEquals("there should be 1 registered", 1, count);

        // Unpublish
        tool = toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(false));

        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from tool where name = '" + toolPath.split("/")[2] + "' and ispublished='t'", long.class);
        Assert.assertEquals("there should be 0 registered", 0, count2);
    }

    /**
     * Tests publishing tools with non-standard structure
     */
    @Test
    public void testPublishAndUnpublishAlternateStructure() {
        publishAndUnpublishAlternateStructureHelper("dockstoretestuser", "quayandgithubalternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay-alternate.git");
        publishAndUnpublishAlternateStructureHelper("dockstoretestuser", "quayandbitbucketalternate",
            "git@bitbucket.org:DockstoreTestUser/quayandbitbucketalternate.git");
        publishAndUnpublishAlternateStructureHelper("dockstoretestuser", "quayandgitlabalternate",
            "git@gitlab.com:dockstore.test.user/quayandgitlabalternate.git");
    }

    private void publishAndUnpublishAlternateStructureHelper(String namespace, String name, String gitUrl) {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, namespace, name, "alternate", gitUrl, "/testDir/Dockstore.cwl",
            "/testDir/Dockstore.wdl", "/testDir/Dockerfile", DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);

        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname = 'alternate' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 1 entries, there are " + count, 1, count);

        // Unpublish
        tool = toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(false));
        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname = 'alternate' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 0 entries, there are " + count2, 0, count2);
    }

    /**
     * Tests registering a tool that already exists
     */
    @Test
    public void testManuallyRegisterDuplicate() {
        manuallyRegisterDuplicateHelper("dockstoretestuser", "quayandgithub", "git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        manuallyRegisterDuplicateHelper("dockstoretestuser", "quayandbitbucket",
            "git@bitbucket.org:DockstoreTestUser/dockstore-whalesay.git");
        manuallyRegisterDuplicateHelper("dockstoretestuser", "quayandgitlab", "git@gitlab.com:DockstoreTestUser/dockstore-whalesay.git");
    }

    private void manuallyRegisterDuplicateHelper(String namespace, String name, String gitUrl) {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, namespace, name, "", gitUrl, "/Dockstore.cwl", "/Dockstore.wdl",
                "/Dockerfile", DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
            fail("Should not be able to register");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("already exists"));
        }
    }

    /**
     * This tests that a tool's default version can be automatically set during refresh
     */
    @Test
    public void testUpdateToolDefaultVersionDuringRefresh() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithub", "regular",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
        Assert.assertEquals("manualRegisterAndPublish does a refresh, it should automatically set the default version", "latest", tool.getDefaultVersion());
        tool = toolsApi.updateToolDefaultVersion(tool.getId(), "test");
        Assert.assertEquals("Should be able to overwrite previous default version", "test", tool.getDefaultVersion());
        tool = toolsApi.refresh(tool.getId());
        Assert.assertEquals("Refresh should not have set it back to the automatic one", "test", tool.getDefaultVersion());

    }

    /**
     * Tests that a WDL file is supported
     */
    @Test
    public void testQuayGithubQuickRegisterWithWDL() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        tool = toolsApi.refresh(tool.getId());
        tool = toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(true));
        final long count = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
            + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and ispublished = 't'", long.class);
        Assert.assertEquals("the given entry should be published", 1, count);
    }


    /**
     * Tests the manual registration of a standard workflow
     */
    @Test
    public void testManualRegistration() {
        manualRegistrationHelper("dockstoretestuser", "dockerhubandgithub", "git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        manualRegistrationHelper("dockstoretestuser", "dockerhubandbitbucket",
            "git@bitbucket.org:DockstoreTestUser/dockstore-whalesay.git");
        manualRegistrationHelper("dockstoretestuser", "dockerhubandgitlab", "git@gitlab.com:dockstore.test.user/dockstore-whalesay.git");
    }

    private void manualRegistrationHelper(String namespace, String name, String gitUrl) {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, namespace, name, "regular", gitUrl, "/Dockstore.cwl", "/Dockstore.wdl",
            "/Dockerfile", DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname = 'regular' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 1 entries", 1, count);

        // Unpublish
        toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(false));
        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname = 'regular' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 0 entries", 0, count2);
    }

    /**
     * Tests the manual registration of a non-standard workflow
     */
    @Test
    public void testManualRegistrationAlternativeStructure() {
        manualRegistrationAlternativeStructureHelper("dockstoretestuser", "dockerhubandgithub",
            "git@github.com:DockstoreTestUser/dockstore-whalesay-alternate.git");
        manualRegistrationAlternativeStructureHelper("dockstoretestuser", "dockerhubandbitbucket",
            "git@bitbucket.org:DockstoreTestUser/quayandbitbucketalternate.git");
        manualRegistrationAlternativeStructureHelper("dockstoretestuser", "dockerhubandgitlab",
            "git@gitlab.com:dockstore.test.user/quayandgitlabalternate.git");
    }

    private void manualRegistrationAlternativeStructureHelper(String namespace, String name, String gitUrl) {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, namespace, name, "alternate", gitUrl, "/testDir/Dockstore.cwl",
            "/testDir/Dockstore.wdl", "/testDir/Dockerfile", DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname = 'alternate' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 1 entries", 1, count);

        // Unpublish
        toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(false));
        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname = 'alternate' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 0 entries", 0, count2);
    }

    /**
     * Tests the manual registration of an existing workflow
     */
    @Test
    public void testManuallyRegisteringDuplicates() {
        manuallyRegisteringDuplicatesHelper("dockstoretestuser", "dockerhubandgithub",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        manuallyRegisteringDuplicatesHelper("dockstoretestuser", "dockerhubandbitbucket",
            "git@bitbucket.org:DockstoreTestUser/dockstore-whalesay.git");
        manuallyRegisteringDuplicatesHelper("dockstoretestuser", "dockerhubandgitlab",
            "git@gitlab.com:dockstore.test.user/dockstore-whalesay.git");

    }

    private void manuallyRegisteringDuplicatesHelper(String namespace, String name, String gitUrl) {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, namespace, name, "regular", gitUrl, "/Dockstore.cwl", "/Dockstore.wdl",
            "/Dockerfile", DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname = 'regular' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 1 entry", 1, count);

        DockstoreTool duplicateTool = manualRegisterAndPublish(toolsApi, namespace, name, "regular2", gitUrl, "/Dockstore.cwl",
            "/Dockstore.wdl", "/Dockerfile", DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        // Unpublish the duplicate entry
        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname like 'regular%' and ispublished='t'", long.class);
        Assert.assertEquals("there should be 2 entries", 2, count2);
        toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(false));

        final long count3 = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname = 'regular2' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 1 entry", 1, count3);

        toolsApi.publish(duplicateTool.getId(), CommonTestUtilities.createPublishRequest(false));
        final long count4 = testingPostgres
            .runSelectStatement("select count(*) from tool where toolname like 'regular%' and ispublished='t'", long.class);

        Assert.assertEquals("there should be 0 entries", 0, count4);
    }

    /**
     * This tests that a tool can be updated to have default version, and that metadata is set related to the default version
     */
    @Test
    public void testSetDefaultTag() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        // Update tool with default version that has metadata
        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        Long toolId = existingTool.getId();
        DockstoreTool refresh = toolsApi.refresh(toolId);
        refresh.setDefaultVersion("master");
        existingTool = toolsApi.updateContainer(toolId, refresh);

        final long count = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
            + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and actualdefaultversion is not null", long.class);
        Assert.assertEquals("the tool should have a default version set", 1, count);

        final long count2 = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and actualdefaultversion is not null and author = 'Dockstore Test User'",
            long.class);
        Assert.assertEquals("the tool should have any metadata set (author)", 1, count2);

        // Invalidate tags
        testingPostgres.runUpdateStatement("UPDATE tag SET valid='f'");

        // Shouldn't be able to publish
        try {
            toolsApi.publish(toolId, CommonTestUtilities.createPublishRequest(true));
            fail("Should not be able to publish");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Repository does not meet requirements to publish."));
        }

    }

    /**
     * This tests that a tool cannot be manually published if it has no default descriptor paths
     * Also tests for entry not found when a broken path is used
     */
    @Test
    public void testManualPublishToolNoDescriptorPaths() {
        // Manual publish, should fail
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithubalternate", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay-alternate.git", "", "", "/testDir/Dockerfile",
                DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
            fail("Should not be able to publish");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Repository does not meet requirements to publish"));
        }
        testBrokenPath();
    }

    /**
     * This tests the dirty bit attribute for tool tags with quay
     */
    @Test
    public void testQuayDirtyBit() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        ContainertagsApi toolTagsApi = new ContainertagsApi(client);

        // Check that no tags have a true dirty bit
        final long count = testingPostgres.runSelectStatement("select count(*) from tag where dirtybit = true", long.class);
        Assert.assertEquals("there should be no tags with dirty bit, there are " + count, 0, count);

        // Edit tag cwl
        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        Optional<Tag> optTag = existingTool.getWorkflowVersions().stream().filter(version -> Objects.equals(version.getName(), "master"))
            .findFirst();
        if (optTag.isEmpty()) {
            fail("There should exist a master tag");
        }
        Tag tag = optTag.get();
        tag.setCwlPath("/Dockstoredirty.cwl");
        List<Tag> tags = new ArrayList<>();
        tags.add(tag);
        toolTagsApi.updateTags(existingTool.getId(), tags);

        // Edit another tag wdl
        optTag = existingTool.getWorkflowVersions().stream().filter(version -> Objects.equals(version.getName(), "latest")).findFirst();
        if (optTag.isEmpty()) {
            fail("There should exist a master tag");
        }
        tag = optTag.get();
        tag.setWdlPath("/Dockstoredirty.wdl");
        tags = new ArrayList<>();
        tags.add(tag);
        toolTagsApi.updateTags(existingTool.getId(), tags);

        // There should now be two true dirty bits
        final long count1 = testingPostgres.runSelectStatement("select count(*) from tag where dirtybit = true", long.class);
        Assert.assertEquals("there should be two tags with dirty bit, there are " + count1, 2, count1);

        // Update default cwl to /Dockstoreclean.cwl
        existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        existingTool.setDefaultCwlPath("/Dockstoreclean.cwl");
        existingTool = toolsApi.updateContainer(existingTool.getId(), existingTool);
        toolsApi.refresh(existingTool.getId());

        // There should only be one tag with /Dockstoreclean.cwl (both tag with new cwl and new wdl should be dirty and not changed)
        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from tag where cwlpath = '/Dockstoreclean.cwl'", long.class);
        Assert.assertEquals("there should be only one tag with the cwl path /Dockstoreclean.cwl, there are " + count2, 1, count2);
    }

    /**
     * This tests basic concepts with tool test parameter files
     */
    @Test
    public void testTestJson() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        ContainertagsApi toolTagsApi = new ContainertagsApi(client);

        // Refresh
        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/test_input_json", "");
        toolsApi.refresh(existingTool.getId());

        // Check that no WDL or CWL test files
        final long count = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be no sourcefiles that are test parameter files, there are " + count, 0, count);

        // Update tag with test parameters
        List<String> toAdd = new ArrayList<>();
        toAdd.add("test.cwl.json");
        toAdd.add("test2.cwl.json");
        toAdd.add("fake.cwl.json");

        List<String> toRemove = new ArrayList<>();
        toRemove.add("notreal.cwl.json");

        toolsApi.addTestParameterFiles(existingTool.getId(), toAdd, "cwl", "", "master");
        try {
            toolsApi.deleteTestParameterFiles(existingTool.getId(), toRemove, "cwl", "master");
            Assert.fail("Should've have thrown an error when deleting non-existent file");
        } catch (ApiException e) {
            assertEquals("Should have returned a 404 when deleting non-existent file", HttpStatus.NOT_FOUND_404, e.getCode());
        }
        toolsApi.refresh(existingTool.getId());

        final long count2 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be two sourcefiles that are test parameter files, there are " + count2, 2, count2);

        // Update tag with test parameters
        toAdd = new ArrayList<>();
        toAdd.add("test.cwl.json");

        toRemove = new ArrayList<>();
        toRemove.add("test2.cwl.json");

        toolsApi.addTestParameterFiles(existingTool.getId(), toAdd, "cwl", "", "master");
        toolsApi.deleteTestParameterFiles(existingTool.getId(), toRemove, "cwl", "master");
        toolsApi.refresh(existingTool.getId());

        final long count3 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be one sourcefile that is a test parameter file, there are " + count3, 1, count3);

        // Update tag wdltest with test parameters
        toAdd = new ArrayList<>();
        toAdd.add("test.wdl.json");

        toolsApi.addTestParameterFiles(existingTool.getId(), toAdd, "wdl", "", "wdltest");
        toolsApi.refresh(existingTool.getId());

        final long count4 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type='WDL_TEST_JSON'", long.class);
        Assert.assertEquals("there should be one sourcefile that is a wdl test parameter file, there are " + count4, 1, count4);

        toAdd = new ArrayList<>();
        toAdd.add("test.cwl.json");

        toolsApi.addTestParameterFiles(existingTool.getId(), toAdd, "cwl", "", "wdltest");
        toolsApi.refresh(existingTool.getId());
        final long count5 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type='CWL_TEST_JSON'", long.class);
        assertEquals("there should be two sourcefiles that are test parameter files, there are " + count5, 2, count5);

        // refreshing again with the default paths set should not create extra redundant test parameter files
        existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/test_input_json", "");
        existingTool.setDefaultCWLTestParameterFile("test.cwl.json");
        existingTool.setDefaultWDLTestParameterFile("test.wdl.json");
        toolsApi.updateContainer(existingTool.getId(), existingTool);
        toolsApi.refresh(existingTool.getId());
        final List<Long> testJsonCounts = testingPostgres.runSelectListStatement(
            "select count(*) from sourcefile s, version_sourcefile vs where (s.type = 'CWL_TEST_JSON' or s.type = 'WDL_TEST_JSON') and s.id = vs.sourcefileid group by vs.versionid",
            long.class);
        assertTrue("there should be at least three sets of test json sourcefiles " + testJsonCounts.size(), testJsonCounts.size() >= 3);
        for (Long testJsonCount : testJsonCounts) {
            assertTrue("there should be at most two test json for each version", testJsonCount <= 2);
        }
    }

    @Test
    public void testTestParameterOtherUsers() {
        final ApiClient correctWebClient = getWebClient(BaseIT.USER_1_USERNAME, testingPostgres);
        final ApiClient otherWebClient = getWebClient(BaseIT.OTHER_USERNAME, testingPostgres);

        ContainersApi containersApi = new ContainersApi(correctWebClient);
        final DockstoreTool containerByToolPath = containersApi.getContainerByToolPath("quay.io/dockstoretestuser/test_input_json", null);
        containersApi.refresh(containerByToolPath.getId());

        // Check that no WDL or CWL test files
        final long count = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be no sourcefiles that are test parameter files, there are " + count, 0, count);

        containersApi
            .addTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test.json"), DescriptorType.CWL.toString(), "",
                "master");

        boolean shouldFail = false;
        try {
            final ContainersApi containersApi1 = new ContainersApi(otherWebClient);
            containersApi1.addTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test2.cwl.json"),
                DescriptorType.CWL.toString(), "", "master");
        } catch (Exception e) {
            shouldFail = true;
        }
        assertTrue(shouldFail);

        containersApi
            .addTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test2.cwl.json"), DescriptorType.CWL.toString(),
                "", "master");

        final long count3 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be one sourcefile that is a test parameter file, there are " + count3, 2, count3);

        // start testing deletion
        shouldFail = false;
        try {
            final ContainersApi containersApi1 = new ContainersApi(otherWebClient);
            containersApi1.deleteTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test2.cwl.json"),
                DescriptorType.CWL.toString(), "master");
        } catch (Exception e) {
            shouldFail = true;
        }
        assertTrue(shouldFail);
        containersApi
            .deleteTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test.json"), DescriptorType.CWL.toString(),
                "master");
        containersApi.deleteTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test2.cwl.json"),
            DescriptorType.CWL.toString(), "master");

        final long count4 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be one sourcefile that is a test parameter file, there are " + count4, 0, count4);
    }

    /**
     * This tests some cases for private tools
     */
    @Test
    public void testPrivateManualPublish() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish private repo with tool maintainer email
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "tool1",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true, true, "testemail@domain.com", null);

        // The tool should be private, published and have the correct email
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail@domain.com'",
            long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count, 1, count);

        // Manual publish public repo
        DockstoreTool publicTool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "tool2",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        // NOTE: The tool should not have an associated email

        // Should not be able to convert to a private repo since it is published and has no email
        long toolId = publicTool.getId();
        publicTool.setPrivateAccess(true);
        try {
            publicTool = toolsApi.updateContainer(publicTool.getId(), publicTool);
            fail("Should not be able to update without email");
        } catch (ApiException e) {
            assertTrue(
                e.getMessage().contains("A published, private tool must have either an tool author email or tool maintainer email set up"));
        }

        // Give the tool a tool maintainer email
        publicTool = toolsApi.getContainer(toolId, "");
        publicTool.setPrivateAccess(true);
        publicTool.setToolMaintainerEmail("testemail@domain.com");
        publicTool = toolsApi.updateContainer(publicTool.getId(), publicTool);
    }

    /**
     * This tests that you can convert a published public tool to private if it has a tool maintainer email set
     */
    @Test
    public void testPublicToPrivateToPublicTool() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish public repo
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "tool1",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        // NOTE: The tool should not have an associated email

        // Give the tool a tool maintainer email and make private
        tool.setPrivateAccess(true);
        tool.setToolMaintainerEmail("testemail@domain.com");
        tool = toolsApi.updateContainer(tool.getId(), tool);

        // The tool should be private, published and have the correct email
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail@domain.com'",
            long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count, 1, count);

        // Convert the tool back to public
        tool.setPrivateAccess(false);
        tool = toolsApi.updateContainer(tool.getId(), tool);

        // Check that the tool is no longer private
        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail@domain.com'",
            long.class);
        Assert.assertEquals("no tool should be private, but there are " + count2, 0, count2);

    }

    /**
     * This tests that you can change a tool from public to private without a tool maintainer email, as long as an email is found in the descriptor
     */
    @Test
    public void testDefaultToEmailInDescriptorForPrivateRepos() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish public repo
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "tool1",
            "git@github.com:DockstoreTestUser/dockstore-whalesay-2.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        // NOTE: The tool should have an associated email

        // Make the tool private
        tool.setPrivateAccess(true);
        tool = toolsApi.updateContainer(tool.getId(), tool);
        tool = toolsApi.refresh(tool.getId());

        // The tool should be private, published and not have a maintainer email
        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail=''",
                long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count, 1, count);

        // Convert the tool back to public
        tool.setPrivateAccess(false);
        tool = toolsApi.updateContainer(tool.getId(), tool);
        tool = toolsApi.refresh(tool.getId());

        // Check that the tool is no longer private
        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail@domain.com'",
            long.class);
        Assert.assertEquals("no tool should be private, but there are " + count2, 0, count2);

        // Make the tool private but this time define a tool maintainer
        tool.setPrivateAccess(true);
        tool.setToolMaintainerEmail("testemail2@domain.com");
        tool = toolsApi.updateContainer(tool.getId(), tool);
        tool = toolsApi.refresh(tool.getId());

        // Check that the tool is no longer private
        final long count3 = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail2@domain.com'",
            long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count3, 1, count3);
    }

    /**
     * This tests that you cannot manually publish a private tool unless it has a tool maintainer email
     */
    @Test
    public void testPrivateManualPublishNoToolMaintainerEmail() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish private repo without tool maintainer email
        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true, true, null, null);
            fail("Should not be able to manually register due to missing email");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Tool maintainer email is required for private tools"));
        }

    }

    /**
     * This tests that you can manually publish a gitlab registry image
     */
    @Test
    @Category(SlowTest.class)
    public void testManualPublishGitlabDocker() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true, true, "duncan.andrew.g@gmail.com", null);

        // Check that tool exists and is published
        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where ispublished='true' and privateaccess='true'", long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count, 1, count);

    }

    /**
     * This tests that you can manually publish a private only registry (Amazon ECR), but you can't change the tool to public
     */
    @Test
    public void testManualPublishPrivateOnlyRegistry() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true, true, "duncan.andrew.g@gmail.com",
            "test.dkr.ecr.test.amazonaws.com");

        // Check that tool is published and has correct values
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and registry='test.dkr.ecr.test.amazonaws.com' and namespace = 'notarealnamespace' and name = 'notarealname'",
            long.class);
        Assert.assertEquals("one tool should be private, published and from amazon, there are " + count, 1, count);

        // Update tool to public (shouldn't work)
        tool.setPrivateAccess(false);
        try {
            toolsApi.updateContainer(tool.getId(), tool);
            fail("Should not be able to update tool to public");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The registry Amazon ECR is private only, cannot set tool to public"));
        }
    }

    /**
     * This tests that you can manually publish a private only registry (Seven Bridges), but you can't change the tool to public
     */
    @Test
    public void testManualPublishSevenBridgesTool() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.SEVEN_BRIDGES, "master", "latest", true, true, "duncan.andrew.g@gmail.com", "images.sbgenomics.com");

        // Check that tool is published and has correct values
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and registry='images.sbgenomics.com' and namespace = 'notarealnamespace' and name = 'notarealname'",
            long.class);
        assertEquals("one tool should be private, published and from seven bridges, there are " + count, 1, count);

        // Update tool to public (shouldn't work)
        tool.setPrivateAccess(false);
        try {
            toolsApi.updateContainer(tool.getId(), tool);
            fail("Should not be able to update tool to public");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The registry Seven Bridges is private only, cannot set tool to public"));
        }
    }

    /**
     * This tests that you can't manually publish a private only registry (Seven Bridges) with an incorrect registry path
     */
    @Test
    public void testManualPublishSevenBridgesToolIncorrectRegistryPath() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish correct path
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.SEVEN_BRIDGES, "master", "latest", true, true, "duncan.andrew.g@gmail.com",
            "test-images.sbgenomics.com");

        // Check that tool is published and has correct values
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and registry='test-images.sbgenomics.com' and namespace = 'notarealnamespace' and name = 'notarealname'",
            long.class);
        assertEquals("one tool should be private, published and from seven bridges, there are " + count, 1, count);

        // Manual publish incorrect path
        try {
            manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.SEVEN_BRIDGES, "master", "latest", true, true, "duncan.andrew.g@gmail.com",
                "testimages.sbgenomics.com");
            fail("Should not be able to register");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The provided registry is not valid"));
        }
    }

    /**
     * This tests that you can't manually publish a private only registry as public
     */
    @Test
    public void testManualPublishPrivateOnlyRegistryAsPublic() {
        // Manual publish
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true, false, "duncan.andrew.g@gmail.com",
                "test.dkr.ecr.test.amazonaws.com");
            fail("Should fail since it is a private only registry with a public tool");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The registry Amazon ECR is a private only registry"));
        }
    }

    /**
     * This tests that you can't manually publish a tool from a registry that requires a custom docker path without specifying the path
     */
    @Test
    public void testManualPublishCustomDockerPathRegistry() {
        // Manual publish
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true, true, "duncan.andrew.g@gmail.com", null);
            fail("Should fail due to no custom docker path");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The provided registry is not valid"));
        }
    }

    /**
     * This tests that you can refresh user data by refreshing a tool
     * ONLY WORKS if the current user in the database dump has no metadata, and on Github there is metadata (bio, location)
     * If the user has metadata, test will pass as long as the user's metadata isn't the same as Github already
     */
    @Test
    public void testRefreshingUserMetadata() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Refresh a tool
        DockstoreTool tool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandbitbucket", "");
        toolsApi.refresh(tool.getId());

        // Check that user has been updated
        // TODO: bizarrely, the new GitHub Java API library doesn't seem to handle bio
        //final long count = testingPostgres.runSelectStatement("select count(*) from enduser where location='Toronto' and bio='I am a test user'", long.class);
        final long count = testingPostgres.runSelectStatement("select count(*) from user_profile where location='Toronto'", long.class);
        Assert.assertEquals("One user should have this info now, there are " + count, 1, count);
    }

    public void testBrokenPath() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);
        try {
            workflowsApi.getWorkflowByPath("potato", "potato", false);
            Assert.fail("Should've not been able to get an entry that does not exist");
        } catch (ApiException e) {
            Assert.assertEquals("Entry not found", e.getMessage());
        }
    }

    @Test
    public void testGettingSourceFilesForTag() {
        final ApiClient webClient = getWebClient(USER_1_USERNAME, testingPostgres);
        final io.dockstore.openapi.client.ApiClient openAPIWebClient = getOpenAPIWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolApi = new ContainersApi(webClient);
        io.dockstore.openapi.client.api.ContainertagsApi toolTagsApi = new io.dockstore.openapi.client.api.ContainertagsApi(openAPIWebClient);

        // Sourcefiles for tags
        DockstoreTool tool = toolApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", null);
        Tag tag = tool.getWorkflowVersions().stream().filter(existingTag -> Objects.equals(existingTag.getName(), "master")).findFirst().get();

        List<SourceFile> sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), null);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(3, sourceFiles.size());

        // Check that filtering works
        List<String> fileTypes = new ArrayList<>();
        fileTypes.add(DescriptorLanguage.FileType.DOCKERFILE.toString());
        fileTypes.add(DescriptorLanguage.FileType.DOCKSTORE_CWL.toString());
        fileTypes.add(DescriptorLanguage.FileType.DOCKSTORE_WDL.toString());

        sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), fileTypes);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(3, sourceFiles.size());

        fileTypes.remove(1);
        sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), fileTypes);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(2, sourceFiles.size());

        fileTypes.clear();
        fileTypes.add(DescriptorLanguage.FileType.NEXTFLOW_CONFIG.toString());
        sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), fileTypes);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(0, sourceFiles.size());

        // Check that you can't grab a tag's sourcefiles if it doesn't belong to the tool.
        DockstoreTool tool2 = manualRegisterAndPublish(toolApi, "dockstoretestuser", "private_test_repo", "tool1",
                "git@github.com:DockstoreTestUser/dockstore-whalesay-2.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
        Tag tool2tag = tool2.getWorkflowVersions().get(0);

        try {
            sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tool2tag.getId(), null);
            Assert.fail("Shouldn't be able to get a tag's sourcefiles if it doesn't belong to the tool.");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            Assert.assertEquals("Version " + tool2tag.getId() + " does not exist for this entry", ex.getMessage());
        }


        // check that sourcefiles can't be viewed by another user if they aren't published
        final io.dockstore.openapi.client.ApiClient user2OpenAPIWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.ContainertagsApi user2toolTagsApi = new io.dockstore.openapi.client.api.ContainertagsApi(user2OpenAPIWebClient);
        try {
            sourceFiles = user2toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), null);
            Assert.fail("Should not be able to grab sourcefiles if not published and doesn't belong to user.");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            Assert.assertEquals("Forbidden: you do not have the credentials required to access this entry.", ex.getMessage());
        }

        // sourcefiles can be viewed by others once published
        tool = toolApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(true));
        sourceFiles = user2toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), null);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(3, sourceFiles.size());
    }

}
