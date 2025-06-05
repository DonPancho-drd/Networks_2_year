import java.io.*;
import java.net.*;
import java.util.*;

public class Client {
    private final String ipAddress;
    private final String macAddress;
    private final int listenPort;
    private final String routerHost = "localhost";
    private final int routerPort;
    private ServerSocket serverSocket;

    public Client(String routerPortStr, String mac, String ip) throws IOException {
        this.routerPort = Integer.parseInt(routerPortStr);
        this.macAddress = mac;
        this.ipAddress = ip;
        this.listenPort = 6000 + new Random().nextInt(1000);

        registerWithRouter();
        startListeningThread();
    }

    private void registerWithRouter() throws IOException {
        try (Socket socket = new Socket(routerHost, routerPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println("REGISTER " + ipAddress + " " + macAddress + " " + listenPort);
        }
    }

    private void startListeningThread() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(listenPort);
                System.out.println("Listening for incoming connections on port " + listenPort);

                while (!serverSocket.isClosed()) {
                    try (Socket socket = serverSocket.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                        String msg = in.readLine();
                        handleIncomingMessage(msg);
                    }
                }

            } catch (IOException e) {
                System.err.println("Listening thread stopped: " + e.getMessage());
            }
        }).start();
    }

    private void handleIncomingMessage(String msg) {
        if (msg == null) return;

        if (msg.startsWith("PING_FROM")) {
            String fromIP = msg.split(" ")[1];
            System.out.println("Received PING from " + fromIP);
            sendPongBack(fromIP);
        } else if (msg.startsWith("PONG_FROM")) {
            String fromIP = msg.split(" ")[1];
            System.out.println("Received PONG from " + fromIP);
        } else {
            System.out.println("Unknown message: " + msg);
        }
    }

    private void sendPing(String targetIP) {
        try (Socket socket = new Socket(routerHost, routerPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println("PING " + ipAddress + " " + targetIP);
        } catch (IOException e) {
            System.err.println("Failed to send PING: " + e.getMessage());
        }
    }

    private void sendPongBack(String targetIP) {
        try (Socket socket = new Socket("localhost", routerPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println("PONG " + ipAddress + " " + targetIP);
        } catch (IOException e) {
            System.err.println("Failed to send PONG to " + targetIP + ": " + e.getMessage());
        }
    }

    private void disconnect() {
        try (Socket socket = new Socket(routerHost, routerPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println("DISCONNECT " + ipAddress);
            System.out.println("Disconnecting client...");

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

        } catch (IOException e) {
            System.err.println("Disconnect error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Usage: java ClientComputer <RouterPort> <MAC> <IP>");
            return;
        }

        try {
            Client client = new Client(args[0], args[1], args[2]);
            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine().trim().toLowerCase();

                if (input.equals("exit")) {
                    client.disconnect();
                    break;
                } else if (input.startsWith("ping ")) {
                    String[] parts = input.split(" ");
                    if (parts.length == 2) {
                        client.sendPing(parts[1]);
                    } else {
                        System.out.println("Usage: ping <IP>");
                    }
                } else {
                    System.out.println("Unknown command. Use 'ping <IP>' or 'exit'.");
                }
            }

            System.exit(0);

        } catch (IOException e) {
            System.err.println("Client initialization error: " + e.getMessage());
        }
    }

}