import java.io.*;
import java.net.*;
import java.util.*;

public class ExternalNode {
    private final int listenPort;

    public ExternalNode(int port) {
        this.listenPort = port;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(listenPort);
        System.out.println("Внешний узел запущен на порту " + listenPort);

        while (true) {
            try (Socket socket = serverSocket.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                String msg = in.readLine();
                if (msg != null && msg.startsWith("PING_FROM")) {
                    String from = msg.split(" ")[1];
                    System.out.println("Получен PING от " + from);

                    // Отправляем PONG обратно
                    out.println("PONG_FROM external.node");
                    System.out.println("Отправлен PONG на " + from);
                }
            } catch (IOException e) {
                System.err.println("Ошибка во внешнем узле: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Использование: java ExternalNode <Port>");
            return;
        }
        new ExternalNode(Integer.parseInt(args[0])).start();
    }
}