/*
 *    Copyright 2016 OICR
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

package io.dockstore.client.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UpgradeTestIT {
    private ObjectMapper objectMapper;

    @Before
    public void setup() throws IOException{

        this.objectMapper = mock(ObjectMapper.class);
        Client.setObjectMapper(objectMapper);

        ObjectMapper localObjectMapper = new ObjectMapper();

        URL latest = new URL("https://api.github.com/repos/ga4gh/dockstore/releases/latest");
        URL all = new URL("https://api.github.com/repos/ga4gh/dockstore/releases");

        Map map = localObjectMapper.readValue(latest,Map.class);

        ArrayList list = localObjectMapper.readValue(all, ArrayList.class);
        when(objectMapper.readValue(eq(latest),eq(Map.class))).thenReturn(map);
        when(objectMapper.readValue(eq(all),eq(ArrayList.class))).thenReturn(list);
        when(objectMapper.getTypeFactory()).thenReturn(localObjectMapper.getTypeFactory());

        TypeFactory typeFactory = localObjectMapper.getTypeFactory();
        CollectionType ct = typeFactory.constructCollectionType(List.class, Map.class);
        Object mapRel = localObjectMapper.readValue(all, ct);
        when(objectMapper.readValue(eq(all),any(CollectionType.class))).thenReturn(mapRel);

    }

    @Test
    public void upgradeTest() throws IOException {
        //if current is older, upgrade to the most stable version right away
        Client client = new Client();
        String detectedVersion = "0.4-beta.1";
        String currentVersion = "0.3-beta.1";
        String unstable = "0.4-beta.0";
        // assert that the value matches the mocking
        String desiredVersion = client.decideOutput("none", currentVersion,detectedVersion, unstable);
        assert(desiredVersion.equals("0.4-beta.1"));
    }

    @Test
    public void upTestStableOption() throws IOException{
        //if the current is newer and unstable, output "--upgrade-stable" command option
        Client client = new Client();
        String detectedVersion ="0.3-beta.1";  //detectedVersion is the latest stable
        String currentVersion = "0.4-beta.0";   //current is newer and unstable
        String unstable = "0.4-beta.0";
        // assert that the value matches the mocking
        String optCommand = client.decideOutput("none", currentVersion, detectedVersion, unstable);
        assert(optCommand.equals("upgrade-stable"));
    }

    @Test
    public void upTestUnstableOption() throws IOException{
        //else if current is the latest stable version, output "you are currently running the most stable version"
        //         and option to "--upgrade-unstable"
        Client client = new Client();
        String detectedVersion = "0.4-beta.1";
        String currentVersion = "0.4-beta.1";
        String unstable = "0.4-beta.0";
        // assert that the value matches the mocking
        String optCommand = client.decideOutput("none", currentVersion,detectedVersion, unstable);
        assert(optCommand.equals("upgrade-unstable"));
    }

    @Test
    public void upgradeStable() throws IOException {
        //if the current is not latest stable, upgrade to the latest stable version
        Client client = new Client();
        String detectedVersion = "0.4-beta.1";
        String currentVersion = "0.4-beta.0";  //can also be 0.3-beta.1 , as long as it's not latest stable
        String unstable = "0.4-beta.0";
        // assert that the value matches the mocking
        String desiredVersion = client.decideOutput("stable",currentVersion, detectedVersion, unstable);
        assert(desiredVersion.equals("0.4-beta.1"));
    }

    @Test
    public void upgradeStableOption() throws IOException {
        //if the current is latest stable, output option to "--upgrade-unstable"
        Client client = new Client();
        String detectedVersion = "0.4-beta.1";
        String currentVersion = "0.4-beta.1";
        String unstable = "0.4-beta.0";
        // assert that the value matches the mocking
        String optCommand = client.decideOutput("stable",currentVersion, detectedVersion, unstable);
        assert(optCommand.equals("upgrade-unstable"));
    }

    @Test
    public void upgradeUnstable() throws IOException {
        //if the current is not latest unstable, upgrade to the most unstable version
        Client client = new Client();
        String detectedVersion = "0.4-beta.1";
        String currentVersion = "0.3-beta.0";
        String unstable = "0.4-beta.0";
        // assert that the value matches the mocking
        String desiredVersion = client.decideOutput("unstable",currentVersion, detectedVersion, unstable);
        assert(desiredVersion.equals("0.4-beta.0"));
    }

    @Test
    public void upgradeUnstableOption() throws IOException {
        //if the current is latest unstable, output option to "--upgrade-stable"'
        Client client = new Client();
        String detectedVersion = "0.4-beta.1";
        String currentVersion = "0.4-beta.0";
        String unstable = "0.4-beta.0";
        // assert that the value matches the mocking
        String optCommand = client.decideOutput("unstable",currentVersion, detectedVersion, unstable);
        assert(optCommand.equals("upgrade-stable"));
    }
}

