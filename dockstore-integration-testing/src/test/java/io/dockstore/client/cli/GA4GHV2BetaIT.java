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
import io.swagger.client.model.FileWrapper;
import io.swagger.client.model.Metadata;
import io.swagger.client.model.Tool;
import io.swagger.client.model.ToolClass;
import io.swagger.client.model.ToolFile;
import io.swagger.client.model.ToolVersion;
import org.apache.http.HttpStatus;
import org.junit.Test;

import static io.dropwizard.testing.FixtureHelpers.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author gluu
 * @since 02/01/18
 */
public class GA4GHV2BetaIT extends GA4GHIT {
    private static final String API_VERSION = "api/ga4gh/v2/";
    public String getApiVersion() {
        return API_VERSION;
    }

    @Override
    void assertDescriptor(String descriptor) {
        assertThat(descriptor).contains("url");
        assertThat(descriptor).contains("content");
    }

    @Test
    @Override
    public void testMetadata() throws Exception {
        Response response = checkedResponse(baseURL + "metadata");
        Metadata responseObject = response.readEntity(Metadata.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("api_version");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("friendly_name");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).doesNotContain("api-version");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).doesNotContain("friendly-name");
    }

    @Test
    @Override
    public void testTools() throws Exception {
        Response response = checkedResponse(baseURL + "tools");
        List<Tool> responseObject = response.readEntity(new GenericType<>() {
        });
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseObject), true);
    }

    @Test
    @Override
    public void testToolsId() throws Exception {
        toolsIdTool();
        toolsIdWorkflow();
    }

    private void toolsIdTool() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6");
        Tool responseObject = response.readEntity(Tool.class);
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseObject), true);
        // regression test for #1248
        assertTrue("registry_url should never be null", responseObject.getVersions().size() > 0 && responseObject.getVersions().stream()
            .allMatch(version -> version.getRegistryUrl() != null));
        assertTrue("imageName should never be null", responseObject.getVersions().size() > 0 && responseObject.getVersions().stream()
            .allMatch(version -> version.getImageName() != null));
        // search by id
        response = checkedResponse(baseURL + "tools?id=quay.io%2Ftest_org%2Ftest6");
        List<Tool> responseList = response.readEntity(new GenericType<>() {
        });
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseList), true);
    }

    private void toolsIdWorkflow() throws Exception {
        Response response = checkedResponse(baseURL + "tools/%23workflow%2Fgithub.com%2FA%2Fl");
        Tool responseObject = response.readEntity(Tool.class);
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseObject), false);
        // search by id
        response = checkedResponse(baseURL + "tools?id=%23workflow%2Fgithub.com%2FA%2Fl");
        List<Tool> responseList = response.readEntity(new GenericType<>() {
        });
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseList), false);
    }

    @Test
    @Override
    public void testToolsIdVersions() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions");
        List<ToolVersion> responseObject = response.readEntity(new GenericType<>() {
        });
        assertVersion(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Test
    @Override
    public void testToolClasses() throws Exception {
        Response response = checkedResponse(baseURL + "toolClasses");
        List<ToolClass> responseObject = response.readEntity(new GenericType<>() {
        });
        final String expected = SUPPORT.getObjectMapper().writeValueAsString(
            SUPPORT.getObjectMapper().readValue(fixture("fixtures/toolClasses.json"), new TypeReference<List<ToolClass>>() {
            }));
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).isEqualTo(expected);
    }

    @Test
    @Override
    public void testToolsIdVersionsVersionId() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName");
        ToolVersion responseObject = response.readEntity(ToolVersion.class);
        assertVersion(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    public void testToolsIdVersionsVersionIdTypeDescriptor() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor");
        FileWrapper responseObject = response.readEntity(FileWrapper.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    protected void toolsIdVersionsVersionIdTypeDescriptorRelativePathNormal() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor/%2FDockstore.cwl");
        FileWrapper responseObject = response.readEntity(FileWrapper.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    protected void toolsIdVersionsVersionIdTypeDescriptorRelativePathMissingSlash() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor/Dockstore.cwl");
        FileWrapper responseObject = response.readEntity(FileWrapper.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    protected void toolsIdVersionsVersionIdTypeDescriptorRelativePathExtraDot() throws Exception {
        Response response = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor/.%2FDockstore.cwl");
        FileWrapper responseObject = response.readEntity(FileWrapper.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Test
    @Override
    public void testRelativePathEndpointToolTestParameterFileJSON() {
        Response response = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor/%2Fnested%2Ftest.cwl.json");
        FileWrapper responseObject = response.readEntity(FileWrapper.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("nestedPotato", responseObject.getContent());
        Response response2 = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/WDL/descriptor/%2Fnested%2Ftest.wdl.json");
        FileWrapper responseObject2 = response2.readEntity(FileWrapper.class);
        assertEquals(HttpStatus.SC_OK, response2.getStatus());
        assertEquals("nestedPotato", responseObject2.getContent());
    }

    @Test
    @Override
    public void testRelativePathEndpointWorkflowTestParameterFileJSON() throws Exception {
        // Insert the 4 workflows into the database using migrations
        CommonTestUtilities.setupTestWorkflow(SUPPORT);

        // Check responses
        Response response = checkedResponse(
            baseURL + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/CWL/descriptor/%2Fnested%2Ftest.cwl.json");
        FileWrapper responseObject = response.readEntity(io.swagger.client.model.FileWrapper.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("nestedPotato", responseObject.getContent());
        Response response2 = client
            .target(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/WDL/descriptor/%2Ftest.potato.json").request().get();
        assertEquals(HttpStatus.SC_NOT_FOUND, response2.getStatus());
        Response response3 = checkedResponse(
            baseURL + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/CWL/descriptor/%2Ftest.cwl.json");
        io.swagger.client.model.FileWrapper responseObject3 = response3.readEntity(io.swagger.client.model.FileWrapper.class);
        assertEquals(HttpStatus.SC_OK, response3.getStatus());
        assertEquals("potato", responseObject3.getContent());

        // reset DB for other tests
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
    }

    @Test
    @Override
    public void testToolsIdVersionsVersionIdTypeTests() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/tests");
        List<FileWrapper> responseObject = response.readEntity(new GenericType<>() {
        });
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject).contains("test")).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
    }

    @Test
    @Override
    public void testToolsIdVersionsVersionIdTypeDockerfile() {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/containerfile");
        // note to tester, this seems to intentionally be a list in v2 as opposed to v1
        List<FileWrapper> responseObject = response.readEntity(new GenericType<>() {
        });
        assertEquals(1, responseObject.size());
        FileWrapper fileWrapper = responseObject.get(0);
        assertTrue(!fileWrapper.getContent().isEmpty() && !fileWrapper.getUrl().isEmpty());
    }

    /**
     * This tests the /tools/{id}/versions/{version_id}/{type}/files endpoint
     */
    @Test
    public void toolsIdVersionsVersionIdTypeFile() throws Exception {
        toolsIdVersionsVersionIdTypeFileCWL();
        toolsIdVersionsVersionIdTypeFileWDL();
    }

    @Test
    public void toolsIdVersionsVersionIdTypeDescriptorRelativePathNoEncode() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor//Dockstore.cwl");
        FileWrapper responseObject = response.readEntity(FileWrapper.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    /**
     * Tests GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Tool with non-encoded nested cwl test parameter file
     * Tool with non-encoded non-nested cwl test parameter file
     */
    @Test
    public void relativePathEndpointToolTestParameterFileNoEncode() {
        Response response = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_CWL/descriptor//nested/test.cwl.json");
        String responseObject = response.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("nestedPotato", responseObject);

        Response response2 = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_CWL/descriptor//test.cwl.json");
        String responseObject2 = response2.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response2.getStatus());
        assertEquals("potato", responseObject2);
    }

    /**
     * Tests GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Workflow with non-encoded nested cwl test parameter file
     * Workflow with non-encoded non-nested cwl test parameter file
     */
    @Test
    public void relativePathEndpointWorkflowTestParameterFileNoEncode() throws Exception {
        // Insert the 4 workflows into the database using migrations
        CommonTestUtilities.setupTestWorkflow(SUPPORT);

        // Check responses
        Response response = checkedResponse(
            baseURL + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/PLAIN_CWL/descriptor//nested/test.cwl.json");
        String responseObject = response.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("nestedPotato", responseObject);
        Response response2 = checkedResponse(
            baseURL + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/PLAIN_CWL/descriptor//test.cwl.json");
        String responseObject2 = response2.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response2.getStatus());
        assertEquals("potato", responseObject2);

        // reset DB for other tests
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
    }

    private void toolsIdVersionsVersionIdTypeFileCWL() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/files");
        List<ToolFile> responseObject = response.readEntity(new GenericType<>() {
        });

        final String expected = SUPPORT.getObjectMapper()
            .writeValueAsString(SUPPORT.getObjectMapper().readValue(fixture("fixtures/cwlFiles.json"), new TypeReference<List<ToolFile>>() {
            }));
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).isEqualTo(expected);
    }

    private void toolsIdVersionsVersionIdTypeFileWDL() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/WDL/files");
        List<ToolFile> responseObject = response.readEntity(new GenericType<>() {
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
     */
    @Test
    public void toolsIdGet4Workflows() throws Exception {
        // Insert the 4 workflows into the database using migrations
        CommonTestUtilities.setupSamePathsTest(SUPPORT);

        // Check responses
        Response response = checkedResponse(baseURL + "tools/%23workflow%2Fgithub.com%2FfakeOrganization%2FfakeRepository");
        Tool responseObject = response.readEntity(Tool.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author1");
        response = checkedResponse(baseURL + "tools/%23workflow%2Fbitbucket.org%2FfakeOrganization%2FfakeRepository");
        responseObject = response.readEntity(Tool.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author2");
        response = checkedResponse(baseURL + "tools/%23workflow%2Fgithub.com%2FfakeOrganization%2FfakeRepository%2FPotato");
        responseObject = response.readEntity(Tool.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author3");
        response = checkedResponse(baseURL + "tools/%23workflow%2Fbitbucket.org%2FfakeOrganization%2FfakeRepository%2FPotato");
        responseObject = response.readEntity(Tool.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author4");

        // test garbage source control value
        response = checkedResponse(baseURL + "tools/%23workflow%2Fgarbagio%2FfakeOrganization%2FfakeRepository%2FPotato", HttpStatus.SC_NOT_FOUND);
        assertThat(response == null);

        // reset DB for other tests
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
    }
}
