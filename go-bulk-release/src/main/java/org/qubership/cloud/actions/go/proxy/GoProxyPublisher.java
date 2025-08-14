package org.qubership.cloud.actions.go.proxy;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

//todo vlla AI-generated, refactor
@Slf4j
public class GoProxyPublisher {

    /**
     * Публикует версию Go-модуля в локальный файловый GOPROXY
     *
     * @param srcRepo       путь к корню git-репозитория
     * @param version       версия (допишется префикс 'v', если отсутствует), напр. v1.2.3
     * @param proxyRoot     путь к корню файлового GOPROXY
     * @param moduleDir     относительная папка модуля от корня репо (по умолчанию ".")
     */
    public static void publishToLocalGoProxy(
            Path srcRepo,
            String version,
            Path proxyRoot,
            String moduleDir
    ) {
        try {
            Objects.requireNonNull(srcRepo, "srcRepo");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(proxyRoot, "proxyRoot");

            if (moduleDir == null || moduleDir.isEmpty()) moduleDir = ".";
            if (!version.startsWith("v")) version = "v" + version;

            Path src = srcRepo.toRealPath();
            Path proxy = proxyRoot.toRealPath();

            log.debug("Params:");
            log.debug("  SRC         = {}", src);
            log.debug("  PROXY       = {}", proxy);
            log.debug("  VERSION     = {}", version);
            log.debug("  MODULE_DIR  = {}", moduleDir);

            // --- repo / rev checks
            if (!Files.isDirectory(src.resolve(".git"))) {
                throw new IllegalStateException(src + " is not a git repository");
            }
            // git rev-parse --verify --quiet REV^{commit}
            int rc = run(new ProcessBuilder("git", "-C", src.toString(),
                    "rev-parse", "--verify", "--quiet", version + "^{commit}"));
            if (rc != 0) {
                throw new IllegalArgumentException(
                        "Git revision '" + version + "' not found. Ensure the tag/branch/commit exists (tip: tag should be '" + version + "').");
            }

            // --- time for .info
            Instant infoTime = Instant.now();
            String infoRfc3339 = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(infoTime);
            log.debug("  INFO_TIME   = {}", infoRfc3339);

            // --- read module path
            Path goModPath = src.resolve(moduleDir).resolve("go.mod");
            String goModFromRev = Files.isRegularFile(goModPath)
                    ? Files.readString(goModPath, StandardCharsets.UTF_8)
                    : null;
            String modFromRev = extractModulePath(goModFromRev);

            if (modFromRev == null) {
                throw new IllegalStateException("Cannot extract module name from go.mod file");
            }

            log.debug("  MOD_FROM_REV = {}", modFromRev);

            if (modFromRev.isEmpty()) {
                throw new IllegalStateException("Cannot determine module path. go.mod not found or has no 'module' line.");
            }

            // --- escape module path for FS layout
            String escapedModule = escapeForGoProxyFS(modFromRev);
            log.debug("  ESCAPED_MODULE = {}", escapedModule);

            Path vdir = proxy.resolve(escapedModule).resolve("@v");
            Files.createDirectories(vdir);
            log.debug("  VDIR        = {}", vdir);

            // --- write .mod
            Path modOut = vdir.resolve(version + ".mod");
            log.debug("Writing .mod");
            Files.writeString(modOut, goModFromRev, StandardCharsets.UTF_8);
            log.debug("Wrote .mod -> {}", modOut);

            // --- write .info
            Path infoOut = vdir.resolve(version + ".info");
            String infoJson = String.format("{\"Version\":\"%s\",\"Time\":\"%s\"}%n", version, infoRfc3339);
            Files.writeString(infoOut, infoJson, StandardCharsets.UTF_8);
            log.debug("Wrote .info -> {}", infoOut);

            // --- create .zip from git (ONLY module dir), prefix "<module>@<version>/"
            Path tmpDir = Files.createTempDirectory("goproxy-publish-");
            try {
                String prefixPath = modFromRev + "@" + version + "/";
                log.debug("Archiving from git:");
                log.debug("  archive path = {} (relative to repo root)", moduleDir);
                log.debug("  prefix       = {}", prefixPath);

                // Create tar via git archive
                Path zipFile = vdir.resolve(version + ".zip");
                String comm = String.join(" ", "git", "-C", src.toString(),
                        "archive", "--format=zip", "--prefix=" + prefixPath, "-o", zipFile.toString(), version, "--", moduleDir);
                log.debug("VLLA comm = {}", comm);
                ProcessBuilder pb = new ProcessBuilder("git", "-C", src.toString(),
                        "archive", "--format=zip", "--prefix=" + prefixPath, "-o", zipFile.toString(), version, "--", moduleDir);
                rc = run(pb);
                if (rc != 0) {
                    throw new RuntimeException("git archive failed (rc=" + rc + ")");
                }

//            // Extract tar using system tar (consistent с оригинальным скриптом)
//            rc = run(new ProcessBuilder("tar", "-x", "-C", tmpDir.toString(), "-f", tarFile.toString()));
//            if (rc != 0) {
//                throw new RuntimeException("tar extract failed (rc=" + rc + ")");
//            }
//
//            // Zip folder with prefix into <version>.zip
//            Path zipOut = vdir.resolve(version + ".zip");
//            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipOut))) {
//                Path base = tmpDir.resolve(prefixPath);
//                if (!Files.isDirectory(base)) {
//                    throw new IllegalStateException("Expected extracted prefix folder missing: " + base);
//                }
//                // Walk and add entries
//                Files.walk(base).forEach(p -> {
//                    try {
//                        String rel = base.getParent().relativize(p).toString().replace("\\", "/");
//                        if (rel.isEmpty()) return; // skip base parent
//                        ZipEntry ze = Files.isDirectory(p) ? new ZipEntry(rel + "/") : new ZipEntry(rel);
//                        zos.putNextEntry(ze);
//                        if (Files.isRegularFile(p)) {
//                            Files.copy(p, zos);
//                        }
//                        zos.closeEntry();
//                    } catch (IOException e) {
//                        throw new UncheckedIOException(e);
//                    }
//                });
//            }
                log.debug("Wrote .zip -> {}", zipFile);

                // List files in VDIR
                log.debug("DONE. Published module '{}' version {} (rev: {}, dir: {})", modFromRev, version, version, moduleDir);
            } finally {
                // cleanup temp
                deleteRecursive(tmpDir);
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- helpers

    private static int run(ProcessBuilder pb) throws IOException, InterruptedException {
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        return p.waitFor();
    }

    private static String gitShow(Path repo, String spec) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "-C", repo.toString(), "show", spec);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (InputStream is = p.getInputStream()) {
            String out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            int rc = p.waitFor();
            if (rc == 0) return out;
        }
        return null;
    }

    private static String extractModulePath(String goModText) {
        if (goModText == null) return null;
        // Первая строка module <path>
        Pattern pat = Pattern.compile("^module\\s+(.+)\\s*$", Pattern.MULTILINE);
        Matcher m = pat.matcher(goModText);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static String patchModuleLine(String goModText, String newModule) {
        Pattern pat = Pattern.compile("^module\\s+(.+)\\s*$", Pattern.MULTILINE);
        Matcher m = pat.matcher(goModText);
        if (m.find()) {
            return new StringBuilder(goModText).replace(m.start(), m.end(), "module " + newModule).toString();
        }
        // если почему-то строки нет — вставим в начало
        return "module " + newModule + System.lineSeparator() + goModText;
    }

    //todo vlla проверить, нужен ли?
    private static String escapeForGoProxyFS(String modulePath) {
        // URL-escape, сохраняя '/' и RFC3986 unreserved
        StringBuilder sb = new StringBuilder();
        for (char c : modulePath.toCharArray()) {
            if (isUnreserved(c) || c == '/') {
                sb.append(c);
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte v : b) {
                    sb.append('%');
                    String hex = Integer.toHexString(v & 0xff).toUpperCase(Locale.ROOT);
                    if (hex.length() == 1) sb.append('0');
                    sb.append(hex);
                }
            }
        }
        return sb.toString();
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') ||
               (c >= 'a' && c <= 'z') ||
               (c >= '0' && c <= '9') ||
               c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static void deleteRecursive(Path p) {
        if (p == null) return;
        try {
            if (!Files.exists(p)) return;
            Files.walk(p)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }
}

