{{/*
kallimachos container env — probe/http ports + OTel + DB extraEnv.
ports.http is the probe port (7260); ports.grpc is the HTTP API port (7261).
*/}}
{{- define "kallimachos.env" -}}
- name: KALLIMACHOS_PROBE_PORT
  value: {{ .Values.ports.http | quote }}
- name: KALLIMACHOS_HTTP_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_KALLIMACHOS
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
