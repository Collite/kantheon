{{/* midas-core container env — HTTP/MCP ports + Postgres (password via secretKeyRef) +
     OTel + downstream extraEnv. */}}
{{- define "midas-core.env" -}}
- name: MIDAS_CORE_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
- name: MIDAS_CORE_MCP_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: MIDAS_DB_HOST
  value: {{ .Values.db.host | quote }}
- name: MIDAS_DB_NAME
  value: {{ .Values.db.name | quote }}
- name: MIDAS_DB_USER
  value: {{ .Values.db.user | quote }}
- name: MIDAS_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "db.existingSecret is required" .Values.db.existingSecret }}
      key: {{ .Values.db.passwordKey }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_MIDAS_CORE
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
