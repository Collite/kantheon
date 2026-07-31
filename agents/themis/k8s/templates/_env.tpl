{{/* themis-mcp container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "themis-mcp.env" -}}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: TELEMETRY_ENABLED
  value: {{ .Values.telemetry.enabled | quote }}
{{- if .Values.telemetry.enabled }}
- name: RESOLVER_OTEL_PROTOCOL
  value: {{ .Values.telemetry.protocol | quote }}
{{- if .Values.telemetry.otlpHost }}
# HOST + GRPC_PORT, not ENDPOINT. `telemetry.endpoint` rendered
# `OTEL_EXPORTER_OTLP_ENDPOINT`, which the otel-config lib never reads — it resolves
# `System.getenv("OTEL_EXPORTER_OTLP_HOST") ?: "localhost"` and composes the URL itself.
# So the key was inert: any deployment that set it still exported to localhost:4317 and
# retried against nothing. `endpoint` is kept in values.yaml as a deprecation stub.
- name: OTEL_EXPORTER_OTLP_HOST
  value: {{ .Values.telemetry.otlpHost | quote }}
- name: OTEL_EXPORTER_OTLP_GRPC_PORT
  value: {{ .Values.telemetry.otlpGrpcPort | quote }}
{{- end }}
{{- end }}
{{- range .Values.secretEnv }}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secretName }}
      key: {{ .secretKey }}
      {{- if .optional }}
      optional: {{ .optional }}
      {{- end }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
