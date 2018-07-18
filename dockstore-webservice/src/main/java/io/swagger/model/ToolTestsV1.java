/*
 * GA4GH Tool Discovery API
 * Proposed API for GA4GH (Global Alliance for Genomics & Health) tool repositories. A tool consists of a set of container images that are paired with a set of documents. Examples of documents include CWL (Common Workflow Language) or WDL (Workflow Description Language) or NFL (Nextflow) that describe how to use those images and a set of specifications for those images (examples are Dockerfiles or Singularity recipes) that describe how to reproduce those images in the future. We use the following terminology, a \"container image\" describes a container as stored at rest on a filesystem, a \"tool\" describes one of the triples as described above. In practice, examples of \"tools\" include CWL CommandLineTools, CWL Workflows, WDL workflows, and Nextflow workflows that reference containers in formats such as Docker or Singularity.
 *
 * OpenAPI spec version: 2.0.0
 *
 *
 * NOTE: This class is auto generated by the swagger code generator program.
 * https://github.com/swagger-api/swagger-codegen.git
 * Do not edit the class manually.
 */


package io.swagger.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * A tool document that describes how to test with one or more sample test JSON.
 */
@ApiModel(description = "A tool document that describes how to test with one or more sample test JSON.")
@javax.annotation.Generated(value = "io.swagger.codegen.languages.JavaJerseyServerCodegen", date = "2018-07-18T11:25:19.861-04:00")
public class ToolTestsV1   {
    @JsonProperty("test")
    private String test = null;

    @JsonProperty("url")
    private String url = null;

    public ToolTestsV1 test(String test) {
        this.test = test;
        return this;
    }

    /**
     * Optional test JSON content for this tool. (Note that one of test and URL are required)
     * @return test
     **/
    @JsonProperty("test")
    @ApiModelProperty(value = "Optional test JSON content for this tool. (Note that one of test and URL are required)")
    public String getTest() {
        return test;
    }

    public void setTest(String test) {
        this.test = test;
    }

    public ToolTestsV1 url(String url) {
        this.url = url;
        return this;
    }

    /**
     * Optional url to the test JSON used to test this tool. Note that this URL should resolve to the raw unwrapped content that would otherwise be available in test.
     * @return url
     **/
    @JsonProperty("url")
    @ApiModelProperty(value = "Optional url to the test JSON used to test this tool. Note that this URL should resolve to the raw unwrapped content that would otherwise be available in test.")
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }


    @Override
    public boolean equals(java.lang.Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ToolTestsV1 toolTests = (ToolTestsV1) o;
        return Objects.equals(this.test, toolTests.test) &&
            Objects.equals(this.url, toolTests.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(test, url);
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class ToolTests {\n");

        sb.append("    test: ").append(toIndentedString(test)).append("\n");
        sb.append("    url: ").append(toIndentedString(url)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Convert the given object to string with each line indented by 4 spaces
     * (except the first line).
     */
    private String toIndentedString(java.lang.Object o) {
        if (o == null) {
            return "null";
        }
        return o.toString().replace("\n", "\n    ");
    }
}

