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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.CharMatcher;
import groovyjarjarantlr.RecognitionException;
import groovyjarjarantlr.TokenStreamException;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.NextflowUtilities;
import io.dockstore.common.VersionTypeValidation;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.DescriptionSource;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Validation;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.jdbi.ToolDAO;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.codehaus.groovy.antlr.GroovySourceAST;
import org.codehaus.groovy.antlr.parser.GroovyLexer;
import org.codehaus.groovy.antlr.parser.GroovyRecognizer;

/**
 * This class will eventually handle support for Nextflow
 */
public class NextflowHandler extends AbstractLanguageHandler implements LanguageHandlerInterface {

    protected static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*include.+?from.+?'.+?'", Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern INCLUDE_CONFIG_PATTERN = Pattern.compile("(?i)(?m)^[ \t]*includeConfig(.*)");

    @Override
    protected DescriptorLanguage.FileType getFileType() {
        return DescriptorLanguage.FileType.NEXTFLOW;
    }

    @Override
    public Version parseWorkflowContent(String filepath, String content, Set<SourceFile> sourceFiles, Version version) {
        // this is where we can look for things like Nextflow config files or maybe a future Dockstore.yml
        try {
            final Configuration configuration = NextflowUtilities.grabConfig(content);
            String descriptionInProgress = null;
            if (configuration.containsKey("manifest.description")) {
                version.setDescriptionAndDescriptionSource(configuration.getString("manifest.description"), DescriptionSource.DESCRIPTOR);
                descriptionInProgress = version.getDescription();
            }
            if (configuration.containsKey("manifest.author")) {
                version.setAuthor(configuration.getString("manifest.author"));
            }
            // look for extended help message from nf-core workflows when it is available
            String mainScriptPath = "main.nf";
            if (configuration.containsKey("manifest.mainScript")) {
                mainScriptPath = configuration.getString("manifest.mainScript");
            }
            String finalMainScriptPath = mainScriptPath;
            final Optional<SourceFile> potentialScript = sourceFiles.stream().filter(file -> file.getPath().equals(finalMainScriptPath))
                .findFirst();
            if (potentialScript.isPresent()) {
                String helpMessage = getHelpMessage(potentialScript.get().getContent());
                // abitrarily follow description, markdown looks funny without the line breaks
                if (!StringUtils.isEmpty(helpMessage)) {
                    helpMessage = "\n\n" + helpMessage;
                }
                String builder = Stream.of(descriptionInProgress, helpMessage).filter(s -> s != null && !s.isEmpty())
                    .collect(Collectors.joining(""));
                version.setDescriptionAndDescriptionSource(builder, DescriptionSource.DESCRIPTOR);
            }
        } catch (NextflowUtilities.NextflowParsingException e) {
            createValidationMessageForGeneralFailure(version, filepath);
        }
        return version;
    }

    @Override
    public Map<String, SourceFile> processImports(String repositoryId, String content, Version version,
        SourceCodeRepoInterface sourceCodeRepoInterface, String filepath) {
        LOG.info("Processing import for repository {}, version {}, filepath {}", repositoryId, version.getName(), filepath);
        // FIXME: see{@link NextflowUtilities#grabConfig(String) grabConfig} method for comments on why
        // we have to look at imports in this crummy way
        final Matcher matcher = INCLUDE_CONFIG_PATTERN.matcher(content);
        Set<String> suspectedConfigImports = new HashSet<>();
        while (matcher.find()) {
            suspectedConfigImports.add(CharMatcher.is('\'').trimFrom(matcher.group(1).trim()));
        }
        Map<String, SourceFile> imports = new HashMap<>();
        Configuration configuration;
        try {
            configuration = NextflowUtilities.grabConfig(content);
        } catch (Exception e) {
            createValidationMessageForGeneralFailure(version, filepath);
            return imports;
        }

        // add the Nextflow scripts
        String mainScriptPath = "main.nf";
        if (configuration.containsKey("manifest.mainScript")) {
            mainScriptPath = configuration.getString("manifest.mainScript");
        }

        suspectedConfigImports.add(mainScriptPath);

        for (String filename : suspectedConfigImports) {
            String filenameAbsolutePath = convertRelativePathToAbsolutePath(filepath, filename);
            Optional<SourceFile> sourceFile = sourceCodeRepoInterface
                .readFile(repositoryId, version, DescriptorLanguage.FileType.NEXTFLOW, filenameAbsolutePath);
            if (sourceFile.isPresent()) {
                sourceFile.get().setPath(filename);
                imports.put(filename, sourceFile.get());
                imports.putAll(processOtherImports(repositoryId, sourceFile.get().getContent(), version, sourceCodeRepoInterface,
                        sourceFile.get().getAbsolutePath()));
            }
        }

        // source files in /lib seem to be automatically added to the script classpath
        // binaries are also there and will need to be ignored
        List<String> strings = sourceCodeRepoInterface.listFiles(repositoryId, "/", version.getReference());
        handleNextflowImports(repositoryId, version, sourceCodeRepoInterface, imports, strings, "lib");
        handleNextflowImports(repositoryId, version, sourceCodeRepoInterface, imports, strings, "bin");
        return imports;
    }

    /**
     * Similar to processImports() of other handlers.
     * The current processImports() method looks for main.nf and similar so is not suitable for recursion.
     * Looks for imports by searching for lines that start "include"
     * @param repositoryId  The Git repository (ex. nextflow-io/rnaseq-nf)
     * @param content   The contents of the file being analyzed
     * @param version   The Git version (branch/tag) of the repository
     * @param sourceCodeRepoInterface   The source code repository interface
     * @param workingDirectoryForFile   The parent directory of the file being analyzed
     * @return
     */
    private Map<String, SourceFile> processOtherImports(String repositoryId, String content, Version version,
            SourceCodeRepoInterface sourceCodeRepoInterface, String workingDirectoryForFile) {
        Map<String, SourceFile> imports = new HashMap<>();
        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String path = getRelativeImportPathFromLine(m.group(), workingDirectoryForFile);
            String absoluteImportPath = convertRelativePathToAbsolutePath(workingDirectoryForFile, path);
            handleImport(repositoryId, version, imports, path, sourceCodeRepoInterface, absoluteImportPath);
        }
        Map<String, SourceFile> recursiveImports = new HashMap<>();
        for (Map.Entry<String, SourceFile> importFile : imports.entrySet()) {
            final Map<String, SourceFile> sourceFiles = processOtherImports(repositoryId, importFile.getValue().getContent(), version, sourceCodeRepoInterface, importFile.getKey());
            recursiveImports.putAll(sourceFiles);
        }
        recursiveImports.putAll(imports);
        return recursiveImports;
    }

    /**
     * Give the line in the file that has the import, figure out what the relative path is
     * @param line  A line in the file that has the import (ex. "include { RNASEQ } from './modules/rnaseq'")
     * @return  The relative path
     */
    protected static String getRelativeImportPathFromLine(String line, String workingDirectoryForFile) {
        final String nextflowFileExtension = ".nf";
        String importPath = StringUtils.substringBetween(line, "'", "'");
        importPath = importPath.replaceFirst(workingDirectoryForFile, "");
        importPath = importPath.replaceFirst("^[.]/", "");
        importPath = importPath.replaceFirst("^/", "");
        // Sometimes the import line looks like "include { RNASEQ } from './modules/rnaseq'"
        // "./modules/rnaseq" is not a file, it is actually "./modules/rnaseq.nf"
        if (!importPath.endsWith(nextflowFileExtension)) {
            importPath = importPath + nextflowFileExtension;
        }
        return importPath;
    }

    private void createValidationMessageForGeneralFailure(Version version, String filepath) {
        Map<String, String> validationMessageObject = new HashMap<>();
        validationMessageObject.put(filepath, "Nextflow config file is malformed or missing, cannot extract metadata");
        version.addOrUpdateValidation(new Validation(DescriptorLanguage.FileType.NEXTFLOW_CONFIG, false, validationMessageObject));
    }

    private void handleNextflowImports(String repositoryId, Version version, SourceCodeRepoInterface sourceCodeRepoInterface,
        Map<String, SourceFile> imports, List<String> strings, String lib) {
        if (strings.contains(lib)) {
            List<String> libraries = sourceCodeRepoInterface.listFiles(repositoryId, lib, version.getReference());
            for (String library : libraries) {
                Optional<SourceFile> librarySourceFile = sourceCodeRepoInterface
                    .readFile(repositoryId, version, DescriptorLanguage.FileType.NEXTFLOW, FilenameUtils.concat(lib, library));
                librarySourceFile.ifPresent(sourceFile -> imports.put(lib + "/" + library, sourceFile));
            }
        }
    }

    private GroovySourceAST getFirstAstWithKeyword(GroovySourceAST ast, String keyword, boolean compareChild) {
        return getFirstAstWithKeyword(ast, keyword, compareChild, new HashSet<>());
    }

    /**
     * Returns the first AST found with some keyword as text
     *
     * @param ast          An AST
     * @param keyword      Text to search node for (exact match)
     * @param compareChild If true will check first child for keyword, if false will check current node
     * @return AST with some keyword as text
     */
    private GroovySourceAST getFirstAstWithKeyword(GroovySourceAST ast, String keyword, boolean compareChild, Set<GroovySourceAST> seen) {
        if (ast == null) {
            return null;
        }

        if (seen.contains(ast)) {
            return null;
        }
        seen.add(ast);

        if (compareChild) {
            if (ast.getFirstChild() != null && Objects.equals(ast.getFirstChild().getText(), keyword)) {
                return ast;
            }
        } else {
            if (Objects.equals(ast.getText(), keyword)) {
                return ast;
            }
        }

        int numChildren = ast.getNumberOfChildren();
        GroovySourceAST subtree = null;

        for (int i = 0; i < numChildren; i++) {
            GroovySourceAST groovySourceAST = ast.childAt(i);
            subtree = getFirstAstWithKeyword(groovySourceAST, keyword, compareChild, seen);
            if (subtree != null) {
                break;
            }
        }

        if (subtree == null && ast.getNextSibling() != null) {
            subtree = getFirstAstWithKeyword((GroovySourceAST)ast.getNextSibling(), keyword, compareChild, seen);
        }
        return subtree;
    }

    /**
     * Given an AST for an EXPR will return the text name
     *
     * @param exprAST AST of an EXPR
     * @return Input channel name
     */
    private String getInputChannelNameForEXPR(GroovySourceAST exprAST) {
        return exprAST == null ? null : exprAST.getFirstChild().getFirstChild().getNextSibling().getFirstChild().getText();
    }

    /**
     * Given an AST for a process, returns the name of the process
     *
     * @param processAST AST of a process
     * @return Process name or null if there isn't one
     */
    private String getProcessValue(GroovySourceAST processAST) {
        try {
            return processAST == null ? null : processAST.getNextSibling().getFirstChild().getFirstChild().getText();
        } catch (NullPointerException e) {
            return null;
        }
    }

    /**
     * Get a list of all channel names for either inputs or outputs of an EXPR
     *
     * @param processAST AST of a process
     * @return List of channels for inputs or outputs of a process
     */
    private List<String> getListOfIO(GroovySourceAST processAST) {
        List<String> inputs = new ArrayList<>();
        GroovySourceAST firstEXPR = getFirstAstWithKeyword(processAST, "EXPR", false);
        inputs.add(getInputChannelNameForEXPR(firstEXPR));

        // Only look at next sibling under certain conditions
        if (!(processAST != null && processAST.getNextSibling() != null && processAST.getNextSibling().getFirstChild() != null && Objects
            .equals(processAST.getNextSibling().getFirstChild().getText(), "output"))) {
            if (processAST != null && processAST.getNextSibling() != null) {
                inputs.addAll(getListOfIO((GroovySourceAST)processAST.getNextSibling()));
            }
        }
        return inputs;
    }

    private List<GroovySourceAST> getSubtreesOfKeyword(GroovySourceAST ast, String keyword) {
        return getSubtreesOfKeyword(ast, keyword, new HashSet<>());
    }

    /**
     * Gets a list of all subtrees with text keyword
     *
     * @param ast     Some AST
     * @param keyword A keyword of an existing node in an AST
     * @return List of AST with some keyword
     */
    private List<GroovySourceAST> getSubtreesOfKeyword(GroovySourceAST ast, String keyword, Set<GroovySourceAST> seen) {

        List<GroovySourceAST> subtrees = new ArrayList<>();
        if (seen.contains(ast)) {
            return subtrees;
        }
        seen.add(ast);

        if (ast == null) {
            return null;
        }

        if (Objects.equals(ast.getText(), keyword)) {
            subtrees.add(ast);
            return subtrees;
        }

        int numChildren = ast.getNumberOfChildren();

        for (int i = 0; i < numChildren; i++) {
            GroovySourceAST childAST = ast.childAt(i);
            subtrees.addAll(getSubtreesOfKeyword(childAST, keyword, seen));
        }

        if (ast.getNextSibling() != null) {
            subtrees.addAll(getSubtreesOfKeyword((GroovySourceAST)ast.getNextSibling(), keyword, seen));
        }

        return subtrees;
    }

    /**
     * Returns a list of input channels the process AST depends on
     *
     * @param processAST AST of a process
     * @return List of input channels for a process
     */
    private List<String> getInputDependencyList(GroovySourceAST processAST) {
        GroovySourceAST inputAST = getFirstAstWithKeyword(processAST, "input", true);
        if (inputAST != null) {
            return getListOfIO(inputAST);
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * Returns a list of all channels written to by the given process AST
     *
     * @param processAST AST of a process
     * @return List of output channels for a process
     */
    private List<String> getOutputDependencyList(GroovySourceAST processAST) {
        GroovySourceAST outputAst = getFirstAstWithKeyword(processAST, "output", true);
        if (outputAst != null) {
            // Stops from parsing outside the output AST
            outputAst.setNextSibling(null);
            return getListOfIO(outputAst);
        } else {
            return new ArrayList<>();
        }
    }

    @Override
    public Optional<String> getContent(String mainDescName, String mainDescriptor, Set<SourceFile> secondarySourceFiles, Type type, ToolDAO dao) {
        String callType = "call"; // This may change later (ex. tool, workflow)
        String toolType = "tool";

        // Write main descriptor to file
        // The use of temporary files is not needed here and might cause new problems
        // Iterate over each call, grab docker containers

        // nextflow uses the main script from the manifest as the main descriptor
        // add the Nextflow scripts

        Configuration configuration = null;
        try {
            configuration = NextflowUtilities.grabConfig(mainDescriptor);
        } catch (NextflowUtilities.NextflowParsingException e) {
            throw new CustomWebApplicationException(e.getMessage(), HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
        String mainScriptPath = "main.nf";
        if (configuration.containsKey("manifest.mainScript")) {
            mainScriptPath = configuration.getString("manifest.mainScript");
        }
        final String finalMainScriptPath = mainScriptPath;
        mainDescriptor = secondarySourceFiles.stream().filter(sf -> sf.getPath().equals(finalMainScriptPath)).findFirst().map(sf -> sf.getContent()).orElse(null);

        // Get default container (process.container takes precedence over params.container)
        String defaultContainer = null;
        if (configuration.containsKey("params.container")) {
            defaultContainer = configuration.getString("params.container");
        }

        if (configuration.containsKey("process.container")) {
            defaultContainer = configuration.getString("process.container");
        }

        Map<String, String> callToDockerMap = new HashMap<>();
        String finalDefaultContainer = defaultContainer;

        // Add all DockerMap from each secondary sourcefile
        if (!secondarySourceFiles.isEmpty()) {
            secondarySourceFiles.forEach(sourceFile -> callToDockerMap.putAll(this.getCallsToDockerMap(sourceFile.getContent(), finalDefaultContainer)));
        }

        callToDockerMap.putAll(this.getCallsToDockerMap(mainDescriptor, defaultContainer));
        // Iterate over each call, determine dependencies
        // Mapping of stepId -> array of dependencies for the step
        Map<String, List<String>> callToDependencies = this.getCallsToDependencies(mainDescriptor);
        // Get import files
        Map<String, String> namespaceToPath = this.getImportMap(mainDescriptor);
        Map<String, ToolInfo> toolInfoMap = WDLHandler.mapConverterToToolInfo(WDLHandler.convertToDockerParameter(callToDockerMap), callToDependencies);
        return convertMapsToContent(mainScriptPath, type, dao, callType, toolType, toolInfoMap, namespaceToPath);
    }

    private Map<String, String> getImportMap(String mainDescriptor) {
        //TODO: deal with secondary files properly? (for DAG and tools display)
        return new HashMap<>();
    }

    /**
     * Get help message from nf-core workflows
     *
     * @return the aggregated help message
     */
    private String getHelpMessage(String mainDescriptor) {
        try {
            StringBuilder builder = new StringBuilder();
            final List<GroovySourceAST> process = getGroovySourceASTList(mainDescriptor, "helpMessage");
            process.forEach(ast -> getHelpMessage(ast, builder, new HashSet<GroovySourceAST>()));
            return builder.toString();
        } catch (IOException | TokenStreamException | RecognitionException e) {
            LOG.warn("could not parse", e);
        }
        return null;
    }

    /**
     * Aggregates the help message from what looks like log comments
     *
     * @param definition the ast
     * @param builder    aggregates text
     * @param set        the set of AST seen before (to avoid looping)
     */
    private void getHelpMessage(GroovySourceAST definition, StringBuilder builder, Set<GroovySourceAST> set) {
        if (set.contains(definition)) {
            return;
        }
        set.add(definition);
        // not sure why groovy puts text under a type of 88, cannot find a constant for this
        final int groovyTextType = 88;
        if (definition.getType() == groovyTextType) {
            builder.append(definition.getText());
        }
        for (int i = 0; i < definition.getNumberOfChildren(); i++) {
            final GroovySourceAST groovySourceAST = definition.childAt(i);
            getHelpMessage(groovySourceAST, builder, set);
        }
        GroovySourceAST nextSibling = (GroovySourceAST)definition.getNextSibling();
        while (nextSibling != null) {
            getHelpMessage(nextSibling, builder, set);
            nextSibling = (GroovySourceAST)nextSibling.getNextSibling();
        }
    }

    /**
     * Given string content, look for a keyword inside the AST representation of it
     *
     * @param mainDescriptor content
     * @param keyword        keyword to lookup
     * @return nodes with the suspectec content
     * @throws RecognitionException
     * @throws TokenStreamException
     * @throws IOException
     */
    private List<GroovySourceAST> getGroovySourceASTList(String mainDescriptor, String keyword)
        throws RecognitionException, TokenStreamException, IOException {
        try (InputStream stream = IOUtils.toInputStream(mainDescriptor, StandardCharsets.UTF_8)) {
            GroovyRecognizer make = GroovyRecognizer.make(new GroovyLexer(stream));
            make.compilationUnit();
            GroovySourceAST ast = (GroovySourceAST)make.getAST();
            return getSubtreesOfKeyword(ast, keyword);
        }
    }

    /**
     * Returns map from names of processes to their dependencies (processes that had to come before)
     *
     * @param mainDescriptor
     * @return
     */
    private Map<String, List<String>> getCallsToDependencies(String mainDescriptor) {
        //TODO: create proper dependency arrays, for now just list processes sequentially
        Map<String, List<String>> map = new HashMap<>();
        try {
            List<GroovySourceAST> processList = getGroovySourceASTList(mainDescriptor, "process");
            Map<String, List<String>> processNameToInputChannels = new HashMap<>();
            Map<String, List<String>> processNameToOutputChannels = new HashMap<>();

            processList.forEach((GroovySourceAST processAST) -> {
                String processName = getProcessValue(processAST);

                // Get a list of all channels that the process depends on
                List<String> inputs = getInputDependencyList(processAST);
                processNameToInputChannels.put(processName, inputs);

                // Get a list of all channels that the process writes to
                List<String> outputs = getOutputDependencyList(processAST);
                processNameToOutputChannels.put(processName, outputs);
            });

            // Create a map of process name to dependent processes
            processNameToInputChannels.keySet().forEach((String processName) -> {
                List<String> dependencies = new ArrayList<>();
                processNameToInputChannels.get(processName).forEach((String channelRead) -> {
                    processNameToOutputChannels.keySet().forEach((String dependentProcessName) -> {
                        Optional<String> temp = processNameToOutputChannels.get(dependentProcessName).stream()
                            .filter(channelWrite -> Objects.equals(channelRead, channelWrite)).findFirst();

                        if (temp.isPresent()) {
                            dependencies.add(dependentProcessName);
                        }
                    });
                });
                map.put(processName, dependencies);
            });
        } catch (IOException | TokenStreamException | RecognitionException e) {
            LOG.warn("could not parse", e);
        }
        return map;
    }

    private Map<String, String> getCallsToDockerMap(String mainDescriptor, String defaultContainer) {
        Map<String, String> map = new HashMap<>();
        try {
            List<GroovySourceAST> processList = getGroovySourceASTList(mainDescriptor, "process");

            for (GroovySourceAST processAST : processList) {
                String processName = getProcessValue(processAST);
                if (processName == null) {
                    continue;
                }
                GroovySourceAST containerAST = getFirstAstWithKeyword(processAST, "container", false);
                String containerName;
                if (containerAST != null) {
                    containerName = containerAST.getNextSibling().getFirstChild().getText();
                } else {
                    containerName = defaultContainer;
                }

                if (containerName != null) {
                    map.put(processName, containerName);
                    LOG.debug("found container: " + containerName + " in process " + processName);
                }
            }
        } catch (IOException | TokenStreamException | RecognitionException e) {
            LOG.warn("could not parse", e);
        }
        return map;
    }

    @Override
    public VersionTypeValidation validateWorkflowSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        Optional<SourceFile> mainDescriptor = sourcefiles.stream()
            .filter((sourceFile -> Objects.equals(sourceFile.getPath(), primaryDescriptorFilePath))).findFirst();
        Map<String, String> validationMessageObject = new HashMap<>();
        String validationMessage;
        String content;
        if (mainDescriptor.isPresent()) {
            content = mainDescriptor.get().getContent();
            if (content.contains("manifest")) {
                return new VersionTypeValidation(true, Collections.emptyMap());
            } else {
                validationMessage = "Descriptor file '" + primaryDescriptorFilePath + "' is missing the manifest section.";
            }
        } else {
            validationMessage = "Descriptor file '" + primaryDescriptorFilePath + "' not found.";
        }
        validationMessageObject.put(primaryDescriptorFilePath, validationMessage);
        return new VersionTypeValidation(false, validationMessageObject);
    }

    @Override
    public VersionTypeValidation validateToolSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        // Todo: Throw exception instead?
        Map<String, String> validationMessageObject = new HashMap<>();
        validationMessageObject.put(primaryDescriptorFilePath, "Nextflow does not support tools.");
        return new VersionTypeValidation(true, validationMessageObject);
    }

    @Override
    public VersionTypeValidation validateTestParameterSet(Set<SourceFile> sourceFiles) {
        // Todo: Throw exception instead?
        Map<String, String> validationMessageObject = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            validationMessageObject.put(sourceFile.getPath(), "Nextflow does not support test parameter files.");
        }
        return new VersionTypeValidation(true, validationMessageObject);
    }
}
