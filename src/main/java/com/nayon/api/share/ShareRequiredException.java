package com.nayon.api.share;

public class ShareRequiredException extends RuntimeException {
    public ShareRequiredException() {
        super("Open the share sheet before claiming the reward.");
    }
}
