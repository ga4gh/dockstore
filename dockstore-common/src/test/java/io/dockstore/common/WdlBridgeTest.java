package io.dockstore.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;

import io.dropwizard.testing.FixtureHelpers;
import org.junit.Assert;
import org.junit.Test;
import wdl.draft3.parser.WdlParser;
import wom.callable.ExecutableCallable;
import wom.executable.WomBundle;

import static org.junit.Assert.assertEquals;

public class WdlBridgeTest {

    private static final String DOCKER_IMAGES_WDL_10 = FixtureHelpers.fixture("fixtures/dockerImages10.wdl");
    private static final String DOCKER_IMAGES_WDL_PRE_10 = FixtureHelpers.fixture("fixtures/dockerImagesPre10.wdl");

    @Test
    public void testGetCallsToDockerMapWdl10() {
        checkDockerImageReferences(getDockerParameterMap(DOCKER_IMAGES_WDL_10));
    }

    @Test
    public void testGetCallsToDockerMapWdlPre10() {
        checkDockerImageReferences(getDockerParameterMap(DOCKER_IMAGES_WDL_PRE_10));
    }

    private void checkDockerImageReferences(final Map<String, DockerParameter> callsToDockerMap) {
        callsToDockerMap.entrySet().stream().forEach(entry -> {
            if ("dockstore_parmeterizedDocker".equals(entry.getKey())) {
                assertEquals(DockerImageReference.DYNAMIC, entry.getValue().imageReference());
            } else {
                assertEquals(DockerImageReference.LITERAL, entry.getValue().imageReference());
            }
        });
    }

    private Map<String, DockerParameter> getDockerParameterMap(String filePath) {
        final WdlBridge wdlBridge = new WdlBridge();
        final WomBundle bundleFromContent = wdlBridge.getBundleFromContent(filePath, filePath, filePath);
        final ExecutableCallable executableCallable = wdlBridge.convertBundleToExecutableCallable(bundleFromContent);
        final Map<String, DockerParameter> callsToDockerMap = wdlBridge.getCallsToDockerMap(executableCallable);
        return callsToDockerMap;
    }

    @Test
    public void testBooleanMetadata() throws WdlParser.SyntaxError {
        WdlBridge wdlBridge = new WdlBridge();
        File file = new File("src/test/resources/MitocondriaPipeline.wdl");
        String filePath = file.getAbsolutePath();
        String sourceFilePath = "/scripts/mitochondria_m2_wdl/MitochondriaPipeline.wdl";
        ArrayList<Map<String, String>> metadata = wdlBridge.getMetadata(filePath, sourceFilePath);
        // Known number of metadata objects
        final int knownMetadataObjectSize = 4;
        assertEquals("There should be 4 sets of metadata (3 from tasks, 1 from workflow)", knownMetadataObjectSize, metadata.size());
        Assert.assertTrue("The metadata from a task should be gotten", metadata.get(0).containsValue("Removes alignment information while retaining recalibrated base qualities and original alignment tags"));
        Assert.assertTrue("The metadata from the main workflow should be gotten", metadata.get(2).containsValue("Takes in an hg38 bam or cram and outputs VCF of SNP/Indel calls on the mitochondria."));
    }
}
