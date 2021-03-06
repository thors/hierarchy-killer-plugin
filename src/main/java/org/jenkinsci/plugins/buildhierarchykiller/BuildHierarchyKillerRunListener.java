package org.jenkinsci.plugins.buildhierarchykiller;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import jenkins.YesNoMaybe;

import javax.annotation.Nonnull;

@Extension(dynamicLoadable=YesNoMaybe.YES)
public class BuildHierarchyKillerRunListener extends RunListener<Run> {
    @Override
    public void onStarted(Run run, TaskListener listener) {
        if (null != BuildHierarchyKillerPlugin.get()) {
            BuildHierarchyKillerPlugin.get().notifyRunStarted(run, listener);
        }
    }

    @Override
    public void onCompleted(Run run, @Nonnull TaskListener listener) {
        if (null != BuildHierarchyKillerPlugin.get()) {
            BuildHierarchyKillerPlugin.get().notifyRunCompleted(run, listener);
        }
    }

    @Override
    public void onFinalized(Run run) {
        if (null != BuildHierarchyKillerPlugin.get()) {
            BuildHierarchyKillerPlugin.get().notifyRunFinalized(run);
        }
    }
}
