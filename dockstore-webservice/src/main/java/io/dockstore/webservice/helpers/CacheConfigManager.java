package io.dockstore.webservice.helpers;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.dockstore.webservice.DockstoreWebserviceApplication;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CacheConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger(CacheConfigManager.class);

    private static final CacheConfigManager CACHE_CONFIG_MANAGER = new CacheConfigManager();

    private static LoadingCache<String, String> installationAccessTokenCache;

    private static volatile String jsonWebToken;

    public static CacheConfigManager getInstance() {
        return CACHE_CONFIG_MANAGER;
    }

    /**
     * Set the JWT
     * @param token JWT string
     */
    public static void setJsonWebToken(String token) {
        jsonWebToken = token;
    }

    /**
     * Given a JWT for the GitHub app and an installation ID, retrieve the Installation Access Token
     * @param installationId App installation ID (per user)
     * @return Installation Access Token
     */
    private String getInstallationAccessTokenFromInstallationId(String installationId) throws Exception {
        Request request = new Request.Builder()
                .url("https://api.github.com/app/installations/" + installationId + "/access_tokens")
                .post(RequestBody.create(MediaType.parse(""), "")) // Empty body to appease library
                .addHeader("Accept", "application/vnd.github.machine-man-preview+json")
                .addHeader("Authorization", "Bearer " + jsonWebToken)
                .build();

        String errorMsg = "Unable to retrieve installation access token.";
        try {
            Response response = DockstoreWebserviceApplication.getOkHttpClient().newCall(request).execute();
            JsonElement body = new JsonParser().parse(response.body().string());
            if (body.isJsonObject()) {
                JsonObject responseBody = body.getAsJsonObject();
                if (response.isSuccessful()) {
                    JsonElement token = responseBody.get("token");
                    if (token.isJsonPrimitive()) {
                        return token.getAsString();
                    }
                } else {
                    JsonElement errorMessage = responseBody.get("message");
                    if (errorMessage.isJsonPrimitive()) {
                        errorMsg = errorMessage.getAsString();
                    }
                }
            }
        } catch (IOException ex) {
            LOG.error(errorMsg, ex);
            throw new Exception(errorMsg, ex);
        }

        LOG.error(errorMsg);
        throw new Exception(errorMsg);
    }

    /**
     * Initialize the cache for installation access tokens
     */
    public void initCache() {
        final int maxSize = 100;
        // GitHub token has a 1 hour expiration; leave a generous gap
        // so we don't fetch a token that may expire as we use it
        final int timeOutInMinutes = 58;
        if (installationAccessTokenCache == null) {
            installationAccessTokenCache = CacheBuilder.newBuilder()
                    .maximumSize(maxSize)
                    .expireAfterWrite(timeOutInMinutes, TimeUnit.MINUTES)
                    .recordStats()
                    .build(new CacheLoader<>() {
                        @Override
                        public String load(String installationId) throws Exception {
                            LOG.info("Fetching installation " + installationId + " from cache.");
                            return getInstallationAccessTokenFromInstallationId(installationId);
                        }
                    });
        }
    }

    /**
     * Load installation access token from the cache
     * @param installationId App installation ID (per repository)
     * @return installation access token
     */
    public String getInstallationAccessTokenFromCache(String installationId) {
        try {
            CacheStats cacheStats = installationAccessTokenCache.stats();
            LOG.info(cacheStats.toString());
            return installationAccessTokenCache.get(installationId);
        } catch (Exception ex) {
            LOG.error("Error retrieving token", ex);
        }
        return null;
    }
}
