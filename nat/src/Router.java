import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Router {
    private final int listenPort;

    private final Map<String, ClientInfo> ipTable = new ConcurrentHashMap<>();
    private final Map<String, String> arpTable = new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private final String publicIp = "203.0.113.1"; //
    private static final String EXTERNAL_NODE_IP = "198.51.100.10"; //
    private final Map<String, String> natTable = new ConcurrentHashMap<>(); // sourceIP:sourcePort -> publicIP:port


    public Router(int port) {
        this.listenPort = port;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(listenPort);
        System.out.println("Router started on port " + listenPort + " with public IP " + publicIp);

        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                String command = scanner.nextLine();
                if ("STOP".equalsIgnoreCase(command.trim())) {
                    System.out.println("Stopping router on STOP command...");
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        System.err.println("Error closing socket: " + e.getMessage());
                    }
                    break;
                }
            }
        }).start();

        try {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.out.println("Server stopped.");
        } finally {
            shutdown();
        }
    }

    private void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String message = in.readLine();
            if (message == null) return;

            String command = message.split(" ")[0];

            switch (command) {
                case "REGISTER" -> handleRegister(message);
                case "DISCONNECT" -> handleDisconnect(message);
                case "PING" -> handlePing(message);
                case "PONG" -> handlePong(message);
                default -> System.err.println("Unknown command: " + command);
            }

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private void handleRegister(String msg) {
        String[] parts = msg.split(" ");
        String ip = parts[1];
        String mac = parts[2];
        int port = Integer.parseInt(parts[3]);

        ClientInfo existing = ipTable.get(ip);
        if (existing != null && !existing.mac.equals(mac)) {
            System.err.println("IP conflict: " + ip + " already registered with different MAC.");
            return;
        }

        if (arpTable.containsValue(mac) && !ip.equals(getIpByMac(mac))) {
            System.err.println("MAC conflict: " + mac + " already used by another IP.");
            return;
        }

        ClientInfo info = new ClientInfo(ip, mac, port);
        ipTable.put(ip, info);
        arpTable.put(ip, mac);

        System.out.println("Registered: IP=" + ip + " MAC=" + mac + " Port=" + port);
    }

    private String getIpByMac(String mac) {
        for (Map.Entry<String, String> entry : arpTable.entrySet()) {
            if (entry.getValue().equals(mac)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void handleDisconnect(String msg) {
        String[] parts = msg.split(" ");
        String ip = parts[1];
        ClientInfo removed = ipTable.remove(ip);
        if (removed != null) {
            arpTable.remove(ip);
            natTable.entrySet().removeIf(entry -> entry.getKey().startsWith(ip + ":"));
            System.out.println("Disconnected: IP=" + ip);
        }
    }

    private void handlePing(String msg) {
        String[] parts = msg.split(" ");
        String fromIP = parts[1];
        String toIP = parts[2];

        ClientInfo from = ipTable.get(fromIP);
        if (from == null) {
            System.err.println("Source node " + fromIP + " not found in IP table.");
            return;
        }

        String sourceIp;
        String message;

        if (isPrivateIp(fromIP) && toIP.equals(EXTERNAL_NODE_IP)) {
            int portForClient = 8000 + new Random().nextInt(1000);
            sourceIp = publicIp;
            message = "PING_FROM " + sourceIp+":" + portForClient;
            String natKey = fromIP + ":" + from.port;
            String natValue = publicIp + ":" + portForClient;
            natTable.put(natKey, natValue);
            System.out.println("NAT mapping added: " + natKey + " -> " + natValue);
        } else{
            sourceIp = fromIP;
            message = "PING_FROM " + sourceIp;

        }

        if (toIP.equals(EXTERNAL_NODE_IP)) {
            threadPool.submit(() -> sendMessage(7000, message));
        } else {
            ClientInfo to = ipTable.get(toIP);
            if (to != null) {
                threadPool.submit(() -> sendMessage(to.port, message));
            } else {
                System.err.println("Destination node " + toIP + " not found in IP table.");
            }
        }
    }

    private void handlePong(String msg) {
        String[] parts = msg.split(" ");
        String fromIP = parts[1];
        String[] toIpPort = parts[2].split(":");

        if (toIpPort[0].equals(publicIp)) {
            String natKey = null;
            for (Map.Entry<String, String> entry : natTable.entrySet()) {
                if (entry.getValue().startsWith(toIpPort[0] + ":") && entry.getValue().endsWith(":" + toIpPort[1])) {
                    natKey = entry.getKey();
                    break;
                }
            }

            if (natKey != null) {
                String[] natParts = natKey.split(":");
                String destIp = natParts[0];
                int destPort = Integer.parseInt(natParts[1]);
                threadPool.submit(() -> sendMessage(destPort, "PONG_FROM " + fromIP));
                natTable.remove(natKey);
            } else {
                System.err.println("No NAT mapping found for PONG to " + toIpPort[0] + " from " + fromIP);
            }
        } else {
            // local-to-local PONG
            String toIP = toIpPort[0];
            ClientInfo to = ipTable.get(toIP);
            if (to != null) {
                threadPool.submit(() -> sendMessage(to.port, "PONG_FROM " + fromIP));
            } else {
                System.err.println("Destination node " + toIP + " not found in IP table.");
            }
        }
    }

    private void sendMessage(int port, String msg) {
        try (Socket socket = new Socket("localhost", port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            String ipForPort = getIpForPort(port);
            System.out.println("Sending to IP=" + ipForPort + " Port=" + port + ": " + msg);
            out.println(msg);
        } catch (IOException e) {
            System.err.println("Error sending to port " + port + ": " + e.getMessage());
        }
    }

    private String getIpForPort(int port) {
        if (port == 7000) {
            return EXTERNAL_NODE_IP;
        }
        for (Map.Entry<String, ClientInfo> entry : ipTable.entrySet()) {
            if (entry.getValue().port == port) {
                return entry.getKey();
            }
        }
        return "unknown";
    }

    private boolean isPrivateIp(String ip) {
        return ip.startsWith("192.");
    }

    private void shutdown() {
        threadPool.shutdown();
        System.out.println("Router shut down");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Usage: java Router <Port>");
            return;
        }
        new Router(Integer.parseInt(args[0])).start();
    }

    static class ClientInfo {
        String ip, mac;
        int port;

        ClientInfo(String ip, String mac, int port) {
            this.ip = ip;
            this.mac = mac;
            this.port = port;
        }
    }
}