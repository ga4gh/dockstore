package io.dockstore.client.cli;

import java.util.ArrayList;
import java.util.List;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.openapi.client.ApiClient;
import io.dockstore.openapi.client.ApiException;
import io.dockstore.openapi.client.api.CloudInstancesApi;
import io.dockstore.openapi.client.api.UsersApi;
import io.dockstore.openapi.client.model.CloudInstance;
import io.dockstore.openapi.client.model.User;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;

public class CloudInstanceIT extends BaseIT {
    public static final CloudInstance.PartnerEnum MODIFIED_MEMBER_PARTNER_1 = CloudInstance.PartnerEnum.DNA_STACK;
    public static final CloudInstance.PartnerEnum MEMBER_PARTNER_2 = CloudInstance.PartnerEnum.DNA_NEXUS;
    public static final CloudInstance.PartnerEnum MODIFIED_ADMIN_PARTNER_1 = CloudInstance.PartnerEnum.CGC;
    public static final CloudInstance.PartnerEnum ADMIN_PARTNER_2 = CloudInstance.PartnerEnum.ANVIL;
    public static final CloudInstance.PartnerEnum MEMBER_PARTNER_1 = CloudInstance.PartnerEnum.GALAXY;
    public static final CloudInstance.PartnerEnum ADMIN_PARTNER_1 = CloudInstance.PartnerEnum.TERRA;
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.addAdditionalToolsWithPrivate2(SUPPORT, false);
    }

    @Test
    public void cloudInstanceResourceTest() {
        ApiClient adminApiClient = getOpenAPIWebClient(ADMIN_USERNAME, testingPostgres);
        ApiClient memberApiClient = getOpenAPIWebClient(OTHER_USERNAME, testingPostgres);
        ApiClient anonymousApiClient = getAnonymousOpenAPIWebClient();
        CloudInstancesApi adminCloudInstancesApi = new CloudInstancesApi(adminApiClient);
        CloudInstancesApi memberCloudInstancesApi = new CloudInstancesApi(memberApiClient);
        CloudInstancesApi anonymousCloudInstancesApi = new CloudInstancesApi(anonymousApiClient);
        List<CloudInstance> adminCloudInstances = adminCloudInstancesApi.getCloudInstances();
        List<CloudInstance> memberCloudInstances = memberCloudInstancesApi.getCloudInstances();
        List<CloudInstance> anonymousCloudInstances = anonymousCloudInstancesApi.getCloudInstances();
        Assert.assertEquals(0, adminCloudInstances.size());
        Assert.assertEquals(0, memberCloudInstances.size());
        Assert.assertEquals(0, anonymousCloudInstances.size());
        CloudInstance newCloudInstance = new CloudInstance();
        // This should not do anything
        Long ignoredId = 9001L;
        newCloudInstance.setId(ignoredId);
        newCloudInstance.setPartner(CloudInstance.PartnerEnum.DNA_STACK);
        newCloudInstance.setUrl("www.google.ca");
        newCloudInstance.setSupportsFileImports(null);
        newCloudInstance.setSupportsHttpImports(null);
        newCloudInstance.setSupportedLanguages(new ArrayList<>());
        try {
            anonymousCloudInstancesApi.postCloudInstance(newCloudInstance);
            Assert.fail("Only admins can create a new cloud instance");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, e.getCode());
        }

        try {
            memberCloudInstancesApi.postCloudInstance(newCloudInstance);
            Assert.fail("Only admins can create a new cloud instance");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_FORBIDDEN, e.getCode());
        }
        adminCloudInstancesApi.postCloudInstance(newCloudInstance);
        adminCloudInstances = adminCloudInstancesApi.getCloudInstances();
        memberCloudInstances = memberCloudInstancesApi.getCloudInstances();
        anonymousCloudInstances = anonymousCloudInstancesApi.getCloudInstances();
        Assert.assertEquals(1, adminCloudInstances.size());
        Assert.assertEquals(1, memberCloudInstances.size());
        Assert.assertEquals(1, anonymousCloudInstances.size());
        Long dnaNexusId = anonymousCloudInstances.get(0).getId();
        Assert.assertNotEquals("Should have ignored the ID passed in", ignoredId, dnaNexusId);
        newCloudInstance.setPartner(CloudInstance.PartnerEnum.DNA_NEXUS);
        newCloudInstance.setUrl("www.google.com");
        adminCloudInstancesApi.postCloudInstance(newCloudInstance);
        adminCloudInstances = adminCloudInstancesApi.getCloudInstances();
        memberCloudInstances = memberCloudInstancesApi.getCloudInstances();
        anonymousCloudInstances = anonymousCloudInstancesApi.getCloudInstances();
        Assert.assertEquals(2, adminCloudInstances.size());
        Assert.assertEquals(2, memberCloudInstances.size());
        Assert.assertEquals(2, anonymousCloudInstances.size());
        try {
            anonymousCloudInstancesApi.deleteCloudInstance(dnaNexusId);
            Assert.fail("Only admins can create a new cloud instance");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, e.getCode());
        }

        try {
            memberCloudInstancesApi.deleteCloudInstance(dnaNexusId);
            Assert.fail("Only admins can create a new cloud instance");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_FORBIDDEN, e.getCode());
        }
        adminCloudInstancesApi.deleteCloudInstance(dnaNexusId);
        adminCloudInstances = adminCloudInstancesApi.getCloudInstances();
        memberCloudInstances = memberCloudInstancesApi.getCloudInstances();
        anonymousCloudInstances = anonymousCloudInstancesApi.getCloudInstances();
        Assert.assertEquals(1, adminCloudInstances.size());
        Assert.assertEquals(1, memberCloudInstances.size());
        Assert.assertEquals(1, anonymousCloudInstances.size());
        Assert.assertEquals("The DNAstack cloud instance should be deleted, not DNAnexus", CloudInstance.PartnerEnum.DNA_NEXUS, anonymousCloudInstances.get(0).getPartner());

        testCloudInstancesInUserResource(adminApiClient, memberApiClient, anonymousApiClient, newCloudInstance);

        List<CloudInstance> cloudInstances = anonymousCloudInstancesApi.getCloudInstances();
        Assert.assertEquals("After all the user cloud instance tests, public cloud instances should remain unchanged", 1,
                cloudInstances.size());
    }

    private void testCloudInstancesInUserResource(ApiClient adminApiClient, ApiClient memberApiClient, ApiClient anonymousApiClient,
            CloudInstance newCloudInstance) {
        UsersApi adminUsersApi = new UsersApi(adminApiClient);
        UsersApi memberUsersApi = new UsersApi(memberApiClient);
        UsersApi anonymousUsersApi = new UsersApi(anonymousApiClient);

        // Check get works
        User adminUser = adminUsersApi.getUser();
        Long adminUserId = adminUser.getId();
        List<CloudInstance> adminUserCloudInstances = adminUsersApi.getUserCloudInstances(adminUserId);
        Assert.assertEquals(0, adminUserCloudInstances.size());
        User memberUser = memberUsersApi.getUser();
        Long memberUserId = memberUser.getId();
        List<CloudInstance> memberUserCloudInstances = memberUsersApi.getUserCloudInstances(memberUserId);
        Assert.assertEquals(0, memberUserCloudInstances.size());
        anonymousUserCannotDoAnything(anonymousUsersApi);

        try {
            memberUsersApi.getUserCloudInstances(adminUserId);
            Assert.fail("Should not be able to get a different user's cloud instances");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_FORBIDDEN, e.getCode());
        }
        memberUserCloudInstances = adminUsersApi.getUserCloudInstances(memberUserId);
        Assert.assertEquals("Admin can still get a different user's cloud instance", 0, memberUserCloudInstances.size());

        // Check post works
        newCloudInstance.setPartner(ADMIN_PARTNER_1);
        adminUsersApi.postUserCloudInstance(newCloudInstance, adminUserId);
        newCloudInstance.setPartner(MEMBER_PARTNER_1);
        memberUsersApi.postUserCloudInstance(newCloudInstance, memberUserId);
        newCloudInstance.setPartner(ADMIN_PARTNER_2);
        adminUsersApi.postUserCloudInstance(newCloudInstance, adminUserId);
        newCloudInstance.setPartner(MEMBER_PARTNER_2);
        memberUsersApi.postUserCloudInstance(newCloudInstance, memberUserId);
        memberUserCloudInstances = memberUsersApi.getUserCloudInstances(memberUserId);
        adminUserCloudInstances = adminUsersApi.getUserCloudInstances(adminUserId);
        Assert.assertEquals(2, memberUserCloudInstances.size());
        Long memberPartner1Id = memberUserCloudInstances.stream()
                .filter(cloudInstance -> cloudInstance.getPartner().equals(MEMBER_PARTNER_1)).findFirst().get().getId();
        Long memberPartner2Id = memberUserCloudInstances.stream()
                .filter(cloudInstance -> cloudInstance.getPartner().equals(MEMBER_PARTNER_2)).findFirst().get().getId();
        Assert.assertEquals(2, adminUserCloudInstances.size());
        Long adminPartner1Id = adminUserCloudInstances.stream().filter(cloudInstance -> cloudInstance.getPartner().equals(ADMIN_PARTNER_1))
                .findFirst().get().getId();
        Long adminPartner2Id = adminUserCloudInstances.stream().filter(cloudInstance -> cloudInstance.getPartner().equals(ADMIN_PARTNER_2))
                .findFirst().get().getId();
        try {
            memberUsersApi.postUserCloudInstance(newCloudInstance, adminUserId);
            Assert.fail("Should not be create a cloud instances on a different user");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_FORBIDDEN, e.getCode());
        }

        // Check put works
        newCloudInstance.setPartner(MODIFIED_MEMBER_PARTNER_1);
        memberUsersApi.putUserCloudInstance(newCloudInstance, memberUserId, memberPartner1Id);
        memberUserCloudInstances = memberUsersApi.getUserCloudInstances(memberUserId);
        newCloudInstance.setPartner(MODIFIED_ADMIN_PARTNER_1);
        adminUsersApi.putUserCloudInstance(newCloudInstance, adminUserId, adminPartner1Id);
        adminUserCloudInstances = adminUsersApi.getUserCloudInstances(adminUserId);
        try {
            memberUsersApi.putUserCloudInstance(newCloudInstance, adminUserId, memberPartner1Id);
            Assert.fail("Should not be update a cloud instances on a different user");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_FORBIDDEN, e.getCode());
        }
        try {
            memberUsersApi.putUserCloudInstance(newCloudInstance, memberUserId, 9001L);
            Assert.fail("Should not be update a cloud instances that doesn't exist");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_NOT_FOUND, e.getCode());
        }
        Assert.assertEquals(2, memberUserCloudInstances.size());
        memberPartner1Id = memberUserCloudInstances.stream()
                .filter(cloudInstance -> cloudInstance.getPartner().equals(MODIFIED_MEMBER_PARTNER_1)).findFirst().get().getId();
        memberPartner2Id = memberUserCloudInstances.stream().filter(cloudInstance -> cloudInstance.getPartner().equals(MEMBER_PARTNER_2))
                .findFirst().get().getId();
        Assert.assertEquals(2, adminUserCloudInstances.size());
        adminPartner1Id = adminUserCloudInstances.stream()
                .filter(cloudInstance -> cloudInstance.getPartner().equals(MODIFIED_ADMIN_PARTNER_1)).findFirst().get().getId();
        adminPartner2Id = adminUserCloudInstances.stream().filter(cloudInstance -> cloudInstance.getPartner().equals(ADMIN_PARTNER_2))
                .findFirst().get().getId();

        // Check delete works
        adminUsersApi.deleteUserCloudInstance(adminUserId, adminPartner1Id);
        memberUsersApi.deleteUserCloudInstance(memberUserId, memberPartner1Id);
        try {
            memberUsersApi.deleteUserCloudInstance(adminUserId, adminPartner1Id);
            Assert.fail("Should not be delete a cloud instances on a different user");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_FORBIDDEN, e.getCode());
        }
        try {
            memberUsersApi.deleteUserCloudInstance(memberUserId, 9001L);
            Assert.fail("Should not be delete a cloud instances that doesn't exist");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_NOT_FOUND, e.getCode());
        }
        adminUserCloudInstances = adminUsersApi.getUserCloudInstances(adminUserId);
        Assert.assertEquals(1, adminUserCloudInstances.size());
        Assert.assertEquals(ADMIN_PARTNER_2, adminUserCloudInstances.get(0).getPartner());
        Assert.assertEquals(adminPartner2Id, adminUserCloudInstances.get(0).getId());
        memberUserCloudInstances = memberUsersApi.getUserCloudInstances(memberUserId);
        Assert.assertEquals(1, memberUserCloudInstances.size());
        Assert.assertEquals(MEMBER_PARTNER_2, memberUserCloudInstances.get(0).getPartner());
        Assert.assertEquals(memberPartner2Id, memberUserCloudInstances.get(0).getId());
    }

    private void anonymousUserCannotDoAnything(UsersApi anonymousUsersApi) {
        try {
            anonymousUsersApi.getUserCloudInstances(1L);
            Assert.fail("Should not be able to get a different user's cloud instances");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, e.getCode());
        }
        try {
            anonymousUsersApi.postUserCloudInstance(new CloudInstance(), 1L);
            Assert.fail("Should not be able to get a different user's cloud instances");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, e.getCode());
        }
        try {
            anonymousUsersApi.putUserCloudInstance(new CloudInstance(), 1L, 1L);
            Assert.fail("Should not be able to get a different user's cloud instances");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, e.getCode());
        }
        try {
            anonymousUsersApi.deleteUserCloudInstance(1L, 1L);
            Assert.fail("Should not be able to get a different user's cloud instances");
        } catch (ApiException e) {
            Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, e.getCode());
        }
    }
}
