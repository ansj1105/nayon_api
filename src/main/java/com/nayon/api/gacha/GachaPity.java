package com.nayon.api.gacha;

public record GachaPity(int hero, int legendary) {
    public static final GachaPity NONE = new GachaPity(0, 0);
}
