import io.github.captheorem.spectral.KeepAlive;
import io.github.captheorem.spectral.Lease;
import io.github.captheorem.spectral.Provider;
import io.github.captheorem.spectral.Registration;
import io.github.captheorem.spectral.ServiceNotFoundException;
import io.github.captheorem.spectral.SpectralClient;
import java.time.Duration;

public class Quickstart {
    public static void main(String[] args) throws InterruptedException {
        SpectralClient client = SpectralClient.create();

        Lease lease = client.register(
                new Registration("payments", "payments-1", "http://127.0.0.1:9000", Duration.ofSeconds(10)));
        System.out.println("registered with " + client.addr() + ", lease expires at " + lease.expiresAt());

        try (KeepAlive keeper = client.keepAlive(lease)) {
            try {
                Provider inventory = client.lookup("inventory");
                System.out.println("inventory is at " + inventory.endpoint());
            } catch (ServiceNotFoundException e) {
                System.out.println("no inventory instance registered yet");
            }

            while (keeper.isRunning()) {
                Thread.sleep(1000);
            }
            keeper.failure().ifPresent(e -> System.out.println("keepalive stopped: " + e.getMessage()));
        }
    }
}
