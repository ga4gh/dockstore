Due to some super crappy regression, this template directory needs the full set even though we're only modifying 
api and apiService.

Changes include:
* overriding of tags to organize new operations
* adding @UnitOfWork
* adding support for containerContext (for content negotiation) and user (for authorization)
* overriding of path
* override tags to GA4GHV20
* adding @JsonValue annotation to modelEnum

Using https://github.com/swagger-api/swagger-codegen-generators/tree/v1.0.16/src/main/resources/handlebars/JavaJaxRS
Updated selected templates to
Using https://github.com/swagger-api/swagger-codegen-generators/tree/v1.0.25/src/main/resources/handlebars/JavaJaxRS
(pending resolution of https://github.com/swagger-api/swagger-codegen/issues/8904 )

See https://github.com/swagger-api/swagger-codegen/issues/9893



