/*
 * The MIT License
 * 
 * Copyright (c) 2015 Thorsten Möllers
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.buildhierarchykiller;

import hudson.EnvVars;
import hudson.Plugin;
import hudson.init.Initializer;
import hudson.model.*;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.init.InitMilestone.PLUGINS_STARTED;

public class BuildHierarchyKillerPlugin extends Plugin {
    private static final Logger LOGGER = Logger.getLogger(BuildHierarchyKillerPlugin.class.getName());
    
    private static BuildHierarchyKillerPlugin instance;
    private ConcurrentHashMap<AbstractBuild, RunData> jobMap;
    private int verbosity;
    private int hitCount;
    private final static int debug = 4;
    private final static int error=1;
    private final static int warning=2;
    private final static int info=3;
    private final static int info2=4;

    public static BuildHierarchyKillerPlugin get() {
	return instance;
    }

    public void notifyRunStarted(AbstractBuild run, TaskListener listener) {
	if (null == instance) {
	    log(listener, "BuildHierarchyKillerPlugin.notifyRunStarted: Plugin not yet initialized");
	    return;
	}
	EnvVars env = getEnvVars(run, listener);
	if (!"true".equals(env.get("ENABLE_HIERARCHY_KILLER","false"))) {
	    log(debug, listener, "BuildHierarchyKillerPlugin: ENABLE_HIERARCHY_KILLER not true, this build is not governed by BuildHierarchyKiller");
	    return;
	}
	RunData r = new RunData();
	r.listener = listener;
	jobMap.put(run, r);
	List<Cause> lCauses = run.getCauses();
	if (lCauses.size() == 1) {
	    Cause c = lCauses.get(0);
	    if (c instanceof Cause.UpstreamCause) {
		Cause.UpstreamCause usc = (Cause.UpstreamCause) c;
		if (usc.getUpstreamRun() instanceof AbstractBuild) {
		    r.upstream.add((AbstractBuild) usc.getUpstreamRun());
		    RunData parentRunData = jobMap.get((AbstractBuild) usc.getUpstreamRun());
		    if (null != parentRunData) {
			// add current run to parents child-list (we know now that parent and child have hierarchy-killer enabled)
			log(jobMap.get(usc.getUpstreamRun()).listener, "Triggered: " + env.get("JENKINS_URL","JENKINS_URL")  + run.getUrl());
			parentRunData.downstream.add(run); 
		    }
		}
	    }
	}
	printStats(listener);
    }

    public void notifyRunCompleted(AbstractBuild run, TaskListener listener) {
	if (null == instance ) {
	    log(listener, "notifyRunCompleted: Plugin not yet initialized");
	    return;
	}
	if (!jobMap.containsKey(run)) {
	    log(listener, "BuildHierarchyKillerPlugin: notifyRunCompleted: This job is not governed by BuildHierarchyKillerPlugin");
	    return;
	}
	RunData runData = jobMap.get(run); 
	if (null == runData) {
	    log(listener, "BuildHierarchyKillerPlugin: notifyRunCompleted: No runData available to this run. This should never happen...");
	    return;
	}
	if (!runData.reason.isEmpty()) {
	    log(listener, "Aborted by BuildHierarchyKillerPlugin" + runData.reason);
	}
	EnvVars env = getEnvVars(run, listener);
	if (!"true".equals(env.get("ENABLE_HIERARCHY_KILLER","false"))) {
	    log(listener, "notifyRunCompleted: ENABLE_HIERARCHY_KILLER not true, don't care about this build...");
	    jobMap.remove(run);
	    return;
	}
	Result result = run.getResult();
	if (null == result) {
	    log(listener, "notifyRunCompleted: result == null, ignore");
	    jobMap.remove(run);
	    return;
	}
	if ("true".equals(env.get("HIERARCHY_KILLER_KILL_UNSTABLE","false"))) {
	    if (result.isWorseThan(Result.SUCCESS)) {
		killUpAndDownstream(run, listener, env, runData);
	    }
	} else {
	    if (result.isWorseThan(Result.UNSTABLE)) {
		killUpAndDownstream(run, listener, env, runData);
	    }
	}
	jobMap.remove(run);
    }
    
    protected void killUpAndDownstream(AbstractBuild run, TaskListener listener, EnvVars env, RunData runData) {
	String reason = ", caused by " + env.get("JENKINS_URL")  + run.getUrl() + runData.reason;
	if ("true".equals(env.get("HIERARCHY_KILLER_KILL_UPSTREAM","false"))) {
	    for (AbstractBuild upstream: runData.upstream) {
		RunData upstreamRunData = jobMap.get(upstream);
		if (null != upstream && upstream.isBuilding()) { 
		    kill(upstream, upstreamRunData, reason);
		}
	    } 
        }
	if ("true".equals(env.get("HIERARCHY_KILLER_KILL_DOWNSTREAM","false"))) {
	    killAllDownstream(run, listener, env, runData, reason);
        }
    }

    protected void killAllDownstream(AbstractBuild run, TaskListener listener, EnvVars env, RunData runData, String reason) {
	/* Kill all downstream jobs */
	for(AbstractBuild r: runData.downstream) {
            if (!r.isBuilding()) {
		continue;
	    }
	    RunData downstreamRunData = jobMap.get(r);
	    if ( null == downstreamRunData ) {
		LOGGER.log(Level.SEVERE, "BuildHierarchyKillerPlugin: Run is in downstreamlist of another run, not completed, but not in run list.");
		LOGGER.log(Level.SEVERE, "BuildHierarchyKillerPlugin: This should not happen. Run is only added to downstream list when it is governed by this plugin");
		continue;
	    }
	    kill(r, downstreamRunData, reason);
	}
	for(Queue.Item item: Jenkins.getInstance().getQueue().getItems()) {
	    if (item.getCauses().size() == 1) {
		Cause c = item.getCauses().get(0);
		if (c instanceof Cause.UpstreamCause) {
		    Cause.UpstreamCause usc = (Cause.UpstreamCause) c;
		    if (run.equals(usc.getUpstreamRun())) {
				LOGGER.log(Level.INFO, "BuildHierarchyKiller: waiting item " + item.getUrl() + " aborted" + reason);
				Jenkins.getInstance().getQueue().cancel(item.task);
		    }
		}
	    }
	}
    }

    protected void printStats(TaskListener listener) {
	if (verbosity > debug) {
	    LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: hitCount: " + hitCount + ", size of job-list: " + jobMap.size());
	}
    }

	protected void kill(AbstractBuild run, RunData runData, String reason) {
		runData.reason = reason;
		if (run.isBuilding()) {
			//As far as I know, all ongoing builds should implement the AbstractBuild interface; need to check for MatrixBuild
			LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: Aborted " + run.getUrl() + '(' + reason + ')');
			run.getExecutor().doStop();
			run.setResult(Result.ABORTED);
			hitCount++;
		}
	}

    protected EnvVars getEnvVars(AbstractBuild run, TaskListener listener) {
	EnvVars env = null;
	try {
	    env = run.getEnvironment(listener);
	} catch(IOException e) {
	    e.printStackTrace(listener.getLogger());
	} catch(InterruptedException e) {
	    e.printStackTrace(listener.getLogger());
	}
	if ( null == env ) {
	    env = new EnvVars();
	}
	return env;
    }

    public void notifyRunFinalized(AbstractBuild run) {
	if (null == instance ) {
	    return;
	}
	jobMap.remove(run);
    }
    
    private void log(int loglevel, final TaskListener listener, final String message) {
	if (loglevel < verbosity) {
	    log(listener, message);
	}
    }

    private void log(final TaskListener listener, final String message) {
        listener.getLogger().println("BuildHierarchyKillerPlugin: " + message);
    }

    protected void instanceInit() {
	verbosity = 4;
	hitCount = 0;
	jobMap = new ConcurrentHashMap<AbstractBuild, RunData>();
    }

    @Initializer(after = PLUGINS_STARTED)
    public static void init() {
       LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: Initialized...");
       instance = Jenkins.getInstance().getPlugin(BuildHierarchyKillerPlugin.class);
       instance.instanceInit();
       if (null == instance) {
           LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: Initialization failed...");
       }
    }
      
    @Override
    public void start() throws Exception {
       try {
           load();
           LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: Loaded...");
       } catch (IOException e) {
           LOGGER.log(Level.SEVERE, "BuildHierarchyKillerPlugin: Failed to load", e);
       }
    }
     
    private void start(Runnable runnable) {
       Executors.newSingleThreadExecutor().submit(runnable);
    }

    public class RunData {
	private List<AbstractBuild > downstream;
	private List<AbstractBuild > upstream;
	public TaskListener listener;
	public String reason;
	
	public RunData() {
	    listener = null;
	    downstream = new Vector<AbstractBuild >();
	    upstream = new Vector<AbstractBuild >();
	    reason = "";
	}
    }
}
