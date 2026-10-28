package com.umutvarol.restaurant.printing;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.springframework.stereotype.Component;

/** Sends bytes to a raw (JetDirect, port 9100) printer: connect, write, close, all with timeouts. */
@Component
class PrinterClient {

    private final PrintingProperties properties;

    PrinterClient(PrintingProperties properties) {
        this.properties = properties;
    }

    void send(InetAddress address, int port, byte[] bytes) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), (int) properties.connectTimeout().toMillis());
            socket.setSoTimeout((int) properties.writeTimeout().toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(bytes);
            out.flush();
            socket.shutdownOutput();
        }
    }
}
