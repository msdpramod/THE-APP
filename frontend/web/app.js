const API_BASE = window.THE_APP_API_BASE || 'http://localhost:8080';

const ridePanel = document.querySelector('#ride-panel');
const foodPanel = document.querySelector('#food-panel');
const headline = document.querySelector('#headline');
const subhead = document.querySelector('#subhead');
const navButtons = [...document.querySelectorAll('.nav-pill')];
const quoteResult = document.querySelector('#quote-result');
const bookingResult = document.querySelector('#booking-result');
const bookButton = document.querySelector('#book-btn');
const restaurantList = document.querySelector('#restaurant-list');

const locations = {
  'Madhapur': { latitude: 17.4483, longitude: 78.3915 },
  'HITEC City': { latitude: 17.4435, longitude: 78.3772 },
  'Gachibowli': { latitude: 17.4401, longitude: 78.3489 },
  'Jubilee Hills': { latitude: 17.4326, longitude: 78.4071 }
};

let latestRideRequest = null;
let latestIdempotencyKey = null;

navButtons.forEach(button => button.addEventListener('click', () => setMode(button.dataset.mode)));
document.querySelector('#quote-btn').addEventListener('click', requestQuote);
bookButton.addEventListener('click', requestRide);
document.querySelector('#refresh-food').addEventListener('click', loadRestaurants);

function setMode(mode) {
  navButtons.forEach(button => button.classList.toggle('active', button.dataset.mode === mode));
  const food = mode === 'food';
  ridePanel.classList.toggle('hidden', food);
  foodPanel.classList.toggle('hidden', !food);
  headline.innerHTML = food ? 'Good food,<br><em>right now.</em>' : 'Your city,<br><em>on demand.</em>';
  subhead.textContent = food
    ? 'Discover nearby kitchens with delivery times you can actually plan around.'
    : 'Get a reliable ride in minutes. Clear pricing before you move.';
  if (food) loadRestaurants();
}

function currentRideRequest() {
  const pickupLabel = document.querySelector('#pickup').value.trim();
  const dropoffLabel = document.querySelector('#dropoff').value.trim();
  const pickup = locations[pickupLabel] || locations['Madhapur'];
  const dropoff = locations[dropoffLabel] || locations['HITEC City'];
  return {
    riderId: 'demo-rider',
    pickup: { label: pickupLabel || 'Pickup', ...pickup },
    dropoff: { label: dropoffLabel || 'Dropoff', ...dropoff }
  };
}

async function requestQuote() {
  latestRideRequest = currentRideRequest();
  latestIdempotencyKey = crypto.randomUUID();
  quoteResult.textContent = 'Calculating a fair estimate…';
  bookingResult.textContent = '';
  bookButton.classList.add('hidden');

  try {
    const response = await fetch(`${API_BASE}/api/v1/rides/quote`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pickup: latestRideRequest.pickup, dropoff: latestRideRequest.dropoff })
    });
    if (!response.ok) throw new Error(`Quote request failed (${response.status})`);
    const quote = await response.json();
    quoteResult.innerHTML = `<strong>₹${quote.estimatedFare}</strong> · ${quote.distanceKm} km · about ${quote.estimatedArrivalMinutes} min`;
    bookButton.classList.remove('hidden');
  } catch (error) {
    quoteResult.textContent = 'API unavailable. Start the platform API on port 8080 and try again.';
  }
}

async function requestRide() {
  if (!latestRideRequest || !latestIdempotencyKey) return;
  bookButton.disabled = true;
  bookingResult.textContent = 'Requesting your ride…';

  try {
    const response = await fetch(`${API_BASE}/api/v1/rides/bookings`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': latestIdempotencyKey
      },
      body: JSON.stringify(latestRideRequest)
    });
    if (!response.ok) throw new Error(`Booking request failed (${response.status})`);
    const booking = await response.json();
    bookingResult.innerHTML = `<strong>Ride requested</strong> · ${booking.status} · ${booking.bookingId.slice(0, 8)}`;
  } catch (error) {
    bookingResult.textContent = 'Ride request failed. Retry is safe because THE APP reuses the same idempotency key.';
  } finally {
    bookButton.disabled = false;
  }
}

async function loadRestaurants() {
  restaurantList.innerHTML = '<p>Finding nearby kitchens…</p>';
  try {
    const response = await fetch(`${API_BASE}/api/v1/food/restaurants`);
    if (!response.ok) throw new Error(`Restaurant request failed (${response.status})`);
    const restaurants = await response.json();
    restaurantList.innerHTML = restaurants.map(restaurant => `
      <article class="restaurant">
        <p>${restaurant.cuisine}</p>
        <h3>${restaurant.name}</h3>
        <div class="meta"><span>★ ${restaurant.rating}</span><span>${restaurant.estimatedDeliveryMinutes} min</span></div>
      </article>`).join('');
  } catch (error) {
    restaurantList.innerHTML = '<p>Restaurant service is unavailable right now.</p>';
  }
}
