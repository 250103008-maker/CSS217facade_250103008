public class Main {
    public static void main(String[] args) {
        FlightBookingFacade bookingFacade = new FlightBookingFacade();
        String testCard = "4532015112830366";

        System.out.println("=== TEST 1: Standard successful booking ===");
        String ticket1 = bookingFacade.bookFlight(
                "Amina Suleimenova", "KZ204", "14B", 320.00, 2, 24.5, "EUR", testCard);
        System.out.println("Result 1: " + ticket1);
        System.out.println();

        System.out.println("=== TEST 2: Blocked crew seat ===");
        String ticket2 = bookingFacade.bookFlight(
                "Daniyar Kassymov", "KZ204", "13A", 320.00, 1, 15.0, "USD", testCard);
        System.out.println("Result 2: " + ticket2);
        System.out.println();

        System.out.println("=== TEST 3: Failed payment and seat rollback ===");
        String ticket3 = bookingFacade.bookFlight(
                "Timur Bekov", "KZ204", "08C", 320.00, 1, 18.0, "KZT", "1234");
        System.out.println("Result 3: " + ticket3);
        System.out.println();

        System.out.println("=== TEST 4: SILVER baggage waiver and promo ===");
        String ticket4 = bookingFacade.bookFlight(
                "Madina Akhmetova", "KZ204", "15D", 320.00, 3, 30.0, "USD", testCard, "SILVER");
        System.out.println("Result 4: " + ticket4);
    }
}

class FlightBookingFacade {
    private final SeatInventorySystem seatSystem;
    private final BaggagePricingEngine baggageEngine;
    private final CurrencyConverter currencyConverter;
    private PaymentProcessor paymentProcessor;
    private final TicketIssuer ticketIssuer;

    public FlightBookingFacade() {
        seatSystem = new SeatInventorySystem();
        baggageEngine = new BaggagePricingEngine();
        currencyConverter = new CurrencyConverter();
        paymentProcessor = new PaymentProcessor();
        ticketIssuer = new TicketIssuer();
    }

    public String bookFlight(String passengerName, String flightCode, String seatNumber,
                             double baseFareUSD, int bagCount, double bagWeightKg,
                             String currency, String cardNumber) {
        return bookFlight(passengerName, flightCode, seatNumber, baseFareUSD,
                          bagCount, bagWeightKg, currency, cardNumber, "STANDARD");
    }

    public synchronized String bookFlight(String passengerName, String flightCode, String seatNumber,
                                          double baseFareUSD, int bagCount, double bagWeightKg,
                                          String currency, String cardNumber, String loyaltyTier) {
        boolean silverTier = "SILVER".equalsIgnoreCase(loyaltyTier);
        boolean freeBaggage = silverTier || "GOLD".equalsIgnoreCase(loyaltyTier);
        String lockToken = null;
        boolean promoPending = false;
        try {
            seatSystem.connectToReservationServer();
            lockToken = seatSystem.lockSeat(flightCode, seatNumber, passengerName);

            double baggageFeeUSD = freeBaggage
                    ? 0.0
                    : baggageEngine.calculateBaggageFeeUSD(bagCount, bagWeightKg);
            double totalUSD = baseFareUSD + baggageFeeUSD;
            double convertedTotal = currencyConverter.convertFromUSD(totalUSD, currency);

            paymentProcessor.initializeGateway();
            if (silverTier) {
                paymentProcessor.applyPromoCode("SILVER-FREE-BAG");
                promoPending = true;
            }
            String receiptId = paymentProcessor.chargeCard(cardNumber, convertedTotal, currency);
            promoPending = false;
            return ticketIssuer.generateTicket(passengerName, flightCode, lockToken, receiptId);
        } catch (RuntimeException ex) {
            if (lockToken != null) {
                seatSystem.releaseSeatLock(lockToken);
            }
            if (promoPending) {
                // A declined charge leaves the promo set on the processor.
                paymentProcessor = new PaymentProcessor();
            }
            String failure = "BOOKING_FAILED: " + ex.getMessage();
            System.out.println("[FlightBookingFacade] " + failure);
            return failure;
        } finally {
            seatSystem.disconnect();
        }
    }
}

// Subsystem 1: Manages aircraft seat availability and temporary locks.
class SeatInventorySystem {
    private boolean connected = false;

    // Must be called before checking or locking any seat.
    public void connectToReservationServer() {
        this.connected = true;
        System.out.println("[SeatInventory] Connected to central seat server.");
    }

    // Verifies whether a seat code (e.g., "12A") is valid and open.
    public boolean isSeatAvailable(String flightCode, String seatNumber) {
        if (!connected) {
            throw new IllegalStateException("SeatInventorySystem is not connected.");
        }
        if (seatNumber == null || seatNumber.trim().isEmpty()) {
            return false;
        }
        // Simulated rule: Row 13 seats are blocked for crew use.
        return !seatNumber.toUpperCase().startsWith("13");
    }

    // Locks the seat for the passenger and returns a lock token.
    public String lockSeat(String flightCode, String seatNumber, String passengerName) {
        if (!isSeatAvailable(flightCode, seatNumber)) {
            throw new IllegalArgumentException("Seat " + seatNumber + " cannot be locked.");
        }
        String lockToken = "LOCK-" + flightCode.toUpperCase() + "-" + seatNumber.toUpperCase();
        System.out.println("[SeatInventory] Locked seat " + seatNumber + " for " + passengerName +
                " (" + lockToken + ")");
        return lockToken;
    }

    // Releases a previously acquired lock if booking fails later in the pipeline.
    public void releaseSeatLock(String lockToken) {
        if (connected && lockToken != null) {
            System.out.println("[SeatInventory] Released lock: " + lockToken);
        }
    }

    public void disconnect() {
        this.connected = false;
        System.out.println("[SeatInventory] Disconnected from seat server.");
    }
}

// Subsystem 2: Calculates checked baggage surcharges in USD.
class BaggagePricingEngine {

    // Base allowance is 1 bag (up to 20 kg). Extra bags or heavy bags incur fees.
    public double calculateBaggageFeeUSD(int bagCount, double totalWeightKg) {
        if (bagCount < 0 || totalWeightKg < 0) {
            throw new IllegalArgumentException("Bag count and weight cannot be negative.");
        }
        if (bagCount == 0) {
            System.out.println("[BaggageEngine] No checked bags. Fee: $0.00");
            return 0.0;
        }

        double fee = 0.0;
        if (bagCount > 1) {
            fee += (bagCount - 1) * 35.0; // $35 per additional bag
        }
        if (totalWeightKg > 20.0) {
            double extraKg = totalWeightKg - 20.0;
            fee += extraKg * 12.0; // $12 per overweight kg
        }

        System.out.printf("[BaggageEngine] Calculated baggage fee: $%.2f%n", fee);
        return fee;
    }
}

// Subsystem 3: Converts USD amounts into the passenger's billing currency.
class CurrencyConverter {

    public double convertFromUSD(double amountUSD, String targetCurrency) {
        if (targetCurrency == null) {
            throw new IllegalArgumentException("Target currency cannot be null.");
        }
        String code = targetCurrency.trim().toUpperCase();
        double rate;

        switch (code) {
            case "USD":
                rate = 1.0;
                break;
            case "EUR":
                rate = 0.92;
                break;
            case "GBP":
                rate = 0.79;
                break;
            case "KZT":
                rate = 485.0;
                break;
            default:
                throw new IllegalArgumentException("Unsupported currency code: " + code);
        }

        double converted = amountUSD * rate;
        System.out.printf("[CurrencyConverter] %.2f USD -> %.2f %s%n", amountUSD, converted, code);
        return converted;
    }
}

// Subsystem 4: Validates discount codes and charges the customer's card.
class PaymentProcessor {
    private boolean gatewayReady = false;
    private String activePromoCode = null;

    public void initializeGateway() {
        this.gatewayReady = true;
        System.out.println("[PaymentProcessor] Payment gateway initialized.");
    }

    // Optional promo code must be applied BEFORE calling chargeCard().
    public void applyPromoCode(String promoCode) {
        if (!gatewayReady) {
            throw new IllegalStateException("Initialize gateway before applying promo codes.");
        }
        this.activePromoCode = promoCode;
        System.out.println("[PaymentProcessor] Applied promo code: " + promoCode);
    }

    // Charges the card and returns a transaction receipt ID.
    public String chargeCard(String cardNumber, double amount, String currency) {
        if (!gatewayReady) {
            throw new IllegalStateException("Payment gateway is not initialized.");
        }
        if (cardNumber == null || cardNumber.replaceAll("\s+", "").length() < 12) {
            throw new IllegalArgumentException("Invalid card number provided.");
        }

        double finalAmount = amount;
        if ("SILVER-FREE-BAG".equalsIgnoreCase(activePromoCode)) {
            // Applies a 10% loyalty courtesy discount on total charge
            finalAmount = amount * 0.90;
            System.out.printf("[PaymentProcessor] Promo discount applied. New total: %.2f %s%n",
                              finalAmount, currency);
        }

        // Reset promo state after single transaction
        this.activePromoCode = null;

        String txId = "TX-" + Math.abs((cardNumber + finalAmount).hashCode());
        System.out.printf("[PaymentProcessor] Charged %.2f %s | Receipt: %s%n",
                          finalAmount, currency, txId);
        return txId;
    }
}

// Subsystem 5: Generates the final e-ticket confirmation string.
class TicketIssuer {

    public String generateTicket(String passengerName,
                                 String flightCode,
                                 String seatLockToken,
                                 String paymentReceiptId) {
        if (seatLockToken == null || paymentReceiptId == null) {
            throw new IllegalStateException("Cannot issue ticket without seat lock and payment.");
        }
        String ticketCode = "ETKT-" + flightCode.toUpperCase() + "-" +
                paymentReceiptId.substring(3);
        System.out.println("[TicketIssuer] Issued " + ticketCode + " for " + passengerName);
        return ticketCode;
    }
}
