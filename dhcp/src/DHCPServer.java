import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class DHCPServer {
    private static final int PORT = 6767;
    private final Set<String> availableIPs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, String> macToIp = new ConcurrentHashMap<>(); // MAC -> IP
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public DHCPServer() {
        for (int i = 1; i <= 100; i++) {
            availableIPs.add("192.168.0." + i);
        }
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("DHCP-сервер запущен на порту " + PORT);

        while (true) {
            Socket socket = serverSocket.accept();
            executor.submit(() -> handleClient(socket));
        }
    }

    private void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String msg;
            while ((msg = in.readLine()) != null) {

                String[] parts = msg.split(" ");
                String command = parts[0];
                String mac = parts.length > 1 ? parts[1] : null;
                String ip = parts.length > 2 ? parts[2] : null;

                switch (command) {
                    case "DISCOVER":;
                        handleDiscover(mac, out);
                        break;
                    case "REQUEST":
                        handleRequest(mac, ip, out);
                        break;
                    case "RELEASE":
                        handleRelease(mac);
                        break;
                    default:
                        out.println("ERROR Unknown command");
                }
            }
        } catch (IOException e) {
            System.err.println("Ошибка обработки клиента: " + e.getMessage());
        }
    }

    private void handleDiscover(String mac, PrintWriter out) {
        System.out.println("Обработка DISCOVER для MAC: " + mac);

        if (mac == null) {
            out.println("ERROR Missing MAC");
            return;
        }

        if (macToIp.containsKey(mac)) {
            System.out.println("Известный клиент: mac->ip; OFFER же используемого IP");
            out.println("OFFER " + macToIp.get(mac));
        } else if (!availableIPs.isEmpty()) {
            String ip = availableIPs.iterator().next();
            System.out.println("Отправка OFFER с предложением IP: " + ip);
            out.println("OFFER " + ip);
        } else {
            System.out.println("Нет свободных айпи");
            out.println("NO_AVAILABLE_IP");
        }
    }

    private void handleRequest(String mac, String ip, PrintWriter out) {
        System.out.println("Обработка REQUEST от MAC: " + mac + " на IP: " + ip);

        if (mac == null || ip == null) {
            out.println("NAK");
            return;
        }

        if (availableIPs.contains(ip)) {
            macToIp.put(mac, ip);
            availableIPs.remove(ip);
            out.println("ACK " + ip);
            System.out.println("Выдан IP " + ip + " для MAC " + mac);
        } else if (ip.equals(macToIp.get(mac))) {
            out.println("ACK " + ip);
        } else {
            out.println("NAK");
        }
    }

    private void handleRelease(String mac) {
        System.out.println("Обработка RELEASE для MAC: " + mac);

        if (mac == null) return;

        String ip = macToIp.remove(mac);
        if (ip != null) {
            availableIPs.add(ip);
            System.out.println("IP " + ip + " освобождён от MAC " + mac);
        }
    }

    public static void main(String[] args) {
        try {
            new DHCPServer().start();
        } catch (IOException e) {
            System.err.println("Ошибка запуска DHCP-сервера: " + e.getMessage());
        }
    }
}
