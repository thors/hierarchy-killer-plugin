package org.jenkinsci.plugins.buildhierarchykiller;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.AbstractBuild;
import hudson.model.listeners.RunListener;
import jenkins.YesNoMaybe;

@Extension(dynamicLoadable=YesNoMaybe.YES)
public class BuildHierarchyKillerRunListener extends RunListener<Run<?, ?>> {
    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
	if (run instanceof AbstractBuild && null != BuildHierarchyKillerPlugin.get()) {
	    BuildHierarchyKillerPlugin.get().notifyRunStarted((AbstractBuild) run, listener);
	}
    }
	
    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
	if (run instanceof AbstractBuild && null != BuildHierarchyKillerPlugin.get()) {
	    BuildHierarchyKillerPlugin.get().notifyRunCompleted((AbstractBuild) run, listener);
	}
    }
	
    @Override
    public void onFinalized(Run<?, ?> run) {
	if (run instanceof AbstractBuild && null != BuildHierarchyKillerPlugin.get()) {
	    BuildHierarchyKillerPlugin.get().notifyRunFinalized((AbstractBuild) run);
	}
    }
}
