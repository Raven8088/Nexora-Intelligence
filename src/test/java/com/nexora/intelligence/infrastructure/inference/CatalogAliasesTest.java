package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CHANNEL;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CUSTOMER_AGE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_COUNT;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.PAID;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogAliasesTest {

    private final CatalogAliases aliases = new CatalogAliases(CATALOG);

    @Test
    void aliasesFollowCatalogOrderPerKind() {
        assertThat(aliases.metricAliases()).containsExactly("m1", "m2");
        assertThat(aliases.dimensionAliases()).containsExactly("d1", "d2", "d3", "d4", "d5");
        assertThat(aliases.metric("m1")).contains(TOTAL_SALES.id());
        assertThat(aliases.metric("m2")).contains(ORDER_COUNT.id());
        assertThat(aliases.dimension("d2")).contains(ORDER_DATE.id());
        assertThat(aliases.aliasOf(CHANNEL.id())).contains("d5");
    }

    @Test
    void aliasesAreClosedAndKindSpecific() {
        assertThat(aliases.metric("d1")).isEmpty();
        assertThat(aliases.dimension("m1")).isEmpty();
        assertThat(aliases.metric("m3")).isEmpty();
        assertThat(aliases.definition("x1")).isEmpty();
        assertThat(aliases.definition("M1")).isEmpty();
        assertThat(aliases.definition(TOTAL_SALES.id().toString())).isEmpty();
        assertThat(aliases.definition(TOTAL_SALES.key().value())).isEmpty();
        assertThat(aliases.definition("d4")).contains(CUSTOMER_AGE.id());
        assertThat(aliases.aliasOf(id())).isEmpty();
    }

    @Test
    void temporalDimensionsAreOnlyPeriodAndGranularityTargets() {
        assertThat(aliases.temporalAliases()).containsExactly("d2");
        assertThat(aliases.filterTargetAliases()).containsExactly("d1", "d3", "d4", "d5", "m1", "m2")
                .doesNotContain("d2");
        assertThat(aliases.dimensions().get("d3")).isEqualTo(PAID);
        assertThat(aliases.dimensions().get("d1")).isEqualTo(REGION);
    }

    @Test
    void anEmptyCatalogHasNoAliases() {
        CatalogAliases empty = new CatalogAliases(new SemanticCatalog(SCOPE, List.of(), List.of(), List.of()));

        assertThat(empty.metricAliases()).isEmpty();
        assertThat(empty.dimensionAliases()).isEmpty();
        assertThat(empty.filterTargetAliases()).isEmpty();
    }
}
