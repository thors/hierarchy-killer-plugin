package org.jenkinsci.plugins.hierarchykiller;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import jenkins.YesNoMaybe;

@Extension(dynamicLoadable=YesNoMaybe.YES)
public class HierarchyKillerRunListener extends RunListener<Run<?, ?>> {
    
    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
	HierarchyKillerPlugin.notifyRunStarted(run, listener);
    }
	
    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
	HierarchyKillerPlugin.notifyRunCompleted(run, listener);
    }
	
    @Override
    public void onFinalized(Run<?, ?> run) {
	HierarchyKillerPlugin.notifyRunFinalized(run);
    }	
}
