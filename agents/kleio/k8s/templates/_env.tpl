{{/* kleio container env — KLEIO_PORT + OTel + downstream extraEnv. The base carries no
     OTEL vars; the standard OTel block is added here (env-agnostic, off by default). */}}
{{- define "kleio.env" -}}
- name: KLEIO_PORT
  value: {{ .Values.ports.http | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_KLEIO
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
