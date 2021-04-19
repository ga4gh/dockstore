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
import io.swagger.client.model.MetadataV1;
import io.swagger.client.model.ToolClass;
import io.swagger.client.model.ToolDockerfile;
import io.swagger.client.model.ToolTestsV1;
import io.swagger.client.model.ToolV1;
import io.swagger.client.model.ToolVersionV1;
import io.swagger.model.ToolDescriptor;
import org.apache.http.HttpStatus;
import org.junit.Test;

import static io.dropwizard.testing.FixtureHelpers.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author gluu
 * @since 19/04/17
 */
public class GA4GHV1IT extends GA4GHIT {
    private static final String API_VERSION = "api/ga4gh/v1/";

    public String getApiVersion() {
        return API_VERSION;
    }

    @Test
    @Override
    public void testMetadata() throws Exception {
        Response response = checkedResponse(baseURL + "metadata");
        MetadataV1 metadata = response.readEntity(MetadataV1.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(metadata)).contains("api-version");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(metadata)).contains("friendly-name");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(metadata)).doesNotContain("api_version");
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(metadata)).doesNotContain("friendly_name");
    }

    @Test
    @Override
    public void testTools() throws Exception {
        // The other test which uses a different DB manages to cache the endpoint with different tools
        // Restart application to clear cache
        SUPPORT.after();
        SUPPORT.before();
        Response response = checkedResponse(baseURL + "tools");
        List<ToolV1> responseObject = response.readEntity(new GenericType<List<ToolV1>>() {
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
        ToolV1 responseObject = response.readEntity(ToolV1.class);
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseObject), true);
        // also try search by id
        response = checkedResponse(baseURL + "tools?id=quay.io%2Ftest_org%2Ftest6");
        List<ToolV1> responseList = response.readEntity(new GenericType<List<ToolV1>>() {
        });
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseList), true);
    }

    private void toolsIdWorkflow() throws Exception {
        Response response = checkedResponse(baseURL + "tools/%23workflow%2Fgithub.com%2FA%2Fl");
        ToolV1 responseObject = response.readEntity(ToolV1.class);
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseObject), false);
        // also try search by id
        response = checkedResponse(baseURL + "tools?id=%23workflow%2Fgithub.com%2FA%2Fl");
        List<ToolV1> responseList = response.readEntity(new GenericType<List<ToolV1>>() {
        });
        assertTool(SUPPORT.getObjectMapper().writeValueAsString(responseList), false);
    }

    @Test
    @Override
    public void testToolsIdVersions() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions");
        List<ToolVersionV1> responseObject = response.readEntity(new GenericType<List<ToolVersionV1>>() {
        });
        assertVersion(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Test
    @Override
    public void testToolClasses() throws Exception {
        Response response = checkedResponse(baseURL + "tool-classes");
        List<ToolClass> responseObject = response.readEntity(new GenericType<List<ToolClass>>() {
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
        ToolVersionV1 responseObject = response.readEntity(ToolVersionV1.class);
        assertVersion(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    public void testToolsIdVersionsVersionIdTypeDescriptor() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor");
        ToolDescriptor responseObject = response.readEntity(ToolDescriptor.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    protected void toolsIdVersionsVersionIdTypeDescriptorRelativePathNormal() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor/%2FDockstore.cwl");
        ToolDescriptor responseObject = response.readEntity(ToolDescriptor.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    protected void toolsIdVersionsVersionIdTypeDescriptorRelativePathMissingSlash() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor/Dockstore.cwl");
        ToolDescriptor responseObject = response.readEntity(ToolDescriptor.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Override
    protected void toolsIdVersionsVersionIdTypeDescriptorRelativePathExtraDot() throws Exception {
        Response response = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor/.%2FDockstore.cwl");
        ToolDescriptor responseObject = response.readEntity(ToolDescriptor.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        assertDescriptor(SUPPORT.getObjectMapper().writeValueAsString(responseObject));
    }

    @Test
    @Override
    public void testRelativePathEndpointToolTestParameterFileJSON() {
        Response response = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/descriptor/%2Fnested%2Ftest.cwl.json");
        ToolTestsV1 responseObject = response.readEntity(ToolTestsV1.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("nestedPotato", responseObject.getTest());
        Response response2 = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/WDL/descriptor/%2Fnested%2Ftest.wdl.json");
        ToolTestsV1 responseObject2 = response2.readEntity(ToolTestsV1.class);
        assertEquals(HttpStatus.SC_OK, response2.getStatus());
        assertEquals("nestedPotato", responseObject2.getTest());
    }

    @Test
    @Override
    public void testRelativePathEndpointWorkflowTestParameterFileJSON() throws Exception {
        // Insert the 4 workflows into the database using migrations
        CommonTestUtilities.setupTestWorkflow(SUPPORT);

        // Check responses
        Response response = checkedResponse(
            baseURL + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/CWL/descriptor/%2Fnested%2Ftest.cwl.json");
        ToolTestsV1 responseObject = response.readEntity(ToolTestsV1.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("nestedPotato", responseObject.getTest());
        Response response2 = client
            .target(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/WDL/descriptor/%2Ftest.potato.json").request().get();
        assertEquals(HttpStatus.SC_NOT_FOUND, response2.getStatus());
        Response response3 = checkedResponse(
            baseURL + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/CWL/descriptor/%2Ftest.cwl.json");
        ToolTestsV1 responseObject3 = response3.readEntity(ToolTestsV1.class);
        assertEquals(HttpStatus.SC_OK, response3.getStatus());
        assertEquals("potato", responseObject3.getTest());

        // reset DB for other tests
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
    }

    @Test
    @Override
    public void testToolsIdVersionsVersionIdTypeTests() throws Exception {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/CWL/tests");
        List<ToolTestsV1> responseObject = response.readEntity(new GenericType<List<ToolTestsV1>>() {
        });
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject).contains("test")).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
    }

    @Override
    void assertDescriptor(String descriptor) {
        assertThat(descriptor).contains("type");
        assertThat(descriptor).contains("descriptor");
    }

    @Test
    @Override
    public void testToolsIdVersionsVersionIdTypeDockerfile() {
        Response response = checkedResponse(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/dockerfile");
        // note: v1 really does expect only one item
        ToolDockerfile responseObject = response.readEntity(ToolDockerfile.class);
        assertTrue(!responseObject.getDockerfile().isEmpty() && !responseObject.getUrl().isEmpty());
    }

    @Override
    protected void assertVersion(String version) {
        assertThat(version).contains("meta-version");
        assertThat(version).contains("descriptor-type");
        assertThat(version).contains("verified-source");
        assertThat(version).doesNotContain("meta_version");
        assertThat(version).doesNotContain("descriptor_type");
        assertThat(version).doesNotContain("verified_source");
    }

    @Override
    protected void assertTool(String tool, boolean isTool) {
        assertThat(tool).contains("meta-version");
        assertThat(tool).contains("verified-source");
        assertThat(tool).doesNotContain("meta_version");
        assertThat(tool).doesNotContain("verified_source");
        if (isTool) {
            assertVersion(tool);
        }
    }

    /**
     * This tests if the 4 workflows with a combination of different repositories and either same or matching workflow name
     * can be retrieved separately
     */
    @Test
    public void toolsIdGet4Workflows() throws Exception {
        // Insert the 4 workflows into the database using migrations
        CommonTestUtilities.setupSamePathsTest(SUPPORT);
        Response response2 = checkedResponse(baseURL + "tools");
        checkedResponse(baseURL + "tools");
        List<ToolV1> responseObject2 = response2.readEntity(new GenericType<List<ToolV1>>() {
        });
        assertNotNull(responseObject2);
        // Check responses
        Response response = checkedResponse(baseURL + "tools/%23workflow%2Fgithub.com%2FfakeOrganization%2FfakeRepository");
        ToolV1 responseObject = response.readEntity(ToolV1.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author1");
        response = checkedResponse(baseURL + "tools/%23workflow%2Fbitbucket.org%2FfakeOrganization%2FfakeRepository");
        responseObject = response.readEntity(ToolV1.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author2");
        response = checkedResponse(baseURL + "tools/%23workflow%2Fgithub.com%2FfakeOrganization%2FfakeRepository%2FPotato");
        responseObject = response.readEntity(ToolV1.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author3");
        response = checkedResponse(baseURL + "tools/%23workflow%2Fbitbucket.org%2FfakeOrganization%2FfakeRepository%2FPotato");
        responseObject = response.readEntity(ToolV1.class);
        assertThat(SUPPORT.getObjectMapper().writeValueAsString(responseObject)).contains("author4");

        // test garbage source control value
        response = checkedResponse(baseURL + "tools/%23workflow%2Fgarbagio%2FfakeOrganization%2FfakeRepository%2FPotato", HttpStatus.SC_NOT_FOUND);
        assertThat(response == null);

        // reset DB for other tests
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
    }
}
