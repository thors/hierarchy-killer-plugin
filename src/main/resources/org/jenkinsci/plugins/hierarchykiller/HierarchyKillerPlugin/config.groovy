package org.jenkinsci.plugins.hierarchykiller.HierarchyKillerGlobalConfiguration

def f=namespace(lib.FormTagLib)

f.section(title:_("Hierarchy Killer")) {
    f.block {
        addCaption:_("Add a new endpoint"), deleteCaption:_("Delete endpoint"))
    }
}
