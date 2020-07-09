package io.dockstore.webservice.core.database;

import io.dockstore.common.SourceControl;

public class TrsTool {
    private final Long id;
    /**
     * sourceControl for Workflows, registry for Tools
     */
    private final SourceControl sourceControl;

    public TrsTool(final Long id, final SourceControl sourceControl) {
        this.id = id;
        this.sourceControl = sourceControl;
    }

    public Long getId() {
        return id;
    }

    public SourceControl getSourceControlOrRegistry() {
        return sourceControl;
    }
}
