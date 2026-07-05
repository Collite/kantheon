{{/* midas-excel-loader container env — HTTP port + blob dir + OTel + upstream extraEnv. */}}
{{- define "midas-excel-loader.env" -}}
- name: EXCEL_LOADER_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
- name: EXCEL_LOADER_BLOB_DIR
  value: {{ .Values.blobs.mountPath | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_MIDAS_EXCEL_LOADER
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
