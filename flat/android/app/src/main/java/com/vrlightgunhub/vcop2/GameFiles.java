// SPDX-License-Identifier: GPL-2.0-or-later
package com.vrlightgunhub.vcop2;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GameFiles {
    private static final String TAG = "GameFiles";

    // Expected ROM filenames inside vcop2.zip and segabill.zip
    private static final String[] VCOP2_ROMS = {
        "epr-19234.31", "epr-19235.32", "epr-19236.33", "epr-19237.34",
        "mpr-19238.1",  "mpr-19239.2",  "mpr-19240.3",  "mpr-19241.4",
        "epr-19242.7",  "epr-19243.8",  "mpr-19244.5",  "mpr-19245.6",
        "mpr-19246.17", "mpr-19247.18", "mpr-19248.19", "mpr-19249.20",
        "mpr-19250.21", "mpr-19251.22", "mpr-19252.23", "mpr-19253.24",
        "epr-19254.9",  "epr-19255.10", "epr-19256.11", "epr-19257.12",
        "epr-19258.13", "epr-19259.14", "epr-19260.15", "epr-19261.16"
    };

    private static final String[] SEGABILL_ROMS = {
        "epr-19348.ic8", "epr-19349.ic9", "epr-19350.ic10"
    };

    public static String getRomPath() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return downloads.getAbsolutePath();
    }

    public static class ValidationResult {
        public final boolean valid;
        public final String errorMessage;

        public ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }
    }

    public static ValidationResult validateRoms() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File vcop2Zip = new File(downloadDir, "vcop2.zip");
        File segabillZip = new File(downloadDir, "segabill.zip");
        return validate(vcop2Zip, segabillZip);
    }

    public static ValidationResult validate(File vcop2Zip, File segabillZip) {
        if (!vcop2Zip.exists()) {
            return new ValidationResult(false, "vcop2.zip not found in Downloads folder (" + vcop2Zip.getParent() + "). Please place arcade ROM set there.");
        }

        if (!segabillZip.exists()) {
            return new ValidationResult(false, "segabill.zip not found in Downloads folder (" + segabillZip.getParent() + "). Please place Sega board ROM set there.");
        }

        StringBuilder missing = new StringBuilder();

        checkZipEntries(vcop2Zip, VCOP2_ROMS, missing);
        checkZipEntries(segabillZip, SEGABILL_ROMS, missing);

        if (missing.length() > 0) {
            return new ValidationResult(false, "Missing ROM files inside zip sets:\n" + missing.toString());
        }

        return new ValidationResult(true, "ROM sets validated successfully.");
    }

    private static void checkZipEntries(File zipFile, String[] expectedEntries, StringBuilder missingOut) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            for (String entryName : expectedEntries) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    missingOut.append("- ").append(entryName).append(" (in ").append(zipFile.getName()).append(")\n");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading " + zipFile.getName(), e);
            missingOut.append("- Could not read ").append(zipFile.getName()).append(": ").append(e.getMessage()).append("\n");
        }
    }
}
