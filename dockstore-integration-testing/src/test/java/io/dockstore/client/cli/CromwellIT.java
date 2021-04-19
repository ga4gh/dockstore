/*
 *    Copyright 2018 OICR
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

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.dockstore.common.LanguageHandlerHelper;
import io.dockstore.common.WdlBridge;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import wdl.draft3.parser.WdlParser;

/**
 * This tests integration with the CromWell engine and what will eventually be wdltool.
 *
 * @author dyuen
 */
public class CromwellIT {

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final ExpectedSystemExit exit = ExpectedSystemExit.none();

    @Test
    public void testWDL2Json() {
        File sourceFile = new File(ResourceHelpers.resourceFilePath("wdl.wdl"));
        WdlBridge wdlBridge = new WdlBridge();
        try {
            String inputs = wdlBridge.getParameterFile(sourceFile.getAbsolutePath(), "/wdl.wdl");
            Assert.assertTrue(inputs.contains("three_step.cgrep.pattern"));
        } catch (WdlParser.SyntaxError ex) {
            Assert.fail("There should not be any parsing errors");
        }
    }

    @Test
    public void testWDLResolver() {
        // If resolver works, this should throw no errors
        File sourceFile = new File(ResourceHelpers.resourceFilePath("wdl-sanger-workflow.wdl"));
        WdlBridge wdlBridge = new WdlBridge();
        @SuppressWarnings("checkstyle:IllegalType")
        HashMap<String, String> secondaryFiles = new HashMap<>();
        secondaryFiles.put("wdl.wdl",
            "task ps {\n" + "  command {\n" + "    ps\n" + "  }\n" + "  output {\n" + "    File procs = stdout()\n" + "  }\n" + "}\n" + "\n"
                + "task cgrep {\n" + "  String pattern\n" + "  File in_file\n" + "  command {\n"
                + "    grep '${pattern}' ${in_file} | wc -l\n" + "  }\n" + "  output {\n" + "    Int count = read_int(stdout())\n" + "  }\n"
                + "}\n" + "\n" + "task wc {\n" + "  File in_file\n" + "  command {\n" + "    cat ${in_file} | wc -l\n" + "  }\n"
                + "  output {\n" + "    Int count = read_int(stdout())\n" + "  }\n" + "}\n" + "\n" + "workflow three_step {\n"
                + "  call ps\n" + "  call cgrep {\n" + "    input: in_file=ps.procs\n" + "  }\n" + "  call wc {\n"
                + "    input: in_file=ps.procs\n" + "  }\n" + "}\n");
        wdlBridge.setSecondaryFiles(secondaryFiles);
        try {
            wdlBridge.validateWorkflow(sourceFile.getAbsolutePath(), "/wdl-sanger-workflow.wdl");
        } catch (WdlParser.SyntaxError ex) {
            Assert.fail("Should not fail parsing file");
        }
    }


    /**
     * This tests compatibility with Cromwell 30.2 by converting to JSON (https://github.com/dockstore/dockstore/issues/1211)
     */
    @Test
    public void testWDL2JsonIssue() {
        File sourceFile = new File(ResourceHelpers.resourceFilePath("hello_world.wdl"));
        WdlBridge wdlBridge = new WdlBridge();
        try {
            String inputs = wdlBridge.getParameterFile(sourceFile.getAbsolutePath(), "/hello_world.wdl");
            Assert.assertTrue(inputs.contains("wf.hello_world.hello_input"));
        } catch (WdlParser.SyntaxError ex) {
            Assert.fail("Should properly parse document");
        }
    }

    /**
     * Tests that we can generate a DAG for https://staging.dockstore.org/workflows/github.com/HumanCellAtlas/skylab/Snap-ATAC:gl_576?tab=info
     */
    @Test
    public void testSnapAtacDag() {
        final File file = new File(ResourceHelpers.resourceFilePath("snap_atac.wdl"));
        final WdlBridge wdlBridge = new WdlBridge();
        final Map<String, List<String>> callsToDependencies = wdlBridge.getCallsToDependencies(file.getAbsolutePath(), "snap_atac.wdl");
        Assert.assertEquals(5, callsToDependencies.size());
    }

    @Test
    public void testPathResolver() {
        Assert.assertEquals("/module00a/Module00a.wdl", LanguageHandlerHelper
                .convertRelativePathToAbsolutePath("/GATKSVPipelineClinical.wdl", "module00a/Module00a.wdl"));
        Assert.assertEquals("/a/importA.wdl", LanguageHandlerHelper
                .convertRelativePathToAbsolutePath("/parent/parent.wdl", "../a/importA.wdl"));
    }
}
