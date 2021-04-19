/*
 *    Copyright 2019 OICR
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

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.VersionTypeValidation;
import io.dockstore.language.CompleteLanguageInterface;
import io.dockstore.language.MinimalLanguageInterface;
import io.dockstore.language.RecommendedLanguageInterface;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.DescriptionSource;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.jdbi.ToolDAO;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LanguagePluginHandler implements LanguageHandlerInterface {

    public static final Logger LOG = LoggerFactory.getLogger(LanguagePluginHandler.class);
    private final MinimalLanguageInterface minimalLanguageInterface;
    private final Gson gson = new Gson();

    LanguagePluginHandler(Class<? extends MinimalLanguageInterface> workflowLanguagePluginClass) {
        try {
            this.minimalLanguageInterface = workflowLanguagePluginClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            LOG.error("could not construct language plugin", e);
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Version parseWorkflowContent(String filepath, String content, Set<SourceFile> sourceFiles, Version version) {
        final MinimalLanguageInterface.WorkflowMetadata workflowMetadata = minimalLanguageInterface
            .parseWorkflowForMetadata(filepath, content, new HashMap<>());
        version.setAuthor(workflowMetadata.getAuthor());
        version.setEmail(workflowMetadata.getEmail());
        version.setDescriptionAndDescriptionSource(workflowMetadata.getDescription(), DescriptionSource.DESCRIPTOR);
        // TODO: hook up validation object to version for parsing metadata
        return version;
    }

    @Override
    public VersionTypeValidation validateWorkflowSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        if (minimalLanguageInterface instanceof RecommendedLanguageInterface) {
            Optional<SourceFile> mainDescriptor = sourcefiles.stream()
                    .filter((sourceFile -> Objects.equals(sourceFile.getPath(), primaryDescriptorFilePath))).findFirst();
            String content = null;
            if (mainDescriptor.isPresent()) {
                content = mainDescriptor.get().getContent();
            } else {
                Map<String, String> validationMessage = new HashMap<>();
                validationMessage.put("Unknown", "Missing the primary descriptor.");
                return new VersionTypeValidation(false, validationMessage);
            }

            try {
                return ((RecommendedLanguageInterface)minimalLanguageInterface).
                        validateWorkflowSet(primaryDescriptorFilePath, content, sourcefilesToIndexedFiles(sourcefiles));
            } catch (Exception e) {
                throw new CustomWebApplicationException(e.getMessage(), HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        } else {
            return new VersionTypeValidation(true, Collections.emptyMap());
        }
    }

    /**
     * Converts a set of sourcesfiles into the generic indexed files
     * @param sourceFiles set of sourcefiles
     * @return Generic indexed files mapping
     */
    private Map<String, Pair<String, MinimalLanguageInterface.GenericFileType>> sourcefilesToIndexedFiles(Set<SourceFile> sourceFiles) {
        Map<String, Pair<String, MinimalLanguageInterface.GenericFileType>> indexedFiles = new HashMap<>();

        for (SourceFile file : sourceFiles) {
            String content = file.getContent();
            String absolutePath = file.getAbsolutePath();

            MinimalLanguageInterface.GenericFileType fileType;
            switch (file.getType()) {
            case DOCKSTORE_CWL:
            case DOCKSTORE_WDL:
            case NEXTFLOW_CONFIG:
            case NEXTFLOW:
            case DOCKSTORE_SERVICE_YML:
            case DOCKSTORE_SERVICE_OTHER:
            case DOCKSTORE_YML:
                fileType = MinimalLanguageInterface.GenericFileType.IMPORTED_DESCRIPTOR;
                break;
            case CWL_TEST_JSON:
            case WDL_TEST_JSON:
            case NEXTFLOW_TEST_PARAMS:
            case DOCKSTORE_SERVICE_TEST_JSON:
                fileType = MinimalLanguageInterface.GenericFileType.TEST_PARAMETER_FILE;
                break;
            default:
                fileType = MinimalLanguageInterface.GenericFileType.CONTAINERFILE;
                break;
            }

            Pair<String, MinimalLanguageInterface.GenericFileType> indexedFile = new ImmutablePair<>(content, fileType);
            indexedFiles.put(absolutePath, indexedFile);
        }
        return indexedFiles;
    }

    @Override
    public VersionTypeValidation validateToolSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        return new VersionTypeValidation(true, Collections.emptyMap());
    }

    @Override
    public VersionTypeValidation validateTestParameterSet(Set<SourceFile> sourceFiles) {
        return new VersionTypeValidation(true, Collections.emptyMap());
    }

    @Override
    public Map<String, SourceFile> processImports(String repositoryId, String content, Version version,
        SourceCodeRepoInterface sourceCodeRepoInterface, String filepath) {

        MinimalLanguageInterface.FileReader reader = new MinimalLanguageInterface.FileReader() {

            @Override
            public String readFile(String path) {
                return sourceCodeRepoInterface.readFile(repositoryId, path, version.getReference());
            }

            @Override
            public List<String> listFiles(String pathToDirectory) {
                return sourceCodeRepoInterface.listFiles(repositoryId, pathToDirectory, version.getReference());
            }
        };

        final Map<String, Pair<String, MinimalLanguageInterface.GenericFileType>> stringPairMap = minimalLanguageInterface
            .indexWorkflowFiles(filepath, content, reader);
        Map<String, SourceFile> results = new HashMap<>();
        for (Map.Entry<String, Pair<String, MinimalLanguageInterface.GenericFileType>> entry : stringPairMap.entrySet()) {
            final SourceFile sourceFile = new SourceFile();
            sourceFile.setPath(entry.getKey());
            sourceFile.setContent(entry.getValue().getLeft());
            if (minimalLanguageInterface.getDescriptorLanguage().isServiceLanguage()) {
                // TODO: this needs to be more sophisticated
                sourceFile.setType(DescriptorLanguage.FileType.DOCKSTORE_SERVICE_YML);
            }
            sourceFile.setAbsolutePath(entry.getKey());
            results.put(entry.getKey(), sourceFile);
        }
        return results;
    }

    @Override
    public Optional<String> getContent(String mainDescriptorPath, String mainDescriptor, Set<SourceFile> secondarySourceFiles, Type type,
        ToolDAO dao) {

        if (type == Type.DAG && minimalLanguageInterface instanceof CompleteLanguageInterface) {
            final Map<String, Object> maps = ((CompleteLanguageInterface)minimalLanguageInterface)
                .loadCytoscapeElements(mainDescriptorPath, mainDescriptor, sourcefilesToIndexedFiles(secondarySourceFiles));
            return Optional.of(gson.toJson(maps));
        } else if (type == Type.TOOLS && minimalLanguageInterface instanceof CompleteLanguageInterface) {
            // TODO: hook up tools here for Galaxy
            List<CompleteLanguageInterface.RowData> rowData = new ArrayList<>();
            try {
                rowData = ((CompleteLanguageInterface)minimalLanguageInterface)
                        .generateToolsTable(mainDescriptorPath, mainDescriptor, sourcefilesToIndexedFiles(secondarySourceFiles));
            } catch (NullPointerException e) {
                LOG.error("could not parse tools from workflow", e);
                return Optional.empty();
            }
            final List<Map<String, String>> collect = rowData.stream().map(row -> {
                Map<String, String> oldRow = new HashMap<>();
                oldRow.put("id", row.toolid);
                oldRow.put("file", row.filename);
                oldRow.put("docker", row.dockerContainer);
                oldRow.put("link", row.link == null ? "" : row.link.toString());
                return oldRow;
            }).collect(Collectors.toList());
            return Optional.of(gson.toJson(collect));
        }
        return Optional.empty();
    }
}
