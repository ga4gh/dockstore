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
package io.dockstore.consumer.handler;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

import com.google.common.collect.Lists;
import io.dockstore.common.model.DOIMessage;
import io.dockstore.zenodo.client.ApiClient;
import io.dockstore.zenodo.client.ApiException;
import io.dockstore.zenodo.client.api.ActionsApi;
import io.dockstore.zenodo.client.api.DepositsApi;
import io.dockstore.zenodo.client.model.Author;
import io.dockstore.zenodo.client.model.Deposit;
import io.dockstore.zenodo.client.model.DepositMetadata;
import io.dockstore.zenodo.client.model.NestedDepositMetadata;
import io.dockstore.zenodo.client.model.RelatedIdentifier;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.ContainertagsApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.Tag;
import io.swagger.client.model.Workflow;
import io.swagger.client.model.WorkflowVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DOIHandler implements MessageHandler<DOIMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(DOIHandler.class);
    private final String dockstoreURL;
    private final String dockstoreToken;
    private final String zenodoToken;
    private final String zenodoURL;

    public DOIHandler(String dockstoreURL, String dockstoreToken, String zenodoURL, String zenodoToken) {
        this.dockstoreURL = dockstoreURL;
        this.dockstoreToken = dockstoreToken;
        this.zenodoToken = zenodoToken;
        this.zenodoURL = zenodoURL;
    }

    @Override
    public boolean handleMessage(DOIMessage message) {
        // TODO: need to grab the Docker image and documents then send them to zenodo then update the webservice

        // grab info from for the right tool or workflow from Dockstore
        io.swagger.client.ApiClient dockstoreClient = new io.swagger.client.ApiClient();
        dockstoreClient.setBasePath(dockstoreURL);
        dockstoreClient.addDefaultHeader("Authorization", "Bearer " + dockstoreToken);

        ContainersApi toolApi = new ContainersApi(dockstoreClient);
        ContainertagsApi tagsApi = new ContainertagsApi(dockstoreClient);
        WorkflowsApi workflowsApi = new WorkflowsApi(dockstoreClient);

        long entryId = message.getEntryId();
        long entryVersionId = message.getEntryVersionId();
        if (Objects.equals(message.getTargetEntry(), "tool")) {
            DockstoreTool publishedContainer = toolApi.getPublishedContainer(entryId, null);
            Optional<Tag> possibleTag = tagsApi.getTagsByPath(entryId).stream().filter(t -> t.getId() == entryVersionId).findFirst();
            Tag tag;
            if (possibleTag.isPresent()) {
                tag = possibleTag.get();
            } else {
                LOG.error("tag does not exist, will not be transient");
                return true;
            }

            // Someday a prototype could pull Docker images here

            // send documents to zenodo
            ApiClient zenodoClient = new ApiClient();
            zenodoClient.addDefaultHeader("Authorization", "Bearer " + zenodoToken);

            // for testing, either 'https://sandbox.zenodo.org/api' or 'https://zenodo.org/api' is the first parameter
            zenodoClient.setBasePath(zenodoURL);
            zenodoClient.setApiKey(zenodoToken);

            // this is working through the quick start to make sure we can do normal operations
            // http://developers.zenodo.org/#quickstart-upload

            DepositsApi depositApi = new DepositsApi(zenodoClient);
            Deposit deposit = new Deposit();
            Deposit returnDeposit = null;
            try {
                returnDeposit = depositApi.createDeposit(deposit);
                // upload a new file
                int depositionID = returnDeposit.getId();
                // Commenting out to get around json ignoring source files. The files in this section aren't used, but keeping them around for reference for possible future work.
                // FilesApi filesApi = new FilesApi(zenodoClient);
                //                for (SourceFile file : tag.getSourceFiles()) {
                //                    Path tempFile = Files.createTempFile("temp", "file");
                //                    FileUtils.writeStringToFile(tempFile.toFile(), file.getContent(), StandardCharsets.UTF_8);
                //                    // file name is passsed in but seems to be ignore
                //                    filesApi.createFile(depositionID, tempFile.toFile(), new File(file.getPath()).getName());
                //                }
                // TODO: this would be fleshed out to populate descriptors, secondary descriptors, test json, dockerfiles, etc.
                // TODO: this could use a progress indicator, uploading docker images seems slow
                //DepositionFile file = filesApi.createFile(depositionID, dockerImageFile, dockerImageFile.getAbsolutePath());
                // LOG.info(file.toString());

                // add some metadata
                returnDeposit.getMetadata().setTitle(publishedContainer.getToolPath());
                returnDeposit.getMetadata().setUploadType(DepositMetadata.UploadTypeEnum.SOFTWARE);
                returnDeposit.getMetadata().setDescription(publishedContainer.getDescription());

                RelatedIdentifier relatedIdentifier = new RelatedIdentifier();
                relatedIdentifier.setIdentifier("https://dockstore.org/containers/quay.io%2Fpancancer%2Fpcawg-sanger-cgp-workflow");
                relatedIdentifier.setRelation(RelatedIdentifier.RelationEnum.ISIDENTICALTO);
                returnDeposit.getMetadata().setRelatedIdentifiers(Collections.singletonList(relatedIdentifier));

                Author author1 = new Author();
                author1.setName(publishedContainer.getAuthor());
                returnDeposit.getMetadata().setCreators(Collections.singletonList(author1));
                NestedDepositMetadata nestedDepositMetadata = new NestedDepositMetadata();
                nestedDepositMetadata.setMetadata(returnDeposit.getMetadata());
                depositApi.putDeposit(depositionID, nestedDepositMetadata);

                // publish it
                ActionsApi actionsApi = new ActionsApi(zenodoClient);
                Deposit publishedDeposit = actionsApi.publishDeposit(depositionID);
                // need to grab and save the generated DOI here
                LOG.info(publishedDeposit.toString());
            } catch (ApiException e) {
                LOG.error("could not create zenodo representation", e);
                return false;
            }
            System.out.println(returnDeposit.toString());


            // update the webservice with a new DOI status
            ContainertagsApi containertagsApi = new ContainertagsApi(dockstoreClient);
            containertagsApi.updateTags(entryId, Lists.newArrayList(tag));
            return true;
        } else if (Objects.equals(message.getTargetEntry(), "workflow")) {
            Workflow publishedWorkflow = workflowsApi.getPublishedWorkflow(entryId, null);
            Optional<WorkflowVersion> first = publishedWorkflow.getWorkflowVersions().stream().filter(v -> v.getId() == entryVersionId)
                .findFirst();
            WorkflowVersion workflowVersion;
            if (first.isPresent()) {
                workflowVersion = first.get();
            } else {
                LOG.error("tag does not exist, will not be transient");
                return true;
            }

            // send documents to zenodo
            ApiClient zenodoClient = new ApiClient();
            zenodoClient.addDefaultHeader("Authorization", "Bearer " + zenodoToken);
            // TODO: flesh out the workflow version of this

            // update the webservice with a new DOI status
            workflowsApi.updateWorkflowVersion(entryId, Lists.newArrayList(workflowVersion));
            return true;
        } else {
            LOG.error("type of message was incorrect, but not transient");
            return true;
        }
    }

    // TODO: the next two methods kinda suck and are repetitive due to type erasure
    @Override
    public String messageTypeHandled() {
        return DOIMessage.class.getName();
    }

    @Override
    public Class<DOIMessage> messageClassHandled() {
        return DOIMessage.class;
    }
}
