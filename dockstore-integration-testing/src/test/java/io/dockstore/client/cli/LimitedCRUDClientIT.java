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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.google.common.collect.Lists;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.Registry;
import io.dockstore.common.TestingPostgres;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.HostedApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.Limits;
import io.swagger.client.model.SourceFile;
import io.swagger.client.model.User;
import io.swagger.client.model.Workflow;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpStatus;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import static io.dockstore.common.DescriptorLanguage.CWL;
import static io.dockstore.common.DescriptorLanguage.WDL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests CRUD style operations for tools and workflows hosted directly on Dockstore
 *
 * @author dyuen, agduncan
 */
@Category(ConfidentialTest.class)
public class LimitedCRUDClientIT {
    public static final DropwizardTestSupport<DockstoreWebserviceConfiguration> SUPPORT = new DropwizardTestSupport<>(
        DockstoreWebserviceApplication.class, CommonTestUtilities.PUBLIC_CONFIG_PATH);
    /**
     * Used to set user based hosted entry count and hosted entry version limits -- higher than the system limits for those values.
     */
    public static final int NEW_LIMITS = 20;
    /**
     * The system limit for hosted entry count and hosted entry version.
     * Specified in dockstore-integration-testing/src/test/resources/dockstore.yml
     */
    public static final int SYSTEM_LIMIT = 10;
    private static TestingPostgres testingPostgres;
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    //TODO: duplicates BaseIT but with a different config file, attempt to simplify after release
    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            System.out.println("Starting test: " + description.getMethodName());
        }
    };

    @BeforeClass
    public static void dropAndRecreateDB() throws Exception {
        CommonTestUtilities.dropAndRecreateNoTestData(SUPPORT);
        SUPPORT.before();
        testingPostgres = new TestingPostgres(SUPPORT);
    }

    @AfterClass
    public static void afterClass() {
        SUPPORT.getEnvironment().healthChecks().shutdown();
        SUPPORT.after();
    }

    @Before
    public void resetDBAndAdminUserLimitsBetweenTests() throws Exception {
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);

        // Tests can run in any order, and the CachingAuthenticator is not cleared between tests
        // Reset limits for user between tests so it's not set when it's not supposed to be.
        ApiClient webClient = BaseIT.getWebClient(BaseIT.ADMIN_USERNAME, testingPostgres);
        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();
        usersApi.setUserLimits(user.getId(), new Limits());
    }

    @Test
    public void testToolCreation() {
        ApiClient webClient = BaseIT.getWebClient(BaseIT.ADMIN_USERNAME, testingPostgres);
        HostedApi api = new HostedApi(webClient);
        DockstoreTool hostedTool = api
            .createHostedTool("awesomeTool", Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.getShortName(), "coolNamespace", null);
        assertNotNull("tool was not created properly", hostedTool);
        // createHostedTool() endpoint is safe to have user profiles because that profile is your own
        assertEquals("One user should belong to this tool, yourself", 1, hostedTool.getUsers().size());
        hostedTool.getUsers().forEach(user -> {
            assertNotNull("createHostedTool() endpoint should have user profiles", user.getUserProfiles());
        });
        // Setting it to null afterwards to compare with the getContainer endpoint since that one doesn't return user profiles
        hostedTool.setUsers(null);

        assertTrue("tool was not created with a valid id", hostedTool.getId() != 0);
        // can get it back with regular api
        ContainersApi oldApi = new ContainersApi(webClient);
        DockstoreTool container = oldApi.getContainer(hostedTool.getId(), null);
        // clear lazy fields for now till merge
        hostedTool.setAliases(null);
        container.setAliases(null);
        assertEquals(container, hostedTool);
        assertNull(container.getUsers());

        // test repeated workflow creation up to limit
        for (int i = 1; i < SYSTEM_LIMIT; i++) {
            api.createHostedTool("awesomeTool" + i, Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.getShortName(), "coolNamespace",
                null);
        }

        thrown.expect(ApiException.class);
        api.createHostedTool("awesomeTool" + 10, Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.getShortName(), "coolNamespace", null);
    }

    @Test
    public void testOverrideEntryLimit() {
        ApiClient webClient = BaseIT.getWebClient(BaseIT.ADMIN_USERNAME, testingPostgres);
        HostedApi api = new HostedApi(webClient);

        // Change limits for current user
        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();
        Limits limits = new Limits();
        limits.setHostedEntryCountLimit(NEW_LIMITS);
        usersApi.setUserLimits(user.getId(), limits);
        DockstoreTool hostedTool = api
            .createHostedTool("awesomeTool", Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.getShortName(), "coolNamespace", null);
        assertNotNull("tool was not created properly", hostedTool);
        // createHostedTool() endpoint is safe to have user profiles because that profile is your own
        assertEquals("One user should belong to this tool, yourself", 1, hostedTool.getUsers().size());

        // test repeated workflow creation up to limit
        for (int i = 1; i <= NEW_LIMITS - 1; i++) {
            api.createHostedTool("awesomeTool" + i, Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.getShortName(), "coolNamespace",
                null);
        }

        thrown.expect(ApiException.class);
        api.createHostedTool("awesomeTool" + NEW_LIMITS, Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.getShortName(),
            "coolNamespace", null);
    }

    @Test
    public void testToolVersionCreation() throws IOException {
        ApiClient webClient = BaseIT.getWebClient(BaseIT.ADMIN_USERNAME, testingPostgres);
        HostedApi api = new HostedApi(webClient);
        DockstoreTool hostedTool = api
            .createHostedTool("awesomeTool", Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.getShortName(), "coolNamespace", null);

        List<SourceFile> sourceFiles = generateSourceFiles(CWL);

        api.editHostedTool(hostedTool.getId(), sourceFiles);

        // test repeated workflow version creation up to limit
        for (int i = 1; i < SYSTEM_LIMIT; i++) {
            sourceFiles.get(0).setContent(sourceFiles.get(0).getContent() + "\ns:citation: " + UUID.randomUUID().toString());
            api.editHostedTool(hostedTool.getId(), sourceFiles);
        }

        thrown.expect(ApiException.class);
        api.editHostedTool(hostedTool.getId(), sourceFiles);
    }

    @Test
    public void testGettingDescriptorType() throws IOException {
        ApiClient webClient = BaseIT.getWebClient(BaseIT.ADMIN_USERNAME, testingPostgres);
        HostedApi api = new HostedApi(webClient);
        DockstoreTool hostedTool = api
                .createHostedTool("awesomeTool", Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.toString(), "coolNamespace", null);

        List<SourceFile> sourceFiles = generateSourceFiles(CWL);

        hostedTool = api.editHostedTool(hostedTool.getId(), sourceFiles);
        assertEquals(CWL.toString(), hostedTool.getDescriptorType().get(0));

        sourceFiles = generateSourceFiles(WDL);
        hostedTool = api.editHostedTool(hostedTool.getId(), sourceFiles);
        assertTrue(hostedTool.getDescriptorType().size() == 2);
        assertTrue(hostedTool.getDescriptorType().get(0) != hostedTool.getDescriptorType().get(1));
    }

    @Test
    public void testOverrideVersionLimit() throws IOException {
        ApiClient webClient = BaseIT.getWebClient(BaseIT.ADMIN_USERNAME, testingPostgres);

        // Change limits for current user
        UsersApi usersApi = new UsersApi(webClient);
        User user = usersApi.getUser();
        Limits limits = new Limits();
        limits.setHostedEntryVersionLimit(NEW_LIMITS);
        usersApi.setUserLimits(user.getId(), limits);

        HostedApi api = new HostedApi(webClient);
        DockstoreTool hostedTool = api
            .createHostedTool("awesomeTool", Registry.QUAY_IO.getDockerPath().toLowerCase(), CWL.getShortName(), "coolNamespace", null);

        List<SourceFile> sourceFiles = generateSourceFiles(CWL);
        api.editHostedTool(hostedTool.getId(), sourceFiles);

        // a few updates with no actual changes shouldn't break anything since they are ignored
        for (int i = 1; i <= NEW_LIMITS - 1; i++) {
            api.editHostedTool(hostedTool.getId(), sourceFiles);
        }

        // test repeated workflow version creation up to limit
        for (int i = 1; i <= NEW_LIMITS - 1; i++) {
            sourceFiles.get(0).setContent(sourceFiles.get(0).getContent() + "\ns:citation: " + UUID.randomUUID().toString());
            api.editHostedTool(hostedTool.getId(), sourceFiles);
        }

        thrown.expect(ApiException.class);
        api.editHostedTool(hostedTool.getId(), sourceFiles);
    }

    @Test
    public void testUploadZipHonorsVersionLimit() {
        ApiClient webClient = BaseIT.getWebClient(BaseIT.ADMIN_USERNAME, testingPostgres);
        final HostedApi hostedApi = new HostedApi(webClient);
        final Workflow hostedWorkflow = hostedApi.createHostedWorkflow("hosted", "something", "wdl", "something", null);
        // Created workflow, no versions
        File smartSeqFile = new File(ResourceHelpers.resourceFilePath("smartseq.zip"));
        for (int i = 0; i < SYSTEM_LIMIT; i++) {
            hostedApi.addZip(hostedWorkflow.getId(), smartSeqFile);
        }
        thrown.expect(ApiException.class);
        hostedApi.addZip(hostedWorkflow.getId(), smartSeqFile);

    }

    private List<SourceFile> generateSourceFiles(DescriptorLanguage descriptorLanguage) throws IOException {
        String resourceFilePath;
        String dockstorePath;
        SourceFile.TypeEnum type;
        if (descriptorLanguage == CWL) {
            resourceFilePath = "tar-param.cwl";
            dockstorePath = "/Dockstore.cwl";
            type = SourceFile.TypeEnum.DOCKSTORE_CWL;
        } else if (descriptorLanguage == WDL) {
            resourceFilePath = "hello.wdl";
            dockstorePath = "/Dockstore.wdl";
            type = SourceFile.TypeEnum.DOCKSTORE_WDL;
        } else {
            throw new CustomWebApplicationException("Only WDL and CWL are an option", HttpStatus.SC_BAD_REQUEST);
        }
        SourceFile descriptorFile = new SourceFile();
        descriptorFile
            .setContent(FileUtils.readFileToString(new File(ResourceHelpers.resourceFilePath(resourceFilePath)), StandardCharsets.UTF_8));
        descriptorFile.setType(type);
        descriptorFile.setPath(dockstorePath);
        descriptorFile.setAbsolutePath(dockstorePath);
        SourceFile dockerfile = new SourceFile();
        dockerfile.setContent("FROM ubuntu:latest");
        dockerfile.setType(SourceFile.TypeEnum.DOCKERFILE);
        dockerfile.setPath("/Dockerfile");
        dockerfile.setAbsolutePath("/Dockerfile");
        return Lists.newArrayList(descriptorFile, dockerfile);

    }
}
