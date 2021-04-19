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
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.ws.rs.core.GenericType;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.Registry;
import io.dockstore.common.SourceControl;
import io.dockstore.common.WorkflowTest;
import io.dockstore.openapi.client.api.Ga4Ghv20Api;
import io.dockstore.openapi.client.model.ImageData;
import io.dockstore.openapi.client.model.Repository;
import io.dockstore.openapi.client.model.ToolVersion;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.helpers.EntryVersionHelper;
import io.dockstore.webservice.jdbi.EntryDAO;
import io.dockstore.webservice.jdbi.FileDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dockstore.webservice.jdbi.WorkflowVersionDAO;
import io.dropwizard.testing.ResourceHelpers;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.AliasesApi;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.EntriesApi;
import io.swagger.client.api.Ga4GhApi;
import io.swagger.client.api.HostedApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.Entry;
import io.swagger.client.model.FileFormat;
import io.swagger.client.model.FileWrapper;
import io.swagger.client.model.ParsedInformation;
import io.swagger.client.model.PublishRequest;
import io.swagger.client.model.SourceFile;
import io.swagger.client.model.Tag;
import io.swagger.client.model.Tool;
import io.swagger.client.model.ToolFile;
import io.swagger.client.model.User;
import io.swagger.client.model.Workflow;
import io.swagger.client.model.Workflow.DescriptorTypeEnum;
import io.swagger.client.model.WorkflowVersion;
import io.swagger.model.DescriptorType;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.context.internal.ManagedSessionContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;

import static io.dockstore.common.DescriptorLanguage.CWL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Extra confidential integration tests, focus on testing workflow interactions
 * {@link io.dockstore.client.cli.BaseIT}
 *
 * @author dyuen
 */
@Category({ ConfidentialTest.class, WorkflowTest.class })
public class WorkflowIT extends BaseIT {
    public static final String DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME = "DockstoreTestUser2/hello-dockstore-workflow";
    public static final String DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW =
        SourceControl.GITHUB.toString() + "/" + DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME;
    public static final String DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW =
        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/dockstore_workflow_cnv";
    private static final String DOCKSTORE_TEST_USER2_DOCKSTORE_WORKFLOW =
        SourceControl.BITBUCKET.toString() + "/dockstore_testuser2/dockstore-workflow";
    private static final String DOCKSTORE_TEST_USER2_IMPORTS_DOCKSTORE_WORKFLOW =
        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/dockstore-whalesay-imports";
    private static final String DOCKSTORE_TEST_USER2_GDC_DNASEQ_CWL_WORKFLOW =
        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/gdc-dnaseq-cwl";
    // workflow with external library in lib directory
    private static final String DOCKSTORE_TEST_USER2_NEXTFLOW_LIB_WORKFLOW = SourceControl.GITHUB.toString() + "/DockstoreTestUser2/rnatoy";
    // workflow that uses containers
    private static final String DOCKSTORE_TEST_USER2_NEXTFLOW_DOCKER_WORKFLOW =
        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/galaxy-workflows";
    // workflow with includeConfig in config file directory
    private static final String DOCKSTORE_TEST_USER2_INCLUDECONFIG_WORKFLOW = SourceControl.GITHUB.toString() + "/DockstoreTestUser2/vipr";
    private static final String DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL =
        Registry.QUAY_IO.getDockerPath() + "/dockstoretestuser2/dockstore-cgpmap";
    private static final String DOCKSTORE_TEST_USER2_MORE_IMPORT_STRUCTURE =
        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/workflow-seq-import";
    private static final String GATK_SV_TAG = "dockstore-test";
    private static final String DESCRIPTOR_FILE_SHA_TYPE_FOR_TRS = "sha1";
    private static final String DOCKER_IMAGE_SHA_TYPE_FOR_TRS = "sha-256";

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    @Rule
    public final ExpectedException thrown = ExpectedException.none();


    private WorkflowDAO workflowDAO;
    private WorkflowVersionDAO workflowVersionDAO;
    private FileDAO fileDAO;

    @Before
    public void setup() {
        DockstoreWebserviceApplication application = SUPPORT.getApplication();
        SessionFactory sessionFactory = application.getHibernate().getSessionFactory();

        this.workflowDAO = new WorkflowDAO(sessionFactory);
        this.workflowVersionDAO = new WorkflowVersionDAO(sessionFactory);
        this.fileDAO = new FileDAO(sessionFactory);

        // used to allow us to use workflowDAO outside of the web service
        Session session = application.getHibernate().getSessionFactory().openSession();
        ManagedSessionContext.bind(session);

    }
    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
    }

    /**
     * Manually register and publish a workflow with the given path and name
     *
     * @param workflowsApi
     * @param workflowPath
     * @param workflowName
     * @param descriptorType
     * @param sourceControl
     * @param descriptorPath
     * @param toPublish
     * @return Published workflow
     */
    private Workflow manualRegisterAndPublish(WorkflowsApi workflowsApi, String workflowPath, String workflowName, String descriptorType,
        SourceControl sourceControl, String descriptorPath, boolean toPublish) {
        // Manually register
        Workflow workflow = workflowsApi
            .manualRegister(sourceControl.getFriendlyName().toLowerCase(), workflowPath, descriptorPath, workflowName, descriptorType,
                "/test.json");
        assertEquals(Workflow.ModeEnum.STUB, workflow.getMode());

        // Refresh
        workflow = workflowsApi.refresh(workflow.getId(), false);
        assertEquals(Workflow.ModeEnum.FULL, workflow.getMode());

        // Publish
        if (toPublish) {
            workflow = workflowsApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));
        }
        assertEquals(workflow.isIsPublished(), toPublish);
        return workflow;
    }

    // Tests 3 things:
    // WDL workflow with local imports
    // WDL workflow with HTTP imports
    // WDL workflow with HTTP imports and local imports and nested
    @Test
    public void testWDLLanguageParsingInformation() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        Workflow wdl = workflowApi
                .manualRegister(SourceControl.GITHUB.name(), "dockstore-testing/md5sum-checker", "/md5sum/md5sum-workflow.wdl", "WDL",
                        DescriptorLanguage.WDL.toString(), "/test.json");
        Long id = wdl.getId();
        workflowApi.refresh(id, false);
        Workflow workflow = workflowApi.getWorkflow(id, null);
        WorkflowVersion workflowWithLocalImport = workflow.getWorkflowVersions().stream()
                .filter(version -> version.getName().equals("workflowWithLocalImport")).findFirst().get();
        ParsedInformation parsedInformation = workflowWithLocalImport.getVersionMetadata().getParsedInformationSet().get(0);
        Assert.assertTrue(parsedInformation.isHasLocalImports());
        Assert.assertFalse(parsedInformation.isHasHTTPImports());
        WorkflowVersion workflowWithHTTPImport = workflow.getWorkflowVersions().stream()
                .filter(version -> version.getName().equals("workflowWithHTTPImport")).findFirst().get();
        ParsedInformation parsedInformationHTTP = workflowWithHTTPImport.getVersionMetadata().getParsedInformationSet().get(0);
        Assert.assertFalse(parsedInformationHTTP.isHasLocalImports());
        Assert.assertTrue(parsedInformationHTTP.isHasHTTPImports());

        Workflow wdlChecker = workflowApi
                .manualRegister(SourceControl.GITHUB.name(), "dockstore-testing/md5sum-checker", "/checker-workflow-wrapping-workflow.wdl", "WDLChecker",
                        DescriptorLanguage.WDL.toString(), "/test.json");
        id = wdlChecker.getId();
        workflowApi.refresh(id, false);
        workflow = workflowApi.getWorkflow(id, null);
        WorkflowVersion workflowWithBothImports = workflow.getWorkflowVersions().stream()
                .filter(version -> version.getName().equals("workflowWithHTTPImport")).findFirst().get();
        parsedInformation = workflowWithBothImports.getVersionMetadata().getParsedInformationSet().get(0);
        Assert.assertTrue(parsedInformation.isHasLocalImports());
        Assert.assertTrue(parsedInformation.isHasHTTPImports());

    }

    // Tests 3 things:
    // CWL workflow with local imports
    // CWL workflow with HTTP imports
    // CWL workflow with HTTP imports and local imports and nested
    @Test
    public void testCDLLanguageParsingInformation() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        Workflow cwlWorkflow = workflowApi
                .manualRegister(SourceControl.GITHUB.name(), "dockstore-testing/md5sum-checker", "/md5sum/md5sum-workflow.cwl", "CWL",
                        DescriptorLanguage.CWL.toString(), "/test.json");
        Long cwlId = cwlWorkflow.getId();
        workflowApi.refresh(cwlId, false);
        Workflow workflow = workflowApi.getWorkflow(cwlId, null);
        WorkflowVersion workflowWithLocalImport = workflow.getWorkflowVersions().stream()
                .filter(version -> version.getName().equals("workflowWithLocalImport")).findFirst().get();
        ParsedInformation parsedInformation = workflowWithLocalImport.getVersionMetadata().getParsedInformationSet().get(0);
        Assert.assertTrue(parsedInformation.isHasLocalImports());
        Assert.assertFalse(parsedInformation.isHasHTTPImports());
        WorkflowVersion workflowWithHTTPImport = workflow.getWorkflowVersions().stream()
                .filter(version -> version.getName().equals("workflowWithHTTPImport")).findFirst().get();
        ParsedInformation parsedInformationHTTP = workflowWithHTTPImport.getVersionMetadata().getParsedInformationSet().get(0);
        Assert.assertFalse(parsedInformationHTTP.isHasLocalImports());
        Assert.assertTrue(parsedInformationHTTP.isHasHTTPImports());
        Workflow cwlChecker = workflowApi
                .manualRegister(SourceControl.GITHUB.name(), "dockstore-testing/md5sum-checker", "/checker-workflow-wrapping-workflow.cwl", "CWLChecker",
                        DescriptorLanguage.CWL.toString(), "/test.json");
        Long id = cwlChecker.getId();
        workflowApi.refresh(id, false);
        workflow = workflowApi.getWorkflow(id, null);
        WorkflowVersion workflowWithBothImports = workflow.getWorkflowVersions().stream()
                .filter(version -> version.getName().equals("workflowWithHTTPImport")).findFirst().get();
        parsedInformation = workflowWithBothImports.getVersionMetadata().getParsedInformationSet().get(0);
        Assert.assertTrue(parsedInformation.isHasLocalImports());
        Assert.assertTrue(parsedInformation.isHasHTTPImports());
    }

    @Test
    public void testStubRefresh() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        workflowApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl",
                "/test.json");
        workflowApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-wdl", "/dockstore.wdl", "",
                DescriptorLanguage.WDL.getShortName(), "");

        final List<Workflow> workflows = usersApi.userWorkflows(user.getId());

        for (Workflow workflow : workflows) {
            assertNotSame("", workflow.getWorkflowName());
        }

        assertTrue("workflow size was " + workflows.size(), workflows.size() > 1);
        assertTrue(
            "found non stub workflows " + workflows.stream().filter(workflow -> workflow.getMode() != Workflow.ModeEnum.STUB).count(),
            workflows.stream().allMatch(workflow -> workflow.getMode() == Workflow.ModeEnum.STUB));
    }

    @Test
    public void testTargettedRefresh() throws ApiException, URISyntaxException, IOException {

        testingPostgres.runUpdateStatement("update enduser set isadmin = 't' where username = 'DockstoreTestUser2';");

        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();
        Assert.assertNotEquals("getUser() endpoint should actually return the user profile", null, user.getUserProfiles());

        workflowApi.manualRegister(SourceControl.GITHUB.name(), DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "/Dockstore.cwl", "",
                DescriptorLanguage.CWL.getShortName(), "/test.json");
        workflowApi.manualRegister(SourceControl.BITBUCKET.name(), "dockstore_testuser2/dockstore-workflow", "/Dockstore.cwl", "",
                DescriptorLanguage.CWL.getShortName(), "/test.json");
        List<Workflow> workflows = usersApi.userWorkflows(user.getId());

        for (Workflow workflow : workflows) {
            assertNotSame("", workflow.getWorkflowName());
        }

        // do targetted refresh, should promote workflow to fully-fleshed out workflow
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, null, false);
        final Workflow refreshGithub = workflowApi.refresh(workflowByPathGithub.getId(), false);
        final Workflow workflowByPathBitbucket = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_DOCKSTORE_WORKFLOW, null, false);
        final Workflow refreshBitbucket = workflowApi.refresh(workflowByPathBitbucket.getId(), false);

        // tests for reference type for bitbucket workflows
        assertTrue("should see at least 4 branches", refreshBitbucket.getWorkflowVersions().stream()
            .filter(version -> version.getReferenceType() == WorkflowVersion.ReferenceTypeEnum.BRANCH).count() >= 4);
        assertTrue("should see at least 1 tags", refreshBitbucket.getWorkflowVersions().stream()
            .filter(version -> version.getReferenceType() == WorkflowVersion.ReferenceTypeEnum.TAG).count() >= 1);

        assertSame("github workflow is not in full mode", refreshGithub.getMode(), Workflow.ModeEnum.FULL);
        assertTrue("github workflow version count is wrong: " + refreshGithub.getWorkflowVersions().size(),
                4 <= refreshGithub.getWorkflowVersions().size());
        assertEquals("should find two versions with files for github workflow, found : " + refreshGithub.getWorkflowVersions().stream()
                .filter(workflowVersion -> !fileDAO.findSourceFilesByVersion(workflowVersion.getId()).isEmpty()).count(), 2,
            refreshGithub.getWorkflowVersions().stream().filter(workflowVersion -> !fileDAO.findSourceFilesByVersion(workflowVersion.getId()).isEmpty()).count());
        assertEquals("should find two valid versions for github workflow, found : " + refreshGithub.getWorkflowVersions().stream()
                .filter(WorkflowVersion::isValid).count(), 2,
            refreshGithub.getWorkflowVersions().stream().filter(WorkflowVersion::isValid).count());

        assertSame("bitbucket workflow is not in full mode", refreshBitbucket.getMode(), Workflow.ModeEnum.FULL);

        assertEquals("bitbucket workflow version count is wrong: " + refreshBitbucket.getWorkflowVersions().size(), 5,
            refreshBitbucket.getWorkflowVersions().size());
        assertEquals("should find 4 versions with files for bitbucket workflow, found : " + refreshBitbucket.getWorkflowVersions().stream()
                .filter(workflowVersion -> !fileDAO.findSourceFilesByVersion(workflowVersion.getId()).isEmpty()).count(), 4,
            refreshBitbucket.getWorkflowVersions().stream().filter(workflowVersion -> !fileDAO.findSourceFilesByVersion(workflowVersion.getId()).isEmpty()).count());
        assertEquals("should find 0 valid versions for bitbucket workflow, found : " + refreshBitbucket.getWorkflowVersions().stream()
                .filter(WorkflowVersion::isValid).count(), 0,
            refreshBitbucket.getWorkflowVersions().stream().filter(WorkflowVersion::isValid).count());

        // should not be able to get content normally
        Ga4GhApi anonymousGa4Ghv2Api = new Ga4GhApi(CommonTestUtilities.getWebClient(false, null, testingPostgres));
        Ga4GhApi adminGa4Ghv2Api = new Ga4GhApi(webClient);
        boolean exceptionThrown = false;
        try {
            anonymousGa4Ghv2Api
                .toolsIdVersionsVersionIdTypeDescriptorGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, "master");
        } catch (ApiException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
        FileWrapper adminToolDescriptor = adminGa4Ghv2Api
            .toolsIdVersionsVersionIdTypeDescriptorGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, "testCWL");
        assertTrue("could not get content via optional auth", adminToolDescriptor != null && !adminToolDescriptor.getContent().isEmpty());

        workflowApi.publish(refreshGithub.getId(), CommonTestUtilities.createPublishRequest(true));
        // check on URLs for workflows via ga4gh calls
        FileWrapper toolDescriptor = adminGa4Ghv2Api
            .toolsIdVersionsVersionIdTypeDescriptorGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, "testCWL");
        String content = IOUtils.toString(new URI(toolDescriptor.getUrl()), StandardCharsets.UTF_8);
        assertFalse(content.isEmpty());
        checkForRelativeFile(adminGa4Ghv2Api, "#workflow/" + DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, "testCWL", "Dockstore.cwl");

        // check on commit ids for github
        boolean allHaveCommitIds = refreshGithub.getWorkflowVersions().stream().noneMatch(version -> version.getCommitID().isEmpty());
        assertTrue("not all workflows seem to have commit ids", allHaveCommitIds);
    }

    @Test
    public void testHostedEditAndDelete() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();

        Workflow workflow = manualRegisterAndPublish(workflowApi, DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "", "cwl", SourceControl.GITHUB,
            "/Dockstore.cwl", false);

        // using hosted apis to delete normal workflow should fail
        HostedApi hostedApi = new HostedApi(webClient);
        try {
            hostedApi.deleteHostedWorkflowVersion(workflow.getId(), "v1.0");
            fail("Should throw API exception");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("cannot modify non-hosted entries this way"));
        }

        // using hosted apis to edit normal workflow should fail
        try {
            hostedApi.editHostedWorkflow(workflow.getId(), new ArrayList<>());
            fail("Should throw API exception");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("cannot modify non-hosted entries this way"));
        }
    }

    @Test
    public void testTableToolAndDagContent() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        Workflow workflow = manualRegisterAndPublish(workflowApi, "DockstoreTestUser2/cwl-gene-prioritization", "", "cwl", SourceControl.GITHUB, "/Dockstore.cwl", true);
        Assert.assertEquals("Other", workflow.getLicenseInformation().getLicenseName());
        WorkflowVersion branchVersion = workflow.getWorkflowVersions().stream().filter(wv -> wv.getName().equals("master")).findFirst().get();
        WorkflowVersion tagVersion = workflow.getWorkflowVersions().stream().filter(wv -> wv.getName().equals("test")).findFirst().get();

        // test getting tool table json on a branch and that it clears after refresh workflow
        String branchToolJsonFromApi = workflowApi.getTableToolContent(workflow.getId(), branchVersion.getId());
        String branchToolJson = testingPostgres.runSelectStatement(String.format("select tooltablejson from workflowversion where id = '%s'", branchVersion.getId()), String.class);
        assertNotNull(branchToolJson);
        assertFalse(branchToolJson.isEmpty());
        assertEquals(branchToolJsonFromApi, branchToolJson);

        workflow = workflowApi.refresh(workflow.getId(), true);
        branchToolJson = testingPostgres.runSelectStatement(String.format("select tooltablejson from workflowversion where id = '%s'", branchVersion.getId()), String.class);
        assertNull(branchToolJson);

        // Test getting tool table json for a tag and that only that version is cleared after a refreshVersion.
        String tagToolJsonFromApi = workflowApi.getTableToolContent(workflow.getId(), tagVersion.getId());
        String tagToolJson = testingPostgres.runSelectStatement(String.format("select tooltablejson from workflowversion where id = '%s'", tagVersion.getId()), String.class);
        assertNotNull(tagToolJson);
        assertFalse(tagToolJson.isEmpty());
        assertEquals(tagToolJsonFromApi, tagToolJson);

        workflowApi.getTableToolContent(workflow.getId(), branchVersion.getId());
        workflow = workflowApi.refreshVersion(workflow.getId(), tagVersion.getName(), true);
        tagToolJson = testingPostgres.runSelectStatement(String.format("select tooltablejson from workflowversion where id = '%s'", tagVersion.getId()), String.class);
        assertNull(tagToolJson);
        branchToolJson = testingPostgres.runSelectStatement(String.format("select tooltablejson from workflowversion where id = '%s'", branchVersion.getId()), String.class);
        assertNotNull(branchToolJson);

        // Test getting dag json for a branch and that it clears after a refresh workflow
        String branchDagJsonFromApi = workflowApi.getWorkflowDag(workflow.getId(), branchVersion.getId());
        String branchDagJson = testingPostgres.runSelectStatement(String.format("select dagjson from workflowversion where id = '%s'", branchVersion.getId()), String.class);
        assertNotNull(branchDagJson);
        assertFalse(branchDagJson.isEmpty());
        assertEquals(branchDagJsonFromApi, branchDagJson);

        workflow = workflowApi.refresh(workflow.getId(), true);
        branchDagJson = testingPostgres.runSelectStatement(String.format("select dagjson from workflowversion where id = '%s'", branchVersion.getId()), String.class);
        assertNull(branchDagJson);

        // Test getting dag json for a tag that only that version is cleared after a refreshVersion
        String tagDagJsonFromApi = workflowApi.getWorkflowDag(workflow.getId(), tagVersion.getId());
        String tagDagJson = testingPostgres.runSelectStatement(String.format("select dagjson from workflowversion where id = '%s'", tagVersion.getId()), String.class);
        assertNotNull(tagDagJson);
        assertFalse(tagDagJson.isEmpty());
        assertEquals(tagDagJsonFromApi, tagDagJson);

        workflowApi.getWorkflowDag(workflow.getId(), branchVersion.getId());
        workflowApi.refreshVersion(workflow.getId(), tagVersion.getName(), true);
        tagDagJson = testingPostgres.runSelectStatement(String.format("select dagjson from workflowversion where id = '%s'", tagVersion.getId()), String.class);
        assertNull(tagDagJson);
        branchDagJson = testingPostgres.runSelectStatement(String.format("select dagjson from workflowversion where id = '%s'", branchVersion.getId()), String.class);
        assertNotNull(branchDagJson);

        // Test json is cleared after an organization refresh
        UsersApi usersApi = new UsersApi(webClient);
        long userId = 1;
        workflow = workflowApi.refresh(workflow.getId(), true);

        final List<Workflow> workflows = usersApi.userWorkflows(userId);
        branchDagJson = testingPostgres.runSelectStatement(String.format("select dagjson from workflowversion where id = '%s'", branchVersion.getId()), String.class);
        assertNull(branchDagJson);
        branchToolJson = testingPostgres.runSelectStatement(String.format("select tooltablejson from workflowversion where id = '%s'", branchVersion.getId()), String.class);
        assertNull(branchToolJson);

        // Test freezing versions
        tagVersion.setFrozen(true);

        List<WorkflowVersion> versions = workflowApi.updateWorkflowVersion(workflow.getId(), Collections.singletonList(tagVersion));
        WorkflowVersion frozenVersion = versions.stream().filter(version -> version.getName().equals("test")).findFirst().get();
        String frozenDagJson = testingPostgres.runSelectStatement(String.format("select dagjson from workflowversion where id = '%s'", frozenVersion.getId()), String.class);
        String frozenToolTableJson = testingPostgres.runSelectStatement(String.format("select tooltablejson from workflowversion where id = '%s'", frozenVersion.getId()), String.class);
        assertNotNull(frozenDagJson);
        assertNotNull(frozenToolTableJson);
    }
    /**
     * This tests that you are able to download zip files for versions of a workflow
     */
    @Test
    public void downloadZipFile() throws IOException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        // Register and refresh workflow
        Workflow workflow = workflowApi
            .manualRegister(SourceControl.GITHUB.getFriendlyName(), "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.cwl",
                "test", "cwl", null);
        Workflow refresh = workflowApi.refresh(workflow.getId(), false);
        Long workflowId = refresh.getId();
        WorkflowVersion workflowVersion = refresh.getWorkflowVersions().get(0);
        Long versionId = workflowVersion.getId();

        // Download unpublished workflow version
        workflowApi.getWorkflowZip(workflowId, versionId);
        byte[] arbitraryURL = CommonTestUtilities.getArbitraryURL("/workflows/" + workflowId + "/zip/" + versionId, new GenericType<byte[]>() {
        }, webClient);
        File tempZip = File.createTempFile("temp", "zip");
        Path write = Files.write(tempZip.toPath(), arbitraryURL);
        ZipFile zipFile = new ZipFile(write.toFile());
        assertTrue("zip file seems incorrect",
            zipFile.stream().map(ZipEntry::getName).collect(Collectors.toList()).contains("md5sum/md5sum-workflow.cwl"));

        // should not be able to get zip anonymously before publication
        boolean thrownException = false;
        try {
            CommonTestUtilities.getArbitraryURL("/workflows/" + workflowId + "/zip/" + versionId, new GenericType<byte[]>() {
            }, CommonTestUtilities.getWebClient(false, null, testingPostgres));
        } catch (Exception e) {
            thrownException = true;
        }
        assertTrue(thrownException);
        tempZip.deleteOnExit();

        // Download published workflow version
        workflowApi.publish(workflowId, CommonTestUtilities.createPublishRequest(true));
        arbitraryURL = CommonTestUtilities.getArbitraryURL("/workflows/" + workflowId + "/zip/" + versionId, new GenericType<byte[]>() {
        }, CommonTestUtilities.getWebClient(false, null, testingPostgres));
        File tempZip2 = File.createTempFile("temp", "zip");
        write = Files.write(tempZip2.toPath(), arbitraryURL);
        zipFile = new ZipFile(write.toFile());
        assertTrue("zip file seems incorrect",
            zipFile.stream().map(ZipEntry::getName).collect(Collectors.toList()).contains("md5sum/md5sum-workflow.cwl"));
        tempZip2.deleteOnExit();
    }

    /**
     * This tests a not found zip file
     */
    @Test
    public void sillyWorkflowZipFile() throws IOException {
        final ApiClient anonWebClient = CommonTestUtilities.getWebClient(false, null, testingPostgres);
        WorkflowsApi anonWorkflowApi = new WorkflowsApi(anonWebClient);
        boolean success = false;
        try {
            anonWorkflowApi.getWorkflowZip(100000000L, 1000000L);
        } catch (ApiException ex) {
            assertEquals(ex.getCode(), HttpStatus.SC_NOT_FOUND);
            success = true;
        }
        assertTrue("should have got 404", success);
    }

    /**
     * This tests that zip file can be downloaded or not based on published state and auth.
     */
    @Test
    public void downloadZipFileTestAuth() {
        final ApiClient ownerWebClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi ownerWorkflowApi = new WorkflowsApi(ownerWebClient);

        final ApiClient anonWebClient = CommonTestUtilities.getWebClient(false, null, testingPostgres);
        WorkflowsApi anonWorkflowApi = new WorkflowsApi(anonWebClient);

        final ApiClient otherUserWebClient = CommonTestUtilities.getWebClient(true, OTHER_USERNAME, testingPostgres);
        WorkflowsApi otherUserWorkflowApi = new WorkflowsApi(otherUserWebClient);

        // Register and refresh workflow
        Workflow workflow = ownerWorkflowApi
            .manualRegister(SourceControl.GITHUB.getFriendlyName(), "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.cwl",
                "test", "cwl", null);
        Workflow refresh = ownerWorkflowApi.refresh(workflow.getId(), false);
        Long workflowId = refresh.getId();
        Long versionId = refresh.getWorkflowVersions().get(0).getId();

        // Try downloading unpublished
        // Owner: Should pass
        ownerWorkflowApi.getWorkflowZip(workflowId, versionId);
        // Anon: Should fail
        boolean success = true;
        try {
            anonWorkflowApi.getWorkflowZip(workflowId, versionId);
        } catch (ApiException ex) {
            success = false;
        } finally {
            assertFalse(success);
        }
        // Other user: Should fail
        success = true;
        try {
            otherUserWorkflowApi.getWorkflowZip(workflowId, versionId);
        } catch (ApiException ex) {
            success = false;
        } finally {
            assertFalse(success);
        }

        // Publish
        PublishRequest publishRequest = CommonTestUtilities.createPublishRequest(true);
        ownerWorkflowApi.publish(workflowId, publishRequest);

        // Try downloading published
        // Owner: Should pass
        ownerWorkflowApi.getWorkflowZip(workflowId, versionId);
        // Anon: Should pass
        anonWorkflowApi.getWorkflowZip(workflowId, versionId);
        // Other user: Should pass
        otherUserWorkflowApi.getWorkflowZip(workflowId, versionId);
    }

    /**
     * Downloads and verifies dockstore-testing/gatk-sv-clinical, a complex WDL workflow with lots
     * of imports
     */
    @Test
    public void downloadZipComplex() throws IOException {
        final ApiClient ownerWebClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi ownerWorkflowApi = new WorkflowsApi(ownerWebClient);
        Workflow refresh = registerGatkSvWorkflow(ownerWorkflowApi);

        Long workflowId = refresh.getId();
        Long versionId = refresh.getWorkflowVersions().get(0).getId();

        // Try downloading unpublished
        // Owner: Should pass
        ownerWorkflowApi.getWorkflowZip(workflowId, versionId);

        // Unfortunately, the generated Swagger client for getWorkflowZip returns void.
        // Verify the zip contents by making the server side calls
        final io.dockstore.webservice.core.Workflow dockstoreWorkflow = workflowDAO.findById(workflowId);
        final Optional<io.dockstore.webservice.core.WorkflowVersion> version = dockstoreWorkflow.getWorkflowVersions().stream()
                .filter(wv -> wv.getId() == versionId).findFirst();
        Assert.assertTrue(version.isPresent());
        final File tempFile = File.createTempFile("dockstore-test", ".zip");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            final List<io.dockstore.webservice.core.SourceFile> sourceFiles = fileDAO.findSourceFilesByVersion(version.get().getId());

            new EntryVersionHelperImpl().writeStreamAsZip(sourceFiles.stream().collect(Collectors.toSet()), fos, Paths.get("/GATKSVPipelineClinical.wdl"));
            final ZipFile zipFile = new ZipFile(tempFile);
            final long wdlCount = zipFile.stream().filter(e -> e.getName().endsWith(".wdl")).count();
            Assert.assertEquals(sourceFiles.size(), wdlCount);
            zipFile.stream().filter(e -> e.getName().endsWith(".wdl")).forEach(e -> {
                final String name = "/" + e.getName();
                Assert.assertTrue("expected " + name, sourceFiles.stream().anyMatch(sf -> sf.getAbsolutePath().equals(name)));
            });
            zipFile.close();
        } finally {
            FileUtils.deleteQuietly(tempFile);
        }
        DockstoreWebserviceApplication application = SUPPORT.getApplication();
        SessionFactory sessionFactory = application.getHibernate().getSessionFactory();
        sessionFactory.getCurrentSession().close();
    }

    /**
     * Tests GA4GH endpoint. Ideally this would be in GA4GH*IT, but because we're manually registering
     * the workflow, putting it here
     */
    @Test
    public void testGa4ghEndpointForComplexWdlWorkflow() {
        final ApiClient ownerWebClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi ownerWorkflowApi = new WorkflowsApi(ownerWebClient);
        Workflow refresh = registerGatkSvWorkflow(ownerWorkflowApi);
        ownerWorkflowApi.publish(refresh.getId(), CommonTestUtilities.createPublishRequest(true));
        final List<io.dockstore.webservice.core.SourceFile> sourceFiles = fileDAO.findSourceFilesByVersion(refresh.getWorkflowVersions().stream()
                .filter(workflowVersion -> GATK_SV_TAG.equals(workflowVersion.getName())).findFirst().get().getId());
        final Ga4GhApi ga4GhApi = new Ga4GhApi(ownerWebClient);
        final List<ToolFile> files = ga4GhApi
                .toolsIdVersionsVersionIdTypeFilesGet("WDL", "#workflow/" + refresh.getFullWorkflowPath(), GATK_SV_TAG);
        Assert.assertEquals(1, files.stream().filter(f -> f.getFileType() == ToolFile.FileTypeEnum.PRIMARY_DESCRIPTOR).count());
        Assert.assertEquals(sourceFiles.size() - 1, files.stream().filter(f -> f.getFileType() == ToolFile.FileTypeEnum.SECONDARY_DESCRIPTOR).count());
        files.forEach(file -> {
            final String path = file.getPath();
            // TRS paths are relative
            Assert.assertTrue(sourceFiles.stream().anyMatch(sf -> sf.getAbsolutePath().equals("/" + path)));
        });
    }

    @Test
    public void testCheckerWorkflowDownloadBasedOnCredentials() throws IOException {
        testingPostgres.runUpdateStatement("update enduser set isadmin = 't' where username = 'DockstoreTestUser2';");

        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        final ApiClient webClientNoAccess = getWebClient(USER_1_USERNAME, testingPostgres);
        WorkflowsApi workflowApiNoAccess = new WorkflowsApi(webClientNoAccess);

        Workflow workflow = workflowApi
            .manualRegister(SourceControl.GITHUB.getFriendlyName(), "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.cwl",
                "test", "cwl", null);
        Workflow refresh = workflowApi.refresh(workflow.getId(), false);
        assertFalse(refresh.isIsPublished());
        workflowApi.registerCheckerWorkflow("checker-workflow-wrapping-workflow.cwl", workflow.getId(), "cwl", "checker-input-cwl.json");
        workflowApi.refresh(workflow.getId(), false);

        final String fileWithIncorrectCredentials = ResourceHelpers.resourceFilePath("config_file.txt");
        final String fileWithCorrectCredentials = ResourceHelpers.resourceFilePath("config_file2.txt");

        final Long versionId = refresh.getWorkflowVersions().get(0).getId();

        // should be able to download properly with correct credentials even though the workflow is not published
        workflowApi.getWorkflowZip(refresh.getId(), versionId);

        // Publish the workflow
        workflowApi.publish(refresh.getId(), CommonTestUtilities.createPublishRequest(true));

        // Owner should still have access
        workflowApiNoAccess.getWorkflowZip(refresh.getId(), versionId);

        // should be able to download properly with incorrect credentials because the entry is published
        workflowApiNoAccess.getWorkflowZip(refresh.getId(), versionId);

        // Unpublish the workflow
        workflowApi.publish(refresh.getId(), CommonTestUtilities.createPublishRequest(false));

        // should not be able to download properly with incorrect credentials because the entry is not published
        try {
            workflowApiNoAccess.getWorkflowZip(refresh.getId(), versionId);
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Forbidden"));
        }
    }

    @Test
    public void testNextflowRefresh() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        Workflow workflowByPathGithub = manualRegisterAndPublish(workflowApi, "DockstoreTestUser2/rnatoy", "", "nfl", SourceControl.GITHUB,
            "/nextflow.config", false);

        // need to set paths properly
        workflowByPathGithub.setWorkflowPath("/nextflow.config");
        workflowByPathGithub.setDescriptorType(DescriptorTypeEnum.NFL);
        workflowApi.updateWorkflow(workflowByPathGithub.getId(), workflowByPathGithub);

        workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_NEXTFLOW_LIB_WORKFLOW, null, false);
        final Workflow refreshGithub = workflowApi.refresh(workflowByPathGithub.getId(), false);

        assertSame("github workflow is not in full mode", refreshGithub.getMode(), Workflow.ModeEnum.FULL);

        // look that branches and tags are typed correctly for workflows on GitHub
        assertTrue("should see at least 6 branches", refreshGithub.getWorkflowVersions().stream()
            .filter(version -> version.getReferenceType() == WorkflowVersion.ReferenceTypeEnum.BRANCH).count() >= 6);
        assertTrue("should see at least 6 tags", refreshGithub.getWorkflowVersions().stream()
            .filter(version -> version.getReferenceType() == WorkflowVersion.ReferenceTypeEnum.TAG).count() >= 6);

        assertEquals("github workflow version count is wrong: " + refreshGithub.getWorkflowVersions().size(), 12,
            refreshGithub.getWorkflowVersions().size());
        assertEquals("should find 12 versions with files for github workflow, found : " + refreshGithub.getWorkflowVersions().stream()
                .filter(workflowVersion -> !fileDAO.findSourceFilesByVersion(workflowVersion.getId()).isEmpty()).count(), 12,
            refreshGithub.getWorkflowVersions().stream().filter(workflowVersion -> !fileDAO.findSourceFilesByVersion(workflowVersion.getId()).isEmpty()).count());
        assertEquals("should find 12 valid versions for github workflow, found : " + refreshGithub.getWorkflowVersions().stream()
                .filter(WorkflowVersion::isValid).count(), 12,
            refreshGithub.getWorkflowVersions().stream().filter(WorkflowVersion::isValid).count());

        // nextflow version should have
        assertTrue("should find 2 files for each version for now: " + refreshGithub.getWorkflowVersions().stream()
                .filter(workflowVersion -> fileDAO.findSourceFilesByVersion(workflowVersion.getId()).size() != 2).count(),
            refreshGithub.getWorkflowVersions().stream().noneMatch(workflowVersion -> fileDAO.findSourceFilesByVersion(workflowVersion.getId()).size() != 2));

        // check that container is properly parsed
        Optional<WorkflowVersion> nextflow = refreshGithub.getWorkflowVersions().stream()
            .filter(workflow -> workflow.getName().equals("master")).findFirst();
        String workflowDag = workflowApi.getWorkflowDag(refreshGithub.getId(), nextflow.get().getId());
        ArrayList<String> dagList = Lists.newArrayList(workflowDag);

        Assert.assertTrue("Should use nextflow/rnatoy and not ubuntu:latest", dagList.get(0)
            .contains("\"docker\":\"nextflow/rnatoy@sha256:9ac0345b5851b2b20913cb4e6d469df77cf1232bafcadf8fd929535614a85c75\""));
    }

    @Test
    public void testNextflowWorkflowWithConfigIncludes() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        UsersApi usersApi = new UsersApi(webClient);

        Workflow workflowByPathGithub = manualRegisterAndPublish(workflowApi, "DockstoreTestUser2/vipr", "", "nfl", SourceControl.GITHUB,
            "/nextflow.config", false);

        // need to set paths properly
        workflowByPathGithub.setWorkflowPath("/nextflow.config");
        workflowByPathGithub.setDescriptorType(DescriptorTypeEnum.NFL);
        workflowApi.updateWorkflow(workflowByPathGithub.getId(), workflowByPathGithub);

        workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_INCLUDECONFIG_WORKFLOW, null, false);
        final Workflow refreshGithub = workflowApi.refresh(workflowByPathGithub.getId(), false);

        assertEquals("workflow does not include expected config included files", 3,
            fileDAO.findSourceFilesByVersion(refreshGithub.getWorkflowVersions().stream().filter(version -> version.getName().equals("master")).findFirst().get().getId())
                    .stream().filter(file -> file.getPath().startsWith("conf/")).count());
    }

    @Test
    public void testNextflowWorkflowWithImages() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        Workflow workflowByPathGithub = manualRegisterAndPublish(workflowApi, "DockstoreTestUser2/galaxy-workflows", "", "nfl", SourceControl.GITHUB,
            "/nextflow.config", false);

        // need to set paths properly
        workflowByPathGithub.setWorkflowPath("/nextflow.config");
        workflowByPathGithub.setDescriptorType(DescriptorTypeEnum.NFL);
        workflowApi.updateWorkflow(workflowByPathGithub.getId(), workflowByPathGithub);

        workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_NEXTFLOW_DOCKER_WORKFLOW, null, false);
        final Workflow refreshGithub = workflowApi.refresh(workflowByPathGithub.getId(), false);

        assertSame("github workflow is not in full mode", refreshGithub.getMode(), Workflow.ModeEnum.FULL);
        Optional<WorkflowVersion> first = refreshGithub.getWorkflowVersions().stream().filter(version -> version.getName().equals("1.0"))
            .findFirst();
        String tableToolContent = workflowApi.getTableToolContent(refreshGithub.getId(), first.get().getId());
        String workflowDag = workflowApi.getWorkflowDag(refreshGithub.getId(), first.get().getId());
        assertFalse(tableToolContent.isEmpty());
        assertFalse(workflowDag.isEmpty());
        Gson gson = new Gson();
        List<Map<String, String>> list = gson.fromJson(tableToolContent, List.class);
        Map<Map, List> map = gson.fromJson(workflowDag, Map.class);
        assertTrue("tool table should be present", list.size() >= 9);
        long dockerCount = list.stream().filter(tool -> !tool.get("docker").isEmpty()).count();
        assertEquals("tool table is populated with docker images", dockerCount, list.size());
        assertTrue("workflow dag should be present", map.entrySet().size() >= 2);
        assertTrue("workflow dag is not as large as expected", map.get("nodes").size() >= 11 && map.get("edges").size() >= 13);
    }

    /**
     * This test checks that a user can successfully refresh their workflows (only stubs)
     *
     * @throws ApiException
     */
    @Test
    public void testRefreshAllForAUser() throws ApiException {

        testingPostgres.runUpdateStatement("update enduser set isadmin = 't' where username = 'DockstoreTestUser2';");
        long userId = 1;

        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        UsersApi usersApi = new UsersApi(webClient);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        io.dockstore.openapi.client.ApiClient openAPIWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        refreshByOrganizationReplacement(workflowApi, openAPIWebClient);

        List<Workflow> workflows = usersApi.userWorkflows(userId);

        // Check that there are multiple workflows
        final long count = testingPostgres.runSelectStatement("select count(*) from workflow", long.class);
        assertTrue("Workflow entries should exist", count > 0);

        // Check that there are only stubs (no workflow version)
        final long count2 = testingPostgres.runSelectStatement("select count(*) from workflowversion", long.class);
        assertEquals("No entries in workflowversion", 0, count2);
        final long count3 = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("No workflows are in full mode", 0, count3);

        // check that a nextflow workflow made it
        long nfWorkflowCount = workflows.stream().filter(w -> w.getGitUrl().contains("mta-nf")).count();
        assertTrue("Nextflow workflow not found", nfWorkflowCount > 0);
        Workflow mtaNf = workflows.stream().filter(w -> w.getGitUrl().contains("mta-nf")).findFirst().get();
        mtaNf.setWorkflowPath("/nextflow.config");
        mtaNf.setDescriptorType(DescriptorTypeEnum.NFL);
        workflowApi.updateWorkflow(mtaNf.getId(), mtaNf);
        workflowApi.refresh(mtaNf.getId(), false);
        // publish this way? (why is the auto-generated variable private?)
        workflowApi.publish(mtaNf.getId(), CommonTestUtilities.createPublishRequest(true));
        mtaNf = workflowApi.getWorkflow(mtaNf.getId(), null);
        Assert.assertTrue("a workflow lacks a date", mtaNf.getLastModifiedDate() != null && mtaNf.getLastModified() != 0);
        assertNotNull("Nextflow workflow not found after update", mtaNf);
        assertTrue("nextflow workflow should have at least two versions", mtaNf.getWorkflowVersions().size() >= 2);

        int numOfSourceFiles = mtaNf.getWorkflowVersions().stream().mapToInt(version -> fileDAO.findSourceFilesByVersion(version.getId()).size()).sum();
        assertTrue("nextflow workflow should have at least two sourcefiles", numOfSourceFiles >= 2);

        long scriptCount = mtaNf.getWorkflowVersions().stream()
                .mapToLong(version -> fileDAO.findSourceFilesByVersion(version.getId()).stream().filter(file -> file.getType() == DescriptorLanguage.FileType.NEXTFLOW).count())
                .sum();

        long configCount = mtaNf.getWorkflowVersions().stream()
                .mapToLong(version -> fileDAO.findSourceFilesByVersion(version.getId()).stream().filter(file -> file.getType() == DescriptorLanguage.FileType.NEXTFLOW_CONFIG).count())
                .sum();
        assertTrue("nextflow workflow should have at least one config file and one script file", scriptCount >= 1 && configCount >= 1);

        // check that we can pull down the nextflow workflow via the ga4gh TRS API
        Ga4GhApi ga4Ghv2Api = new Ga4GhApi(webClient);
        List<Tool> toolV2s = ga4Ghv2Api.toolsGet(null, null, null, null, null, null, null, null, null, null, null);
        String mtaWorkflowID = "#workflow/github.com/DockstoreTestUser2/mta-nf";
        Tool toolV2 = ga4Ghv2Api.toolsIdGet(mtaWorkflowID);
        assertTrue("could get mta as part of list",
            toolV2s.size() > 0 && toolV2s.stream().anyMatch(tool -> Objects.equals(tool.getId(), mtaWorkflowID)));
        assertNotNull("could get mta as a specific tool", toolV2);

        // Check that a workflow from my namespace is present
        assertTrue("Should have at least one repo from DockstoreTestUser2.",
            workflows.stream().anyMatch((Workflow workflow) -> workflow.getOrganization().equalsIgnoreCase("DockstoreTestUser2")));

        // Check that a workflow from an organization I belong to is present
        assertTrue("Should have repository basic-workflow from organization dockstoretesting.", workflows.stream().anyMatch(
            (Workflow workflow) -> workflow.getOrganization().equalsIgnoreCase("dockstoretesting") && workflow.getRepository()
                .equalsIgnoreCase("basic-workflow")));

        // Check that a workflow that I am a collaborator on is present
        assertTrue("Should have repository dockstore-whalesay-2 from DockstoreTestUser.", workflows.stream().anyMatch(
            (Workflow workflow) -> workflow.getOrganization().equalsIgnoreCase("DockstoreTestUser") && workflow.getRepository()
                .equalsIgnoreCase("dockstore-whalesay-2")));

        // Check that for a repo from my organization that I forked to DockstoreTestUser2, that it along with the original repo are present
        assertTrue("Should have two repos with name basic-workflow, one from DockstoreTestUser2 and one from dockstoretesting.",
            2 <= workflows.stream().filter((Workflow workflow) ->
                (workflow.getOrganization().equalsIgnoreCase("dockstoretesting") || workflow.getOrganization()
                    .equalsIgnoreCase("DockstoreTestUser2")) && workflow.getRepository().equalsIgnoreCase("basic-workflow")).count());

    }

    private void refreshByOrganizationReplacement(WorkflowsApi workflowApi, io.dockstore.openapi.client.ApiClient openAPIWebClient) {
        io.dockstore.openapi.client.api.UsersApi openUsersApi = new io.dockstore.openapi.client.api.UsersApi(openAPIWebClient);
        for (SourceControl control : SourceControl.values()) {
            List<String> userOrganizations = openUsersApi.getUserOrganizations(control.name());
            for (String org : userOrganizations) {
                List<Repository> userOrganizationRepositories = openUsersApi.getUserOrganizationRepositories(control.name(), org);
                for (Repository repo : userOrganizationRepositories) {
                    workflowApi.manualRegister(control.name(), repo.getPath(), "/Dockstore.cwl", "",
                            DescriptorLanguage.CWL.getShortName(), "");
                }
            }
        }
    }

    /**
     * This test does not use admin rights, note that a number of operations go through the UserApi to get this to work
     *
     * @throws ApiException exception used for errors coming back from the web service
     */
    @Test
    public void testPublishingAndListingOfPublished() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        io.dockstore.openapi.client.ApiClient openAPIWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);

        // should start with nothing published
        assertTrue("should start with nothing published ",
            workflowApi.allPublishedWorkflows(null, null, null, null, null, false).isEmpty());
        // refresh just for the current user
        UsersApi usersApi = new UsersApi(webClient);

        refreshByOrganizationReplacement(workflowApi, openAPIWebClient);

        assertTrue("should remain with nothing published ",
            workflowApi.allPublishedWorkflows(null, null, null, null, null, false).isEmpty());
        // ensure that sorting or filtering don't expose unpublished workflows
        assertTrue("should start with nothing published ",
            workflowApi.allPublishedWorkflows(null, null, null, "descriptorType", "asc", false).isEmpty());
        assertTrue("should start with nothing published ",
            workflowApi.allPublishedWorkflows(null, null, "hello", null, null, false).isEmpty());
        assertTrue("should start with nothing published ",
            workflowApi.allPublishedWorkflows(null, null, "hello", "descriptorType", "asc", false).isEmpty());

        // assertTrue("should have a bunch of stub workflows: " +  usersApi..allWorkflows().size(), workflowApi.allWorkflows().size() == 4);

        final Workflow workflowByPath = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, null, false);
        // refresh targeted
        workflowApi.refresh(workflowByPath.getId(), false);

        // publish one
        final PublishRequest publishRequest = CommonTestUtilities.createPublishRequest(true);
        workflowApi.publish(workflowByPath.getId(), publishRequest);
        assertEquals("should have one published, found  " + workflowApi.allPublishedWorkflows(null, null, null, null, null, false).size(),
            1, workflowApi.allPublishedWorkflows(null, null, null, null, null, false).size());
        final Workflow publishedWorkflow = workflowApi.getPublishedWorkflow(workflowByPath.getId(), null);
        assertNotNull("did not get published workflow", publishedWorkflow);
        final Workflow publishedWorkflowByPath = workflowApi
            .getPublishedWorkflowByPath(DOCKSTORE_TEST_USER2_HELLO_DOCKSTORE_WORKFLOW, null, false, null);
        assertNotNull("did not get published workflow", publishedWorkflowByPath);

        // publish everything so pagination testing makes more sense (going to unfortunately use rate limit)
        Lists.newArrayList("github.com/" + DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME,
            "github.com/DockstoreTestUser2/dockstore-whalesay-imports", "github.com/DockstoreTestUser2/parameter_test_workflow")
            .forEach(path -> {
                Workflow workflow = workflowApi.getWorkflowByPath(path, null, false);
                workflowApi.refresh(workflow.getId(), false);
                workflowApi.publish(workflow.getId(), publishRequest);
            });
        List<Workflow> workflows = workflowApi.allPublishedWorkflows(null, null, null, null, null, false);
        // test offset
        assertEquals("offset does not seem to be working",
            workflowApi.allPublishedWorkflows("1", null, null, null, null, false).get(0).getId(), workflows.get(1).getId());
        // test limit
        assertEquals(1, workflowApi.allPublishedWorkflows(null, 1, null, null, null, false).size());
        // test custom sort column
        List<Workflow> ascId = workflowApi.allPublishedWorkflows(null, null, null, "id", "asc", false);
        List<Workflow> descId = workflowApi.allPublishedWorkflows(null, null, null, "id", "desc", false);
        assertEquals("sort by id does not seem to be working", ascId.get(0).getId(), descId.get(descId.size() - 1).getId());
        // test filter
        List<Workflow> filteredLowercase = workflowApi.allPublishedWorkflows(null, null, "whale", "stars", null, false);
        assertEquals(1, filteredLowercase.size());
        filteredLowercase.forEach(workflow -> assertNull(workflow.getAliases()));
        List<Workflow> filteredUppercase = workflowApi.allPublishedWorkflows(null, null, "WHALE", "stars", null, false);
        assertEquals(1, filteredUppercase.size());
        assertEquals(filteredLowercase, filteredUppercase);
    }

    /**
     * Tests manual registration and publishing of a github and bitbucket workflow
     *
     * @throws ApiException exception used for errors coming back from the web service
     */
    @Test
    public void testManualRegisterThenPublish() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        // Make publish request (true)
        final PublishRequest publishRequest = CommonTestUtilities.createPublishRequest(true);

        // Manually register workflow github
        Workflow githubWorkflow = workflowApi
            .manualRegister("github", DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "/Dockstore.wdl", "altname", "wdl", "/test.json");

        // Manually register workflow bitbucket
        Workflow bitbucketWorkflow = workflowApi
            .manualRegister("bitbucket", "dockstore_testuser2/dockstore-workflow", "/Dockstore.cwl", "altname", "cwl", "/test.json");

        // Assert some things
        final long count = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("No workflows are in full mode", 0, count);
        final long count2 = testingPostgres.runSelectStatement("select count(*) from workflow where workflowname = 'altname'", long.class);
        assertEquals("There should be two workflows with name altname, there are " + count2, 2, count2);

        // Publish github workflow
        workflowApi.refresh(githubWorkflow.getId(), false);
        workflowApi.publish(githubWorkflow.getId(), publishRequest);

        // Assert some things
        assertEquals("should have two published, found  " + workflowApi.allPublishedWorkflows(null, null, null, null, null, false).size(),
            1, workflowApi.allPublishedWorkflows(null, null, null, null, null, false).size());
        final long count3 = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("One workflow is in full mode", 1, count3);
        final long count4 = testingPostgres.runSelectStatement("select count(*) from workflowversion where valid = 't'", long.class);
        assertEquals("There should be 2 valid version tags, there are " + count4, 2, count4);

        workflowApi.refresh(bitbucketWorkflow.getId(), false);
        thrown.expect(ApiException.class);
        // Publish bitbucket workflow, will fail now since the workflow test case is actually invalid now
        workflowApi.publish(bitbucketWorkflow.getId(), publishRequest);
    }

    /**
     * Tests that the info for quay images included in CWL workflows are grabbed and that the trs endpoints convert this info correctly
     */
    @Test
    public void testGettingImagesFromQuay() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(webClient);
        final io.dockstore.openapi.client.ApiClient openAPIClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        Ga4Ghv20Api ga4Ghv20Api = new Ga4Ghv20Api(openAPIClient);

        //Check image info is grabbed
        WorkflowVersion version = snapshotWorkflowVersion(workflowsApi, "dockstore-testing/hello_world", DescriptorType.CWL.toString(), "/hello_world.cwl", "1.0.1");
        assertEquals("Should only be one image in this workflow", 1, version.getImages().size());
        verifyImageChecksumsAreSaved(version);

        List<ToolVersion> versions = ga4Ghv20Api.toolsIdVersionsGet("#workflow/github.com/dockstore-testing/hello_world");
        verifyTRSImageConversion(versions, "1.0.1", 1);

        // Test that a workflow version containing an unversioned image isn't saved
        WorkflowVersion workflowVersionWithoutVersionedImage = snapshotWorkflowVersion(workflowsApi, "dockstore-testing/tools-cwl-workflow-experiments", DescriptorType.CWL.toString(), "/cwl/workflow_docker.cwl", "1.0");
        assertEquals("Should not have grabbed any images", 0, workflowVersionWithoutVersionedImage.getImages().size());

        // Test that a workflow version that contains duplicate images will not store multiples
        WorkflowVersion versionWithDuplicateImages = snapshotWorkflowVersion(workflowsApi, "dockstore-testing/zhanghj-8555114", DescriptorType.CWL.toString(), "/main.cwl", "1.0");
        assertEquals("Should have grabbed 3 images", 3, versionWithDuplicateImages.getImages().size());
        verifyImageChecksumsAreSaved(versionWithDuplicateImages);
        versions = ga4Ghv20Api.toolsIdVersionsGet("#workflow/github.com/dockstore-testing/zhanghj-8555114");
        verifyTRSImageConversion(versions, "1.0", 3);
    }

    @Test
    public void testHiddenAndDefaultVersions() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(webClient);
        HostedApi hostedApi = new HostedApi(webClient);
        Workflow workflow = workflowsApi.manualRegister("github", DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "/Dockstore.wdl", "", DescriptorLanguage.WDL.toString(), "/test.json");

        workflow = workflowsApi.refresh(workflow.getId(), false);
        List<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
        WorkflowVersion version = workflowVersions.stream().filter(w -> w.getReference().equals("testBoth")).findFirst().get();
        version.setHidden(true);
        workflowsApi.updateWorkflowVersion(workflow.getId(), Collections.singletonList(version));

        try {
            workflow = workflowsApi.updateWorkflowDefaultVersion(workflow.getId(), version.getName());
            fail("Shouldn't be able to set the default version to one that is hidden.");
        } catch (ApiException ex) {
            Assert.assertEquals("You can not set the default version to a hidden version.", ex.getMessage());
        }

        // Set the default version to a non-hidden version
        version.setHidden(false);
        workflowsApi.updateWorkflowVersion(workflow.getId(), Collections.singletonList(version));
        workflow = workflowsApi.updateWorkflowDefaultVersion(workflow.getId(), version.getName());

        // Should not be able to hide a default version
        version.setHidden(true);
        try {
            workflowsApi.updateWorkflowVersion(workflow.getId(), Collections.singletonList(version));
            fail("Should not be able to hide a default version");
        } catch (ApiException ex) {
            Assert.assertEquals("You cannot hide the default version.", ex.getMessage());
        }

        // Test same for hosted workflows
        Workflow hostedWorkflow = hostedApi.createHostedWorkflow("awesomeTool", null, CWL.getShortName(), null, null);
        SourceFile file = new SourceFile();
        file.setContent("cwlVersion: v1.0\n" + "class: Workflow");
        file.setType(SourceFile.TypeEnum.DOCKSTORE_CWL);
        file.setPath("/Dockstore.cwl");
        file.setAbsolutePath("/Dockstore.cwl");
        hostedWorkflow = hostedApi.editHostedWorkflow(hostedWorkflow.getId(), Lists.newArrayList(file));

        WorkflowVersion hostedVersion = workflowsApi.getWorkflowVersions(hostedWorkflow.getId()).get(0);
        hostedVersion.setHidden(true);
        try {
            workflowsApi.updateWorkflowVersion(hostedWorkflow.getId(), Collections.singletonList(hostedVersion));
            fail("Shouldn't be able to hide the default version.");
        } catch (ApiException ex) {
            Assert.assertEquals("You cannot hide the default version.", ex.getMessage());
        }

        file.setContent("cwlVersion: v1.0\n\n" + "class: Workflow");
        hostedWorkflow = hostedApi.editHostedWorkflow(hostedWorkflow.getId(), Lists.newArrayList(file));
        hostedVersion = workflowsApi.getWorkflowVersions(hostedWorkflow.getId()).stream().filter(v -> v.getName().equals("1")).findFirst().get();
        hostedVersion.setHidden(true);
        workflowsApi.updateWorkflowVersion(hostedWorkflow.getId(), Collections.singletonList(hostedVersion));

        try {
            workflowsApi.updateWorkflowDefaultVersion(hostedWorkflow.getId(), hostedVersion.getName());
            fail("Shouldn't be able to set the default version to one that is hidden.");
        } catch (ApiException ex) {
            Assert.assertEquals("You can not set the default version to a hidden version.", ex.getMessage());
        }
    }

    /**
    * Tests the a checksum is calculated for workflow sourcefiles on refresh or snapshot. Also checks that trs endpoints convert correctly.
    * */
    @Test
    public void testChecksumsForSourceFiles() {
        // Test grabbing checksum on refresh
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(webClient);
        final io.dockstore.openapi.client.ApiClient openAPIClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        Ga4Ghv20Api ga4Ghv20Api = new Ga4Ghv20Api(openAPIClient);
        Workflow workflow = workflowsApi.manualRegister("github", DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "/Dockstore.wdl", "", DescriptorLanguage.WDL.toString(), "/test.json");

        workflow = workflowsApi.refresh(workflow.getId(), false);
        List<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
        assertFalse(workflowVersions.isEmpty());
        boolean testedWDL = false;

        for (WorkflowVersion workflowVersion : workflowVersions) {
            if (workflowVersion.getName().equals("testBoth") || workflowVersion.getName().equals("testWDL")) {
                testedWDL = true;
                List<io.dockstore.webservice.core.SourceFile> sourceFiles = fileDAO.findSourceFilesByVersion(workflowVersion.getId());
                assertNotNull(sourceFiles);
                verifySourcefileChecksumsSaved(sourceFiles);
                sourceFiles.stream().forEach(sourceFile -> assertFalse("Source file should have a checksum", sourceFile.getChecksums().get(0).toString().isEmpty()));
            }
        }
        assertTrue(testedWDL);

        // Test grabbing checksum on snapshot
        Workflow workflow2 = manualRegisterAndPublish(workflowsApi, "dockstore-testing/hello_world", "", DescriptorLanguage.CWL.toString(), SourceControl.GITHUB, "/hello_world.cwl", true);
        WorkflowVersion snapshotVersion = workflow2.getWorkflowVersions().stream().filter(v -> v.getName().equals("1.0.1")).findFirst().get();
        List<io.dockstore.webservice.core.SourceFile> sourceFiles = fileDAO.findSourceFilesByVersion(snapshotVersion.getId());
        assertNotNull(sourceFiles);
        snapshotVersion.setFrozen(true);
        workflowsApi.updateWorkflowVersion(workflow2.getId(), Collections.singletonList(snapshotVersion));
        verifySourcefileChecksumsSaved(sourceFiles);

        // Make sure refresh does not error.
        workflowsApi.refresh(workflow2.getId(), false);

        // Test TRS conversion
        io.dockstore.openapi.client.model.FileWrapper fileWrapper = ga4Ghv20Api.toolsIdVersionsVersionIdTypeDescriptorGet(DescriptorLanguage.CWL.toString(), "#workflow/github.com/dockstore-testing/hello_world", "1.0.1");
        verifyTRSSourcefileConversion(fileWrapper);

    }

    private void verifyTRSSourcefileConversion(final io.dockstore.openapi.client.model.FileWrapper fileWrapper) {
        assertEquals(1, fileWrapper.getChecksum().size());
        fileWrapper.getChecksum().stream().forEach(checksum -> {
            assertFalse(checksum.getChecksum().isEmpty());
            assertEquals(DESCRIPTOR_FILE_SHA_TYPE_FOR_TRS, checksum.getType());
        });
    }

    private void verifySourcefileChecksumsSaved(final List<io.dockstore.webservice.core.SourceFile> sourceFiles) {
        assertTrue(sourceFiles.size() >= 1);
        sourceFiles.stream().forEach(sourceFile -> {
            assertFalse("Source File should have a checksum", sourceFile.getChecksums().isEmpty());
            assertTrue(sourceFile.getChecksums().size() >= 1);
            sourceFile.getChecksums().stream().forEach(checksum -> {
                assertFalse(checksum.getType().isEmpty());
                assertFalse(checksum.getChecksum().isEmpty());
            });
        });
    }

    private void verifyTRSImageConversion(final List<ToolVersion> versions, final String snapShottedVersionName, final int numImages) {
        assertFalse("Should have at least one version", versions.isEmpty());
        boolean snapshotInList = false;
        for (ToolVersion trsVersion : versions) {
            if (trsVersion.getName().equals(snapShottedVersionName)) {
                assertTrue(trsVersion.isIsProduction());
                assertEquals("There should be" + numImages + "image(s) in this workflow", numImages, trsVersion.getImages().size());
                snapshotInList = true;
                assertFalse(trsVersion.getImages().isEmpty());
                for (ImageData imageData :trsVersion.getImages()) {
                    assertNotNull(imageData.getChecksum());
                    imageData.getChecksum().stream().forEach(checksum -> {
                        assertEquals(checksum.getType(), DOCKER_IMAGE_SHA_TYPE_FOR_TRS);
                        assertFalse(checksum.getChecksum().isEmpty());
                    });
                    assertNotNull(imageData.getSize());
                    assertNotNull(imageData.getUpdated());
                    assertNotNull(imageData.getRegistryHost());
                }
            } else {
                assertFalse(trsVersion.isIsProduction());
                assertEquals("Non-snapshotted versions should have 0 images ", 0, trsVersion.getImages().size());
            }
        }
        assertTrue("Snapshotted version should be in the list", snapshotInList);
    }

    private WorkflowVersion snapshotWorkflowVersion(WorkflowsApi workflowsApi, String workflowPath, String descriptorType, String descriptorPath, String versionName) {
        Workflow workflow = manualRegisterAndPublish(workflowsApi, workflowPath, "", descriptorType, SourceControl.GITHUB, descriptorPath, true);
        WorkflowVersion version = workflow.getWorkflowVersions().stream().filter(v -> v.getName().equals(versionName)).findFirst().get();
        version.setFrozen(true);
        workflowsApi.updateWorkflowVersion(workflow.getId(), Collections.singletonList(version));
        workflow = workflowsApi.getWorkflow(workflow.getId(), "images");
        return workflow.getWorkflowVersions().stream().filter(v -> v.getName().equals(versionName)).findFirst().get();
    }

    private void verifyImageChecksumsAreSaved(WorkflowVersion version) {
        assertFalse(version.getImages().isEmpty());
        version.getImages().stream().forEach(image -> image.getChecksums().stream().forEach(checksum -> {
            assertFalse(checksum.getChecksum().isEmpty());
            assertFalse(checksum.getType().isEmpty());
        })
        );
    }

    @Test
    public void testGettingImagesFromDockerHub() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(webClient);
        final io.dockstore.openapi.client.ApiClient openAPIClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        Ga4Ghv20Api ga4Ghv20Api = new Ga4Ghv20Api(openAPIClient);

        // Test that a version of an official dockerhub image will get an image per architecture. (python 2.7) Also check that regular
        // DockerHub images are grabbed correctly broadinstitute/gatk:4.0.1.1
        WorkflowVersion version = snapshotWorkflowVersion(workflowsApi, "dockstore-testing/broad-prod-wgs-germline-snps-indels", DescriptorType.WDL.toString(), "/JointGenotypingWf.wdl", "1.1.2");
        assertEquals("Should 10 images in this workflow", 10, version.getImages().size());
        verifyImageChecksumsAreSaved(version);

        List<ToolVersion> versions = ga4Ghv20Api.toolsIdVersionsGet("#workflow/github.com/dockstore-testing/broad-prod-wgs-germline-snps-indels");
        verifyTRSImageConversion(versions, "1.1.2", 10);
    }

    @Test
    public void testCreationOfIncorrectHostedWorkflowTypeGarbage() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        HostedApi hostedApi = new HostedApi(webClient);
        thrown.expect(ApiException.class);
        hostedApi.createHostedWorkflow("name", null, "garbage type", null, null);
    }

    @Test
    public void testDuplicateHostedWorkflowCreation() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        HostedApi hostedApi = new HostedApi(webClient);
        hostedApi.createHostedWorkflow("name", null, DescriptorType.CWL.toString(), null, null);
        thrown.expectMessage("already exists");
        hostedApi.createHostedWorkflow("name", null, DescriptorType.CWL.toString(), null, null);
    }

    @Test
    public void testDuplicateHostedToolCreation() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        HostedApi hostedApi = new HostedApi(webClient);
        hostedApi.createHostedTool("name", Registry.DOCKER_HUB.getDockerPath(), DescriptorType.CWL.toString(), "namespace", null);
        thrown.expectMessage("already exists");
        hostedApi.createHostedTool("name", Registry.DOCKER_HUB.getDockerPath(), DescriptorType.CWL.toString(), "namespace", null);
    }

    @Test
    public void testHostedWorkflowMetadata() throws IOException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        HostedApi hostedApi = new HostedApi(webClient);
        Workflow hostedWorkflow = hostedApi.createHostedWorkflow("name", null, DescriptorType.CWL.toString(), null, null);
        assertNotNull(hostedWorkflow.getLastModifiedDate());
        assertNotNull(hostedWorkflow.getLastUpdated());

        // make a couple garbage edits
        SourceFile source = new SourceFile();
        source.setPath("/Dockstore.cwl");
        source.setAbsolutePath("/Dockstore.cwl");
        source.setContent("cwlVersion: v1.0\nclass: Workflow");
        source.setType(SourceFile.TypeEnum.DOCKSTORE_CWL);
        SourceFile source1 = new SourceFile();
        source1.setPath("sorttool.cwl");
        source1.setContent("foo");
        source1.setAbsolutePath("/sorttool.cwl");
        source1.setType(SourceFile.TypeEnum.DOCKSTORE_CWL);
        SourceFile source2 = new SourceFile();
        source2.setPath("revtool.cwl");
        source2.setContent("foo");
        source2.setAbsolutePath("/revtool.cwl");
        source2.setType(SourceFile.TypeEnum.DOCKSTORE_CWL);
        hostedApi.editHostedWorkflow(hostedWorkflow.getId(), Lists.newArrayList(source, source1, source2));

        source.setContent("cwlVersion: v1.0\nclass: Workflow");
        source1.setContent("food");
        source2.setContent("food");
        final Workflow updatedHostedWorkflow = hostedApi
            .editHostedWorkflow(hostedWorkflow.getId(), Lists.newArrayList(source, source1, source2));
        assertNotNull(updatedHostedWorkflow.getLastModifiedDate());
        assertNotNull(updatedHostedWorkflow.getLastUpdated());

        // note that this workflow contains metadata defined on the inputs to the workflow in the old (pre-map) CWL way that is still valid v1.0 CWL
        source.setContent(FileUtils
            .readFileToString(new File(ResourceHelpers.resourceFilePath("hosted_metadata/Dockstore.cwl")), StandardCharsets.UTF_8));
        source1.setContent(
            FileUtils.readFileToString(new File(ResourceHelpers.resourceFilePath("hosted_metadata/sorttool.cwl")), StandardCharsets.UTF_8));
        source2.setContent(
            FileUtils.readFileToString(new File(ResourceHelpers.resourceFilePath("hosted_metadata/revtool.cwl")), StandardCharsets.UTF_8));
        Workflow workflow = hostedApi.editHostedWorkflow(hostedWorkflow.getId(), Lists.newArrayList(source, source1, source2));
        assertFalse(workflow.getInputFileFormats().isEmpty());
        assertFalse(workflow.getOutputFileFormats().isEmpty());
    }

    /**
     * This tests that a nested WDL workflow (three levels) is properly parsed
     *
     * @throws ApiException exception used for errors coming back from the web service
     */
    @Test
    public void testNestedWdlWorkflow() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        // Set up postgres

        // Manually register workflow github
        Workflow githubWorkflow = workflowApi
            .manualRegister("github", "DockstoreTestUser2/nested-wdl", "/Dockstore.wdl", "altname", "wdl", "/test.json");

        // Assert some things
        final long count = testingPostgres
            .runSelectStatement("select count(*) from workflow where mode = '" + Workflow.ModeEnum.FULL + "'", long.class);
        assertEquals("No workflows are in full mode", 0, count);

        // Refresh the workflow
        workflowApi.refresh(githubWorkflow.getId(), false);

        // Confirm that correct number of sourcefiles are found
        githubWorkflow = workflowApi.getWorkflow(githubWorkflow.getId(), null);
        List<WorkflowVersion> versions = githubWorkflow.getWorkflowVersions();
        assertEquals("There should be two versions", 2, versions.size());

        Optional<WorkflowVersion> loopVersion = versions.stream().filter(version -> Objects.equals(version.getReference(), "infinite-loop"))
            .findFirst();
        if (loopVersion.isPresent()) {
            assertEquals("There should be two sourcefiles", 2, fileDAO.findSourceFilesByVersion(loopVersion.get().getId()).size());
        } else {
            fail("Could not find version infinite-loop");
        }

        Optional<WorkflowVersion> masterVersion = versions.stream().filter(version -> Objects.equals(version.getReference(), "master"))
            .findFirst();
        if (masterVersion.isPresent()) {
            assertEquals("There should be three sourcefiles", 3, fileDAO.findSourceFilesByVersion(masterVersion.get().getId()).size());
        } else {
            fail("Could not find version master");
        }
    }

    /**
     * Tests for https://github.com/dockstore/dockstore/issues/2154
     */
    @Test
    public void testMoreCWLImportsStructure() throws ApiException, URISyntaxException, IOException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi
            .manualRegister("github", "DockstoreTestUser2/workflow-seq-import", "/cwls/chksum_seqval_wf_interleaved_fq.cwl", "", "cwl",
                "/examples/chksum_seqval_wf_interleaved_fq.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_MORE_IMPORT_STRUCTURE, null, false);

        workflowApi.refresh(workflowByPathGithub.getId(), false);
        Assert.assertEquals("GNU General Public License v3.0", workflowByPathGithub.getLicenseInformation().getLicenseName());
        workflowApi.publish(workflowByPathGithub.getId(), CommonTestUtilities.createPublishRequest(true));

        // check on URLs for workflows via ga4gh calls
        Ga4GhApi ga4Ghv2Api = new Ga4GhApi(webClient);
        FileWrapper toolDescriptor = ga4Ghv2Api
            .toolsIdVersionsVersionIdTypeDescriptorGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_MORE_IMPORT_STRUCTURE, "0.4.0");
        String content = IOUtils.toString(new URI(toolDescriptor.getUrl()), StandardCharsets.UTF_8);
        assertFalse(content.isEmpty());
        // check slashed paths
        checkForRelativeFile(ga4Ghv2Api, "#workflow/" + DOCKSTORE_TEST_USER2_MORE_IMPORT_STRUCTURE, "0.4.0",
            "toolkit/if_input_is_bz2_convert_to_gz_else_just_rename.cwl");
        checkForRelativeFile(ga4Ghv2Api, "#workflow/" + DOCKSTORE_TEST_USER2_MORE_IMPORT_STRUCTURE, "0.4.0",
            "toolkit/if_file_name_is_bz2_then_return_null_else_return_in_json_to_output.cwl");
        checkForRelativeFile(ga4Ghv2Api, "#workflow/" + DOCKSTORE_TEST_USER2_MORE_IMPORT_STRUCTURE, "0.4.0",
            "../examples/chksum_seqval_wf_interleaved_fq.json");
    }

    // working on https://github.com/dockstore/dockstore/issues/3335
    @Test
    public void testWeirdPathCase() throws ApiException, URISyntaxException, IOException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi
                .manualRegister("github", "dockstore-testing/viral-pipelines", "/pipes/WDL/workflows/multi_sample_assemble_kraken.wdl", "", "wdl",
                        "");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath("github.com/dockstore-testing/viral-pipelines", null, false);

        workflowApi.refresh(workflowByPathGithub.getId(), false);
        workflowApi.publish(workflowByPathGithub.getId(), CommonTestUtilities.createPublishRequest(true));

        // check on URLs for workflows via ga4gh calls
        Ga4GhApi ga4Ghv2Api = new Ga4GhApi(webClient);
        FileWrapper toolDescriptor = ga4Ghv2Api
                .toolsIdVersionsVersionIdTypeDescriptorGet("WDL", "#workflow/github.com/dockstore-testing/viral-pipelines", "test_path");
        String content = IOUtils.toString(new URI(toolDescriptor.getUrl()), StandardCharsets.UTF_8);
        assertFalse(content.isEmpty());
        // check relative path below the main descriptor
        checkForRelativeFile(ga4Ghv2Api, "#workflow/" + "github.com/dockstore-testing/viral-pipelines", "test_path",
                "../tasks/tasks_assembly.wdl");
    }

    /**
     * Tests manual registration of a tool and check that descriptors are downloaded properly.
     * Description is pulled properly from an $include.
     *
     * @throws ApiException exception used for errors coming back from the web service
     */
    @Test
    public void testManualRegisterToolWithMixinsAndSymbolicLinks() throws ApiException, URISyntaxException, IOException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        ContainersApi toolApi = new ContainersApi(webClient);

        DockstoreTool tool = new DockstoreTool();
        tool.setDefaultDockerfilePath("/Dockerfile");
        tool.setDefaultCwlPath("/cwls/cgpmap-bamOut.cwl");
        tool.setGitUrl("git@github.com:DockstoreTestUser2/dockstore-cgpmap.git");
        tool.setNamespace("dockstoretestuser2");
        tool.setName("dockstore-cgpmap");
        tool.setRegistryString(Registry.QUAY_IO.getDockerPath());
        tool.setDefaultVersion("symbolic.v1");

        DockstoreTool registeredTool = toolApi.registerManual(tool);
        registeredTool = toolApi.refresh(registeredTool.getId());

        // Make publish request (true)
        final PublishRequest publishRequest = CommonTestUtilities.createPublishRequest(true);
        toolApi.publish(registeredTool.getId(), publishRequest);

        // look that branches and tags are typed correctly for tools
        assertTrue("should see at least 6 branches",
            registeredTool.getWorkflowVersions().stream().filter(version -> version.getReferenceType() == Tag.ReferenceTypeEnum.BRANCH)
                .count() >= 1);
        assertTrue("should see at least 6 tags",
            registeredTool.getWorkflowVersions().stream().filter(version -> version.getReferenceType() == Tag.ReferenceTypeEnum.TAG).count()
                >= 2);

        assertTrue("did not pick up description from $include",
            registeredTool.getDescription().contains("A Docker container for PCAP-core."));
        List<io.dockstore.webservice.core.SourceFile> sourceFiles = fileDAO.findSourceFilesByVersion(registeredTool.getWorkflowVersions().stream().filter(tag -> Objects.equals(tag.getName(), "test.v1")).findFirst().get().getId());
        assertEquals("did not import mixin and includes properly", 5, sourceFiles.size());
        sourceFiles = fileDAO.findSourceFilesByVersion(registeredTool.getWorkflowVersions().stream().filter(tag -> Objects.equals(tag.getName(), "symbolic.v1")).findFirst().get().getId());
        assertEquals("did not import symbolic links to folders properly", 5, sourceFiles.size());
        // check that commit ids look properly recorded
        // check on commit ids for github
        boolean allHaveCommitIds = registeredTool.getWorkflowVersions().stream().noneMatch(version -> version.getCommitID().isEmpty());
        assertTrue("not all tools seem to have commit ids", allHaveCommitIds);

        // check on URLs for workflows via ga4gh calls
        Ga4GhApi ga4Ghv2Api = new Ga4GhApi(webClient);
        FileWrapper toolDescriptor = ga4Ghv2Api
            .toolsIdVersionsVersionIdTypeDescriptorGet("CWL", DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL, "symbolic.v1");
        String content = IOUtils.toString(new URI(toolDescriptor.getUrl()), StandardCharsets.UTF_8);
        assertFalse(content.isEmpty());
        // check slashed paths (this doesn't seem to make sense, the leading slash seems to indicate this is relative to the root)
        //        checkForRelativeFile(ga4Ghv2Api, DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL, "symbolic.v1", "/cgpmap-bamOut.cwl");
        // a true absolute path would seem to be
        checkForRelativeFile(ga4Ghv2Api, DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL, "symbolic.v1", "/cwls/cgpmap-bamOut.cwl");
        // check paths without slash
        checkForRelativeFile(ga4Ghv2Api, DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL, "symbolic.v1", "cgpmap-bamOut.cwl");
        // check other secondaries and the dockerfile
        checkForRelativeFile(ga4Ghv2Api, DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL, "symbolic.v1", "includes/doc.yml");
        checkForRelativeFile(ga4Ghv2Api, DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL, "symbolic.v1", "mixins/hints.yml");
        checkForRelativeFile(ga4Ghv2Api, DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL, "symbolic.v1", "mixins/requirements.yml");
        checkForRelativeFile(ga4Ghv2Api, DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_TOOL, "symbolic.v1", "/Dockerfile");
    }

    /**
     * Checks that a file can be received from TRS and passes through a valid URL to github, bitbucket, etc.
     *
     * @param ga4Ghv2Api
     * @param dockstoreTestUser2RelativeImportsTool
     * @param reference
     * @param filename
     * @throws IOException
     * @throws URISyntaxException
     */
    private void checkForRelativeFile(Ga4GhApi ga4Ghv2Api, String dockstoreTestUser2RelativeImportsTool, String reference, String filename)
        throws IOException, URISyntaxException {
        FileWrapper toolDescriptor;
        String content;
        toolDescriptor = ga4Ghv2Api
            .toolsIdVersionsVersionIdTypeDescriptorRelativePathGet("CWL", dockstoreTestUser2RelativeImportsTool, reference, filename);
        content = IOUtils.toString(new URI(toolDescriptor.getUrl()), StandardCharsets.UTF_8);
        assertFalse(content.isEmpty());
    }

    /**
     * Tests that trying to register a duplicate workflow fails, and that registering a non-existant repository fails
     *
     * @throws ApiException exception used for errors coming back from the web service
     */
    @Test
    public void testManualRegisterErrors() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        UsersApi usersApi = new UsersApi(webClient);

        // Manually register workflow
        boolean success = true;
        try {
            workflowApi.manualRegister("github", DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "/Dockstore.wdl", "", "wdl", "/test.json");
            workflowApi.manualRegister("github", DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "/Dockstore.wdl", "", "wdl", "/test.json");
        } catch (ApiException c) {
            success = false;
        } finally {
            assertFalse(success);
        }

        success = true;
        try {
            workflowApi.manualRegister("github", "dasn/iodnasiodnasio", "/Dockstore.wdl", "", "wdl", "/test.json");
        } catch (ApiException c) {
            success = false;
        } finally {
            assertFalse(success);
        }
    }

    @Test
    public void testSecondaryFileOperations() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);

        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore-whalesay-imports", "/Dockstore.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_IMPORTS_DOCKSTORE_WORKFLOW, null, false);

        // This checks if a workflow whose default name was manually registered as an empty string would become null
        assertNull(workflowByPathGithub.getWorkflowName());

        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);

        // This checks if a workflow whose default name is null would remain as null after refresh
        assertNull(workflow.getWorkflowName());

        // test out methods to access secondary files
        final List<SourceFile> masterImports = workflowApi
            .secondaryDescriptors(workflow.getId(), "master", DescriptorLanguage.CWL.toString());
        assertEquals("should find 2 imports, found " + masterImports.size(), 2, masterImports.size());
        final SourceFile master = workflowApi.primaryDescriptor(workflow.getId(), "master", DescriptorLanguage.CWL.toString());
        assertTrue("master content incorrect", master.getContent().contains("untar") && master.getContent().contains("compile"));

        // get secondary files by path
        SourceFile argumentsTool = workflowApi
            .secondaryDescriptorPath(workflow.getId(), "arguments.cwl", "master", DescriptorLanguage.CWL.toString());
        assertTrue("argumentstool content incorrect", argumentsTool.getContent().contains("Example trivial wrapper for Java 7 compiler"));
    }

    @Test
    public void testRelativeSecondaryFileOperations() throws ApiException, URISyntaxException, IOException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, null, false);

        // This checks if a workflow whose default name was manually registered as an empty string would become null
        assertNull(workflowByPathGithub.getWorkflowName());

        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);

        // Test that the secondary file's input file formats are recognized (secondary file is varscan_cnv.cwl)
        List<FileFormat> fileFormats = workflow.getInputFileFormats();
        List<WorkflowVersion> workflowVersionsForFileFormat = workflow.getWorkflowVersions();
        Assert.assertTrue(workflowVersionsForFileFormat.stream().anyMatch(workflowVersion -> workflowVersion.getInputFileFormats().stream()
            .anyMatch(fileFormat -> fileFormat.getValue().equals("http://edamontology.org/format_2572"))));
        Assert.assertTrue(workflowVersionsForFileFormat.stream().anyMatch(workflowVersion -> workflowVersion.getInputFileFormats().stream()
            .anyMatch(fileFormat -> fileFormat.getValue().equals("http://edamontology.org/format_1929"))));
        Assert.assertTrue(workflowVersionsForFileFormat.stream().anyMatch(workflowVersion -> workflowVersion.getInputFileFormats().stream()
            .anyMatch(fileFormat -> fileFormat.getValue().equals("http://edamontology.org/format_3003"))));
        Assert.assertTrue(fileFormats.stream().anyMatch(fileFormat -> fileFormat.getValue().equals("http://edamontology.org/format_2572")));
        Assert.assertTrue(fileFormats.stream().anyMatch(fileFormat -> fileFormat.getValue().equals("http://edamontology.org/format_1929")));
        Assert.assertTrue(fileFormats.stream().anyMatch(fileFormat -> fileFormat.getValue().equals("http://edamontology.org/format_3003")));
        Assert.assertTrue(workflowVersionsForFileFormat.stream().anyMatch(workflowVersion -> workflowVersion.getOutputFileFormats().stream()
            .anyMatch(fileFormat -> fileFormat.getValue().equals("file://fakeFileFormat"))));
        Assert.assertTrue(
                workflow.getOutputFileFormats().stream().anyMatch(fileFormat -> fileFormat.getValue().equals("file://fakeFileFormat")));

        // This checks if a workflow whose default name is null would remain as null after refresh
        assertNull(workflow.getWorkflowName());

        // test out methods to access secondary files

        final List<SourceFile> masterImports = workflowApi
            .secondaryDescriptors(workflow.getId(), "master", DescriptorLanguage.CWL.toString());
        assertEquals("should find 3 imports, found " + masterImports.size(), 3, masterImports.size());
        final List<SourceFile> rootImports = workflowApi
            .secondaryDescriptors(workflow.getId(), "rootTest", DescriptorLanguage.CWL.toString());
        assertEquals("should find 0 imports, found " + rootImports.size(), 0, rootImports.size());

        // next, change a path for the root imports version
        List<WorkflowVersion> workflowVersions = workflow.getWorkflowVersions();
        workflowVersions.stream().filter(v -> v.getName().equals("rootTest")).findFirst().get().setWorkflowPath("/cnv.cwl");
        workflowApi.updateWorkflowVersion(workflow.getId(), workflowVersions);
        workflowApi.refresh(workflowByPathGithub.getId(), false);
        final List<SourceFile> newMasterImports = workflowApi
            .secondaryDescriptors(workflow.getId(), "master", DescriptorLanguage.CWL.toString());
        assertEquals("should find 3 imports, found " + newMasterImports.size(), 3, newMasterImports.size());
        final List<SourceFile> newRootImports = workflowApi
            .secondaryDescriptors(workflow.getId(), "rootTest", DescriptorLanguage.CWL.toString());
        assertEquals("should find 3 imports, found " + newRootImports.size(), 3, newRootImports.size());

        workflowApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));
        // check on URLs for workflows via ga4gh calls
        Ga4GhApi ga4Ghv2Api = new Ga4GhApi(webClient);
        FileWrapper toolDescriptor = ga4Ghv2Api
            .toolsIdVersionsVersionIdTypeDescriptorGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master");
        String content = IOUtils.toString(new URI(toolDescriptor.getUrl()), StandardCharsets.UTF_8);
        assertFalse(content.isEmpty());
        checkForRelativeFile(ga4Ghv2Api, "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master", "adtex.cwl");
        // ignore extra separators, broken as side effect fix for of https://github.com/dockstore/dockstore/issues/3335
        // checkForRelativeFile(ga4Ghv2Api, "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master", "/adtex.cwl");
        // test json should use relative path with ".."
        checkForRelativeFile(ga4Ghv2Api, "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master", "../test.json");
        List<ToolFile> toolFiles = ga4Ghv2Api
            .toolsIdVersionsVersionIdTypeFilesGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master");
        assertTrue("should have at least 5 files", toolFiles.size() >= 5);
        assertTrue("all files should have relative paths",
            toolFiles.stream().filter(toolFile -> !toolFile.getPath().startsWith("/")).count() >= 5);

        // check on urls created for test files
        List<FileWrapper> toolTests = ga4Ghv2Api
            .toolsIdVersionsVersionIdTypeTestsGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master");
        assertTrue("could not find tool tests", toolTests.size() > 0);
        for (FileWrapper test : toolTests) {
            content = IOUtils.toString(new URI(test.getUrl()), StandardCharsets.UTF_8);
            assertFalse(content.isEmpty());
        }
    }

    @Test
    public void testAnonAndAdminGA4GH() throws ApiException, URISyntaxException, IOException {
        WorkflowsApi workflowApi = new WorkflowsApi(getWebClient(USER_2_USERNAME, testingPostgres));
        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, null, false);
        workflowApi.refresh(workflowByPathGithub.getId(), false);

        // should not be able to get content normally
        Ga4GhApi anonymousGa4Ghv2Api = new Ga4GhApi(CommonTestUtilities.getWebClient(false, null, testingPostgres));
        boolean thrownException = false;
        try {
            anonymousGa4Ghv2Api
                .toolsIdVersionsVersionIdTypeFilesGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master");
        } catch (ApiException e) {
            thrownException = true;
        }
        assert (thrownException);

        boolean thrownListException = false;
        try {
            anonymousGa4Ghv2Api
                .toolsIdVersionsVersionIdTypeTestsGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master");
        } catch (ApiException e) {
            thrownListException = true;
        }
        assert (thrownListException);

        // can get content via admin user
        Ga4GhApi adminGa4Ghv2Api = new Ga4GhApi(getWebClient(USER_2_USERNAME, testingPostgres));

        List<ToolFile> toolFiles = adminGa4Ghv2Api
            .toolsIdVersionsVersionIdTypeFilesGet("CWL", "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master");
        assertTrue("should have at least 5 files", toolFiles.size() >= 5);

        // cannot get relative paths anonymously
        toolFiles.forEach(file -> {
            boolean thrownInnerException = false;
            try {
                anonymousGa4Ghv2Api.toolsIdVersionsVersionIdTypeDescriptorRelativePathGet("CWL",
                    "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master", file.getPath());
            } catch (ApiException e) {
                thrownInnerException = true;
            }
            assertTrue(thrownInnerException);
        });

        final AtomicInteger count = new AtomicInteger(0);
        // can get relative paths with admin user
        toolFiles.forEach(file -> {
            if (file.getFileType() == ToolFile.FileTypeEnum.TEST_FILE) {
                // enable later with a simplification to TRS
                FileWrapper test = adminGa4Ghv2Api.toolsIdVersionsVersionIdTypeDescriptorRelativePathGet("CWL",
                    "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master", file.getPath());
                assertFalse(test.getContent().isEmpty());
                count.incrementAndGet();
            } else if (file.getFileType() == ToolFile.FileTypeEnum.PRIMARY_DESCRIPTOR
                || file.getFileType() == ToolFile.FileTypeEnum.SECONDARY_DESCRIPTOR) {
                // annoyingly, some files are tool tests, some are tooldescriptor
                FileWrapper toolDescriptor = adminGa4Ghv2Api.toolsIdVersionsVersionIdTypeDescriptorRelativePathGet("CWL",
                    "#workflow/" + DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "master", file.getPath());
                assertFalse(toolDescriptor.getContent().isEmpty());
                count.incrementAndGet();
            } else {
                fail();
            }
        });
        assertTrue("did not count expected (5) number of files, got" + count.get(), count.get() >= 5);
    }

    @Test
    public void testAliasOperations() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, null, false);
        // do targetted refresh, should promote workflow to fully-fleshed out workflow
        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);
        workflowApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));

        Workflow md5workflow = workflowApi.manualRegister(SourceControl.GITHUB.getFriendlyName(), "DockstoreTestUser2/md5sum-checker",
            "/checker-workflow-wrapping-workflow.cwl", "test", "cwl", null);
        workflowApi.refresh(md5workflow.getId(), false);
        workflowApi.publish(md5workflow.getId(), CommonTestUtilities.createPublishRequest(true));

        // give the workflow a few aliases
        EntriesApi genericApi = new EntriesApi(webClient);
        Entry entry = genericApi.addAliases(workflow.getId(), "awesome workflow, spam, test workflow");
        Assert.assertTrue("entry is missing expected aliases",
            entry.getAliases().containsKey("awesome workflow") && entry.getAliases().containsKey("spam") && entry.getAliases()
                .containsKey("test workflow"));

        Workflow workflowById = workflowApi.getWorkflow(entry.getId(), null);
        Assert.assertNotNull("Getting workflow by ID has null alias", workflowById.getAliases());

        // check that the aliases work in TRS search
        Ga4GhApi ga4GhApi = new Ga4GhApi(webClient);
        // this generated code is mucho silly
        List<Tool> workflows = ga4GhApi.toolsGet(null, null, null, null, null, null, null, null, null, null, 100);
        Assert.assertEquals("expected workflows not found", 2, workflows.size());
        List<Tool> awesomeWorkflow = ga4GhApi.toolsGet(null, "awesome workflow", null, null, null, null, null, null, null, null, 100);
        Assert.assertTrue("workflow was not found or didn't have expected aliases",
            awesomeWorkflow.size() == 1 && awesomeWorkflow.get(0).getAliases().size() == 3);
        // add a few new aliases
        entry = genericApi.addAliases(workflow.getId(), "foobar, another workflow");
        Assert.assertTrue("entry is missing expected aliases",
            entry.getAliases().containsKey("foobar") && entry.getAliases().containsKey("test workflow") && entry.getAliases().size() == 5);

        // try to add duplicates; this is not allowed
        boolean throwsError = false;
        try {
            // add a few new aliases
            entry = genericApi.addAliases(workflow.getId(), "another workflow");
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to add a duplicate Workflow alias.");
        }

        // Get workflow by alias
        Workflow aliasWorkflow = workflowApi.getWorkflowByAlias("foobar");
        Assert.assertNotNull("Should retrieve the workflow by alias", aliasWorkflow);
    }

    /**
     * This tests that the absolute path is properly set for CWL workflow sourcefiles for the primary descriptor and any imported files
     */
    @Test
    public void testAbsolutePathForImportedFilesCWL() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi.manualRegister("github", "DockstoreTestUser2/gdc-dnaseq-cwl", "/workflows/dnaseq/transform.cwl", "", "cwl",
            "/workflows/dnaseq/transform.cwl.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_GDC_DNASEQ_CWL_WORKFLOW, null, false);
        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);

        Assert.assertEquals("should have 2 version", 2, workflow.getWorkflowVersions().size());
        Optional<WorkflowVersion> workflowVersion = workflow.getWorkflowVersions().stream()
            .filter(version -> Objects.equals(version.getName(), "test")).findFirst();
        if (workflowVersion.isEmpty()) {
            Assert.fail("Missing the test release");
        }

        List<io.dockstore.webservice.core.SourceFile> sourceFiles = fileDAO.findSourceFilesByVersion(workflowVersion.get().getId());
        Optional<io.dockstore.webservice.core.SourceFile> primarySourceFile = sourceFiles.stream().filter(
            sourceFile -> Objects.equals(sourceFile.getPath(), "/workflows/dnaseq/transform.cwl") && Objects
                .equals(sourceFile.getAbsolutePath(), "/workflows/dnaseq/transform.cwl")).findFirst();
        if (primarySourceFile.isEmpty()) {
            Assert.fail("Does not properly set the absolute path of the primary descriptor.");
        }

        Optional<io.dockstore.webservice.core.SourceFile> importedSourceFileOne = sourceFiles.stream().filter(
            sourceFile -> Objects.equals(sourceFile.getPath(), "../../tools/bam_readgroup_to_json.cwl") && Objects
                .equals(sourceFile.getAbsolutePath(), "/tools/bam_readgroup_to_json.cwl")).findFirst();
        if (importedSourceFileOne.isEmpty()) {
            Assert.fail("Does not properly set the absolute path of the imported file.");
        }

        Optional<io.dockstore.webservice.core.SourceFile> importedSourceFileTwo = sourceFiles.stream().filter(
            sourceFile -> Objects.equals(sourceFile.getPath(), "integrity.cwl") && Objects
                .equals(sourceFile.getAbsolutePath(), "/workflows/dnaseq/integrity.cwl")).findFirst();
        if (importedSourceFileTwo.isEmpty()) {
            Assert.fail("Does not properly set the absolute path of the imported file.");
        }
    }

    /**
     * NOTE: This test is not normally run. It is only for running locally to confirm that the discourse topic generation is working.
     * <p>
     * Adds a discourse topic for a workflow (adds to a Automatic Tool and Workflow Threads - NEED TO DELETE TOPIC)
     * <p>
     * Requires you to have the correct discourse information set in the dockstoreTest.yml
     */
    @Ignore
    public void publishWorkflowAndTestDiscourseTopicCreation() {
        final ApiClient curatorApiClient = getWebClient(curatorUsername, testingPostgres);
        EntriesApi curatorEntriesApi = new EntriesApi(curatorApiClient);
        final ApiClient userApiClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi userWorkflowsApi = new WorkflowsApi(userApiClient);

        // Create a workflow with a random name
        String workflowName = Long.toString(Instant.now().toEpochMilli());
        userWorkflowsApi
            .manualRegister("github", "DockstoreTestUser2/gdc-dnaseq-cwl", "/workflows/dnaseq/transform.cwl", workflowName, "cwl",
                "/workflows/dnaseq/transform.cwl.json");
        final Workflow workflowByPathGithub = userWorkflowsApi
            .getWorkflowByPath(DOCKSTORE_TEST_USER2_GDC_DNASEQ_CWL_WORKFLOW + "/" + workflowName, null, false);
        final Workflow workflow = userWorkflowsApi.refresh(workflowByPathGithub.getId(), true);

        // Publish workflow, which will also add a topic
        userWorkflowsApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));

        // Should not be able to create a topic for the same workflow
        try {
            curatorEntriesApi.setDiscourseTopic(workflow.getId());
            fail("Should still not be able to set discourse topic.");
        } catch (ApiException ignored) {
            assertTrue(true);
        }

        // Unpublish and publish, should not throw error
        Workflow unpublishedWf = userWorkflowsApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(false));
        Workflow publishedWf = userWorkflowsApi.publish(unpublishedWf.getId(), CommonTestUtilities.createPublishRequest(true));
        assertEquals("Topic id should remain the same.", unpublishedWf.getTopicId(), publishedWf.getTopicId());
    }

    /**
     * Test for cwl1.1
     * Of the languages support features, this tests:
     * Workflow Registration
     * Metadata Display
     * Validation
     */
    @Test
    public void cwlVersion11() {
        final ApiClient userApiClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi userWorkflowsApi = new WorkflowsApi(userApiClient);
        userWorkflowsApi.manualRegister("github", "dockstore-testing/Workflows-For-CI", "/cwl/v1.1/metadata.cwl", "metadata", "cwl",
            "/cwl/v1.1/cat-job.json");
        final Workflow workflowByPathGithub = userWorkflowsApi
            .getWorkflowByPath("github.com/dockstore-testing/Workflows-For-CI/metadata", null, false);
        final Workflow workflow = userWorkflowsApi.refresh(workflowByPathGithub.getId(), true);
        workflow.getWorkflowVersions().forEach(workflowVersion -> {
            Assert.assertEquals("Print the contents of a file to stdout using 'cat' running in a docker container.", workflow.getDescription());
            Assert.assertEquals("Peter Amstutz", workflow.getAuthor());
            Assert.assertTrue(workflow.getWorkflowVersions().stream().anyMatch(versions -> "master".equals(versions.getName())));
        });
        Assert.assertEquals("Default branch should've been set to get metadata", "master", workflow.getDefaultVersion());
        Assert.assertEquals("peter.amstutz@curoverse.com", workflow.getEmail());
        Assert.assertEquals("Print the contents of a file to stdout using 'cat' running in a docker container.", workflow.getDescription());
        Assert.assertEquals("Peter Amstutz", workflow.getAuthor());
        Assert.assertTrue(workflow.getWorkflowVersions().stream().anyMatch(versions -> "master".equals(versions.getName())));
        Assert.assertEquals("Default version should've been set to get metadata", "master", workflow.getDefaultVersion());
        Optional<WorkflowVersion> optionalWorkflowVersion = workflow.getWorkflowVersions().stream()
            .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersion.isPresent());
        WorkflowVersion workflowVersion = optionalWorkflowVersion.get();
        List<io.dockstore.webservice.core.SourceFile> sourceFiles = fileDAO.findSourceFilesByVersion(workflowVersion.getId());
        Assert.assertEquals(2, sourceFiles.size());
        Assert.assertTrue(sourceFiles.stream().anyMatch(sourceFile -> sourceFile.getPath().equals("/cwl/v1.1/cat-job.json")));
        Assert.assertTrue(sourceFiles.stream().anyMatch(sourceFile -> sourceFile.getPath().equals("/cwl/v1.1/metadata.cwl")));
        // Check validation works.  It is invalid because this is a tool and not a workflow.
        Assert.assertFalse(workflowVersion.isValid());

        userWorkflowsApi
            .manualRegister("github", "dockstore-testing/Workflows-For-CI", "/cwl/v1.1/count-lines1-wf.cwl", "count-lines1-wf", "cwl",
                "/cwl/v1.1/wc-job.json");
        final Workflow workflowByPathGithub2 = userWorkflowsApi
            .getWorkflowByPath("github.com/dockstore-testing/Workflows-For-CI/count-lines1-wf", null, false);
        final Workflow workflow2 = userWorkflowsApi.refresh(workflowByPathGithub2.getId(), false);
        Assert.assertTrue(workflow.getWorkflowVersions().stream().anyMatch(versions -> "master".equals(versions.getName())));
        Optional<WorkflowVersion> optionalWorkflowVersion2 = workflow2.getWorkflowVersions().stream()
            .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersion2.isPresent());
        WorkflowVersion workflowVersion2 = optionalWorkflowVersion2.get();
        // Check validation works.  It should be valid
        Assert.assertTrue(workflowVersion2.isValid());
        userWorkflowsApi.publish(workflowByPathGithub2.getId(), CommonTestUtilities.createPublishRequest(true));
    }

    private Workflow registerGatkSvWorkflow(WorkflowsApi ownerWorkflowApi) {
        // Register and refresh workflow
        Workflow workflow = ownerWorkflowApi.manualRegister(SourceControl.GITHUB.getFriendlyName(), "dockstore-testing/gatk-sv-clinical", "/GATKSVPipelineClinical.wdl",
                "test", "wdl", "/test.json");
        return ownerWorkflowApi.refresh(workflow.getId(), false);
    }
    
    @Test
    public void testWorkflowVersionAliasOperations() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv",
                "/workflow/cnv.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, null, false);
        // do targetted refresh, should promote workflow to fully-fleshed out workflow
        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);
        workflowApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));

        Assert.assertTrue(workflow.getWorkflowVersions().stream().anyMatch(versions -> "master".equals(versions.getName())));
        Optional<WorkflowVersion> optionalWorkflowVersion = workflow.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersion.isPresent());
        WorkflowVersion workflowVersion = optionalWorkflowVersion.get();

        // give the workflow version a few aliases
        AliasesApi aliasesApi = new AliasesApi(webClient);
        WorkflowVersion workflowVersionWithAliases = aliasesApi.addAliases(workflowVersion.getId(), "awesome workflowversion, spam, test workflowversion");
        Assert.assertTrue("entry is missing expected aliases",
                workflowVersionWithAliases.getAliases().containsKey("awesome workflowversion")
                        && workflowVersionWithAliases.getAliases().containsKey("spam")
                        && workflowVersionWithAliases.getAliases().containsKey("test workflowversion"));

        // add a few new aliases
        workflowVersion = aliasesApi.addAliases(workflowVersion.getId(), "foobar, another workflowversion");
        Assert.assertTrue("entry is missing expected aliases",
                workflowVersion.getAliases().containsKey("foobar")
                        && workflowVersion.getAliases().containsKey("test workflowversion")
                        && workflowVersion.getAliases().size() == 5);

        // try to add duplicates; this is not allowed
        boolean throwsError = false;
        try {
            // add a few new aliases
            workflowVersion = aliasesApi.addAliases(workflow.getId(), "another workflowversion");
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to add a duplicate Workflow version alias.");
        }

        // Get workflow version by alias
        io.dockstore.webservice.core.WorkflowVersion aliasWorkflowVersion = workflowVersionDAO.findByAlias("foobar");
        Assert.assertNotNull("Should retrieve the workflow by alias", aliasWorkflowVersion);
    }

    @Test
    public void testWorkflowVersionAliasesAreReturned() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowApi = new WorkflowsApi(webClient);
        workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv",
                "/workflow/cnv.cwl", "", "cwl", "/test.json");
        final Workflow workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, null, false);
        // do targeted refresh, should promote workflow to fully-fleshed out workflow
        final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);
        workflowApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));

        Assert.assertTrue(workflow.getWorkflowVersions().stream().anyMatch(versions -> "master".equals(versions.getName())));
        Optional<WorkflowVersion> optionalWorkflowVersion = workflow.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersion.isPresent());
        WorkflowVersion workflowVersion = optionalWorkflowVersion.get();

        // give the workflow version a few aliases
        AliasesApi aliasesApi = new AliasesApi(webClient);
        WorkflowVersion workflowVersionWithAliases = aliasesApi
                .addAliases(workflowVersion.getId(), "awesome workflowversion, spam, test workflowversion");
        Assert.assertTrue("entry is missing expected aliases",
                workflowVersionWithAliases.getAliases().containsKey("awesome workflowversion") && workflowVersionWithAliases.getAliases().containsKey("spam")
                        && workflowVersionWithAliases.getAliases().containsKey("test workflowversion"));

        // Do not include the validation parameter that requests workflow version aliases be included in the returned object
        // So the aliases portion of the returned object should be null
        Workflow workflowById = workflowApi.getWorkflow(workflow.getId(), null);
        Optional<WorkflowVersion> optionalWorkflowVersionById = workflowById.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersionById.isPresent());
        WorkflowVersion workflowVersionById = optionalWorkflowVersionById.get();
        Assert.assertNull("Getting workflow version via workflow ID has null alias", workflowVersionById.getAliases());

        final Workflow publishedWorkflow = workflowApi.getPublishedWorkflow(workflow.getId(), null);
        assertNotNull("did not get published workflow", publishedWorkflow);
        Optional<WorkflowVersion> optionalWorkflowVersionByPublished = publishedWorkflow.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersionByPublished.isPresent());
        WorkflowVersion workflowVersionByPublshed = optionalWorkflowVersionByPublished.get();
        Assert.assertNull("Getting workflow version via published workflow has null alias", workflowVersionByPublshed.getAliases());

        final Workflow workflowByPath = workflowApi
                .getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "versions", false);
        assertNotNull("did not get published workflow by path", workflowByPath);
        Optional<WorkflowVersion> optionalWorkflowVersionByPath = workflowByPath.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersionByPath.isPresent());
        WorkflowVersion workflowVersionByPath = optionalWorkflowVersionByPath.get();
        Assert.assertNull("Getting workflow version via workflow path has null alias", workflowVersionByPath.getAliases());

        final Workflow publishedWorkflowByPath = workflowApi
                .getPublishedWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "versions", false, null);
        assertNotNull("did not get published workflow by path", publishedWorkflowByPath);
        Optional<WorkflowVersion> optionalWorkflowVersionByPublishedByPath = publishedWorkflowByPath.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersionByPublishedByPath.isPresent());
        WorkflowVersion workflowVersionByPublshedByPath = optionalWorkflowVersionByPublishedByPath.get();
        Assert.assertNull("Getting workflow version via published workflow has null alias", workflowVersionByPublshedByPath.getAliases());



        // Include the validation parameter that requests workflow version aliases be included in the returned object
        Workflow workflowByIdValidation = workflowApi.getWorkflow(workflow.getId(), "aliases");
        Optional<WorkflowVersion> optionalWorkflowVersionByIdValidation = workflowByIdValidation.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersionByIdValidation.isPresent());
        WorkflowVersion workflowVersionByIdValidation = optionalWorkflowVersionByIdValidation.get();
        Assert.assertFalse("Getting workflow version via workflow ID has null or empty alias",
                MapUtils.isEmpty(workflowVersionByIdValidation.getAliases()));

        final Workflow publishedWorkflowValidation = workflowApi.getPublishedWorkflow(workflow.getId(), "aliases");
        assertNotNull("did not get published workflow", publishedWorkflowValidation);
        Optional<WorkflowVersion> optionalWorkflowVersionByPublishedValidation = publishedWorkflowValidation.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersionByPublishedValidation.isPresent());
        WorkflowVersion workflowVersionByPublshedValidation = optionalWorkflowVersionByPublishedValidation.get();
        Assert.assertFalse("Getting workflow version via published workflow has null or empty alias",
                MapUtils.isEmpty(workflowVersionByPublshedValidation.getAliases()));

        final Workflow workflowByPathValidation = workflowApi
                .getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "aliases", false);
        assertNotNull("did not get published workflow by path", workflowByPathValidation);
        Optional<WorkflowVersion> optionalWorkflowVersionByPathValidation = workflowByPathValidation.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersionByPathValidation.isPresent());
        WorkflowVersion workflowVersionByPathValidation = optionalWorkflowVersionByPathValidation.get();
        Assert.assertFalse("Getting workflow version via workflow path has null or empty alias",
                MapUtils.isEmpty(workflowVersionByPathValidation.getAliases()));


        final Workflow publishedWorkflowByPathValidation = workflowApi
                .getPublishedWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, "aliases", false, null);
        assertNotNull("did not get published workflow by path", publishedWorkflowByPathValidation);
        Optional<WorkflowVersion> optionalWorkflowVersionByPublishedByPathValidation = publishedWorkflowByPathValidation.getWorkflowVersions().stream()
                .filter(version -> "master".equalsIgnoreCase(version.getName())).findFirst();
        assertTrue(optionalWorkflowVersionByPublishedByPathValidation.isPresent());
        WorkflowVersion workflowVersionByPublshedByPathValidation = optionalWorkflowVersionByPublishedByPathValidation.get();
        Assert.assertFalse("Getting workflow version via published workflow has null alias",
                MapUtils.isEmpty(workflowVersionByPublshedByPathValidation.getAliases()));

    }

    @Test
    public void testGettingSourceFilesForWorkflowVersion() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(webClient);
        final io.dockstore.openapi.client.ApiClient openAPIWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.WorkflowsApi workflowsOpenApi = new io.dockstore.openapi.client.api.WorkflowsApi(openAPIWebClient);

        // Sourcefiles for workflowversions
        Workflow workflow = workflowsApi
                .manualRegister(SourceControl.GITHUB.getFriendlyName(), DOCKSTORE_TEST_USER_2_HELLO_DOCKSTORE_NAME, "/Dockstore.cwl", "", "cwl", "/test.json");
        workflow = workflowsApi.refresh(workflow.getId(), false);

        WorkflowVersion workflowVersion = workflow.getWorkflowVersions().stream().filter(workflowVersion1 -> workflowVersion1.getName().equals("testCWL")).findFirst().get();

        List<io.dockstore.openapi.client.model.SourceFile> sourceFiles = workflowsOpenApi.getWorkflowVersionsSourcefiles(workflow.getId(), workflowVersion.getId(), null);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(1, sourceFiles.size());

        // Check that filtering works
        List<String> fileTypes = new ArrayList<>();
        fileTypes.add(DescriptorLanguage.FileType.DOCKSTORE_CWL.toString());
        sourceFiles = workflowsOpenApi.getWorkflowVersionsSourcefiles(workflow.getId(), workflowVersion.getId(), fileTypes);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(1, sourceFiles.size());

        fileTypes.clear();
        fileTypes.add(DescriptorLanguage.FileType.DOCKSTORE_WDL.toString());
        sourceFiles = workflowsOpenApi.getWorkflowVersionsSourcefiles(workflow.getId(), workflowVersion.getId(), fileTypes);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(0, sourceFiles.size());

        // Check that you can't retrieve a version's sourcefiles if it doesn't belong to the workflow
        Workflow workflow2 = workflowsApi
                .manualRegister(SourceControl.GITHUB.getFriendlyName(), "DockstoreTestUser2/md5sum-checker", "/md5sum/md5sum-workflow.cwl",
                        "test", "cwl", null);
        workflow2 = workflowsApi.refresh(workflow2.getId(), false);
        WorkflowVersion workflow2Version = workflow2.getWorkflowVersions().get(0);
        try {
            sourceFiles = workflowsOpenApi.getWorkflowVersionsSourcefiles(workflow.getId(), workflow2Version.getId(), null);
            fail("Should not be able to grab sourcefile for a version not belonging to a workflow");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals("Version " + workflow2Version.getId() + " does not exist for this entry", ex.getMessage());
        }

        // check that sourcefiles can't be viewed by another user if entry isn't published
        final io.dockstore.openapi.client.ApiClient user1OpenAPIWebClient = getOpenAPIWebClient(USER_1_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.WorkflowsApi user1WorkflowsOpenApi = new io.dockstore.openapi.client.api.WorkflowsApi(user1OpenAPIWebClient);
        try {
            sourceFiles = user1WorkflowsOpenApi.getWorkflowVersionsSourcefiles(workflow.getId(), workflowVersion.getId(), null);
            fail("Should not be able to grab sourcefiles if not published and doesn't belong to user.");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals("Forbidden: you do not have the credentials required to access this entry.", ex.getMessage());
        }

        // sourcefiles can be viewed by others once published
        workflow = workflowsApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));
        sourceFiles = user1WorkflowsOpenApi.getWorkflowVersionsSourcefiles(workflow.getId(), workflowVersion.getId(), null);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(1, sourceFiles.size());
    }

    /**
     * We need an EntryVersionHelper instance so we can call EntryVersionHelper.writeStreamAsZip; getDAO never gets invoked.
     */
    private static class EntryVersionHelperImpl implements EntryVersionHelper {

        @Override
        public EntryDAO getDAO() {
            return null;
        }
    }
}


