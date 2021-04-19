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

import java.util.List;
import java.util.Objects;

import io.dockstore.client.cli.BaseIT;
import io.dockstore.client.cli.WorkflowIT;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.SourceControl;
import io.dockstore.openapi.client.model.PrivilegeRequest;
import io.dockstore.openapi.client.model.SourceControlOrganization;
import io.dockstore.webservice.resources.WorkflowResource;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.HostedApi;
import io.swagger.client.api.OrganizationsApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.BioWorkflow;
import io.swagger.client.model.Collection;
import io.swagger.client.model.EntryUpdateTime;
import io.swagger.client.model.Organization;
import io.swagger.client.model.OrganizationUpdateTime;
import io.swagger.client.model.Profile;
import io.swagger.client.model.Repository;
import io.swagger.client.model.User;
import io.swagger.client.model.Workflow;
import org.apache.commons.lang3.RandomStringUtils;
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

import static io.dockstore.client.cli.WorkflowIT.DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests operations from the UserResource
 *
 * @author dyuen
 */
@Category(ConfidentialTest.class)
public class UserResourceIT extends BaseIT {

    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
    }

    @Test
    public void testAddUserToOrgs() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        io.dockstore.openapi.client.ApiClient client = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.UsersApi userApi = new io.dockstore.openapi.client.api.UsersApi(client);
        WorkflowsApi workflowApi = new WorkflowsApi(getWebClient(USER_2_USERNAME, testingPostgres));
        workflowApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-wdl", "/dockstore.wdl", "",
                DescriptorLanguage.WDL.getShortName(), "");
        workflowApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-2", "/dockstore.wdl", "",
                DescriptorLanguage.WDL.getShortName(), "");
        workflowApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/ampa-nf", "/nextflow.config", "",
                DescriptorLanguage.NEXTFLOW.getShortName(), "");
        Workflow workflow1 = workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl", "/test.json");
        Long id = workflow1.getId();
        List<io.dockstore.openapi.client.model.Workflow> workflows = userApi.addUserToDockstoreWorkflows(userApi.getUser().getId(), "");


        // Remove an association with an entry
        long numberOfWorkflows = workflows.size();
        testingPostgres.runUpdateStatement("delete from user_entry where entryid = " + id);
        long newNumberOfWorkflows = userApi.userWorkflows((long)1).size();
        assertEquals("Should have one less workflow", numberOfWorkflows - 1, newNumberOfWorkflows);

        // Add user back to workflow
        workflows = userApi.addUserToDockstoreWorkflows((long)1, "");
        newNumberOfWorkflows = workflows.size();
        assertEquals("Should have the original number of workflows", numberOfWorkflows, newNumberOfWorkflows);
        assertTrue("Should have the workflow DockstoreTestUser/dockstore-whalesay-2", workflows.stream().anyMatch(workflow -> Objects.equals("dockstore-whalesay-2", workflow.getRepository()) && Objects.equals("DockstoreTestUser", workflow.getOrganization())));
    }

    @Test(expected = ApiException.class)
    public void testChangingNameFail() throws ApiException {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi userApi = new UsersApi(client);
        userApi.changeUsername("1direction"); // do not lengthen test, failure expected
    }

    @Test(expected = ApiException.class)
    public void testChangingNameFail2() throws ApiException {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi userApi = new UsersApi(client);
        userApi.changeUsername("foo@gmail.com"); // do not lengthen test, failure expected
    }

    @Test
    public void testChangingNameSuccess() throws ApiException {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi userApi = new UsersApi(client);
        userApi.changeUsername("foo");
        assertEquals("foo", userApi.getUser().getUsername());

        // Add hosted workflow, should use new username
        HostedApi userHostedApi = new HostedApi(client);
        Workflow hostedWorkflow = userHostedApi.createHostedWorkflow("hosted1", null, "cwl", null, null);
        assertEquals("Hosted workflow should used foo as workflow org, has " + hostedWorkflow.getOrganization(), "foo",
            hostedWorkflow.getOrganization());
    }

    @Test
    public void testUserTermination() throws ApiException {
        ApiClient adminWebClient = getWebClient(ADMIN_USERNAME, testingPostgres);
        ApiClient userWebClient = getWebClient(USER_2_USERNAME, testingPostgres);

        UsersApi userUserWebClient = new UsersApi(userWebClient);
        final User user = userUserWebClient.getUser();
        assertFalse(user.getUsername().isEmpty());

        UsersApi adminAdminWebClient = new UsersApi(adminWebClient);
        final Boolean aBoolean = adminAdminWebClient.terminateUser(user.getId());

        assertTrue(aBoolean);

        try {
            userUserWebClient.getUser();
            fail("should be unreachable, user must not have been banned properly");
        } catch (ApiException e) {
            assertEquals(e.getCode(), HttpStatus.SC_UNAUTHORIZED);
        }
    }

    @Test
    public void longAvatarUrlTest() {
        String generatedString = RandomStringUtils.randomAlphanumeric(9001);
        testingPostgres.runUpdateStatement(String.format("update enduser set avatarurl='%s'", generatedString));
    }

    /**
     * Creates an organization using the given names
     * @param client
     * @param name
     * @param displayName
     * @return new organization
     */
    private Organization createOrganization(ApiClient client, String name, String displayName) {
        OrganizationsApi organizationsApi = new OrganizationsApi(client);

        Organization organization = new Organization();
        organization.setName(name);
        organization.setDisplayName(displayName);
        organization.setLocation("testlocation");
        organization.setLink("https://www.google.com");
        organization.setEmail("test@email.com");
        organization.setDescription("hello");
        organization.setTopic("This is a short topic");
        organization.setAvatarUrl("https://www.lifehardin.net/images/employees/default-logo.png");

        return organizationsApi.createOrganization(organization);
    }

    /**
     * Should not be able to update username after creating an organisation
     *
     * @throws ApiException
     */
    @Test
    public void testChangeUsernameAfterOrgCreation() throws ApiException {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi userApi = new UsersApi(client);

        // Can change username when not a member of any organisations
        assertTrue(userApi.getExtendedUserData().isCanChangeUsername());

        // Create organization
        createOrganization(client, "testname", "test name");

        // Cannot change username now that user is part of an organisation
        assertFalse(userApi.getExtendedUserData().isCanChangeUsername());
    }

    @Test
    public void testSelfDestruct() throws ApiException {
        ApiClient client = getAnonymousWebClient();
        UsersApi userApi = new UsersApi(client);

        String serviceRepo = "DockstoreTestUser2/test-service";
        String installationId = "1179416";

        // anon should not exist
        boolean shouldFail = false;
        try {
            userApi.getUser();
        } catch (ApiException e) {
            shouldFail = true;
        }
        assertTrue(shouldFail);

        // use a real account
        client = getWebClient(USER_2_USERNAME, testingPostgres);
        userApi = new UsersApi(client);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);
        final ApiClient adminWebClient = getWebClient(ADMIN_USERNAME, testingPostgres);

        final WorkflowsApi adminWorkflowsApi = new WorkflowsApi(adminWebClient);

        User user = userApi.getUser();
        assertNotNull(user);

        // try to delete with published workflows & service
        workflowsApi.manualRegister(SourceControl.GITHUB.name(), DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "/Dockstore.cwl", "", DescriptorLanguage.CWL.getShortName(), "");
        workflowsApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/ampa-nf", "/nextflow.config", "", DescriptorLanguage.NEXTFLOW.getShortName(), "");
        workflowsApi.handleGitHubRelease(serviceRepo, USER_2_USERNAME, "refs/tags/1.0", installationId);

        final Workflow workflowByPath = workflowsApi
            .getWorkflowByPath(WorkflowIT.DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, null, false);
        // refresh targeted
        workflowsApi.refresh(workflowByPath.getId(), false);

        // Verify that admin can access unpublished workflow, because admin is going to verify later
        // that the workflow is gone
        adminWorkflowsApi.getWorkflowByPath(WorkflowIT.DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, null, false);
        adminWorkflowsApi.getWorkflowByPath(SourceControl.GITHUB + "/" + serviceRepo, null, true);

        // publish one
        workflowsApi.publish(workflowByPath.getId(), CommonTestUtilities.createPublishRequest(true));

        assertFalse(userApi.getExtendedUserData().isCanChangeUsername());

        boolean expectedFailToDelete = false;
        try {
            userApi.selfDestruct();
        } catch (ApiException e) {
            expectedFailToDelete = true;
        }
        assertTrue(expectedFailToDelete);
        // then unpublish them
        workflowsApi.publish(workflowByPath.getId(), CommonTestUtilities.createPublishRequest(false));
        assertTrue(userApi.getExtendedUserData().isCanChangeUsername());
        assertTrue(userApi.selfDestruct());
        //TODO need to test that profiles are cascaded to and cleared

        // Verify that self-destruct also deleted the workflow
        boolean expectedAdminAccessToFail = false;
        try {
            adminWorkflowsApi.getWorkflowByPath(WorkflowIT.DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, null, false);

        } catch (ApiException e) {
            assertEquals(e.getCode(), HttpStatus.SC_BAD_REQUEST);
            expectedAdminAccessToFail = true;
        }
        assertTrue(expectedAdminAccessToFail);

        // Verify that self-destruct also deleted the service
        boolean expectedAdminServiceAccessToFail = false;
        try {
            adminWorkflowsApi.getWorkflowByPath(SourceControl.GITHUB + "/" + serviceRepo, null, true);
        } catch (ApiException e) {
            assertEquals(e.getCode(), HttpStatus.SC_BAD_REQUEST);
            expectedAdminServiceAccessToFail = true;
        }
        assertTrue(expectedAdminServiceAccessToFail);

        // I shouldn't be able to get info on myself after deletion
        boolean expectedFailToGetInfo = false;
        try {
            userApi.getUser();
        } catch (ApiException e) {
            expectedFailToGetInfo = true;
        }
        assertTrue(expectedFailToGetInfo);

        expectedFailToGetInfo = false;
        try {
            userApi.getExtendedUserData();
        } catch (ApiException e) {
            expectedFailToGetInfo = true;
        }
        assertTrue(expectedFailToGetInfo);
    }

    @Test
    public void testDeletedUsernameReuse() {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi userApi = new UsersApi(client);
        ApiClient adminWebClient = getWebClient(ADMIN_USERNAME, testingPostgres);
        UsersApi adminUserApi = new UsersApi(adminWebClient);

        User user = userApi.getUser();

        // Test that deleting a user creates a deleteduser entry
        long count = testingPostgres.runSelectStatement("select count(*) from deletedusername", long.class);
        assertEquals(0, count);
        String altUsername = "notTheNameOfTheSite";
        assertFalse(userApi.checkUserExists(altUsername));
        userApi.changeUsername(altUsername);
        userApi.selfDestruct();
        count = testingPostgres.runSelectStatement("select count(*) from deletedusername", long.class);
        assertEquals(1, count);

        // Check that the deleted user comes up
        assertTrue(adminUserApi.checkUserExists(altUsername));

        try {
            adminUserApi.changeUsername(altUsername);
            fail("User should not be able to change username to a deleted username for 3 years");
        } catch (ApiException ex) {
            assertEquals(HttpStatus.SC_BAD_REQUEST, ex.getCode());
            assertTrue(ex.getMessage().contains("because it is already in use"));
        }


        // Test that a deleteduser entry older than 3 years doesn't prevent another user from using the username.
        testingPostgres.runUpdateStatement("UPDATE deletedusername SET dbcreatedate = '2018-01-20 09:34:24.674000' WHERE username = '" + altUsername + "'");
        assertFalse(adminUserApi.checkUserExists(altUsername));
        adminUserApi.changeUsername(altUsername);
    }

    /**
     * Tests that the endpoints for the wizard registration work
     * @throws ApiException
     */
    @Test
    public void testWizardEndpoints() throws ApiException {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi userApi = new UsersApi(client);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);

        List<String> registries = userApi.getUserRegistries();
        assertTrue(registries.size() > 0);
        assertTrue(registries.contains(SourceControl.GITHUB.toString()));
        assertTrue(registries.contains(SourceControl.GITLAB.toString()));
        assertTrue(registries.contains(SourceControl.BITBUCKET.toString()));

        // Test GitHub
        List<String> orgs = userApi.getUserOrganizations(SourceControl.GITHUB.name());
        assertTrue(orgs.size() > 0);
        assertTrue(orgs.contains("dockstoretesting"));
        assertTrue(orgs.contains("DockstoreTestUser"));
        assertTrue(orgs.contains("DockstoreTestUser2"));

        List<Repository> repositories = userApi.getUserOrganizationRepositories(SourceControl.GITHUB.name(), "dockstoretesting");
        assertTrue(repositories.size() > 0);
        assertTrue(
            repositories.stream().anyMatch(repo -> Objects.equals(repo.getPath(), "dockstoretesting/basic-tool") && !repo.isPresent()));
        assertTrue(
            repositories.stream().anyMatch(repo -> Objects.equals(repo.getPath(), "dockstoretesting/basic-workflow") && !repo.isPresent()));

        // Register a workflow
        BioWorkflow ghWorkflow = workflowsApi.addWorkflow(SourceControl.GITHUB.name(), "dockstoretesting", "basic-workflow");
        assertNotNull("GitHub workflow should be added", ghWorkflow);
        assertEquals(ghWorkflow.getFullWorkflowPath(), "github.com/dockstoretesting/basic-workflow");

        // dockstoretesting/basic-workflow should be present now
        repositories = userApi.getUserOrganizationRepositories(SourceControl.GITHUB.name(), "dockstoretesting");
        assertTrue(repositories.size() > 0);
        assertTrue(
            repositories.stream().anyMatch(repo -> Objects.equals(repo.getPath(), "dockstoretesting/basic-tool") && !repo.isPresent()));
        assertTrue(
            repositories.stream().anyMatch(repo -> Objects.equals(repo.getPath(), "dockstoretesting/basic-workflow") && repo.isPresent()));

        // Try deleting a workflow
        workflowsApi.deleteWorkflow(SourceControl.GITHUB.name(), "dockstoretesting", "basic-workflow");
        Workflow deletedWorkflow = null;
        try {
            deletedWorkflow = workflowsApi.getWorkflow(ghWorkflow.getId(), null);
            assertFalse("Should not reach here as entry should not exist", false);
        } catch (ApiException ex) {
            assertNull("Workflow should be null", deletedWorkflow);
        }

        // Try making a repo undeletable
        ghWorkflow = workflowsApi.addWorkflow(SourceControl.GITHUB.name(), "dockstoretesting", "basic-workflow");
        workflowsApi.refresh(ghWorkflow.getId(), false);
        repositories = userApi.getUserOrganizationRepositories(SourceControl.GITHUB.name(), "dockstoretesting");
        assertTrue(repositories.size() > 0);
        assertTrue(
            repositories.stream().anyMatch(repo -> Objects.equals(repo.getPath(), "dockstoretesting/basic-workflow") && repo.isPresent() && !repo.isCanDelete()));

        // Test Gitlab
        orgs = userApi.getUserOrganizations(SourceControl.GITLAB.name());
        assertTrue(orgs.size() > 0);
        assertTrue(orgs.contains("dockstore.test.user2"));

        repositories = userApi.getUserOrganizationRepositories(SourceControl.GITLAB.name(), "dockstore.test.user2");
        assertTrue(repositories.size() > 0);
        assertTrue(
            repositories.stream().anyMatch(repo -> Objects.equals(repo.getPath(), "dockstore.test.user2/dockstore-workflow-md5sum-unified") && !repo.isPresent()));
        assertTrue(
            repositories.stream().anyMatch(repo -> Objects.equals(repo.getPath(), "dockstore.test.user2/dockstore-workflow-example") && !repo.isPresent()));

        // Register a workflow
        BioWorkflow glWorkflow = workflowsApi.addWorkflow(SourceControl.GITLAB.name(), "dockstore.test.user2", "dockstore-workflow-example");
        assertEquals(glWorkflow.getFullWorkflowPath(), "gitlab.com/dockstore.test.user2/dockstore-workflow-example");

        // dockstore.test.user2/dockstore-workflow-example should be present now
        repositories = userApi.getUserOrganizationRepositories(SourceControl.GITLAB.name(), "dockstore.test.user2");
        assertTrue(repositories.size() > 0);
        assertTrue(
            repositories.stream().anyMatch(repo -> Objects.equals(repo.getPath(), "dockstore.test.user2/dockstore-workflow-example") && repo.isPresent()));

        // Try registering the workflow again (duplicate) should fail
        try {
            workflowsApi.addWorkflow(SourceControl.GITLAB.name(), "dockstore.test.user2", "dockstore-workflow-example");
            assertFalse("Should not reach this, should fail", false);
        } catch (ApiException ex) {
            assertTrue("Should have error message that workflow already exists.", ex.getMessage().contains("already exists"));
        }

        // Try registering a hosted workflow
        try {
            BioWorkflow dsWorkflow = workflowsApi.addWorkflow(SourceControl.DOCKSTORE.name(), "foo", "bar");
            assertFalse("Should not reach this, should fail", false);
        } catch (ApiException ex) {
            assertTrue("Should have error message that hosted workflows cannot be added this way.", ex.getMessage().contains(WorkflowResource.SC_REGISTRY_ACCESS_MESSAGE));
        }

    }

    /**
     * Tests the endpoints used for logged in homepage to retrieve recent entries and organizations
     */
    @Test
    public void testLoggedInHomepageEndpoints() {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi userApi = new UsersApi(client);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);

        workflowsApi.manualRegister("gitlab", "dockstore.test.user2/dockstore-workflow-md5sum-unified", "/Dockstore.cwl", "", "cwl", "/test.json");

        List<EntryUpdateTime> entries = userApi.getUserEntries(10, null);
        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().anyMatch(e -> e.getPath().contains("gitlab.com/dockstore.test.user2/dockstore-workflow-md5sum-unified")));
        assertTrue(entries.stream().anyMatch(e -> e.getPath().contains("dockstore-workflow-md5sum-unified")));

        // Update an entry
        Workflow workflow = workflowsApi.getWorkflowByPath("gitlab.com/dockstore.test.user2/dockstore-workflow-md5sum-unified", null, false);
        Workflow refreshedWorkflow = workflowsApi.refresh(workflow.getId(), false);

        // Develop branch doesn't have a descriptor with the default Dockstore.cwl, it should pull from README instead
        Assert.assertTrue(refreshedWorkflow.getDescription().contains("To demonstrate the checker workflow proposal"));

        // Entry should now be at the top
        entries = userApi.getUserEntries(10, null);
        assertEquals("gitlab.com/dockstore.test.user2/dockstore-workflow-md5sum-unified", entries.get(0).getPath());
        assertEquals("dockstore-workflow-md5sum-unified", entries.get(0).getPrettyPath());

        // Create organizations
        Organization foobarOrg = createOrganization(client, "Foobar", "Foo Bar");
        Organization foobarOrgTwo = createOrganization(client, "Foobar2", "Foo Bar the second");
        Organization tacoOrg = createOrganization(client, "taco", "taco place");

        // taco should be most recent
        List<OrganizationUpdateTime> organizations = userApi.getUserDockstoreOrganizations(10, null);
        assertFalse(organizations.isEmpty());
        assertEquals("taco", organizations.get(0).getName());

        // Add collection to foobar2
        OrganizationsApi organizationsApi = new OrganizationsApi(client);
        organizationsApi.createCollection(foobarOrgTwo.getId(), createCollection());

        // foobar2 should be the most recent
        organizations = userApi.getUserDockstoreOrganizations(10, null);
        assertFalse(organizations.isEmpty());
        assertEquals("Foobar2", organizations.get(0).getName());

        // Search for taco organization
        organizations = userApi.getUserDockstoreOrganizations(10, "tac");
        assertFalse(organizations.isEmpty());
        assertEquals("taco", organizations.get(0).getName());
    }

    /**
     * Creates a collection (does not save to database)
     * @return new collection
     */
    private Collection createCollection() {
        Collection collection = new Collection();
        collection.setDisplayName("cool name");
        collection.setName("coolname");
        collection.setTopic("this is the topic");
        collection.setDescription("this is the description");

        return collection;
    }

    @Test
    public void testUpdateUserMetadataFromGithub() {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi usersApi = new UsersApi(client);
        Profile userProfile = usersApi.getUser().getUserProfiles().get("github.com");

        assertEquals("", userProfile.getName());
        assertNull(userProfile.getEmail());
        assertNull(userProfile.getAvatarURL());
        assertNull(userProfile.getBio());
        assertNull(userProfile.getLocation());
        assertNull(userProfile.getCompany());
        assertEquals("DockstoreTestUser2", userProfile.getUsername());

        final User user = usersApi.updateLoggedInUserMetadata("github.com");
        userProfile = usersApi.getUser().getUserProfiles().get("github.com");

        System.out.println(usersApi.getUser().getUserProfiles().get("github.com"));
        assertNull(userProfile.getName());
        assertEquals("dockstore.test.user2@gmail.com", userProfile.getEmail());
        assertTrue(userProfile.getAvatarURL().endsWith("githubusercontent.com/u/17859829?v=4"));
        assertEquals("", userProfile.getBio());
        assertEquals("Toronto", userProfile.getLocation());
        assertNull(userProfile.getCompany());
        assertEquals("DockstoreTestUser2", userProfile.getUsername());

        io.dockstore.openapi.client.ApiClient userWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.UsersApi userApi = new io.dockstore.openapi.client.api.UsersApi(userWebClient);
        List<SourceControlOrganization> myGitHubOrgs = userApi.getMyGitHubOrgs();
        assertTrue(!myGitHubOrgs.isEmpty() && myGitHubOrgs.stream().anyMatch(org -> org.getName().equals("dockstoretesting")));
        // Delete all of the tokens (except for Dockstore tokens) for every user
        testingPostgres.runUpdateStatement("UPDATE token set content = 'foo' WHERE tokensource <> 'dockstore'");

        try {
            userApi.getMyGitHubOrgs();
        } catch (io.dockstore.openapi.client.ApiException e) {
            assertEquals(HttpStatus.SC_BAD_REQUEST, e.getCode());
            return;
        }
        fail("should not be able to get here");
    }

    @Test
    public void testSetUserPrivilege() {
        io.dockstore.openapi.client.ApiClient adminWebClient = getOpenAPIWebClient(ADMIN_USERNAME, testingPostgres);
        io.dockstore.openapi.client.ApiClient userWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);

        io.dockstore.openapi.client.model.PrivilegeRequest privilegeRequest = new PrivilegeRequest();
        io.dockstore.openapi.client.api.UsersApi adminApi = new io.dockstore.openapi.client.api.UsersApi(adminWebClient);
        io.dockstore.openapi.client.api.UsersApi userApi = new io.dockstore.openapi.client.api.UsersApi(userWebClient);
        io.dockstore.openapi.client.model.User admin = adminApi.getUser();
        io.dockstore.openapi.client.model.User user = userApi.getUser();

        privilegeRequest.setAdmin(false);
        adminApi.setUserPrivileges(privilegeRequest, user.getId());
        adminApi.setUserPrivileges(privilegeRequest, user.getId());
        assertFalse(userApi.getUser().isIsAdmin());
        assertFalse(userApi.getUser().isCurator());

        privilegeRequest.setCurator(true);
        adminApi.setUserPrivileges(privilegeRequest, user.getId());
        assertFalse(userApi.getUser().isIsAdmin());
        assertTrue(userApi.getUser().isCurator());

        try {
            userApi.setUserPrivileges(privilegeRequest, admin.getId());
            fail("Curator should not be able to set admin permissions");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_FORBIDDEN, ex.getCode());
        }

        privilegeRequest.setAdmin(true);
        adminApi.setUserPrivileges(privilegeRequest, user.getId());
        assertTrue(userApi.getUser().isIsAdmin());

        privilegeRequest.setAdmin(false);
        try {
            adminApi.setUserPrivileges(privilegeRequest, admin.getId());
            fail("User should not be able to set their own permissions");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_FORBIDDEN, ex.getCode());
        }

        privilegeRequest.setCurator(false);
        adminApi.setUserPrivileges(privilegeRequest, user.getId());
        assertFalse(userApi.getUser().isIsAdmin());
        assertFalse(userApi.getUser().isCurator());
        try {
            userApi.setUserPrivileges(privilegeRequest, admin.getId());
            fail("User with no curator or admin rights should not be able to access the API call");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_FORBIDDEN, ex.getCode());
        }
    }

    /**
     * Tests the endpoint used to sync all users' Github information called by a user who has a valid GitHub token
     * and one user who has a missing or outdated GitHub token
     */
    @Test
    public void testUpdateUserMetadataWithTokens() {
        io.dockstore.openapi.client.ApiClient adminWebClient = getOpenAPIWebClient(ADMIN_USERNAME, testingPostgres);
        io.dockstore.openapi.client.ApiClient userWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.UsersApi adminApi = new io.dockstore.openapi.client.api.UsersApi(adminWebClient);
        io.dockstore.openapi.client.api.UsersApi userApi = new io.dockstore.openapi.client.api.UsersApi(userWebClient);
        io.dockstore.openapi.client.model.User admin = adminApi.getUser();
        io.dockstore.openapi.client.model.Profile userProfile = userApi.getUser().getUserProfiles().get("github.com");

        // Should add a test above this to check that the API call should pass once the admin tokens are up to date
        // Testing that the updateLoggedInUserMetadata() should fail if GitHub tokens are expired or absent
        testingPostgres.runUpdateStatement(String.format("DELETE FROM token WHERE userid = %d and tokensource = 'github.com'", admin.getId()));
        try {
            adminApi.updateLoggedInUserMetadata("github.com");
            fail("API call should fail and throw an error when no GitHub tokens are found or if tokens are out of date");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_FORBIDDEN, ex.getCode());
        }

        // DockstoreUser2's profile elements should be initially set to null since the GitHub metadata isn't synced yet
        assertNull(userProfile.getEmail());
        assertNull(userProfile.getAvatarURL());
        assertNull(userProfile.getLocation());

        // The API call updateUserMetadata() should not throw an error and exit if any users' tokens are out of date or absent
        // Additionally, the API call should go through and sync DockstoreTestUser2's GitHub data
        adminApi.updateUserMetadata();

        userProfile = userApi.getUser().getUserProfiles().get("github.com");
        assertEquals("dockstore.test.user2@gmail.com", userProfile.getEmail());
        assertTrue(userProfile.getAvatarURL().endsWith("githubusercontent.com/u/17859829?v=4"));
        assertEquals("Toronto", userProfile.getLocation());
    }

    /**
     * Tests the endpoint while all users have no valid GitHub token and the caller also does not have a valid token
     */
    @Test
    public void testUpdateUserMetadataWithoutTokens() {
        io.dockstore.openapi.client.ApiClient adminWebClient = getOpenAPIWebClient(ADMIN_USERNAME, testingPostgres);
        io.dockstore.openapi.client.ApiClient userWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.UsersApi adminApi = new io.dockstore.openapi.client.api.UsersApi(adminWebClient);
        io.dockstore.openapi.client.api.UsersApi userApi = new io.dockstore.openapi.client.api.UsersApi(userWebClient);
        io.dockstore.openapi.client.model.User admin = adminApi.getUser();
        io.dockstore.openapi.client.model.Profile userProfile = userApi.getUser().getUserProfiles().get("github.com");

        // Delete all of the tokens (except for Dockstore tokens) for every user
        testingPostgres.runUpdateStatement("DELETE FROM token WHERE tokensource <> 'dockstore'");

        assertNull(userProfile.getEmail());
        assertNull(userProfile.getAvatarURL());
        assertNull(userProfile.getLocation());

        // Call the API method while the caller has no token
        // An error should not be thrown and the call should pass, but every user should not have their GitHub information synced
        userApi.updateUserMetadata();

        userProfile = userApi.getUser().getUserProfiles().get("github.com");
        assertNull(userProfile.getEmail());
        assertNull(userProfile.getAvatarURL());
        assertNull(userProfile.getLocation());
    }
}
