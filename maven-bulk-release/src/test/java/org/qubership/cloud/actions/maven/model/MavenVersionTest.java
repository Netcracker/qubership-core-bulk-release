package org.qubership.cloud.actions.maven.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MavenVersionTest {

    @Test
    public void testSemver() {
        String version = "1.2.3";
        MavenVersion mavenVersion = new MavenVersion(version);
        Assertions.assertEquals(1, mavenVersion.getMajor());
        Assertions.assertEquals(2, mavenVersion.getMinor());
        Assertions.assertEquals(3, mavenVersion.getPatch());
        Assertions.assertEquals(version, mavenVersion.toString());
        Assertions.assertNull(mavenVersion.getSuffix());
    }

    @Test
    public void testMinorOnly() {
        String version = "1.2";
        MavenVersion mavenVersion = new MavenVersion(version);
        Assertions.assertEquals(1, mavenVersion.getMajor());
        Assertions.assertEquals(2, mavenVersion.getMinor());
        Assertions.assertEquals(0, mavenVersion.getPatch());
        Assertions.assertEquals(version, mavenVersion.toString());
        Assertions.assertNull(mavenVersion.getSuffix());
    }

    @Test
    public void testMajorOnly() {
        String version = "20250101";
        MavenVersion mavenVersion = new MavenVersion(version);
        Assertions.assertEquals(20250101, mavenVersion.getMajor());
        Assertions.assertEquals(0, mavenVersion.getMinor());
        Assertions.assertEquals(0, mavenVersion.getPatch());
        Assertions.assertEquals(version, mavenVersion.toString());
        Assertions.assertNull(mavenVersion.getSuffix());
    }

    @Test
    public void testChangePatchPart() {
        String version = "1.2.3";
        MavenVersion mavenVersion = new MavenVersion(version);
        mavenVersion.update(VersionIncrementType.PATCH, 0);
        Assertions.assertEquals(1, mavenVersion.getMajor());
        Assertions.assertEquals(2, mavenVersion.getMinor());
        Assertions.assertEquals(0, mavenVersion.getPatch());
        Assertions.assertEquals("1.2.0", mavenVersion.toString());
        Assertions.assertNull(mavenVersion.getSuffix());
    }

    @Test
    public void testChangeMinorPart() {
        String version = "1.2.3";
        MavenVersion mavenVersion = new MavenVersion(version);
        mavenVersion.update(VersionIncrementType.MINOR, 4);
        Assertions.assertEquals(1, mavenVersion.getMajor());
        Assertions.assertEquals(4, mavenVersion.getMinor());
        Assertions.assertEquals(3, mavenVersion.getPatch());
        Assertions.assertEquals("1.4.3", mavenVersion.toString());
        Assertions.assertNull(mavenVersion.getSuffix());
    }

    @Test
    public void testRemoveNumberSuffixOnPatchChange() {
        String version = "1.2.3.4";
        MavenVersion mavenVersion = new MavenVersion(version);
        Assertions.assertEquals(".4", mavenVersion.getSuffix());
        mavenVersion.update(VersionIncrementType.PATCH, 5);
        Assertions.assertEquals(1, mavenVersion.getMajor());
        Assertions.assertEquals(2, mavenVersion.getMinor());
        Assertions.assertEquals(5, mavenVersion.getPatch());
        Assertions.assertEquals("1.2.5", mavenVersion.toString());
        Assertions.assertNull(mavenVersion.getSuffix());
    }
    @Test
    public void testRemoveNumberTextSuffixOnPatchChange() {
        String version = "1.2.3.Final";
        MavenVersion mavenVersion = new MavenVersion(version);
        Assertions.assertEquals(".Final", mavenVersion.getSuffix());
        mavenVersion.update(VersionIncrementType.PATCH, 5);
        Assertions.assertEquals(1, mavenVersion.getMajor());
        Assertions.assertEquals(2, mavenVersion.getMinor());
        Assertions.assertEquals(5, mavenVersion.getPatch());
        Assertions.assertEquals("1.2.5", mavenVersion.toString());
        Assertions.assertNull(mavenVersion.getSuffix());
    }

}
