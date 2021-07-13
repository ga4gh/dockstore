/*
 *    Copyright 2021 OICR
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
/**
 * Models for responses from the Docker Registry HTTP API V2. Used right now just to grab checksum info for images. But if other fields are needed
 * in the future, then the fields that are two words (ex: imageID) need something like @SerializedName("image_id").
 * Created by looking at https://docs.docker.com/registry/spec/manifest-v2-2/#image-manifest
 */
package io.dockstore.webservice.core.docker;
