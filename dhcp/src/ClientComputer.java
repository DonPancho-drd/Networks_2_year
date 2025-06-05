import java.io.*;
import java.net.*;
import java.util.*;

public class ClientComputer {
    private String ipAddress;
    private final String macAddress;
    private final int listenPort;
    private final String routerHost = "localhost";
    private final int routerPort;
    private final int dhcpPort = 6767;
    private ServerSocket serverSocket;

    public ClientComputer(String routerPortStr, String mac) throws IOException {
        this.routerPort = Integer.parseInt(routerPortStr);
        this.macAddress = mac;
        this.listenPort = 6000 + new Random().nextInt(1000);

        obtainIpFromDhcp();
        registerWithRouter();
        startListeningThread();
    }

    private void obtainIpFromDhcp() throws IOException {
        try (Socket socket = new Socket("localhost", dhcpPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("DISCOVER " + macAddress);
            String offer = in.readLine();

            if (offer == null) {
                throw new IOException("Нет ответа от DHCP-сервера.");
            }

            if (offer.equals("NO_AVAILABLE_IP")) {
                throw new IOException("DHCP-сервер не имеет доступных IP.");
            }

            if (!offer.startsWith("OFFER")) {
                throw new IOException("Некорректный OFFER: " + offer);
            }

            ipAddress = offer.split(" ")[1];
            System.out.println("DHCP предложил IP: " + ipAddress);

            out.println("REQUEST " + macAddress + " " + ipAddress);
            String ack = in.readLine();

            if (ack == null || !ack.startsWith("ACK") || !ack.contains(ipAddress)) {
                throw new IOException("DHCP не подтвердил IP-адрес. Ответ: " + ack);
            }

            System.out.println("DHCP подтвердил IP: " + ipAddress);
        }
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
                System.out.println("Клиент слушает на порту " + listenPort);

                while (!serverSocket.isClosed()) {
                    try (Socket socket = serverSocket.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                        String msg = in.readLine();
                        handleIncomingMessage(msg);
                    }
                }
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("Ошибка слушающего потока: " + e.getMessage());
                }
            }
        }).start();
    }

    private void handleIncomingMessage(String msg) {
        if (msg == null) return;

        if (msg.startsWith("PING_FROM")) {
            String fromIP = msg.split(" ")[1];
            System.out.println("Получен PING от " + fromIP);
            sendPongBack(fromIP);
        } else if (msg.startsWith("PONG_FROM")) {
            String fromIP = msg.split(" ")[1];
            System.out.println("Получен PONG от " + fromIP);
        }
    }

    private void sendPing(String targetIP) {
        try (Socket socket = new Socket(routerHost, routerPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("PING " + ipAddress + " " + targetIP);
        } catch (IOException e) {
            System.err.println("Ошибка отправки PING: " + e.getMessage());
        }
    }

    private void sendPongBack(String targetIP) {
        try (Socket socket = new Socket(routerHost, routerPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("PONG " + ipAddress + " " + targetIP);
        } catch (IOException e) {
            System.err.println("Ошибка отправки PONG: " + e.getMessage());
        }
    }

    private void disconnect() {
        try {
            try (Socket socket = new Socket(routerHost, routerPort);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                out.println("DISCONNECT " + ipAddress);
            }

            try (Socket dhcpSocket = new Socket("localhost", dhcpPort);
                 PrintWriter dhcpOut = new PrintWriter(dhcpSocket.getOutputStream(), true)) {
                dhcpOut.println("RELEASE " + macAddress);
            }

            System.out.println("Клиент отключён.");

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

        } catch (IOException e) {
            System.err.println("Ошибка при отключении клиента: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Использование: java ClientComputer <RouterPort> <MAC>");
            return;
        }

        try {
            ClientComputer client = new ClientComputer(args[0], args[1]);
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
                        System.out.println("Использование: ping <IP>");
                    }
                } else {
                    System.out.println("Неизвестная команда. Используйте 'ping <IP>' или 'exit'.");
                }
            }

        } catch (IOException e) {
            System.err.println("Ошибка инициализации клиента: " + e.getMessage());
        }
    }
}
