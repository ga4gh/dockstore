package io.dockstore.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.zafarkhaja.semver.Version;

/**
 * @author gluu
 * @since 13/04/18
 */
public final class PipHelper {
    public static final String DEV_SEM_VER = "development-build";
    private PipHelper() { }

    /**
     * Figures out which pip requirements file to resolve to since not every clientVersion changes the pip requirements.
     * This function should be modified every time a new pip requirements file is added.
     * @param semVerString  The Dockstore client version
     * @return              The most recently changed pip requirements file to the Dockstore client version
     */
    public static String convertSemVerToAvailableVersion(String semVerString) {
        if (semVerString == null || DEV_SEM_VER.equals(semVerString)) {
            semVerString = "9001.9001.9001";
        }
        Version semVer = Version.valueOf(semVerString);
        if (semVer.greaterThan(Version.valueOf("1.9.0"))) {
            return "1.10.0";
        }
        // Use the 1.7.0 even for snapshot
        if (semVer.greaterThan(Version.valueOf("1.6.0"))) {
            return "1.7.0";
        }
        if (semVer.greaterThan(Version.valueOf("1.5.0"))) {
            return "1.6.0";
        }
        if (semVer.greaterThan(Version.valueOf("1.4.0"))) {
            return "1.5.0";
        } else {
            return "1.4.0";
        }
    }

    /**
     * Converts a pip requirements file's contents into a map that can be used as a json
     * @param pipRequirementsString     The pip requirements file's contents
     * @return                          A map equivalent to the file's contents
     */
    public static Map<String, String> convertPipRequirementsStringToMap(String pipRequirementsString) {
        String[] lines = pipRequirementsString.split(System.getProperty("line.separator"));
        List<String> pipDeps = new ArrayList(Arrays.asList(lines));
        Map<String, String> pipDepMap = new HashMap<>();
        pipDeps.forEach(pipDep -> {
            String[] split = pipDep.split("==");
            String key = split[0];
            String mapValue;
            if (split.length != 2) {
                mapValue = "any";
            } else {
                mapValue = split[1];
            }
            pipDepMap.put(key, mapValue);
        });
        return pipDepMap;
    }
}
