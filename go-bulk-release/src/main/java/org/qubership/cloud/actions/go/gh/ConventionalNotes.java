package org.qubership.cloud.actions.go.gh;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class ConventionalNotes {

    static final Pattern CC = Pattern.compile(
            "^(?<type>build|ci|docs|feat|fix|perf|refactor|style|test|chore|revert)(?:\\((?<scope>[^)]+)\\))?(?<bang>!)?:\\s(?<subject>.+)$"
    );

    public static String generate(File repoDir, String fromTagOrNull, String toTagOrSha) throws Exception {
        try (Git git = Git.open(repoDir)) {
            Iterable<RevCommit> gitLog;
            ObjectId to = git.getRepository().resolve(toTagOrSha + "^{commit}");
            if (fromTagOrNull == null || fromTagOrNull.isEmpty()) {
                gitLog = git.log()
                        .add(to)
                        .call();
            } else {
                ObjectId from = git.getRepository().resolve(fromTagOrNull + "^{commit}");
                gitLog = git.log()
                        .add(to)
                        .not(from)
                        .call();
            }

            Map<String, List<Item>> buckets = new LinkedHashMap<>();
            List<String> order = List.of("feat","fix","perf","refactor","docs","test","build","ci","chore","revert","style");
            order.forEach(t -> buckets.put(t, new ArrayList<>()));
            List<Item> breaking = new ArrayList<>();

            for (RevCommit c : gitLog) {
                log.debug("VLLA iterate before {}", c.getFullMessage());
            }

            for (RevCommit c : gitLog) {
                log.debug("VLLA iterate {}", c.getFullMessage());
                String msg = c.getFullMessage().trim();
                if (msg.startsWith("Merge ")) continue;

                Matcher m = CC.matcher(msg.lines().findFirst().orElse(""));
                boolean isBreaking = msg.contains("\nBREAKING CHANGE:") || (m.find() && "!".equals(m.group("bang")));
                if (!m.matches()) continue;

                String type  = m.group("type");
                String scope = m.group("scope");
                String subj  = m.group("subject");

                Item it = new Item(type, scope, subj, shortSha(c));
                if (isBreaking) breaking.add(it);
                buckets.computeIfAbsent(type, k -> new ArrayList<>()).add(it);
            }

            StringBuilder md = new StringBuilder();
            md.append("## Изменения\n\n");

            if (!breaking.isEmpty()) {
                md.append("### ⚠️ BREAKING CHANGES\n");
                for (Item i : reverse(breaking)) {
                    md.append("- ").append(render(i)).append("\n");
                }
                md.append("\n");
            }

            Map<String, String> titles = getStringStringMap();

            for (String t : order) {
                List<Item> items = buckets.getOrDefault(t, List.of());
                if (items.isEmpty()) continue;
                md.append(titles.getOrDefault(t, "### " + t)).append("\n");
                for (Item i : reverse(items)) {
                    md.append("- ").append(render(i)).append("\n");
                }
                md.append("\n");
            }

            return md.toString().trim();
        }
    }

    private static Map<String, String> getStringStringMap() {
        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("feat","### ✨ Features");
        titles.put("fix","### 🐛 Fixes");
        titles.put("perf","### 🚀 Performance");
        titles.put("refactor","### ♻️ Refactoring");
        titles.put("docs","### 📝 Docs");
        titles.put("test","### ✅ Tests");
        titles.put("build","### 🏗️ Build");
        titles.put("ci","### 🔁 CI");
        titles.put("chore","### 🔧 Chores");
        titles.put("revert","### ↩️ Reverts");
        titles.put("style","### 🎨 Style");
        return titles;
    }

    static String render(Item i) {
        return (i.scope != null ? "**" + i.scope + "**: " : "") + i.subject + " (" + i.sha + ")";
    }

    static String shortSha(RevCommit c) { return c.getId().abbreviate(7).name(); }

    static <T> List<T> reverse(List<T> list) { var r = new ArrayList<>(list); Collections.reverse(r); return r; }

    static class Item {
        final String type, scope, subject, sha;
        Item(String type, String scope, String subject, String sha) {
            this.type=type; this.scope=scope; this.subject=subject; this.sha=sha;
        }
    }
}