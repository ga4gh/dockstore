package io.dockstore.client.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import io.dockstore.client.cli.nested.WorkflowClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.UsersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.Entry;
import io.swagger.client.model.Workflow;

import static io.dockstore.client.cli.ArgumentUtility.CWL_STRING;
import static io.dockstore.client.cli.ArgumentUtility.WDL_STRING;
import static io.dockstore.client.cli.ArgumentUtility.containsHelpRequest;
import static io.dockstore.client.cli.ArgumentUtility.errorMessage;
import static io.dockstore.client.cli.ArgumentUtility.exceptionMessage;
import static io.dockstore.client.cli.ArgumentUtility.optVal;
import static io.dockstore.client.cli.ArgumentUtility.out;
import static io.dockstore.client.cli.ArgumentUtility.printFlagHelp;
import static io.dockstore.client.cli.ArgumentUtility.printHelpFooter;
import static io.dockstore.client.cli.ArgumentUtility.printHelpHeader;
import static io.dockstore.client.cli.ArgumentUtility.printLineBreak;
import static io.dockstore.client.cli.ArgumentUtility.printUsageHelp;
import static io.dockstore.client.cli.ArgumentUtility.reqVal;

/**
 * This implements all operations on the CLI that are specific to checkers
 * @author aduncan
 */
public class CheckerClient extends WorkflowClient {

    public CheckerClient(WorkflowsApi workflowApi, UsersApi usersApi, Client client, boolean isAdmin) {
        super(workflowApi, usersApi, client, isAdmin);
    }

    @Override
    public void printGeneralHelp() {
        printHelpHeader();
        printUsageHelp(getEntryType().toLowerCase());

        // Checker client help
        out("Commands:");
        out("");
        out("  add              :  Adds a checker workflow to and existing tool/workflow.");
        out("");
        out("  update           :  Updates an existing checker workflow of a tool/workflow.");
        out("");
        out("  download         :  Downloads all files associated with a checker workflow.");
        out("");
        out("  launch           :  Launch a checker workflow locally.");
        out("");
        out("  test_parameter   :  Add/Remove test parameter files for a checker workflow version.");
        out("");

        if (isAdmin) {
            printAdminHelp();
        }

        printLineBreak();
        printFlagHelp();
        printHelpFooter();
    }

    @Override
    public String getEntryType() {
        return "Checker";
    }

    @Override
    public boolean processEntryCommands(List<String> args, String activeCommand) throws IOException, ApiException {
        if (null != activeCommand) {
            switch (activeCommand) {
            case "add":
                addChecker(args);
                break;
            case "update":
                updateChecker(args);
                break;
            case "download":
                downloadChecker(args);
                break;
            case "launch":
                launchChecker(args);
                break;
            case "test_parameter":
                testParameterChecker(args);
                break;
            default:
                return false;
            }
            return true;
        }
        return false;
    }

    private void addChecker(List<String> args) {
        if (containsHelpRequest(args) || args.isEmpty()) {
            addCheckerHelp();
        } else {
            // Retrieve arguments
            String entryPath = reqVal(args, "--entry");
            String descriptorType = optVal(args, "--descriptor-type", "cwl");
            String descriptorPath = reqVal(args, "--descriptor-path");
            String inputParameterPath = optVal(args, "--input-parameter-path", null);

            // Check that descriptor type is valid
            descriptorType = descriptorType.toLowerCase();
            if (!Objects.equals(descriptorType, CWL_STRING) && !Objects.equals(descriptorType, WDL_STRING)) {
                errorMessage("The given descriptor type " + descriptorType + " is not valid.",
                    Client.CLIENT_ERROR);
            }

            // Check that descriptor path is valid
            if (!descriptorPath.startsWith("/")) {
                errorMessage("Descriptor paths must be absolute paths.",
                    Client.CLIENT_ERROR);
            }

            // Check that input parameter path is valid
            if (inputParameterPath != null && !inputParameterPath.startsWith("/")) {
                errorMessage("Input parameter path paths must be absolute paths.",
                    Client.CLIENT_ERROR);
            }

            // Get entry from path
            Entry entry = null;
            try {
                entry = workflowsApi.getEntryByPath(entryPath);
            } catch (ApiException ex) {
                exceptionMessage(ex, "Could not find the entry with path" + entryPath, Client.API_ERROR);
            }

            // Register the checker workflow
            if (entry != null) {
                try {
                    workflowsApi.registerCheckerWorkflow(descriptorPath, entry.getId(), descriptorType, inputParameterPath);
                    out("A checker workflow has been successfully added to entry with path " + entryPath);
                } catch (ApiException ex) {
                    exceptionMessage(ex, "There was a problem registering the checker workflow.", Client.API_ERROR);
                }
            }
        }
    }

    private void addCheckerHelp() {
        printHelpHeader();
        out("Usage: dockstore " + getEntryType().toLowerCase() + " add --help");
        out("       dockstore " + getEntryType().toLowerCase() + " add [parameters]");
        out("");
        out("Description:");
        out("  Add a checker workflow to an existing tool or workflow.");
        out("");
        out("Required Parameters:");
        out("  --entry <entry>                                                          Complete entry path in the Dockstore (ex. quay.io/collaboratory/seqware-bwa-workflow)");
        out("  --descriptor-type <descriptor-type>                                      cwl/wdl, defaults to cwl.");
        out("  --descriptor-path <descriptor-path>                                      Path to the main descriptor file.");
        out("");
        out("Optional Parameters:");
        out("  --input-parameter-path <input parameter path>                            Path to the input parameter path, defaults to that of the entry.");
        printHelpFooter();
    }

    private void updateChecker(List<String> args) {
        if (containsHelpRequest(args) || args.isEmpty()) {
            updateCheckerHelp();
        } else {
            // Retrieve arguments
            String entryPath = reqVal(args, "--entry");

            // Get entry from path
            Entry entry = null;
            try {
                entry = workflowsApi.getEntryByPath(entryPath);
            } catch (ApiException ex) {
                exceptionMessage(ex, "Could not find the entry with path" + entryPath, Client.API_ERROR);
            }

            Workflow checkerWorkflow = null;
            if (entry != null) {
                if (entry.getCheckerId() == null) {
                    errorMessage("The entry has no checker workflow.",
                        Client.CLIENT_ERROR);
                } else {
                    checkerWorkflow = workflowsApi.getWorkflow(entry.getCheckerId());
                }
            }

            // Update the checker workflow
            if (entry != null && checkerWorkflow != null) {
                String descriptorPath = optVal(args, "--default-descriptor-path", checkerWorkflow.getWorkflowPath());
                String inputParameterPath = optVal(args, "--default-test-parameter-path", checkerWorkflow.getDefaultTestParameterFilePath());

                // Check that descriptor path is valid
                if (!descriptorPath.startsWith("/")) {
                    errorMessage("Descriptor paths must be absolute paths.",
                        Client.CLIENT_ERROR);
                }

                // Check that input parameter path is valid
                if (inputParameterPath != null && !inputParameterPath.startsWith("/")) {
                    errorMessage("Input parameter path paths must be absolute paths.",
                        Client.CLIENT_ERROR);
                }

                // Update fields
                checkerWorkflow.setWorkflowPath(descriptorPath);
                checkerWorkflow.setDefaultTestParameterFilePath(inputParameterPath);

                try {
                    // Update the checker workflow
                    workflowsApi.updateWorkflow(checkerWorkflow.getId(), checkerWorkflow);

                    // Refresh the checker workflow
                    workflowsApi.refresh(checkerWorkflow.getId());
                    out("The workflow has been updated.");
                } catch (ApiException ex) {
                    exceptionMessage(ex, "There was a problem updating the checker workflow.", Client.API_ERROR);
                }
            }
        }
    }


    private void updateCheckerHelp() {
        printHelpHeader();
        out("Usage: dockstore " + getEntryType().toLowerCase() + " update --help");
        out("       dockstore " + getEntryType().toLowerCase() + " update [parameters]");
        out("");
        out("Description:");
        out("  Update an existing checker workflow associated with an entry.");
        out("");
        out("Required Parameters:");
        out("  --entry <entry>                                                          Complete entry path in the Dockstore (ex. quay.io/collaboratory/seqware-bwa-workflow)");
        out("");
        out("Optional Parameters:");
        out("  --default-test-parameter-path <input parameter path>                     Path to the input parameter path, defaults to that of the entry.");
        out("  --default-descriptor-path <descriptor-path>                              Path to the main descriptor file.");
        printHelpFooter();
    }

    private void downloadChecker(List<String> args) {
        if (containsHelpRequest(args) || args.isEmpty()) {
            downloadCheckerHelp();
        } else {
            // Get current directory
            String currentDirectory = Paths.get(".").toAbsolutePath().normalize().toString();

            // Retrieve arguments
            String entryPath = reqVal(args, "--entry");

            // Get entry from path
            Entry entry = null;
            try {
                entry = workflowsApi.getEntryByPath(entryPath);
            } catch (ApiException ex) {
                exceptionMessage(ex, "Could not find the entry with path" + entryPath, Client.API_ERROR);
            }

            // Get checker workflow
            Workflow checkerWorkflow = null;
            if (entry != null) {
                if (entry.getCheckerId() == null) {
                    errorMessage("The entry has no checker workflow.",
                        Client.CLIENT_ERROR);
                } else {
                    checkerWorkflow = workflowsApi.getWorkflow(entry.getCheckerId());
                }
            }

            // Download files
            if (entry != null && checkerWorkflow != null) {
                try {
                    File downloadFolder = new File(currentDirectory);
                    downloadDescriptorFiles(checkerWorkflow.getFullWorkflowPath(), checkerWorkflow.getDescriptorType(), downloadFolder);
                    out("Files have been successfully downloaded to the current directory.");
                } catch (IOException ex) {
                    exceptionMessage(ex, "Problems downloading files to " + currentDirectory, Client.IO_ERROR);
                }
            }
        }
    }


    private void downloadCheckerHelp() {
        printHelpHeader();
        out("Usage: dockstore " + getEntryType().toLowerCase() + " download --help");
        out("       dockstore " + getEntryType().toLowerCase() + " download [parameters]");
        out("");
        out("Description:");
        out("  Downloads all checker workflow files for the given entry and stores them in the current directory.");
        out("");
        out("Required Parameters:");
        out("  --entry <entry>                             Complete entry path in the Dockstore (ex. quay.io/collaboratory/seqware-bwa-workflow)");
        out("");
        printHelpFooter();
    }

    private void launchChecker(List<String> args) {
        if (containsHelpRequest(args) || args.isEmpty()) {
            launchHelp();
        } else {
            // Retrieve arguments
            String entryPath = reqVal(args, "--entry");

            // Get entry from path
            Entry entry = null;
            try {
                entry = workflowsApi.getEntryByPath(entryPath);
            } catch (ApiException ex) {
                exceptionMessage(ex, "Could not find the entry with path" + entryPath, Client.API_ERROR);
            }

            // Get checker workflow
            Workflow checkerWorkflow = null;
            if (entry != null) {
                if (entry.getCheckerId() == null) {
                    errorMessage("The entry has no checker workflow.",
                        Client.CLIENT_ERROR);
                } else {
                    checkerWorkflow = workflowsApi.getWorkflow(entry.getCheckerId());
                }
            }

            // Call parent launcher
            if (entry != null && checkerWorkflow != null) {
                // Readd entry path to call, but with checker workflow
                args.add("--entry");
                args.add(checkerWorkflow.getFullWorkflowPath());
                launch(args);
            }
        }
    }

    private void testParameterChecker(List<String> args) {
        if (containsHelpRequest(args) || args.isEmpty()) {
            testParameterHelp();
        } else {
            // Retrieve arguments
            String entryPath = reqVal(args, "--entry");

            // Get entry from path
            Entry entry = null;
            try {
                entry = workflowsApi.getEntryByPath(entryPath);
            } catch (ApiException ex) {
                exceptionMessage(ex, "Could not find the entry with path" + entryPath, Client.API_ERROR);
            }

            // Get checker workflow
            Workflow checkerWorkflow = null;
            if (entry != null) {
                if (entry.getCheckerId() == null) {
                    errorMessage("The entry has no checker workflow.",
                        Client.CLIENT_ERROR);
                } else {
                    checkerWorkflow = workflowsApi.getWorkflow(entry.getCheckerId());
                }
            }

            // Add/remove test parameter files
            if (entry != null && checkerWorkflow != null) {
                // Readd entry path to call, but with checker workflow
                args.add("--entry");
                args.add(checkerWorkflow.getFullWorkflowPath());

                // This is used by testParameter to properly display output/error messages
                args.add("--parent-entry");
                args.add(entryPath);

                // Call inherited test parameter function
                testParameter(args);
            }
        }
    }

}
