-- this file is a last chance to modify any database schema in hibernate (hbm2ddl) create mode with postgres-specific
-- changes not possible in JPA

-- column remark support in JPA does not seem to work for postgres, possible starting point https://stackoverflow.com/questions/28773022/jpa-column-annotation-to-create-comment-description 
COMMENT ON COLUMN sourcefile_verified.platformversion IS 'By default set to null';
COMMENT ON COLUMN validation.message IS 'A mapping of file path to message.';
COMMENT ON COLUMN tool.lastbuild IS 'For automated builds: When refresh is hit, the last time the tool was built gets stored here. If tool was never built on quay.io, then last build will be null. N/A for hosted/manual path tools';
COMMENT ON COLUMN tool.lastupdated IS 'For automated builds: last time tool/namespace was refreshed Dockstore, tool info updated, default version selected. For hosted tools: when you created the tool';
COMMENT ON COLUMN tool.lastmodified IS 'For automated builds: N/A. For hosted: Last time a file was updated (new version created)';
COMMENT ON COLUMN tool.dbcreatedate IS 'For automated builds and hosted: Time registered on Dockstore, either by refresh or manual register. Can be blank as this column was added in 2018.';
COMMENT ON COLUMN tool.dbupdatedate IS 'For automated builds: Last time tool/namespace was refreshed, different version is selected, checker workflow was added, or tool info updated (like path information). For hosted: Last time a file was updated (new version created), default version selected. Can be blank as this column was added in 2018. Basically anytime db entry modified';
COMMENT ON COLUMN tag.lastbuilt IS 'For automated builds: The last time the container backing this tool version was built. For hosted: N/A';
COMMENT ON COLUMN tag.dbcreatedate IS 'For automated builds and hosted/manual path: Time registered on Dockstore, either by refresh or manual register. Can be blank as this column was added in 2018.';
COMMENT ON COLUMN tag.dbupdatedate IS 'For automated builds and hosted/manual path: Time created or last time version tab was edited (under actions in version tab). Basically anytime db entry modified';
COMMENT ON COLUMN workflow.lastmodified IS 'For remote: When refresh is hit, the last time GitHub repo was changed is recorded. Hosted: Last time a new version was made.';
COMMENT ON COLUMN workflow.lastupdated IS 'For remote: When refresh all is hit for first time. Hosted: Time created.';
COMMENT ON COLUMN workflow.dbcreatedate IS 'Remote: When workflow is refreshed for first time. Hosted: Time created';
COMMENT ON COLUMN workflow.dbupdatedate IS 'For remote: When refresh all is hit for first time, update workflow info (like path information), or add checker workflow. Hosted: Time created. Basically anytime db entry modified.';
COMMENT ON COLUMN workflowversion.lastmodified IS 'Remote: Last time version on GitHub repo was changed. Hosted: time version created';
COMMENT ON COLUMN workflowversion.dbcreatedate IS 'Remote: When workflow was refreshed for first time. Hosted: time version created';
COMMENT ON COLUMN workflowversion.dbupdatedate IS 'Remote: When workflow was refreshed for the first time or last time version was edited under action column in versions tab. Hosted: time version created or last time version was edited under actions column in versions tab. Basically anytime db entry modified';
-- postgres partial indexes seem unsupported https://stackoverflow.com/questions/12025844/how-to-annotate-unique-constraint-with-where-clause-in-jpa
CREATE UNIQUE INDEX full_workflow_name ON workflow USING btree (sourcecontrol, organization, repository, workflowname) WHERE workflowname IS NOT NULL;
CREATE UNIQUE INDEX full_tool_name ON tool USING btree (registry, namespace, name, toolname) WHERE toolname IS NOT NULL;
CREATE UNIQUE INDEX partial_workflow_name ON workflow USING btree (sourcecontrol, organization, repository) WHERE workflowname IS NULL;
CREATE UNIQUE INDEX partial_tool_name ON tool USING btree (registry, namespace, name) WHERE toolname IS NULL;
CREATE UNIQUE INDEX full_service_name ON service USING btree (sourcecontrol, organization, repository, workflowname) WHERE workflowname IS NOT NULL;
CREATE UNIQUE INDEX partial_service_name ON service USING btree (sourcecontrol, organization, repository) WHERE workflowname IS NULL;

-- unable to convert these to JPA properly
ALTER TABLE token ADD CONSTRAINT fk_userid_with_enduser FOREIGN KEY (userid) REFERENCES public.enduser (id) MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION;
-- https://liquibase.jira.com/browse/CORE-2895
CREATE UNIQUE INDEX organization_name_index on organization (LOWER(name));
CREATE UNIQUE INDEX collection_name_index on collection (LOWER(name), organizationid);
-- JPA doesn't seem to understand deferrable constraints, need to insert them this way
ALTER TABLE tag ADD CONSTRAINT fk_tagVersionMetadata FOREIGN KEY(id) REFERENCES public.version_metadata (id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE workflowversion ADD CONSTRAINT fk_workflowVersionMetadata FOREIGN KEY(id) REFERENCES public.version_metadata (id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE sourcefile ADD CONSTRAINT filecontentsha1 FOREIGN KEY(sha1) REFERENCES public.filecontent (id) DEFERRABLE INITIALLY DEFERRED;


-- cannot seem to define these due to inheritance
ALTER TABLE tag ADD CONSTRAINT parentid_constraint FOREIGN KEY(parentid) REFERENCES tool (id) DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX unique_collection_entry ON collection_entry_version USING btree (collection_id, entry_id) WHERE version_id IS NULL;
CREATE UNIQUE INDEX unique_collection_entry_version ON collection_entry_version USING btree (collection_id, entry_id, version_id) WHERE version_id IS NOT NULL;
