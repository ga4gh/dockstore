package io.dockstore.webservice.languages;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.DescriptionSource;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.SourceControlOrganization;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Tool;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.jdbi.ToolDAO;
import io.dropwizard.testing.ResourceHelpers;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.mockito.Mockito;

import static io.dockstore.webservice.languages.WDLHandler.ERROR_PARSING_WORKFLOW_YOU_MAY_HAVE_A_RECURSIVE_IMPORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class WDLHandlerTest {

    public static final String MAIN_WDL = "/GATKSVPipelineClinical.wdl";

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Test
    public void getWorkflowContent() throws IOException {
        final WDLHandler wdlHandler = new WDLHandler();
        final Version workflow = new WorkflowVersion();
        workflow.setAuthor("Jane Doe");
        workflow.setDescriptionAndDescriptionSource("A good description", DescriptionSource.DESCRIPTOR);
        workflow.setEmail("janedoe@example.org");

        final String validFilePath = ResourceHelpers.resourceFilePath("valid_description_example.wdl");

        final String goodWdl = FileUtils.readFileToString(new File(validFilePath), StandardCharsets.UTF_8);
        Version version = wdlHandler.parseWorkflowContent(validFilePath, goodWdl, Collections.emptySet(), workflow);
        Assert.assertEquals(version.getAuthor(), "Mr. Foo");
        Assert.assertEquals(version.getEmail(), "foo@foo.com");
        Assert.assertEquals(version.getDescription(),
                "This is a cool workflow trying another line \n## This is a header\n* First Bullet\n* Second bullet");


        final String invalidFilePath = ResourceHelpers.resourceFilePath("invalid_description_example.wdl");
        final String invalidDescriptionWdl = FileUtils.readFileToString(new File(invalidFilePath), StandardCharsets.UTF_8);
        Version version1 = wdlHandler.parseWorkflowContent(invalidFilePath, invalidDescriptionWdl, Collections.emptySet(), version);
        Assert.assertNull(version1.getAuthor());
        Assert.assertNull(version1.getEmail());
    }

    @Test
    public void getWorkflowContentOfTool() throws IOException {
        final WDLHandler wdlHandler = new WDLHandler();
        final Tool tool = new Tool();
        tool.setAuthor("Jane Doe");
        tool.setDescription("A good description");
        tool.setEmail("janedoe@example.org");

        Assert.assertEquals("Jane Doe", tool.getAuthor());
        Assert.assertEquals("A good description", tool.getDescription());
        Assert.assertEquals("janedoe@example.org", tool.getEmail());

        final String invalidFilePath = ResourceHelpers.resourceFilePath("invalid_description_example.wdl");
        final String invalidDescriptionWdl = FileUtils.readFileToString(new File(invalidFilePath), StandardCharsets.UTF_8);
        wdlHandler.parseWorkflowContent(invalidFilePath, invalidDescriptionWdl, Collections.emptySet(), new WorkflowVersion());

        // Check that parsing an invalid WDL workflow does not corrupt the CWL metadata
        Assert.assertEquals("Jane Doe", tool.getAuthor());
        Assert.assertEquals("A good description", tool.getDescription());
        Assert.assertEquals("janedoe@example.org", tool.getEmail());
    }

    @Test
    public void testRecursiveImports() throws IOException {
        final File recursiveWdl = new File(ResourceHelpers.resourceFilePath("recursive.wdl"));

        final WDLHandler wdlHandler = new WDLHandler();
        String s = FileUtils.readFileToString(recursiveWdl, StandardCharsets.UTF_8);
        try {
            wdlHandler.checkForRecursiveHTTPImports(s, new HashSet<>());
            Assert.fail("Should've detected recursive import");
        } catch (CustomWebApplicationException e) {
            Assert.assertEquals(ERROR_PARSING_WORKFLOW_YOU_MAY_HAVE_A_RECURSIVE_IMPORT, e.getErrorMessage());
        }

        final File notRecursiveWdl = new File(ResourceHelpers.resourceFilePath("valid_description_example.wdl"));
        s = FileUtils.readFileToString(notRecursiveWdl, StandardCharsets.UTF_8);
        wdlHandler.checkForRecursiveHTTPImports(s, new HashSet<>());
    }


    @Test
    public void testRepeatedFilename() throws IOException {
        final String content = getGatkSvMainDescriptorContent();
        final WDLHandler wdlHandler = new WDLHandler();
        Version emptyVersion = new WorkflowVersion();
        final Map<String, SourceFile> map = wdlHandler
                .processImports("whatever", content, emptyVersion, new GatkSvClinicalSourceCodeRepoInterface(), MAIN_WDL);
        // There are 9 Structs.wdl files, in gatk-sv-clinical, but the one in gncv is not imported
        final long structsWdlCount = map.keySet().stream().filter(key -> key.contains("Structs.wdl")).count();
        Assert.assertEquals(8, structsWdlCount); // Note: there are 9 Structs.wdl files

        final BioWorkflow entry = new BioWorkflow();
        Version version = wdlHandler.parseWorkflowContent("/GATKSVPipelineClinical.wdl", content, new HashSet<>(map.values()), new WorkflowVersion());
        Assert.assertEquals("Christopher Whelan", version.getAuthor());
    }

    @Test
    public void testGetToolsForComplexWorkflow() throws IOException {
        final WDLHandler wdlHandler = new WDLHandler();
        Version emptyVersion = new WorkflowVersion();
        final String content = getGatkSvMainDescriptorContent();
        final Map<String, SourceFile> sourceFileMap = wdlHandler
                .processImports("whatever", content, emptyVersion, new GatkSvClinicalSourceCodeRepoInterface(), MAIN_WDL);

        // wdlHandler.getContent ultimately invokes toolDAO.findAllByPath from LanguageHandlerEntry.getURLFromEntry for look
        // up; just have it return null
        final ToolDAO toolDAO = Mockito.mock(ToolDAO.class);
        when(toolDAO.findAllByPath(Mockito.anyString(), Mockito.anyBoolean())).thenReturn(null);

        final Optional<String> toolsStr = wdlHandler
                .getContent(MAIN_WDL, content, new HashSet<SourceFile>(sourceFileMap.values()), LanguageHandlerInterface.Type.TOOLS, toolDAO);
        if (toolsStr.isPresent()) {
            final Gson gson = new Gson();
            final Object[] tools = gson.fromJson(toolsStr.get(), Object[].class);
            Assert.assertEquals("There should be 227 tools", 227, tools.length);
        } else {
            Assert.fail("Should be able to get tool json");
        }

    }

    @Test
    public void testGetContentWithSyntaxErrors() throws IOException {
        final WDLHandler wdlHandler = new WDLHandler();
        final File wdlFile = new File(ResourceHelpers.resourceFilePath("brokenWDL.wdl"));
        final Set<SourceFile> emptySet = Collections.emptySet();

        // wdlHandler.getContent ultimately invokes toolDAO.findAllByPath from LanguageHandlerEntry.getURLFromEntry for look
        // up; just have it return null
        final ToolDAO toolDAO = Mockito.mock(ToolDAO.class);
        when(toolDAO.findAllByPath(Mockito.anyString(), Mockito.anyBoolean())).thenReturn(null);

        // run test with a WDL descriptor with syntax errors
        try {
            wdlHandler.getContent("/brokenWDL.wdl", FileUtils.readFileToString(wdlFile, StandardCharsets.UTF_8), emptySet,
                LanguageHandlerInterface.Type.TOOLS, toolDAO);
            Assert.fail("Expected parsing error");
        } catch (CustomWebApplicationException e) {
            Assert.assertEquals(HttpStatus.SC_UNPROCESSABLE_ENTITY, e.getResponse().getStatus());
            assertThat(e.getErrorMessage()).contains(WDLHandler.WDL_PARSE_ERROR);
        }
    }

    private String getGatkSvMainDescriptorContent() throws IOException {
        final File wdlFile = new File(ResourceHelpers.resourceFilePath("gatk-sv-clinical" + MAIN_WDL));
        return FileUtils.readFileToString(wdlFile, StandardCharsets.UTF_8);
    }

    /**
     * Reads files from gatk-sv-clinical directory in resources. Only need to truly implement two methods; the rest are
     * never called from WDLHandler.parseWorkflowContent
     */
    private static class GatkSvClinicalSourceCodeRepoInterface extends SourceCodeRepoInterface {
        @Override
        public String readGitRepositoryFile(String repositoryId, DescriptorLanguage.FileType fileType, Version version,
                String specificPath) {
            return this.readFile(repositoryId, specificPath, null);
        }

        @Override
        public String readFile(String repositoryId, String fileName, String reference) {
            try {
                final File file = new File(ResourceHelpers.resourceFilePath("gatk-sv-clinical" + fileName));
                return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public String getREADMEContent(String repositoryId, String branch) {
            return null;
        }

        @Override
        public String getName() {
            return "gatk";
        }

        @Override
        public void setLicenseInformation(Entry entry, String gitRepository) {

        }

        // From here on down these methods are not invoked in our tests
        @Override
        public List<String> listFiles(String repositoryId, String pathToDirectory, String reference) {
            return null;
        }

        @Override
        public Map<String, String> getWorkflowGitUrl2RepositoryId() {
            return null;
        }

        @Override
        public boolean checkSourceCodeValidity() {
            return false;
        }

        @Override
        public Workflow initializeWorkflow(String repositoryId, Workflow workflow) {
            return null;
        }

        @Override
        public Workflow setupWorkflowVersions(String repositoryId, Workflow workflow, Optional<Workflow> existingWorkflow,
                Map<String, WorkflowVersion> existingDefaults, Optional<String> versionName, boolean hardRefresh) {
            return null;
        }

        @Override
        public String getRepositoryId(Entry entry) {
            return null;
        }

        @Override
        public String getMainBranch(Entry entry, String repositoryId) {
            return null;
        }

        @Override
        public SourceFile getSourceFile(String path, String id, String branch, DescriptorLanguage.FileType type) {
            return null;
        }

        @Override
        public List<SourceControlOrganization> getOrganizations() {
            return null;
        }

        @Override
        public void updateReferenceType(String repositoryId, Version version) {

        }
        @Override
        public String getCommitID(String repositoryId, Version version) {
            return null;
        }
    }


}


