/*
 *    Copyright 2017 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dockstore.common;

/**
 * This describes all keys available in Dockstore config files.
 *
 * @author xliu
 */
public final class Constants {

    public static final String WEBSERVICE_BASE_PATH = "webservice.base_path";
    public static final String WEBSERVICE_TOKEN_USER_1 = "webservice.tokenUser1"; // Dummy dockstore token for user 1
    public static final String WEBSERVICE_TOKEN_USER_2 = "webservice.tokenUser2"; // Dummy dockstore token for user 2

    private Constants() {
        // hide the default constructor for a constant class
    }
}
