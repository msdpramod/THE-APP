package io.theapp.platform.ride;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RideQuoteController.class)
class RideQuoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsQuoteForValidCoordinates() throws Exception {
        String body = """
                {
                  "pickup": {"label":"Madhapur","latitude":17.4483,"longitude":78.3915},
                  "dropoff": {"label":"HITEC City","latitude":17.4435,"longitude":78.3772}
                }
                """;

        mockMvc.perform(post("/api/v1/rides/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.estimatedFare").isNumber())
                .andExpect(jsonPath("$.distanceKm").isNumber());
    }

    @Test
    void rejectsInvalidCoordinates() throws Exception {
        String body = """
                {
                  "pickup": {"label":"Invalid","latitude":120.0,"longitude":78.0},
                  "dropoff": {"label":"Valid","latitude":17.4,"longitude":78.4}
                }
                """;

        mockMvc.perform(post("/api/v1/rides/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
