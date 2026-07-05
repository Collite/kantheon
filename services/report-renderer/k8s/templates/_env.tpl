{{/* report-renderer container env — HTTP port + OTel + extraEnv passthrough (HTTP-only). */}}
{{- define "report-renderer.env" -}}
- name: REPORT_RENDERER_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_REPORT_RENDERER
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
