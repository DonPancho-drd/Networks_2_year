package client;

import util.DnsException;
import java.io.*;
import java.net.*;
import java.util.Scanner;

public class DnsClient {
    private static final int DNS_PORT = 5354;
    private static final int TIMEOUT_MS = 5000;
    private static final String BROADCAST_ADDRESS = "255.255.255.255";
    private static String dnsServerAddress = BROADCAST_ADDRESS;
    private static String domainName;
    private static String ipAddress;
    private static int httpPort;

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Usage: java DnsClient <domain> <ip> <http-port>");
            System.exit(1);
        }

        try {
            domainName = args[0];
            ipAddress = args[1];
            httpPort = Integer.parseInt(args[2]);
            if (httpPort < 1 || httpPort > 65535) {
                throw new NumberFormatException("Port out of range");
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("DNS Client started");
        discoverDnsServer();
        registerWithDns();
        new Thread(DnsClient::startHttpServer).start();
        runCommandLoop();
    }

    private static void runCommandLoop() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Command > ");
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("exit")) {
                System.exit(0);
            }
            if (line.startsWith("get html ")) {
                String domain = line.substring(9).trim();
                fetchPage(domain);
            } else {
                System.out.println("Unknown command. Use 'get html <domain>' or 'exit'.");
            }
        }
    }

    private static void discoverDnsServer() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(TIMEOUT_MS);

            DatagramPacket packet = new DatagramPacket(
                    "DISCOVER_DNS".getBytes("UTF-8"),
                    "DISCOVER_DNS".length(),
                    InetAddress.getByName(BROADCAST_ADDRESS),
                    DNS_PORT
            );
            socket.send(packet);

            byte[] buffer = new byte[256];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);

            dnsServerAddress = new String(responsePacket.getData(), 0, responsePacket.getLength(), "UTF-8").trim();
            System.out.println("DNS Server found at: " + dnsServerAddress);
        } catch (SocketTimeoutException e) {
            System.out.println("DNS discovery timed out, using default broadcast address");
        } catch (Exception e) {
            System.err.println("DNS discovery failed: " + e.getMessage());
        }
    }

    private static void registerWithDns() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            String message = String.format("REGISTER %s %s %d", domainName, ipAddress, httpPort);
            DatagramPacket packet = new DatagramPacket(
                    message.getBytes("UTF-8"),
                    message.length(),
                    InetAddress.getByName(dnsServerAddress),
                    DNS_PORT
            );
            socket.send(packet);

            byte[] buffer = new byte[256];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);
            String response = new String(responsePacket.getData(), 0, responsePacket.getLength(), "UTF-8").trim();
            System.out.println("Registration response: " + response);
            if (response.startsWith("ERROR")) {
                System.err.println("Registration failed: " + response);
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Failed to register with DNS: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String[] resolveDomain(String domain) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            String message = "RESOLVE " + domain;
            DatagramPacket packet = new DatagramPacket(
                    message.getBytes("UTF-8"),
                    message.length(),
                    InetAddress.getByName(dnsServerAddress),
                    DNS_PORT
            );
            socket.send(packet);

            byte[] buffer = new byte[256];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);

            String response = new String(responsePacket.getData(), 0, responsePacket.getLength(), "UTF-8").trim();
            if (response.startsWith("ERROR")) {
                return null;
            }
            String[] parts = response.split(":");
            if (parts.length != 2 || !isValidPort(parts[1])) {
                throw new DnsException("ERROR Invalid IP:port format");
            }
            return parts;
        } catch (Exception e) {
            System.err.println("DNS resolve failed: " + e.getMessage());
            return null;
        }
    }

    private static void fetchPage(String domain) {
        String[] ipPort = resolveDomain(domain);
        if (ipPort == null) {
            System.out.println("Could not resolve domain: " + domain);
            return;
        }
        String resolvedIp = ipPort[0];
        int resolvedPort = Integer.parseInt(ipPort[1]);

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            socket.connect(new InetSocketAddress(resolvedIp, resolvedPort), TIMEOUT_MS);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println("GET / HTTP/1.1");
            out.println("Host: " + domainName);
            out.println();

            System.out.println("Response from " + domain + " (" + resolvedIp + ":" + resolvedPort + "):");
            System.out.println("----------------------------------------");

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
            System.out.println("----------------------------------------");
        } catch (Exception e) {
            System.out.println("Failed to fetch page from " + domain + ": " + e.getMessage());
        }
    }

    private static void startHttpServer() {
        try (ServerSocket serverSocket = new ServerSocket(httpPort)) {
            System.out.println("HTTP Server running on port " + httpPort);
            while (true) {
                try (Socket client = serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                     PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {System.out.println(line);}

                    out.println("HTTP/1.1 200 OK");
                    out.println("Content-Type: text/html");
                    out.println();
                    out.println("<html><body><h1>Welcome to " + domainName + "</h1>");
                    out.println("<p>This is " + domainName + "'s unique page</p></body></html>");
                } catch (Exception e) {
                    System.err.println("Failed to handle HTTP client: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("HTTP server error: " + e.getMessage());
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