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

package io.dockstore.webservice.helpers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.ws.rs.core.GenericType;

import com.google.common.base.Strings;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.SourceControl;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.core.SourceControlOrganization;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowVersion;
import io.swagger.bitbucket.client.ApiClient;
import io.swagger.bitbucket.client.ApiException;
import io.swagger.bitbucket.client.Configuration;
import io.swagger.bitbucket.client.api.RefsApi;
import io.swagger.bitbucket.client.api.RepositoriesApi;
import io.swagger.bitbucket.client.model.Branch;
import io.swagger.bitbucket.client.model.PaginatedBranches;
import io.swagger.bitbucket.client.model.PaginatedRefs;
import io.swagger.bitbucket.client.model.PaginatedRepositories;
import io.swagger.bitbucket.client.model.PaginatedTags;
import io.swagger.bitbucket.client.model.PaginatedTreeentries;
import io.swagger.bitbucket.client.model.Repository;
import io.swagger.bitbucket.client.model.Tag;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.webservice.Constants.SKIP_COMMIT_ID;

/**
 * @author dyuen
 */
public class BitBucketSourceCodeRepo extends SourceCodeRepoInterface {
    /**
     * should use the java api, but I can't make heads or tails of the documentation
     * https://docs.atlassian.com/bitbucket-server/javadoc/5.11.1/api/reference/packages.html
     */
    private static final String BITBUCKET_V2_API_URL = "https://api.bitbucket.org/2.0/";
    private static final String BITBUCKET_GIT_URL_PREFIX = "git@bitbucket.org:";
    private static final String BITBUCKET_GIT_URL_SUFFIX = ".git";

    private static final Logger LOG = LoggerFactory.getLogger(BitBucketSourceCodeRepo.class);
    private final ApiClient apiClient;

    /**
     * @param gitUsername           username that owns the bitbucket token
     * @param bitbucketTokenContent bitbucket token
     */
    BitBucketSourceCodeRepo(String gitUsername, String bitbucketTokenContent) {
        this.gitUsername = gitUsername;

        apiClient = Configuration.getDefaultApiClient();
        apiClient.addDefaultHeader("Authorization", "Bearer " + bitbucketTokenContent);
    }

    @Override
    public String readFile(String repositoryId, String fileName, String reference) {
        if (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        if (fileName.isEmpty()) {
            LOG.info(gitUsername + ": no file path provided for " + repositoryId);
            return null;
        }
        try {
            String fileContent = this
                .getArbitraryURL(BITBUCKET_V2_API_URL + "repositories/" + repositoryId + "/src/" + reference + '/' + fileName,
                    new GenericType<String>() {
                    });
            LOG.info(gitUsername + ": FOUND: {}", fileName);
            return fileContent;
        } catch (ApiException e) {
            LOG.error(gitUsername + ": ApiException on readFile " + fileName + " from repository " + repositoryId +  ":" + reference + ", " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public List<String> listFiles(String repositoryId, String pathToDirectory, String reference) {
        RepositoriesApi repositoriesApi = new RepositoriesApi(apiClient);
        try {
            List<String> files = new ArrayList<>();
            PaginatedTreeentries paginatedTreeentries = repositoriesApi
                .repositoriesUsernameRepoSlugSrcNodePathGet(repositoryId.split("/")[0], reference, pathToDirectory,
                    repositoryId.split("/")[1], null, null, null);
            // TODO: this pagination pattern happens a lot with Bitbucket, a future exercise would clean this up
            while (paginatedTreeentries != null) {
                files.addAll(
                    paginatedTreeentries.getValues().stream().map(entry -> StringUtils.removeStart(entry.getPath(), pathToDirectory + "/"))
                        .collect(Collectors.toList()));
                if (paginatedTreeentries.getNext() != null) {
                    paginatedTreeentries = getArbitraryURL(paginatedTreeentries.getNext(), new GenericType<PaginatedTreeentries>() {
                    });
                } else {
                    paginatedTreeentries = null;
                }
            }
            return files;
        } catch (ApiException e) {
            LOG.error(gitUsername + ": IOException on listFiles in " + pathToDirectory + " for repository " + repositoryId +  ":" + reference + ", " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public Map<String, String> getWorkflowGitUrl2RepositoryId() {
        RepositoriesApi repositoriesApi = new RepositoriesApi(apiClient);
        try {
            Map<String, String> collect = new HashMap<>();
            PaginatedRepositories contributor = repositoriesApi.repositoriesUsernameGet(gitUsername, "contributor");
            while (contributor != null) {
                collect.putAll(contributor.getValues().stream().collect(Collectors
                    .toMap(object -> BITBUCKET_GIT_URL_PREFIX + object.getFullName() + BITBUCKET_GIT_URL_SUFFIX, Repository::getFullName)));
                if (contributor.getNext() != null) {
                    contributor = getArbitraryURL(contributor.getNext(), new GenericType<PaginatedRepositories>() {
                    });
                } else {
                    contributor = null;
                }
            }
            return collect;
        } catch (ApiException e) {
            return this.handleGetWorkflowGitUrl2RepositoryIdError(e);
        }
    }

    @Override
    public String getName() {
        return "Bitbucket";
    }

    @Override
    public void setLicenseInformation(Entry entry, String gitRepository) {

    }

    /**
     * Gets arbitrary URLs that Bitbucket seems to use for pagination
     *
     * @param url full URL coming back from a self link in Bitbucket
     * @return the typed result
     * @throws ApiException swagger classes throw this exception
     */
    private <T> T getArbitraryURL(String url, GenericType<T> type) throws ApiException {
        String substring = url.substring(BITBUCKET_V2_API_URL.length() - 1);
        return apiClient
            .invokeAPI(substring, "GET", new ArrayList<>(), null, new HashMap<>(), new HashMap<>(), "application/json", "application/json",
                new String[] { "api_key", "basic", "oauth2" }, type).getData();
    }

    /**
     * Uses Bitbucket API to grab a raw source file and return it; Return null if nothing found
     *
     * @param path         path to the file
     * @param repositoryId id in the format of "name/repo"
     * @param branch       branch name
     * @param type         type of file to create
     * @return source file
     */
    @Override
    public SourceFile getSourceFile(String path, String repositoryId, String branch, DescriptorLanguage.FileType type) {
        // TODO: should we even be creating a sourcefile before checking that it is valid?
        // I think it is fine since in the next part we just check that source file has content or not (no content is like null)
        SourceFile file = null;
        String content = this.readFile(repositoryId, path, branch);

        if (!Strings.isNullOrEmpty(content)) {
            file = new SourceFile();
            // Grab content from found file
            // do not censor invalid versions to match github expected behaviour
            file.setType(type);
            file.setContent(content);
            file.setPath(path);
            file.setAbsolutePath(path);
        }
        return file;
    }

    @Override
    public List<SourceControlOrganization> getOrganizations() {
        throw new UnsupportedOperationException("apps not supported for bitbucket yet");
    }

    @Override
    public void updateReferenceType(String repositoryId, Version version) {
        if (version.getReferenceType() != Version.ReferenceType.UNSET) {
            return;
        }
        RefsApi refsApi = new RefsApi(apiClient);
        try {
            PaginatedBranches paginatedBranches = refsApi
                .repositoriesUsernameRepoSlugRefsBranchesGet(repositoryId.split("/")[0], repositoryId.split("/")[1]);
            while (paginatedBranches != null) {
                if (paginatedBranches.getValues().stream().anyMatch(key -> key.getName().equals(version.getReference()))) {
                    version.setReferenceType(Version.ReferenceType.BRANCH);
                }
                if (paginatedBranches.getNext() != null) {
                    paginatedBranches = getArbitraryURL(paginatedBranches.getNext(), new GenericType<PaginatedBranches>() {
                    });
                } else {
                    paginatedBranches = null;
                }
            }
        } catch (ApiException e) {
            LOG.error(gitUsername + ": apiexception on reading branches" + e.getMessage(), e);
            // this is not so critical to warrant a http error code
        }

        try {
            PaginatedTags paginatedTags = refsApi
                .repositoriesUsernameRepoSlugRefsTagsGet(repositoryId.split("/")[0], repositoryId.split("/")[1]);
            while (paginatedTags != null) {
                if (paginatedTags.getValues().stream().anyMatch(key -> key.getName().equals(version.getReference()))) {
                    version.setReferenceType(Version.ReferenceType.TAG);
                }
                if (paginatedTags.getNext() != null) {
                    paginatedTags = getArbitraryURL(paginatedTags.getNext(), new GenericType<PaginatedTags>() {
                    });
                } else {
                    paginatedTags = null;
                }
            }
        } catch (ApiException e) {
            LOG.error(gitUsername + ": apiexception on reading tags" + e.getMessage(), e);
            // this is not so critical to warrant a http error code
        }
    }

    @Override
    protected String getCommitID(String repositoryId, Version version) {
        RefsApi refsApi = new RefsApi(apiClient);
        try {
            Branch branch = refsApi.repositoriesUsernameRepoSlugRefsBranchesNameGet(repositoryId.split("/")[0], version.getReference(),
                repositoryId.split("/")[1]);
            if (branch != null) {
                return branch.getTarget().getHash();
            }
        } catch (ApiException ex) {
            LOG.error(gitUsername + ": apiexception on reading branch commitid", ex);
            // this is not so critical to warrant a http error code
        }

        try {
            Tag tag = refsApi.repositoriesUsernameRepoSlugRefsTagsNameGet(repositoryId.split("/")[0], version.getReference(),
                    repositoryId.split("/")[1]);
            if (tag != null) {
                return tag.getTarget().getHash();
            }
        } catch (ApiException ex) {
            LOG.error(gitUsername + ": apiexception on reading tag commitid", ex);
            // this is not so critical to warrant a http error code
        }
        return null;
    }

    @Override
    public Workflow initializeWorkflow(String repositoryId, Workflow workflow) {
        // Does this split not work if name has a slash?
        String[] id = repositoryId.split("/");
        String owner = id[0];
        String name = id[1];

        // Setup workflow
        workflow.setOrganization(owner);
        workflow.setRepository(name);
        workflow.setSourceControl(SourceControl.BITBUCKET);

        final String gitUrl = BITBUCKET_GIT_URL_PREFIX + repositoryId + BITBUCKET_GIT_URL_SUFFIX;
        workflow.setGitUrl(gitUrl);
        workflow.setLastUpdated(new Date());

        return workflow;
    }

    @Override
    public Workflow setupWorkflowVersions(String repositoryId, Workflow workflow, Optional<Workflow> existingWorkflow,
        Map<String, WorkflowVersion> existingDefaults, Optional<String> versionName, boolean hardRefresh) {
        RefsApi refsApi = new RefsApi(apiClient);
        try {
            PaginatedRefs paginatedRefs = refsApi
                .repositoriesUsernameRepoSlugRefsGet(repositoryId.split("/")[0], repositoryId.split("/")[1]);
            // this pagination structure is repetitive and should be refactored
            while (paginatedRefs != null) {
                paginatedRefs.getValues().forEach(ref -> {
                    final String branchName = ref.getName();
                    if (versionName.isEmpty() || Objects.equals(branchName, versionName.get())) {
                        WorkflowVersion version = new WorkflowVersion();
                        version.setName(branchName);
                        version.setReference(branchName);
                        final OffsetDateTime date = ref.getTarget().getDate();
                        final Date lastModifiedDate = Date.from(date.toInstant());
                        version.setLastModified(lastModifiedDate);
                        final String commitId = getCommitID(repositoryId, version);

                        if (toRefreshVersion(commitId, existingDefaults.get(branchName), hardRefresh)) {
                            LOG.info(gitUsername + ": Looking at Bitbucket reference: " + branchName);
                            version = initializeWorkflowVersion(branchName, existingWorkflow, existingDefaults);

                            version.setLastModified(lastModifiedDate);
                            String calculatedPath = version.getWorkflowPath();
                            // Now grab source files
                            DescriptorLanguage.FileType identifiedType = workflow.getFileType();
                            // TODO: No exceptions are caught here in the event of a failed call
                            SourceFile sourceFile = getSourceFile(calculatedPath, repositoryId, branchName, identifiedType);

                            // Use default test parameter file if either new version or existing version that hasn't been edited
                            createTestParameterFiles(workflow, repositoryId, branchName, version, identifiedType);
                            workflow.addWorkflowVersion(combineVersionAndSourcefile(repositoryId, sourceFile, workflow, identifiedType, version, existingDefaults));

                            version.setCommitID(getCommitID(repositoryId, version));

                            version = versionValidation(version, workflow, calculatedPath);
                            if (version != null) {
                                workflow.addWorkflowVersion(version);
                            }
                        } else {
                            // Version didn't change, but we don't want to delete
                            // Add a stub version with commit ID set to an ignore value so that the version isn't deleted
                            LOG.info(gitUsername + ": Skipping Bitbucket reference: " + branchName);
                            version.setCommitID(SKIP_COMMIT_ID);
                            workflow.addWorkflowVersion(version);
                        }
                    }
                });

                if (paginatedRefs.getNext() != null) {
                    paginatedRefs = getArbitraryURL(paginatedRefs.getNext(), new GenericType<PaginatedRefs>() {
                    });
                } else {
                    paginatedRefs = null;
                }
            }
        } catch (ApiException e) {
            LOG.error("Could not find Bitbucket repository " + repositoryId + " for user.", e);
            throw new CustomWebApplicationException("Could not reach Bitbucket", HttpStatus.SC_SERVICE_UNAVAILABLE);
        }
        return workflow;
    }

    @Override
    public String getRepositoryId(Entry entry) {
        String repositoryId;
        String giturl = entry.getGitUrl();

        Pattern p = Pattern.compile("git@bitbucket.org:(\\S+)/(\\S+)\\.git");
        Matcher m = p.matcher(giturl);
        LOG.info(gitUsername + ": " + giturl);

        if (!m.find()) {
            LOG.info(gitUsername + ": Namespace and/or repository name could not be found from tool's giturl");
            return null;
        }

        repositoryId = m.group(1) + "/" + m.group(2);

        return repositoryId;
    }

    @Override
    public String getMainBranch(Entry entry, String repositoryId) {
        RepositoriesApi api = new RepositoriesApi(apiClient);
        // Is default version set?
        if (entry.getDefaultVersion() != null) {
            return getBranchNameFromDefaultVersion(entry);
        } else {
            // If default version is not set, need to find the main branch
            try {
                Repository repository = api.repositoriesUsernameRepoSlugGet(repositoryId.split("/")[0], repositoryId.split("/")[1]);
                return repository.getMainbranch().getName();
            } catch (ApiException e) {
                LOG.error("Unable to retrieve default branch for repository " + repositoryId, e);
                return null;
            }
        }
    }

    @Override
    public boolean checkSourceCodeValidity() {
        //TODO
        return true;
    }
}
