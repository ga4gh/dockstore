package io.openapi.api.impl;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import io.dockstore.webservice.core.User;
import io.openapi.api.ServiceInfoApiService;
import io.openapi.model.Service;
import io.openapi.model.ServiceType;
import io.openapi.model.TRSServiceOrganization;
import io.swagger.api.impl.ToolsApiServiceImpl;

import static io.dockstore.common.PipHelper.DEV_SEM_VER;

public class ServiceInfoApiServiceImpl extends ServiceInfoApiService {
    public static final Date UPDATE_DATE = new Date();
    @Override
    public Response getServiceInfo(SecurityContext securityContext, ContainerRequestContext value, Optional<User> user) {
        Service service = getService();
        return Response.ok().entity(service).build();
    }

    public static Service getService() {
        Service service = new Service();
        service.setId("1");
        service.setName("Dockstore");
        service.setType(getServiceType());
        service.setDescription("Dockstore is an implementation of the GA4GH Tool Registry API which is a standard for listing and describing available tools (both stand-alone, Docker-based tools as well as workflows in CWL, WDL, Nextflow, or Galaxy)");
        service.setOrganization(getOrganization());
        service.setContactUrl("https://discuss.dockstore.org/t/opening-helpdesk-tickets/1506");
        service.setDocumentationUrl("https://docs.dockstore.org");
        final int createYear = 2020;
        // Current create date is set to the 1.9.0 Dockstore release which is when we first started supporting TRS 2.0.0
        Date createDate = Date.from(LocalDate.of(createYear, Month.JULY, 2).atStartOfDay(ZoneId.systemDefault()).toInstant());
        // TODO: Somehow get swagger codegen to generate string parameter (RFC 3339) instead of Date. Or...get the object mapper to selectively map Dates different for this endpoint.
        service.setCreatedAt(createDate);
        // TODO: Somehow get swagger codegen to generate string parameter (RFC 3339) instead of Date. Or...get the object mapper to selectively map Dates different for this endpoint.
        service.setUpdatedAt(UPDATE_DATE);
        String implVersion = ToolsApiServiceImpl.class.getPackage().getImplementationVersion();
        String environment = implVersion == null ? "dev" : "prod";
        implVersion = implVersion == null ? DEV_SEM_VER : implVersion;
        service.setEnvironment(environment);
        service.setVersion(implVersion);
        return service;
    }

    private static ServiceType getServiceType() {
        ServiceType serviceType = new ServiceType();
        serviceType.setGroup("org.ga4gh");
        serviceType.setArtifact("TRS");
        // Need to manually set this every time we support a new version, though it's not very often
        // TODO: Maybe parse the YAML for the version, though is it worth it?
        serviceType.setVersion("2.0.1");
        return serviceType;
    }

    private static TRSServiceOrganization getOrganization() {
        TRSServiceOrganization organization = new TRSServiceOrganization();
        organization.setName("Dockstore");
        organization.setUrl("https://dockstore.org");
        return organization;

    }
}
