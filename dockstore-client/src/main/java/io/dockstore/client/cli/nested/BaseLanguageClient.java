package io.dockstore.client.cli.nested;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.common.io.Files;
import io.dockstore.client.cli.nested.NotificationsClients.NotificationsClient;
import io.dockstore.common.Utilities;
import io.swagger.client.ApiException;
import io.swagger.client.model.ToolDescriptor;
import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.lang3.tuple.MutableTriple;
import org.apache.commons.lang3.tuple.Triple;

import static io.dockstore.client.cli.ArgumentUtility.exceptionMessage;
import static io.dockstore.client.cli.ArgumentUtility.out;
import static io.dockstore.client.cli.Client.ENTRY_NOT_FOUND;
import static io.dockstore.client.cli.Client.IO_ERROR;

/**
 * A base class for all language clients
 * Clients for CWL, WDL, Nextflow, etc should extend this.
 */
public abstract class BaseLanguageClient {
    protected final AbstractEntryClient abstractEntryClient;
    protected String uuid;
    protected INIConfiguration config;
    protected String notificationsWebHookURL;
    protected NotificationsClient notificationsClient;
    protected BaseLauncher launcher;

    protected File tempLaunchDirectory;
    protected File localPrimaryDescriptorFile;
    protected File importsZipFile;
    protected String selectedParameterFile;
    protected File provisionedParameterFile;
    protected boolean isLocalEntry;
    protected String entry;
    protected String workingDirectory;
    protected Map<String, Object> inputJson;
    protected String stdout;
    protected String stderr;

    public BaseLanguageClient(AbstractEntryClient abstractEntryClient, BaseLauncher launcher) {
        this.abstractEntryClient = abstractEntryClient;
        this.launcher = launcher;
    }

    /**
     * Selects the intended parameter file
     * @param yamlParameterFile Path to YAML parameter file
     * @param jsonParameterFile Path to JSON parameter file
     */
    public abstract void selectParameterFile(String yamlParameterFile, String jsonParameterFile);

    /**
     * Provision the input files based on the selected parameter file.
     * Creates an updated version of the parameter file with new local file locations.
     * @return Updated parameter file
     */
    public abstract File provisionInputFiles();

    /**
     * Runs the tool/workflow with the selected launcher
     * @throws ExecuteException
     */
    public abstract void executeEntry() throws ExecuteException;

    /**
     * Provisions the output files
     */
    public abstract void provisionOutputFiles();

    /**
     * Setup for notifications to webhook
     */
    public void setupNotifications() {
        config = Utilities.parseConfig(abstractEntryClient.getConfigFile());
        notificationsWebHookURL = config.getString("notifications", "");
        notificationsClient = new NotificationsClient(notificationsWebHookURL, uuid);
    }

    /**
     * Creates a working directory and downloads descriptor files
     * @param type CWL or WDL
     * @return Pair of downloaded primary descriptor and zip file
     */
    public Triple<File, File, File> initializeWorkingDirectoryWithFiles(ToolDescriptor.TypeEnum type) {
        // Try to create a working directory
        File workingDir;
        try {
            workingDir = Files.createTempDir();
        } catch (IllegalStateException ex) {
            exceptionMessage(ex, "Could not create a temporary working directory.", IO_ERROR);
            throw new RuntimeException(ex);
        }
        out("Created temporary working directory at '" + workingDir.getAbsolutePath() + "'");

        File primaryDescriptor;
        File zipFile;
        if (!isLocalEntry) {
            // If not a local entry then download remote descriptors
            try {
                primaryDescriptor = abstractEntryClient.downloadTargetEntry(entry, type, true, workingDir);
                String[] parts = entry.split(":");
                String path = parts[0];
                String convertedName = path.replaceAll("/", "_") + ".zip";
                zipFile = new File(workingDir, convertedName);
                out("Successfully downloaded files for entry '" + path + "'");
            } catch (ApiException ex) {
                if (abstractEntryClient.getEntryType().toLowerCase().equals("tool")) {
                    exceptionMessage(ex, "The tool entry does not exist. Did you mean to launch a local tool or a workflow?",
                            ENTRY_NOT_FOUND);
                } else {
                    exceptionMessage(ex, "The workflow entry does not exist. Did you mean to launch a local workflow or a tool?",
                            ENTRY_NOT_FOUND);
                }
                throw new RuntimeException(ex);
            } catch (IOException ex) {
                exceptionMessage(ex, "Problem downloading and unzipping entry.", IO_ERROR);
                throw new RuntimeException(ex);
            }
        } else {
            // For local entries zip the directory where the primary descriptor is located
            primaryDescriptor = new File(entry);
            File parentFile = primaryDescriptor.getParentFile();
            zipFile = zipDirectory(workingDir, parentFile);
            out("Using local file '" + entry + "' as primary descriptor");
        }

        return new MutableTriple<>(workingDir, primaryDescriptor, zipFile);
    }

    /**
     * Zips the given directoryToZip and returns the zip file
     * @param workingDir The working dir to place the zip file
     * @param directoryToZip The directoryToZip to zip
     * @return The zip file created
     */
    public File zipDirectory(File workingDir, File directoryToZip) {
        String zipFilePath = workingDir.getAbsolutePath() + "/directory.zip";
        try {
            FileOutputStream fos = new FileOutputStream(zipFilePath);
            ZipOutputStream zos = new ZipOutputStream(fos);
            zipFile(directoryToZip, "/", zos);
            zos.close();
            fos.close();
        } catch (IOException ex) {
            exceptionMessage(ex, "There was a problem zipping the directoryToZip '" + directoryToZip.getPath() + "'", IO_ERROR);
        }
        return new File(zipFilePath);
    }

    /**
     * A helper function for zipping directories
     * @param fileToZip File being looked at (could be a directory)
     * @param fileName Name of file being looked at
     * @param zos Zip Output Stream
     * @throws IOException
     */
    public void zipFile(File fileToZip, String fileName, ZipOutputStream zos) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                if (!Objects.equals(fileName, "/")) {
                    zos.putNextEntry(new ZipEntry(fileName.endsWith("/") ? fileName : fileName + "/"));
                    zos.closeEntry();
                }
            }
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                //if (childFile.getName().endsWith(".cwl") || childFile.getName().endsWith(".wdl") || childFile.getName().endsWith(".yml") || childFile.getName().endsWith(".yaml")) {
                if (Objects.equals(fileName, "/")) {
                    zipFile(childFile, childFile.getName(), zos);
                } else {
                    zipFile(childFile, fileName + "/" + childFile.getName(), zos);
                }
                //}
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zos.putNextEntry(zipEntry);
        final int byteLength = 1024;
        byte[] bytes = new byte[byteLength];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zos.write(bytes, 0, length);
        }
        fis.close();
    }
}
