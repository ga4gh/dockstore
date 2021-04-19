package io.dockstore.webservice.helpers;

import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dropwizard.lifecycle.Managed;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;

public final class  ElasticSearchHelper implements Managed {

    private static RestHighLevelClient restHighLevelClient = null;

    private DockstoreWebserviceConfiguration.ElasticSearchConfig config;

    public ElasticSearchHelper(DockstoreWebserviceConfiguration.ElasticSearchConfig config) {
        this.config = config;
    }

    public static RestClient restClient() {
        return restHighLevelClient().getLowLevelClient();
    }

    public static synchronized RestHighLevelClient restHighLevelClient() {
        return restHighLevelClient;
    }

    private RestClientBuilder builder() {
        if (config == null) {
            throw new IllegalStateException("ElasticSearchHelper.config is not set");
        }
        final RestClientBuilder builder = RestClient.builder(new HttpHost(config.getHostname(), config.getPort(), getProtocol()));
        if (StringUtils.isBlank(config.getUser())) {
            return builder;
        } else {
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(config.getUser(), config.getPassword()));
            return builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                @Override
                public HttpAsyncClientBuilder customizeHttpClient(final HttpAsyncClientBuilder httpAsyncClientBuilder) {
                    return httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }
            });
        }
    }

    private String getProtocol() {
        if (StringUtils.isBlank(config.getProtocol())) {
            return "http";
        } else {
            return config.getProtocol();
        }
    }

    @Override
    public void start() throws Exception {
        if (StringUtils.isNotBlank(config.getHostname())) {
            restHighLevelClient = new RestHighLevelClient(builder());
        }
    }

    @Override
    public void stop() throws Exception {
        if (restHighLevelClient != null) {
            restHighLevelClient.close();
        }
    }
}
