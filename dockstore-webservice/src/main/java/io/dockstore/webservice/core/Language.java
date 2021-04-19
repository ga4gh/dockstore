package io.dockstore.webservice.core;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;

import io.dockstore.common.DescriptorLanguage;
import io.swagger.annotations.ApiModelProperty;

@Embeddable
public class Language implements Serializable {
    // TODO: This needs to be an enum
    @Column(name = "language", nullable = false, columnDefinition = "varchar(50)")
    @ApiModelProperty(value = "The supported language")
    @Enumerated(EnumType.STRING)
    private DescriptorLanguage language;

    @Column(name = "version", nullable = true, columnDefinition = "varchar(50)")
    @ApiModelProperty(value = "The version of the supported language")
    private String version;


}
