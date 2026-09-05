package io.theapp.platform.food;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/food")
public class RestaurantController {

    @GetMapping("/restaurants")
    public List<RestaurantSummary> restaurants() {
        return List.of(
                new RestaurantSummary("hyd-biryani-house", "Hyderabad Biryani House", "Biryani", 4.7, 28, true),
                new RestaurantSummary("deccan-kitchen", "Deccan Kitchen", "South Indian", 4.5, 24, true),
                new RestaurantSummary("green-bowl", "Green Bowl", "Healthy", 4.4, 21, true)
        );
    }

    public record RestaurantSummary(
            String id,
            String name,
            String cuisine,
            double rating,
            int estimatedDeliveryMinutes,
            boolean acceptingOrders) {}
}
