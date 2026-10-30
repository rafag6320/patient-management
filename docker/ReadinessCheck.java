import java.net.HttpURLConnection;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Probe sin curl, shell ni dependencias adicionales en la imagen JRE. */
public final class ReadinessCheck {
    public static void main(String[] args) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(args[0]).toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Servicio HTTP no disponible");
            }
        } finally {
            connection.disconnect();
        }
        if (args.length == 3) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(args[1], Integer.parseInt(args[2])), 2000);
            }
        }
    }
}
