package com.nayon.api.save;

public class SaveNotFoundException extends RuntimeException {

    public SaveNotFoundException() {
        super("No cloud save exists for this account.");
    }
}
