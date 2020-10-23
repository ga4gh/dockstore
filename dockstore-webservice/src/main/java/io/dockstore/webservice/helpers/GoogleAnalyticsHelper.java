package io.dockstore.webservice.helpers;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.services.analyticsreporting.v4.AnalyticsReporting;
import com.google.api.services.analyticsreporting.v4.AnalyticsReportingScopes;
import com.google.api.services.analyticsreporting.v4.model.DateRange;
import com.google.api.services.analyticsreporting.v4.model.DateRangeValues;
import com.google.api.services.analyticsreporting.v4.model.DimensionFilter;
import com.google.api.services.analyticsreporting.v4.model.DimensionFilterClause;
import com.google.api.services.analyticsreporting.v4.model.GetReportsRequest;
import com.google.api.services.analyticsreporting.v4.model.GetReportsResponse;
import com.google.api.services.analyticsreporting.v4.model.Metric;
import com.google.api.services.analyticsreporting.v4.model.MetricHeaderEntry;
import com.google.api.services.analyticsreporting.v4.model.Report;
import com.google.api.services.analyticsreporting.v4.model.ReportRequest;
import com.google.api.services.analyticsreporting.v4.model.ReportRow;
import io.dockstore.webservice.DockstoreWebserviceConfiguration;
import io.dockstore.webservice.resources.TokenResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

public class GoogleAnalyticsHelper {
    private static final Logger LOG = LoggerFactory.getLogger(GoogleAnalyticsHelper.class);

    private static AnalyticsReporting analyticsReporting;
    private static DockstoreWebserviceConfiguration config;

    public static void setConfig(final DockstoreWebserviceConfiguration config) {
        GoogleAnalyticsHelper.config = config;
    }

    /**
     * Initialize a Google Analytics Reporting API V4 service object
     * @return AnalyticsReporting
     */
    public static void initAnalyticsReporting() {
        try {
            HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            GoogleCredential credential = GoogleCredential
                    .fromStream(new FileInputStream(config.getGoogleServiceAccountSecretFile()))
                    .createScoped(AnalyticsReportingScopes.all());
            analyticsReporting = new AnalyticsReporting.Builder(httpTransport, TokenResource.JSON_FACTORY, credential)
                    .setApplicationName("dockstore").build();
        } catch (Exception e) {
            LOG.error("Failed to initialize Google Analytics Reporting API service", e);
            analyticsReporting = null;
        }
    }

    /**
     * Total pagesviews for a dockstore.org page since the start date
     * @param pagePath - dockstore.org URL path; ie. https://dockstore.org{pagePath}
     * @param startDate - YYYY-MM-DD (ISO-8601)
     * @return
     */
    public static String getPageviews(final String pagePath, final String startDate) {
        final GetReportsResponse pageviewsReport = getPageviewsReport(pagePath, startDate);

        if (pageviewsReport == null) {
            return null;
        }

        for (Report report: pageviewsReport.getReports()) {
            List<MetricHeaderEntry> metricHeaders = report.getColumnHeader().getMetricHeader().getMetricHeaderEntries();
            List<ReportRow> rows = report.getData().getRows();

            if (rows == null) {
                LOG.warn("No data found for " + config.getGoogleAnalyticsViewId());
                return null;
            }

            for (ReportRow row: rows) {
                List<DateRangeValues> metrics = row.getMetrics();

                for (int i = 0; i < metrics.size(); i++) {
                    List<String> values = metrics.get(i).getValues();
                    for (int j = 0; j < values.size() && j < metricHeaders.size(); j++) {
                        if (metricHeaders.get(j).getName().equals("ga:pageviews")) {
                            return values.get(j);
                        }
                    }
                }
            }
        }

        LOG.warn("No pageviews data found for " + pagePath);
        return null;
    }

    private static GetReportsResponse getPageviewsReport(final String pagePath, final String startDate) {
        if (analyticsReporting == null) {
            return null;
        }

        DateRange dateRange = new DateRange();
        dateRange.setStartDate(startDate);
        dateRange.setEndDate("today");

        Metric pageviews = new Metric()
                .setExpression("ga:pageviews");

        DimensionFilter pagePathFilter = new DimensionFilter()
                .setDimensionName("ga:pagePath")
                .setOperator("BEGINS_WITH")
                .setExpressions(List.of(pagePath));

        DimensionFilterClause pagePathFilterClause = new DimensionFilterClause()
                .setFilters(List.of(pagePathFilter));

        ReportRequest request = new ReportRequest()
                .setViewId(config.getGoogleAnalyticsViewId())
                .setDateRanges(List.of(dateRange))
                .setMetrics(List.of(pageviews))
                .setDimensionFilterClauses(List.of(pagePathFilterClause))
                .setHideTotals(true)
                .setHideValueRanges(true);

        GetReportsRequest getReport = new GetReportsRequest()
                .setReportRequests(List.of(request));

        try {
            return analyticsReporting.reports().batchGet(getReport).execute();
        } catch (IOException e) {
            LOG.error("Could not get pageviews from Google Analytics", e);
            return null;
        }
    }
}
