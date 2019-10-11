/*
 *    Copyright 2019 OICR
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
package io.dockstore.webservice.helpers.stateListeners;

import java.util.List;

import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.helpers.ElasticMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Future home of code for managing the sitemap
 */
public class SitemapListener implements StateListenerInterface {

    private static final Logger LOGGER = LoggerFactory.getLogger(SitemapListener.class);

    @Override
    public void handleIndexUpdate(Entry entry, ElasticMode command) {
        LOGGER.error("this should update the sitemap for the one new entry");
    }

    @Override
    public void bulkUpsert(List<Entry> entries) {
        LOGGER.error("this should generate a whole new sitemap");
    }
}
