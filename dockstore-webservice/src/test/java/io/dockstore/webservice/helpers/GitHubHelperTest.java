package io.dockstore.webservice.helpers;

import io.dockstore.webservice.CustomWebApplicationException;
import org.junit.Assert;
import org.junit.Test;

// Ignoring for now, see https://ucsc-cgl.atlassian.net/browse/SEAB-1855
public class GitHubHelperTest {

    private static final String CODE = "abcdefghijklmnop";
    private static final String GITHUB_CLIENT_ID = "123456789abcd";
    private static final String GITHUB_CLIENT_SECRET = "98654321dcba";

    // Invalid GitHub authentication should return 400
    @Test
    public void testGetGitHubAccessToken() {
        try {
            GitHubHelper.getGitHubAccessToken(CODE, GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET);
            Assert.fail("No CustomWebApplicationException thrown");
        } catch (CustomWebApplicationException e) {
            Assert.assertEquals("HTTP 400 Bad Request", e.getMessage());
        }

    }
}
