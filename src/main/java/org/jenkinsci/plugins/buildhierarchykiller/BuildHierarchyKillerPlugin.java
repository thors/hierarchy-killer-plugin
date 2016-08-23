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
    private ConcurrentHashMap<Run, RunData> jobMap;
    private int verbosity = 4;
    private int hitCount = 0;
    private boolean active = true;
    private final static int debug = 5;
    private final static int error=1;
    private final static int warning=2;
    private final static int info=3;
    private final static int info2=4;

    /**
     * This method can be used to change verbosity via groovy console
     *
     * @param verbosity
     */
    public void setVerbosity(int verbosity) {
        this.verbosity = verbosity;
    }

    /**
     * Get current level of verbosity via groovy console
     */
    public int getVerbosity() {
        return verbosity;
    }

    /**
     * This method can be used to disable the hierarchy killer at runtime without changing all environment variables
     *
     * @param active
     */
    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            jobMap.clear();
        }
    }

    /**
     * Get current active state via groovy console
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Get number of jobs aborted by the plugin (since Jenkins start)
     * @return
     */
    public int getHitCount() {
        return hitCount;
    }

    /**
     * Get number of running jobs managed by this plugin
     * @return
     */
    public int getActiveManagedJobsCount() {
        return jobMap.size();
    }

    /**
     * Get instance of this plugin (which is a singleton)
     * @return
     */
    public static BuildHierarchyKillerPlugin get() {
        return instance;
    }

    /**
     * This method is invoked for the event of job start
     * Method might be called asynchronous, job state already be completed or finalized when event is received
     *
     * @param run reference to the running job
     * @param listener TaskListener for ongoing execution
     */
    void notifyRunStarted(Run run, TaskListener listener) {
        if (null == instance) {
            log(warning, listener, "BuildHierarchyKillerPlugin.notifyRunStarted: Plugin not yet initialized");
            return;
        }
        if (!active) {
            LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: BuildHierarchyKillerPlugin.notifyRunStarted: Plugin disabled");
            return;
        }
        EnvVars env = getEnvVars(run, listener);
        if (!"true".equals(env.get("ENABLE_HIERARCHY_KILLER","false"))) {
            log(debug, listener, "BuildHierarchyKillerPlugin: ENABLE_HIERARCHY_KILLER not true, this build is not governed by BuildHierarchyKiller");
            return;
        }
        log(debug, listener, "BuildHierarchyKillerPlugin: adding " + run.getUrl());

        RunData r = new RunData();
        r.enabled = true;
        r.killDownstream = "true".equals(env.get("HIERARCHY_KILLER_KILL_DOWNSTREAM","false"));
        r.killUpstream = "true".equals(env.get("HIERARCHY_KILLER_KILL_UPSTREAM","false"));
        r.killUnstable = "true".equals(env.get("HIERARCHY_KILLER_KILL_UNSTABLE","false"));
        r.listener = listener;

        jobMap.put(run, r);
        List<Cause> lCauses = run.getCauses();
        if (lCauses.size() == 1) {
            Cause c = lCauses.get(0);
            if (c instanceof Cause.UpstreamCause) {
                Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause) c;
                if (upstreamCause.getUpstreamRun() != null) {
                    r.upstream.add((Run) upstreamCause.getUpstreamRun());
                    RunData parentRunData = jobMap.get((Run) upstreamCause.getUpstreamRun());
                    if (null != parentRunData) {
                        // add current run to parents child-list (we know now that parent and child have hierarchy-killer enabled)
                        log(info, jobMap.get(upstreamCause.getUpstreamRun()).listener, "Triggered: " + env.get("JENKINS_URL","JENKINS_URL")  + run.getUrl());
                        parentRunData.downstream.add(run);
                    }
                }
            }
        }
        if (verbosity > debug) {
            printStats();
        }
    }

    /**
     * This method is invoked for the event of job completion (that is, after last build step, before first post-build step)
     * Method might be called asynchronous, job state already be finalized when event is received
     *
     * @param run reference to the running job
     * @param listener TaskListener for ongoing execution
     */
    void notifyRunCompleted(Run run, TaskListener listener) {
        if (null == instance ) {
            log(warning, listener, "notifyRunCompleted: Plugin not yet initialized");
            return;
        }
        if (!active) {
            LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: BuildHierarchyKillerPlugin.notifyRunCompleted: Plugin disabled");
            return;
        }
        if (!jobMap.containsKey(run)) {
            log(info, listener, "BuildHierarchyKillerPlugin: notifyRunCompleted: This job is not governed by BuildHierarchyKillerPlugin");
            return;
        }
        RunData runData = jobMap.get(run);
        if (null == runData) {
            log(error, listener, "BuildHierarchyKillerPlugin: notifyRunCompleted: No runData available for " + run.getUrl() + ". This should never happen...");
            return;
        }
        if (!runData.reason.isEmpty()) {
            log(info, listener, "Aborted by BuildHierarchyKillerPlugin" + runData.reason);
        }

        EnvVars env = getEnvVars(run, listener);
        if (!runData.enabled) {
            log(info, listener, "notifyRunCompleted: ENABLE_HIERARCHY_KILLER not true, don't care about this build...");
            return;
        }
        Result result = run.getResult();
        if (null == result) {
            log(warning, listener, "BuildHierarchyKillerPlugin: " + run.getUrl() + ", notifyRunCompleted: result == null, ignore");
            jobMap.remove(run);
            return;
        }
        if (runData.killUnstable) {
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

    /**
     * This method is invoked for the event of job finalized (that is, after the last post-build step was performed)
     * Method might be called asynchronous, job state already be finalized when event is received
     *
     * @param run
     */
    void notifyRunFinalized(Run run) {
        if (null == instance) {
            return;
        }
        if (!active) {
            LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: BuildHierarchyKillerPlugin.notifyRunFinalized: Plugin disabled");
            return;
        }
        jobMap.remove(run);
    }

    /**
     * Kill all up- and downstream jobs to this one, if so indicated in run-data
     * Conveniently, only direct predecessors and descendants need to be aborted, since a new RunCompleted indication
     * will be received once they are terminated which then triggers termination of their predecessors/descendants
     *
     * @param run
     * @param listener
     * @param env
     * @param runData
     */
    protected void killUpAndDownstream(Run run, TaskListener listener, EnvVars env, RunData runData) {
        String reason = ", caused by " + env.get("JENKINS_URL")  + run.getUrl() + runData.reason;
        if (runData.killUpstream) {
            for (Run upstream: runData.upstream) {
                RunData upstreamRunData = jobMap.get(upstream);
                if (upstream.isBuilding()) {
                    kill(upstream, upstreamRunData, reason);
                }
            }
        }
        if (runData.killDownstream) {
            killAllDownstream(run, listener, runData, reason);
        }
    }

    /**
     * Kill all downstream jobs
     * Conveniently, only direct predecessors and descendants need to be aborted, since a new RunCompleted indication
     * will be received once they are terminated which then triggers termination of their predecessors/descendants
     *
     * @param run
     * @param listener
     * @param runData
     * @param reason
     */
    protected void killAllDownstream(Run run, TaskListener listener, RunData runData, String reason) {
        for(Run r: runData.downstream) {
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
                        log(info, listener, "BuildHierarchyKiller: waiting item " + item.getUrl() + " aborted" + reason);
                        Jenkins.getInstance().getQueue().cancel(item);
                    }
                }
            }
        }
    }

    /**
     * Provide some debug output (how many jobs were aborted by this plugin, how many jobs are currently registered)
     */
    public void printStats() {
        LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: hitCount: " + hitCount + ", size of job-list: " + jobMap.size());
    }

    /**
     * Kill an ongoing build, add reason to runData and to build console log
     * @param run
     * @param runData
     * @param reason
     */
    protected void kill(Run run, RunData runData, String reason) {
        runData.reason = reason;
        if (run.isBuilding()) {
            //As far as I know, all ongoing builds should implement the Run interface; need to check for MatrixBuild
            LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: Aborted " + run.getUrl() + '(' + reason + ')');
            run.getExecutor().doStop();
            run.setResult(Result.ABORTED);
            hitCount++;
        }
    }

    /**
     * Helper to get environment variables of an ongoing build. Wrapper to catch exceptions
     *
     * @param run
     * @param listener
     * @return
     */
    protected EnvVars getEnvVars(Run run, TaskListener listener) {
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

    /**
     *
     * @param loglevel
     * @param listener
     * @param message
     */
    private void log(int loglevel, final TaskListener listener, final String message) {
        if (loglevel < verbosity) {
            log(listener, message);
        }
    }

    /**
     * Write a log message to the logger of the given TaskListener
     *
     * @param listener listener to log to
     * @param message message to write to listener log
     */
    private void log(final TaskListener listener, final String message) {
        listener.getLogger().println("BuildHierarchyKillerPlugin: " + message);
    }

    /**
     * Initiate current instance
     */
    protected void instanceInit() {
        jobMap = new ConcurrentHashMap<Run, RunData>();
    }

    /**
     * instantiate and initialize plugin
     */
    @Initializer(after = PLUGINS_STARTED)
    public static void init() {
        LOGGER.log(Level.INFO, "BuildHierarchyKillerPlugin: Initialized...");
        instance = Jenkins.getInstance().getPlugin(BuildHierarchyKillerPlugin.class);
        instance.instanceInit();
        if (null == instance) {
            LOGGER.log(Level.SEVERE, "BuildHierarchyKillerPlugin: Initialization failed...");
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

    /**
     * Collect information for an ongoing build
     */
    class RunData {
        private final List<Run > downstream;
        private final List<Run > upstream;
        TaskListener listener;
        String reason;
        boolean killDownstream;
        boolean killUpstream;
        boolean killUnstable;
        boolean enabled;
        RunData() {
            listener = null;
            downstream = new Vector<Run >();
            upstream = new Vector<Run >();
            reason = "";
        }
    }
}
