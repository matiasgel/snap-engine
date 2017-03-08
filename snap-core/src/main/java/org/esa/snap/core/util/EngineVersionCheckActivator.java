package org.esa.snap.core.util;

import com.bc.ceres.core.runtime.Version;
import org.esa.snap.runtime.Activator;
import org.esa.snap.runtime.EngineConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * @author Marco Peters
 */
public class EngineVersionCheckActivator implements Activator {

    private static final String MSG_UPDATE_INFO = "A new SNAP version is available for download.\nCurrently installed %s, available is %s.\nPlease visit %s\n";
    private static final VC VERSION_CHECKER = VC.getInstance();
    private static boolean hasChecked = false;

    @Override
    public void start() {
        if (VERSION_CHECKER.mustCheck()) {
            hasChecked = true;
            if (VERSION_CHECKER.checkForNewRelease()) {
                String localVersion = String.valueOf(VERSION_CHECKER.getLocalVersion());
                String remoteVersion = String.valueOf(VERSION_CHECKER.getRemoteVersion());
                String logMsg = String.format(MSG_UPDATE_INFO, localVersion, remoteVersion, SystemUtils.getApplicationHomepageUrl());
                SystemUtils.LOG.log(Level.WARNING, logMsg);
            }
        }
    }

    @Override
    public void stop() {
        if (hasChecked) {
            VERSION_CHECKER.setChecked();
        }
    }

    @Override
    public int getStartLevel() {
        // no need to start early, others can be served first.
        return 1000;
    }

    private static class VC {

        private static final String PK_CHECK_INTERVAL = "snap.versionCheck.interval";
        private static final String PK_LAST_DATE = "snap.versionCheck.lastDate";
        private static final String VERSION_FILE_NAME = "VERSION.txt";
        private static final String REMOTE_VERSION_FILE_URL = "http://step.esa.int/downloads/" + VERSION_FILE_NAME;
        private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

        private static VC instance = new VC();

        private final InputStream localVersionStream;
        private final InputStream remoteVersionStream;

        private final AtomicReference<Version> localVersion = new AtomicReference<>();
        private final AtomicReference<Version> remoteVersion = new AtomicReference<>();
        private final Preferences preferences;

        public static VC getInstance() {
            return instance;
        }

        private VC() {
            this(null, null);
        }


        // constructor used for tests
        VC(InputStream localVersionStream, InputStream remoteVersionStream) {
            this.localVersionStream = localVersionStream;
            this.remoteVersionStream = remoteVersionStream;
            EngineConfig config = EngineConfig.instance().load();
            preferences = config.preferences();

        }

        private boolean mustCheck() {
            String dateText = preferences.get(VC.PK_LAST_DATE, null);
            return mustCheck(
                    CHECK.valueOf(preferences.get(VC.PK_CHECK_INTERVAL, CHECK.WEEKLY.name())),
                    dateText != null ? LocalDateTime.parse(dateText, DATE_FORMATTER) : null);

        }

        private boolean checkForNewRelease() {
            Version localVersion = getLocalVersion();
            if (localVersion == null) {
                SystemUtils.LOG.log(Level.WARNING, "Not able to check for new SNAP version. Local version could not be retrieved.");
                return false;
            }
            Version remoteVersion = getRemoteVersion();
            if (remoteVersion == null) {
                SystemUtils.LOG.log(Level.WARNING, "Not able to check for new SNAP version. Remote version could not be retrieved.");
                return false;
            }
            return compareVersions();
        }

        private void setChecked() {
            preferences.put(VC.PK_LAST_DATE, LocalDateTime.now().format(VC.DATE_FORMATTER));
            try {
                preferences.flush();
            } catch (BackingStoreException e) {
                SystemUtils.LOG.log(Level.WARNING, "Not able to store preferences.", e);
            }
        }

        /**
         * Returns the local version, or {@code null} if no version could be found
         *
         * @return the local version, or {@code null} if no version could be found
         */
        private Version getLocalVersion() {
            if (localVersion.get() == null) {
                Path versionFile = SystemUtils.getApplicationHomeDir().toPath().resolve(VC.VERSION_FILE_NAME);
                try {
                    localVersion.set(readVersionFromStream(localVersionStream == null ? Files.newInputStream(versionFile) : localVersionStream));
                } catch (IOException e) {
                    SystemUtils.LOG.log(Level.WARNING, "Not able to check for new SNAP version. Local version could not be retrieved.", e);
                    return null;
                }
            }
            return localVersion.get();
        }

        /**
         * Returns the remote version, or {@code null} if no version could be found
         *
         * @return the remote version, or {@code null} if no version could be found
         */
        private Version getRemoteVersion() {
            if (remoteVersion.get() == null) {
                try {
                    remoteVersion.set(readVersionFromStream(
                            remoteVersionStream == null ? new URL(VC.REMOTE_VERSION_FILE_URL).openStream() : remoteVersionStream));
                } catch (IOException e) {
                    SystemUtils.LOG.log(Level.WARNING, "Not able to check for new SNAP version. Remote version could not be retrieved.", e);
                    return null;
                }
            }
            return remoteVersion.get();
        }

        static boolean mustCheck(CHECK checkInterval, LocalDateTime lastDate) {
            if (CHECK.NEVER.equals(checkInterval)) {
                return false;
            }
            if (CHECK.ON_START.equals(checkInterval)) {
                return true;
            }

            if (lastDate == null) { // no checked yet, so do it now
                return true;
            } else {
                Duration duration = Duration.between(LocalDateTime.now(), lastDate);
                long daysAgo = duration.toDays();
                return checkInterval.exceedsInterval(daysAgo);
            }
        }

        /**
         * Compares the remote and the local version and return true if the remote version is greater.
         *
         * @return {@code true} if the remote version is greater, otherwise {@code false}
         */
        private boolean compareVersions() {
            final Version remoteVersion = getRemoteVersion();
            final Version localVersion = getLocalVersion();
            return remoteVersion != null && localVersion != null && remoteVersion.compareTo(localVersion) > 0;
        }

        private static Version readVersionFromStream(InputStream inputStream) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                line = reader.readLine();
                if (line != null) {
                    return Version.parseVersion(line.toUpperCase());
                }
            }
            return null;
        }

        public enum CHECK {
            ON_START(0),
            DAILY(1),
            WEEKLY(7),
            MONTHLY(30),
            NEVER(-1);

            private final int days;

            CHECK(int days) {
                this.days = days;
            }

            boolean exceedsInterval(long daysAgo) {
                return Math.abs(daysAgo) > this.days;
            }

        }
    }
}