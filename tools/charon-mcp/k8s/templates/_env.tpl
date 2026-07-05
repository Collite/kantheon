{{/* charon-mcp container env — lifted from the kustomize base (D2). No OTel in the base. */}}
{{- define "charon-mcp.env" -}}
- name: CHARON_MCP_SERVER_PORT
  value: {{ .Values.ports.http | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_CHARON_MCP
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
