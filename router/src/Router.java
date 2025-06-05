import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Router {
    private final int listenPort;
    private final Map<String, ClientInfo> ipTable = new ConcurrentHashMap<>();
    private final Map<String, String> arpTable = new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();


    public Router(int port) {
        this.listenPort = port;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(listenPort);
        System.out.println("Router запущен на порту " + listenPort);

        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                String command = scanner.nextLine();
                if ("STOP".equalsIgnoreCase(command.trim())) {
                    System.out.println("Остановка роутера по команде STOP...");
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        System.err.println("Ошибка при закрытии сокета: " + e.getMessage());
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
            System.out.println("Сервер остановлен.");
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
                default -> System.err.println("Неизвестная команда: " + command);
            }

        } catch (IOException e) {
            System.err.println("Ошибка клиента: " + e.getMessage());
        }
    }

    private void handleRegister(String msg) {
        String[] parts = msg.split(" ");
        String ip = parts[1];
        String mac = parts[2];
        int port = Integer.parseInt(parts[3]);

        ClientInfo existing = ipTable.get(ip);
        if (existing != null && !existing.mac.equals(mac)) {
            System.err.println("Конфликт IP-адреса: " + ip + " уже зарегистрирован другим MAC.");
            return;
        }

        if (arpTable.containsValue(mac) && !ip.equals(getIpByMac(mac))) {
            System.err.println("Конфликт MAC-адреса: " + mac + " уже используется другим IP.");
            return;
        }

        ClientInfo info = new ClientInfo(ip, mac, port);
        ipTable.put(ip, info);
        arpTable.put(ip, mac);

        System.out.println("Зарегистрирован: IP=" + ip + " MAC=" + mac + " Port=" + port);
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
            System.out.println("Отключён: IP=" + ip);
        }
    }

    private void handlePing(String msg) {
        String[] parts = msg.split(" ");
        String fromIP = parts[1];
        String toIP = parts[2];

        ClientInfo from = ipTable.get(fromIP);
        ClientInfo to = ipTable.get(toIP);

        if (from == null || to == null) {
            System.err.println("Один из узлов не найден в таблице IP.");
            return;
        }

        threadPool.submit(() -> sendMessage(to.port, "PING_FROM " + fromIP));
    }

    private void handlePong(String msg) {
        String[] parts = msg.split(" ");
        String fromIP = parts[1];
        String toIP = parts[2];

        ClientInfo from = ipTable.get(fromIP);
        ClientInfo to = ipTable.get(toIP);

        if (from == null || to == null) {
            System.err.println("Один из узлов не найден в таблице IP.");
            return;
        }

        threadPool.submit(() -> sendMessage(to.port, "PONG_FROM " + fromIP));
    }

    private void sendMessage(int port, String msg) {
        try (Socket socket = new Socket("localhost", port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(msg);
        } catch (IOException e) {
            System.err.println("Ошибка отправки на порт " + port + ": " + e.getMessage());
        }
    }

    private void shutdown() {
        threadPool.shutdown();

        System.out.println("Роутер корректно завершил работу.");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Использование: java Router <Port>");
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
