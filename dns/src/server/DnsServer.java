package server;

import util.DnsException;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DnsServer {
    private static final int PORT = 5354;
    private static final int THREAD_POOL_SIZE = 10;
    private static final int MAX_PACKET_SIZE = 512;
    private static final Map<String, String> dnsTable = new HashMap<>();

    public static void main(String[] args) {
        try (DatagramSocket socket = new DatagramSocket(PORT)) {
            System.out.println("DNS Server started on port " + PORT);
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

            while (true) {
                DatagramPacket requestPacket = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
                try {
                    socket.receive(requestPacket);
                    executor.submit(() -> handleRequest(socket, requestPacket));
                } catch (SocketException e) {
                    System.err.println("Socket closed: " + e.getMessage());
                    break;
                } catch (Exception e) {
                    System.err.println("Error receiving packet: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Server startup failed: " + e.getMessage());
        } finally {
            System.out.println("DNS Server shutting down");
        }
    }

    private static void handleRequest(DatagramSocket socket, DatagramPacket requestPacket) {
        try {
            String message = new String(requestPacket.getData(), 0, requestPacket.getLength(), "UTF-8").trim();
            String response = switch (parseCommand(message)) {
                case "DISCOVER_DNS" -> InetAddress.getLocalHost().getHostAddress();
                case "REGISTER" -> handleRegister(message);
                case "RESOLVE" -> handleResolve(message);
                default -> throw new DnsException("ERROR Unknown command");
            };

            sendResponse(socket, requestPacket, response);
        } catch (DnsException e) {
            sendResponse(socket, requestPacket, e.getMessage());
        } catch (Exception e) {
            System.err.println("Error handling request: " + e.getMessage());
            sendResponse(socket, requestPacket, "ERROR Server error");
        }
    }

    private static String parseCommand(String message) {
        if (message.equals("DISCOVER_DNS")) {
            return "DISCOVER_DNS";
        }
        String[] parts = message.split(" ", 2);
        return parts.length > 0 ? parts[0] : "";
    }

    private static String handleRegister(String message) {
        String[] parts = message.split(" ");
        if (parts.length != 4) {
            throw new DnsException("ERROR Invalid REGISTER format");
        }
        String domain = parts[1];
        String ip = parts[2];
        String port = parts[3];

        if (!isValidDomain(domain) || !isValidIp(ip) || !isValidPort(port)) {
            throw new DnsException("ERROR Invalid domain, IP, or port format");
        }

        String ipPort = ip + ":" + port;
        synchronized (dnsTable) {
            if (dnsTable.containsKey(domain)) {
                throw new DnsException("ERROR Domain already registered");
            }
            dnsTable.put(domain, ipPort);
        }
        System.out.println("Registered: " + domain + " -> " + ipPort);
        return "REGISTERED " + domain;
    }

    private static String handleResolve(String message) {
        String[] parts = message.split(" ");
        if (parts.length != 2) {
            throw new DnsException("ERROR Invalid RESOLVE format");
        }
        String domain = parts[1];
        synchronized (dnsTable) {
            String ipPort = dnsTable.get(domain);
            if (ipPort == null) {
                throw new DnsException("ERROR Domain not found");
            }
            System.out.println("Resolved domain:" + domain + ", ip:" + ipPort.split(":")[0]);
            return ipPort;
        }
    }

    private static void sendResponse(DatagramSocket socket, DatagramPacket requestPacket, String response) {
        try {
            byte[] responseData = response.getBytes("UTF-8");
            DatagramPacket responsePacket = new DatagramPacket(
                    responseData,
                    responseData.length,
                    requestPacket.getAddress(),
                    requestPacket.getPort()
            );
            socket.send(responsePacket);
        } catch (Exception e) {
            System.err.println("Failed to send response: " + e.getMessage());
        }
    }

    private static boolean isValidDomain(String domain) {
        return domain != null && domain.matches("^[a-zA-Z0-9.-]+$");
    }

    private static boolean isValidIp(String ip) {
        try {
            InetAddress.getByName(ip);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isValidPort(String port) {
        try {
            int portNum = Integer.parseInt(port);
            return portNum >= 1 && portNum <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}