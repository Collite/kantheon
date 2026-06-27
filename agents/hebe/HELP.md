# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Gradle documentation](https://docs.gradle.org)
* [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/4.0.2/gradle-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.0.2/gradle-plugin/packaging-oci-image.html)
* [Spring Boot DevTools](https://docs.spring.io/spring-boot/4.0.2/reference/using/devtools.html)
* [Docker Compose Support](https://docs.spring.io/spring-boot/4.0.2/reference/features/dev-services.html#features.dev-services.docker-compose)
* [Spring Web](https://docs.spring.io/spring-boot/4.0.2/reference/web/servlet.html)
* [Apache Freemarker](https://docs.spring.io/spring-boot/4.0.2/reference/web/servlet.html#web.servlet.spring-mvc.template-engines)
* [Spring Security](https://docs.spring.io/spring-boot/4.0.2/reference/web/spring-security.html)
* [OAuth2 Client](https://docs.spring.io/spring-boot/4.0.2/reference/web/spring-security.html#web.security.oauth2.client)
* [OAuth2 Authorization Server](https://docs.spring.io/spring-boot/4.0.2/reference/web/spring-security.html#web.security.oauth2.authorization-server)
* [JDBC API](https://docs.spring.io/spring-boot/4.0.2/reference/data/sql.html)
* [Spring Data JDBC](https://docs.spring.io/spring-boot/4.0.2/reference/data/sql.html#data.sql.jdbc)
* [Flyway Migration](https://docs.spring.io/spring-boot/4.0.2/how-to/data-initialization.html#howto.data-initialization.migration-tool.flyway)
* [WebAuthn for Spring Security](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html)
* [Elasticsearch](https://docs.spring.io/spring-boot/4.0.2/reference/data/nosql.html#data.nosql.elasticsearch)
* [Neo4j](https://docs.spring.io/spring-boot/4.0.2/reference/data/nosql.html#data.nosql.neo4j)
* [Spring gRPC](https://docs.spring.io/spring-grpc/reference/index.html)
* [Spring Boot Actuator](https://docs.spring.io/spring-boot/4.0.2/reference/actuator/index.html)
* [OpenTelemetry](https://docs.spring.io/spring-boot/4.0.2/reference/actuator/observability.html#actuator.observability.opentelemetry)
* [Anthropic Claude](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html)
* [Azure OpenAI](https://docs.spring.io/spring-ai/reference/api/chat/azure-openai-chat.html)
* [Ollama](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)
* [OpenAI](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
* [Model Context Protocol Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
* [Model Context Protocol Client](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)
* [OpenAI SDK](https://docs.spring.io/spring-ai/reference/api/chat/openai-sdk-chat.html)
* [In-memory Chat Memory Repository](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
* [JDBC Chat Memory Repository](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
* [PGvector Vector Database](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)
* [PostgresML](https://docs.spring.io/spring-ai/reference/api/embeddings/postgresml-embeddings.html)
* [Redis Search and Query Vector Database](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)
* [Transformers (ONNX) Embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)
* [Qdrant Vector Database](https://docs.spring.io/spring-ai/reference/api/vectordbs/qdrant.html)
* [Markdown Document Reader](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html#_markdown)
* [Tika Document Reader](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html#_tika_docx_pptx_html)
* [PDF Document Reader](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html#_pdf_page)
* [JSoup Document Reader](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)
* [Securing a Web Application](https://spring.io/guides/gs/securing-web/)
* [Spring Boot and OAuth2](https://spring.io/guides/tutorials/spring-boot-oauth2/)
* [Authenticating a User with LDAP](https://spring.io/guides/gs/authenticating-ldap/)
* [Accessing Relational Data using JDBC with Spring](https://spring.io/guides/gs/relational-data-access/)
* [Managing Transactions](https://spring.io/guides/gs/managing-transactions/)
* [Using Spring Data JDBC](https://github.com/spring-projects/spring-data-examples/tree/main/jdbc/basics)
* [Building a RESTful Web Service with Spring Boot Actuator](https://spring.io/guides/gs/actuator-service/)

### Additional Links
These additional references should also help you:

* [Gradle Build Scans – insights for your project's build](https://scans.gradle.com#gradle)
* [Various sample apps using Spring gRPC](https://github.com/spring-projects/spring-grpc/tree/main/samples)

### Docker Compose support
This project contains a Docker Compose file named `compose.yaml`.
In this file, the following services have been defined:

* grafana-lgtm: [`grafana/otel-lgtm:latest`](https://hub.docker.com/r/grafana/otel-lgtm)
* ollama: [`ollama/ollama:latest`](https://hub.docker.com/r/ollama/ollama)
* pgvector: [`pgvector/pgvector:pg16`](https://hub.docker.com/r/pgvector/pgvector)
* qdrant: [`qdrant/qdrant:latest`](https://hub.docker.com/r/qdrant/qdrant)
* redis: [`redis/redis-stack:latest`](https://hub.docker.com/r/redis/redis-stack)
* sqlserver: [`mcr.microsoft.com/mssql/server:latest`](https://mcr.microsoft.com/en-us/product/mssql/server/about/)

Please review the tags of the used images and set them to the same as you're running in production.

