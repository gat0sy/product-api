package sn.isi.l3gl.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.isi.l3gl.core.repository.ProductRepository;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application context against an in-memory H2 database (profile "test"):
 * controller -> product-core service -> JPA repository.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ProductApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
    }

    private long createProduct(String name, int quantity) throws Exception {
        String json = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"test","price":10.0,"quantity":%d}
                                """.formatted(name, quantity)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(json).read("$.id", Long.class);
    }

    @Test
    void createdProductIsPersistedAndListed() throws Exception {
        long id = createProduct("Keyboard", 12);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].name").value("Keyboard"))
                .andExpect(jsonPath("$[0].quantity").value(12));
    }

    @Test
    void updateQuantityIsPersisted() throws Exception {
        long id = createProduct("Keyboard", 12);

        mockMvc.perform(put("/api/products/{id}", id).param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2));

        mockMvc.perform(get("/api/products"))
                .andExpect(jsonPath("$[0].quantity").value(2));
    }

    @Test
    void lowStockCountIncludesQuantitiesUpToFive() throws Exception {
        createProduct("Empty", 0);
        createProduct("AtThreshold", 5);
        createProduct("AboveThreshold", 6);
        createProduct("Plenty", 100);

        mockMvc.perform(get("/api/products/low-stock/count"))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));
    }

    @Test
    void healthAndProbeEndpointsAreUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    @Test
    void prometheusEndpointExposesMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }
}
