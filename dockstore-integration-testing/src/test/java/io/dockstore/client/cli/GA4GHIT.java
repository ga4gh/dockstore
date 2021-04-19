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

import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.Response;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.TestUtility;
import io.dockstore.common.TestingPostgres;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.DropwizardTestSupport;
import io.swagger.model.Error;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import static io.dockstore.common.CommonTestUtilities.WAIT_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * @author gluu
 * @since 03/01/18
 */
public abstract class GA4GHIT {
    protected static final DropwizardTestSupport<DockstoreWebserviceConfiguration> SUPPORT = new DropwizardTestSupport<>(
        DockstoreWebserviceApplication.class, CommonTestUtilities.PUBLIC_CONFIG_PATH,
        ConfigOverride.config("database.properties.hibernate.hbm2ddl.auto", "validate"));
    protected static javax.ws.rs.client.Client client;
    protected static TestingPostgres testingPostgres;
    @Rule
    public final TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            System.out.println("Starting test: " + description.getMethodName());
        }
    };
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    final String basePath = SUPPORT.getConfiguration().getExternalConfig().getBasePath();
    final String baseURL = String.format("http://localhost:%d" + basePath + getApiVersion(), SUPPORT.getLocalPort());

    @BeforeClass
    public static void dropAndRecreateDB() throws Exception {
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, true);
        SUPPORT.before();
        client = new JerseyClientBuilder(SUPPORT.getEnvironment()).build("test client").property(ClientProperties.READ_TIMEOUT, WAIT_TIME);
        testingPostgres = new TestingPostgres(SUPPORT);
    }

    @AfterClass
    public static void afterClass() {
        SUPPORT.after();
    }

    protected abstract String getApiVersion();

    /**
     * This tests the /metadata endpoint
     */
    @Test
    public abstract void testMetadata() throws Exception;

    /**
     * This tests the /tools endpoint
     */
    @Test
    public abstract void testTools() throws Exception;

    /**
     * This tests the /tools/{id} endpoint
     */
    @Test
    public abstract void testToolsId() throws Exception;

    /**
     * This tests the /tools/{id}/versions endpoint
     */
    @Test
    public abstract void testToolsIdVersions() throws Exception;

    /**
     * This tests the /tool-classes or /testToolClasses endpoint
     */
    @Test
    public abstract void testToolClasses() throws Exception;

    /**
     * This tests the /tools/{id}/versions/{version_id} endpoint
     */
    @Test
    public abstract void testToolsIdVersionsVersionId() throws Exception;

    /**
     * This tests the /tools/{id}/versions/{version-id}/{type}/descriptor endpoint
     */
    @Test
    public abstract void testToolsIdVersionsVersionIdTypeDescriptor() throws Exception;

    /**
     * This tests the /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} endpoint
     */

    @Test
    public void testToolsIdVersionsVersionIdTypeDescriptorRelativePath() throws Exception {
        toolsIdVersionsVersionIdTypeDescriptorRelativePathNormal();
        toolsIdVersionsVersionIdTypeDescriptorRelativePathMissingSlash();
        toolsIdVersionsVersionIdTypeDescriptorRelativePathExtraDot();
    }

    protected abstract void toolsIdVersionsVersionIdTypeDescriptorRelativePathNormal() throws Exception;

    protected abstract void toolsIdVersionsVersionIdTypeDescriptorRelativePathMissingSlash() throws Exception;

    protected abstract void toolsIdVersionsVersionIdTypeDescriptorRelativePathExtraDot() throws Exception;

    /**
     * Tests PLAIN GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Tool with nested cwl test parameter file
     * Tool with non-existent cwl test parameter file
     * Tool with nested wdl test parameter file
     * Tool with non-existent wdl test parameter file
     */
    @Test
    public void relativePathEndpointToolTestParameterFilePLAIN() {
        Response response = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_CWL/descriptor/%2Fnested%2Ftest.cwl.json");
        String responseObject = response.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("nestedPotato", responseObject);
        Response response2 = client
            .target(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_CWL/descriptor/%2Ftest.potato.json").request()
            .get();
        assertEquals(HttpStatus.SC_NOT_FOUND, response2.getStatus());
        Response response3 = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_WDL/descriptor/%2Fnested%2Ftest.wdl.json");
        String responseObject3 = response3.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response3.getStatus());
        assertEquals("nestedPotato", responseObject3);
        Response response4 = client
            .target(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_WDL/descriptor/%2Ftest.potato.json").request()
            .get();
        assertEquals(HttpStatus.SC_NOT_FOUND, response4.getStatus());
    }

    /**
     * Tests JSON GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Tool with nested cwl test parameter file
     * Tool with non-existent cwl test parameter file
     * Tool with nested wdl test parameter file
     * Tool with non-existent wdl test parameter file
     */
    @Test
    public abstract void testRelativePathEndpointToolTestParameterFileJSON();

    /**
     * Tests GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Tool with a dockerfile
     */
    @Test
    public void relativePathEndpointToolContainerfile() {
        Response response = checkedResponse(
            baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_CWL/descriptor/%2FDockerfile");
        String responseObject = response.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("potato", responseObject);
    }

    /**
     * Tests PLAIN GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Workflow with nested cwl test parameter file
     * Workflow with non-existent wdl test parameter file
     * Workflow with non-nested cwl test parameter file
     */
    @Test
    public void relativePathEndpointWorkflowTestParameterFilePLAIN() throws Exception {
        // Insert the 4 workflows into the database using migrations
        CommonTestUtilities.setupTestWorkflow(SUPPORT);

        // Check responses
        Response response = checkedResponse(baseURL
            + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/PLAIN_CWL/descriptor/%2Fnested%2Ftest.cwl.json");
        String responseObject = response.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response.getStatus());
        assertEquals("nestedPotato", responseObject);
        Response response2 = client
            .target(baseURL + "tools/quay.io%2Ftest_org%2Ftest6/versions/fakeName/PLAIN_WDL/descriptor/%2Ftest.potato.json").request()
            .get();
        assertEquals(HttpStatus.SC_NOT_FOUND, response2.getStatus());
        Response response3 = checkedResponse(
            baseURL + "tools/%23workflow%2Fgithub.com%2Fgaryluu%2FtestWorkflow/versions/master/PLAIN_CWL/descriptor/%2Ftest.cwl.json");
        String responseObject3 = response3.readEntity(String.class);
        assertEquals(HttpStatus.SC_OK, response3.getStatus());
        assertEquals("potato", responseObject3);

        // reset DB for other tests
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
    }

    /**
     * Tests JSON GET /tools/{id}/versions/{version_id}/{type}/descriptor/{relative_path} with:
     * Workflow with nested cwl test parameter file
     * Workflow with non-existent wdl test parameter file
     * Workflow with non-nested cwl test parameter file
     */
    @Test
    public abstract void testRelativePathEndpointWorkflowTestParameterFileJSON() throws Exception;

    /**
     * This tests the /tools/{id}/versions/{version_id}/{type}/tests endpoint
     */
    @Test
    public abstract void testToolsIdVersionsVersionIdTypeTests() throws Exception;

    /**
     * checks that a descriptor or equivalent has the right fields
     *
     * @param descriptor the descriptor to check
     */
    abstract void assertDescriptor(String descriptor);

    protected abstract void assertTool(String tool, boolean isTool);

    @Test
    public abstract void testToolsIdVersionsVersionIdTypeDockerfile() throws Exception;

    protected abstract void assertVersion(String toolVersion);

    Response checkedResponse(String path) {
        return checkedResponse(path, HttpStatus.SC_OK);
    }

    Response checkedResponse(String path, int expectedStatus) {
        String nginxRewrittenPath = TestUtility.mimicNginxRewrite(path, basePath);
        Response response = client.target(nginxRewrittenPath).request().get();
        response.bufferEntity();
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        if (expectedStatus != HttpStatus.SC_OK) {
            return null;
        }
        String stringResponse = response.readEntity(String.class);
        JsonParser parser = new JsonParser();
        JsonElement jsonElement = parser.parse(stringResponse);
        parseJSONElementForURLs(jsonElement);
        return response;
    }

    /**
     * This tests that a 400 response returns an Error response object similar to the HttpStatus.SC_NOT_FOUND response defined in the
     * GA4GH swagger.yaml
     */
    @Test
    public void testInvalidToolId() {
        String nginxRewrittenPath = TestUtility.mimicNginxRewrite(baseURL + "tools/potato", basePath);
        Response response = client.target(nginxRewrittenPath).request().get();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        Error error = response.readEntity(Error.class);
        Assert.assertTrue(error.getMessage().contains("Tool ID should"));
        assertThat(error.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
    }

    /**
     * Checks the JsonElement for URLs
     *
     * @param jsonElement The JsonElement to check
     */
    private void parseJSONElementForURLs(JsonElement jsonElement) {
        if (jsonElement.isJsonObject()) {
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            Set<Map.Entry<String, JsonElement>> keys = jsonObject.entrySet();
            for (Map.Entry<String, JsonElement> jsonElementEntry : keys) {
                if (jsonElementEntry.getKey().equals("url")) {
                    checkURL(jsonElementEntry.getValue().getAsString());
                } else {
                    parseJSONElementForURLs(jsonElementEntry.getValue());
                }
            }
        } else {
            if (jsonElement.isJsonArray()) {
                JsonArray jsonArray = jsonElement.getAsJsonArray();
                jsonArray.forEach(this::parseJSONElementForURLs);
            } else {
                if (!jsonElement.isJsonPrimitive()) {
                    Assert.fail("Unknown type: " + jsonElement.toString());
                }
            }
        }
    }

    /**
     * Checks if the URL has an OK response status
     *
     * @param url The URL to check
     */
    private void checkURL(String url) {
        url = TestUtility.mimicNginxRewrite(url, basePath);
        if (url.startsWith("https://raw.githubusercontent.com")) {
            // Ignore GitHub urls because they're fake
            return;
        }
        Response response = client.target(url).request().get();
        assertEquals("Not ok response: " + url, HttpStatus.SC_OK, response.getStatus());
    }
}
