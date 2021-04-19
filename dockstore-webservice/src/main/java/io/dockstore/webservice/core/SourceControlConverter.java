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
package io.dockstore.webservice.core;

import java.util.Optional;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

import com.google.common.collect.Lists;
import io.dockstore.common.SourceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Converter(autoApply = true)
public class SourceControlConverter implements AttributeConverter<SourceControl, String> {
    private static final Logger LOG = LoggerFactory.getLogger(SourceControlConverter.class);

    @Override
    public String convertToDatabaseColumn(SourceControl attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.toString();
    }

    @Override
    public SourceControl convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            LOG.info("Cannot convert null or empty source control");
            return null;
        }
        Optional<SourceControl> first = Lists.newArrayList(SourceControl.values()).stream().filter(val -> val.toString().equals(dbData))
            .findFirst();
        if (first.isPresent()) {
            return first.get();
        } else {
            LOG.error("could not convert source control: " + dbData);
            return null;
        }
    }
}
