package com.nexora.intelligence;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NexoraIntelligenceApplicationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void contextStartsWithoutAnyInterpreterAdapter() {
        assertThat(context.getBeanNamesForType(QuestionInterpreter.class)).isEmpty();
    }

    @Test
    void contextStartsWithoutSemanticAdaptersOrPlanningAsBefore() {
        assertThat(context.getBeanNamesForType(SemanticCatalogClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LogicalPlanClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(QuestionPlanningService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ReportExecutionClient.class)).isEmpty();
    }

    @Test
    void defaultPortIs8100() {
        assertThat(environment.getProperty("server.port")).isEqualTo("8100");
    }

    @Test
    void healthIsUpWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void healthProbesAreAvailable() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void noOtherActuatorOrBusinessEndpointIsExposed() throws Exception {
        for (String path : new String[] {"/actuator/info", "/actuator/env", "/actuator/beans", "/actuator/metrics",
                "/actuator/configprops", "/actuator/mappings", "/questions", "/api/v1/questions"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/questions").contentType("application/json").content("{\"text\":\"Ventas\"}"))
                .andExpect(status().isNotFound());
    }
}
