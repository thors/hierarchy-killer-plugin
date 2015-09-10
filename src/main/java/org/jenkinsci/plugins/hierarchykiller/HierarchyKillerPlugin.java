package org.jenkinsci.plugins.hierarchykiller;

import static hudson.init.InitMilestone.PLUGINS_STARTED;
import hudson.Plugin;
import hudson.EnvVars;
import hudson.init.Initializer;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.util.DescribableList;
import hudson.model.AbstractBuild;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;

import com.google.common.collect.Maps;

public class HierarchyKillerPlugin extends Plugin {
    private static final Logger LOGGER = Logger.getLogger(HierarchyKillerPlugin.class.getName());
    
    private static ConcurrentHashMap<Run<?,?>, RunData> iJobMap;
    private static HierarchyKillerPlugin instance;
    private static int iVerbosity = 6;
    private static int iHitCount = 0;
    private static final int debug=4;
    private static final int error=1;
    private static final int warning=2;
    private static final int info=3;
    private static final int info2=4;

    public static void notifyRunStarted(Run<?,?> run, TaskListener listener) {
	if (null == instance) {
	    log(listener, "HierarchyKillerPlugin.notifyRunStarted: Plugin not yet initialized");
	    return;
	}
	EnvVars env = getEnvVars(run, listener);
	if (!"true".equals(env.get("ENABLE_HIERARCHY_KILLER","false"))) {
	    log(debug, listener, "HierarchyKillerPlugin: ENABLE_HIERARCHY_KILLER not true, this build is not governed by HierarchyKiller");
	    return;
	}
	RunData r = new RunData();
	r.iListener = listener;
	log(debug, listener, "HierarchyKillerPlugin: RunData:" + r + ", listener:" + listener + ", iJobMap:" + iJobMap);
	iJobMap.put(run, r);
	for(Cause c: run.getCauses()) {
	    if (c instanceof Cause.UpstreamCause) {
		Cause.UpstreamCause usc = (Cause.UpstreamCause) c;
		r.iUpstream = usc.getUpstreamRun();
		RunData parentRunData = iJobMap.get(r.iUpstream);
		if (null != parentRunData) {
		    TaskListener parentTaskListener = parentRunData.iListener;
		    // add current run to parents child-list (we know now that parent and child have hierarchy-killer enabled)		 
		    log(iJobMap.get(usc.getUpstreamRun()).iListener, "Triggered: " + env.get("JENKINS_URL")  + run.getUrl());
		    parentRunData.iDownstream.add(run); 
		}
	    }
	}
	printStats(listener);
    }

    public static void notifyRunCompleted(Run<?,?> run, TaskListener listener) {
	Result result = null;
	if (null == instance ) {
	    log(listener, "notifyRunCompleted: Plugin not yet initialized");
	    return;
	}
	if (!iJobMap.containsKey(run)) {
	    log(listener, "HierarchyKillerPlugin: notifyRunCompleted: This job is not governed by HierarchyKillerPlugin");
	    return;
	}
	RunData runData = iJobMap.get(run); 
	if (null == runData) {
	    log(listener, "HierarchyKillerPlugin: notifyRunCompleted: No runData available to this run. This should never happen...");
	    return;
	}
	log(listener, "notifyRunCompleted");
	EnvVars env = getEnvVars(run, listener);
	if (!"true".equals(env.get("ENABLE_HIERARCHY_KILLER","false"))) {
	    log(listener, "notifyRunCompleted: ENABLE_HIERARCHY_KILLER not true, don't care about this build...");
	    iJobMap.remove(run);
	    return;
	}
	result = run.getResult();
	if (null == result) {
	    log(listener, "notifyRunCompleted: result == null, ignore");
	    iJobMap.remove(run);
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
	iJobMap.remove(run);
    }
    
    protected static void killUpAndDownstream(Run<?,?> run, TaskListener listener, EnvVars env, RunData runData) {
	String reason = "Killed via HierarchyPlugin by " + env.get("JENKINS_URL")  + run.getUrl();
	if ("true".equals(env.get("HIERARCHY_KILLER_KILL_UPSTREAM","false"))) {
	    if (null != runData.iUpstream && (runData.iUpstream.isBuilding())) {
		RunData upstreamRunData = iJobMap.get(runData.iUpstream);
		kill(runData.iUpstream, upstreamRunData.iListener, reason);
	    } else {
		log(debug, listener, "killUpAndDownstream: upstream: no running upstream job found");
	    }
        }
	if ("true".equals(env.get("HIERARCHY_KILLER_KILL_DOWNSTREAM","false"))) {
	    killAllDownstream(run, listener, env, runData, reason);
        }
    }

    protected static void printStats(TaskListener listener) {
	if (iVerbosity > debug) {
	    LOGGER.log(Level.INFO, "HierarchyKillerPlugin: iHitCount: " + iHitCount + ", size of job-list: " + iJobMap.size());
	}
    }

    protected static void kill(Run <?,?> run, TaskListener listener, String reason) {
	log(listener, reason);
	run.setResult(Result.ABORTED);
	if (run instanceof AbstractBuild) {
	    //As far as I know, all ongoing builds should implement the AbstractBuild interface; need to check for MatrixBuild
	    try {
		LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Aborted " + run.getUrl() + "(" + reason + ")");
		((AbstractBuild) run).doStop();
		iHitCount++;
	    } catch(IOException e) {
		LOGGER.log(Level.SEVERE, "HierarchyKillerPlugin: IOException while trying to abort " + run.getUrl());
	    } catch(ServletException e) {
		LOGGER.log(Level.SEVERE, "HierarchyKillerPlugin: ServletException while trying to abort " + run.getUrl());
	    }
	}
    }
    protected static void killAllDownstream(Run<?,?> run, TaskListener listener, EnvVars env, RunData runData, String reason) {
	/* Kill all downstream jobs */
	for(Run <?,?> r: runData.iDownstream) {
            if (!r.isBuilding()) {
		continue;
	    }
	    RunData downstreamRunData = iJobMap.get(r);
	    if ( null == downstreamRunData ) {
		LOGGER.log(Level.SEVERE, "HierarchyKillerPlugin: Run is in downstreamlist of another run, not completed, but not in run list.");
		LOGGER.log(Level.SEVERE, "HierarchyKillerPlugin: This should not happen. Run is only added to downstream list when it is governed by this plugin");
		continue;
	    }
	    kill(r, downstreamRunData.iListener, reason);
	}
    }

    protected static EnvVars getEnvVars(Run<?,?> run, TaskListener listener) {
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

    public static void notifyRunFinalized(Run<?,?> run) {
	if (null == instance ) {
	    return;
	}
	LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Finalized...");
	iJobMap.remove(run);
    }
    
    private static void log(int loglevel, final TaskListener listener, final String message) {
	if (loglevel < iVerbosity) {
	    log(listener, message);
	}
    }

    private static void log(final TaskListener listener, final String message) {
        listener.getLogger().println("HierarchyKillerPlugin: " + message);
    }

    @Initializer(after = PLUGINS_STARTED)
    public static void init() {
	LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Initialized...");
	instance = Jenkins.getInstance().getPlugin(HierarchyKillerPlugin.class);
	iJobMap = new ConcurrentHashMap<Run<?,?>, RunData>();
	if (null == instance) {
	    LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Initialization failed...");
	}
    }
      
    @Override
    public void start() throws Exception {
	try {
	    load();
	    LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Loaded...");
	} catch (IOException e) {
	    LOGGER.log(Level.SEVERE, "HierarchyKillerPlugin: Failed to load", e);
	}
    }
     
    @Override
    public void configure(StaplerRequest req, JSONObject formData) throws IOException, ServletException, FormException {
	try {
	    save();
	} catch (IOException e) {
	    throw new FormException(e, "endpoints");
	}
    }
    
    private void start(Runnable runnable) {
	Executors.newSingleThreadExecutor().submit(runnable);
    }
	
    public static class RunData {
	public List<Run<?,?> > iDownstream;
	public Run<?,?> iUpstream;
	public TaskListener iListener;
	public RunData() {
	    iListener = null;
	    iDownstream = new Vector<Run<?,?> >();
	    iUpstream = null;
	}
    }
}
