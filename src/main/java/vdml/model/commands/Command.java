package vdml.model.commands;

public interface Command {
    void undo();
    void redo();
}
