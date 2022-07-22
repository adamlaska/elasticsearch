
/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.script;

import java.util.Map;

/**
 * A script used in the update API
 */
public abstract class UpdateScript {

    public static final String[] PARAMETERS = {};

    /** The context used to compile {@link UpdateScript} factories. */
    public static final ScriptContext<Factory> CONTEXT = new ScriptContext<>("update", Factory.class);

    /** The generic runtime parameters for the script. */
    private final Map<String, Object> params;

    private final UpdateCtxMap ctxMap;

    public UpdateScript(Map<String, Object> params, UpdateCtxMap ctxMap) {
        this.params = params;
        this.ctxMap = ctxMap;
    }

    /** Return the parameters for this script. */
    public Map<String, Object> getParams() {
        return params;
    }

    /** Return the update context for this script. */
    public Map<String, Object> getCtx() {
        return ctxMap;
    }

    /** Return the update metadata for this script */
    public Metadata metadata() {
        return ctxMap.getMetadata();
    }

    public abstract void execute();

    public interface Factory {
        UpdateScript newInstance(Map<String, Object> params, UpdateCtxMap ctxMap);
    }
}
