package io.dockstore.webservice.core;

import java.io.Serializable;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(value = "CloudInstance", description = "Instances that launch-with cloud partners have")
@Entity
@Table(name = "cloud_instance")
@NamedQueries({
        @NamedQuery(name = "io.dockstore.webservice.core.CloudInstance.findAllWithoutUser", query = "SELECT ci from CloudInstance ci where user_id is null")
})
public class CloudInstance implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", unique = true, nullable = false)
    @ApiModelProperty(value = "The CloudInstance ID", accessMode = ApiModelProperty.AccessMode.READ_ONLY)
    private long id;

    // TODO: This needs to be an enum
    // @Column(name = "partner", nullable = false, columnDefinition = "enum('GALAXY', 'TERRA', 'DNA_STACK', 'DNA_NEXUS', 'CGC', 'NHLBI_BIODATA_CATALYST', 'ANVIL')") did not work
    @Column(name = "partner", nullable = false)
    @ApiModelProperty(value = "Name of the partner")
    @Enumerated(EnumType.STRING)
    private Partner partner;

    @Column(name = "url", nullable = false)
    @ApiModelProperty(value = "The URL of the launch-with partner's private cloud instance")
    private String url;

    @Column(name = "supports_http_imports")
    @ApiModelProperty(value = "Whether the CloudInstance supports http imports or not")
    private boolean supportsHttpImports;

    @Column(name = "supports_file_imports")
    @ApiModelProperty(value = "Whether the CloudInstance supports file imports or not")
    private boolean supportsFileImports;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    @JsonIgnore
    private User user;

    @ElementCollection(targetClass = Language.class)
    @ApiModelProperty(value = "The languages the cloud instance is known to support")
    private Set<Language> supportedLanguages;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Partner getPartner() {
        return partner;
    }

    public void setPartner(Partner partner) {
        this.partner = partner;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isSupportsHttpImports() {
        return supportsHttpImports;
    }

    public void setSupportsHttpImports(boolean supportsHttpImports) {
        this.supportsHttpImports = supportsHttpImports;
    }

    public boolean isSupportsFileImports() {
        return supportsFileImports;
    }

    public void setSupportsFileImports(boolean supportsFileImports) {
        this.supportsFileImports = supportsFileImports;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Set<Language> getSupportedLanguages() {
        return supportedLanguages;
    }

    public void setSupportedLanguages(Set<Language> supportedLanguages) {
        this.supportedLanguages = supportedLanguages;
    }
}
