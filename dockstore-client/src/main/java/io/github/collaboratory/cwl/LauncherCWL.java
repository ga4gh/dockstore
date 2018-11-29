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

package io.github.collaboratory.cwl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.UUID;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.cwl.avro.CWL;
import io.cwl.avro.CommandLineTool;
import io.cwl.avro.CommandOutputParameter;
import io.cwl.avro.Workflow;
import io.cwl.avro.WorkflowOutputParameter;
import io.dockstore.client.cli.nested.NotificationsClients.NotificationsClient;
import io.github.collaboratory.cwl.cwlrunner.CWLRunnerFactory;
import io.dockstore.common.FileProvisioning;
import io.dockstore.common.Utilities;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import static io.dockstore.client.cli.ArgumentUtility.out;

/**
 * @author boconnor 9/24/15
 * @author dyuen
 * @author tetron
 */
public class LauncherCWL {

    private static final String DEFAULT_CROMWELL_VERSION = "36";
    private static final Logger LOG = LoggerFactory.getLogger(LauncherCWL.class);

    private static final String WORKING_DIRECTORY = "working-directory";
    private final String configFilePath;
    private final String imageDescriptorPath;
    private final String runtimeDescriptorPath;
    private final String notificationsUUID;
    private final OutputStream stdoutStream;
    private final OutputStream stderrStream;
    private final Yaml yaml = new Yaml(new SafeConstructor());
    private final Gson gson;
    private final FileProvisioning fileProvisioning;
    private final String originalTestParameterFilePath;
    private INIConfiguration config;
    private String globalWorkingDir;

    /**
     * Constructor for shell-based launch
     *
     * @param args raw arguments from the command-line
     */
    public LauncherCWL(String[] args) throws CWL.GsonBuildException {
        // create the command line parser
        CommandLineParser parser = new DefaultParser();
        // parse command line
        CommandLine line = parseCommandLine(parser, args);
        configFilePath = line.getOptionValue("config");
        imageDescriptorPath = line.getOptionValue("descriptor");
        runtimeDescriptorPath = line.getOptionValue("job");
        notificationsUUID = line.getOptionValue("notificationsUUID");
        originalTestParameterFilePath = "";
        this.stdoutStream = null;
        this.stderrStream = null;
        gson = CWL.getTypeSafeCWLToolDocument();
        fileProvisioning = new FileProvisioning(configFilePath);
    }

    /**
     * Constructor for programmatic launch
     *
     * @param configFilePath        configuration for this launcher
     * @param imageDescriptorPath   descriptor for the tool itself
     * @param runtimeDescriptorPath descriptor for this run of the tool
     * @param stdoutStream          pass a stream in order to capture stdout from the run tool
     * @param stderrStream          pass a stream in order to capture stderr from the run tool
     * @param uuid
     */
    public LauncherCWL(String configFilePath, String imageDescriptorPath, String runtimeDescriptorPath, OutputStream stdoutStream,
            OutputStream stderrStream, String originalTestParameterFilePath, String uuid) {
        this.configFilePath = configFilePath;
        this.imageDescriptorPath = imageDescriptorPath;
        this.runtimeDescriptorPath = runtimeDescriptorPath;
        this.notificationsUUID = uuid;
        this.originalTestParameterFilePath = originalTestParameterFilePath;
        fileProvisioning = new FileProvisioning(configFilePath);
        this.stdoutStream = stdoutStream;
        this.stderrStream = stderrStream;
        gson = CWL.getTypeSafeCWLToolDocument();
    }

    /**
     * Prints and stores the stdout and stderr to files
     * @param workingDir where to save stderr and stdout
     * @param execute    a pair holding the unformatted stderr and stderr
     * @param stdout     formatted stdout for outpuit
     * @param stderr     formatted stderr for output
     * @param executor    help text explaining name of integration
     */
    public static void outputIntegrationOutput(String workingDir, ImmutablePair<String, String> execute, String stdout, String stderr,
            String executor) {
        System.out.println(executor + " stdout:\n" + stdout);
        System.out.println(executor + " stderr:\n" + stderr);
        try {
            final Path path = Paths.get(workingDir + File.separator + executor + ".stdout.txt");
            FileUtils.writeStringToFile(path.toFile(), execute.getLeft(), StandardCharsets.UTF_8, false);
            System.out.println("Saving copy of " + executor + " stdout to: " + path.toAbsolutePath().toString());
            final Path txt2 = Paths.get(workingDir + File.separator + executor + ".stderr.txt");
            FileUtils.writeStringToFile(txt2.toFile(), execute.getRight(), StandardCharsets.UTF_8, false);
            System.out.println("Saving copy of " + executor + " stderr to: " + txt2.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException("unable to save " + executor + " output", e);
        }
    }

    private File getCromwellTargetFile() {
        // initialize cromwell location from ~/.dockstore/config
        String cromwellVersion = config.getString("cromwell-version", DEFAULT_CROMWELL_VERSION);
        String cromwellLocation =
                "https://github.com/broadinstitute/cromwell/releases/download/" + cromwellVersion + "/cromwell-" + cromwellVersion + ".jar";
        if (!Objects.equals(DEFAULT_CROMWELL_VERSION, cromwellVersion)) {
            System.out.println("Running with Cromwell " + cromwellVersion + " , Dockstore tests with " + DEFAULT_CROMWELL_VERSION);
        }

        // grab the cromwell jar if needed
        String libraryLocation =
                System.getProperty("user.home") + File.separator + ".dockstore" + File.separator + "libraries" + File.separator;
        URL cromwellURL;
        String cromwellFileName;
        try {
            cromwellURL = new URL(cromwellLocation);
            cromwellFileName = new File(cromwellURL.toURI().getPath()).getName();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new RuntimeException("Could not create cromwell location", e);
        }
        String cromwellTarget = libraryLocation + cromwellFileName;
        File cromwellTargetFile = new File(cromwellTarget);
        if (!cromwellTargetFile.exists()) {
            try {
                FileUtils.copyURLToFile(cromwellURL, cromwellTargetFile);
            } catch (IOException e) {
                throw new RuntimeException("Could not download cromwell location", e);
            }
        }
        return cromwellTargetFile;
    }

    public void run(Class cwlClassTarget, File workingDirectory) {
        // Setup notifications
        config = Utilities.parseConfig(configFilePath);
        CWLRunnerFactory.setConfig(config);
        String notificationsWebHookURL = config.getString("notifications", "");
        NotificationsClient notificationsClient = new NotificationsClient(notificationsWebHookURL, notificationsUUID);

        // Load CWL from JSON to object
        CWL cwlUtil = new CWL(false, config);
        // This won't work since I am using zip files, it is expecting files to be unzipped
        final String imageDescriptorContent = cwlUtil.parseCWL(imageDescriptorPath).getLeft();
        Object cwlObject;
        try {
            cwlObject = gson.fromJson(imageDescriptorContent, cwlClassTarget);
            if (cwlObject == null) {
                LOG.info("CWL file was null.");
                return;
            }
        } catch (JsonParseException ex) {
            LOG.error("The CWL file provided is invalid.");
            return;
        }

        // Load parameter file into map
        Map<String, Object> inputsAndOutputsJson = loadJob(runtimeDescriptorPath);
        if (inputsAndOutputsJson == null) {
            LOG.info("Cannot load job object.");
            return;
        }

        // Setup directories
        globalWorkingDir = setupDirectories();

        // Provision input files
        Map<String, FileProvisioning.FileInfo> inputsId2dockerMountMap;
        Map<String, List<FileProvisioning.FileInfo>> outputMap;
        notificationsClient.sendMessage(NotificationsClient.PROVISION_INPUT, true);
        String provisionedParameterPath;
        out("Provisioning your input files to your local machine");
        try {
            if (cwlObject instanceof Workflow) {
                Workflow workflow = (Workflow)cwlObject;
                SecondaryFilesUtility secondaryFilesUtility = new SecondaryFilesUtility(cwlUtil, this.gson);
                secondaryFilesUtility.modifyWorkflowToIncludeToolSecondaryFiles(workflow);

                // Pull input files
                inputsId2dockerMountMap = pullFiles(workflow, inputsAndOutputsJson);

                // Prep outputs, just creates output dir and records what the local output path will be
                outputMap = prepUploadsWorkflow(workflow, inputsAndOutputsJson);

            } else if (cwlObject instanceof CommandLineTool) {
                CommandLineTool commandLineTool = (CommandLineTool)cwlObject;
                // Pull input files
                inputsId2dockerMountMap = pullFiles(commandLineTool, inputsAndOutputsJson);

                // Prep outputs, just creates output dir and records what the local output path will be
                outputMap = prepUploadsTool(commandLineTool, inputsAndOutputsJson);
            } else {
                throw new UnsupportedOperationException("CWL target type not supported yet");
            }
            // Create updated JSON inputs document
            provisionedParameterPath = createUpdatedInputsAndOutputsJson(inputsId2dockerMountMap, outputMap, inputsAndOutputsJson);

        } catch (Exception e) {
            notificationsClient.sendMessage(NotificationsClient.PROVISION_INPUT, false);
            throw e;
        }

        // Create command to run Cromwell
        notificationsClient.sendMessage(NotificationsClient.RUN, true);
        final List<String> runCommand;
        File localPrimaryDescriptorFile = new File(imageDescriptorPath);
        runCommand = Lists.newArrayList(localPrimaryDescriptorFile.getAbsolutePath(), "--inputs", provisionedParameterPath);
        File cromwellTargetFile = getCromwellTargetFile();
        final String[] s = { "java", "-jar", cromwellTargetFile.getAbsolutePath(), "run" };
        List<String> arguments = new ArrayList<>();
        arguments.addAll(Arrays.asList(s));
        arguments.addAll(runCommand);

        // Execute Cromwell command using the temp working dir as the working directory for Cromwell
        int exitCode = 0;
        String stdout;
        String stderr;
        try {
            final String join = Joiner.on(" ").join(arguments);
            out(join);
            final ImmutablePair<String, String> execute = Utilities.executeCommand(join, workingDirectory);
            stdout = execute.getLeft();
            stderr = execute.getRight();
        } catch (RuntimeException e) {
            LOG.error("Problem running cromwell: ", e);
            notificationsClient.sendMessage(NotificationsClient.RUN, false);
            throw new RuntimeException("Could not run Cromwell", e);
        } finally {
            out("Cromwell exit code: " + exitCode);
        }

        // Provision the output
        notificationsClient.sendMessage(NotificationsClient.PROVISION_OUTPUT, true);
        try {
            // Display output information
            LauncherCWL.outputIntegrationOutput(localPrimaryDescriptorFile.getParentFile().getAbsolutePath(), ImmutablePair.of(stdout, stderr), stdout,
                    stderr, "Cromwell");

            // Grab outputs object from Cromwell output (TODO: This is incredibly fragile)
            String outputPrefix = "Succeeded";
            int startIndex = stdout.indexOf("\n{\n", stdout.indexOf(outputPrefix));
            int endIndex = stdout.indexOf("\n}\n", startIndex) + 2;
            String bracketContents = stdout.substring(startIndex, endIndex).trim();
            if (bracketContents.isEmpty()) {
                throw new RuntimeException("No cromwell output");
            }
            Map<String, Object> outputJson = new Gson().fromJson(bracketContents, HashMap.class);

            // Find the name of the workflow that is used as a suffix for workflow output IDs
            startIndex = stdout.indexOf("Pre-Processing ");
            endIndex = stdout.indexOf("\n", startIndex);
            String temporaryWorkflowPath = stdout.substring(startIndex, endIndex).trim();
            String[] splitPath = temporaryWorkflowPath.split("/");
            String workflowName = splitPath[splitPath.length - 1];

            // Create a list of pairs of output ID and FileInfo objects used for uploading files
            List<ImmutablePair<String, FileProvisioning.FileInfo>> outputList = registerOutputFiles(outputMap, (Map<String, Object>)outputJson.get("outputs"), workflowName);

            // Provision output files
            this.fileProvisioning.uploadFiles(outputList);

        } catch (Exception e) {
            notificationsClient.sendMessage(NotificationsClient.PROVISION_OUTPUT, false);
            throw e;
        }

        notificationsClient.sendMessage(NotificationsClient.COMPLETED, true);
        out("Workflow has succeeded.");
    }

    /**
     * Scours a CWL document paired with a JSON document to create our data structure for describing desired output files (for provisoning)
     *
     * @param cwl           deserialized CWL document
     * @param inputsOutputs inputs and output from json document
     * @return a map containing all output files either singly or in arrays
     */
    private Map<String, List<FileProvisioning.FileInfo>> prepUploadsTool(CommandLineTool cwl, Map<String, Object> inputsOutputs) {

        Map<String, List<FileProvisioning.FileInfo>> fileMap = new HashMap<>();

        LOG.info("PREPPING UPLOADS...");

        final List<CommandOutputParameter> outputs = cwl.getOutputs();

        // for each file input from the CWL
        for (CommandOutputParameter file : outputs) {
            LOG.info(file.toString());
            handleParameter(inputsOutputs, fileMap, file.getId().toString());
        }
        return fileMap;
    }

    private Map<String, List<FileProvisioning.FileInfo>> prepUploadsWorkflow(Workflow workflow, Map<String, Object> inputsOutputs) {

        Map<String, List<FileProvisioning.FileInfo>> fileMap = new HashMap<>();

        LOG.info("PREPPING UPLOADS...");

        final List<WorkflowOutputParameter> outputs = workflow.getOutputs();

        // for each file input from the CWL
        for (WorkflowOutputParameter file : outputs) {
            LOG.info(file.toString());
            handleParameter(inputsOutputs, fileMap, file.getId().toString());
        }
        return fileMap;
    }

    private void handleParameter(Map<String, Object> inputsOutputs, Map<String, List<FileProvisioning.FileInfo>> fileMap,
            String fileIdString) {
        // pull back the name of the input from the CWL
        String cwlID = fileIdString.contains("#") ? fileIdString.split("#")[1] : fileIdString;
        LOG.info("ID: {}", cwlID);
        prepUploadsHelper(inputsOutputs, fileMap, cwlID);
    }

    /**
     * @param inputsOutputs a map of both inputs and outputs to their data in the input json file
     * @param fileMap       stores the results of each file provision event
     * @param cwlID         the cwl id of the file that we are attempting to process
     */
    private void prepUploadsHelper(Map<String, Object> inputsOutputs, final Map<String, List<FileProvisioning.FileInfo>> fileMap,
            String cwlID) {
        // now that I have an input name from the CWL I can find it in the JSON parameterization for this run
        LOG.info("JSON: {}", inputsOutputs);
        if (inputsOutputs.containsKey(cwlID)) {
            Object jsonParameters = inputsOutputs.get(cwlID);
            if (jsonParameters instanceof Map || jsonParameters instanceof List) {
                if (jsonParameters instanceof Map) {
                    Map param = (Map<String, Object>)jsonParameters;
                    handleOutputFile(fileMap, cwlID, param);
                } else {
                    assert (jsonParameters instanceof List);
                    for (Object entry : (List)jsonParameters) {
                        if (entry instanceof Map) {
                            handleOutputFile(fileMap, cwlID, (Map<String, Object>)entry);
                        }
                    }
                }
            } else {
                System.out.println("WARNING: Output malformed for \"" + cwlID + "\" provisioning by default to working directory");
                handleOutputFileToWorkingDirectory(fileMap, cwlID);
            }
        } else {
            System.out.println("WARNING: Output location not found for \"" + cwlID + "\" provisioning by default to working directory");
            handleOutputFileToWorkingDirectory(fileMap, cwlID);
        }
    }

    private void handleOutputFileToWorkingDirectory(Map<String, List<FileProvisioning.FileInfo>> fileMap, String cwlID) {
        Map<String, Object> workDir = new HashMap<>();
        workDir.put("class", "Directory");
        workDir.put("path", ".");
        handleOutputFile(fileMap, cwlID, workDir);
    }

    /**
     * Handles one output file for upload
     *
     * @param fileMap stores the results of each file provision event
     * @param cwlID   the cwl id of the file that we are attempting to process
     * @param param   the parameter from the json input file
     */
    private void handleOutputFile(Map<String, List<FileProvisioning.FileInfo>> fileMap, final String cwlID, Map<String, Object> param) {
        String path = (String)param.get("path");
        // if it's the current one
        LOG.info("PATH TO UPLOAD TO: {} FOR {}", path, cwlID);

        // output
        // TODO: poor naming here, need to cleanup the variables
        // just file name
        // the file URL
        File filePathObj = new File(cwlID);
        String newDirectory = globalWorkingDir + "/outputs";
        Utilities.executeCommand("mkdir -p " + newDirectory);
        File newDirectoryFile = new File(newDirectory);
        String uuidPath = newDirectoryFile.getAbsolutePath() + "/" + filePathObj.getName();

        // now add this info to a hash so I can later reconstruct a docker -v command
        FileProvisioning.FileInfo new1 = new FileProvisioning.FileInfo();
        new1.setUrl(path);
        new1.setLocalPath(uuidPath);
        if (param.containsKey("metadata")) {
            byte[] metadatas = Base64.getDecoder().decode((String)param.get("metadata"));
            new1.setMetadata(new String(metadatas, StandardCharsets.UTF_8));
        }
        fileMap.putIfAbsent(cwlID, new ArrayList<>());
        fileMap.get(cwlID).add(new1);

        if (param.containsKey("class") && param.get("class").toString().equalsIgnoreCase("Directory")) {
            Utilities.executeCommand("mkdir -p " + uuidPath);
            new1.setDirectory(true);
        }

        LOG.info("UPLOAD FILE: LOCAL: {} URL: {}", cwlID, path);
    }

    /**
     * This function modifies the current parameter object's secondary files to use absolute paths instead of relative paths
     *
     * @param param     The current parameter object than contains the class, path, and secondary files
     * @param fileMap   The map that contains the absolute paths
     * @param paramName The parameter name
     */
    private void modifySecondaryFiles(Map<String, Object> param, Map<String, FileProvisioning.FileInfo> fileMap, String paramName) {
        Gson googleJson = new Gson();
        Object secondaryFiles = param.get("secondaryFiles");
        if (secondaryFiles != null) {
            String json = googleJson.toJson(secondaryFiles);
            ArrayList<Map<String, String>> data = googleJson.fromJson(json, ArrayList.class);
            for (Object suspectedFileMap : data) {
                if (suspectedFileMap instanceof Map) {
                    Map<String, String> currentFileMap = (Map)suspectedFileMap;
                    final String localPath = fileMap.get(paramName + ":" + currentFileMap.get("path")).getLocalPath();
                    currentFileMap.put("path", localPath);
                } else {
                    System.err.println("WARNING: We did not understand secondary files for \"" + paramName + "\" , skipping");
                }
            }
            param.put("secondaryFiles", data);

        }
    }

    /**
     * fudge
     *
     * @param fileMap
     * @param outputMap
     * @param inputsAndOutputsJson
     * @return
     */
    private String createUpdatedInputsAndOutputsJson(Map<String, FileProvisioning.FileInfo> fileMap,
            Map<String, List<FileProvisioning.FileInfo>> outputMap, Map<String, Object> inputsAndOutputsJson) {

        JSONObject newJSON = new JSONObject();

        for (Entry<String, Object> entry : inputsAndOutputsJson.entrySet()) {
            String paramName = entry.getKey();

            final Object currentParam = entry.getValue();
            if (currentParam instanceof Map) {
                Map<String, Object> param = (Map<String, Object>)currentParam;

                rewriteParamField(fileMap, outputMap, paramName, param, "path");
                rewriteParamField(fileMap, outputMap, paramName, param, "location");

                // now add to the new JSON structure
                JSONObject newRecord = new JSONObject();

                param.entrySet().forEach(paramEntry -> newRecord.put(paramEntry.getKey(), paramEntry.getValue()));
                newJSON.put(paramName, newRecord);

                // TODO: fill in for all possible types
            } else if (currentParam instanceof Integer || currentParam instanceof Double || currentParam instanceof Float
                    || currentParam instanceof Boolean || currentParam instanceof String) {
                newJSON.put(paramName, currentParam);
            } else if (currentParam instanceof List) {
                // this code kinda assumes that if a list exists, its a list of files which is not correct
                List currentParamList = (List)currentParam;
                for (Object entry2 : currentParamList) {
                    if (entry2 instanceof Map) {

                        Map<String, Object> param = (Map<String, Object>)entry2;
                        String path = (String)param.get("path");
                        this.modifySecondaryFiles(param, fileMap, paramName);

                        LOG.info("PATH: {} PARAM_NAME: {}", path, paramName);
                        // will be null for output, only dealing with inputs currently
                        // TODO: can outputs be file arrays too???  Maybe need to do something for globs??? Need to investigate
                        if (fileMap.get(paramName + ":" + path) != null) {
                            final String localPath = fileMap.get(paramName + ":" + path).getLocalPath();
                            param.put("path", localPath);
                            LOG.info("NEW FULL PATH: {}", localPath);
                        }
                        // now add to the new JSON structure
                        JSONArray exitingArray = (JSONArray)newJSON.get(paramName);
                        if (exitingArray == null) {
                            exitingArray = new JSONArray();
                        }
                        JSONObject newRecord = new JSONObject();
                        param.entrySet().forEach(paramEntry -> newRecord.put(paramEntry.getKey(), paramEntry.getValue()));
                        exitingArray.add(newRecord);
                        newJSON.put(paramName, exitingArray);
                    } else if (entry2 instanceof ArrayList) {
                        try {
                            JSONArray exitingArray2 = new JSONArray();
                            // now add to the new JSON structure
                            JSONArray exitingArray = (JSONArray)newJSON.get(paramName);
                            if (exitingArray == null) {
                                exitingArray = new JSONArray();
                            }
                            for (Map linkedHashMap : (ArrayList<LinkedHashMap>)entry2) {
                                Map<String, Object> param = linkedHashMap;
                                String path = (String)param.get("path");

                                this.modifySecondaryFiles(param, fileMap, paramName);

                                if (fileMap.get(paramName + ":" + path) != null) {
                                    final String localPath = fileMap.get(paramName + ":" + path).getLocalPath();
                                    param.put("path", localPath);
                                    LOG.info("NEW FULL PATH: {}", localPath);
                                }
                                JSONObject newRecord = new JSONObject();
                                param.entrySet().forEach(paramEntry -> newRecord.put(paramEntry.getKey(), paramEntry.getValue()));
                                exitingArray.add(newRecord);
                            }
                            exitingArray2.add(exitingArray);
                            newJSON.put(paramName, exitingArray2);
                        } catch (ClassCastException e) {
                            LOG.warn("This is not an array of array of files, it may be an array of array of strings");
                            newJSON.put(paramName, currentParam);
                        }
                    } else {
                        newJSON.put(paramName, currentParam);
                    }
                }

            } else {
                throw new RuntimeException(
                        "we found an unexpected datatype as follows: " + currentParam.getClass() + "\n with content " + currentParam);
            }
        }

        // make an updated JSON file that will be used to run the workflow
        writeJob(globalWorkingDir + "/workflow_params.json", newJSON);
        return globalWorkingDir + "/workflow_params.json";
    }

    /**
     * @param fileMap           map of input files
     * @param outputMap         map of output files
     * @param paramName         parameter name handle
     * @param param             the actual CWL parameter map
     * @param replacementTarget the parameter path to rewrite
     */
    private void rewriteParamField(Map<String, FileProvisioning.FileInfo> fileMap, Map<String, List<FileProvisioning.FileInfo>> outputMap,
            String paramName, Map<String, Object> param, String replacementTarget) {
        if (!param.containsKey(replacementTarget)) {
            return;
        }
        String path = (String)param.get(replacementTarget);
        LOG.info("PATH: {} PARAM_NAME: {}", path, paramName);
        // will be null for output
        if (fileMap.get(paramName) != null) {
            final String localPath = fileMap.get(paramName).getLocalPath();
            param.put(replacementTarget, localPath);
            LOG.info("NEW FULL PATH: {}", localPath);
        } else if (outputMap.get(paramName) != null) {
            //TODO: just the get the first one for a default? probably not correct
            final String localPath = outputMap.get(paramName).get(0).getLocalPath();
            param.put(replacementTarget, localPath);
            LOG.info("NEW FULL PATH: {}", localPath);
        }
    }

    private Map<String, Object> loadJob(String jobPath) {
        try {
            return (Map<String, Object>)yaml.load(new FileInputStream(jobPath));
        } catch (FileNotFoundException e) {
            throw new RuntimeException("could not load job from yaml", e);
        }
    }

    private void writeJob(String jobOutputPath, JSONObject newJson) {
        try {
            //TODO: investigate, why is this replacement occurring?
            final String replace = newJson.toJSONString().replace("\\", "");
            FileUtils.writeStringToFile(new File(jobOutputPath), replace, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not write job ", e);
        }
    }

    private String setupDirectories() {

        LOG.info("MAKING DIRECTORIES...");
        // directory to use, typically a large, encrypted filesystem
        String workingDir = config.getString(WORKING_DIRECTORY, System.getProperty("user.dir") + "/datastore/");
        // make UUID
        UUID uuid = UUID.randomUUID();
        // setup directories
        globalWorkingDir = workingDir + "/launcher-" + uuid;
        System.out.println("Creating directories for run of Dockstore launcher at: " + globalWorkingDir);

        Path globalWorkingPath = Paths.get(globalWorkingDir);

        try {
            Files.createDirectories(Paths.get(workingDir));
            Files.createDirectories(globalWorkingPath);
            Files.createDirectories(Paths.get(globalWorkingDir, "working"));
            Files.createDirectories(Paths.get(globalWorkingDir, "inputs"));
            Files.createDirectories(Paths.get(globalWorkingDir, "outputs"));
            Files.createDirectories(Paths.get(globalWorkingDir, "tmp"));
        } catch (IOException e) {
            throw new RuntimeException("unable to create datastore directories", e);
        }
        return globalWorkingDir;
    }

    /**
     * @param fileMap      indicates which output files need to be provisioned where
     * @param outputObject provides information on the output files from cwltool
     */
    List<ImmutablePair<String, FileProvisioning.FileInfo>> registerOutputFiles(Map<String, List<FileProvisioning.FileInfo>> fileMap,
            Map<String, Object> outputObject, String workflowName) {

        LOG.info("UPLOADING FILES...");
        List<ImmutablePair<String, FileProvisioning.FileInfo>> outputSet = new ArrayList<>();

        for (Entry<String, List<FileProvisioning.FileInfo>> entry : fileMap.entrySet()) {
            List<FileProvisioning.FileInfo> files = entry.getValue();
            String key = workflowName + "." + entry.getKey();

            if ((outputObject.get(key) instanceof List)) {
                List<Map<String, Object>> cwltoolOutput = (List)outputObject.get(key);
                FileProvisioning.FileInfo file = files.get(0);
                if (files.size() == 1 && file.isDirectory()) {
                    // we're provisioning a number of files into a directory
                    for (Object currentEntry : cwltoolOutput) {
                        outputSet.addAll(handleOutputFileEntry(key, file, currentEntry));
                    }
                } else {
                    // lengths should be the same when not dealing with directories
                    assert (cwltoolOutput.size() == files.size());
                    // for through each one and handle it, we have to assume that the order matches?
                    final Iterator<Map<String, Object>> iterator = cwltoolOutput.iterator();
                    for (FileProvisioning.FileInfo info : files) {
                        final Map<String, Object> cwlToolOutputEntry = iterator.next();
                        outputSet.addAll(provisionOutputFile(key, info, cwlToolOutputEntry));
                    }
                }
            } else {
                assert (files.size() == 1);
                FileProvisioning.FileInfo file = files.get(0);
                final Map<String, Object> fileMapDataStructure = (Map)(outputObject).get(key);
                outputSet.addAll(provisionOutputFile(key, file, fileMapDataStructure));
            }
        }
        return outputSet;
    }

    private List<ImmutablePair<String, FileProvisioning.FileInfo>> handleOutputFileEntry(String key, FileProvisioning.FileInfo file,
            Object currentEntry) {
        List<ImmutablePair<String, FileProvisioning.FileInfo>> outputSet = new ArrayList<>();
        if (currentEntry instanceof Map) {
            Map<String, Object> map = (Map)currentEntry;
            outputSet.addAll(provisionOutputFile(key, file, map));
        } else if (currentEntry instanceof List) {
            // unwrap a list if it happens to be inside a list (as in bcbio)
            for (Object listEntry : (List)currentEntry) {
                outputSet.addAll(handleOutputFileEntry(key, file, listEntry));
            }
        } else {
            // output a warning if there is some other odd output structure we don't understand
            LOG.error("We don't understand provision out structure for: " + key + " ,skipping");
            System.out.println("Ignoring odd provision out structure for: " + key + " ,skipping");
        }
        return outputSet;
    }

    /**
     * Copy one output file to its final location
     *
     * @param key                  informational, identifies this file in the output
     * @param file                 information on the final resting place for the output file
     * @param fileMapDataStructure the CWLtool output which contains the path to the file after cwltool is done with it
     */
    private List<ImmutablePair<String, FileProvisioning.FileInfo>> provisionOutputFile(final String key, FileProvisioning.FileInfo file,
            final Map<String, Object> fileMapDataStructure) {

        List<ImmutablePair<String, FileProvisioning.FileInfo>> outputSet = new ArrayList<>();

        if (fileMapDataStructure == null) {
            System.out.println("Skipping: #" + key + " was null from Cromwell");
            return outputSet;
        }

        String cwlOutputPath = (String)fileMapDataStructure.get("path");
        // toil 3.15.0 uses location
        if (cwlOutputPath == null) {
            cwlOutputPath = (String)fileMapDataStructure.get("location");
        }
        if (cwlOutputPath == null) {
            System.out.println("Skipping: #" + key + " was null from Cromwell");
            return outputSet;
        }
        Path path = Paths.get(cwlOutputPath);
        if (!path.isAbsolute() || !Files.exists(path)) {
            // changing the cwlOutput path to an absolute path (bunny uses absolute, cwltool uses relative, but can change?!)
            Path currentRelativePath = Paths.get("");
            cwlOutputPath = currentRelativePath.toAbsolutePath().toString() + cwlOutputPath;
        }

        LOG.info("NAME: {} URL: {} FILENAME: {} CWL OUTPUT PATH: {}", file.getLocalPath(), file.getUrl(), key, cwlOutputPath);
        System.out.println("Registering: #" + key + " to provision from " + cwlOutputPath + " to : " + file.getUrl());
        outputSet.add(ImmutablePair.of(cwlOutputPath, file));

        if (fileMapDataStructure.containsKey("secondaryFiles")) {
            final List<Map<String, Object>> secondaryFiles = (List<Map<String, Object>>)fileMapDataStructure
                    .getOrDefault("secondaryFiles", new ArrayList<Map<String, Object>>());
            for (Map<String, Object> secondaryFile : secondaryFiles) {
                FileProvisioning.FileInfo fileInfo = new FileProvisioning.FileInfo();
                fileInfo.setLocalPath(file.getLocalPath());
                List<String> splitPathList = Lists.newArrayList(file.getUrl().split("/"));

                if (!file.isDirectory()) {
                    String mutatedSecondaryFile = mutateSecondaryFileName(splitPathList.get(splitPathList.size() - 1), (String)fileMapDataStructure.get("basename"), (String)secondaryFile.get("basename"));
                    // when the provision target is a specific file, trim that off
                    splitPathList.remove(splitPathList.size() - 1);
                    splitPathList.add(mutatedSecondaryFile);
                } else {
                    splitPathList.add((String)secondaryFile.get("basename"));
                }
                final String join = Joiner.on("/").join(splitPathList);
                fileInfo.setUrl(join);
                outputSet.addAll(provisionOutputFile(key, fileInfo, secondaryFile));
            }
        }
        return outputSet;
    }

    /**
     *
     * @param outputParameterFile the name of the base file in the parameter json
     * @param originalBaseName the name of the base file as output by the cwlrunner
     * @param renamedBaseName the name of the secondary associated with the base file as output by the cwlrunner
     * @return the name of the secondary file in the parameter json, mutated correctly to match outputParameterFile
     */
    private String mutateSecondaryFileName(String outputParameterFile, String originalBaseName, String renamedBaseName) {
        String commonPrefix = Strings.commonPrefix(originalBaseName, renamedBaseName);
        String mutationSuffixStart = originalBaseName.substring(commonPrefix.length());
        String mutationSuffixTarget = renamedBaseName.substring(commonPrefix.length());
        int replacementIndex = outputParameterFile.lastIndexOf(mutationSuffixStart);
        if (replacementIndex == -1) {
            // all extensions should be removed before adding on the target
            return FilenameUtils.removeExtension(outputParameterFile) + "." + mutationSuffixTarget;
        }
        return outputParameterFile.substring(0, replacementIndex) + mutationSuffixTarget;
    }

    private Map<String, FileProvisioning.FileInfo> pullFiles(Object cwlObject, Map<String, Object> inputsOutputs) {
        Map<String, FileProvisioning.FileInfo> fileMap = new HashMap<>();

        LOG.info("DOWNLOADING INPUT FILES...");

        final Method getInputs;
        try {
            getInputs = cwlObject.getClass().getDeclaredMethod("getInputs");
            final List<?> files = (List<?>)getInputs.invoke(cwlObject);

            List<Pair<String, Path>> pairs = new ArrayList<>();

            // for each file input from the CWL
            for (Object file : files) {
                // pull back the name of the input from the CWL
                LOG.info(file.toString());
                // remove the hash from the cwlInputFileID
                final Method getId = file.getClass().getDeclaredMethod("getId");
                String cwlInputFileID = getId.invoke(file).toString();
                // trim quotes or starting '#' if necessary
                cwlInputFileID = CharMatcher.is('#').trimLeadingFrom(cwlInputFileID);
                // split on # if needed
                cwlInputFileID = cwlInputFileID.contains("#") ? cwlInputFileID.split("#")[1] : cwlInputFileID;
                // remove extra namespace if needed
                cwlInputFileID = cwlInputFileID.contains("/") ? cwlInputFileID.split("/")[1] : cwlInputFileID;
                LOG.info("ID: {}", cwlInputFileID);

                List<String> secondaryFiles = getSecondaryFileStrings(file);
                pairs.addAll(pullFilesHelper(inputsOutputs, fileMap, cwlInputFileID, secondaryFiles));
            }
            fileProvisioning.provisionInputFiles(this.originalTestParameterFilePath, pairs);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            LOG.error("Reflection issue, this is likely a coding problem.");
            throw new RuntimeException();
        }
        return fileMap;
    }

    /**
     * @param file either an input or output parameter for both workflows and tools
     * @return A list of secondary files
     */
    private List<String> getSecondaryFileStrings(Object file) {
        try {
            // identify and get secondary files if needed
            final Method getSecondaryFiles = file.getClass().getDeclaredMethod("getSecondaryFiles");
            final Object invoke = getSecondaryFiles.invoke(file);
            List<String> secondaryFiles = null;
            if (invoke instanceof List) {
                secondaryFiles = (List<String>)invoke;
            } else if (invoke instanceof String) {
                secondaryFiles = Lists.newArrayList((String)invoke);
            }
            return secondaryFiles;
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            LOG.error("Reflection issue, this is likely a coding problem.");
            throw new RuntimeException();
        }
    }

    /**
     * @param inputsOutputs  json parameter file
     * @param fileMap        a record of the files that we have provisioned
     * @param cwlInputFileID the file id from the CWL file
     * @param secondaryFiles a record of secondary files that were identified
     */
    private List<Pair<String, Path>> pullFilesHelper(Map<String, Object> inputsOutputs, Map<String, FileProvisioning.FileInfo> fileMap,
            String cwlInputFileID, List<String> secondaryFiles) {

        List<Pair<String, Path>> inputSet = new ArrayList<>();

        // now that I have an input name from the CWL I can find it in the JSON parameterization for this run
        LOG.info("JSON: {}", inputsOutputs);
        for (Entry<String, Object> stringObjectEntry : inputsOutputs.entrySet()) {
            // in this case, the input is an array and not a single instance
            if (stringObjectEntry.getValue() instanceof ArrayList) {
                // need to handle case where it is an array, but not an array of files
                List stringObjectEntryList = (List)stringObjectEntry.getValue();
                for (Object entry : stringObjectEntryList) {
                    if (entry instanceof Map) {
                        Map lhm = (Map)entry;
                        if ((lhm.containsKey("path") && lhm.get("path") instanceof String) || (lhm.containsKey("location") && lhm
                                .get("location") instanceof String)) {
                            String path = getPathOrLocation(lhm);
                            // notice I'm putting key:path together so they are unique in the hash
                            if (stringObjectEntry.getKey().equals(cwlInputFileID)) {
                                inputSet.addAll(doProcessFile(stringObjectEntry.getKey() + ":" + path, path, cwlInputFileID, fileMap,
                                        secondaryFiles));
                            }
                        }
                    } else if (entry instanceof ArrayList) {
                        inputSet.addAll(processArrayofArrayOfFiles(entry, stringObjectEntry, cwlInputFileID, fileMap, secondaryFiles));
                    }
                }
                // in this case the input is a single instance and not an array
            } else if (stringObjectEntry.getValue() instanceof HashMap) {
                Map param = (HashMap)stringObjectEntry.getValue();
                String path = getPathOrLocation(param);
                if (stringObjectEntry.getKey().equals(cwlInputFileID)) {
                    inputSet.addAll(doProcessFile(stringObjectEntry.getKey(), path, cwlInputFileID, fileMap, secondaryFiles));
                }
            }
        }
        return inputSet;
    }

    private List<Pair<String, Path>> processArrayofArrayOfFiles(Object entry, Entry<String, Object> stringObjectEntry,
            String cwlInputFileID, Map<String, FileProvisioning.FileInfo> fileMap, List<String> secondaryFiles) {
        List<Pair<String, Path>> inputSet = new ArrayList<>();
        try {
            ArrayList<Map> filesArray = (ArrayList)entry;
            for (Map file : filesArray) {
                Map lhm = file;
                if ((lhm.containsKey("path") && lhm.get("path") instanceof String) || (lhm.containsKey("location") && lhm
                        .get("location") instanceof String)) {
                    String path = getPathOrLocation(lhm);
                    // notice I'm putting key:path together so they are unique in the hash
                    if (stringObjectEntry.getKey().equals(cwlInputFileID)) {
                        inputSet.addAll(
                                doProcessFile(stringObjectEntry.getKey() + ":" + path, path, cwlInputFileID, fileMap, secondaryFiles));
                    }
                }
            }
        } catch (ClassCastException e) {
            LOG.warn("This is not an array of array of files, it may be an array of array of strings");
        }
        return inputSet;
    }

    private String getPathOrLocation(Map param) {
        return ObjectUtils.firstNonNull((String)param.get("path"), (String)param.get("location"));
    }

    /**
     * Looks like this is intended to copy one file from source to a local destination
     *
     * @param key            what is this?
     * @param path           the path for the source of the file, whether s3 or http
     * @param cwlInputFileID looks like the descriptor for a particular path+class pair in the parameter json file, starts with a hash in the CWL file
     * @param fileMap        store information on each added file as a return type
     * @param secondaryFiles secondary files that also need to be transferred
     */
    private List<Pair<String, Path>> doProcessFile(final String key, final String path, final String cwlInputFileID,
            Map<String, FileProvisioning.FileInfo> fileMap, List<String> secondaryFiles) {

        List<Pair<String, Path>> inputSet = new ArrayList<>();
        // key is unique for that key:download URL, cwlInputFileID is just the key

        LOG.info("PATH TO DOWNLOAD FROM: {} FOR {} FOR {}", path, cwlInputFileID, key);

        // set up output paths
        String downloadDirectory = globalWorkingDir + "/inputs/" + UUID.randomUUID();
        System.out
                .println("Preparing download location for: #" + cwlInputFileID + " from " + path + " into directory: " + downloadDirectory);
        Utilities.executeCommand("mkdir -p " + downloadDirectory);
        File downloadDirFileObj = new File(downloadDirectory);

        inputSet.add(copyIndividualFile(key, path, fileMap, downloadDirFileObj, true));

        // also handle secondary files if specified
        if (secondaryFiles != null) {
            for (String sFile : secondaryFiles) {
                String sPath = path;
                while (sFile.startsWith("^")) {
                    sFile = sFile.replaceFirst("\\^", "");
                    int periodIndex = path.lastIndexOf(".");
                    if (periodIndex != -1) {
                        sPath = sPath.substring(0, periodIndex);
                    }
                }
                sPath = sPath + sFile;
                inputSet.add(copyIndividualFile(cwlInputFileID + ":" + sPath, sPath, fileMap, downloadDirFileObj, true));
            }
        }
        return inputSet;
    }

    /**
     * This methods seems to handle the copying of individual files
     *
     * @param key
     * @param path
     * @param fileMap
     * @param downloadDirFileObj
     * @param record             add a record to the fileMap
     */
    private Pair<String, Path> copyIndividualFile(String key, String path, Map<String, FileProvisioning.FileInfo> fileMap,
            File downloadDirFileObj, boolean record) {
        String shortfileName = Paths.get(path).getFileName().toString();
        final Path targetFilePath = Paths.get(downloadDirFileObj.getAbsolutePath(), shortfileName);
        // now add this info to a hash so I can later reconstruct a docker -v command
        FileProvisioning.FileInfo info = new FileProvisioning.FileInfo();
        info.setLocalPath(targetFilePath.toFile().getAbsolutePath());
        info.setUrl(path);
        // key may contain either key:download_URL for array inputs or just cwlInputFileID for scalar input
        if (record) {
            fileMap.put(key, info);
        }
        return ImmutablePair.of(path, targetFilePath);
    }

    private CommandLine parseCommandLine(CommandLineParser parser, String[] args) {
        try {
            // parse the command line arguments
            Options options = new Options();
            options.addOption("c", "config", true, "the INI config file for this tool");
            options.addOption("d", "descriptor", true, "a CWL tool descriptor used to construct the command and run it");
            options.addOption("j", "job", true, "a JSON parameterization of the CWL tool, includes URLs for inputs and outputs");
            return parser.parse(options, args);
        } catch (ParseException exp) {
            LOG.error("Unexpected exception:{}", exp.getMessage());
            throw new RuntimeException("Could not parse command-line", exp);
        }
    }

    private String trimAndPrintInput(String input) {
        input = input.trim();
        System.out.println(input);
        return input;
    }
}
