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

import java.util.List;
import java.util.Optional;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.WorkflowTest;
import io.dockstore.webservice.core.dag.ElementsDefinition;
import io.dockstore.webservice.languages.WDLHandler;
import io.swagger.client.ApiException;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.Workflow;
import io.swagger.client.model.WorkflowVersion;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

/**
 * Created by jpatricia on 24/06/16.
 */
@Category({ ConfidentialTest.class, WorkflowTest.class })
public class DAGWorkflowTestIT extends BaseIT {

    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate1(SUPPORT);
    }

    private List<String> getJSON(String repo, String fileName, String descType, String branch) throws ApiException {
        final String testWorkflowName = "test-workflow";
        WorkflowsApi workflowApi = new WorkflowsApi(getWebClient(USER_1_USERNAME, testingPostgres));
        Workflow githubWorkflow = workflowApi.manualRegister("github", repo, fileName, testWorkflowName, descType, "/test.json");

        // This checks if a workflow whose default name was manually registered as test-workflow remains as test-workflow and not null or empty string
        Assert.assertEquals(githubWorkflow.getWorkflowName(), testWorkflowName);

        // Publish github workflow
        Workflow refresh = workflowApi.refresh(githubWorkflow.getId(), false);

        // This checks if a workflow whose default name is test-workflow remains as test-workflow and not null or empty string after refresh
        Assert.assertEquals(refresh.getWorkflowName(), testWorkflowName);

        Optional<WorkflowVersion> master = refresh.getWorkflowVersions().stream().filter(workflow -> workflow.getName().equals(branch))
            .findFirst();

        //getting the dag json string
        String workflowDag = workflowApi.getWorkflowDag(githubWorkflow.getId(), master.get().getId());
        return Lists.newArrayList(workflowDag);
    }

    private int countNodeInJSON(List<String> strings) {
        //count the number of nodes in the DAG json
        int countNode = 0;
        int last = 0;
        String node = "id";
        if (strings.size() > 0) {
            while (last != -1) {
                last = strings.get(0).indexOf(node, last);

                if (last != -1) {
                    countNode++;
                    last += node.length();
                }
            }
        }

        return countNode;
    }

    @Test
    public void testWorkflowDAGCWL() throws ApiException {
        // Input: 1st-workflow.cwl
        // Repo: test_workflow_cwl
        // Branch: master
        // Test: normal cwl workflow DAG
        // Return: JSON with 2 nodes and an edge connecting it (nodes:{{untar},{compile}}, edges:{untar->compile})

        final List<String> strings = getJSON("DockstoreTestUser2/test_workflow_cwl", "/1st-workflow.cwl", "cwl", "master");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have five nodes (including start and end)", countNode, 5);
        Assert.assertTrue("node data should have untar as tool", strings.get(0).contains("untar"));
        Assert.assertTrue("node data should have compile as tool", strings.get(0).contains("compile"));
        Assert.assertTrue("edge should connect untar and compile",
            strings.get(0).contains("\"source\":\"dockstore_untar\",\"target\":\"dockstore_compile\""));

    }

    @Test
    public void testWorkflowDAGCWLRequirementMap() throws ApiException {
        // Input: snaptools_create_snap_file.cwl
        // Repo: SnapTools
        // Branch: feature/docker_cwl_req_in_min
        // Test: normal CWL workflow DAG with requirement map

        final List<String> strings = getJSON("DockstoreTestUser2/SnapTools", "/snaptools_create_snap_file.cwl", DescriptorLanguage.CWL.getShortName(), "feature/docker_cwl_req_in_main");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have eight nodes (including start and end)", 8, countNode);
        Assert.assertTrue("node data should have snaptools_preprocess_reads as tool", strings.get(0).contains("snaptools_preprocess_reads"));
        Assert.assertTrue("node data should have snaptools_create_ref_genome_size_file as tool", strings.get(0).contains("snaptools_create_ref_genome_size_file"));
        Assert.assertTrue("edge should connect snaptools_preprocess_reads and snaptools_create_ref_genome_size_file",
                strings.get(0).contains("\"source\":\"dockstore_snaptools_create_ref_genome_size_file\",\"target\":\"dockstore_snaptools_preprocess_reads\""));

    }

    @Test
    public void testWorkflowDAGWDLSingleNode() throws ApiException {
        // Input: hello.wdl
        // Repo: test_workflow_wdl
        // Branch: master
        // Test: normal wdl workflow DAG, three nodes
        // Return: JSON with a three nodes and two edges

        final List<String> strings = getJSON("DockstoreTestUser2/test_workflow_wdl", "/hello.wdl", "wdl", "master");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have four nodes (including start and end)", countNode, 4);
        Assert.assertTrue("node data should have hello as task", strings.get(0).contains("hello"));
        Assert.assertTrue("should have first edge",
            strings.get(0).contains("{\"data\":{\"source\":\"UniqueBeginKey\",\"target\":\"dockstore_hello\"}}"));
        Assert.assertTrue("should have second edge",
            strings.get(0).contains("{\"data\":{\"source\":\"UniqueBeginKey\",\"target\":\"dockstore_helper\"}}"));
        Assert.assertTrue("should have third edge",
            strings.get(0).contains("{\"data\":{\"source\":\"dockstore_helper\",\"target\":\"UniqueEndKey\"}}"));
        Assert.assertTrue("should have fourth edge",
            strings.get(0).contains("{\"data\":{\"source\":\"dockstore_hello\",\"target\":\"UniqueEndKey\"}}"));

    }

    @Test
    public void testWorkflowDAGWDLMultipleNodes() throws ApiException {
        // Input: hello.wdl
        // Repo: hello-dockstore-workflow
        // Branch: master
        // Test: normal wdl workflow DAG, multiple nodes
        // Return: JSON with 5 nodes node and five edges

        final List<String> strings = getJSON("DockstoreTestUser2/hello-dockstore-workflow", "/Dockstore.wdl", "wdl", "testWDL");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have five nodes (including start and end)", countNode, 5);
        Assert.assertTrue("node data should have ps as tool", strings.get(0).contains("ps"));
        Assert.assertTrue("node data should have cgrep as tool", strings.get(0).contains("cgrep"));
        Assert.assertTrue("node data should have wc as tool", strings.get(0).contains("wc"));
        Assert.assertTrue("should have first edge",
            strings.get(0).contains("{\"data\":{\"source\":\"UniqueBeginKey\",\"target\":\"dockstore_ps\"}}"));
        Assert.assertTrue("should have second edge",
            strings.get(0).contains("{\"data\":{\"source\":\"dockstore_ps\",\"target\":\"dockstore_cgrep\"}}"));
        Assert.assertTrue("should have third edge",
            strings.get(0).contains("{\"data\":{\"source\":\"dockstore_ps\",\"target\":\"dockstore_wc\"}}"));
        Assert.assertTrue("should have fourth edge",
            strings.get(0).contains("{\"data\":{\"source\":\"dockstore_wc\",\"target\":\"UniqueEndKey\"}}"));
        Assert.assertTrue("should have fifth edge",
            strings.get(0).contains("{\"data\":{\"source\":\"dockstore_cgrep\",\"target\":\"UniqueEndKey\"}}"));

    }

    @Test
    public void testWorkflowDAGCWLMissingTool() throws ApiException {
        // Input: Dockstore.cwl
        // Repo: hello-dockstore-workflow
        // Branch: testCWL
        // Test: Repo does not have required tool files called by workflow file
        // Return: JSON not blank, but it will have empty nodes and edges (nodes:{},edges:{})

        final List<String> strings = getJSON("DockstoreTestUser2/hello-dockstore-workflow", "/Dockstore.cwl", "cwl", "testCWL");

        //JSON will have node:[] and edges:[]
        Assert.assertEquals("JSON should not have any data for nodes and edges", strings.size(), 1);
    }

    @Test
    @Ignore("This test will fail as long as we are not using validation on WDL workflows and are assuming that if the file exists it is valid")
    public void testWorkflowDAGWDLMissingTask() throws ApiException {
        // Input: hello.wdl
        // Repo: test_workflow_wdl
        // Branch: missing_docker
        // Test: task called by workflow not found in the file
        // Return: blank JSON

        final List<String> strings = getJSON("DockstoreTestUser2/test_workflow_wdl", "/hello.wdl", "wdl", "missing_docker");

        //JSON will have no content at all
        Assert.assertEquals("JSON should be blank", strings.size(), 0);
    }

    @Test
    public void testDAGImportSyntax() throws ApiException {
        // Input: Dockstore.cwl
        // Repo: dockstore-whalesay-imports
        // Branch: update-to-valid-cwl
        // Test: "run: {import:.....}"
        // Return: DAG with two nodes and an edge connecting it (nodes:{{rev},{sorted}}, edges:{rev->sorted})

        final List<String> strings = getJSON("DockstoreTestUser2/dockstore-whalesay-imports", "/Dockstore.cwl", "cwl", "update-to-valid-cwl");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have four nodes (including start and end)", countNode, 4);
        Assert.assertTrue("node data should have untar as tool", strings.get(0).contains("untar"));
        Assert.assertTrue("node data should have compile as tool", strings.get(0).contains("compile"));
        Assert.assertTrue("edge should connect untar and compile",
            strings.get(0).contains("\"source\":\"dockstore_untar\",\"target\":\"dockstore_compile\""));
    }

    @Test
    public void testDAGCWL1Syntax() throws ApiException {
        // Input: preprocess_vcf.cwl
        // Repo: OxoG-Dockstore-Tools
        // Branch: develop
        // Test: "[pass_filter -> [inputs: ...., outputs: ....]] instead of [id->pass_filter,inputs->....]"
        // Return: DAG with 17 nodes

        final List<String> strings = getJSON("DockstoreTestUser2/OxoG-Dockstore-Tools", "/preprocess_vcf.cwl", "cwl", "develop");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have 19 nodes", countNode, 19);
        Assert.assertTrue("node data should have pass_filter as tool", strings.get(0).contains("pass_filter"));
        Assert.assertTrue("node data should have merge_vcfs as tool", strings.get(0).contains("merge_vcfs"));
    }

    @Test
    public void testHintsExpressionTool() throws ApiException {
        // Input: preprocess_vcf.cwl
        // Repo: OxoG-Dockstore-Tools
        // Branch: hints_ExpressionTool
        // Test: "filter has a docker requirement inside expression Tool, linked to ubuntu"
        // Return: DAG with 19 nodes

        final List<String> strings = getJSON("DockstoreTestUser2/OxoG-Dockstore-Tools", "/preprocess_vcf.cwl", "cwl",
            "hints_ExpressionTool");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have 19 nodes", countNode, 19);
        Assert.assertTrue("should have end with gather_sanger_indels and merge_vcfs",
            strings.get(0).contains("\"source\":\"dockstore_gather_sanger_indels\",\"target\":\"dockstore_merge_vcfs\""));
        Assert.assertTrue("should have end with filter and normalize",
            strings.get(0).contains("\"source\":\"dockstore_filter\",\"target\":\"dockstore_normalize\""));
        Assert.assertTrue("should have docker requirement for vcf_merge", strings.get(0).contains(
            "\"name\":\"merge_vcfs\",\"run\":\"vcf_merge.cwl\",\"id\":\"dockstore_merge_vcfs\",\"type\":\"tool\",\"tool\":\"https://hub.docker.com/r/pancancer/pcawg-oxog-tools\",\"docker\":\"pancancer/pcawg-oxog-tools\""));
        Assert.assertTrue("should have docker requirement for clean", strings.get(0).contains(
            "\"name\":\"clean\",\"run\":\"clean_vcf.cwl\",\"id\":\"dockstore_clean\",\"type\":\"tool\",\"tool\":\"https://hub.docker.com/r/pancancer/pcawg-oxog-tools\",\"docker\":\"pancancer/pcawg-oxog-tools:1.0.0\""));
    }

    /**
     * This tests the NCI-GDC DNASeq workflow. This workflow has a huge dag and about 30+ files. It also has secondary, tertiary, etc imports.\
     * This also tests that absolute paths are correctly used for imported files.
     *
     * @throws ApiException
     */
    @Test
    public void testHugeWorkflowWithManyImports() throws ApiException {
        // Input: /workflows/dnaseq/transform.cwl
        // Repo: gdc-dnaseq-cwl
        // Branch: master
        // Return: DAG with 110 nodes

        final List<String> strings = getJSON("DockstoreTestUser2/gdc-dnaseq-cwl", "/workflows/dnaseq/transform.cwl", "cwl", "test");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have 110 nodes", countNode, 110);
    }

    /**
     * This tests the CWL Gene Prioritization workflow. The getSteps function returns an array instead of the previously assumed object. This has been fixed,
     * and this is testing that it is fixed.
     *
     * @throws ApiException
     */
    @Test
    public void testGetStepsArrayInsteadOfObject() throws ApiException {
        // Input: /gp_workflow.cwl
        // Repo: cwl-gene-prioritization
        // Branch: master
        // Return: DAG with 5 nodes

        final List<String> strings = getJSON("DockstoreTestUser2/cwl-gene-prioritization", "/gp_workflow.cwl", "cwl", "test");
        int countNode = countNodeInJSON(strings);

        Assert.assertTrue("JSON should not be blank", strings.size() > 0);
        Assert.assertEquals("JSON should have 5 nodes", countNode, 5);
    }

    /**
     * This tests that a WDL workflow with complex imports is properly imported (also tests absolute paths)
     *
     * Note: With fix for https://github.com/dockstore/dockstore/issues/2931, this WDL now fails. Adjusting test accordingly. If you run
     * womtool validate from the command line, this workflow fails to validate, so our code was correctly validating this WDL before,
     * that seems to have been incorrect.
     *
     * @throws ApiException
     */
    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    @Test
    public void testComplexImportWdlWorkflow() throws ApiException {
        // Input: /parent/parent.wdl
        // Repo: ComplexImportsWdl
        // Branch: master
        // Return: DAG with 7 nodes

        try {
            getJSON("DockstoreTestUser2/ComplexImportsWdl", "/parent/parent.wdl", "wdl", "test");
            fail("Invalid WDL somehow came back good");
        } catch (Exception ex) {
            assertThat(ex.getMessage()).contains(WDLHandler.WDL_PARSE_ERROR);
        }
    }

    @Test
    public void testReallyComplexImportedWdlWorkflow() throws ApiException {
        final List<String> strings = getJSON("dockstore-testing/gatk-sv-clinical", "/GATKSVPipelineClinical.wdl", "wdl", "dockstore-test");
        Assert.assertEquals(1, strings.size());
        final Gson gson = new Gson();
        final ElementsDefinition dag = gson.fromJson(strings.get(0), ElementsDefinition.class);
        Assert.assertEquals("Dag should have 229 nodes", 229, dag.nodes.size());

        // Locally it always comes back as 390. On Travis, it sometimes comes back with 391. Have not been able narrow down
        // the issue, so for now do an less than ideal test for a range.
        Assert.assertTrue("Dag should have between 385 and 395 edges", dag.edges.size() >= 385 && dag.edges.size() <= 395);
    }

}
