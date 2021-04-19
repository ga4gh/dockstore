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
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.Registry;
import io.dockstore.common.SourceControl;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.ContainertagsApi;
import io.swagger.client.api.ExtendedGa4GhApi;
import io.swagger.client.api.Ga4GhApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.PublishRequest;
import io.swagger.client.model.Tag;
import io.swagger.client.model.Tool;
import io.swagger.client.model.ToolVersion;
import io.swagger.client.model.Workflow;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;

/**
 * Extra confidential integration tests, focuses on proposed GA4GH extensions
 * {@link BaseIT}
 *
 * @author dyuen
 */
@Category(ConfidentialTest.class)
public class ExtendedTRSIT extends BaseIT {

    private static final String DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW =
        SourceControl.GITHUB.toString() + "/DockstoreTestUser2/dockstore_workflow_cnv";
    private static final String AWESOME_PLATFORM = "awesome platform";
    private static final String CRUMMY_PLATFORM = "crummy platform";
    private static final String TRS_ID = "quay.io/dockstoretestuser2/dockstore-cgpmap";
    private static final String VERSION_NAME = "symbolic.v1";

    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
    }

    @Test(expected = ApiException.class)
    public void testVerificationOnSourceFileLevelForWorkflowsAsOwner() throws ApiException {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        // need to turn off admin of USER_2_USERNAME
        testingPostgres.runUpdateStatement("update enduser set isadmin = 'f' where username = '" + USER_2_USERNAME + "'");
        testVerificationWithGivenClient(webClient, webClient);
    }

    @Test(expected = ApiException.class)
    public void testVerificationOnSourceFileLevelForWorkflowsAsAnon() throws ApiException {
        testVerificationWithGivenClient(getWebClient(USER_2_USERNAME, testingPostgres), getAnonymousWebClient());
    }

    @Test(expected = ApiException.class)
    public void testVerificationOnSourceFileLevelForWorkflowsAsWrongUser() throws ApiException {
        testVerificationWithGivenClient(getWebClient(USER_2_USERNAME, testingPostgres), getWebClient(USER_1_USERNAME, testingPostgres));
        testVerificationWithGivenClient(getWebClient(USER_2_USERNAME, testingPostgres), getWebClient(OTHER_USERNAME, testingPostgres));
    }

    @Test
    public void testVerificationOnSourceFileLevelForWorkflowsAsAdmin() throws ApiException {
        // can verify anyone's workflow as an admin
        testVerificationWithGivenClient(getWebClient(USER_2_USERNAME, testingPostgres), getWebClient(ADMIN_USERNAME, testingPostgres));
    }

    @Test
    public void testVerificationOnSourceFileLevelForWorkflowsAsCurator() throws ApiException {
        // or as a curator
        testVerificationWithGivenClient(getWebClient(USER_2_USERNAME, testingPostgres), getWebClient(curatorUsername, testingPostgres));
    }

    @SuppressWarnings({"checkstyle:AvoidNestedBlocks"})
    private void testVerificationWithGivenClient(ApiClient registeringUser, ApiClient verifyingUser) {
        String defaultTestParameterFilePath = "/test.json";
        String id = "#workflow/github.com/DockstoreTestUser2/dockstore_workflow_cnv";
        final Workflow workflowByPathGithub;
        {
            // do initial registration as registeringUser
            WorkflowsApi workflowApi = new WorkflowsApi(registeringUser);
            workflowApi.manualRegister("github", "DockstoreTestUser2/dockstore_workflow_cnv", "/workflow/cnv.cwl", "", "cwl",
                defaultTestParameterFilePath);
            workflowByPathGithub = workflowApi.getWorkflowByPath(DOCKSTORE_TEST_USER2_RELATIVE_IMPORTS_WORKFLOW, null, false);

            // refresh and publish the workflow
            final Workflow workflow = workflowApi.refresh(workflowByPathGithub.getId(), false);
            workflowApi.publish(workflow.getId(), CommonTestUtilities.createPublishRequest(true));
        }

        // create verification data as the verifyingUser
        {
            // check on URLs for workflows via ga4gh calls
            ExtendedGa4GhApi extendedGa4GhApi = new ExtendedGa4GhApi(verifyingUser);
            // try to add verification metadata
            Map<String, Object> stringObjectMap = extendedGa4GhApi
                .toolsIdVersionsVersionIdTypeTestsPost("CWL", id, "master", defaultTestParameterFilePath, AWESOME_PLATFORM, "2.0.0",
                    "metadata", true);
            Assert.assertEquals(1, stringObjectMap.size());
            stringObjectMap = extendedGa4GhApi
                .toolsIdVersionsVersionIdTypeTestsPost("CWL", id, "master", defaultTestParameterFilePath, CRUMMY_PLATFORM, "1.0.0",
                    "metadata", true);
            Assert.assertEquals(2, stringObjectMap.size());

            // assert some things about map structure
            Assert.assertTrue("verification information seems off",
                stringObjectMap.containsKey(AWESOME_PLATFORM) && stringObjectMap.containsKey(CRUMMY_PLATFORM) && stringObjectMap
                    .get(AWESOME_PLATFORM) instanceof Map && stringObjectMap.get(CRUMMY_PLATFORM) instanceof Map
                    && ((Map)stringObjectMap.get(AWESOME_PLATFORM)).size() == 3 && ((Map)stringObjectMap.get(AWESOME_PLATFORM))
                    .get("metadata").equals("metadata"));
            Assert
                .assertEquals("AWESOME_PLATFORM has the wrong version", ((Map)stringObjectMap.get(AWESOME_PLATFORM)).get("platformVersion"),
                    "2.0.0");
            Assert.assertEquals("CRUMMY_PLATFORM has the wrong version", ((Map)stringObjectMap.get(CRUMMY_PLATFORM)).get("platformVersion"),
                "1.0.0");

            // verification on a sourcefile level should flow up to to version and entry level
            Ga4GhApi api = new Ga4GhApi(verifyingUser);
            Tool tool = api.toolsIdGet(id);
            Assert.assertTrue("verification states do not seem to flow up",
                tool.isVerified() && tool.getVersions().stream().anyMatch(ToolVersion::isVerified));
        }
        {
            ExtendedGa4GhApi extendedGa4GhApi = new ExtendedGa4GhApi(registeringUser);
            // refresh as the owner
            WorkflowsApi workflowApi = new WorkflowsApi(registeringUser);
            // refresh should not destroy verification data
            workflowApi.refresh(workflowByPathGithub.getId(), false);
            Map<String, Object> stringObjectMap = extendedGa4GhApi
                .toolsIdVersionsVersionIdTypeTestsPost("CWL", id, "master", defaultTestParameterFilePath, CRUMMY_PLATFORM, "1.0.0",
                    "new metadata", true);
            Assert.assertEquals(2, stringObjectMap.size());
            Assert
                .assertEquals("AWESOME_PLATFORM has the wrong version", ((Map)stringObjectMap.get(AWESOME_PLATFORM)).get("platformVersion"),
                    "2.0.0");
            Assert.assertEquals("CRUMMY_PLATFORM has the wrong version", ((Map)stringObjectMap.get(CRUMMY_PLATFORM)).get("platformVersion"),
                "1.0.0");
        }

        {
            ExtendedGa4GhApi extendedGa4GhApi = new ExtendedGa4GhApi(verifyingUser);
            // try to remove verification metadata
            extendedGa4GhApi
                .toolsIdVersionsVersionIdTypeTestsPost("CWL", id, "master", defaultTestParameterFilePath, AWESOME_PLATFORM, "2.0.0",
                    "metadata", null);
            Map<String, Object> stringObjectMap = extendedGa4GhApi
                .toolsIdVersionsVersionIdTypeTestsPost("CWL", id, "master", defaultTestParameterFilePath, CRUMMY_PLATFORM, "1.0.0",
                    "metadata", null);
            Assert.assertEquals(0, stringObjectMap.size());
        }
    }

    /**
     * Tests verification using the extended TRS endpoint.
     * Also tests that the tag verification endpoint can fix a potential sync issue
     */
    @Test
    public void testVerificationOnSourceFileLevelForTools() {
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        ContainersApi toolApi = new ContainersApi(webClient);
        ContainertagsApi containertagsApi = new ContainertagsApi(webClient);
        Ga4GhApi ga4GhApi = new Ga4GhApi(webClient);
        DockstoreTool tool = new DockstoreTool();
        tool.setDefaultCwlPath("/cwls/cgpmap-bamOut.cwl");
        tool.setGitUrl("git@github.com:DockstoreTestUser2/dockstore-cgpmap.git");
        tool.setNamespace("dockstoretestuser2");
        tool.setName("dockstore-cgpmap");
        tool.setRegistryString(Registry.QUAY_IO.getDockerPath());
        tool.setDefaultVersion("symbolic.v1");
        tool.setDefaultCWLTestParameterFile("/examples/cgpmap/bamOut/bam_input.json");

        DockstoreTool registeredTool = toolApi.registerManual(tool);
        registeredTool = toolApi.refresh(registeredTool.getId());

        // Make publish request (true)
        final PublishRequest publishRequest = CommonTestUtilities.createPublishRequest(true);
        toolApi.publish(registeredTool.getId(), publishRequest);

        // check on URLs for workflows via ga4gh calls
        ExtendedGa4GhApi extendedGa4GhApi = new ExtendedGa4GhApi(webClient);
        // try to add verification metadata
        Map<String, Object> stringObjectMap = extendedGa4GhApi
            .toolsIdVersionsVersionIdTypeTestsPost("CWL", "quay.io/dockstoretestuser2/dockstore-cgpmap", "symbolic.v1",
                "/examples/cgpmap/bamOut/bam_input.json", AWESOME_PLATFORM, "2.0.0", "metadata", true);
        Assert.assertEquals(1, stringObjectMap.size());

        // see if refresh destroys verification metadata
        registeredTool = toolApi.refresh(registeredTool.getId());
        final Long toolId = registeredTool.getId();
        final Long tagId = getSpecificVersion(registeredTool).getId();
        stringObjectMap = extendedGa4GhApi
            .toolsIdVersionsVersionIdTypeTestsPost("CWL", TRS_ID, VERSION_NAME, "/examples/cgpmap/bamOut/bam_input.json", "crummy platform",
                "1.0.0", "metadata", true);
        Assert.assertEquals(2, stringObjectMap.size());
        Assert.assertEquals("AWESOME_PLATFORM has the wrong version", ((Map)stringObjectMap.get(AWESOME_PLATFORM)).get("platformVersion"),
            "2.0.0");
        Assert.assertEquals("CRUMMY_PLATFORM has the wrong version", ((Map)stringObjectMap.get(CRUMMY_PLATFORM)).get("platformVersion"),
            "1.0.0");
    }

    private Tag getSpecificVersion(DockstoreTool dockstoreTool) {
        Optional<Tag> first = dockstoreTool.getWorkflowVersions().stream().filter(version -> version.getName().equals(VERSION_NAME))
            .findFirst();
        return first.orElse(null);
    }

    private void assertNotOutOfSync(Long toolId, ContainersApi containersApi, Ga4GhApi ga4GhApi) {
        // Get tool, tag, and TRS toolVersion
        DockstoreTool dockstoreTool = containersApi.getContainer(toolId, null);
        Tag tag = getSpecificVersion(dockstoreTool);
        ToolVersion toolVersion = ga4GhApi.toolsIdVersionsVersionIdGet(TRS_ID, VERSION_NAME);
        // Check that it's no longer out of sync in dockstore
        Assert.assertTrue(tag.isVerified());
        Assert.assertEquals(Collections.singletonList("metadata"), tag.getVerifiedSources());
        // Check that it's no longer out of sync in TRS
        Assert.assertTrue(toolVersion.isVerified());
        Assert.assertEquals("[\"metadata\"]", toolVersion.getVerifiedSource());
    }

    private void assertOutOfSync(Long toolId, ContainersApi containersApi, Ga4GhApi ga4GhApi) {
        // Get tool, tag, and TRS toolVersion
        DockstoreTool dockstoreTool = containersApi.getContainer(toolId, null);
        Tag tag = getSpecificVersion(dockstoreTool);
        ToolVersion toolVersion = ga4GhApi.toolsIdVersionsVersionIdGet(TRS_ID, VERSION_NAME);
        // Check that it's out of sync in dockstore
        Assert.assertFalse(tag.isVerified());
        Assert.assertEquals(new ArrayList<String>(), tag.getVerifiedSources());
        // Check that it's out of sync in TRS
        Assert.assertFalse(toolVersion.isVerified());
        Assert.assertEquals("[]", toolVersion.getVerifiedSource());
    }
}
