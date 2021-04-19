/*
 * Copyright 2019 OICR
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dockstore.webservice.core.dockerhub;

import com.google.gson.annotations.SerializedName;

public class DockerHubImage {
    private String osFeatures;

    private String features;

    private Long size;

    private String os;

    @SerializedName("os_version")
    private String osVersion;

    private String digest;

    private String variant;

    private String architecture;

    @SerializedName("last_pushed")
    private String lastPushed;

    public String getOsFeatures() {
        return osFeatures;
    }

    public void setOsFeatures(String osFeatures) {
        this.osFeatures = osFeatures;
    }

    public String getFeatures() {
        return features;
    }

    public void setFeatures(String features) {
        this.features = features;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public String getVariant() {
        return variant;
    }

    public void setVariant(String variant) {
        this.variant = variant;
    }

    public String getArchitecture() {
        return architecture;
    }

    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    public String getLastPushed() {
        return lastPushed;
    }

    public void setLastPushed(String lastPushed) {
        this.lastPushed = lastPushed;
    }
}
