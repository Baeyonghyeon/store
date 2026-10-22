import java.net.*;
import java.net.http.*;
import java.time.Duration;

public class Healthcheck {
    public static void main(String[] args) throws Exception {
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(args[0]))
                                        .timeout(Duration.ofSeconds(3))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) System.exit(1);
    }
}
