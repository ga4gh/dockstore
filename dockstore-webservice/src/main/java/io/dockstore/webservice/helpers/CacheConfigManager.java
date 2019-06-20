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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CacheConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger(CacheConfigManager.class);

    private static CacheConfigManager cacheConfigManager = new CacheConfigManager();

    private static LoadingCache<String, String> installationAccessTokenCache;

    private static String jsonWebToken;

    public static CacheConfigManager getInstance() {
        return cacheConfigManager;
    }

    /**
     * Retrieve the JWT
     * @return JWT string
     */
    public static String getJsonWebToken() {
        return jsonWebToken;
    }

    /**
     * Set the JWT
     * @param token JWT string
     */
    public static void setJsonWebToken(String token) {
        jsonWebToken = token;
    }

    /**
     * Given a JWT for the GitHub app, retrieve the GitHub app installation ID (per repository)
     * @param installationId App installation ID (per repository)
     * @return Installation Access Token
     */
    public String getInstallationAccessTokenFromInstallationId(String installationId) throws Exception {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url("https://api.github.com/app/installations/" + installationId + "/access_tokens")
                .post(RequestBody.create(okhttp3.MediaType.parse(""), "")) // Empty body to appease library
                .addHeader("Accept", "application/vnd.github.machine-man-preview+json")
                .addHeader("Authorization", "Bearer " + jsonWebToken)
                .build();

        try {
            okhttp3.Response response = client.newCall(request).execute();
            JsonElement body = new JsonParser().parse(response.body().string());
            if (body.isJsonObject()) {
                JsonObject responseBody = body.getAsJsonObject();
                JsonElement token = responseBody.get("token");
                if (token.isJsonPrimitive()) {
                    return token.getAsString();
                }
            }
        } catch (IOException ex) {
            throw new Exception("Unable to retrieve installation access token.");
        }

        throw new Exception("Unable to retrieve installation access token.");
    }

    /**
     * Initialize the cache for installation access tokens
     */
    public void initCache() {
        final int maxSize = 100;
        if (installationAccessTokenCache == null) {
            installationAccessTokenCache = CacheBuilder.newBuilder()
                    .maximumSize(maxSize)
                    .expireAfterWrite(1, TimeUnit.HOURS)
                    .recordStats()
                    .build(new CacheLoader<>() {
                        @Override
                        public String load(String installationId) throws Exception {
                            LOG.info("Fetching " + installationId);
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
            LOG.error("Error retrieving token " + ex.getMessage());
        }
        return null;
    }
}
