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
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Gauge;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.Constants;
import io.dockstore.common.TestingPostgres;
import io.dockstore.common.Utilities;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dropwizard.testing.DropwizardTestSupport;
import io.swagger.client.ApiClient;
import io.swagger.client.auth.ApiKeyAuth;
import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * Base integration test class
 * A default configuration that cleans the database between tests
 */
@Category(ConfidentialTest.class)
public class BaseIT {

    // This is obviously an admin
    public static final String ADMIN_USERNAME = "admin@admin.com";
    // This is also an admin
    public static final String USER_1_USERNAME = "DockstoreTestUser";
    // This is also an admin
    public static final String USER_2_USERNAME = "DockstoreTestUser2";
    public static final DropwizardTestSupport<DockstoreWebserviceConfiguration> SUPPORT = new DropwizardTestSupport<>(
        DockstoreWebserviceApplication.class, CommonTestUtilities.CONFIDENTIAL_CONFIG_PATH);
    protected static TestingPostgres testingPostgres;
    // This is not an admin
    static final String OTHER_USERNAME = "OtherUser";

    @Rule
    public final TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            System.out.println("Starting test: " + description.getMethodName());
        }
    };
    final String curatorUsername = "curator@curator.com";

    @BeforeClass
    public static void dropAndRecreateDB() throws Exception {
        CommonTestUtilities.dropAndRecreateNoTestData(SUPPORT);
        SUPPORT.before();
        testingPostgres = new TestingPostgres(SUPPORT);
    }

    public static void assertNoMetricsLeaks(DropwizardTestSupport<DockstoreWebserviceConfiguration> support) throws InterruptedException {
        SortedMap<String, Gauge> gauges = support.getEnvironment().metrics().getGauges();
        int active = (int)gauges.get("io.dropwizard.db.ManagedPooledDataSource.hibernate.active").getValue();
        int waiting = (int)gauges.get("io.dropwizard.db.ManagedPooledDataSource.hibernate.waiting").getValue();
        if (active != 0 || waiting != 0) {
            // Waiting 10 seconds to see if active connection disappears
            TimeUnit.SECONDS.sleep(10);
            active = (int)gauges.get("io.dropwizard.db.ManagedPooledDataSource.hibernate.active").getValue();
            waiting = (int)gauges.get("io.dropwizard.db.ManagedPooledDataSource.hibernate.waiting").getValue();
            Assert.assertEquals("There should be no active connections", 0, active);
            Assert.assertEquals("There should be no waiting connections", 0, waiting);
        }
    }

    @AfterClass
    public static void afterClass() {
        SUPPORT.getEnvironment().healthChecks().shutdown();
        SUPPORT.after();
    }

    protected static io.dockstore.openapi.client.ApiClient getOpenAPIWebClient(String username, TestingPostgres testingPostgresParameter) {
        return CommonTestUtilities.getOpenAPIWebClient(true, username, testingPostgresParameter);
    }

    /**
     * the following were migrated from SwaggerClientIT and can be eventually merged. Note different config file used
     */
    protected static ApiClient getWebClient(String username, TestingPostgres testingPostgresParameter) {
        return CommonTestUtilities.getWebClient(true, username, testingPostgresParameter);
    }

    protected static ApiClient getWebClient() {
        return getWebClient(true, false);
    }

    protected static ApiClient getWebClient(boolean correctUser, boolean admin) {
        File configFile = FileUtils.getFile("src", "test", "resources", "config");
        INIConfiguration parseConfig = Utilities.parseConfig(configFile.getAbsolutePath());
        ApiClient client = new ApiClient();
        ApiKeyAuth bearer = (ApiKeyAuth)client.getAuthentication("BEARER");
        bearer.setApiKeyPrefix("BEARER");
        bearer.setApiKey((correctUser ? parseConfig.getString(admin ? Constants.WEBSERVICE_TOKEN_USER_1 : Constants.WEBSERVICE_TOKEN_USER_2)
            : "foobar"));
        client.setBasePath(parseConfig.getString(Constants.WEBSERVICE_BASE_PATH));
        return client;
    }

    static ApiClient getAdminWebClient() {
        return getWebClient(true, true);
    }

    protected static ApiClient getAnonymousWebClient() {
        File configFile = FileUtils.getFile("src", "test", "resources", "config");
        INIConfiguration parseConfig = Utilities.parseConfig(configFile.getAbsolutePath());
        ApiClient client = new ApiClient();
        client.setBasePath(parseConfig.getString(Constants.WEBSERVICE_BASE_PATH));
        return client;
    }

    protected static io.dockstore.openapi.client.ApiClient getAnonymousOpenAPIWebClient() {
        File configFile = FileUtils.getFile("src", "test", "resources", "config");
        INIConfiguration parseConfig = Utilities.parseConfig(configFile.getAbsolutePath());
        io.dockstore.openapi.client.ApiClient client = new io.dockstore.openapi.client.ApiClient();
        client.setBasePath(parseConfig.getString(Constants.WEBSERVICE_BASE_PATH));
        return client;
    }

    @After
    public void after() throws InterruptedException {
        assertNoMetricsLeaks(SUPPORT);
    }

    @Before
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
    }
}
