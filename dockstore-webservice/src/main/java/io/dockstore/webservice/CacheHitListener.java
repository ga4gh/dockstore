package io.dockstore.webservice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.validation.constraints.NotNull;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Response;
import okhttp3.internal.connection.RealCall;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CacheHitListener extends EventListener {

    private static final Logger LOG = LoggerFactory.getLogger(CacheHitListener.class);

    private final String listenerTag;
    private final String username;

    public CacheHitListener(String listenerTag, String username) {
        this.listenerTag = listenerTag;
        this.username = username;
    }

    @Override
    public void cacheConditionalHit(@NotNull Call call, @NotNull Response cachedResponse) {
        /* do nothing, might be useful for debugging rate limit */
    }

    @Override
    public void cacheHit(@NotNull Call call, @NotNull Response response) {
        /* do nothing, might be useful for debugging rate limit */
    }

    @Override
    public void cacheMiss(@NotNull Call call) {
        String endpointCalled = ((RealCall)call).getOriginalRequest().url().toString();
        if (!endpointCalled.contains("rate_limit")) {
            LOG.debug(listenerTag + " cacheMiss for : " + endpointCalled);
            try {
                FileUtils.writeStringToFile(DockstoreWebserviceApplication.CACHE_MISS_LOG_FILE, listenerTag + ',' + username + ',' + endpointCalled + '\n',
                        StandardCharsets.UTF_8, true);
            } catch (IOException e) {
                LOG.error("could not write cache miss to log", e);
            }
        }
    }
}
