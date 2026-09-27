package gradum;


/**
 * Gradum server version. SemVer MAJOR.MINOR.PATCH[-pre-release]; bump rules
 * live in docs/VERSIONING.md. Keep this literal and build.gradle.kts's
 * version in sync. It appears in session events, /health, and tests.
 */
public final class Version {

    public static final String GRADUM_VERSION = "1.0.2-experimental";

    private Version() {
    }
}
