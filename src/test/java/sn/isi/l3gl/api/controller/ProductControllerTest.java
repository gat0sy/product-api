package sn.isi.l3gl.api.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import sn.isi.l3gl.core.entity.Product;
import sn.isi.l3gl.core.service.ProductService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService productService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(productService)).build();
    }

    @Test
    void createProduct_passesBodyToServiceAndReturnsCreatedProduct() throws Exception {
        when(productService.createProduct(any(Product.class)))
                .thenReturn(new Product(1L, "Keyboard", "Mechanical", 49.99, 12));

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Keyboard","description":"Mechanical","price":49.99,"quantity":12}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Keyboard"))
                .andExpect(jsonPath("$.quantity").value(12));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productService).createProduct(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Keyboard");
        assertThat(captor.getValue().getPrice()).isEqualTo(49.99);
        assertThat(captor.getValue().getQuantity()).isEqualTo(12);
    }

    @Test
    void listProducts_returnsAllProducts() throws Exception {
        when(productService.listProducts()).thenReturn(List.of(
                new Product(1L, "Keyboard", "Mechanical", 49.99, 12),
                new Product(2L, "Mouse", "Wireless", 19.99, 3)));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Keyboard"))
                .andExpect(jsonPath("$[1].name").value("Mouse"));
    }

    @Test
    void listProducts_returnsEmptyArrayWhenNoProducts() throws Exception {
        when(productService.listProducts()).thenReturn(List.of());

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void updateQuantity_passesIdAndQuantityToService() throws Exception {
        when(productService.updateQuantity(1L, 5))
                .thenReturn(new Product(1L, "Keyboard", "Mechanical", 49.99, 5));

        mockMvc.perform(put("/api/products/1").param("quantity", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(5));

        verify(productService).updateQuantity(1L, 5);
    }

    @Test
    void updateQuantity_withoutQuantityParam_returnsBadRequest() throws Exception {
        mockMvc.perform(put("/api/products/1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void countLowStockProducts_returnsServiceCount() throws Exception {
        when(productService.countLowStockProducts()).thenReturn(3L);

        mockMvc.perform(get("/api/products/low-stock/count"))
                .andExpect(status().isOk())
                .andExpect(content().string("3"));
    }
}
