package com.nayon.api.gacha;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;

class GachaEngineTest {
    private final GachaCatalog catalog = new GachaCatalog(new ObjectMapper());

    @Test
    void catalogMatchesUnitySourceVersionAndAllSixEquipmentTypes() {
        assertThat(catalog.version()).isEqualTo("unity-equipment-2026-08-16");
        assertThat(catalog.candidates("COMMON", false))
                .extracting(GachaCatalog.Entry::type)
                .contains(1, 2, 3, 4, 5, 6);
        assertThat(catalog.candidates("EPIC", true)).hasSize(7);
        assertThat(catalog.candidates("UNIQUE", true)).hasSize(7);
    }

    @Test
    void regularBannerUsesExactUnityGradeBoundaries() {
        assertThat(regularGrade(GachaBanner.COMMON, 0.5999)).isEqualTo("COMMON");
        assertThat(regularGrade(GachaBanner.COMMON, 0.60)).isEqualTo("UNCOMMON");
        assertThat(regularGrade(GachaBanner.COMMON, 0.85)).isEqualTo("RARE");
        assertThat(regularGrade(GachaBanner.COMMON, 0.96)).isEqualTo("EPIC");
        assertThat(regularGrade(GachaBanner.ADVANCED, 0.5799)).isEqualTo("UNCOMMON");
        assertThat(regularGrade(GachaBanner.ADVANCED, 0.58)).isEqualTo("RARE");
        assertThat(regularGrade(GachaBanner.ADVANCED, 0.85)).isEqualTo("EPIC");
        assertThat(regularGrade(GachaBanner.ADVANCED, 0.96)).isEqualTo("UNIQUE");
    }

    @Test
    void tenthChromaDrawGuaranteesHeroAndResetsHeroPity() {
        FakeRandom random = new FakeRandom(
                0.10, 0.10, 0.10, 0.10, 0.10,
                0.10, 0.10, 0.10, 0.10, 0.10);
        GachaEngine.GachaOutcome outcome = new GachaEngine(catalog, random).draw(
                new GachaSpec(GachaBanner.CHROMA_SEASON_01, GachaPayment.DIAMOND,
                        10, "CURRENCY", "DIAMOND", 3200),
                GachaPity.NONE);

        assertThat(outcome.awards()).hasSize(10);
        assertThat(outcome.awards().subList(0, 9)).allMatch(award -> !award.chroma());
        assertThat(outcome.awards().getLast().grade()).isEqualTo("EPIC");
        assertThat(outcome.awards().getLast().chroma()).isTrue();
        assertThat(outcome.pity()).isEqualTo(new GachaPity(0, 10));
    }

    @Test
    void fiftiethChromaDrawGuaranteesLegendaryAndResetsBothPities() {
        GachaEngine.GachaOutcome outcome = new GachaEngine(
                catalog, new FakeRandom(0.10)).draw(
                new GachaSpec(GachaBanner.CHROMA_SEASON_01,
                        GachaPayment.CHROMA_FRAGMENT, 1,
                        "ITEM", "CHROMA_FRAGMENT", 30),
                new GachaPity(9, 49));

        assertThat(outcome.awards().getFirst().grade()).isEqualTo("UNIQUE");
        assertThat(outcome.awards().getFirst().chroma()).isTrue();
        assertThat(outcome.pity()).isEqualTo(GachaPity.NONE);
    }

    private String regularGrade(GachaBanner banner, double roll) {
        GachaEngine.GachaOutcome outcome = new GachaEngine(
                catalog, new FakeRandom(roll)).draw(
                new GachaSpec(banner,
                        banner == GachaBanner.COMMON
                                ? GachaPayment.SILVER_KEY : GachaPayment.GOLD_KEY,
                        1, "ITEM", "KEY", 1),
                GachaPity.NONE);
        return outcome.awards().getFirst().grade();
    }

    private static final class FakeRandom implements GachaRandom {
        private final Deque<Double> doubles;

        private FakeRandom(Double... doubles) {
            this.doubles = new ArrayDeque<>(Arrays.asList(doubles));
        }

        @Override
        public double nextDouble() {
            return doubles.removeFirst();
        }

        @Override
        public int nextInt(int bound) {
            return 0;
        }
    }
}
