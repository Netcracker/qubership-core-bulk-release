package org.qubership.cloud.actions.go.proxy;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.RepositoryInfo;
import org.qubership.cloud.actions.go.util.CommandRunner;

import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.Arrays;

@Slf4j
public class GoProxyPublisher {

    public static void publishToLocalGoProxy(RepositoryInfo srcRepo,
                                             String version,
                                             String proxyRoot,
                                             OutputStream out) {
        String[] command = {"./gopack", "-out", proxyRoot, "-src", srcRepo.getRepositoryDirFile().getAbsolutePath(), "-version", version};
        log.debug("VLLA gopack command: {}", Arrays.toString(command));
        CommandRunner.runCommand(null, out, "pwd");
        CommandRunner.runCommand(null, out, "ls", "-la");
        CommandRunner.runCommand(Paths.get(srcRepo.getBaseDir()).toFile(), out, "pwd");
        CommandRunner.runCommand(Paths.get(srcRepo.getBaseDir()).toFile(), out, "ls", "-la");
        CommandRunner.runCommand(null, out, command);
    }
}

