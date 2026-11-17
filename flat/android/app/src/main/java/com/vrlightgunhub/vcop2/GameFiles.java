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
        "epr-18518.14", "epr-18519.15", "epr-18524.12", "epr-18525.13",
        "epr-18530.30", "epr-18520.4",  "epr-18521.5",  "mpr-16310.15",
        "mpr-16311.16", "mpr-16312.14", "mpr-18510.20", "mpr-18511.24",
        "mpr-18512.25", "mpr-18513.16", "mpr-18514.8",  "mpr-18515.9",
        "mpr-18516.10", "mpr-18517.11", "mpr-18522.6",  "mpr-18523.7",
        "mpr-18526.37", "mpr-18527.36", "mpr-18528.32", "mpr-18529.31",
        "opr-14742a.45", "opr-14743a.46", "opr-14744.58", "opr-14745.59",
        "opr-14746.62", "opr-14747.63"
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

        StringBuilder missing = new StringBuilder();
        checkZipEntries(vcop2Zip, VCOP2_ROMS, missing);

        if (missing.length() > 0) {
            return new ValidationResult(false, "Missing ROM files inside vcop2.zip:\n" + missing.toString());
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
