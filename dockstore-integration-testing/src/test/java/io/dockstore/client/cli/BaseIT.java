/*
 *    Copyright 2017 OICR
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
import java.util.concurrent.TimeoutException;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.Constants;
import io.dockstore.common.Utilities;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dropwizard.testing.DropwizardTestSupport;
import io.swagger.client.ApiClient;
import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.dbutils.handlers.ScalarHandler;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import static io.dockstore.common.CommonTestUtilities.getTestingPostgres;

/**
 * Base integration test class
 * A default configuration that cleans the database between tests
 */
@Category(ConfidentialTest.class)
public class BaseIT {

    public static final DropwizardTestSupport<DockstoreWebserviceConfiguration> SUPPORT = new DropwizardTestSupport<>(
        DockstoreWebserviceApplication.class, CommonTestUtilities.CONFIG_PATH);

    @BeforeClass
    public static void dropAndRecreateDB() throws Exception {
        CommonTestUtilities.dropAndRecreateNoTestData(SUPPORT);
        SUPPORT.before();
    }

    @AfterClass
    public static void afterClass(){
        SUPPORT.after();
    }

    @Before
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
    }

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            System.out.println("Starting test: " + description.getMethodName());
        }
    };

    /**
     * Shared convenience method
     * @return
     * @throws IOException
     * @throws TimeoutException
     */
    protected static ApiClient getWebClient(boolean authenticated) {
        final CommonTestUtilities.TestingPostgres testingPostgres = getTestingPostgres();
        File configFile = FileUtils.getFile("src", "test", "resources", "config2");
        INIConfiguration parseConfig = Utilities.parseConfig(configFile.getAbsolutePath());
        ApiClient client = new ApiClient();
        client.setBasePath(parseConfig.getString(Constants.WEBSERVICE_BASE_PATH));
        if (authenticated) {
            client.addDefaultHeader("Authorization", "Bearer " + (testingPostgres
                .runSelectStatement("select content from token where tokensource='dockstore' and username='DockstoreTestUser2';", new ScalarHandler<>())));
        }
        return client;
    }

    protected static ApiClient getWebClient(){
        return getWebClient(true);
    }

    protected static ApiClient getWebClientOtherUser(boolean authenticated) {
        final CommonTestUtilities.TestingPostgres testingPostgres = getTestingPostgres();
        File configFile = FileUtils.getFile("src", "test", "resources", "config2");
        INIConfiguration parseConfig = Utilities.parseConfig(configFile.getAbsolutePath());
        ApiClient client = new ApiClient();
        client.setBasePath(parseConfig.getString(Constants.WEBSERVICE_BASE_PATH));
        if (authenticated) {
            client.addDefaultHeader("Authorization", "Bearer " + (testingPostgres
                    .runSelectStatement("select content from token where tokensource='dockstore' and username='OtherUser';", new ScalarHandler<>())));
        }
        return client;
    }
}
