/*
 *    Copyright 2017 OICR
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
package io.dockstore.webservice.languages;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.dockstore.common.DescriptorLanguage;
import io.dockstore.language.MinimalLanguageInterface;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginWrapper;

import static io.dockstore.common.DescriptorLanguage.FileType;

public final class LanguageHandlerFactory {
    private static Map<DescriptorLanguage, MinimalLanguageInterface> pluginMap = new HashMap<>();
    private static Map<FileType, MinimalLanguageInterface> fileTypeMap = new HashMap<>();

    private LanguageHandlerFactory() {
        // do nothing constructor
    }

    public static void setLanguagePluginManager(DefaultPluginManager manager) {
        // should not have to do this, but starting and restarting webserver in tests does weird things when these static variables carry-over
        pluginMap = new HashMap<>();
        fileTypeMap = new HashMap<>();
        List<PluginWrapper> plugins = manager.getStartedPlugins();
        for (PluginWrapper wrapper : plugins) {
            List<MinimalLanguageInterface> minimalLanguageInterfaces = manager
                .getExtensions(MinimalLanguageInterface.class, wrapper.getPluginId());
            minimalLanguageInterfaces.forEach(inter -> pluginMap.put(inter.getDescriptorLanguage(), inter));
            minimalLanguageInterfaces.forEach(inter -> {
                fileTypeMap.put(inter.getDescriptorLanguage().getFileType(), inter);
                fileTypeMap.put(inter.getDescriptorLanguage().getTestParamType(), inter);
            });
        }
        pluginMap = Collections.unmodifiableMap(pluginMap);
        fileTypeMap = Collections.unmodifiableMap(fileTypeMap);
    }

    public static LanguageHandlerInterface getInterface(DescriptorLanguage type) {

        switch (type) {
        case CWL:
            return new CWLHandler();
        case WDL:
            return new WDLHandler();
        case NEXTFLOW:
            return new NextflowHandler();
        case SERVICE:
            return new LanguagePluginHandler(ServicePrototypePlugin.class);
        default:
            // look through plugin list
            if (pluginMap.containsKey(type)) {
                return new LanguagePluginHandler(pluginMap.get(type).getClass());
            }
            throw new UnsupportedOperationException("language not known");
        }
    }

    public static LanguageHandlerInterface getInterface(FileType type) {
        switch (type) {
        case DOCKSTORE_CWL:
            return new CWLHandler();
        case DOCKSTORE_WDL:
            return new WDLHandler();
        case NEXTFLOW_CONFIG:
            return new NextflowHandler();
        case DOCKSTORE_SERVICE_YML:
            return new LanguagePluginHandler(ServicePrototypePlugin.class);
        default:
            // look through plugin list
            if (fileTypeMap.containsKey(type)) {
                return new LanguagePluginHandler(fileTypeMap.get(type).getClass());
            }
            throw new UnsupportedOperationException("language not known");
        }
    }

    /**
     * Get map of activated plugins
     * @return activated plugins by language
     */
    public static Map<DescriptorLanguage, MinimalLanguageInterface> getPluginMap() {
        return pluginMap;
    }

}
