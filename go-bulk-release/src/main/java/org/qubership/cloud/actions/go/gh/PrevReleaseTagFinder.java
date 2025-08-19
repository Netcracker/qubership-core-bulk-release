package org.qubership.cloud.actions.go.gh;

import org.kohsuke.github.*;
import java.io.IOException;
import java.util.regex.*;

public class PrevReleaseTagFinder {
    private static final Pattern SEMVER = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$");


    public static String findFromTagViaReleases(GHRepository repo, String currentTag, boolean includePrereleases) throws IOException {
        SemVer cur = SemVer.parse(currentTag);

        GHRelease bestRelease = null;
        SemVer bestVer = null;

        for (GHRelease r : repo.listReleases()) {
            if (r.isDraft()) continue;
            if (!includePrereleases && r.isPrerelease()) continue;
            String tag = r.getTagName();
            if (tag == null) continue;

            if (cur != null) {
                SemVer v = SemVer.parse(tag);
                if (v == null) continue;
                if (v.compareTo(cur) >= 0) continue;          // нужно строго меньше текущего
                if (bestVer == null || v.compareTo(bestVer) > 0) {
                    bestVer = v;
                    bestRelease = r;
                }
            } else {
                // Текущий тег не SemVer — возьмём просто самый свежий опубликованный релиз
                if (bestRelease == null || r.getCreatedAt().after(bestRelease.getCreatedAt())) {
                    bestRelease = r;
                }
            }
        }
        return bestRelease != null ? bestRelease.getTagName() : null;
    }

    // --- простой SemVer без build metadata, пререлизы меньше релизов ---
    static final class SemVer implements Comparable<SemVer> {
        final int major, minor, patch; final String pre; // null -> релиз
        SemVer(int M,int m,int p,String pre){this.major=M;this.minor=m;this.patch=p;this.pre=pre;}
        static SemVer parse(String tag){
            Matcher m = SEMVER.matcher(tag); if(!m.matches()) return null;
            return new SemVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), m.group(4));
        }
        public int compareTo(SemVer o){
            if (major!=o.major) return major-o.major;
            if (minor!=o.minor) return minor-o.minor;
            if (patch!=o.patch) return patch-o.patch;
            if (pre==null && o.pre!=null) return 1;
            if (pre!=null && o.pre==null) return -1;
            if (pre==null) return 0;
            return pre.compareTo(o.pre);
        }
    }
}
