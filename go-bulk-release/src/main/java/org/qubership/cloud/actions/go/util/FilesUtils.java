package org.qubership.cloud.actions.go.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class FilesUtils {
    //todo vlla get rid of static
    public static List<Path> findAll(Path repositoryDir, String filename) {
        List<Path> result = new ArrayList<>();
        try {
            //todo vlla simplify?
            Files.walkFileTree(repositoryDir, new FileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

                    List<String> pathList = StreamSupport.stream(file.spliterator(), false)
                            .map(Path::toString)
                            .toList();
                    if (pathList.contains(filename) && !pathList.contains("target")) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }


}
