# Runtime dependencies

The standalone application is built as `target/vdml-1.0.0-standalone.jar`.
Its Java libraries are bundled into that JAR; they do not need to be downloaded
separately on the computer running the application.

## Java libraries bundled in the JAR

Direct dependencies:

- `org.apache.pdfbox:pdfbox:2.0.31` — PDF export.
- `org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0` — optional VHDL language-server integration.

Transitive dependencies:

- `org.apache.pdfbox:fontbox:2.0.31`
- `commons-logging:commons-logging:1.2`
- `org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:1.0.0`
- `com.google.code.gson:gson:2.14.0`
- `com.google.errorprone:error_prone_annotations:2.48.0`

## External runtime tools

- Java 17 or newer is required to run the JAR.
- GHDL is optional. Install it and add it to `PATH` for VHDL syntax checks and
  design validation.
- A VHDL language server such as `vhdl_ls` is optional and must be installed
  separately to use LSP diagnostics.
- Maven is only required to build vdml, not to run the standalone JAR.
