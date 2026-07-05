{{/* sysifos-bff container env — HTTP port + auth toggle + OTel + upstream extraEnv. */}}
{{- define "sysifos-bff.env" -}}
- name: SYSIFOS_BFF_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
- name: SYSIFOS_AUTH_VERIFY_SIGNATURE
  value: {{ .Values.auth.verifySignature | quote }}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: OTEL_ENABLED_SYSIFOS_BFF
  value: {{ .Values.telemetry.enabled | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
{{- end }}
{{- with .Values.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}
