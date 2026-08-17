package com.nayon.api.limitedbenefit;

public class LimitedBenefitException extends RuntimeException {
    private final String code;

    public LimitedBenefitException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
