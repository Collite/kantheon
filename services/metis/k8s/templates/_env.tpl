{{/* metis container env — lifted verbatim from the kustomize base (D2). */}}
{{- define "metis.env" -}}
- name: METIS_GRPC_PORT
  value: {{ .Values.ports.grpc | quote }}
- name: METIS_HTTP_PORT
  value: {{ .Values.ports.http | quote }}
{{- if and .Values.telemetry.enabled .Values.telemetry.otlpHost }}
- name: OTEL_EXPORTER_OTLP_HOST
  value: {{ .Values.telemetry.otlpHost | quote }}
- name: OTEL_EXPORTER_OTLP_GRPC_PORT
  value: {{ .Values.telemetry.otlpGrpcPort | quote }}
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
