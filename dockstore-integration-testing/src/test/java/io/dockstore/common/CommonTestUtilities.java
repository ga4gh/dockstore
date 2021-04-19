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

package io.dockstore.common;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import javax.ws.rs.core.GenericType;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dropwizard.Application;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import io.swagger.client.ApiClient;
import io.swagger.client.model.PublishRequest;
import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author xliu
 */
public final class CommonTestUtilities {

    public static final String OLD_DOCKSTORE_VERSION = "1.7.4";
    // Travis is slow, need to wait up to 1 min for webservice to return
    public static final int WAIT_TIME = 60000;
    public static final String PUBLIC_CONFIG_PATH = ResourceHelpers.resourceFilePath("dockstore.yml");
    /**
     * confidential testing config, includes keys
     */
    public static final String CONFIDENTIAL_CONFIG_PATH;
    static final String DUMMY_TOKEN_1 = "08932ab0c9ae39a880905666902f8659633ae0232e94ba9f3d2094cb928397e7";
    private static final Logger LOG = LoggerFactory.getLogger(CommonTestUtilities.class);

    static {
        String confidentialConfigPath = null;
        try {
            confidentialConfigPath = ResourceHelpers.resourceFilePath("dockstoreTest.yml");
        } catch (Exception e) {
            LOG.error("Confidential Dropwizard configuration file not found.", e);

        }
        CONFIDENTIAL_CONFIG_PATH = confidentialConfigPath;
    }

    private CommonTestUtilities() {

    }

    /**
     * Drops the database and recreates from migrations, not including any test data, using new application
     *
     * @param support reference to testing instance of the dockstore web service
     * @throws Exception
     */
    public static void dropAndRecreateNoTestData(DropwizardTestSupport<DockstoreWebserviceConfiguration> support) throws Exception {
        dropAndRecreateNoTestData(support, CONFIDENTIAL_CONFIG_PATH);
    }

    public static void dropAndRecreateNoTestData(DropwizardTestSupport<DockstoreWebserviceConfiguration> support,
        String dropwizardConfigurationFile) throws Exception {
        LOG.info("Dropping and Recreating the database with no test data");
        Application<DockstoreWebserviceConfiguration> application = support.newApplication();
        application.run("db", "drop-all", "--confirm-delete-everything", dropwizardConfigurationFile);
        application
            .run("db", "migrate", dropwizardConfigurationFile, "--include", "1.3.0.generated,1.3.1.consistency,1.4.0,1.5.0,"
                    + "1.6.0,1.7.0,1.8.0,1.9.0,1.10.0,1.11.0");
    }

    /**
     * Drops the database and recreates from migrations for non-confidential tests
     *
     * @param support reference to testing instance of the dockstore web service
     * @throws Exception
     */
    public static void dropAndCreateWithTestData(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, boolean isNewApplication)
        throws Exception {
        dropAndCreateWithTestData(support, isNewApplication, CONFIDENTIAL_CONFIG_PATH);
    }

    public static void dropAndCreateWithTestData(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, boolean isNewApplication,
        String dropwizardConfigurationFile) throws Exception {
        LOG.info("Dropping and Recreating the database with non-confidential test data");
        Application<DockstoreWebserviceConfiguration> application = getApplicationAndDropDB(support, dropwizardConfigurationFile,
                isNewApplication);

        List<String> migrationList = Arrays
            .asList("1.3.0.generated", "1.3.1.consistency", "test", "1.4.0",  "1.5.0", "test_1.5.0", "1.6.0", "1.7.0", "1.8.0", "1.9.0", "1.10.0", "1.11.0");
        runMigration(migrationList, application, dropwizardConfigurationFile);
    }

    // Adds 3 tools to the database. 2 tools are unpublished with 1 version each. 1 tool is published and has two versions (1 hidden).
    public static void dropAndCreateWithTestDataAndAdditionalTools(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, boolean isNewApplication)
            throws Exception {
        dropAndCreateWithTestDataAndAdditionalTools(support, isNewApplication, CONFIDENTIAL_CONFIG_PATH);
    }

    public static void dropAndCreateWithTestDataAndAdditionalTools(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, boolean isNewApplication,
            String dropwizardConfigurationFile) throws Exception {
        LOG.info("Dropping and Recreating the database with non-confidential test data");
        Application<DockstoreWebserviceConfiguration> application = getApplicationAndDropDB(support, dropwizardConfigurationFile,
                isNewApplication);

        List<String> migrationList = Arrays
                .asList("1.3.0.generated", "1.3.1.consistency", "test", "add_test_tools", "1.4.0",  "1.5.0", "test_1.5.0", "1.6.0", "1.7.0", "1.8.0", "1.9.0", "1.10.0", "1.11.0");
        runMigration(migrationList, application, dropwizardConfigurationFile);
    }

    /**
     * Shared convenience method
     * TODO: Somehow merge it with the method below, they are nearly identical
     * @return
     */
    public static ApiClient getWebClient(boolean authenticated, String username, TestingPostgres testingPostgres) {
        ApiClient client = new ApiClient();
        client.setBasePath(getBasePath());
        if (authenticated) {
            client.addDefaultHeader("Authorization", getDockstoreToken(testingPostgres, username));
        }
        return client;
    }

    /**
     * Shared convenience method
     * TODO: Somehow merge it with the method above, they are nearly identical
     * @return
     */
    public static io.dockstore.openapi.client.ApiClient getOpenAPIWebClient(boolean authenticated, String username, TestingPostgres testingPostgres) {
        io.dockstore.openapi.client.ApiClient client = new io.dockstore.openapi.client.ApiClient();
        client.setBasePath(getBasePath());
        if (authenticated) {
            client.addDefaultHeader("Authorization", getDockstoreToken(testingPostgres, username));
        }
        return client;
    }

    private static String getBasePath() {
        File configFile = FileUtils.getFile("src", "test", "resources", "config2");
        INIConfiguration parseConfig = Utilities.parseConfig(configFile.getAbsolutePath());
        return parseConfig.getString(Constants.WEBSERVICE_BASE_PATH);
    }

    private static String getDockstoreToken(TestingPostgres testingPostgres, String username) {
        return "Bearer " + (testingPostgres
                .runSelectStatement("select content from token where tokensource='dockstore' and username= '" + username + "';", String.class));
    }

    /**
     * Wrapper for dropping and recreating database from migrations for test confidential 1
     *
     * @param support reference to testing instance of the dockstore web service
     * @throws Exception
     */
    public static void cleanStatePrivate1(DropwizardTestSupport<DockstoreWebserviceConfiguration> support) throws Exception {
        LOG.info("Dropping and Recreating the database with confidential 1 test data");
        cleanStatePrivate1(support, CONFIDENTIAL_CONFIG_PATH);
        // TODO: it looks like gitlab's API has gone totally unresponsive, delete after recovery
        // getTestingPostgres(SUPPORT).runUpdateStatement("delete from token where tokensource = 'gitlab.com'");
    }

    /**
     * Drops and recreates database from migrations for test confidential 1
     *
     * @param support    reference to testing instance of the dockstore web service
     * @param configPath
     * @throws Exception
     */
    private static void cleanStatePrivate1(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, String configPath)
        throws Exception {
        Application<DockstoreWebserviceConfiguration> application = support.getApplication();
        application.run("db", "drop-all", "--confirm-delete-everything", configPath);

        List<String> migrationList = Arrays
            .asList("1.3.0.generated", "1.3.1.consistency", "test.confidential1", "1.4.0", "1.5.0", "test.confidential1_1.5.0", "1.6.0",
                "1.7.0", "1.8.0", "1.9.0", "1.10.0", "1.11.0");
        runMigration(migrationList, application, configPath);
    }

    public static void runMigration(List<String> migrationList, Application<DockstoreWebserviceConfiguration> application,
        String configPath) {
        migrationList.forEach(migration -> {
            try {
                application.run("db", "migrate", configPath, "--include", migration);
            } catch (Exception e) {
                Assert.fail();
            }
        });
    }

    /**
     * Wrapper fir dropping and recreating database from migrations for test confidential 2
     *
     * @param support reference to testing instance of the dockstore web service
     * @throws Exception
     */
    public static void cleanStatePrivate2(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, boolean isNewApplication)
        throws Exception {
        LOG.info("Dropping and Recreating the database with confidential 2 test data");

        cleanStatePrivate2(support, CONFIDENTIAL_CONFIG_PATH, isNewApplication);
        // TODO: You can uncomment the following line to disable GitLab tool and workflow discovery
        // getTestingPostgres(SUPPORT).runUpdateStatement("delete from token where tokensource = 'gitlab.com'");
    }

    /**
     * Drops and recreates database from migrations for test confidential 2
     *
     * @param support    reference to testing instance of the dockstore web service
     * @param configPath
     * @throws Exception
     */
    public static void cleanStatePrivate2(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, String configPath,
        boolean isNewApplication) throws Exception {
        Application<DockstoreWebserviceConfiguration> application = getApplicationAndDropDB(support, configPath, isNewApplication);

        List<String> migrationList = Arrays
            .asList("1.3.0.generated", "1.3.1.consistency", "test.confidential2", "1.4.0", "1.5.0", "test.confidential2_1.5.0", "1.6.0",

                "1.7.0", "1.8.0", "1.9.0", "1.10.0", "1.11.0");
        runMigration(migrationList, application, configPath);
    }

    // Adds 3 tools to the database. 2 tools are unpublished with 1 version each. 1 tool is published and has two versions (1 hidden).
    public static void addAdditionalToolsWithPrivate2(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, boolean isNewApplication)
            throws Exception {
        LOG.info("Dropping and Recreating the database with confidential 2 test data and additonal tools");
        addAdditionalToolsWithPrivate2(support, CONFIDENTIAL_CONFIG_PATH, isNewApplication);
    }

    public static void addAdditionalToolsWithPrivate2(DropwizardTestSupport<DockstoreWebserviceConfiguration> support, String configPath,
            boolean isNewApplication) throws Exception {
        Application<DockstoreWebserviceConfiguration> application = getApplicationAndDropDB(support, configPath, isNewApplication);

        List<String> migrationList = Arrays
                .asList("1.3.0.generated", "1.3.1.consistency", "test.confidential2", "add_test_tools", "1.4.0", "1.5.0", "test.confidential2_1.5.0", "1.6.0",

                        "1.7.0", "1.8.0", "1.9.0", "1.10.0", "1.11.0");
        runMigration(migrationList, application, configPath);
    }

    public static Application<DockstoreWebserviceConfiguration> getApplicationAndDropDB(
            final DropwizardTestSupport<DockstoreWebserviceConfiguration> support, final String configPath, final boolean isNewApplication)
            throws Exception {
        Application<DockstoreWebserviceConfiguration> application;
        if (isNewApplication) {
            application = support.newApplication();
        } else {
            application = support.getApplication();
        }
        application.run("db", "drop-all", "--confirm-delete-everything", configPath);
        return application;
    }

    /**
     * Loads up a specific set of workflows into the database
     * Specifically for tests toolsIdGet4Workflows() in GA4GHV1IT.java and toolsIdGet4Workflows() in GA4GHV2IT.java
     *
     * @param support reference to testing instance of the dockstore web service
     * @throws Exception
     */
    public static void setupSamePathsTest(DropwizardTestSupport<DockstoreWebserviceConfiguration> support) throws Exception {
        LOG.info("Migrating samepaths migrations");
        Application<DockstoreWebserviceConfiguration> application = support.newApplication();
        application.run("db", "drop-all", "--confirm-delete-everything", CONFIDENTIAL_CONFIG_PATH);
        application
            .run("db", "migrate", CONFIDENTIAL_CONFIG_PATH, "--include", "1.3.0.generated,1.3.1.consistency,1.4.0,1.5.0,1.6.0,samepaths");
        application.run("db", "migrate", CONFIDENTIAL_CONFIG_PATH, "--include", "1.7.0, 1.8.0, 1.9.0,1.10.0,1.11.0");

    }

    /**
     * Loads up a specific set of workflows into the database
     * Specifically for tests cwlrunnerWorkflowRelativePathNotEncodedAdditionalFiles in GA4GHV2IT.java
     *
     * @param support reference to testing instance of the dockstore web service
     * @throws Exception
     */
    public static void setupTestWorkflow(DropwizardTestSupport<DockstoreWebserviceConfiguration> support) throws Exception {
        LOG.info("Migrating testworkflow migrations");
        Application<DockstoreWebserviceConfiguration> application = support.getApplication();
        application.run("db", "drop-all", "--confirm-delete-everything", CONFIDENTIAL_CONFIG_PATH);
        List<String> migrationList = Arrays
                .asList("1.3.0.generated", "1.3.1.consistency", "test", "1.4.0", "testworkflow", "1.5.0", "test_1.5.0", "1.6.0", "1.7.0",
                        "1.8.0", "1.9.0", "1.10.0", "1.11.0");
        runMigration(migrationList, application, CONFIDENTIAL_CONFIG_PATH);
    }

    public static ImmutablePair<String, String> runOldDockstoreClient(File dockstore, String[] commandArray) throws RuntimeException {
        List<String> commandList = new ArrayList<>();
        commandList.add(dockstore.getAbsolutePath());
        commandList.addAll(Arrays.asList(commandArray));
        String commandString = String.join(" ", commandList);
        return Utilities.executeCommand(commandString);
    }

    /**
     * For running the old dockstore client when spaces are involved
     *
     * @param dockstore
     * @param commandArray
     * @throws RuntimeException
     */
    public static void runOldDockstoreClientWithSpaces(File dockstore, String[] commandArray) throws RuntimeException {
        List<String> commandList;
        CommandLine commandLine = new CommandLine(dockstore.getAbsoluteFile());

        commandList = Arrays.asList(commandArray);
        commandList.forEach(command -> {
            commandLine.addArgument(command, false);
        });
        Executor executor = new DefaultExecutor();
        try {
            executor.execute(commandLine);
        } catch (IOException e) {
            LOG.error("Could not execute command. " + e.getMessage(), e);
            e.printStackTrace();
        }
    }

    public static void checkToolList(String log) {
        Assert.assertTrue(log.contains("NAME"));
        Assert.assertTrue(log.contains("DESCRIPTION"));
        Assert.assertTrue(log.contains("Git Repo"));
    }

    public static void restartElasticsearch() throws Exception {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();

        try (DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).build(); DockerClient instance = DockerClientImpl.getInstance(config, httpClient)) {
            List<Container> exec = instance.listContainersCmd().exec();
            Optional<Container> elasticsearch = exec.stream().filter(container -> container.getImage().contains("elasticsearch"))
                    .findFirst();
            if (elasticsearch.isPresent()) {
                Container container = elasticsearch.get();
                try {
                    instance.restartContainerCmd(container.getId());
                    // Wait 25 seconds for elasticsearch to become ready
                    // TODO: Replace with better wait
                    Thread.sleep(25000);
                } catch (Exception e) {
                    System.err.println("Problems restarting Docker container");
                }
            }

        }
    }

    // These two functions are duplicated from SwaggerUtility in dockstore-client to prevent importing dockstore-client
    // This cannot be moved to dockstore-common because PublishRequest requires built dockstore-webservice

    /**
     * @param bool
     * @return
     */
    public static PublishRequest createPublishRequest(Boolean bool) {
        PublishRequest publishRequest = new PublishRequest();
        publishRequest.setPublish(bool);
        return publishRequest;
    }

    public static <T> T getArbitraryURL(String url, GenericType<T> type, ApiClient client) {
        return client
                .invokeAPI(url, "GET", new ArrayList<>(), null, new HashMap<>(), new HashMap<>(), "application/zip", "application/zip",
                        new String[] { "BEARER" }, type).getData();
    }
}
