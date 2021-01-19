package io.dockstore.client.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.openapi.client.api.EventsApi;
import io.dockstore.webservice.core.OrganizationUser;
import io.dockstore.webservice.jdbi.EventDAO;
import io.dockstore.webservice.resources.EventSearchType;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.EntriesApi;
import io.swagger.client.api.OrganizationsApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.model.Collection;
import io.swagger.client.model.CollectionOrganization;
import io.swagger.client.model.Event;
import io.swagger.client.model.Organization;
import io.swagger.client.model.Organization.StatusEnum;
import io.swagger.client.model.PublishRequest;
import io.swagger.client.model.StarRequest;
import io.swagger.client.model.User;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@Category(ConfidentialTest.class)
public class OrganizationIT extends BaseIT {
    private static final StarRequest STAR_REQUEST = SwaggerUtility.createStarRequest(true);
    private static final StarRequest UNSTAR_REQUEST = SwaggerUtility.createStarRequest(false);
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    @Rule
    public final ExpectedException thrown = ExpectedException.none();
    // All numbers, too short, bad pattern, too long, foreign characters
    private final List<String> badNames = Arrays.asList("1234", "ab", "1aab", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "我喜欢狗");
    // Doesn't have extension, has query parameter at the end, extension is not jpg, jpeg, png, or gif.
    private final List<String> badAvatarUrls = Arrays
        .asList("https://via.placeholder.com/150", "https://media.giphy.com/media/3o7bu4EJkrXG9Bvs9G/giphy.svg",
            "https://i2.wp.com/upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Patates.jpg/2560px-Patates.jpg?ssl=1", ".png",
            "https://via.placeholder.com/150.jpg asdf", "ad .jpg");
    private final List<String> goodDisplayNames = Arrays
        .asList("test-name", "test name", "test,name", "test_name", "test(name)", "test'name", "test&name");
    private final List<String> badDisplayNames = Arrays
        .asList("test@hello", "aa", "我喜欢狗", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab", "%+%");


    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate2(SUPPORT, false);
    }

    /**
     * Creates a stub Organization object
     *
     * @return Organization object
     */
    private Organization stubOrgObject() {
        String markdownDescription = "An h1 header ============ Paragraphs are separated by a blank line. 2nd paragraph. *Italic*, **bold**, and `monospace`. Itemized lists look like: * this one * that one * the other one Note that --- not considering the asterisk --- the actual text content starts at 4-columns in. > Block quotes are > written like so. > > They can span multiple paragraphs, > if you like. Use 3 dashes for an em-dash. Use 2 dashes for ranges (ex., \"it's all in chapters 12--14\"). Three dots ... will be converted to an ellipsis. Unicode is supported. ☺ ";
        Organization organization = new Organization();
        organization.setName("testname");
        organization.setDisplayName("test name");
        organization.setLocation("testlocation");
        organization.setLink("https://www.google.com");
        organization.setEmail("test@email.com");
        organization.setDescription(markdownDescription);
        organization.setTopic("This is a short topic");
        organization.setAvatarUrl("https://www.lifehardin.net/images/employees/default-logo.png");
        return organization;
    }

    /**
     * Creates an openAPI version of a stub Organization object
     *
     * @return openAPI Organization object
     */
    private io.dockstore.openapi.client.model.Organization openApiStubOrgObject() {
        String markdownDescription = "An h1 header ============ Paragraphs are separated by a blank line. 2nd paragraph. *Italic*, **bold**, and `monospace`. Itemized lists look like: * this one * that one * the other one Note that --- not considering the asterisk --- the actual text content starts at 4-columns in. > Block quotes are > written like so. > > They can span multiple paragraphs, > if you like. Use 3 dashes for an em-dash. Use 2 dashes for ranges (ex., \"it's all in chapters 12--14\"). Three dots ... will be converted to an ellipsis. Unicode is supported. ☺ ";
        io.dockstore.openapi.client.model.Organization organization = new io.dockstore.openapi.client.model.Organization();
        organization.setName("testname");
        organization.setDisplayName("test name");
        organization.setLocation("testlocation");
        organization.setLink("https://www.google.com");
        organization.setEmail("test@email.com");
        organization.setDescription(markdownDescription);
        organization.setTopic("This is a short topic");
        organization.setAvatarUrl("https://www.lifehardin.net/images/employees/default-logo.png");
        return organization;
    }

    /**
     * Creates a stub collection object
     *
     * @return Collection object
     */
    private Collection stubCollectionObject() {
        Collection collection = new Collection();
        collection.setName("Alignment");
        collection.setDisplayName("Alignment Algorithms");
        collection.setDescription("A collection of alignment algorithms");
        return collection;
    }

    /**
     * Creates and registers an Organization
     *
     * @param organizationsApi
     * @return Newly registered Organization
     */
    private Organization createOrg(OrganizationsApi organizationsApi) {
        Organization organization = stubOrgObject();
        return organizationsApi.createOrganization(organization);
    }

    /**
     * Tests that a user can create an Organization and it will not be approved right away.
     * The user should be able to view and update the Organization before and after approval.
     * However, the user is not able to update the Organization name/display name after organization approval
     * Also tests who the Organization should be visible to based on approval.
     * Also tests admin being able to approve an org and admin/curators being able to see the Organization
     * A curator/admin can still update the organization's name/display name
     */
    @Test
    @SuppressWarnings("checkstyle:MethodLength")
    public void testCreateNewOrganization() {
        // Set the user that's creating the organization to not be an admin
        testingPostgres.runUpdateStatement("update enduser set isadmin ='f' where username = 'DockstoreTestUser2'");
        // Setup postgres

        // Setup user two. admin: false, curator false
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Setup user one
        final ApiClient webClientUser1 = getWebClient(USER_1_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiUser1 = new OrganizationsApi(webClientUser1);

        // Setup admin. admin: true, curator: false
        final ApiClient webClientAdminUser = getWebClient(ADMIN_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiAdmin = new OrganizationsApi(webClientAdminUser);

        // Setup curator. admin: false, curator: true
        final ApiClient webClientCuratorUser = getWebClient(curatorUsername, testingPostgres);
        OrganizationsApi organizationsApiCurator = new OrganizationsApi(webClientCuratorUser);

        // Setup unauthorized user
        final ApiClient unauthClient = CommonTestUtilities.getWebClient(false, "", testingPostgres);
        OrganizationsApi organizationsApiUnauth = new OrganizationsApi(unauthClient);

        // Create the organization
        Organization registeredOrganization = createOrg(organizationsApiUser2);
        assertNotEquals(registeredOrganization.getStatus().getValue(), StatusEnum.APPROVED);

        // There should be one CREATE_ORG event
        final long count = testingPostgres.runSelectStatement("select count(*) from event where type = 'CREATE_ORG'", long.class);
        assertEquals("There should be 1 event of type CREATE_ORG, there are " + count, 1, count);

        // Should not appear in approved list
        List<Organization> organizationList = organizationsApiUser2.getApprovedOrganizations();
        assertEquals("Should have no approved Organizations.", organizationList.size(), 0);

        // User should be able to get by id
        Organization organization = organizationsApiUser2.getOrganizationById(registeredOrganization.getId());
        assertNotNull("organization should be returned.", organization);

        // Should be in PENDING state
        assertEquals(organization.getStatus(), StatusEnum.PENDING);

        // Admin should be able to see by id
        organization = organizationsApiAdmin.getOrganizationById(registeredOrganization.getId());
        assertNotNull("organization should be returned.", organization);

        // Other user should not be able to see by id
        try {
            organization = organizationsApiUser1.getOrganizationById(registeredOrganization.getId());
        } catch (ApiException ex) {
            organization = null;
        }
        assertNull("organization should NOT be returned.", organization);

        // Curator should be able to see by id
        organization = organizationsApiCurator.getOrganizationById(registeredOrganization.getId());
        assertNotNull("organization should be returned.", organization);

        // User should be able to get by name
        organization = organizationsApiUser2.getOrganizationByName(registeredOrganization.getName());
        assertNotNull("organization should be returned.", organization);

        // Admin should be able to see by name
        organization = organizationsApiAdmin.getOrganizationByName(registeredOrganization.getName());
        assertNotNull("organization should be returned.", organization);

        // Other user should not be able to see by name
        boolean failedUser1GetByName = false;
        try {
            organization = organizationsApiUser1.getOrganizationByName(registeredOrganization.getName());
        } catch (ApiException ex) {
            failedUser1GetByName = true;
        }
        assertTrue("organization should NOT be returned.", failedUser1GetByName);

        // Unauth user should not be able to see by name
        boolean failedUnauthGetByName = false;
        try {
            organization = organizationsApiUnauth.getOrganizationByName(registeredOrganization.getName());
        } catch (ApiException ex) {
            failedUnauthGetByName = true;
        }
        assertTrue("organization should NOT be returned.", failedUnauthGetByName);

        // Curator should be able to see by name
        organization = organizationsApiCurator.getOrganizationByName(registeredOrganization.getName());
        assertNotNull("organization should be returned.", organization);

        // Update the organization
        String email = "another@email.com";
        Organization newOrganization = stubOrgObject();
        newOrganization.setEmail(email);
        organization = organizationsApiUser2.updateOrganization(newOrganization, organization.getId());

        // There should be one MODIFY_ORG event
        final long count2 = testingPostgres.runSelectStatement("select count(*) from event where type = 'MODIFY_ORG'", long.class);
        assertEquals("There should be 1 event of type MODIFY_ORG, there are " + count2, 1, count2);

        // organization should have new information
        organization = organizationsApiUser2.getOrganizationById(registeredOrganization.getId());
        assertEquals("organization should be returned and have an updated email.", email, organization.getEmail());

        // Should not be able to request re-review
        boolean canRequestReview = true;
        try {
            organizationsApiUser2.requestOrganizationReview(registeredOrganization.getId());
        } catch (ApiException ex) {
            canRequestReview = false;
        } finally {
            assertFalse("Can request re-review, but should not be able to", canRequestReview);
        }

        // Admin approve it
        organizationsApiAdmin.approveOrganization(registeredOrganization.getId());

        // There should be one APPROVE_ORG event
        final long count3 = testingPostgres.runSelectStatement("select count(*) from event where type = 'APPROVE_ORG'", long.class);
        assertEquals("There should be 1 event of type APPROVE_ORG, there are " + count3, 1, count3);

        try {
            organization.setName("NameSquatting");
            organization.setDisplayName("DisplayNameSquatting");
            organizationsApiUser2.updateOrganization(organization, organization.getId());
            fail("Only admin and curators are able to change an approved Organization's name or display name");
        } catch (ApiException e) {
            assertEquals("Only admin and curators are able to change an approved Organization's name or display name. Contact Dockstore to have it changed.", e.getMessage());
            assertEquals(HttpStatus.SC_UNAUTHORIZED, e.getCode());
        }

        // Should be in APPROVED state
        registeredOrganization = organizationsApiUser2.getOrganizationById(registeredOrganization.getId());
        assertEquals(registeredOrganization.getStatus(), StatusEnum.APPROVED);

        // Should now appear in approved list
        organizationList = organizationsApiUser2.getApprovedOrganizations();
        assertEquals("Should have one approved Organizations.", organizationList.size(), 1);
        organizationList.forEach(approvedOrganization -> assertTrue(approvedOrganization.getAliases().isEmpty()));

        // Should not be able to request re-review
        canRequestReview = true;
        try {
            organizationsApiUser2.requestOrganizationReview(registeredOrganization.getId());
        } catch (ApiException ex) {
            canRequestReview = false;
        } finally {
            assertFalse("Can request re-review, but should not be able to", canRequestReview);
        }

        // User should be able to get by id
        organization = organizationsApiUser2.getOrganizationById(registeredOrganization.getId());
        assertNotNull("organization should be returned.", organization);

        // Other user should also be able to get by id
        organization = organizationsApiUser1.getOrganizationById(registeredOrganization.getId());
        assertNotNull("organization should be returned.", organization);

        // Admin should also be able to get by id
        organization = organizationsApiAdmin.getOrganizationById(registeredOrganization.getId());
        assertNotNull("organization should be returned.", organization);

        // Curator should be able to see by id
        organization = organizationsApiCurator.getOrganizationById(registeredOrganization.getId());
        assertNotNull("organization should be returned.", organization);

        // Unauth user should be able to get by id
        organization = organizationsApiUnauth.getOrganizationById(registeredOrganization.getId());
        assertNotNull("organization should be returned.", organization);

        // Unauth user should be able to get by name
        organization = organizationsApiUnauth.getOrganizationByName(registeredOrganization.getName());
        assertNotNull("organization should be returned.", organization);

        // Update the organization
        String link = "http://www.anothersite.com";
        newOrganization.setLink(link);
        organization = organizationsApiUser2.updateOrganization(newOrganization, organization.getId());

        // There should be two MODIFY_ORG events
        final long count4 = testingPostgres.runSelectStatement("select count(*) from event where type = 'MODIFY_ORG'", long.class);
        assertEquals("There should be 2 events of type MODIFY_ORG, there are " + count4, 2, count4);

        // organization should have new information
        organization = organizationsApiUser2.getOrganizationById(registeredOrganization.getId());
        assertEquals("organization should be returned and have an updated link.", link, organization.getLink());

        List<Event> events = organizationsApiUser2.getOrganizationEvents(registeredOrganization.getId(), 0, 5);
        assertEquals("There should be 4 events, there are " + events.size(), 4, events.size());

        // Events pagination tests
        List<Event> firstTwoEvents = organizationsApiUser2.getOrganizationEvents(registeredOrganization.getId(), 0, 2);
        assertEquals("There should only be 2 events, there are " + firstTwoEvents.size(), 2, firstTwoEvents.size());
        assertEquals(firstTwoEvents.get(0), events.get(0));
        assertEquals(firstTwoEvents.get(1), events.get(1));

        List<Event> secondEvent = organizationsApiUser2.getOrganizationEvents(registeredOrganization.getId(), 1, 1);
        assertEquals("There should only be 1 event, there are " + secondEvent.size(), 1, secondEvent.size());
        assertEquals(secondEvent.get(0), events.get(1));

        List<io.swagger.client.model.OrganizationUser> users = organizationsApiUser2.getOrganizationMembers(registeredOrganization.getId());
        assertEquals("There should be 1 user, there are " + users.size(), 1, users.size());

        // Update the organization
        String logo = "https://res.cloudinary.com/hellofresh/image/upload/f_auto,fl_lossy,q_auto,w_640/v1/hellofresh_s3/image/554a3abff8b25e1d268b456d.png";
        newOrganization.setAvatarUrl(logo);
        organization = organizationsApiUser2.updateOrganization(newOrganization, organization.getId());

        // There should be three MODIFY_ORG events
        final long count5 = testingPostgres.runSelectStatement("select count(*) from event where type = 'MODIFY_ORG'", long.class);
        assertEquals("There should be 2 events of type MODIFY_ORG, there are " + count5, 3, count5);

        // organization should have new information
        organization = organizationsApiUser2.getOrganizationById(registeredOrganization.getId());
        assertEquals("organization should be returned and have an updated logo image.", logo, organization.getAvatarUrl());

        // Update organization test
        organization = organizationsApiUser2.updateOrganizationDescription(organization.getId(), "potato");
        assertEquals("potato", organization.getDescription());
        String description = organizationsApiUser2.getOrganizationDescription(organization.getId());
        assertEquals("potato", description);

        testStarredOrganizationEvents(organizationsApiUser2, organization);

        organization.setName("NameSquatting");
        organization.setDisplayName("DisplayNameSquatting");
        Organization curatorUpdatedOrganization = organizationsApiCurator.updateOrganization(organization, organization.getId());
        assertEquals("A curator can still update an approved organization name", "NameSquatting", curatorUpdatedOrganization.getName());
        assertEquals("A curator can still update an approved organization display name", "DisplayNameSquatting", curatorUpdatedOrganization.getDisplayName());

        testEmptyEmailAndLink(organizationsApiCurator, organization);
    }

    private void testEmptyEmailAndLink(OrganizationsApi organizationsApi, Organization organization) {
        organization.setEmail("");
        organization.setLink("");
        Organization updatedOrganization = organizationsApi.updateOrganization(organization, organization.getId());
        assertEquals(null, updatedOrganization.getEmail());
        assertEquals(null, updatedOrganization.getEmail());
    }

    /**
     * This tests that:
     * the pagination limit works
     * the newest events are gotten
     * @param organizationsApiUser2 Organization API for user 2 who will star the organization
     * @param organization  An organization which is known to have 6 events (create > modify > approve > modify > modify > modify)
     */
    private void testStarredOrganizationEvents(OrganizationsApi organizationsApiUser2, Organization organization) {
        final io.dockstore.openapi.client.ApiClient openAPIWebClientUser2 = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        EventsApi eventsApi = new EventsApi(openAPIWebClientUser2);
        List<io.dockstore.openapi.client.model.Event> events = eventsApi
                .getEvents(EventSearchType.STARRED_ORGANIZATION.toString(), null, null);
        assertEquals("Should have the correct amount of events", 0, events.size());

        organizationsApiUser2.starOrganization(organization.getId(), STAR_REQUEST);

        events = eventsApi
                .getEvents(EventSearchType.STARRED_ORGANIZATION.toString(), null, null);
        assertEquals("Should have the correct amount of events (STARRED_ORGANIZATION)", 6, events.size());
        events = eventsApi
                .getEvents(EventSearchType.ALL_STARRED.toString(), null, null);
        assertEquals("Should have the correct amount of events (ALL_STARRED)", 6, events.size());
        events = eventsApi.getEvents(EventSearchType.STARRED_ORGANIZATION.toString(), 5, null);
        assertEquals("Should have the correct amount of events", 5, events.size());
        Assert.assertFalse("The create org event is the oldest, it should not be returned", events.stream().anyMatch(event -> event.getType().equals(io.dockstore.openapi.client.model.Event.TypeEnum.CREATE_ORG)));
        try {
            eventsApi.getEvents(EventSearchType.STARRED_ORGANIZATION.toString(), EventDAO.MAX_LIMIT + 1, 0);
            Assert.fail("Should've failed because it's over the limit");
        } catch (io.dockstore.openapi.client.ApiException e) {
            assertEquals("{\"errors\":[\"query param limit must be less than or equal to " + EventDAO.MAX_LIMIT + "\"]}", e.getMessage());
        }
        try {
            eventsApi.getEvents(EventSearchType.STARRED_ORGANIZATION.toString(), 0, 0);
            Assert.fail("Should've failed because it's under the limit");
        } catch (io.dockstore.openapi.client.ApiException e) {
            assertEquals("{\"errors\":[\"query param limit must be greater than or equal to 1\"]}", e.getMessage());
        }
    }

    @Test(expected = ApiException.class)
    public void testDuplicateOrgByCase() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organisationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Create the organisation
        Organization organisation = stubOrgObject();
        organisationsApiUser2.createOrganization(organisation);
        organisation.setName(organisation.getName().toUpperCase());
        organisationsApiUser2.createOrganization(organisation);
    }

    @Test
    public void createOrgInvalidEmail() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organisationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Create the organisation
        Organization organisation = stubOrgObject();
        organisation.setEmail("thisisnotanemail");
        thrown.expect(ApiException.class);
        organisationsApiUser2.createOrganization(organisation);
    }

    @Test
    public void createOrgInvalidLink() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organisationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Create the organisation
        Organization organisation = stubOrgObject();
        organisation.setLink("www.google.com");
        thrown.expect(ApiException.class);
        organisationsApiUser2.createOrganization(organisation);
    }

    /**
     * This tests that you cannot add an organization with a duplicate display name
     */
    @Test
    public void testDuplicateOrgDisplayName() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organisationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Create the organisation
        Organization organisation = stubOrgObject();
        organisationsApiUser2.createOrganization(organisation);

        // Create org with different name and display name
        organisation.setName("testname2");
        organisation.setDisplayName("test name 2");
        organisationsApiUser2.createOrganization(organisation);

        // Create org with different name but same display name
        organisation.setName("testname3");
        organisation.setDisplayName("test name");
        thrown.expect(ApiException.class);
        organisationsApiUser2.createOrganization(organisation);
    }

    /**
     * This tests that just changing the case of your name should be fine
     */
    @Test
    public void testRenameOrgByCase() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organisationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Create the organisation
        Organization organisation = stubOrgObject();
        organisation.setName("HCA");
        organisation.setDisplayName("HCA");
        organisation = organisationsApiUser2.createOrganization(organisation);

        // Create org with different name and display name
        Organization organisation2 = stubOrgObject();
        organisation2.setName("testname2");
        organisation2.setDisplayName("test name 2");
        organisation2 = organisationsApiUser2.createOrganization(organisation2);

        organisation.setName("hCa");
        organisation.setDisplayName("hCa");
        organisationsApiUser2.updateOrganization(organisation, organisation.getId());
    }

    @Test
    public void testCollectionAlternateCase() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organisationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Create the organisation
        Organization organisation = stubOrgObject();
        organisation = organisationsApiUser2.createOrganization(organisation);

        // Create a collection
        Collection stubCollection = stubCollectionObject();
        stubCollection.setName("hcacollection");

        // Attach collection
        organisationsApiUser2.createCollection(organisation.getId(), stubCollection);
        stubCollection.setName("HCAcollection");
        thrown.expect(ApiException.class);
        organisationsApiUser2.createCollection(organisation.getId(), stubCollection);
    }

    /**
     * This tests that you cannot add a collection with a duplicate display name
     */
    @Test
    public void testDuplicateCollectionDisplayName() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organisationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Create the organisation
        Organization organisation = stubOrgObject();
        organisation = organisationsApiUser2.createOrganization(organisation);

        // Create a collection
        Collection stubCollection = stubCollectionObject();

        // Attach collection
        Collection collection = organisationsApiUser2.createCollection(organisation.getId(), stubCollection);

        // Create another collection with a different name and display name
        stubCollection.setName("testcollection2");
        stubCollection.setDisplayName("test collection 2");

        Collection collectionTwo = organisationsApiUser2.createCollection(organisation.getId(), stubCollection);

        // Create another collection with a different name but same display name
        stubCollection.setName("testcollection3");
        thrown.expect(ApiException.class);
        organisationsApiUser2.createCollection(organisation.getId(), stubCollection);
    }

    @Test
    public void testGetViaAlternateCase() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organisationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Create the organisation
        Organization organisation = stubOrgObject();
        final Organization createdOrg = organisationsApiUser2.createOrganization(organisation);
        String alternateName = organisation.getName().toUpperCase();
        final Organization organisationByName = organisationsApiUser2.getOrganizationByName(alternateName);
        assertEquals(organisationByName.getId(), createdOrg.getId());
    }

    /**
     * This tests that an Organization can be rejected
     */
    @Test
    public void testCreateOrganizationAndRejectIt() {
        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Setup curator
        final ApiClient webClientCuratorUser = getWebClient(curatorUsername, testingPostgres);
        OrganizationsApi organizationsApiCurator = new OrganizationsApi(webClientCuratorUser);

        // Setup admin
        final ApiClient webClientAdminUser = getWebClient(ADMIN_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiAdmin = new OrganizationsApi(webClientAdminUser);

        // Create the Organization
        Organization registeredOrganization = createOrg(organizationsApiUser2);
        assertEquals(registeredOrganization.getStatus(), StatusEnum.PENDING);

        // Should appear in the pending
        List<Organization> organizationList = organizationsApiAdmin.getAllOrganizations("pending");
        assertEquals("Should have one pending Organization, there are " + organizationList.size(), 1, organizationList.size());
        organizationList.forEach(organization -> Assert.assertNull(organization.getAliases()));

        // Should not appear in rejected
        organizationList = organizationsApiAdmin.getAllOrganizations("rejected");
        assertEquals("Should have no rejected Organizations, there are " + organizationList.size(), 0, organizationList.size());

        // Should not appear in approved
        organizationList = organizationsApiAdmin.getAllOrganizations("approved");
        assertEquals("Should have no approved Organizations, there are " + organizationList.size(), 0, organizationList.size());

        // Curator reject org
        organizationsApiCurator.rejectOrganization(registeredOrganization.getId());

        // Should not appear in pending
        organizationList = organizationsApiAdmin.getAllOrganizations("pending");
        assertEquals("Should have no pending Organizations, there are " + organizationList.size(), 0, organizationList.size());

        // Should appear in rejected
        organizationList = organizationsApiAdmin.getAllOrganizations("rejected");
        assertEquals("Should have one rejected Organization, there are " + organizationList.size(), 1, organizationList.size());
        organizationList.forEach(organization -> Assert.assertNull(organization.getAliases()));

        // Should not appear in approved
        organizationList = organizationsApiAdmin.getAllOrganizations("approved");
        assertEquals("Should have no approved Organizations, there are " + organizationList.size(), 0, organizationList.size());

        // Should be able to request a re-review
        organizationsApiUser2.requestOrganizationReview(registeredOrganization.getId());

        // Should appear in pending
        organizationList = organizationsApiAdmin.getAllOrganizations("pending");
        assertEquals("Should have one pending Organization, there are " + organizationList.size(), 1, organizationList.size());
        organizationList.forEach(organization -> Assert.assertNull(organization.getAliases()));

    }

    /**
     * Tests that you cannot create an Organization if another Organization already exists with the same name.
     * Also will test renaming of Organizations.
     */
    @Test
    public void testCreateDuplicateOrganization() {
        // Setup postgres

        // Setup API client
        final ApiClient webClient = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClient);
        createOrg(organizationsApi);

        // There should be one CREATE_ORG event
        final long count = testingPostgres.runSelectStatement("select count(*) from event where type = 'CREATE_ORG'", long.class);
        assertEquals("There should be 1 event of type CREATE_ORG, there are " + count, 1, count);

        boolean throwsError = false;
        try {
            createOrg(organizationsApi);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to register an Organization with a duplicate Organization name.");
        }
        throwsError = false;

        // Register another Organization
        Organization organization = stubOrgObject();
        organization.setName("anotherorg");
        organization.setDisplayName("anotherorg");

        organization = organizationsApi.createOrganization(organization);

        // There should be one CREATE_ORG event
        final long count2 = testingPostgres.runSelectStatement("select count(*) from event where type = 'CREATE_ORG'", long.class);
        assertEquals("There should be 2 events of type CREATE_ORG, there are " + count2, 2, count2);

        // Try renaming Organization to testname, should fail
        organization.setName("testname");
        organization.setDisplayName("testname2");
        try {
            organizationsApi.updateOrganization(organization, organization.getId());
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to update an Organization with a duplicate Organization name.");
        }

        // Try renaming to testname2, should work
        organization.setName("testname2");
        organization = organizationsApi.updateOrganization(organization, organization.getId());

        // There should be two MODIFY_ORG events
        final long count3 = testingPostgres.runSelectStatement("select count(*) from event where type = 'MODIFY_ORG'", long.class);
        assertEquals("There should be 1 event of type MODIFY_ORG, there are " + count3, 1, count3);

        assertEquals("The Organization should have an updated name", "testname2", organization.getName());
    }

    /**
     * Tests that an Organization admin can request a user to join.
     * The user can then join the Organization as a maintainer or member.
     * Maintainers can edit the Organization metadata.
     * Members cannot edit anything.
     * Change role to maintainer.
     * Then the user can be removed by the maintainer.
     */
    @Test
    public void testRequestUserJoinOrgAndApprove() {
        // Setup postgres

        // Setup user two
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Setup other user
        final ApiClient webClientOtherUser = getWebClient(OTHER_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiOtherUser = new OrganizationsApi(webClientOtherUser);
        UsersApi usersOtherUser = new UsersApi(webClientOtherUser);

        // Create an Organization
        Organization organization = createOrg(organizationsApiUser2);
        assertNotEquals(organization.getStatus(), StatusEnum.APPROVED);

        // There should be one CREATE_ORG event
        final long count = testingPostgres.runSelectStatement("select count(*) from event where type = 'CREATE_ORG'", long.class);
        assertEquals("There should be 1 event of type CREATE_ORG, there are " + count, 1, count);

        long orgId = organization.getId();
        long userId = 2;

        // Other user should be in no orgs
        List<io.swagger.client.model.OrganizationUser> memberships = usersOtherUser.getUserMemberships();
        assertEquals("Should have no memberships, has " + memberships.size(), 0, memberships.size());

        // Request that other user joins
        organizationsApiUser2.addUserToOrg(OrganizationUser.Role.MAINTAINER.toString(), userId, orgId, "");

        // There should be one ADD_USER_TO_ORG event
        final long count2 = testingPostgres.runSelectStatement("select count(*) from event where type = 'ADD_USER_TO_ORG'", long.class);
        assertEquals("There should be 1 event of type ADD_USER_TO_ORG, there are " + count2, 1, count2);

        // There should exist a role that is not accepted
        final long count3 = testingPostgres.runSelectStatement(
            "select count(*) from organization_user where accepted = false and organizationId = '" + 1 + "' and userId = '" + 2 + "'",
            long.class);
        assertEquals("There should be 1 unaccepted role for user 2 and org 1, there are " + count3, 1, count3);

        // Should exist in the users membership list
        memberships = usersOtherUser.getUserMemberships();
        assertEquals("Should have one membership, has " + memberships.size(), 1, memberships.size());

        // Approve request
        organizationsApiOtherUser.acceptOrRejectInvitation(orgId, true);

        // Should still exist in the users membership list
        memberships = usersOtherUser.getUserMemberships();
        assertEquals("Should have one membership, has " + memberships.size(), 1, memberships.size());

        // There should be one APPROVE_ORG_INVITE event
        final long count4 = testingPostgres.runSelectStatement("select count(*) from event where type = 'APPROVE_ORG_INVITE'", long.class);
        assertEquals("There should be 1 event of type APPROVE_ORG_INVITE, there are " + count4, 1, count4);

        // There should exist a role that is accepted
        final long count5 = testingPostgres.runSelectStatement(
            "select count(*) from organization_user where accepted = true and organizationId = '" + 1 + "' and userId = '" + 2 + "'",
            long.class);
        assertEquals("There should be 1 accepted role for user 2 and org 1, there are " + count5, 1, count5);

        List<io.swagger.client.model.OrganizationUser> users = organizationsApiUser2.getOrganizationMembers(organization.getId());
        assertEquals("There should be 2 users, there are " + users.size(), 2, users.size());

        // Should be able to update email of Organization
        String email = "another@email.com";
        Organization newOrganization = stubOrgObject();
        newOrganization.setEmail(email);
        organization = organizationsApiOtherUser.updateOrganization(newOrganization, orgId);
        assertEquals("Organization should be returned and have an updated email.", email, organization.getEmail());

        // There should be one MODIFY_ORG event
        final long count6 = testingPostgres.runSelectStatement("select count(*) from event where type = 'MODIFY_ORG'", long.class);
        assertEquals("There should be 1 event of type MODIFY_ORG, there are " + count6, 1, count6);

        // Admin should be able to change the members role to maintainer
        organizationsApiUser2.updateUserRole(OrganizationUser.Role.MAINTAINER.toString(), userId, orgId);

        // There should be one MODIFY_USER_ROLE_ORG event
        final long count7 = testingPostgres
            .runSelectStatement("select count(*) from event where type = 'MODIFY_USER_ROLE_ORG'", long.class);
        assertEquals("There should be 1 event of type MODIFY_USER_ROLE_ORG, there are " + count7, 1, count7);

        // Remove the user
        organizationsApiUser2.deleteUserRole(userId, orgId);

        // There should be one REMOVE_USER_FROM_ORG event
        final long count8 = testingPostgres
            .runSelectStatement("select count(*) from event where type = 'REMOVE_USER_FROM_ORG'", long.class);
        assertEquals("There should be 1 event of type REMOVE_USER_FROM_ORG, there are " + count8, 1, count8);

        // Should once again not be able to update the email
        email = "hello@email.com";
        newOrganization.setEmail(email);
        try {
            organization = organizationsApiOtherUser.updateOrganization(newOrganization, orgId);
        } catch (ApiException ex) {
            organization = null;
        }
        assertNull("Other user should not be able to update the Organization.", organization);
    }

    @Test
    public void testMembersArePowerless() {
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Setup other user
        final ApiClient webClientOtherUser = getWebClient(OTHER_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiOtherUser = new OrganizationsApi(webClientOtherUser);
        UsersApi usersOtherUser = new UsersApi(webClientOtherUser);

        // Create org, invite user as member, and accept invitation
        Organization organization = createOrg(organizationsApiUser2);
        long orgId = organization.getId();
        long userId = 1;
        long otherUserId = 2;
        organizationsApiUser2.addUserToOrg(OrganizationUser.Role.MEMBER.toString(), otherUserId, orgId, "");
        organizationsApiOtherUser.acceptOrRejectInvitation(orgId, true);

        // Should not be able to update organization information
        String email = "hello@email.com";
        organization.setEmail(email);
        try {
            organization = organizationsApiOtherUser.updateOrganization(organization, orgId);
            Assert.fail("Should not be able to update organization information");
        } catch (ApiException ex) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, ex.getCode());
        }

        // Should not be able to invite another user
        long thirdUser = 3;
        try {
            organizationsApiOtherUser.addUserToOrg(OrganizationUser.Role.MEMBER.toString(), thirdUser, orgId, "");
            Assert.fail("Member should not be able to add a user to organization");
        } catch (ApiException ex) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, ex.getCode());
        }

        // Should not be able to update another user's role
        try {
            organizationsApiOtherUser.updateUserRole(OrganizationUser.Role.MAINTAINER.toString(), userId, orgId);
            Assert.fail(" Should not be able to update another user's role");
        } catch (ApiException ex) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, ex.getCode());
        }

        // Should not be able to create a collection
        Collection stubCollection = stubCollectionObject();
        try {
            Collection collection = organizationsApiOtherUser.createCollection(orgId, stubCollection);
            Assert.fail("Member should not be able to add a user to organization");
        } catch (ApiException ex) {
            Assert.assertEquals("Organization not found.", ex.getMessage());
        }

        // Should not be able to update a collection
        Collection collection = organizationsApiUser2.createCollection(orgId, stubCollection);
        collection.setDescription("description");
        try {
            collection = organizationsApiOtherUser.updateCollection(collection, organization.getId(), collection.getId());
            Assert.fail("Should not be able to update a collection");
        } catch (ApiException ex) {
            Assert.assertEquals("User does not have rights to modify a collection from this organization.", ex.getMessage());
        }

        try {
            collection = organizationsApiOtherUser.updateCollectionDescription(organization.getId(), collection.getId(), "descriptin");
            Assert.fail("Should not be able to update a collection");
        } catch (ApiException ex) {
            Assert.assertEquals("User does not have rights to modify a collection from this organization.", ex.getMessage());
        }
    }

    @Test
    public void testMaintainersCantAddOrUpdateUsers() {
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Setup other user
        final ApiClient webClientOtherUser = getWebClient(OTHER_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiOtherUser = new OrganizationsApi(webClientOtherUser);
        UsersApi usersOtherUser = new UsersApi(webClientOtherUser);

        // Create org, invite user as member, and accept invitation
        Organization organization = createOrg(organizationsApiUser2);
        long orgId = organization.getId();
        long userId = 1;
        long otherUserId = 2;
        organizationsApiUser2.addUserToOrg(OrganizationUser.Role.MAINTAINER.toString(), otherUserId, orgId, "");
        organizationsApiOtherUser.acceptOrRejectInvitation(orgId, true);

        // Should not be able to invite another user
        long thirdUser = 3;
        try {
            organizationsApiOtherUser.addUserToOrg(OrganizationUser.Role.MEMBER.toString(), thirdUser, orgId, "");
            Assert.fail("Member should not be able to add a user to organization");
        } catch (ApiException ex) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, ex.getCode());
        }

        // Should not be able to update another user's role
        try {
            organizationsApiOtherUser.updateUserRole(OrganizationUser.Role.MAINTAINER.toString(), userId, orgId);
            Assert.fail(" Should not be able to update another user's role");
        } catch (ApiException ex) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, ex.getCode());
        }
    }

    /**
     * Tests that an Organization admin can request a user to join.
     * The user can disapprove.
     */
    @Test
    public void testRequestUserJoinOrgAndDisapprove() {
        // Setup postgres

        // Setup user one
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiUser2 = new OrganizationsApi(webClientUser2);

        // Setup user two
        final ApiClient webClientOtherUser = getWebClient(OTHER_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiOtherUser = new OrganizationsApi(webClientOtherUser);

        // Create an Organization
        Organization organization = createOrg(organizationsApiUser2);
        assertNotEquals(organization.getStatus(), StatusEnum.APPROVED);

        // There should be one CREATE_ORG event
        final long count = testingPostgres.runSelectStatement("select count(*) from event where type = 'CREATE_ORG'", long.class);
        assertEquals("There should be 1 event of type CREATE_ORG, there are " + count, 1, count);

        long orgId = organization.getId();
        long userId = 2;

        // Request that other user joins
        organizationsApiUser2.addUserToOrg(OrganizationUser.Role.MEMBER.toString(), userId, orgId, "");

        // There should be one ADD_USER_TO_ORG event
        final long count2 = testingPostgres.runSelectStatement("select count(*) from event where type = 'ADD_USER_TO_ORG'", long.class);
        assertEquals("There should be 1 event of type ADD_USER_TO_ORG, there are " + count2, 1, count2);

        // There should exist a role that is not accepted
        final long count3 = testingPostgres.runSelectStatement(
            "select count(*) from organization_user where accepted = false and organizationId = '" + 1 + "' and userId = '" + 2 + "'",
            long.class);
        assertEquals("There should be 1 unaccepted role for user 2 and org 1, there are " + count3, 1, count3);

        // Disapprove request
        organizationsApiOtherUser.acceptOrRejectInvitation(orgId, false);

        // There should be one REJECT_ORG_INVITE event
        final long count4 = testingPostgres.runSelectStatement("select count(*) from event where type = 'REJECT_ORG_INVITE'", long.class);
        assertEquals("There should be 1 event of type REJECT_ORG_INVITE, there are " + count4, 1, count4);

        // Should not have a role
        final long count5 = testingPostgres
            .runSelectStatement("select count(*) from organization_user where organizationId = '" + 1 + "' and userId = '" + 2 + "'",
                long.class);
        assertEquals("There should be no roles for user 2 and org 1, there are " + count5, 0, count5);

        // Test that events are sorted by DESC dbCreateDate
        List<Event> events = organizationsApiUser2.getOrganizationEvents(orgId, 0, 5);
        assertEquals("Should have 3 events returned, there are " + events.size(), 3, events.size());
        assertEquals("First event should be most recent, which is REJECT_ORG_INVITE, but is actually " + events.get(0).getType().getValue(),
            "REJECT_ORG_INVITE", events.get(0).getType().getValue());

        // Request a user that doesn't exist
        boolean throwsError = false;
        try {
            organizationsApiUser2.addUserToOrgByUsername(OrganizationUser.Role.MEMBER.toString(), "IDONOTEXIST", orgId);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to add a user to an org with a username that does not exist");
        }
    }

    /**
     * Tests that you cannot create an Organization where the name is all numbers.
     * This is because we would like to use the same endpoint to grab an Organization by either name or DB id.
     * <p>
     * Also tests some other cases where the name should fail
     */
    @Test
    public void testCreateOrganizationWithInvalidNames() {
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);
        badNames.forEach(name -> createOrgWithBadName(name, organizationsApi));
    }

    /**
     * Tests that you can create organizations using some unique characters for the display name
     */
    @Test
    public void testCreatedOrganizationWithValidDisplayNames() {
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);
        goodDisplayNames.forEach(displayName -> createOrganizationWithValidDisplayName(displayName, organizationsApi,
            "testname" + goodDisplayNames.indexOf(displayName)));
    }

    /**
     * Tests that you cannot create organizations with some display names
     */
    @Test
    public void testCreateOrganizationsWithBadDisplayNames() {
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);
        badDisplayNames.forEach(displayName -> createOrganizationWithInvalidDisplayName(displayName, organizationsApi,
            "testname" + badDisplayNames.indexOf(displayName)));
    }

    /**
     * Helper that creates an Organization with a name that should fail
     *
     * @param name
     * @param organizationsApi
     */
    private void createOrgWithBadName(String name, OrganizationsApi organizationsApi) {
        Organization organization = stubOrgObject();
        organization.setName(name);

        boolean throwsError = false;
        try {
            organizationsApi.createOrganization(organization);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to create an Organization with an incorrect name: " + name);
        }
    }

    /**
     * Test that Organization avatarUrl column constraints work as intended.
     */
    @Test
    public void testAvatarUrlConstraints() {
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);
        badAvatarUrls.forEach(url -> createOrgWithBadAvatarUrl(url, organizationsApi));
    }

    /**
     * Helper that creates an Organization with an avatar url that should fail
     *
     * @param url
     * @param organizationsApi
     */
    private void createOrgWithBadAvatarUrl(String url, OrganizationsApi organizationsApi) {
        Organization organization = stubOrgObject();
        organization.setAvatarUrl(url);

        boolean throwsError = false;
        try {
            organizationsApi.createOrganization(organization);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to create an Organization with an incorrect avatar url: " + url);
        }
    }

    /**
     * Helper that creates an organization with a display name that should not fail
     *
     * @param displayName
     * @param organizationsApi
     */
    private void createOrganizationWithValidDisplayName(String displayName, OrganizationsApi organizationsApi, String name) {
        Organization organization = stubOrgObject();
        organization.setName(name);
        organization.setDisplayName(displayName);

        organization = organizationsApi.createOrganization(organization);
        assertNotNull("Should create the organization", organizationsApi.getOrganizationById(organization.getId()));
    }

    /**
     * Helper method that create an organization with a display name that is invalid
     *
     * @param displayName
     * @param organizationsApi
     * @param name
     */
    private void createOrganizationWithInvalidDisplayName(String displayName, OrganizationsApi organizationsApi, String name) {
        Organization organization = stubOrgObject();
        organization.setName(name);
        organization.setDisplayName(displayName);

        boolean throwsError = false;
        try {
            organizationsApi.createOrganization(organization);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to create an Organization with an incorrect display name: " + displayName);
        }
    }

    /**
     * This tests that you can create collections with unique characters in their display name
     */
    @Test
    public void testCreateCollectionWithValidDisplayNames() {
        // Setup user who creates Organization and collection
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);

        // Create the Organization and collection
        Organization organization = createOrg(organizationsApi);

        final Long organizationID = organization.getId();
        goodDisplayNames.forEach(displayName -> {
            createCollectionWithValidDisplayName(displayName, organizationsApi, organizationID,
                "testname" + goodDisplayNames.indexOf(displayName));
        });
    }

    /**
     * This tests that you cannot create collections with invalid display names
     */
    @Test
    public void testCreateCollectionWithInvalidDisplayNames() {
        // Setup user who creates Organization and collection
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);

        // Create the Organization and collection
        Organization organization = createOrg(organizationsApi);

        final Long organizationID = organization.getId();
        badDisplayNames.forEach(displayName -> {
            createCollectionWithBadDisplayName(displayName, organizationsApi, organizationID,
                "testname" + badDisplayNames.indexOf(displayName));
        });
    }

    /**
     * A helper method for creating a collection with a valid display name
     *
     * @param displayName
     * @param organizationsApi
     * @param organizationId
     * @param name
     */
    private void createCollectionWithValidDisplayName(String displayName, OrganizationsApi organizationsApi, Long organizationId,
        String name) {
        Collection collection = stubCollectionObject();
        collection.setDisplayName(displayName);
        collection.setName(name);

        collection = organizationsApi.createCollection(organizationId, collection);
        assertNotNull("Should create the collection", organizationsApi.getCollectionById(organizationId, collection.getId()));
    }

    /**
     * Helper that creates an Organization with a display name that should fail
     *
     * @param name
     * @param organizationsApi
     */
    private void createCollectionWithBadDisplayName(String displayName, OrganizationsApi organizationsApi, Long organizationId,
        String name) {
        Collection collection = stubCollectionObject();
        collection.setName(name);
        collection.setDisplayName(displayName);

        boolean throwsError = false;
        try {
            organizationsApi.createCollection(organizationId, collection);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to create a collection with an incorrect name: " + name);
        }
    }

    /**
     * Helper that creates an Organization with a name that should fail
     *
     * @param name
     * @param organizationsApi
     */
    private void createCollectionWithBadName(String name, OrganizationsApi organizationsApi, Long organizationId) {
        Collection collection = stubCollectionObject();
        collection.setName(name);

        boolean throwsError = false;
        try {
            organizationsApi.createCollection(organizationId, collection);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to create a collection with an incorrect name: " + name);
        }
    }

    /**
     * This tests that you can add a collection to an Organization and tests conditions for when it is visible
     */
    @Test
    @SuppressWarnings("checkstyle:MethodLength")
    public void testBasicCollections() {
        // Setup postgres

        // Setup user who creates Organization and collection
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);

        // Setup admin
        final ApiClient webClientAdminUser = getWebClient(ADMIN_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiAdmin = new OrganizationsApi(webClientAdminUser);

        // Setup other user
        final ApiClient webClientOtherUser = getWebClient(OTHER_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiOtherUser = new OrganizationsApi(webClientOtherUser);

        // Setup unauthorized user
        final ApiClient unauthClient = CommonTestUtilities.getWebClient(false, "", testingPostgres);
        OrganizationsApi organizationsApiUnauth = new OrganizationsApi(unauthClient);

        // Create the Organization and collection
        Organization organization = createOrg(organizationsApi);
        Collection stubCollection = stubCollectionObject();

        final Long organizationID = organization.getId();
        badNames.forEach(name -> {
            createCollectionWithBadName(name, organizationsApi, organizationID);
        });

        // Attach collection
        Collection collection = organizationsApi.createCollection(organization.getId(), stubCollection);
        long collectionId = collection.getId();

        // There should be one CREATE_COLLECTION event
        final long count = testingPostgres.runSelectStatement("select count(*) from event where type = 'CREATE_COLLECTION'", long.class);
        assertEquals("There should be 1 event of type CREATE_COLLECTION, there are " + count, 1, count);

        // The creating user should be able to see the collection even though the Organization is not approved
        collection = organizationsApi.getCollectionById(organization.getId(), collectionId);
        assertNotNull("Should be able to see the collection.", collection);

        collection = organizationsApi.getCollectionByName(organization.getName(), collection.getName());
        assertNotNull("Should be able to see the collection.", collection);

        // Other user should not be able to see
        try {
            collection = organizationsApiOtherUser.getCollectionById(organization.getId(), collectionId);
        } catch (ApiException ex) {
            collection = null;
        }
        assertNull("Should not be able to see the collection.", collection);

        // Admin should be able to see the collection
        collection = organizationsApiAdmin.getCollectionById(organization.getId(), collectionId);
        assertNotNull("Should be able to see the collection.", collection);

        // Other user should not be able to see by name
        try {
            collection = organizationsApiOtherUser.getCollectionByName(organization.getName(), collection.getName());
        } catch (ApiException ex) {
            collection = null;
        }
        assertNull("Should not be able to see the collection.", collection);

        // Approve the Organization
        organization = organizationsApiAdmin.approveOrganization(organization.getId());

        // The creating user should be able to see
        collection = organizationsApi.getCollectionById(organization.getId(), collectionId);
        assertNotNull("Should be able to see the collection.", collection);

        // Other user should be able to see
        collection = organizationsApiOtherUser.getCollectionById(organization.getId(), collectionId);
        assertNotNull("Should be able to see the collection.", collection);

        collection = organizationsApiOtherUser.getCollectionByName(organization.getName(), collection.getName());
        assertNotNull("Should be able to see the collection.", collection);

        // Admin should be able to see the collection
        collection = organizationsApiAdmin.getCollectionById(organization.getId(), collectionId);
        assertNotNull("Should be able to see the collection.", collection);

        // Publish a tool
        long entryId = 2;
        ContainersApi containersApi = new ContainersApi(webClientUser2);
        PublishRequest publishRequest = SwaggerUtility.createPublishRequest(true);
        containersApi.publish(entryId, publishRequest);

        // Able to retrieve the collection and organization an entry is part of, even if there aren't any
        EntriesApi entriesApi = new EntriesApi(webClientUser2);
        List<CollectionOrganization> collectionOrganizations = entriesApi.entryCollections(entryId);
        assertEquals(0, collectionOrganizations.size());

        // Add tool to collection
        organizationsApi.addEntryToCollection(organization.getId(), collectionId, entryId, null);

        // Able to retrieve the collection and organization an entry is part of
        collectionOrganizations = entriesApi.entryCollections(entryId);
        assertEquals(1, collectionOrganizations.size());
        CollectionOrganization collectionOrganization = collectionOrganizations.get(0);
        assertEquals(organization.getId(), collectionOrganization.getOrganizationId());
        assertEquals(organization.getName(), collectionOrganization.getOrganizationName());
        assertEquals(organization.getDisplayName(), collectionOrganization.getOrganizationDisplayName());
        assertEquals(collection.getId(), collectionOrganization.getCollectionId());
        assertEquals(collection.getName(), collectionOrganization.getCollectionName());
        assertEquals(collection.getDisplayName(), collectionOrganization.getCollectionDisplayName());

        // Unable to retrieve the collection and organization of an entry that does not exist
        try {
            entriesApi.entryCollections(9001L);
            Assert.fail("Should have gotten an exception because the entry does not exist");
        } catch (Exception e) {
            assertTrue(true);
        }

        // The collection should have an entry
        collection = organizationsApiAdmin.getCollectionById(organization.getId(), collectionId);
        assertEquals("There should be one entry with the collection, there are " + collection.getEntries().size(), 1,
            collection.getEntries().size());

        // Publish another tool
        entryId = 1;
        containersApi.publish(entryId, publishRequest);

        // Add tool to collection
        organizationsApi.addEntryToCollection(organization.getId(), collectionId, entryId, null);

        // There should be two entries for collection with ID 1
        Collection collectionById = organizationsApi.getCollectionById(organizationID, collectionId);
        assertEquals(2, collectionById.getEntries().size());

        // There should be two ADD_TO_COLLECTION events
        final long count3 = testingPostgres.runSelectStatement("select count(*) from event where type = 'ADD_TO_COLLECTION'", long.class);
        assertEquals("There should be 2 events of type ADD_TO_COLLECTION, there are " + count3, 2, count3);

        // Unpublish tool
        PublishRequest unpublishRequest = SwaggerUtility.createPublishRequest(false);
        containersApi.publish(entryId, unpublishRequest);

        // Collection should have one tool returned
        long entryCount = organizationsApi.getCollectionById(organization.getId(), collectionId).getEntries().size();
        assertEquals("There should be one entry with the collection, there are " + entryCount, 1, entryCount);

        // Publish tool
        containersApi.publish(entryId, publishRequest);

        // Collection should have two tools returned
        entryCount = organizationsApi.getCollectionById(organization.getId(), collectionId).getEntries().size();
        assertEquals("There should be two entries with the collection, there are " + entryCount, 2, entryCount);

        // Remove a tool from the collection
        organizationsApi.deleteEntryFromCollection(organization.getId(), collectionId, entryId, null);

        // There should be one REMOVE_FROM_COLLECTION events
        final long count4 = testingPostgres
            .runSelectStatement("select count(*) from event where type = 'REMOVE_FROM_COLLECTION'", long.class);
        assertEquals("There should be 1 event of type REMOVE_FROM_COLLECTION, there are " + count4, 1, count4);

        // There should now be one entry for collection with ID 1
        collectionById = organizationsApi.getCollectionById(organizationID, collectionId);
        assertEquals(1, collectionById.getEntries().size());

        // Try getting all collections
        List<Collection> collections = organizationsApi.getCollectionsFromOrganization(organization.getId(), "");
        assertEquals("There should be 1 collection associated with the Organization, there are " + collections.size(), 1,
            collections.size());
        assertEquals("There should be no entries because entries is not specified to be included " + collections.get(0).getEntries().size(), 0,
                collections.get(0).getEntries().size());

        collections = organizationsApi.getCollectionsFromOrganization(organization.getId(), "entries");
        assertEquals("There should be 1 entry associated with the collection, there are " + collections.get(0).getEntries().size(), 1,
            collections.get(0).getEntries().size());

        // Unauth user should be able to see entries
        Collection unauthCollection = organizationsApiUnauth.getCollectionById(organization.getId(), collections.get(0).getId());
        assertEquals("Should have one entry returned with the collection, there are " + unauthCollection.getEntries().size(), 1,
            unauthCollection.getEntries().size());

        // Test description
        Collection collectionWithDesc = organizationsApi.updateCollectionDescription(organization.getId(), collectionId, "potato");
        assertEquals("potato", collectionWithDesc.getDescription());
        String description = organizationsApi.getCollectionDescription(organization.getId(), collectionId);
        assertEquals("potato", description);

        // Should not be able to reject an approved organization
        boolean throwsError = false;
        try {
            organization = organizationsApiAdmin.rejectOrganization(organizationID);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to reject an approved collection");
        }

        // version 8 and 9 belong to entryId 2
        long versionId = 9L;

        // Add tool and specific version to collection
        organizationsApi.addEntryToCollection(organization.getId(), collectionId, entryId, versionId);
        organizationsApi.addEntryToCollection(organization.getId(), collectionId, entryId, null);

        // There should now be two entries for collection with ID 1 (one with version, one without), 3 entries in total
        collectionById = organizationsApi.getCollectionById(organizationID, collectionId);
        assertEquals(3, collectionById.getEntries().size());
        assertTrue("Collection has the version-specific entry", collectionById.getEntries().stream().anyMatch(entry -> "latest"
                .equals(entry.getVersionName()) && entry.getEntryPath().equals("quay.io/dockstore2/testrepo2")));
        assertTrue("Collection still has the non-version-specific entry", collectionById.getEntries().stream().anyMatch(entry -> entry.getVersionName() == null  && entry.getEntryPath().equals("quay.io/dockstore2/testrepo2")));

        organizationsApi.deleteEntryFromCollection(organizationID, collectionId, entryId, versionId);
        collectionById = organizationsApi.getCollectionById(organizationID, collectionId);
        assertEquals("Two entry remains in collection", 2, collectionById.getEntries().size());
        assertTrue("Collection has the non-version-specific entry even after deleting the version-specific one", collectionById.getEntries().stream().anyMatch(entry -> entry.getVersionName() == null && entry.getEntryPath().equals("quay.io/dockstore2/testrepo2")));

    }

    /**
     * This tests that aliases can be set on collections and workflows
     */
    @Test
    public void testAliasesAreInReturnedOrganizationOrCollection() {
        // Setup postgres

        // Setup admin
        final ApiClient webClientAdminUser = getWebClient(ADMIN_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiAdmin = new OrganizationsApi(webClientAdminUser);

        // Setup user who creates Organization and collection
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);

        // Create the Organization and collection
        Organization organization = createOrg(organizationsApi);
        Collection stubCollection = stubCollectionObject();

        // Attach collections
        Collection collection = organizationsApi.createCollection(organization.getId(), stubCollection);
        long collectionId = collection.getId();

        // approve the org
        testingPostgres.runUpdateStatement(
                "update organization set status = '" + io.dockstore.webservice.core.Organization.ApplicationState.APPROVED.toString() + "'");

        // set aliases
        final Collection collectionWithAlias = organizationsApi.addCollectionAliases(collectionId, "test collection, spam");
        final Organization organizationWithAlias = organizationsApi.addOrganizationAliases(organization.getId(), "test organization, spam");

        assertEquals(2, collectionWithAlias.getAliases().size());
        assertEquals(2, organizationWithAlias.getAliases().size());

        Organization organizationById = organizationsApi.getOrganizationById(organization.getId());
        Assert.assertNotNull("Getting organization by ID has null alias", organizationById.getAliases());
        Collection collectionById = organizationsApi.getCollectionById(organization.getId(), collectionId);
        Assert.assertNotNull("Getting collection by ID has null alias", collectionById.getAliases());

        // note that namespaces for organizations and collections are separate (therefore a collection can have the same alias as an organization)
        final Collection collectionByAlias = organizationsApi.getCollectionByAlias("spam");
        assertNotNull(collectionByAlias);
        Assert.assertNotNull("Getting collection by alias has null alias", collectionByAlias.getAliases());
        final Organization organizationByAlias = organizationsApi.getOrganizationByAlias("spam");
        assertNotNull(organizationByAlias);
        Assert.assertNotNull("Getting organization by alias has null alias", organizationByAlias.getAliases());

        final Collection collectionByName = organizationsApi.getCollectionByName(organizationByAlias.getName(),
                collectionByAlias.getName());
        assertNotNull(collectionByName);
        Assert.assertNotNull("Getting collection by name has null alias", collectionByName.getAliases());
        final Organization organizationByName = organizationsApi.getOrganizationByName(organizationByAlias.getName());
        assertNotNull(organizationByName);
        Assert.assertNotNull("Getting organization by name has null alias", organizationByName.getAliases());


        // Should now appear in approved list
        List<Organization> organizationList = organizationsApi.getApprovedOrganizations();
        assertEquals("Should have one approved Organization.", organizationList.size(), 1);
        // organization alias should be in return from API call
        organizationList.forEach(approvedOrganization -> Assert.assertNotNull(approvedOrganization.getAliases()));
    }

    /**
     * This tests that aliases can be set on collections and workflows
     */
    @Test
    public void testAliasOperations() {
        // Setup postgres

        // Setup user who creates Organization and collection
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);

        // Create the Organization and collection
        Organization organization = createOrg(organizationsApi);
        Collection stubCollection = stubCollectionObject();

        // Attach collections
        Collection collection = organizationsApi.createCollection(organization.getId(), stubCollection);
        long collectionId = collection.getId();

        // approve the org
        testingPostgres.runUpdateStatement(
            "update organization set status = '" + io.dockstore.webservice.core.Organization.ApplicationState.APPROVED.toString() + "'");

        // set aliases
        final Collection collectionWithAlias = organizationsApi.addCollectionAliases(collectionId, "test collection, spam");
        final Organization organizationWithAlias = organizationsApi
            .addOrganizationAliases(organization.getId(), "test organization, spam");

        assertEquals(2, collectionWithAlias.getAliases().size());
        assertEquals(2, organizationWithAlias.getAliases().size());

        Organization organizationById = organizationsApi.getOrganizationById(organization.getId());
        Assert.assertNotNull("Getting organization by ID has null alias", organizationById.getAliases());
        Collection collectionById = organizationsApi.getCollectionById(organization.getId(), collectionId);
        Assert.assertNotNull("Getting collection by ID has null alias", collectionById.getAliases());

        // note that namespaces for organizations and collections are separate (therefore a collection can have the same alias as an organization)
        final Collection spam1 = organizationsApi.getCollectionByAlias("spam");
        assertNotNull(spam1);
        final Organization spam = organizationsApi.getOrganizationByAlias("spam");
        assertNotNull(spam);

        // test that an alias cannot start with one of our reserved prefixes

        // demote self to test setting invalid aliases
        testingPostgres.runUpdateStatement("update enduser set  isadmin='f'");

        boolean throwsError = false;
        try {
            organizationsApi.addCollectionAliases(collectionId, "test collection, doi: foo");
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to use a reserved prefix.");
        }
    }

    /**
     * This tests that aliases can be set on collections and workflows
     */
    @Test
    public void testDuplicateAliasOperations() {
        // Setup postgres

        // Setup user who creates Organization and collection
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);

        // Create the Organization and collection
        Organization organization = createOrg(organizationsApi);
        Collection stubCollection = stubCollectionObject();

        // Attach collections
        Collection collection = organizationsApi.createCollection(organization.getId(), stubCollection);
        long collectionId = collection.getId();

        // approve the org
        testingPostgres.runUpdateStatement(
            "update organization set status = '" + io.dockstore.webservice.core.Organization.ApplicationState.APPROVED.toString() + "'");

        // set aliases
        Collection collectionWithAlias = organizationsApi.addCollectionAliases(collectionId, "test collection, spam");
        Organization organizationWithAlias = organizationsApi
            .addOrganizationAliases(organization.getId(), "test organization, spam");

        assertEquals(2, collectionWithAlias.getAliases().size());
        assertEquals(2, organizationWithAlias.getAliases().size());

        // try to add duplicates; this is not allowed
        // set aliases
        boolean throwsError = false;
        try {
            organizationsApi.addCollectionAliases(collectionId, "test collection, spam");
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to add a duplicate Collection alias.");
        }

        throwsError = false;
        try {
            organizationsApi.addOrganizationAliases(organization.getId(), "test organization, spam");
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to add a duplicate Organization alias.");
        }
    }

    /**
     * This tests that you can update the name and description of a collection.
     * Also tests when name is a duplicate.
     */
    @Test
    public void testUpdatingCollectionMetadata() {
        // Setup postgres

        // Setup user who creates Organization and collection
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);

        // Create the Organization and collection
        Organization organization = createOrg(organizationsApi);
        Collection stubCollection = stubCollectionObject();
        Collection stubCollectionTwo = stubCollectionObject();
        stubCollectionTwo.setName("anothername");
        stubCollectionTwo.setDisplayName("another name");

        // Attach collections
        Collection collection = organizationsApi.createCollection(organization.getId(), stubCollection);
        long collectionId = collection.getId();

        Collection collectionTwo = organizationsApi.createCollection(organization.getId(), stubCollectionTwo);
        long collectionTwoId = collectionTwo.getId();

        // Update description of collection
        String desc = "This is a new description.";
        collection.setDescription(desc);
        collection = organizationsApi.updateCollection(collection, organization.getId(), collectionId);

        final long count = testingPostgres
            .runSelectStatement("select count(*) from collection where description = '" + desc + "'", long.class);
        assertEquals("There should be 1 collection with the updated description, there are " + count, 1, count);

        // Update collection name to existing one
        collection.setName("anothername");
        boolean throwsError = false;
        try {
            organizationsApi.updateCollection(collection, organization.getId(), collectionId);
        } catch (ApiException ex) {
            throwsError = true;
        }

        if (!throwsError) {
            fail("Was able to update a collection with an existing name.");
        }
    }

    @Test
    public void testStarringOrganization() {
        // Setup user
        final ApiClient webClientUser2 = getWebClient(USER_2_USERNAME, testingPostgres);

        // Setup admin
        final ApiClient webClientAdminUser = getWebClient(ADMIN_USERNAME, testingPostgres);
        OrganizationsApi organizationsApiAdmin = new OrganizationsApi(webClientAdminUser);

        // Create org
        OrganizationsApi organizationsApi = new OrganizationsApi(webClientUser2);
        Organization organization = createOrg(organizationsApi);

        // Create user and star request body
        UsersApi usersApi = new UsersApi(webClientUser2);
        User user = usersApi.getUser();
        List<User> users = new ArrayList<>();
        users.add(user);
        StarRequest body = new StarRequest();
        body.setStar(false);

        // Should only be able to star approved organizations
        try {
            organizationsApi.starOrganization(organization.getId(), STAR_REQUEST);
            Assert.fail();
        } catch (ApiException ex) {
            assertEquals("Organization not found", ex.getMessage());
        }

        // Approve organization and star it
        organizationsApiAdmin.approveOrganization(organization.getId());
        organizationsApi.starOrganization(organization.getId(), STAR_REQUEST);

        assertEquals(1, organizationsApi.getStarredUsersForApprovedOrganization(organization.getId()).size());
        assertEquals(USER_2_USERNAME, organizationsApi.getStarredUsersForApprovedOrganization(organization.getId()).get(0).getUsername());

        // Should not be able to star twice
        try {
            organizationsApi.starOrganization(organization.getId(), STAR_REQUEST);
            Assert.fail();
        } catch (ApiException ex) {
            assertTrue(ex.getMessage().contains("You cannot star the organization"));
        }

        organizationsApi.starOrganization(organization.getId(), UNSTAR_REQUEST);
        assertEquals(0, organizationsApi.getStarredUsersForApprovedOrganization(organization.getId()).size());
        // Should not be able to unstar twice
        try {
            organizationsApi.starOrganization(organization.getId(), UNSTAR_REQUEST);
            Assert.fail();
        } catch (ApiException ex) {
            assertTrue(ex.getMessage().contains("You cannot unstar the organization"));
        }

        // Test setting/getting starred users
        organization.setStarredUsers(users);
        assertEquals(1, organization.getStarredUsers().size());
        assertEquals(user.getUsername(), organization.getStarredUsers().get(0).getUsername());

        users.remove(user);
        organization.setStarredUsers(users);
        assertEquals(0, organization.getStarredUsers().size());
    }

    @Test
    public void testRemoveRejectedOrPendingOrganization() {
        // Setup admin and one user
        final io.dockstore.openapi.client.ApiClient webClientAdminUser = getOpenAPIWebClient(ADMIN_USERNAME, testingPostgres);
        final io.dockstore.openapi.client.ApiClient webClientUser = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.OrganizationsApi organizationsApi = new io.dockstore.openapi.client.api.OrganizationsApi(webClientAdminUser);
        io.dockstore.openapi.client.api.OrganizationsApi userOrganizationsApi = new io.dockstore.openapi.client.api.OrganizationsApi(webClientUser);

        // Revoke admin privileges for user2
        testingPostgres.runUpdateStatement("UPDATE enduser set isadmin = false WHERE username='DockstoreTestUser2'");

        // Create an organization
        io.dockstore.openapi.client.model.Organization organization = organizationsApi.createOrganization(openApiStubOrgObject());

        // Organization should initially be pending
        assertEquals(io.dockstore.openapi.client.model.Organization.StatusEnum.PENDING, organization.getStatus());

        // Delete the organization
        organizationsApi.deleteRejectedOrPendingOrganization(organization.getId());
        try {
            organizationsApi.getOrganizationByName(organization.getName());
            fail("Organization should not be found since it was deleted");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_NOT_FOUND, ex.getCode());
        }

        // Recreate the organization
        organization = organizationsApi.createOrganization(openApiStubOrgObject());
        assertEquals(io.dockstore.openapi.client.model.Organization.StatusEnum.PENDING, organization.getStatus());

        // Reject the organization
        organizationsApi.rejectOrganization(organization.getId());
        organization = organizationsApi.getOrganizationById(organization.getId());
        assertEquals(io.dockstore.openapi.client.model.Organization.StatusEnum.REJECTED, organization.getStatus());

        // Delete the organization
        organizationsApi.deleteRejectedOrPendingOrganization(organization.getId());
        try {
            organizationsApi.getOrganizationByName(organization.getName());
            fail("Organization should not be found since it was deleted");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_NOT_FOUND, ex.getCode());
        }

        // Recreate the organization with user2
        organization = userOrganizationsApi.createOrganization(openApiStubOrgObject());
        assertEquals(io.dockstore.openapi.client.model.Organization.StatusEnum.PENDING, organization.getStatus());

        // Delete the organization with the admin - this should pass
        organizationsApi.deleteRejectedOrPendingOrganization(organization.getId());
        try {
            organizationsApi.getOrganizationByName(organization.getName());
            fail("Organization should not be found since it was deleted");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_NOT_FOUND, ex.getCode());
        }

        // Recreate the organization with the admin
        organization = organizationsApi.createOrganization(openApiStubOrgObject());
        assertEquals(io.dockstore.openapi.client.model.Organization.StatusEnum.PENDING, organization.getStatus());

        // Approve the organization
        organizationsApi.approveOrganization(organization.getId());
        organization = organizationsApi.getOrganizationById(organization.getId());
        assertEquals(io.dockstore.openapi.client.model.Organization.StatusEnum.APPROVED, organization.getStatus());

        // Try to delete the organization - this should fail
        try {
            organizationsApi.deleteRejectedOrPendingOrganization(organization.getId());
            fail("User cannot delete their approved organization");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_BAD_REQUEST, ex.getCode());
        }

        assertEquals(io.dockstore.openapi.client.model.Organization.StatusEnum.APPROVED, organization.getStatus());
        assertEquals("testname", organization.getName());

        // Try to delete the organization with a user who is not affiliated with the organization - this should fail
        try {
            userOrganizationsApi.deleteRejectedOrPendingOrganization(organization.getId());
            fail("User has no permissions to delete this organization");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            assertEquals(HttpStatus.SC_FORBIDDEN, ex.getCode());
        }

        assertEquals(io.dockstore.openapi.client.model.Organization.StatusEnum.APPROVED, organization.getStatus());
        assertEquals("testname", organization.getName());
    }
}
