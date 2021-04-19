package io.dockstore.webservice.resources;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;
import io.dockstore.common.DescriptorLanguageSubclass;
import io.dockstore.common.yaml.DockstoreYaml12;
import io.dockstore.common.yaml.DockstoreYamlHelper;
import io.dockstore.common.yaml.Service12;
import io.dockstore.common.yaml.YamlWorkflow;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.core.BioWorkflow;
import io.dockstore.webservice.core.Checksum;
import io.dockstore.webservice.core.LambdaEvent;
import io.dockstore.webservice.core.Service;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.TokenType;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.core.Validation;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowMode;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.helpers.CacheConfigManager;
import io.dockstore.webservice.helpers.FileFormatHelper;
import io.dockstore.webservice.helpers.GitHelper;
import io.dockstore.webservice.helpers.GitHubHelper;
import io.dockstore.webservice.helpers.GitHubSourceCodeRepo;
import io.dockstore.webservice.helpers.PublicStateManager;
import io.dockstore.webservice.helpers.SourceCodeRepoFactory;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.helpers.StateManagerMode;
import io.dockstore.webservice.jdbi.EventDAO;
import io.dockstore.webservice.jdbi.FileDAO;
import io.dockstore.webservice.jdbi.FileFormatDAO;
import io.dockstore.webservice.jdbi.LambdaEventDAO;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dockstore.webservice.jdbi.WorkflowDAO;
import io.dockstore.webservice.jdbi.WorkflowVersionDAO;
import io.swagger.annotations.Api;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.hibernate.SessionFactory;
import org.kohsuke.github.GHRateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.dockstore.webservice.Constants.DOCKSTORE_YML_PATH;
import static io.dockstore.webservice.Constants.LAMBDA_FAILURE;
import static io.dockstore.webservice.Constants.SKIP_COMMIT_ID;
import static io.dockstore.webservice.core.WorkflowMode.DOCKSTORE_YML;
import static io.dockstore.webservice.core.WorkflowMode.FULL;
import static io.dockstore.webservice.core.WorkflowMode.STUB;

/**
 * Base class for ServiceResource and WorkflowResource.
 *
 * Mainly has GitHub app logic, although there is also some BitBucket refresh
 * token logic that was easier to move in here than refactor out.
 *
 * @param <T>
 */
@Api("workflows")
public abstract class AbstractWorkflowResource<T extends Workflow> implements SourceControlResourceInterface, AuthenticatedResourceInterface {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractWorkflowResource.class);
    private static final String SHA_TYPE_FOR_SOURCEFILES = "SHA-1";

    protected final HttpClient client;
    protected final TokenDAO tokenDAO;
    protected final WorkflowDAO workflowDAO;
    protected final UserDAO userDAO;
    protected final WorkflowVersionDAO workflowVersionDAO;
    protected final EntryResource entryResource;
    protected final EventDAO eventDAO;
    protected final FileDAO fileDAO;
    protected final LambdaEventDAO lambdaEventDAO;
    protected final FileFormatDAO fileFormatDAO;
    protected final String gitHubPrivateKeyFile;
    protected final String gitHubAppId;
    protected final SessionFactory sessionFactory;

    protected final String bitbucketClientSecret;
    protected final String bitbucketClientID;

    public AbstractWorkflowResource(HttpClient client, SessionFactory sessionFactory, EntryResource entryResource,
            DockstoreWebserviceConfiguration configuration) {
        this.client = client;
        this.sessionFactory = sessionFactory;
        this.entryResource = entryResource;

        this.tokenDAO = new TokenDAO(sessionFactory);
        this.workflowDAO = new WorkflowDAO(sessionFactory);
        this.userDAO = new UserDAO(sessionFactory);
        this.fileDAO = new FileDAO(sessionFactory);
        this.workflowVersionDAO = new WorkflowVersionDAO(sessionFactory);
        this.eventDAO = new EventDAO(sessionFactory);
        this.lambdaEventDAO = new LambdaEventDAO(sessionFactory);
        this.fileFormatDAO = new FileFormatDAO(sessionFactory);
        this.bitbucketClientID = configuration.getBitbucketClientID();
        this.bitbucketClientSecret = configuration.getBitbucketClientSecret();
        gitHubPrivateKeyFile = configuration.getGitHubAppPrivateKeyFile();
        gitHubAppId = configuration.getGitHubAppId();

    }

    protected SourceCodeRepoInterface getSourceCodeRepoInterface(String gitUrl, User user) {
        List<Token> tokens = getAndRefreshTokens(user, tokenDAO, client, bitbucketClientID, bitbucketClientSecret);

        final String bitbucketTokenContent = getToken(tokens, TokenType.BITBUCKET_ORG);
        Token gitHubToken = Token.extractToken(tokens, TokenType.GITHUB_COM);
        final String gitlabTokenContent = getToken(tokens, TokenType.GITLAB_COM);

        final SourceCodeRepoInterface sourceCodeRepo = SourceCodeRepoFactory
            .createSourceCodeRepo(gitUrl, bitbucketTokenContent, gitlabTokenContent, gitHubToken);
        if (sourceCodeRepo == null) {
            throw new CustomWebApplicationException("Git tokens invalid, please re-link your git accounts.", HttpStatus.SC_BAD_REQUEST);
        }
        return sourceCodeRepo;
    }

    private String getToken(List<Token> tokens, TokenType tokenType) {
        final Token token = Token.extractToken(tokens, tokenType);
        return token == null ? null : token.getContent();
    }

    /**
     * Updates the existing workflow in the database with new information from newWorkflow, including new, updated, and removed
     * workflow verions.
     * @param workflow    workflow to be updated
     * @param newWorkflow workflow to grab new content from
     * @param user
     * @param versionName
     */
    protected void updateDBWorkflowWithSourceControlWorkflow(Workflow workflow, Workflow newWorkflow, final User user, Optional<String> versionName) {
        // update root workflow
        workflow.update(newWorkflow);
        // update workflow versions
        Map<String, WorkflowVersion> existingVersionMap = new HashMap<>();
        workflow.getWorkflowVersions().forEach(version -> existingVersionMap.put(version.getName(), version));

        // delete versions that exist in old workflow but do not exist in newWorkflow
        if (versionName.isEmpty()) {
            Map<String, WorkflowVersion> newVersionMap = new HashMap<>();
            newWorkflow.getWorkflowVersions().forEach(version -> newVersionMap.put(version.getName(), version));
            Sets.SetView<String> removedVersions = Sets.difference(existingVersionMap.keySet(), newVersionMap.keySet());
            for (String version : removedVersions) {
                if (!existingVersionMap.get(version).isFrozen()) {
                    workflow.removeWorkflowVersion(existingVersionMap.get(version));
                }
            }
        }

        // Then copy over content that changed (ignore versions that have not changed)
        newWorkflow.getWorkflowVersions().stream()
                .filter(workflowVersion -> !Objects.equals(SKIP_COMMIT_ID, workflowVersion.getCommitID()))
                .forEach(version -> {
                    WorkflowVersion workflowVersionFromDB = existingVersionMap.get(version.getName());

                    // skip frozen versions
                    if (existingVersionMap.containsKey(version.getName())) {
                        if (workflowVersionFromDB.isFrozen()) {
                            return;
                        }
                        workflowVersionFromDB.update(version);
                    } else {
                        // attach real workflow
                        workflow.addWorkflowVersion(version);

                        final long workflowVersionId = workflowVersionDAO.create(version);
                        workflowVersionFromDB = workflowVersionDAO.findById(workflowVersionId);
                        this.eventDAO.createAddTagToEntryEvent(user, workflow, workflowVersionFromDB);
                        workflow.getWorkflowVersions().add(workflowVersionFromDB);
                        existingVersionMap.put(workflowVersionFromDB.getName(), workflowVersionFromDB);
                    }
                    workflowVersionFromDB.setToolTableJson(null);
                    workflowVersionFromDB.setDagJson(null);

                    updateDBVersionSourceFilesWithRemoteVersionSourceFiles(workflowVersionFromDB, version);
                });
    }

    /**
     * Updates the sourcefiles in the database to match the sourcefiles on the remote
     * @param existingVersion
     * @param remoteVersion
     * @return WorkflowVersion with updated sourcefiles
     */
    private WorkflowVersion updateDBVersionSourceFilesWithRemoteVersionSourceFiles(WorkflowVersion existingVersion, WorkflowVersion remoteVersion) {
        // Update source files for each version
        Map<String, SourceFile> existingFileMap = new HashMap<>();
        existingVersion.getSourceFiles().forEach(file -> existingFileMap.put(file.getType().toString() + file.getAbsolutePath(), file));

        for (SourceFile file : remoteVersion.getSourceFiles()) {
            String fileKey = file.getType().toString() + file.getAbsolutePath();
            SourceFile existingFile = existingFileMap.get(fileKey);
            if (existingFileMap.containsKey(fileKey)) {
                List<Checksum> checksums = new ArrayList<>();
                Optional<String> sha = FileFormatHelper.calcSHA1(file.getContent());
                if (sha.isPresent()) {
                    checksums.add(new Checksum(SHA_TYPE_FOR_SOURCEFILES, sha.get()));
                    if (existingFile.getChecksums() == null) {
                        existingFile.setChecksums(checksums);
                    } else {
                        existingFile.getChecksums().clear();
                        existingFileMap.get(fileKey).getChecksums().addAll(checksums);

                    }
                }
                existingFile.setContent(file.getContent());
            } else {
                final long fileID = fileDAO.create(file);
                final SourceFile fileFromDB = fileDAO.findById(fileID);

                Optional<String> sha = FileFormatHelper.calcSHA1(file.getContent());
                if (sha.isPresent()) {
                    fileFromDB.getChecksums().add(new Checksum(SHA_TYPE_FOR_SOURCEFILES, sha.get()));
                }
                existingVersion.getSourceFiles().add(fileFromDB);
            }
        }

        // Remove existing files that are no longer present on remote
        for (Map.Entry<String, SourceFile> entry : existingFileMap.entrySet()) {
            boolean toDelete = true;
            for (SourceFile file : remoteVersion.getSourceFiles()) {
                if (entry.getKey().equals(file.getType().toString() + file.getAbsolutePath())) {
                    toDelete = false;
                }
            }
            if (toDelete) {
                existingVersion.getSourceFiles().remove(entry.getValue());
            }
        }

        // Update the validations
        for (Validation versionValidation : remoteVersion.getValidations()) {
            existingVersion.addOrUpdateValidation(versionValidation);
        }
        return existingVersion;
    }

    /**
     * Handle webhooks from GitHub apps after branch deletion (redirected from AWS Lambda)
     * - Delete version for corresponding service and workflow
     * @param repository Repository path (ex. dockstore/dockstore-ui2)
     * @param gitReference Git reference from GitHub (ex. refs/tags/1.0)
     * @param username Git user who triggered the event
     * @param installationId GitHub App installation ID
     * @return List of updated workflows
     */
    protected List<Workflow> githubWebhookDelete(String repository, String gitReference, String username, String installationId) {
        // Retrieve name from gitReference
        Optional<String> gitReferenceName = GitHelper.parseGitHubReference(gitReference);
        if (gitReferenceName.isEmpty()) {
            String msg = "Reference " + gitReference + " is not of the valid form";
            LOG.error(msg);
            sessionFactory.getCurrentSession().clear();
            LambdaEvent lambdaEvent = createBasicEvent(repository, gitReference, username, LambdaEvent.LambdaEventType.DELETE);
            lambdaEvent.setMessage(msg);
            lambdaEvent.setSuccess(false);
            lambdaEventDAO.create(lambdaEvent);
            sessionFactory.getCurrentSession().getTransaction().commit();
            throw new CustomWebApplicationException(msg, LAMBDA_FAILURE);
        }

        // Find all workflows and services that are github apps and use the given repo
        List<Workflow> workflows = workflowDAO.findAllByPath("github.com/" + repository, false).stream().filter(workflow -> Objects.equals(workflow.getMode(), DOCKSTORE_YML)).collect(
                Collectors.toList());

        // When the git reference to delete is the default version, set it to the next latest version
        workflows.forEach(workflow -> {
            if (workflow.getActualDefaultVersion() != null && workflow.getActualDefaultVersion().getName().equals(gitReferenceName.get())) {
                Optional<WorkflowVersion> max = workflow.getWorkflowVersions().stream()
                        .filter(v -> !Objects.equals(v.getName(), gitReferenceName.get()))
                        .max(Comparator.comparingLong(ver -> ver.getDate().getTime()));
                workflow.setActualDefaultVersion(max.orElse(null));
            }
        });

        // Delete all non-frozen versions that have the same git reference name and then update the file formats of the entry.
        workflows.forEach(workflow -> {
            workflow.getWorkflowVersions().removeIf(workflowVersion -> Objects.equals(workflowVersion.getName(), gitReferenceName.get()) && !workflowVersion.isFrozen());
            FileFormatHelper.updateEntryLevelFileFormats(workflow);
        });
        LambdaEvent lambdaEvent = createBasicEvent(repository, gitReference, username, LambdaEvent.LambdaEventType.DELETE);
        lambdaEventDAO.create(lambdaEvent);
        return workflows;
    }

    /**
     * Handle webhooks from GitHub apps (redirected from AWS Lambda)
     * - Create services and workflows when necessary
     * - Add or update version for corresponding service and workflow
     * @param repository Repository path (ex. dockstore/dockstore-ui2)
     * @param username Username of GitHub user that triggered action
     * @param gitReference Git reference from GitHub (ex. refs/tags/1.0)
     * @param installationId GitHub App installation ID
     * @return List of new and updated workflows
     */
    protected void githubWebhookRelease(String repository, String username, String gitReference, String installationId) {
        // Retrieve the user who triggered the call (must exist on Dockstore if workflow is not already present)
        User user = GitHubHelper.findUserByGitHubUsername(this.tokenDAO, this.userDAO, username, false);
        // Grab Dockstore YML from GitHub
        GitHubSourceCodeRepo gitHubSourceCodeRepo = (GitHubSourceCodeRepo)SourceCodeRepoFactory.createGitHubAppRepo(gitHubAppSetup(installationId));

        GHRateLimit startRateLimit = gitHubSourceCodeRepo.getGhRateLimitQuietly();
        GHRateLimit endRateLimit;
        boolean isSuccessful = false;
        try {

            SourceFile dockstoreYml = gitHubSourceCodeRepo.getDockstoreYml(repository, gitReference);
            // If this method doesn't throw an exception, it's a valid .dockstore.yml with at least one workflow or service.
            // It also converts a .dockstore.yml 1.1 file to a 1.2 object, if necessary.
            final DockstoreYaml12 dockstoreYaml12 = DockstoreYamlHelper.readAsDockstoreYaml12(dockstoreYml.getContent());
            createServicesAndVersionsFromDockstoreYml(dockstoreYaml12.getService(), repository, gitReference, installationId, user, dockstoreYml);
            createBioWorkflowsAndVersionsFromDockstoreYml(dockstoreYaml12.getWorkflows(), repository, gitReference, installationId, user, dockstoreYml);
            LambdaEvent lambdaEvent = createBasicEvent(repository, gitReference, username, LambdaEvent.LambdaEventType.PUSH);
            lambdaEventDAO.create(lambdaEvent);
            endRateLimit = gitHubSourceCodeRepo.getGhRateLimitQuietly();
            isSuccessful = true;
            gitHubSourceCodeRepo.reportOnGitHubRelease(startRateLimit, endRateLimit, repository, username, gitReference, isSuccessful);
        } catch (CustomWebApplicationException | ClassCastException | DockstoreYamlHelper.DockstoreYamlException | UnsupportedOperationException ex) {
            endRateLimit = gitHubSourceCodeRepo.getGhRateLimitQuietly();
            gitHubSourceCodeRepo.reportOnGitHubRelease(startRateLimit, endRateLimit, repository, username, gitReference, isSuccessful);
            String errorMessage = ex instanceof CustomWebApplicationException ? ((CustomWebApplicationException)ex).getErrorMessage() : ex.getMessage();
            String msg = "User " + username + ": Error handling push event for repository " + repository + " and reference " + gitReference + "\n" + errorMessage;
            LOG.info(msg, ex);
            sessionFactory.getCurrentSession().clear();
            LambdaEvent lambdaEvent = createBasicEvent(repository, gitReference, username, LambdaEvent.LambdaEventType.PUSH);
            lambdaEvent.setSuccess(false);
            lambdaEvent.setMessage(errorMessage);
            lambdaEventDAO.create(lambdaEvent);
            sessionFactory.getCurrentSession().getTransaction().commit();
            throw new CustomWebApplicationException(msg, statusCodeForLambda(ex));
        } catch (Exception ex) {
            endRateLimit = gitHubSourceCodeRepo.getGhRateLimitQuietly();
            gitHubSourceCodeRepo.reportOnGitHubRelease(startRateLimit, endRateLimit, repository, username, gitReference, isSuccessful);
            String msg = "User " + username + ": Unhandled error while handling push event for repository " + repository + " and reference " + gitReference + "\n" + ex.getMessage();
            LOG.error(msg, ex);
            sessionFactory.getCurrentSession().clear();
            LambdaEvent lambdaEvent = createBasicEvent(repository, gitReference, username, LambdaEvent.LambdaEventType.PUSH);
            lambdaEvent.setSuccess(false);
            lambdaEvent.setMessage(ex.getMessage());
            lambdaEventDAO.create(lambdaEvent);
            sessionFactory.getCurrentSession().getTransaction().commit();
            throw new CustomWebApplicationException(msg, LAMBDA_FAILURE);
        }
    }

    /**
     * Determines whether to signal lambda to try again
     * @param ex
     * @return
     */
    private int statusCodeForLambda(Exception ex) {
        if (isGitHubRateLimitError(ex)) {
            // 5xx tells lambda to retry. Lambda is configured to wait an hour for the retry; retries shouldn't immediately cause even more strain on rate limits.
            LOG.info("GitHub rate limit hit, signaling lambda to retry.");
            return HttpStatus.SC_INTERNAL_SERVER_ERROR;
        }
        return LAMBDA_FAILURE;
    }

    private boolean isGitHubRateLimitError(Exception ex) {
        if (ex instanceof CustomWebApplicationException) {
            final CustomWebApplicationException customWebAppEx = (CustomWebApplicationException)ex;
            final String errorMessage = customWebAppEx.getErrorMessage();
            if (errorMessage != null && errorMessage.startsWith(GitHubSourceCodeRepo.OUT_OF_GIT_HUB_RATE_LIMIT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create a basic lambda event
     * @param repository repository path
     * @param gitReference full git reference (ex. refs/heads/master)
     * @param username Username of GitHub user who triggered the event
     * @param type Event type
     * @return New lambda event
     */
    private LambdaEvent createBasicEvent(String repository, String gitReference, String username, LambdaEvent.LambdaEventType type) {
        LambdaEvent lambdaEvent = new LambdaEvent();
        String[] repo = repository.split("/");
        lambdaEvent.setOrganization(repo[0]);
        lambdaEvent.setRepository(repo[1]);
        lambdaEvent.setReference(gitReference);
        lambdaEvent.setGithubUsername(username);
        lambdaEvent.setType(type);
        User user = userDAO.findByGitHubUsername(username);
        if (user != null) {
            lambdaEvent.setUser(user);
        }
        return lambdaEvent;
    }

    /**
     * Create or retrieve workflows based on Dockstore.yml, add or update tag version
     * ONLY WORKS FOR v1.2
     * @param repository Repository path (ex. dockstore/dockstore-ui2)
     * @param gitReference Git reference from GitHub (ex. refs/tags/1.0)
     * @param installationId Installation id needed to setup GitHub apps
     * @param user User that triggered action
     * @param dockstoreYml
     * @return List of new and updated workflows
     */
    private List<Workflow> createBioWorkflowsAndVersionsFromDockstoreYml(List<YamlWorkflow> yamlWorkflows, String repository, String gitReference, String installationId, User user,
            final SourceFile dockstoreYml) {
        GitHubSourceCodeRepo gitHubSourceCodeRepo = (GitHubSourceCodeRepo)SourceCodeRepoFactory.createGitHubAppRepo(gitHubAppSetup(installationId));
        try {
            List<Workflow> updatedWorkflows = new ArrayList<>();
            final Path gitRefPath = Path.of(gitReference);
            for (YamlWorkflow wf : yamlWorkflows) {
                if (!DockstoreYamlHelper.filterGitReference(gitRefPath, wf.getFilters())) {
                    continue;
                }

                String subclass = wf.getSubclass();
                String workflowName = wf.getName();
                Boolean publish = wf.getPublish();

                Workflow workflow = createOrGetWorkflow(BioWorkflow.class, repository, user, workflowName, subclass, gitHubSourceCodeRepo);
                workflow = addDockstoreYmlVersionToWorkflow(repository, gitReference, dockstoreYml, gitHubSourceCodeRepo, workflow);

                if (publish != null && workflow.getIsPublished() != publish) {
                    LambdaEvent lambdaEvent = createBasicEvent(repository, gitReference, user.getUsername(), LambdaEvent.LambdaEventType.PUBLISH);
                    try {
                        workflow = publishWorkflow(workflow, publish);
                    } catch (CustomWebApplicationException ex) {
                        LOG.warn("Could not set publish state from YML.", ex);
                        lambdaEvent.setSuccess(false);
                        lambdaEvent.setMessage(ex.getMessage());
                    }
                    lambdaEventDAO.create(lambdaEvent);
                }

                updatedWorkflows.add(workflow);
            }
            return updatedWorkflows;
        } catch (ClassCastException ex) {
            throw new CustomWebApplicationException("Could not parse workflow array from YML.", LAMBDA_FAILURE);
        }
    }

    /**
     * Create or retrieve services based on Dockstore.yml, add or update tag version
     * ONLY WORKS FOR v1.1
     * @param repository Repository path (ex. dockstore/dockstore-ui2)
     * @param gitReference Git reference from GitHub (ex. refs/tags/1.0)
     * @param installationId installation id needed to set up GitHub Apps
     * @param user User that triggered action
     * @param dockstoreYml
     * @return List of new and updated services
     */
    private List<Workflow> createServicesAndVersionsFromDockstoreYml(Service12 service, String repository, String gitReference, String installationId,
            User user, final SourceFile dockstoreYml) {
        GitHubSourceCodeRepo gitHubSourceCodeRepo = (GitHubSourceCodeRepo)SourceCodeRepoFactory.createGitHubAppRepo(gitHubAppSetup(installationId));
        final List<Workflow> updatedServices = new ArrayList<>();
        if (service != null) {
            if (!DockstoreYamlHelper.filterGitReference(Path.of(gitReference), service.getFilters())) {
                return updatedServices;
            }
            final DescriptorLanguageSubclass subclass = service.getSubclass();
            final Boolean publish = service.getPublish();

            Workflow workflow = createOrGetWorkflow(Service.class, repository, user, "", subclass.getShortName(), gitHubSourceCodeRepo);
            workflow = addDockstoreYmlVersionToWorkflow(repository, gitReference, dockstoreYml, gitHubSourceCodeRepo, workflow);

            if (publish != null && workflow.getIsPublished() != publish) {
                LambdaEvent lambdaEvent = createBasicEvent(repository, gitReference, user.getUsername(), LambdaEvent.LambdaEventType.PUBLISH);
                try {
                    workflow = publishWorkflow(workflow, publish);
                } catch (CustomWebApplicationException ex) {
                    LOG.warn("Could not set publish state from YML.", ex);
                    lambdaEvent.setSuccess(false);
                    lambdaEvent.setMessage(ex.getMessage());
                }
                lambdaEventDAO.create(lambdaEvent);
            }

            updatedServices.add(workflow);
        }
        return updatedServices;
    }

    /**
     * Create or retrieve workflow or service based on Dockstore.yml
     * @param workflowType Either BioWorkflow.class or Service.class
     * @param repository Repository path (ex. dockstore/dockstore-ui2)
     * @param user User that triggered action
     * @param workflowName User that triggered action
     * @param subclass Subclass of the workflow
     * @param gitHubSourceCodeRepo Source Code Repo
     * @return New or updated workflow
     */
    private Workflow createOrGetWorkflow(Class workflowType, String repository, User user, String workflowName, String subclass, GitHubSourceCodeRepo gitHubSourceCodeRepo) {
        // Check for existing workflow
        String dockstoreWorkflowPath = "github.com/" + repository + (workflowName != null && !workflowName.isEmpty() ? "/" + workflowName : "");
        Optional<Workflow> workflow = workflowDAO.findByPath(dockstoreWorkflowPath, false, workflowType);

        Workflow workflowToUpdate = null;
        // Create workflow if one does not exist
        if (workflow.isEmpty()) {
            // Ensure that a Dockstore user exists to add to the workflow
            if (user == null) {
                throw new CustomWebApplicationException("User does not have an account on Dockstore.", LAMBDA_FAILURE);
            }

            if (workflowType == BioWorkflow.class) {
                workflowToUpdate = gitHubSourceCodeRepo.initializeWorkflowFromGitHub(repository, subclass, workflowName);
            } else if (workflowType == Service.class) {
                workflowToUpdate = gitHubSourceCodeRepo.initializeServiceFromGitHub(repository, subclass);
            } else {
                throw new CustomWebApplicationException(workflowType.getCanonicalName()  + " is not a valid workflow type. Currently only workflows and services are supported by GitHub Apps.", LAMBDA_FAILURE);
            }
            long workflowId = workflowDAO.create(workflowToUpdate);
            workflowToUpdate = workflowDAO.findById(workflowId);
            LOG.info("Workflow " + dockstoreWorkflowPath + " has been created.");
        } else {
            workflowToUpdate = workflow.get();
            gitHubSourceCodeRepo.setLicenseInformation(workflowToUpdate, repository);
            if (Objects.equals(workflowToUpdate.getMode(), FULL) || Objects.equals(workflowToUpdate.getMode(), STUB)) {
                LOG.info("Converting workflow to DOCKSTORE_YML");
                workflowToUpdate.setMode(DOCKSTORE_YML);
                workflowToUpdate.setDefaultWorkflowPath(DOCKSTORE_YML_PATH);
            }
        }

        if (user != null) {
            workflowToUpdate.getUsers().add(user);
        }

        return  workflowToUpdate;
    }

    /**
     * Add versions to a service or workflow based on Dockstore.yml
     * @param repository Repository path (ex. dockstore/dockstore-ui2)
     * @param gitReference Git reference from GitHub (ex. refs/tags/1.0)
     * @param dockstoreYml Dockstore YAML File
     * @param gitHubSourceCodeRepo Source Code Repo
     * @return New or updated workflow
     */
    private Workflow addDockstoreYmlVersionToWorkflow(String repository, String gitReference, SourceFile dockstoreYml,
            GitHubSourceCodeRepo gitHubSourceCodeRepo, Workflow workflow) {
        Instant startTime = Instant.now();
        try {
            // Create version and pull relevant files
            WorkflowVersion remoteWorkflowVersion = gitHubSourceCodeRepo
                    .createVersionForWorkflow(repository, gitReference, workflow, dockstoreYml);
            remoteWorkflowVersion.setReferenceType(getReferenceTypeFromGitRef(gitReference));

            // So we have workflowversion which is the new version, we want to update the version and associated source files
            WorkflowVersion existingWorkflowVersion = workflowVersionDAO.getWorkflowVersionByWorkflowIdAndVersionName(workflow.getId(), remoteWorkflowVersion.getName());
            // Update existing source files, add new source files, remove deleted sourcefiles, clear json for dag and tool table
            if (existingWorkflowVersion != null) {
                // Copy over workflow version level information
                existingWorkflowVersion.setWorkflowPath(remoteWorkflowVersion.getWorkflowPath());
                existingWorkflowVersion.setLastModified(remoteWorkflowVersion.getLastModified());
                existingWorkflowVersion.setLegacyVersion(remoteWorkflowVersion.isLegacyVersion());
                existingWorkflowVersion.setAliases(remoteWorkflowVersion.getAliases());
                existingWorkflowVersion.setSubClass(remoteWorkflowVersion.getSubClass());
                existingWorkflowVersion.setCommitID(remoteWorkflowVersion.getCommitID());
                existingWorkflowVersion.setDagJson(null);
                existingWorkflowVersion.setToolTableJson(null);
                existingWorkflowVersion.setReferenceType(remoteWorkflowVersion.getReferenceType());
                existingWorkflowVersion.setValid(remoteWorkflowVersion.isValid());
                updateDBVersionSourceFilesWithRemoteVersionSourceFiles(existingWorkflowVersion, remoteWorkflowVersion);
            } else {
                workflow.addWorkflowVersion(remoteWorkflowVersion);
            }

            WorkflowVersion addedVersion = workflowVersionDAO.getWorkflowVersionByWorkflowIdAndVersionName(workflow.getId(), remoteWorkflowVersion.getName());
            if (addedVersion != null) {
                gitHubSourceCodeRepo.updateVersionMetadata(addedVersion.getWorkflowPath(), addedVersion, workflow.getDescriptorType(), repository);

                // Update file formats for the version and then the entry.
                // TODO: We were not adding file formats to .dockstore.yml versions before, so this only handles new/updated versions. Need to add a way to update all .dockstore.yml versions in a workflow
                Set<WorkflowVersion> workflowVersions = new HashSet<>();
                workflowVersions.add(addedVersion);
                FileFormatHelper.updateFileFormats(workflow, workflowVersions, fileFormatDAO, false);
            }

            LOG.info("Version " + remoteWorkflowVersion.getName() + " has been added to workflow " + workflow.getWorkflowPath() + ".");
        } catch (IOException ex) {
            throw new CustomWebApplicationException("Cannot retrieve the workflow reference from GitHub, ensure that " + gitReference + " is a valid tag.", LAMBDA_FAILURE);
        }
        Instant endTime = Instant.now();
        long timeElasped = Duration.between(startTime, endTime).toSeconds();
        LOG.info("Processing .dockstore.yml workflow version " + gitReference + " for repo: " + repository + " took " + timeElasped + " seconds");
        return workflow;
    }

    private Version.ReferenceType getReferenceTypeFromGitRef(String gitRef) {
        if (gitRef.startsWith("refs/heads/")) {
            return Version.ReferenceType.BRANCH;
        } else if (gitRef.startsWith("refs/tags/")) {
            return Version.ReferenceType.TAG;
        } else {
            return Version.ReferenceType.NOT_APPLICABLE;
        }
    }

    /**
     * Add user to any existing Dockstore workflow and services from GitHub apps they should own
     * @param user
     */
    protected void syncEntitiesForUser(User user) {
        List<Token> githubByUserId = tokenDAO.findGithubByUserId(user.getId());

        if (githubByUserId.isEmpty()) {
            String msg = "The user does not have a GitHub token, please create one";
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        } else {
            syncEntities(user, githubByUserId.get(0));
        }
    }

    /**
     * Syncs entities based on GitHub app installation, optionally limiting to orgs in the GitHub organization <code>organization</code>.
     *
     * 1. Finds all repos that have the Dockstore GitHub app installed
     * 2. For existing entities, ensures that <code>user</code> is one of the entity's users
     *
     * @param user
     * @param gitHubToken
     */
    private void syncEntities(User user, Token gitHubToken) {
        GitHubSourceCodeRepo gitHubSourceCodeRepo = (GitHubSourceCodeRepo)SourceCodeRepoFactory.createSourceCodeRepo(gitHubToken);

        // Get all GitHub repositories for the user
        final Map<String, String> workflowGitUrl2Name = gitHubSourceCodeRepo.getWorkflowGitUrl2RepositoryId();

        // Filter by organization if necessary
        final Collection<String> repositories = workflowGitUrl2Name.values();

        // Add user to any services they should have access to that already exist on Dockstore
        final List<Workflow> existingWorkflows = findDockstoreWorkflowsForGitHubRepos(repositories);
        existingWorkflows.stream()
                .filter(workflow -> !workflow.getUsers().contains(user))
                .forEach(workflow -> workflow.getUsers().add(user));

        // No longer adds stub services, though code could be useful
        //        final Set<String> existingWorkflowPaths = existingWorkflows.stream()
        //                .map(workflow -> workflow.getWorkflowPath()).collect(Collectors.toSet());
        //
        //        GitHubHelper.checkJWT(gitHubAppId, gitHubPrivateKeyFile);
        //
        //        GitHubHelper.reposToCreateEntitiesFor(repositories, organization, existingWorkflowPaths).stream()
        //                .forEach(repositoryName -> {
        //                    final T entity = initializeEntity(repositoryName, gitHubSourceCodeRepo);
        //                    entity.addUser(user);
        //                    final long entityId = workflowDAO.create(entity);
        //                    final Workflow createdEntity = workflowDAO.findById(entityId);
        //                    final Workflow updatedEntity = gitHubSourceCodeRepo.getWorkflow(repositoryName, Optional.of(createdEntity));
        //                    updateDBWorkflowWithSourceControlWorkflow(createdEntity, updatedEntity, user);
        //                });
    }

    /**
     * From the collection of GitHub repositories, returns the list of Dockstore entities (Service or BioWorkflow) that
     * exist for those repositories.
     *
     * Ideally this would return <code>List&lt;T&gt;</code>, but not sure if I can use getClass instead of WorkflowMode
     * for workflows (would it apply to both STUB and WORKFLOW?) in filter call below (see TODO)?
     *
     * @param repositories
     * @return
     */
    private List<Workflow> findDockstoreWorkflowsForGitHubRepos(Collection<String> repositories) {
        final List<String> workflowPaths = repositories.stream().map(repositoryName -> "github.com/" + repositoryName)
                .collect(Collectors.toList());
        return workflowDAO.findByPaths(workflowPaths, false).stream()
                // TODO: Revisit this when support for workflows added.
                .filter(workflow -> Objects.equals(workflow.getMode(), DOCKSTORE_YML))
                .collect(Collectors.toList());
    }

    /**
     * Setup tokens required for GitHub apps
     * @param installationId App installation ID (per repository)
     * @return Installation access token for the given repository
     */
    private String gitHubAppSetup(String installationId) {
        GitHubHelper.checkJWT(gitHubAppId, gitHubPrivateKeyFile);
        String installationAccessToken = CacheConfigManager.getInstance().getInstallationAccessTokenFromCache(installationId);
        if (installationAccessToken == null) {
            String msg = "Could not get an installation access token for install with id " + installationId;
            LOG.info(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
        return installationAccessToken;
    }

    /**
     * Publish or unpublish given workflow, if necessary.
     * @param workflow
     * @param publish
     * @return
     */
    protected Workflow publishWorkflow(Workflow workflow, final boolean publish) {
        if (workflow.getIsPublished() == publish) {
            return workflow;
        }

        Workflow checker = workflow.getCheckerWorkflow();

        if (workflow.isIsChecker()) {
            String msg = "Cannot directly publish/unpublish a checker workflow.";
            LOG.error(msg);
            throw new CustomWebApplicationException(msg, HttpStatus.SC_BAD_REQUEST);
        }

        if (publish) {
            boolean validTag = false;
            Set<WorkflowVersion> versions = workflow.getWorkflowVersions();
            for (WorkflowVersion workflowVersion : versions) {
                if (workflowVersion.isValid()) {
                    validTag = true;
                    break;
                }
            }

            if (validTag && (!workflow.getGitUrl().isEmpty() || Objects.equals(workflow.getMode(), WorkflowMode.HOSTED))) {
                workflow.setIsPublished(true);
                if (checker != null) {
                    checker.setIsPublished(true);
                }
            } else {
                throw new CustomWebApplicationException("Repository does not meet requirements to publish.", HttpStatus.SC_BAD_REQUEST);
            }

            PublicStateManager.getInstance().handleIndexUpdate(workflow, StateManagerMode.PUBLISH);
            if (workflow.getTopicId() == null) {
                try {
                    entryResource.createAndSetDiscourseTopic(workflow.getId());
                } catch (CustomWebApplicationException ex) {
                    LOG.error("Error adding discourse topic.", ex);
                }
            }
        } else {
            workflow.setIsPublished(false);
            if (checker != null) {
                checker.setIsPublished(false);
            }
            PublicStateManager.getInstance().handleIndexUpdate(workflow, StateManagerMode.DELETE);
        }
        return workflow;
    }
}
