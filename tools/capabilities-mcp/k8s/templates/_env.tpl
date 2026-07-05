{{/* capabilities-mcp container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "capabilities-mcp.env" -}}
- name: TELEMETRY_ENABLED
  value: {{ .Values.telemetry.enabled | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: CAPABILITIES_OTEL_PROTOCOL
  value: {{ .Values.telemetry.protocol | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.otlpHost }}
# The otel-config lib reads HOST + GRPC_PORT (not OTLP_ENDPOINT).
- name: OTEL_EXPORTER_OTLP_HOST
  value: {{ .Values.telemetry.otlpHost | quote }}
- name: OTEL_EXPORTER_OTLP_GRPC_PORT
  value: {{ .Values.telemetry.otlpGrpcPort | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
