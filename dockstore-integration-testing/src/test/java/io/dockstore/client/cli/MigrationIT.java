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

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.TestingPostgres;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * Testing migration
 *
 * @author dyuen
 */
public class MigrationIT {

    public static final DropwizardTestSupport<DockstoreWebserviceConfiguration> SUPPORT = new DropwizardTestSupport<>(
        DockstoreWebserviceApplication.class, CommonTestUtilities.CONFIDENTIAL_CONFIG_PATH);
    protected static TestingPostgres testingPostgres;
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            System.out.println("Starting test: " + description.getMethodName());
        }
    };

    @BeforeClass
    public static void dumpDBAndCreateSchema() throws Exception {
        CommonTestUtilities.dropAndRecreateNoTestData(SUPPORT);
        SUPPORT.before();
        testingPostgres = new TestingPostgres(SUPPORT);
    }

    @AfterClass
    public static void afterClass() {
        SUPPORT.getEnvironment().healthChecks().shutdown();
        SUPPORT.after();
    }

    /**
     * This test ensures that our testing databases remain compatible with the migrations.xml we provide
     * In other words, running migration again should be ok
     */
    @Test
    public void testDB1WithNormalDatabase() throws Exception {
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false);
        SUPPORT.getApplication().run("db", "migrate", ResourceHelpers.resourceFilePath("dockstoreTest.yml"), "--include", "test");
    }

    @Test
    public void testDB1WithStandardMigration() throws Exception {
        CommonTestUtilities.cleanStatePrivate1(SUPPORT);
        SUPPORT.getApplication()
            .run("db", "migrate", ResourceHelpers.resourceFilePath("dockstoreTest.yml"), "--include", "test.confidential1");
    }

    @Test
    public void testDB2WithStandardMigration() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        SUPPORT.getApplication()
            .run("db", "migrate", ResourceHelpers.resourceFilePath("dockstoreTest.yml"), "--include", "test.confidential2");
    }

    /**
     * This test ensures that an actual new database change (like adding a new column) works properly
     *
     * @throws Exception
     */
    @Test
    public void testDB1WithFunkyMigration() throws Exception {
        CommonTestUtilities.cleanStatePrivate1(SUPPORT);
        checkOnMigration();
    }

    private void checkOnMigration() throws Exception {

        SUPPORT.getApplication().run("db", "migrate", ResourceHelpers.resourceFilePath("dockstoreTest.yml"), "--migrations",
            ResourceHelpers.resourceFilePath("funky_migrations.xml"));
        // check that column was added
        final long count = testingPostgres.runSelectStatement("select count(funkfile) from tool", long.class);
        // count will be zero, but there should be no exception
        Assert.assertEquals("could select from new column", 0, count);
        final long orphanedTokensCount = testingPostgres
            .runSelectStatement("select count(*) from token where userid not in (select id from enduser)", long.class);
        Assert.assertEquals(0, orphanedTokensCount);
        // reset state
        testingPostgres.runUpdateStatement("alter table tool drop funkfile");
    }

    @Test
    public void testDB2WithFunkyMigration() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
        checkOnMigration();
    }
}
