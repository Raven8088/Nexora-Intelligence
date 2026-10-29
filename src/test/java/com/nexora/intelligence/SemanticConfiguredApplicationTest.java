package com.nexora.intelligence;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With the inference engine, Semantic and Reports configured the application starts without contacting any of them
 * (nothing is listening on these ports), wires the planning service with execution, and exposes no business endpoint.
 */
@SpringBootTest(properties = {
        "nexora.intelligence.inference.base-url=http://127.0.0.1:9/v1",
        "nexora.intelligence.semantic.base-url=http://127.0.0.1:9",
        "nexora.intelligence.semantic.identity.tenant-id=00000000-0000-4000-8000-000000000001",
        "nexora.intelligence.semantic.identity.token-uri=http://127.0.0.1:9/token",
        "nexora.intelligence.semantic.identity.jwk-set-uri=http://127.0.0.1:9/certs",
        "nexora.intelligence.semantic.identity.issuer=http://127.0.0.1:9/realms/nexora",
        "nexora.intelligence.semantic.identity.client-id=nexora-intelligence",
        "nexora.intelligence.semantic.identity.client-secret=test-only",
        "nexora.intelligence.reports.base-url=http://127.0.0.1:9",
        "nexora.intelligence.reports.identity.tenant-id=00000000-0000-4000-8000-000000000001",
        "nexora.intelligence.reports.identity.token-uri=http://127.0.0.1:9/token",
        "nexora.intelligence.reports.identity.jwk-set-uri=http://127.0.0.1:9/certs",
        "nexora.intelligence.reports.identity.issuer=http://127.0.0.1:9/realms/nexora",
        "nexora.intelligence.reports.identity.client-id=nexora-intelligence",
        "nexora.intelligence.reports.identity.client-secret=test-only"})
@AutoConfigureMockMvc
class SemanticConfiguredApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mvc;

    @Test
    void theIntegrationIsWiredWithoutAnyConnectionAtStartup() {
        assertThat(context.getBeanNamesForType(SemanticCatalogClient.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(LogicalPlanClient.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(QuestionPlanningService.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ReportExecutionClient.class)).hasSize(1);
        assertThat(context.getBean(QuestionPlanningService.class).canExecute()).isTrue();
    }

    @Test
    void noBusinessEndpointIsExposedAndHealthStaysUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        for (String path : new String[] {"/questions", "/api/v1/questions", "/api/v1/plans"}) {
            mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isNotFound());
        }
    }
}
