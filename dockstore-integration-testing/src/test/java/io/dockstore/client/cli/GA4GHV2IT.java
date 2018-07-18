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

import java.util.List;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.core.type.TypeReference;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.Utilities;
import io.dropwizard.testing.ResourceHelpers;
import io.swagger.client.model.ToolClass;
import io.swagger.model.FileWrapper;
import io.swagger.model.Metadata;
import io.swagger.model.Tool;
import io.swagger.model.ToolFile;
import io.swagger.model.ToolVersion;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.Assert;
import org.junit.Test;

import static io.dropwizard.testing.FixtureHelpers.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * @author gluu
 * @since 02/01/18
 */
public class GA4GHV2IT extends GA4GHIT {
    private static final String apiVersion = "api/ga4gh/v2/";

    public String getApiVersion() {
        return apiVersion;
    }

    @Test
    public void metadata() throws Exception {
        Response response = checkedResponse(basePath + "metadata");
        Metadata responseObject = response.readEntity(Metadata.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("api_version");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("friendly_name");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).doesNotContain("api-version");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).doesNotContain("friendly-name");
    }

    @Test
    public void tools() throws Exception {
        Response response = checkedResponse(basePath + "tools");
        List<Tool> responseObject = response.readEntity(new GenericType<List<Tool>>() {
        });
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseObject), true);
    }

    @Test
    public void toolsId() throws Exception {
        toolsIdTool();
        toolsIdWorkflow();
    }

    private void toolsIdTool() throws Exception {
        Response response = checkedResponse(basePath + "tools/quay.io%2Ftest_org%2Ftest6");
        Tool responseObject = response.readEntity(Tool.class);
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseObject), true);
        // regression test for #1248
        Assert.assertTrue("registry_url should never be null", responseObject.getVersions().size() > 0 && responseObject.getVersions().stream().allMatch(version -> version.getRegistryUrl() != null));
        Assert.assertTrue("imageName should never be null", responseObject.getVersions().size() > 0 && responseObject.getVersions().stream().allMatch(version -> version.getImageName() != null));
        // search by id
        response = checkedResponse(basePath + "tools?id=quay.io%2Ftest_org%2Ftest6");
        List<Tool> responseList = response.readEntity(List.class);
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseList), true);
    }

    private void toolsIdWorkflow() throws Exception {
        Response response = checkedResponse(basePath + "tools/%23workflow%2Fgithub.com%2FA%2Fl");
        Tool responseObject = response.readEntity(Tool.class);
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseObject), false);
        // search by id
        response = checkedResponse(basePath + "tools?id=%23workflow%2Fgithub.com%2FA%2Fl");
        List<Tool> responseList = response.readEntity(List.class);
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseList), false);
    }

    @Test
    public void toolsIdVersions() throws Exception {
        Response response = checkedResponse(basePath + "tools/quay.io%2Ftest_org%2Ftest6/versions");
        List<ToolVersion> responseObject = response.readEntity(new GenericType<List<ToolVersion>>() {
        });
        assertVersion(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Test
    public void toolClasses() throws Exception {
        Response response = checkedResponse(basePath + "toolClasses");
        List<ToolClass> responseObject = response.readEntity(new GenericType<List<ToolClass>>() {
        });
        final String expected = SUPPORT.getObjectMapper()
                .writeValueAsString(SUPPORT.getObjectMapper().readValue(fixture("fixtures/toolClasses.json"), new TypeReference<List<ToolClass>>() {
                }));
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).isEqualTo(expected);
    }

    @Test
    public void toolsIdVersionsVersionId() throws Exception {
        Response response = checkedResponse(basePath + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName");
        ToolVersion responseObject = response.readEntity(ToolVersion.class);
        assertVersion(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    public void toolsIdVersionsVersionIdTypeDockerfile() throws Exception {
        Response response = checkedResponse(basePath + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/containerfile");
        List<FileWrapper> responseObject = response.readEntity(new GenericType<List<FileWrapper>>() {
        });
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject).contains("containerfile"));
    }

    /**
     * This tests the /tools/{id}/versions/{version_id}/{type}/files endpoint
     *
     * @throws Exception
     */
    @Test
    public void toolsIdVersionsVersionIdTypeFile() throws Exception {
        toolsIdVersionsVersionIdTypeFileCWL();
        toolsIdVersionsVersionIdTypeFileWDL();
    }

    @Test
    public void toolsIdVersionsVersionIdTypeDescriptorRelativePathNoEncode() throws Exception {
        Response response = checkedResponse(basePath + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor//Dockstore.cwl");
        FileWrapper responseObject = response.readEntity(FileWrapper.class);
        assertThat(response.getStatus()).isEqualTo(200);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    /**
     * Tests GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Tool with non-encoded nested cwl test parameter file
     * Tool with non-encoded non-nested cwl test parameter file
     * @throws Exception
     */
    @Test
    public void RelativePathEndpointToolTestParameterFileNoEncode() {
        Response response = checkedResponse(
            basePath + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_CWL/descriptor//nested/test.cwl.json");
        String responseObject = response.readEntity(String.class);
        assertEquals(200, response.getStatus());
        assertEquals("nestedPotato", responseObject);

        Response response2 = checkedResponse(
            basePath + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_CWL/descriptor//test.cwl.json");
        String responseObject2 = response2.readEntity(String.class);
        assertEquals(200, response2.getStatus());
        assertEquals("potato", responseObject2);
    }

    /**
     * Tests GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Workflow with non-encoded nested cwl test parameter file
     * Workflow with non-encoded non-nested cwl test parameter file
     * @throws Exception
     */
    @Test
    public void RelativePathEndpointWorkflowTestParameterFileNoEncode() throws Exception {
        // Insert the 4 workflows into the database using migrations
        CommonTestUtilities.setupTestWorkflow(SUPPORT);

        // Check responses
        Response response = checkedResponse(basePath + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/PLAIN_CWL/descriptor//nested/test.cwl.json");
        String responseObject = response.readEntity(String.class);
        assertEquals(200, response.getStatus());
        assertEquals("nestedPotato", responseObject);
        Response response2 = checkedResponse(basePath + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/PLAIN_CWL/descriptor//test.cwl.json");
        String responseObject2 = response2.readEntity(String.class);
        assertEquals(200, response2.getStatus());
        assertEquals("potato", responseObject2);
    }

    private void toolsIdVersionsVersionIdTypeFileCWL() throws Exception {
        Response response = checkedResponse(basePath + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/files");
        List<ToolFile> responseObject = response.readEntity(new GenericType<List<ToolFile>>() {
        });

        final String expected = SUPPORT.getObjectMapper()
                .writeValueAsString(SUPPORT.getObjectMapper().readValue(fixture("fixtures/cwlFiles.json"), new TypeReference<List<ToolFile>>() {
                }));
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).isEqualTo(expected);
    }

    private void toolsIdVersionsVersionIdTypeFileWDL() throws Exception {
        Response response = checkedResponse(basePath + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/WDL/files");
        List<ToolFile> responseObject = response.readEntity(new GenericType<List<ToolFile>>() {
        });
        final String expected = SUPPORT.getObjectMapper()
                .writeValueAsString(SUPPORT.getObjectMapper().readValue(fixture("fixtures/wdlFiles.json"), new TypeReference<List<ToolFile>>() {
                }));
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).isEqualTo(expected);
    }

    protected void assertVersion(String version) {
        assertThat(version).contains("meta_version");
        assertThat(version).contains("descriptor_type");
        assertThat(version).contains("verified_source");
        assertThat(version).doesNotContain("meta-version");
        assertThat(version).doesNotContain("descriptor-type");
        assertThat(version).doesNotContain("verified-source");
    }

    protected void assertTool(String tool, boolean isTool) {
        assertThat(tool).contains("meta_version");
        assertThat(tool).contains("verified_source");
        assertThat(tool).doesNotContain("meta-version");
        assertThat(tool).doesNotContain("verified-source");
        if (isTool) {
            assertVersion(tool);
        }
    }

    /**
     * This tests if the 4 workflows with a combination of different repositories and either same or matching workflow name
     * can be retrieved separately.  In the test database, the author happens to uniquely identify the workflows.
     *
     * @throws Exception
     */
    @Test
    public void toolsIdGet4Workflows() throws Exception {
        // Insert the 4 workflows into the database using migrations
        CommonTestUtilities.setupSamePathsTest(SUPPORT);

        // Check responses
        Response response = checkedResponse(basePath + "tools/%23workflow%2Fgithub.com%2FfakeOrganization%2FfakeRepository");
        Tool responseObject = response.readEntity(Tool.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author1");
        response = checkedResponse(basePath + "tools/%23workflow%2Fbitbucket.org%2FfakeOrganization%2FfakeRepository");
        responseObject = response.readEntity(Tool.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author2");
        response = checkedResponse(basePath + "tools/%23workflow%2Fgithub.com%2FfakeOrganization%2FfakeRepository%2FPotato");
        responseObject = response.readEntity(Tool.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author3");
        response = checkedResponse(basePath + "tools/%23workflow%2Fbitbucket.org%2FfakeOrganization%2FfakeRepository%2FPotato");
        responseObject = response.readEntity(Tool.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author4");
    }

    /**
     * This tests cwl-runner with a workflow from GA4GH V2 relative-path endpoint (without encoding) that contains 2 more additional files
     * that will reference the GA4GH V2 endpoint
     * @throws Exception
     */
    @Test
    public void cwlrunnerWorkflowRelativePathNotEncodedAdditionalFiles() throws Exception {
        CommonTestUtilities.setupTestWorkflow(SUPPORT);
        String command = "cwl-runner";
        String descriptorPath = basePath + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/plain-CWL/descriptor//Dockstore.cwl";
        String testParameterFilePath = ResourceHelpers.resourceFilePath("testWorkflow.json");
        ImmutablePair<String, String> stringStringImmutablePair = Utilities
                .executeCommand(command + " " + descriptorPath + " " + testParameterFilePath, System.out, System.err);
        Assert.assertTrue("failure message" + stringStringImmutablePair.left, stringStringImmutablePair.getRight().contains("Final process status is success"));
    }
}
