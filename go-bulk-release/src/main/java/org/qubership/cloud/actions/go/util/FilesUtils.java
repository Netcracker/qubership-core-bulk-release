package org.qubership.cloud.actions.go.util;

import org.qubership.cloud.actions.go.model.UnexpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

public class FilesUtils {
    public static List<Path> findAll(Path repositoryDir, String filename) {
        List<Path> result;
        try (var stream = Files.walk(repositoryDir)) {
            result = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(filename))
                    .filter(p -> StreamSupport.stream(p.spliterator(), false)
                            .noneMatch(n -> n.toString().equals("target")))
                    .toList();
        } catch (IOException e) {
            throw new UnexpectedException(e);
        }
        return result;
    }

    private FilesUtils() {}
}
