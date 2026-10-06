# upload-demo

Smallest useful project on top of `eazy-batch-processor`: one `@BatchJob`, no controller.

```bash
(cd ../.. && mvn clean install)     # installs the library
mvn spring-boot:run
curl -o template.xlsx http://localhost:8080/batch/personImport/template
curl -F file=@template.xlsx -F team=sales http://localhost:8080/batch/personImport/upload
```

The upload answers with a `jobExecutionId`; the finished report is at the `errorFileUrl` of the
final message (WebSocket `/topic/batch-progress/{id}` or `GET /batch/{id}/status`).
