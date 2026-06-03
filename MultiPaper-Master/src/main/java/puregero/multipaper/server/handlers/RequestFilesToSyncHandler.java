package puregero.multipaper.server.handlers;

import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestFilesToSyncMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.FilesToSyncMessage;
import puregero.multipaper.server.ServerConnection;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class RequestFilesToSyncHandler {
    public static void handle(ServerConnection connection, RequestFilesToSyncMessage message) {
        // Recursive directory traversal can be slow on large worlds — run it
        // off the Netty event loop so a big synced-server-files/ tree doesn't
        // stall unrelated master traffic.
        CompletableFuture.runAsync(() -> {
            List<File> files = new ArrayList<>();
            appendFilesRecursively(new File("synced-server-files"), files);

            FilesToSyncMessage.FileToSync[] filesToSync = new FilesToSyncMessage.FileToSync[files.size()];
            for (int i = 0; i < filesToSync.length; i++) {
                File file = files.get(i);
                filesToSync[i] = new FilesToSyncMessage.FileToSync(
                        file.getPath().replace("synced-server-files/", "").replace("synced-server-files\\", ""),
                        file.lastModified()
                );
            }

            connection.sendReply(new FilesToSyncMessage(filesToSync), message);
        });
    }

    private static void appendFilesRecursively(File file, List<File> files) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) {
                appendFilesRecursively(child, files);
            }
        } else if (file.isFile()) {
            files.add(file);
        }
    }
}
