package com.nayon.api.save;

public class SaveRevisionConflictException extends RuntimeException {

    public SaveRevisionConflictException() {
        super("The cloud save has changed.");
    }
}
