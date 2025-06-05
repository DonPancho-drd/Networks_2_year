import java.io.*;
import java.net.*;

public class ExternalNode {
    private final String ipAddress = "198.51.100.10";
    private final int listenPort = 7000;
    private final String routerHost = "localhost";
    private final int routerPort;

    public ExternalNode(String routerPortStr) throws IOException {
        this.routerPort = Integer.parseInt(routerPortStr);
        startListeningThread();
    }

    private void startListeningThread() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
                System.out.println("External node listening on port " + listenPort + " with IP " + ipAddress);

                while (true) {
                    try (Socket socket = serverSocket.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                        String msg = in.readLine();
                        handleMessage(msg);
                    }
                }

            } catch (IOException e) {
                System.err.println("External node listening stopped: " + e.getMessage());
            }
        }).start();
    }

    private void handleMessage(String msg) {
        if (msg == null) return;

        if (msg.startsWith("PING_FROM")) {
            String fromIpPort = msg.split(" ")[1];
            System.out.println("External node received PING from " + fromIpPort);
            sendPongBack(fromIpPort);
        } else {
            System.out.println("External node received unknown message: " + msg);
        }
    }

    private void sendPongBack(String targetIpPort) {
        try (Socket socket = new Socket(routerHost, routerPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println("PONG " + ipAddress + " " + targetIpPort);
        } catch (IOException e) {
            System.err.println("External node failed to send PONG to " + targetIpPort + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java ExternalNode <RouterPort>");
            return;
        }

        try {
            new ExternalNode(args[0]);
        } catch (IOException e) {
            System.err.println("External node initialization error: " + e.getMessage());
        }
    }
}