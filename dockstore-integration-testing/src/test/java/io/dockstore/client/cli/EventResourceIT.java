package io.dockstore.client.cli;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.Registry;
import io.dockstore.common.ToolTest;
import io.dockstore.webservice.jdbi.EventDAO;
import io.dockstore.webservice.resources.EventSearchType;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.ContainertagsApi;
import io.swagger.client.api.EventsApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.Event;
import io.swagger.client.model.StarRequest;
import io.swagger.client.model.Tag;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;

import static io.swagger.client.model.DockstoreTool.ModeEnum.MANUAL_IMAGE_PATH;
import static org.junit.Assert.assertTrue;

/**
 * This test was originally in BasicIT, but it sometimes fails on travis and fails consistently locally if it is run with another test
 * before it. If testRefreshAfterDeletingAVersion() was run before, eventResource fails. But if you comment out the lines that refresh the
 * tool then in testRefresh, the eventResource will pass. Separating out this test for now.
 */
@Category({ ConfidentialTest.class, ToolTest.class })
public class EventResourceIT extends BaseIT {
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate1(SUPPORT);
    }

    @SuppressWarnings("checkstyle:parameternumber")
    private DockstoreTool manualRegisterAndPublish(ContainersApi containersApi, String namespace, String name, String toolName,
        String gitUrl, String cwlPath, String wdlPath, String dockerfilePath, DockstoreTool.RegistryEnum registry, String gitReference,
        String versionName, boolean toPublish, boolean isPrivate, String email, String customDockerPath) {
        DockstoreTool newTool = new DockstoreTool();
        newTool.setNamespace(namespace);
        newTool.setName(name);
        newTool.setToolname(toolName);
        newTool.setDefaultCwlPath(cwlPath);
        newTool.setDefaultWdlPath(wdlPath);
        newTool.setDefaultDockerfilePath(dockerfilePath);
        newTool.setGitUrl(gitUrl);
        newTool.setRegistry(registry);
        newTool.setRegistryString(registry.getValue());
        newTool.setMode(MANUAL_IMAGE_PATH);
        newTool.setPrivateAccess(isPrivate);
        newTool.setToolMaintainerEmail(email);
        if (customDockerPath != null) {
            newTool.setRegistryString(customDockerPath);
        }

        if (!Registry.QUAY_IO.name().equals(registry.name())) {
            Tag tag = new Tag();
            tag.setReference(gitReference);
            tag.setName(versionName);
            tag.setDockerfilePath(dockerfilePath);
            tag.setCwlPath(cwlPath);
            tag.setWdlPath(wdlPath);
            List<Tag> tags = new ArrayList<>();
            tags.add(tag);
            newTool.setWorkflowVersions(tags);
        }

        // Manually register
        DockstoreTool tool = containersApi.registerManual(newTool);

        // Refresh
        tool = containersApi.refresh(tool.getId());

        // Publish
        if (toPublish) {
            tool = containersApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(true));
            assertTrue(tool.isIsPublished());
        }
        return tool;
    }

    @SuppressWarnings("checkstyle:parameternumber")
    private DockstoreTool manualRegisterAndPublish(ContainersApi containersApi, String namespace, String name, String toolName,
            String gitUrl, String cwlPath, String wdlPath, String dockerfilePath, DockstoreTool.RegistryEnum registry, String gitReference,
            String versionName, boolean toPublish) {
        return manualRegisterAndPublish(containersApi, namespace, name, toolName, gitUrl, cwlPath, wdlPath, dockerfilePath, registry,
                gitReference, versionName, toPublish, false, null, null);
    }


    @Test()
    public void eventResourcePaginationTest() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        ContainertagsApi toolTagsApi = new ContainertagsApi(client);

        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "dockerhubandgithub", "regular",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
        EventsApi eventsApi = new EventsApi(client);
        List<Event> events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        Assert.assertTrue("No starred entries, so there should be no events returned in starred entries mode", events.isEmpty());
        events = eventsApi.getEvents(EventSearchType.ALL_STARRED.toString(), 10, 0);
        Assert.assertTrue("No starred entries, so there should be no events returned in the all starred mode", events.isEmpty());


        StarRequest starRequest = new StarRequest();
        starRequest.setStar(true);
        toolsApi.starEntry(tool.getId(), starRequest);
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        Assert.assertTrue("Should not be an event for the non-tag version that was automatically created for the newly registered tool", events.isEmpty());
        // Add and update tag 101 times
        Set<String> randomTagNames = new HashSet<>();

        for (int i = 0; i < EventDAO.MAX_LIMIT + 10; i++) {
            randomTagNames.add(RandomStringUtils.randomAlphanumeric(255));
        }
        randomTagNames.forEach(randomTagName -> {
            List<Tag> randomTags = getRandomTags(randomTagName);
            toolTagsApi.addTags(tool.getId(), randomTags);
        });
        try {
            events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), EventDAO.MAX_LIMIT + 1, 0);
            Assert.fail("Should've failed because it's over the limit");
        } catch (ApiException e) {
            Assert.assertEquals("{\"errors\":[\"query param limit must be less than or equal to " + EventDAO.MAX_LIMIT + "\"]}", e.getMessage());
        }
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), EventDAO.MAX_LIMIT, 0);
        Assert.assertEquals("Should have been able to use the max limit", EventDAO.MAX_LIMIT, events.size());
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), EventDAO.MAX_LIMIT - 10, 0);
        Assert.assertEquals("Should have used a specific limit", EventDAO.MAX_LIMIT  - 10, events.size());
        events.forEach(event -> Assert.assertNotNull(event.getVersion()));
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 1, 0);
        Assert.assertEquals("Should have been able to use the min limit", 1, events.size());
        try {
            events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 0, 0);
            Assert.fail("Should've failed because it's under the limit");
        } catch (ApiException e) {
            Assert.assertEquals("{\"errors\":[\"query param limit must be greater than or equal to 1\"]}", e.getMessage());
        }
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), null, null);
        Assert.assertEquals("Should have used the default limit", 10, events.size());
    }

    private List<Tag> getRandomTags(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setReference("potato");
        tag.setImageId("4728f8f5ce1709ec8b8a5282e274e63de3c67b95f03a519191e6ea675c5d34e8");
        tag.setReferenceType(Tag.ReferenceTypeEnum.TAG);
        List<Tag> tags = new ArrayList<>();
        tags.add(tag);
        return tags;
    }
}
