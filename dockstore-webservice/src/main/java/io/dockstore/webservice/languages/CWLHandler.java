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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;
import io.cwl.avro.CWL;
import io.cwl.avro.CommandLineTool;
import io.cwl.avro.ExpressionTool;
import io.cwl.avro.Workflow;
import io.cwl.avro.WorkflowOutputParameter;
import io.cwl.avro.WorkflowStep;
import io.cwl.avro.WorkflowStepInput;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.VersionTypeValidation;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.DescriptionSource;
import io.dockstore.webservice.core.FileFormat;
import io.dockstore.webservice.core.ParsedInformation;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Validation;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.jdbi.ToolDAO;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.http.HttpStatus;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * This class will eventually handle support for understanding CWL
 */
public class CWLHandler extends AbstractLanguageHandler implements LanguageHandlerInterface {
    public static final String CWL_VERSION_PREFIX = "v1";
    public static final Logger LOG = LoggerFactory.getLogger(CWLHandler.class);
    public static final String CWL_PARSE_ERROR = "Unable to parse CWL workflow, ";
    public static final String CWL_VERSION_ERROR = "CWL descriptor should contain a cwlVersion starting with " + CWLHandler.CWL_VERSION_PREFIX + ", detected version ";
    public static final String CWL_NO_VERSION_ERROR = "CWL descriptor should contain a cwlVersion";
    public static final String CWL_PARSE_SECONDARY_ERROR = "Syntax incorrect. Could not ($)import or ($)include secondary file for run command: ";

    @Override
    protected DescriptorLanguage.FileType getFileType() {
        return DescriptorLanguage.FileType.DOCKSTORE_CWL;
    }

    @Override
    public Version parseWorkflowContent(String filepath, String content, Set<SourceFile> sourceFiles, Version version) {
        // parse the collab.cwl file to get important metadata
        if (content != null && !content.isEmpty()) {
            try {
                Yaml yaml = new Yaml();
                Map map = yaml.loadAs(content, Map.class);
                String description = null;
                try {
                    // draft-3 construct
                    description = (String)map.get("description");
                } catch (ClassCastException e) {
                    LOG.debug("\"description:\" is malformed, but was only in CWL draft-3 anyway");
                }
                String label = null;
                try {
                    label = (String)map.get("label");
                } catch (ClassCastException e) {
                    LOG.debug("\"label:\" is malformed");
                }
                // "doc:" added for CWL 1.0
                String doc = null;
                if (map.containsKey("doc")) {
                    Object objectDoc = map.get("doc");
                    if (objectDoc instanceof String) {
                        doc = (String)objectDoc;
                    } else if (objectDoc instanceof Map) {
                        Map docMap = (Map)objectDoc;
                        if (docMap.containsKey("$include")) {
                            String enclosingFile = (String)docMap.get("$include");
                            Optional<SourceFile> first = sourceFiles.stream().filter(file -> file.getPath().equals(enclosingFile))
                                .findFirst();
                            if (first.isPresent()) {
                                doc = first.get().getContent();
                            }
                        }
                    } else if (objectDoc instanceof List) {
                        // arrays for "doc:" added in CWL 1.1
                        List docList = (List)objectDoc;
                        doc = String.join(System.getProperty("line.separator"), docList);
                    }
                }

                final String finalChoiceForDescription = ObjectUtils.firstNonNull(doc, description, label);

                if (finalChoiceForDescription != null) {
                    version.setDescriptionAndDescriptionSource(finalChoiceForDescription, DescriptionSource.DESCRIPTOR);
                } else {
                    LOG.info("Description not found!");
                }

                String dctKey = "dct:creator";
                String schemaKey = "s:author";
                if (map.containsKey(schemaKey)) {
                    processAuthor(version, map, schemaKey, "s:name", "s:email", "Author not found!");
                } else if (map.containsKey(dctKey)) {
                    processAuthor(version, map, dctKey, "foaf:name", "foaf:mbox", "Creator not found!");
                }

                LOG.info("Repository has Dockstore.cwl");
            } catch (YAMLException | NullPointerException | ClassCastException ex) {
                String message;
                if (ex.getCause() != null) {
                    // seems to be possible to get underlying cause in some cases
                    message = ex.getCause().toString();
                } else {
                    // in other cases, the above will NullPointer
                    message = ex.toString();
                }
                LOG.info("CWL file is malformed " + message);
                // should just report on the malformed workflow
                Map<String, String> validationMessageObject = new HashMap<>();
                validationMessageObject.put(filepath, "CWL file is malformed or missing, cannot extract metadata: " + message);
                version.addOrUpdateValidation(new Validation(DescriptorLanguage.FileType.DOCKSTORE_CWL, false, validationMessageObject));
            }
        }
        return version;
    }

    /**
     * Look at the map of metadata and populate entry with an author and email
     * @param version
     * @param map
     * @param dctKey
     * @param authorKey
     * @param emailKey
     * @param errorMessage
     */
    private void processAuthor(Version version, Map map, String dctKey, String authorKey, String emailKey, String errorMessage) {
        Object o = map.get(dctKey);
        if (o instanceof List) {
            o = ((List)o).get(0);
        }
        map = (Map)o;
        if (map != null) {
            String author = (String)map.get(authorKey);
            version.setAuthor(author);
            String email = (String)map.get(emailKey);
            if (!Strings.isNullOrEmpty(email)) {
                version.setEmail(email.replaceFirst("^mailto:", ""));
            }
        } else {
            LOG.info(errorMessage);
        }
    }

    @Override
    public Map<String, SourceFile> processImports(String repositoryId, String content, Version version,
        SourceCodeRepoInterface sourceCodeRepoInterface, String workingDirectoryForFile) {
        Map<String, SourceFile> imports = new HashMap<>();
        Yaml yaml = new Yaml();
        try {
            Map<String, ?> fileContentMap = yaml.loadAs(content, Map.class);
            handleMap(repositoryId, workingDirectoryForFile, version, imports, fileContentMap, sourceCodeRepoInterface);
        } catch (YAMLException e) {
            SourceCodeRepoInterface.LOG.error("Could not process content from workflow as yaml", e);
        }

        Map<String, SourceFile> recursiveImports = new HashMap<>();
        for (Map.Entry<String, SourceFile> importFile : imports.entrySet()) {
            final Map<String, SourceFile> sourceFiles = processImports(repositoryId, importFile.getValue().getContent(), version, sourceCodeRepoInterface, importFile.getKey());
            recursiveImports.putAll(sourceFiles);
        }

        recursiveImports.putAll(imports);
        return recursiveImports;
    }

    /**
     * Gets the file formats (either input or output) associated with the contents of a single CWL descriptor file
     * @param content   Contents of a CWL descriptor file
     * @param type      Either "inputs" or "outputs"
     * @return
     */
    public Set<FileFormat> getFileFormats(String content, String type) {
        Set<FileFormat> fileFormats = new HashSet<>();
        Yaml yaml = new Yaml();
        try {
            Map<String, ?> map = yaml.loadAs(content, Map.class);
            Object targetType = map.get(type);
            if (targetType instanceof Map) {
                Map<String, ?> outputsMap = (Map<String, ?>)targetType;
                outputsMap.forEach((k, v) -> {
                    handlePotentialFormatEntry(fileFormats, v);
                });
            } else if (targetType instanceof List) {
                ((List)targetType).forEach(v -> {
                    handlePotentialFormatEntry(fileFormats, v);
                });
            } else {
                LOG.debug(type + " is not comprehensible.");
            }
        } catch (YAMLException | NullPointerException e) {
            LOG.error("Could not process content from entry as yaml", e);
        }
        return fileFormats;
    }

    private void handlePotentialFormatEntry(Set<FileFormat> fileFormats, Object v) {
        if (v instanceof Map) {
            Map<String, String> outputMap = (Map<String, String>)v;
            String format = outputMap.get("format");
            if (format != null) {
                FileFormat fileFormat = new FileFormat();
                fileFormat.setValue(format);
                fileFormats.add(fileFormat);
            }
        }
    }

    @Override
    @SuppressWarnings("checkstyle:methodlength")
    //TODO: Occassionally misses dockerpulls. One case is when a dockerPull is nested within a run that's within a step. There are other missed cases though that are TBD.
    public Optional<String> getContent(String mainDescriptorPath, String mainDescriptor, Set<SourceFile> secondarySourceFiles, LanguageHandlerInterface.Type type,
        ToolDAO dao) {
        Yaml yaml = new Yaml();
        try {
            // Initialize data structures for DAG
            Map<String, ToolInfo> toolInfoMap = new HashMap<>(); // Mapping of stepId -> array of dependencies for the step
            List<Pair<String, String>> nodePairs = new ArrayList<>();       // List of pairings of step id and dockerPull url
            Map<String, String> stepToType = new HashMap<>();               // Map of stepId -> type (expression tool, tool, workflow)
            String defaultDockerPath = null;

            // Initialize data structures for Tool table
            Map<String, DockerInfo> nodeDockerInfo = new HashMap<>(); // map of stepId -> (run path, docker image, docker url)

            // Convert YAML to JSON
            Map<String, Object> mapping = yaml.loadAs(mainDescriptor, Map.class);

            // verify cwl version is correctly specified
            final Object cwlVersion = mapping.get("cwlVersion");
            if (cwlVersion != null) {
                final boolean startsWith = cwlVersion.toString().startsWith(CWLHandler.CWL_VERSION_PREFIX);
                if (!startsWith) {
                    LOG.error(CWLHandler.CWL_VERSION_ERROR + cwlVersion.toString());
                    throw new CustomWebApplicationException(CWLHandler.CWL_VERSION_ERROR
                        + cwlVersion.toString(), HttpStatus.SC_UNPROCESSABLE_ENTITY);
                }
            } else {
                LOG.error(CWLHandler.CWL_NO_VERSION_ERROR);
                throw new CustomWebApplicationException(CWLHandler.CWL_NO_VERSION_ERROR, HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }

            JSONObject cwlJson = new JSONObject(mapping);

            // CWLAvro only supports requirements and hints as an array, must be converted
            cwlJson = convertJSONObjectToArray("requirements", cwlJson);
            cwlJson = convertJSONObjectToArray("hints", cwlJson);

            // Other useful variables
            String nodePrefix = "dockstore_";
            String toolType = "tool";
            String workflowType = "workflow";
            String expressionToolType = "expressionTool";

            // Set up GSON for JSON parsing
            Gson gson = CWL.getTypeSafeCWLToolDocument();

            final Workflow workflow = gson.fromJson(cwlJson.toString(), Workflow.class);

            if (workflow == null) {
                LOG.error("The workflow does not seem to conform to CWL specs.");
                return Optional.empty();
            }

            // Determine default docker path (Check requirement first and then hint)
            defaultDockerPath = getRequirementOrHint(workflow.getRequirements(), workflow.getHints(), defaultDockerPath);

            // Store workflow steps in json and then read it into map <String, WorkflowStep>
            Object steps = workflow.getSteps();
            String stepJson = gson.toJson(steps);
            Map<String, WorkflowStep> workflowStepMap;
            if (steps instanceof ArrayList) {
                ArrayList<WorkflowStep> workflowStepList = gson.fromJson(stepJson, new TypeToken<ArrayList<WorkflowStep>>() {
                }.getType());
                workflowStepMap = new LinkedTreeMap<>();
                workflowStepList.forEach(workflowStep -> workflowStepMap.put(workflowStep.getId().toString(), workflowStep));
            } else {
                workflowStepMap = gson.fromJson(stepJson, new TypeToken<Map<String, WorkflowStep>>() {
                }.getType());
            }

            if (stepJson == null) {
                LOG.error("Could not find any steps for the workflow.");
                return Optional.empty();
            }

            if (workflowStepMap == null) {
                LOG.error("Error deserializing workflow steps");
                return Optional.empty();
            }

            // Iterate through steps to find dependencies and docker requirements
            for (Map.Entry<String, WorkflowStep> entry : workflowStepMap.entrySet()) {
                WorkflowStep workflowStep = entry.getValue();
                String workflowStepId = nodePrefix + entry.getKey();

                ArrayList<String> stepDependencies = new ArrayList<>();

                // Iterate over source and get the dependencies
                if (workflowStep.getIn() != null) {
                    for (WorkflowStepInput workflowStepInput : workflowStep.getIn()) {
                        Object sources = workflowStepInput.getSource();

                        processDependencies(nodePrefix, stepDependencies, sources);
                    }
                    if (stepDependencies.size() > 0) {
                        toolInfoMap.computeIfPresent(workflowStepId, (toolId, toolInfo) -> {
                            toolInfo.toolDependencyList.addAll(stepDependencies);
                            return toolInfo;
                        });
                        toolInfoMap.computeIfAbsent(workflowStepId, toolId -> new ToolInfo(null, stepDependencies));
                    }
                }

                // Check workflow step for docker requirement and hints
                String stepDockerRequirement = defaultDockerPath;
                stepDockerRequirement = getRequirementOrHint(workflowStep.getRequirements(), workflowStep.getHints(),
                    stepDockerRequirement);

                // Check for docker requirement within workflow step file
                String secondaryFile = null;
                Object run = workflowStep.getRun();
                String runAsJson = gson.toJson(gson.toJsonTree(run));

                if (run instanceof String) {
                    secondaryFile = (String)run;
                } else if (isTool(runAsJson, yaml)) {
                    CommandLineTool clTool = gson.fromJson(runAsJson, CommandLineTool.class);
                    stepDockerRequirement = getRequirementOrHint(clTool.getRequirements(), clTool.getHints(),
                        stepDockerRequirement);
                    stepToType.put(workflowStepId, toolType);
                } else if (isWorkflow(runAsJson, yaml)) {
                    Workflow stepWorkflow = gson.fromJson(runAsJson, Workflow.class);
                    stepDockerRequirement = getRequirementOrHint(stepWorkflow.getRequirements(), stepWorkflow.getHints(),
                        stepDockerRequirement);
                    stepToType.put(workflowStepId, workflowType);
                } else if (isExpressionTool(runAsJson, yaml)) {
                    ExpressionTool expressionTool = gson.fromJson(runAsJson, ExpressionTool.class);
                    stepDockerRequirement = getRequirementOrHint(expressionTool.getRequirements(), expressionTool.getHints(),
                        stepDockerRequirement);
                    stepToType.put(workflowStepId, expressionToolType);
                } else if (run instanceof Map) {
                    // must be import or include
                    Object importVal = ((Map)run).containsKey("$import") ? ((Map)run).get("$import") : ((Map)run).get("import");
                    if (importVal != null) {
                        secondaryFile = importVal.toString();
                    }

                    Object includeVal = ((Map)run).containsKey("$include") ? ((Map)run).get("$include") : ((Map)run).get("include");
                    if (includeVal != null) {
                        secondaryFile = includeVal.toString();
                    }

                    if (secondaryFile == null) {
                        LOG.error(CWLHandler.CWL_PARSE_SECONDARY_ERROR + run);
                        throw new CustomWebApplicationException(CWLHandler.CWL_PARSE_SECONDARY_ERROR + run, HttpStatus.SC_UNPROCESSABLE_ENTITY);
                    }
                }

                // Check secondary file for docker pull
                if (secondaryFile != null) {
                    String finalSecondaryFile = secondaryFile;
                    final Optional<SourceFile> sourceFileOptional = secondarySourceFiles.stream()
                            .filter(sf -> sf.getPath().equals(finalSecondaryFile)).findFirst();
                    final String content = sourceFileOptional.map(SourceFile::getContent).orElse(null);
                    stepDockerRequirement = parseSecondaryFile(stepDockerRequirement, content, gson, yaml);
                    if (isExpressionTool(content, yaml)) {
                        stepToType.put(workflowStepId, expressionToolType);
                    } else if (isTool(content, yaml)) {
                        stepToType.put(workflowStepId, toolType);
                    } else if (isWorkflow(content, yaml)) {
                        stepToType.put(workflowStepId, workflowType);
                    } else {
                        stepToType.put(workflowStepId, "n/a");
                    }
                }

                String dockerUrl = null;
                if ((stepToType.get(workflowStepId).equals(workflowType) || stepToType.get(workflowStepId).equals(toolType)) && !Strings.isNullOrEmpty(stepDockerRequirement)) {
                    dockerUrl = getURLFromEntry(stepDockerRequirement, dao);
                }

                if (type == LanguageHandlerInterface.Type.DAG) {
                    nodePairs.add(new MutablePair<>(workflowStepId, dockerUrl));
                }

                if (secondaryFile != null) {
                    nodeDockerInfo.put(workflowStepId, new DockerInfo(secondaryFile, stepDockerRequirement, dockerUrl));
                } else {
                    nodeDockerInfo.put(workflowStepId, new DockerInfo(mainDescriptorPath, stepDockerRequirement, dockerUrl));
                }

            }

            if (type == LanguageHandlerInterface.Type.DAG) {
                // Determine steps that point to end
                List<String> endDependencies = new ArrayList<>();

                for (WorkflowOutputParameter workflowOutputParameter : workflow.getOutputs()) {
                    Object sources = workflowOutputParameter.getOutputSource();
                    processDependencies(nodePrefix, endDependencies, sources);
                }

                toolInfoMap.put("UniqueEndKey", new ToolInfo(null, endDependencies));
                nodePairs.add(new MutablePair<>("UniqueEndKey", ""));

                // connect start node with them
                for (Pair<String, String> node : nodePairs) {
                    if (toolInfoMap.get(node.getLeft()) == null) {
                        toolInfoMap.put(node.getLeft(), new ToolInfo(null, Lists.newArrayList("UniqueBeginKey")));
                    }
                }
                nodePairs.add(new MutablePair<>("UniqueBeginKey", ""));

                return Optional.of(setupJSONDAG(nodePairs, toolInfoMap, stepToType, nodeDockerInfo));
            } else {
                return Optional.of(getJSONTableToolContent(nodeDockerInfo));
            }
        } catch (ClassCastException | YAMLException | JsonParseException ex) {
            final String exMsg = CWLHandler.CWL_PARSE_ERROR + ex.getMessage();
            LOG.error(exMsg, ex);
            throw new CustomWebApplicationException(exMsg, HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    private void processDependencies(String nodePrefix, List<String> endDependencies, Object sources) {
        if (sources != null) {
            if (sources instanceof String) {
                String[] sourceSplit = ((String)sources).split("/");
                if (sourceSplit.length > 1) {
                    endDependencies.add(nodePrefix + sourceSplit[0].replaceFirst("#", ""));
                }
            } else {
                List<String> filteredDependencies = filterDependent((List<String>)sources, nodePrefix);
                endDependencies.addAll(filteredDependencies);
            }
        }
    }

    /**
     * Iterates over a map of CWL file content looking for imports. When import is found. will grab the imported file from Git
     * and prepare it for import finding.
     * @param repositoryId              identifies the git repository that we wish to use, normally something like 'organization/repo_name`
     * @param parentFilePath            absolute path to the parent file which references the imported file
     * @param version                   version of the files to get
     * @param imports                   mapping of filenames to imports
     * @param fileContentMap            CWL file mapping
     * @param sourceCodeRepoInterface   used too retrieve imports
     */
    private void handleMap(String repositoryId, String parentFilePath, Version version, Map<String, SourceFile> imports, Map<String, ?> fileContentMap,
        SourceCodeRepoInterface sourceCodeRepoInterface) {
        Set<String> importKeywords = Sets.newHashSet("$import", "$include", "$mixin", "import", "include", "mixin");
        ParsedInformation parsedInformation = getParsedInformation(version, DescriptorLanguage.CWL);
        for (Map.Entry<String, ?> e : fileContentMap.entrySet()) {
            final Object mapValue = e.getValue();
            String absoluteImportPath;

            if (importKeywords.contains(e.getKey().toLowerCase())) {
                // handle imports and includes
                if (mapValue instanceof String) {
                    setImportsBasedOnMapValue(parsedInformation, (String)mapValue);
                    absoluteImportPath = convertRelativePathToAbsolutePath(parentFilePath, (String)mapValue);
                    handleImport(repositoryId, version, imports, (String)mapValue, sourceCodeRepoInterface, absoluteImportPath);
                }
            } else if (e.getKey().equalsIgnoreCase("run")) {
                // for workflows, bare files may be referenced. See https://github.com/dockstore/dockstore/issues/208
                //ex:
                //  run: {import: revtool.cwl}
                //  run: revtool.cwl
                if (mapValue instanceof String) {
                    setImportsBasedOnMapValue(parsedInformation, (String)mapValue);
                    absoluteImportPath = convertRelativePathToAbsolutePath(parentFilePath, (String)mapValue);
                    handleImport(repositoryId, version, imports, (String)mapValue, sourceCodeRepoInterface, absoluteImportPath);
                } else if (mapValue instanceof Map) {
                    // this handles the case where an import is used
                    handleMap(repositoryId, parentFilePath, version, imports, (Map)mapValue, sourceCodeRepoInterface);
                }
            } else {
                handleMapValue(repositoryId, parentFilePath, version, imports, mapValue, sourceCodeRepoInterface);
            }
        }
    }

    /**
     * Sets the type of imports in ParsedInformation based on the import string
     * @param parsedInformation     A version's version metadata's
     * @param mapValue              Import string (should be either a local import or an HTTP(s) import
     */
    public static void setImportsBasedOnMapValue(ParsedInformation parsedInformation, String mapValue) {
        String[] schemes = {"http", "https"};
        UrlValidator urlValidator = new UrlValidator(schemes);
        if (urlValidator.isValid(mapValue)) {
            parsedInformation.setHasHTTPImports(true);
        } else {
            parsedInformation.setHasLocalImports(true);
        }
    }

    /**
     * Iterate over object and pass any mappings to check for imports.
     * @param repositoryId              identifies the git repository that we wish to use, normally something like 'organization/repo_name`
     * @param parentFilePath            absolute path to the parent file which references the imported file
     * @param version                   version of the files to get
     * @param imports                   mapping of filenames to imports
     * @param mapValue                  CWL file object
     * @param sourceCodeRepoInterface   used too retrieve imports
     */
    private void handleMapValue(String repositoryId, String parentFilePath, Version version, Map<String, SourceFile> imports,
        Object mapValue, SourceCodeRepoInterface sourceCodeRepoInterface) {
        if (mapValue instanceof Map) {
            handleMap(repositoryId, parentFilePath, version, imports, (Map)mapValue, sourceCodeRepoInterface);
        } else if (mapValue instanceof List) {
            for (Object listMember : (List)mapValue) {
                handleMapValue(repositoryId, parentFilePath, version, imports, listMember, sourceCodeRepoInterface);
            }
        }
    }

    /**
     * Will determine dockerPull from requirements or hints (requirements takes precedence)
     *
     * @param requirements
     * @param hints
     * @return
     */
    private String getRequirementOrHint(List<Object> requirements, List<Object> hints, String dockerPull) {
        dockerPull = getDockerRequirement(requirements, dockerPull);
        if (dockerPull == null) {
            dockerPull = getDockerHint(hints, dockerPull);
        }
        return dockerPull;
    }

    /**
     * Checks secondary file for docker pull information
     *
     * @param stepDockerRequirement
     * @param secondaryFileContents
     * @param gson
     * @param yaml
     * @return
     */
    private String parseSecondaryFile(String stepDockerRequirement, String secondaryFileContents, Gson gson, Yaml yaml) {
        if (secondaryFileContents != null) {
            Map<String, Object> entryMapping = yaml.loadAs(secondaryFileContents, Map.class);
            JSONObject entryJson = new JSONObject(entryMapping);

            List<Object> cltRequirements = null;
            List<Object> cltHints = null;

            // CWLAvro only supports requirements and hints as an array, must be converted
            entryJson = convertJSONObjectToArray("requirements", entryJson);
            entryJson = convertJSONObjectToArray("hints", entryJson);

            if (isExpressionTool(secondaryFileContents, yaml)) {
                final ExpressionTool expressionTool = gson.fromJson(entryJson.toString(), ExpressionTool.class);
                cltRequirements = expressionTool.getRequirements();
                cltHints = expressionTool.getHints();
            } else if (isTool(secondaryFileContents, yaml)) {
                final CommandLineTool commandLineTool = gson.fromJson(entryJson.toString(), CommandLineTool.class);
                cltRequirements = commandLineTool.getRequirements();
                cltHints = commandLineTool.getHints();
            } else if (isWorkflow(secondaryFileContents, yaml)) {
                final Workflow workflow = gson.fromJson(entryJson.toString(), Workflow.class);
                cltRequirements = workflow.getRequirements();
                cltHints = workflow.getHints();
            }
            // Check requirements and hints for docker pull info
            stepDockerRequirement = getRequirementOrHint(cltRequirements, cltHints, stepDockerRequirement);
        }
        return stepDockerRequirement;
    }

    /**
     * Converts a JSON Object in CWL to JSON Array
     * @param keyName Name of key to convert (Ex. requirements, hints)
     * @param entryJson JSON representation of file
     * @return Updated JSON representation of file
     */
    private JSONObject convertJSONObjectToArray(String keyName, JSONObject entryJson) {
        if (entryJson.has(keyName)) {
            if (entryJson.get(keyName) instanceof JSONObject) {
                JSONArray reqArray = new JSONArray();
                JSONObject requirements = (JSONObject)entryJson.get(keyName);
                requirements.keySet().stream().forEach(key -> {
                    JSONObject newReqEntry = requirements.getJSONObject(key);
                    newReqEntry.put("class", key);
                    reqArray.put(newReqEntry);
                });
                entryJson.put(keyName, reqArray);
            }
        }
        return entryJson;
    }

    /**
     * Given a list of CWL requirements, will return the DockerPull information if present.
     * If not will return the current docker path (currentDefault)
     *
     * @param requirements
     * @param currentDefault
     * @return
     */
    private String getDockerRequirement(List<Object> requirements, String currentDefault) {
        if (requirements != null) {
            for (Object requirement : requirements) {
                Object dockerRequirement = ((Map)requirement).get("class");
                Object dockerPull = ((Map)requirement).get("dockerPull");
                if (Objects.equals(dockerRequirement, "DockerRequirement") && dockerPull != null) {
                    return dockerPull.toString();
                }
            }
        }

        return currentDefault;
    }

    /**
     * Given a list of CWL hints, will return the DockerPull information if present.
     * If not will return the current docker path (currentDefault)
     *
     * @param hints
     * @param currentDefault
     * @return
     */
    private String getDockerHint(List<Object> hints, String currentDefault) {
        if (hints != null) {
            for (Object hint : hints) {
                Object dockerRequirement = ((Map)hint).get("class");
                Object dockerPull = ((Map)hint).get("dockerPull");
                if (Objects.equals(dockerRequirement, "DockerRequirement") && dockerPull != null) {
                    return dockerPull.toString();
                }
            }
        }

        return currentDefault;
    }

    /**
     * Checks if a file is a workflow (CWL)
     *
     * @param content
     * @return true if workflow, false otherwise
     */
    private boolean isWorkflow(String content, Yaml yaml) {
        if (!Strings.isNullOrEmpty(content)) {
            Map<String, Object> mapping = yaml.loadAs(content, Map.class);
            if (mapping.get("class") != null) {
                String cwlClass = mapping.get("class").toString();
                return "Workflow".equals(cwlClass);
            }
        }
        return false;
    }

    /**
     * Checks if a file is an expression tool (CWL)
     *
     * @param content
     * @return true if expression tool, false otherwise
     */
    private boolean isExpressionTool(String content, Yaml yaml) {
        if (!Strings.isNullOrEmpty(content)) {
            Map<String, Object> mapping = yaml.loadAs(content, Map.class);
            if (mapping.get("class") != null) {
                String cwlClass = mapping.get("class").toString();
                return "ExpressionTool".equals(cwlClass);
            }
        }
        return false;
    }

    /**
     * Checks if a file is a tool (CWL)
     *
     * @param content
     * @return true if tool, false otherwise
     */
    private boolean isTool(String content, Yaml yaml) {
        if (!Strings.isNullOrEmpty(content)) {
            Map<String, Object> mapping = yaml.loadAs(content, Map.class);
            if (mapping.get("class") != null) {
                String cwlClass = mapping.get("class").toString();
                return "CommandLineTool".equals(cwlClass);
            }
        }
        return false;
    }

    /**
     * Checks that the CWL file is the correct version
     * @param content
     * @param yaml
     * @return true if file is valid CWL version, false otherwise
     */
    private boolean isValidCwl(String content, Yaml yaml) {
        try {
            Map<String, Object> mapping = yaml.loadAs(content, Map.class);
            final Object cwlVersion = mapping.get("cwlVersion");

            if (cwlVersion != null) {
                final boolean startsWith = cwlVersion.toString().startsWith(CWLHandler.CWL_VERSION_PREFIX);
                if (!startsWith) {
                    LOG.error("detected invalid version: " + cwlVersion.toString());
                }
                return startsWith;
            }
        } catch (ClassCastException | YAMLException e) {
            return false;
        }
        return false;
    }

    /**
     * Given an array of sources, will look for dependencies in the source name
     *
     * @param sources
     * @return filtered list of dependent sources
     */
    private List<String> filterDependent(List<String> sources, String nodePrefix) {
        List<String> filteredArray = new ArrayList<>();

        for (String s : sources) {
            String[] split = s.split("/");
            if (split.length > 1) {
                filteredArray.add(nodePrefix + split[0].replaceFirst("#", ""));
            }
        }

        return filteredArray;
    }

    @Override
    public VersionTypeValidation validateWorkflowSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        List<DescriptorLanguage.FileType> fileTypes = new ArrayList<>(Collections.singletonList(DescriptorLanguage.FileType.DOCKSTORE_CWL));
        Set<SourceFile> filteredSourcefiles = filterSourcefiles(sourcefiles, fileTypes);
        Optional<SourceFile> mainDescriptor = filteredSourcefiles.stream().filter((sourceFile -> Objects.equals(sourceFile.getPath(), primaryDescriptorFilePath))).findFirst();

        boolean isValid = true;
        StringBuilder validationMessage = new StringBuilder();
        Map<String, String> validationMessageObject = new HashMap<>();

        if (mainDescriptor.isPresent()) {
            Yaml yaml = new Yaml();
            String content = mainDescriptor.get().getContent();
            if (content == null || content.isEmpty()) {
                isValid = false;
                validationMessage.append("Primary descriptor is empty.");
            } else if (!content.contains("class: Workflow")) {
                isValid = false;
                validationMessage.append("A CWL workflow requires 'class: Workflow'.");
                if (content.contains("class: CommandLineTool") || content.contains("class: ExpressionTool")) {
                    String cwlClass = content.contains("class: CommandLineTool") ? "CommandLineTool" : "ExpressionTool";
                    validationMessage.append(" This file contains 'class: ").append(cwlClass).append("'. Did you mean to register a tool?");
                }
            } else if (!this.isValidCwl(content, yaml)) {
                isValid = false;
                validationMessage.append("Invalid CWL version.");
            }
        } else {
            validationMessage.append("Primary CWL descriptor is not present.");
            isValid = false;
        }

        if (isValid) {
            return new VersionTypeValidation(true, Collections.emptyMap());
        } else {
            validationMessageObject.put(primaryDescriptorFilePath, validationMessage.toString());
            return new VersionTypeValidation(false, validationMessageObject);
        }
    }

    @Override
    public VersionTypeValidation validateToolSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        List<DescriptorLanguage.FileType> fileTypes = new ArrayList<>(Collections.singletonList(DescriptorLanguage.FileType.DOCKSTORE_CWL));
        Set<SourceFile> filteredSourceFiles = filterSourcefiles(sourcefiles, fileTypes);
        Optional<SourceFile> mainDescriptor = filteredSourceFiles.stream().filter((sourceFile -> Objects.equals(sourceFile.getPath(), primaryDescriptorFilePath))).findFirst();

        boolean isValid = true;
        String validationMessage = null;
        Map<String, String> validationMessageObject = new HashMap<>();

        if (mainDescriptor.isPresent()) {
            Yaml yaml = new Yaml();
            String content = mainDescriptor.get().getContent();
            if (content == null || content.isEmpty()) {
                isValid = false;
                validationMessage = "Primary CWL descriptor is empty.";
            } else if (!content.contains("class: CommandLineTool") && !content.contains("class: ExpressionTool")) {
                isValid = false;
                validationMessage = "A CWL tool requires 'class: CommandLineTool' or 'class: ExpressionTool'.";
                if (content.contains("class: Workflow")) {
                    validationMessage += " This file contains 'class: Workflow'. Did you mean to register a workflow?";
                }
            } else if (!this.isValidCwl(content, yaml)) {
                isValid = false;
                validationMessage = "Invalid CWL version.";
            }
        } else {
            isValid = false;
            validationMessage = "Primary CWL descriptor is not present.";
        }

        validationMessageObject.put(primaryDescriptorFilePath, validationMessage);
        return new VersionTypeValidation(isValid, validationMessageObject);
    }

    @Override
    public VersionTypeValidation validateTestParameterSet(Set<SourceFile> sourceFiles) {
        return checkValidJsonAndYamlFiles(sourceFiles, DescriptorLanguage.FileType.CWL_TEST_JSON);
    }
}
