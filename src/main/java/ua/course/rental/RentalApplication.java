package ua.course.rental;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;

/** Starts the real rental API and serves the bundled SUT C UI. */
public final class RentalApplication {
    private RentalApplication() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("API_PORT", "8080"));
        RentalApiServer api = new RentalApiServer(
                () -> {
                    try {
                        return Database.connect();
                    } catch (SQLException exception) {
                        throw new IllegalStateException(exception);
                    }
                },
                (equipment, quantity, days) -> true,
                port
        );
        Runtime.getRuntime().addShutdownHook(new Thread(api::close));
        api.start();
        System.out.println("Rental API and UI listening on http://127.0.0.1:" + port);
        new CountDownLatch(1).await();
    }
}
