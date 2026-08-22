package io.theapp.platform.ride;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RideBookingController.class)
class RideBookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String BODY = """
            {
              "riderId":"demo-rider",
              "pickup":{"label":"Madhapur","latitude":17.4483,"longitude":78.3915},
              "dropoff":{"label":"HITEC City","latitude":17.4435,"longitude":78.3772}
            }
            """;

    @Test
    void createsBookingAndReturnsRequestedState() throws Exception {
        mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", "booking-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void replaysSameBookingForSameIdempotencyKey() throws Exception {
        String first = mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", "booking-replay-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String bookingId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(first).get("bookingId").asText();

        mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", "booking-replay-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId));
    }

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/rides/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForUnknownBooking() throws Exception {
        mockMvc.perform(get("/api/v1/rides/bookings/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
