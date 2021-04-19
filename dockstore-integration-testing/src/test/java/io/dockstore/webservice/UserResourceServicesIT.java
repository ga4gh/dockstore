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

package io.dockstore.webservice;

import io.dockstore.client.cli.BaseIT;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.NonConfidentialTest;
import io.dockstore.common.TestingPostgres;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.TokenType;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.DropwizardTestSupport;
import io.specto.hoverfly.junit.rule.HoverflyRule;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;

import static io.dockstore.common.Hoverfly.SERVICES_SIMULATION_SOURCE;
import static io.dockstore.common.Hoverfly.SUFFIX1;
import static io.dockstore.common.Hoverfly.SUFFIX2;

/**
 * Tests services endpoints from UserResource
 * <p>
 * Separated from UserResourceIT because this one is not confidential.
 * Not confidential because mocking calls to GitHub API.
 * Mocking calls to GitHub API because the API requires GitHub app to be installed,
 * and there's a fair amount of overhead: creating a new app (instead of using
 * staging or production app) private key file,
 * installation id, installing it for one org, installing it for a repo but not an org.
 * <p>
 * That said, we probably should have non-mocked tests as well.
 */
@Category(NonConfidentialTest.class)
public class UserResourceServicesIT {
    @ClassRule
    public static final HoverflyRule HOVERFLY_RULE = HoverflyRule.inSimulationMode(SERVICES_SIMULATION_SOURCE);
    private static final long GITHUB_USER1_ID = 1L;
    private static final long GITHUB_USER2_ID = 2L;
    // These are not from Hoverfly, it's actually in the starting database
    private static final String GITHUB_ACCOUNT_USERNAME_1 = "tuber";
    private static final String GITHUB_ACCOUNT_USERNAME_2 = "potato";
    // This should match GITHUB_APP_ID somewhere
    private static final String GITHUB_APP_ID = "11111";
    private static final String DROPWIZARD_CONFIGURATION_FILE_PATH = CommonTestUtilities.PUBLIC_CONFIG_PATH;
    public static final DropwizardTestSupport<DockstoreWebserviceConfiguration> SUPPORT = new DropwizardTestSupport<>(
        DockstoreWebserviceApplication.class, DROPWIZARD_CONFIGURATION_FILE_PATH, ConfigOverride.config("gitHubAppId", GITHUB_APP_ID),
        ConfigOverride.config("gitHubAppPrivateKeyFile", "./src/test/resources/integrationtest.pem"));
    private static TestingPostgres testingPostgres;
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    public static void dropAndRecreateDB() throws Exception {
        CommonTestUtilities.dropAndRecreateNoTestData(SUPPORT, DROPWIZARD_CONFIGURATION_FILE_PATH);
        SUPPORT.before();
        testingPostgres = new TestingPostgres(SUPPORT);
    }

    @AfterClass
    public static void afterClass() {
        SUPPORT.getEnvironment().healthChecks().shutdown();
        SUPPORT.after();
    }

    @After
    public void after() throws InterruptedException {
        BaseIT.assertNoMetricsLeaks(SUPPORT);
    }

    @Before
    public void setup() throws Exception {
        CommonTestUtilities.dropAndCreateWithTestData(SUPPORT, false, DROPWIZARD_CONFIGURATION_FILE_PATH);
        DockstoreWebserviceApplication application = SUPPORT.getApplication();
        SessionFactory sessionFactory = application.getHibernate().getSessionFactory();
        TokenDAO tokenDAO = new TokenDAO(sessionFactory);
        // non-confidential test database sequences seem messed up and need to be iterated past, but other tests may depend on ids
        testingPostgres.runUpdateStatement("alter sequence enduser_id_seq increment by 50 restart with 100");
        testingPostgres.runUpdateStatement("alter sequence token_id_seq increment by 50 restart with 100");
        // used to allow us to use tokenDAO outside of the web service
        Session session = application.getHibernate().getSessionFactory().openSession();
        ManagedSessionContext.bind(session);
        final Transaction transaction = session.beginTransaction();
        tokenDAO.create(createToken(SUFFIX1, GITHUB_ACCOUNT_USERNAME_1, GITHUB_USER1_ID));
        tokenDAO.create(createToken(SUFFIX2, GITHUB_ACCOUNT_USERNAME_2, GITHUB_USER2_ID));
        transaction.commit();
        session.close();
    }

    private Token createToken(String token, String username, long id) {
        final Token fakeGithubToken = new Token();
        fakeGithubToken.setTokenSource(TokenType.GITHUB_COM);
        fakeGithubToken.setContent(token);
        fakeGithubToken.setUsername(username);
        fakeGithubToken.setUserId(id);
        return fakeGithubToken;
    }
}
