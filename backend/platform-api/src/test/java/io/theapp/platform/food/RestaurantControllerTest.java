package io.theapp.platform.food;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RestaurantController.class)
class RestaurantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsAvailableRestaurants() throws Exception {
        mockMvc.perform(get("/api/v1/food/restaurants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("hyd-biryani-house"))
                .andExpect(jsonPath("$[0].acceptingOrders").value(true));
    }
}
