package io.theapp.platform.ride;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ridebooking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
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

    private static final String DIFFERENT_BODY = """
            {
              "riderId":"demo-rider",
              "pickup":{"label":"Madhapur","latitude":17.4483,"longitude":78.3915},
              "dropoff":{"label":"Gachibowli","latitude":17.4401,"longitude":78.3489}
            }
            """;

    @Test
    void createsBookingAndReturnsRequestedState() throws Exception {
        mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void replaysSameBookingForSameIdempotencyKey() throws Exception {
        String idempotencyKey = key();
        String first = mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String bookingId = JsonPath.read(first, "$.bookingId");

        mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId));
    }

    @Test
    void rejectsSameIdempotencyKeyForDifferentPayload() throws Exception {
        String idempotencyKey = key();
        mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DIFFERENT_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/rides/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/rides/bookings")
                        .header("Idempotency-Key", "x".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForUnknownBooking() throws Exception {
        mockMvc.perform(get("/api/v1/rides/bookings/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    private String key() {
        return "test-" + UUID.randomUUID();
    }
}
