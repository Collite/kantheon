{{/* themis-mcp container env — lifted verbatim from the pre-library chart (D1). */}}
{{- define "themis-mcp.env" -}}
- name: OTEL_SERVICE_NAME
  value: {{ .Values.telemetry.serviceName | quote }}
- name: TELEMETRY_ENABLED
  value: {{ .Values.telemetry.enabled | quote }}
{{- if .Values.telemetry.enabled }}
- name: RESOLVER_OTEL_PROTOCOL
  value: {{ .Values.telemetry.protocol | quote }}
{{- if .Values.telemetry.endpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.telemetry.endpoint | quote }}
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
