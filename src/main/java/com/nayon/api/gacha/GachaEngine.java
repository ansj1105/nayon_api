package com.nayon.api.gacha;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class GachaEngine {
    private static final int HERO_PITY = 10;
    private static final int LEGENDARY_PITY = 50;

    private final GachaCatalog catalog;
    private final GachaRandom random;

    public GachaEngine(GachaCatalog catalog, GachaRandom random) {
        this.catalog = catalog;
        this.random = random;
    }

    public String catalogVersion() {
        return catalog.version();
    }

    public GachaAward drawEquipment(String grade, boolean chroma) {
        return select(grade, chroma);
    }

    GachaOutcome draw(GachaSpec spec, GachaPity initialPity) {
        if (spec.banner() != GachaBanner.CHROMA_SEASON_01) {
            List<GachaAward> awards = new ArrayList<>();
            for (int index = 0; index < spec.count(); index++) {
                String grade = regularGrade(spec.banner(), random.nextDouble());
                awards.add(select(grade, false));
            }
            return new GachaOutcome(awards, GachaPity.NONE);
        }

        int hero = initialPity.hero();
        int legendary = initialPity.legendary();
        boolean batchChroma = false;
        List<GachaAward> awards = new ArrayList<>();
        for (int index = 0; index < spec.count(); index++) {
            hero++;
            legendary++;
            boolean legendaryGuaranteed = legendary >= LEGENDARY_PITY;
            boolean heroGuaranteed = hero >= HERO_PITY
                    || (spec.count() == 10 && index == 9 && !batchChroma);
            double roll = random.nextDouble();
            boolean chroma = legendaryGuaranteed || heroGuaranteed || roll >= 0.97d;
            String grade = legendaryGuaranteed
                    ? "UNIQUE"
                    : chroma ? "EPIC" : (roll < 0.70d ? "UNCOMMON" : "RARE");
            awards.add(select(grade, chroma));
            if (chroma) {
                batchChroma = true;
                hero = 0;
                if (legendaryGuaranteed) {
                    legendary = 0;
                }
            }
        }
        return new GachaOutcome(awards, new GachaPity(hero, legendary));
    }

    private String regularGrade(GachaBanner banner, double roll) {
        if (banner == GachaBanner.ADVANCED) {
            if (roll < 0.58d) return "UNCOMMON";
            if (roll < 0.85d) return "RARE";
            if (roll < 0.96d) return "EPIC";
            return "UNIQUE";
        }
        if (roll < 0.60d) return "COMMON";
        if (roll < 0.85d) return "UNCOMMON";
        if (roll < 0.96d) return "RARE";
        return "EPIC";
    }

    private GachaAward select(String grade, boolean chroma) {
        List<GachaCatalog.Entry> candidates = catalog.candidates(grade, chroma);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No gacha candidate for grade=" + grade + " chroma=" + chroma);
        }
        List<Integer> types = candidates.stream().map(GachaCatalog.Entry::type).distinct().toList();
        int selectedType = types.get(random.nextInt(types.size()));
        List<GachaCatalog.Entry> typed = candidates.stream()
                .filter(entry -> entry.type() == selectedType)
                .toList();
        GachaCatalog.Entry selected = typed.get(random.nextInt(typed.size()));
        return new GachaAward(UUID.randomUUID(), selected.code(), grade, chroma);
    }

    record GachaOutcome(List<GachaAward> awards, GachaPity pity) {
    }
}
