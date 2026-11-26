package com.overdrive.app.automation.action;

import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.server.Messages;

import java.util.List;

/** Common metadata for actions with a constructor-defined schema. */
abstract class DeclaredAction extends BaseAction {
    private final String type;
    private final Label label;
    private final String description;
    private final List<Type> variables;

    DeclaredAction(String type, Label label, String description, List<Type> variables) {
        this.type = type;
        this.label = label;
        this.description = description;
        this.variables = variables;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public Label getLabel() {
        return label;
    }

    @Override
    public String getDescription() {
        // Resolve at serialization time, using the current request locale.
        return Messages.get(description);
    }

    @Override
    public List<Type> getVariables() {
        return variables;
    }
}
