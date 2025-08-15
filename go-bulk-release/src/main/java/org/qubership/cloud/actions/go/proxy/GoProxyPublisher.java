package org.qubership.cloud.actions.go.proxy;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.util.CommandRunner;

import java.io.File;
import java.io.OutputStream;
import java.util.Arrays;

@Slf4j
public class GoProxyPublisher {

    public static void publishToLocalGoProxy(File srcRepo,
                                             String version,
                                             String proxyRoot,
                                             OutputStream out) {
        String[] command = {"gopack", "-out", proxyRoot, "-src", srcRepo.getAbsolutePath(), "-version", version};
        log.debug("VLLA gopack command: {}", Arrays.toString(command));
        CommandRunner.runCommand(null, out, command);
    }
}

