/*
 *    Copyright 2020 OICR
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
package io.dockstore.common.yaml;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.dropwizard.testing.FixtureHelpers;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DockstoreYamlTest {
    private static final String DOCKSTORE10_YAML = FixtureHelpers.fixture("fixtures/dockstore10.yml");
    private static final String DOCKSTORE11_YAML = FixtureHelpers.fixture("fixtures/dockstore11.yml");
    private static final String DOCKSTORE12_YAML = FixtureHelpers.fixture("fixtures/dockstore12.yml");
    private static final String DOCKSTORE_GALAXY_YAML = FixtureHelpers.fixture("fixtures/dockstoreGalaxy.yml");

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Test
    public void testFindVersion() {
        assertTrue(DockstoreYamlHelper.findValidVersion("abc").isEmpty());
        assertTrue(DockstoreYamlHelper.findValidVersion("service: 1.0 garbage").isEmpty());
        assertTrue(DockstoreYamlHelper.findValidVersion("#service: 1.0").isEmpty());
        assertEquals(DockstoreYamlHelper.Version.ONE_ZERO, DockstoreYamlHelper.findValidVersion("version: 1.0").get());
        assertEquals(DockstoreYamlHelper.Version.ONE_ZERO, DockstoreYamlHelper.findValidVersion("#Comment\nversion: 1.0").get());
        assertEquals(DockstoreYamlHelper.Version.ONE_ONE, DockstoreYamlHelper.findValidVersion("#Comment\nversion: 1.1").get());
        assertEquals(DockstoreYamlHelper.Version.ONE_TWO, DockstoreYamlHelper.findValidVersion("#Comment\nversion: 1.2").get());
        assertTrue("1.3", DockstoreYamlHelper.findValidVersion("#Comment\nversion: 1.3").isEmpty());
        assertEquals(DockstoreYamlHelper.Version.ONE_ZERO, DockstoreYamlHelper.findValidVersion(DOCKSTORE10_YAML).get());
    }

    @Test
    public void testReadDockstore10Yaml() throws DockstoreYamlHelper.DockstoreYamlException {
        final DockstoreYaml10 dockstoreYaml = (DockstoreYaml10)DockstoreYamlHelper.readDockstoreYaml(DOCKSTORE10_YAML);
        assertEquals("SmartSeq2SingleSample.wdl", dockstoreYaml.primaryDescriptor);
    }

    @Test
    public void testReadDockstore11Yaml() throws DockstoreYamlHelper.DockstoreYamlException {
        final DockstoreYaml dockstoreYaml = DockstoreYamlHelper.readDockstoreYaml(DOCKSTORE11_YAML);
        assertTrue(dockstoreYaml.getClass() == DockstoreYaml11.class);
        DockstoreYaml11 dockstoreYaml11 = (DockstoreYaml11)dockstoreYaml;
        assertEquals("7222", dockstoreYaml11.getService().getEnvironment().get("httpPort").getDefault());
        final YamlService11.DataSet dataset1 = dockstoreYaml11.getService().getData().get("dataset_1");
        assertEquals("xena/files", dataset1.getTargetDirectory());
        final YamlService11.FileDesc tsv = dataset1.getFiles().get("tsv");
        assertEquals("Data for Xena in TSV format", tsv.getDescription());
    }

    @Test
    public void testReadDockstoreYaml12() throws DockstoreYamlHelper.DockstoreYamlException {
        final DockstoreYaml12 dockstoreYaml = (DockstoreYaml12)DockstoreYamlHelper.readDockstoreYaml(DOCKSTORE12_YAML);
        final List<YamlWorkflow> workflows = dockstoreYaml.getWorkflows();
        assertEquals(3, workflows.size());
        final Optional<YamlWorkflow> workflowFoobar = workflows.stream().filter(w -> "foobar".equals(w.getName())).findFirst();
        if (!workflowFoobar.isPresent()) {
            fail("Could not find workflow foobar");
        }

        YamlWorkflow workflow = workflowFoobar.get();
        assertTrue(workflow.getPublish());
        assertEquals("wdl", workflow.getSubclass());
        assertEquals("/Dockstore2.wdl", workflow.getPrimaryDescriptorPath());
        final List<String> testParameterFiles = workflow.getTestParameterFiles();
        assertEquals(1, testParameterFiles.size());
        assertEquals("/dockstore.wdl.json", testParameterFiles.get(0));
        final Filters filters = workflow.getFilters();
        final List<String> branches = filters.getBranches();
        assertEquals(1, branches.size());
        assertEquals("develop", branches.get(0));
        final List<String> tags = filters.getTags();
        assertEquals(1, tags.size());
        assertEquals("gwas*", tags.get(0));

        workflow = workflows.get(1);
        assertFalse(workflow.getPublish());
        workflow = workflows.get(2);
        assertNull(workflow.getPublish());

        final Service12 service = dockstoreYaml.getService();
        assertNotNull(service);
        assertTrue(service.getPublish());
    }

    @Test
    public void testOptionalName() throws DockstoreYamlHelper.DockstoreYamlException {
        final DockstoreYaml12 dockstoreYaml12 = DockstoreYamlHelper.readAsDockstoreYaml12(DOCKSTORE12_YAML);
        final List<YamlWorkflow> workflows = dockstoreYaml12.getWorkflows();
        assertEquals(3, workflows.size());
        assertEquals("Expecting two workflows with names", 2L, workflows.stream().filter(w -> w.getName() != null).count());
        assertEquals("Expecting one workflow without a name", 1L, workflows.stream().filter(w -> w.getName() == null).count());
    }

    @Test
    public void testMissingPrimaryDescriptor() {
        try {
            final String content = DOCKSTORE10_YAML.replaceFirst("(?m)^primaryDescriptor.*$", "");
            DockstoreYamlHelper.readDockstoreYaml(content);
            fail("Invalid dockstore.yml not caught");
        } catch (DockstoreYamlHelper.DockstoreYamlException e) {
            System.out.println(e.getMessage());
        }
    }

    @Test
    public void testInvalidSubclass() {
        final String content = DOCKSTORE12_YAML.replace("DOCKER_COMPOSE", "invalid sub class");
        try {
            DockstoreYamlHelper.readDockstoreYaml(content);
            fail("Did not catch invalid subclass");
        } catch (DockstoreYamlHelper.DockstoreYamlException e) {
            assertTrue(e.getMessage().startsWith(DockstoreYamlHelper.ERROR_READING_DOCKSTORE_YML));
        }
    }

    @Test
    public void testRead11As12() throws DockstoreYamlHelper.DockstoreYamlException {
        final DockstoreYaml12 dockstoreYaml12 = DockstoreYamlHelper.readAsDockstoreYaml12(DOCKSTORE11_YAML);
        assertEquals(0, dockstoreYaml12.getWorkflows().size());
        assertNotNull(dockstoreYaml12.getService());
    }

    @Test
    public void testEmptyDockstore12() {
        try {
            DockstoreYamlHelper.readAsDockstoreYaml12("version: 1.2");
            fail("Dockstore yaml with no services and no workflows should fail");
        } catch (DockstoreYamlHelper.DockstoreYamlException e) {
            assertEquals(ValidDockstore12.AT_LEAST_1_WORKFLOW_OR_SERVICE, e.getMessage());
        }
    }

    @Test
    public void testMalformedDockstoreYaml() throws IOException {
        final String spec = "https://raw.githubusercontent.com/denis-yuen/test-malformed-app/c43103f4004241cb738280e54047203a7568a337/"
                + ".dockstore.yml";
        final String content = IOUtils.toString(new URL(spec), StandardCharsets.UTF_8);
        try {
            DockstoreYamlHelper.readAsDockstoreYaml12(content);
            fail("expected malformed dockstore.yml to fail");
        } catch (DockstoreYamlHelper.DockstoreYamlException e) {
            assertEquals(DockstoreYamlHelper.DOCKSTORE_YML_MISSING_VALID_VERSION, e.getMessage());
        }
    }

    @Test
    public void testMissingSubclass()  {
        // Replace:
        // ...
        // - subclass: DOCKER_COMPOSE
        //   name: UCSC...
        //
        // With:
        // ...
        // - name: UCSC...
        final String content = DOCKSTORE12_YAML.replaceFirst("(?m)^\\s*- subclass.*\\n", "").replaceFirst("    name: U", "  - name: U");
        try {
            DockstoreYamlHelper.readAsDockstoreYaml12(content);
        } catch (DockstoreYamlHelper.DockstoreYamlException e) {
            assertEquals(Service12.MISSING_SUBCLASS, e.getMessage());
        }
    }

    @Test
    public void testWrongKeys() {
        final String content = DOCKSTORE_GALAXY_YAML;
        try {
            DockstoreYamlHelper.readAsDockstoreYaml12(content);
        } catch (DockstoreYamlHelper.DockstoreYamlException ex) {
            Assert.fail("Should be able to parse correctly");
        }

        final String workflowsKey = DOCKSTORE_GALAXY_YAML.replaceFirst("workflows", "workflow");
        try {
            DockstoreYamlHelper.readAsDockstoreYaml12(workflowsKey);
            Assert.fail("Shouldn't be able to parse correctly");
        } catch (DockstoreYamlHelper.DockstoreYamlException ex) {
            assertTrue(ex.getMessage().contains("must have at least 1 workflow or service"));
        }

        final String nameKey = DOCKSTORE_GALAXY_YAML.replaceFirst("name", "Name");
        try {
            DockstoreYamlHelper.readAsDockstoreYaml12(nameKey);
        } catch (DockstoreYamlHelper.DockstoreYamlException ex) {
            Assert.fail("'name' is not required and 'Name' should just be ignored.");
        }

        final String multipleKeys = DOCKSTORE_GALAXY_YAML.replaceFirst("version", "Version").replaceFirst("workflows", "workflow");
        try {
            DockstoreYamlHelper.readAsDockstoreYaml12(multipleKeys);
            Assert.fail("Shouldn't be able to parse correctly");
        } catch (DockstoreYamlHelper.DockstoreYamlException ex) {
            assertTrue("Should only return first error", ex.getMessage().contains("missing valid version"));
        }
    }

    @Test
    public void testDifferentCaseForWorkflowSubclass() throws DockstoreYamlHelper.DockstoreYamlException {
        final DockstoreYaml12 dockstoreYaml12 = DockstoreYamlHelper.readAsDockstoreYaml12(DOCKSTORE12_YAML);
        final List<YamlWorkflow> workflows = dockstoreYaml12.getWorkflows();
        assertEquals(3, workflows.size());
        assertEquals(1, workflows.stream().filter(w -> "CWL".equals(w.getSubclass())).count());
        assertEquals(1, workflows.stream().filter(w -> "cwl".equals(w.getSubclass())).count());
    }

    /**
     * Subclass in .dockstore.yml for Galaxy was `GXFORMAT2`; should be `GALAXY`.
     * Fixture has 2 of each, with different cases; make sure they all appear as `GXFORMAT2`, which our
     * other code relies on.
     *
     * https://github.com/dockstore/dockstore/issues/3686
     *
     * @throws DockstoreYamlHelper.DockstoreYamlException
     */
    @Test
    public void testGalaxySubclass() throws DockstoreYamlHelper.DockstoreYamlException {
        final List<YamlWorkflow> workflows = DockstoreYamlHelper.readAsDockstoreYaml12(DOCKSTORE_GALAXY_YAML).getWorkflows();
        assertEquals(4, workflows.stream().filter(w -> w.getSubclass().equalsIgnoreCase("gxformat2")).count());
    }

    @Test
    public void testGitReferenceFilter() {
        // Empty filters allow anything
        Filters filters = new Filters();
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/anything"), filters));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/any/thing"), filters));

        // Only match develop branch
        // glob
        filters.setBranches(List.of("develop"));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/develop"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/foo/develop"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/developfoo"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/develop"), filters));
        // regex
        filters.setBranches(List.of());
        filters.setBranches(List.of("/develop/"));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/develop"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/foo/develop"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/developfoo"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/develop"), filters));

        // Only match 0.1 tag
        // glob
        filters.setBranches(List.of());
        filters.setTags(List.of("0.1"));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/0.1"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/foo/0.1"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/0.1/foo"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/0.1"), filters));
        // regex
        filters.setBranches(List.of());
        filters.setTags(List.of("/0\\.1/"));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/0.1"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/foo/0.1"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/0.1/foo"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/0.1"), filters));

        // Match any feature/** branch and ALL tags
        // glob/regex combo
        filters.setBranches(List.of("feature/**"));
        filters.setTags(List.of("/.*/"));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/feature"), filters));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/feature/1234"), filters));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/feature/1234/filters"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/foo/feature/1234"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/featurefoo"), filters));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/anything"), filters));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/any/thing"), filters));

        // X.X.X semantic version tags with regex
        filters.setBranches(List.of());
        filters.setTags(List.of("/\\d+\\.\\d+\\.\\d+/"));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/0.0.0"), filters));
        assertTrue(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/1.10.0"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/1.10.0-beta"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/a.b.c"), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/1.0."), filters));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/heads/0.0.0"), filters));

        // Invalid reference throws an error (this should never happen)
        assertThrows(UnsupportedOperationException.class, () -> DockstoreYamlHelper.filterGitReference(Path.of("fake/reference"), filters));

        // Invalid glob does not match (logs a warning)
        filters.setTags(List.of("["));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/["), filters));

        // Invalid regex does not match (logs a warning)
        filters.setTags(List.of("/[/"));
        assertFalse(DockstoreYamlHelper.filterGitReference(Path.of("refs/tags/["), filters));
    }

}
