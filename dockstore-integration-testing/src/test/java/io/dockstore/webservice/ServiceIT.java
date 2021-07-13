/*
 *    Copyright 2019 OICR
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

import static io.dockstore.webservice.Constants.DOCKSTORE_YML_PATH;
import static io.dockstore.webservice.Constants.LAMBDA_FAILURE;
import static junit.framework.TestCase.assertNotSame;
import static org.hibernate.validator.internal.util.Contracts.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.dockstore.client.cli.BaseIT;
import io.dockstore.client.cli.BasicIT;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.SourceControl;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowMode;
import io.dockstore.webservice.jdbi.FileDAO;
import io.dockstore.webservice.jdbi.ServiceDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dropwizard.client.JerseyClientBuilder;
import io.swagger.api.impl.ToolsImplCommon;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.Ga4GhApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.StarRequest;
import io.swagger.client.model.Tool;
import java.util.List;
import java.util.Map;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ClientProperties;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;

/**
 * @author dyuen
 */
@Category(ConfidentialTest.class)
public class ServiceIT extends BaseIT {

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public ExpectedException thrown = ExpectedException.none();
    private WorkflowDAO workflowDAO;
    private ServiceDAO serviceDAO;
    private Session session;
    private UserDAO userDAO;
    private FileDAO fileDAO;

    @Before
    public void setup() {
        DockstoreWebserviceApplication application = SUPPORT.getApplication();
        SessionFactory sessionFactory = application.getHibernate().getSessionFactory();

        this.workflowDAO = new WorkflowDAO(sessionFactory);
        this.serviceDAO = new ServiceDAO(sessionFactory);
        this.userDAO = new UserDAO(sessionFactory);
        this.fileDAO = new FileDAO(sessionFactory);


        // non-confidential test database sequences seem messed up and need to be iterated past, but other tests may depend on ids
        testingPostgres.runUpdateStatement("alter sequence enduser_id_seq increment by 50 restart with 100");
        testingPostgres.runUpdateStatement("alter sequence token_id_seq increment by 50 restart with 100");

        // used to allow us to use tokenDAO outside of the web service
        this.session = application.getHibernate().getSessionFactory().openSession();
        ManagedSessionContext.bind(session);
    }

    @Test
    public void checkWorkflowAndServiceHierarchy() {
        CreateContent createContent = new CreateContent().invoke(false);
        long workflowID = createContent.getWorkflowID();
        long serviceID = createContent.getServiceID();
        long serviceID2 = createContent.getServiceID2();

        final List<Workflow> allPublished = workflowDAO.findAllPublished();
        assertTrue(allPublished.stream().anyMatch(workflow -> workflow.getId() == workflowID && workflow instanceof BioWorkflow));
        assertTrue(allPublished.stream().anyMatch(workflow -> workflow.getId() == serviceID && workflow instanceof Service));
        assertTrue(allPublished.stream().anyMatch(workflow -> workflow.getId() == serviceID2 && workflow instanceof Service));

        final Service byId = serviceDAO.findById(serviceID);
        final Service byId1 = serviceDAO.findById(workflowID);

        assertTrue(byId != null && byId1 == null);
        session.close();
    }

    //TODO: can uncomment when we surface services via TRS

    //    @Test
    //    public void testTRSOutputOfService() {
    //        new CreateContent().invoke();
    //        final ApiClient webClient = getWebClient(true, false);
    //        Ga4GhApi client = new Ga4GhApi(webClient);
    //        final List<Tool> tools = client.toolsGet(null, null, null, null, null, null, null, null, null, null, null);
    //        assertTrue(tools.stream().filter(tool -> tool.getToolclass().getName().equalsIgnoreCase("service")).count() >= 2);
    //        assertTrue(tools.stream().filter(tool -> tool.getToolclass().getName().equalsIgnoreCase("workflow")).count() >= 1);
    //    }

    @Test
    public void testProprietaryAPI() {
        final CreateContent invoke = new CreateContent().invoke();
        final ApiClient webClient = getWebClient(true, false);
        WorkflowsApi client = new WorkflowsApi(webClient);
        final List<io.swagger.client.model.Workflow> services = client.allPublishedWorkflows(null, null, null, null, null, true);
        final List<io.swagger.client.model.Workflow> workflows = client.allPublishedWorkflows(null, null, null, null, null, false);
        assertTrue(workflows.size() >= 2 && workflows.stream()
            .noneMatch(workflow -> workflow.getDescriptorType().getValue().equalsIgnoreCase(DescriptorLanguage.SERVICE.toString())));
        Client jerseyClient = new JerseyClientBuilder(SUPPORT.getEnvironment()).build("test client");
        testXTotalCount(jerseyClient, String.format("http://localhost:%d/workflows/published", SUPPORT.getLocalPort()));
        testXTotalCount(jerseyClient, String.format("http://localhost:%d/workflows/published?services=true", SUPPORT.getLocalPort()));
        assertTrue(services.size() >= 1 && services.stream()
            .allMatch(workflow -> workflow.getDescriptorType().getValue().equalsIgnoreCase(DescriptorLanguage.SERVICE.toString())));

        // try some standard things we would like services to be able to do
        client.starEntry(invoke.getServiceID(), new StarRequest().star(true));
        client.updateLabels(invoke.getServiceID(), "foo,batman,chicken", "");

        // did it happen?
        final io.swagger.client.model.Workflow workflow = client.getWorkflow(invoke.getServiceID(), "");
        assertFalse(workflow.getStarredUsers().isEmpty());
        assertTrue(workflow.getLabels().stream().anyMatch(label -> "batman".equals(label.getValue())));
    }

    /**
     * Test X-total-count.  It so happens there's two services and two bioworkflows
     *
     * @param jerseyClient Jersey Client to test endpoint
     * @param path         Path of endpoint
     */
    private void testXTotalCount(Client jerseyClient, String path) {
        Response response = jerseyClient.target(path).request().property(ClientProperties.READ_TIMEOUT, 0).get();
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        MultivaluedMap<String, Object> headers = response.getHeaders();
        Object xTotalCount = headers.getFirst("X-total-count");
        assertEquals("2", xTotalCount);
    }

    @Test
    public void testGeneralDefaultPathMechanism() {
        final CreateContent invoke = new CreateContent().invoke();
        final ApiClient webClient = getWebClient(true, false);
        WorkflowsApi client = new WorkflowsApi(webClient);
        // did it happen?
        final io.swagger.client.model.Workflow workflow = client.getWorkflow(invoke.getServiceID(), "");
    }

    /**
     * This tests endpoints that will be triggered by GitHub App webhooks.
     * A service is created and a version is added for a release 1.0
     */
    @Test
    public void testGitHubAppEndpoints() throws Exception {

        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        final ApiClient webClient = getWebClient("DockstoreTestUser2", testingPostgres);
        WorkflowsApi client = new WorkflowsApi(webClient);

        String serviceRepo = "DockstoreTestUser2/test-service";
        String installationId = "1179416";

        // Add version
        client.handleGitHubRelease(serviceRepo, "DockstoreTestUser2", "refs/tags/1.0", installationId);
        long workflowCount = testingPostgres.runSelectStatement("select count(*) from service", long.class);
        assertEquals(1, workflowCount);
        io.swagger.client.model.Workflow service = client.getWorkflowByPath("github.com/" + serviceRepo, "versions", SERVICE);

        assertNotNull(service);
        assertEquals("Should have a new version", 1, service.getWorkflowVersions().size());
        List<SourceFile> sourceFiles = fileDAO.findSourceFilesByVersion(service.getWorkflowVersions().get(0).getId());
        assertEquals("Should have 3 source files", 3, sourceFiles.size());

        long users = testingPostgres.runSelectStatement("select count(*) from user_entry where entryid = '" + service.getId() + "'", long.class);
        assertEquals("Should have 1 user", 1, users);

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from service where sourcecontrol = 'github.com' and organization = 'DockstoreTestUser2' and repository = 'test-service'",
            long.class);
        Assert.assertEquals("there should be one matching service", 1, count);

        // Test user endpoints
        UsersApi usersApi = new UsersApi(webClient);
        final long userId = testingPostgres.runSelectStatement("select userid from user_entry where entryid = '" + service.getId() + "'", long.class);
        List<io.swagger.client.model.Workflow> services = usersApi.userServices(userId);
        List<io.swagger.client.model.Workflow> workflows = usersApi.userWorkflows(userId);
        assertEquals("There should be one service", 1, services.size());
        assertEquals("There should be no workflows", 0, workflows.size());

        // Should not be able to refresh service
        try {
            client.refresh(services.get(0).getId(), false);
            fail("Should not be able refresh a service");
        } catch (ApiException ex) {
            assertEquals("Should fail since you cannot refresh services.", HttpStatus.SC_BAD_REQUEST, ex.getCode());
        }
    }

    /**
     * Ensures that you cannot create a service if the given user is not on Dockstore
     */
    @Test
    public void createServiceNoUser() throws Exception {

        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        final ApiClient webClient = getWebClient("admin@admin.com", testingPostgres);
        WorkflowsApi client = new WorkflowsApi(webClient);

        String serviceRepo = "DockstoreTestUser2/test-service";
        String installationId = "1179416";

        // Add service
        try {
            client.handleGitHubRelease(serviceRepo, "iamnotarealuser", "refs/tags/1.0", installationId);
            fail("Should not reach this statement.");
        } catch (ApiException ex) {
            assertEquals("Should have error code 418", LAMBDA_FAILURE, ex.getCode());
        }

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from service where sourcecontrol = 'github.com' and organization = 'DockstoreTestUser2' and repository = 'test-service'",
            long.class);
        Assert.assertEquals("there should be no matching service", 0, count);
    }

    /**
     * Ensures that a service and workflow can have the same path
     *
     * @throws Exception
     */
    @Test
    public void testServiceWithSamePathAsWorkflow() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        final ApiClient webClient = getWebClient(BasicIT.USER_2_USERNAME, testingPostgres);
        WorkflowsApi client = new WorkflowsApi(webClient);

        final String github = SourceControl.GITHUB.toString();
        String serviceRepo = "DockstoreTestUser2/test-service";
        String installationId = "1179416";

        // Add service
        client.handleGitHubRelease(serviceRepo, BasicIT.USER_2_USERNAME, "refs/tags/1.0", installationId);
        long workflowCount = testingPostgres.runSelectStatement("select count(*) from service", long.class);
        assertEquals(1, workflowCount);

        // Add workflow with same path as service
        final io.swagger.client.model.Workflow workflow = client
            .manualRegister("github", serviceRepo, "/Dockstore.cwl", "", "cwl", "/test.json");
        assertNotNull(workflow);

        // forcibly publish both for testing
        testingPostgres.runUpdateStatement("update workflow set ispublished = 't'");
        testingPostgres.runUpdateStatement("update service set ispublished = 't'");

        // test retrieval
        final io.swagger.client.model.Workflow returnedWorkflow = client.getPublishedWorkflowByPath(github + "/" + serviceRepo, "", false, null);
        final io.swagger.client.model.Workflow returnedService = client.getPublishedWorkflowByPath(github + "/" + serviceRepo, "", true, null);
        assertNotSame(returnedWorkflow.getId(), returnedService.getId());

        // test GA4GH retrieval
        Ga4GhApi ga4GhApi = new Ga4GhApi(webClient);
        final Tool tool1 = ga4GhApi.toolsIdGet(ToolsImplCommon.WORKFLOW_PREFIX + "/" + github + "/" + serviceRepo);
        final Tool tool2 = ga4GhApi.toolsIdGet(ToolsImplCommon.SERVICE_PREFIX + "/" + github + "/" + serviceRepo);
        assertNotSame(tool1.getId(), tool2.getId());
    }

    /**
     * This tests that you can't add a version that doesn't exist
     */
    @Test
    public void updateServiceIncorrectTag() throws Exception {

        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        final ApiClient webClient = getWebClient("admin@admin.com", testingPostgres);
        WorkflowsApi client = new WorkflowsApi(webClient);

        String serviceRepo = "DockstoreTestUser2/test-service";
        String installationId = "1179416";

        // Add version that doesn't exist
        try {
            client.handleGitHubRelease(serviceRepo, "admin@admin.com", "refs/tags/1.0-fake", installationId);
            fail("Should not reach this statement.");
        } catch (ApiException ex) {
            assertEquals("Should have error code 418", LAMBDA_FAILURE, ex.getCode());
        }
    }

    /**
     * This tests that you can't add a version with an invalid dockstore.yml or no dockstore.yml
     */
    @Test
    public void updateServiceNoOrInvalidYml() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        final ApiClient webClient = getWebClient("admin@admin.com", testingPostgres);
        WorkflowsApi client = new WorkflowsApi(webClient);

        String serviceRepo = "DockstoreTestUser2/test-service";
        String installationId = "1179416";

        // Add version that has no dockstore.yml
        try {
            client.handleGitHubRelease(serviceRepo, "admin@admin.com", "refs/tags/no-yml", installationId);
            fail("Should not reach this statement.");
        } catch (ApiException ex) {
            assertEquals("Should have error code 418", LAMBDA_FAILURE, ex.getCode());
        }

        // Add version that has invalid dockstore.yml
        try {
            client.handleGitHubRelease(serviceRepo, "admin@admin.com", "refs/tags/invalid-yml", installationId);
            fail("Should not reach this statement.");
        } catch (ApiException ex) {
            assertEquals("Should have error code 418", LAMBDA_FAILURE, ex.getCode());
        }
    }

    /**
     * Tests that refresh will only grab the releases
     */
    @Test
    public void updateServiceSync() throws Exception {
        testingPostgres.runUpdateStatement("update enduser set isadmin = 't' where username = 'DockstoreTestUser2';");
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        final ApiClient webClient = getWebClient("DockstoreTestUser2", testingPostgres);
        WorkflowsApi client = new WorkflowsApi(webClient);

        String serviceRepo = "DockstoreTestUser2/test-service";
        String installationId = "1179416";

        // Add service
        client.handleGitHubRelease(serviceRepo, "DockstoreTestUser2", "refs/tags/1.0", installationId);
        long workflowCount = testingPostgres.runSelectStatement("select count(*) from service", long.class);
        assertEquals(1, workflowCount);

        io.swagger.client.model.Workflow service = client.getWorkflowByPath("github.com/" + serviceRepo, "", SERVICE);
        // io.swagger.client.model.Workflow service = services.get(0);
        try {
            client.refresh(service.getId(), false);
            fail("Should fail on refresh and not reach this point");
        } catch (ApiException ex) {
            assertEquals("Should not be able to refresh a dockstore.yml service.", HttpStatus.SC_BAD_REQUEST, ex.getCode());
        }
    }

    /**
     * This tests that you cannot create a service from an in invalid GitHub repository
     */
    @Test
    public void createServiceNoGitHubRepo() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        final ApiClient webClient = getWebClient("admin@admin.com", testingPostgres);
        WorkflowsApi client = new WorkflowsApi(webClient);

        String serviceRepo = "DockstoreTestUser2/test-service-foo-bar-not-real";
        String installationId = "1179416";

        // Add service
        try {
            client.handleGitHubRelease(serviceRepo, "admin@admin.com", "refs/tags/1.0", installationId);
            fail("Should not reach this statement.");
        } catch (ApiException ex) {
            assertEquals("Should have error code 418", LAMBDA_FAILURE, ex.getCode());
        }
    }

    private class CreateContent {
        private long workflowID;
        private long serviceID;
        private long serviceID2;

        long getWorkflowID() {
            return workflowID;
        }

        long getServiceID() {
            return serviceID;
        }

        long getServiceID2() {
            return serviceID2;
        }

        CreateContent invoke() {
            return invoke(true);
        }

        CreateContent invoke(boolean cleanup) {
            final Transaction transaction = session.beginTransaction();

            Workflow testWorkflow = new BioWorkflow();
            testWorkflow.setDescription("foo workflow");
            testWorkflow.setIsPublished(true);
            testWorkflow.setSourceControl(SourceControl.GITHUB);
            testWorkflow.setDescriptorType(DescriptorLanguage.CWL);
            testWorkflow.setOrganization("shield");
            testWorkflow.setRepository("shield_repo");

            Service testService = new Service();
            testService.setDescription("test service");
            testService.setIsPublished(true);
            testService.setSourceControl(SourceControl.GITHUB);
            testService.setDescriptorType(DescriptorLanguage.SERVICE);
            testService.setMode(WorkflowMode.DOCKSTORE_YML);
            testService.setOrganization("hydra");
            testService.setRepository("hydra_repo");
            testService.setDefaultWorkflowPath(DOCKSTORE_YML_PATH);

            Service test2Service = new Service();
            test2Service.setDescription("test service");
            test2Service.setIsPublished(true);
            test2Service.setSourceControl(SourceControl.GITHUB);
            test2Service.setMode(WorkflowMode.DOCKSTORE_YML);
            test2Service.setDescriptorType(DescriptorLanguage.SERVICE);
            test2Service.setOrganization("hydra");
            test2Service.setRepository("hydra_repo2");
            test2Service.setDefaultWorkflowPath(DOCKSTORE_YML_PATH);

            final Map<DescriptorLanguage.FileType, String> defaultPaths = test2Service.getDefaultPaths();
            for (DescriptorLanguage.FileType val : DescriptorLanguage.FileType.values()) {
                defaultPaths.put(val, "path for " + val);
            }
            test2Service.setDefaultPaths(defaultPaths);

            // add all users to all things for now
            for (User user : userDAO.findAll()) {
                testWorkflow.addUser(user);
                testService.addUser(user);
                test2Service.addUser(user);
            }

            workflowID = workflowDAO.create(testWorkflow);
            serviceID = serviceDAO.create(testService);
            serviceID2 = serviceDAO.create(test2Service);

            assertTrue(workflowID != 0 && serviceID != 0);

            session.flush();
            transaction.commit();
            if (cleanup) {
                session.close();
            }
            return this;
        }
    }
}
